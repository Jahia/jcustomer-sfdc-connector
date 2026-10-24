package org.jahia.community.modules.jcustomer.sfdc.jcustomer;

import org.jahia.modules.jexperience.admin.ContextServerService;
import org.jahia.modules.jexperience.admin.ContextServerSettings;
import org.jahia.modules.jexperience.admin.JExperienceConfigFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds and updates jCustomer (Apache Unomi) profiles.
 *
 * Matching strategy:
 *   1. Primary: search by SFDC_ContactId (stored after first successful sync)
 *   2. Fallback: search by email
 *   3. On first match by email: store ContactId for future runs
 *
 * Updates send Unomi's standard {@code updateProperties} event to {@code /cxs/eventcollector}. It is a
 * privileged event: jCustomer applies it only when the request carries the third-party key
 * ({@code X-Unomi-Peer}) from an allowed address. The connector reuses the key jExperience is
 * configured with for the site, so it works wherever jExperience itself can send privileged events
 * (Jahia Cloud or on-premise), with no custom jCustomer rule.
 */
@Component(service = JCustomerProfileService.class, immediate = true)
public class JCustomerProfileService {

    private static final Logger logger = LoggerFactory.getLogger(JCustomerProfileService.class);

    private static final String PROP_SFDC_CONTACT_ID = "SFDC_ContactId";
    private static final String PROP_EMAIL           = "email";
    private static final String EVENT_TYPE           = "updateProperties";
    private static final String PEER_HEADER          = "X-Unomi-Peer";
    /** Bits of the {@code updated} value returned by the event collector (Unomi {@code EventService}). */
    private static final int    UNOMI_ERROR           = 1;
    private static final int    UNOMI_PROFILE_UPDATED = 4;

    /** How long the multi-valued property names read from jCustomer are reused, per site. */
    private static final long   PROPERTY_TYPES_TTL_MS = 5 * 60 * 1000L;
    /** Salesforce separates the values of a multi-select picklist with ";". */
    private static final String SF_MULTI_SEPARATOR    = ";";

    private static final class MultiValued {
        final Set<String> names;
        final long loadedAt;
        MultiValued(Set<String> names, long loadedAt) { this.names = names; this.loadedAt = loadedAt; }
    }
    private final Map<String, MultiValued> multiValuedBySite = new ConcurrentHashMap<>();

    private volatile ContextServerService      contextServerService;
    private volatile JExperienceConfigFactory  jexperienceConfig;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    public void setContextServerService(ContextServerService s) { this.contextServerService = s; }
    public void unsetContextServerService(ContextServerService s) { this.contextServerService = null; }

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    public void setJexperienceConfig(JExperienceConfigFactory f) { this.jexperienceConfig = f; }
    public void unsetJexperienceConfig(JExperienceConfigFactory f) { this.jexperienceConfig = null; }

    // ── Profile lookup ────────────────────────────────────────────────────────

    /**
     * Finds the jCustomer profile for a given SF Contact.
     * Returns the profile map (with "itemId" as profile ID), or null if not found.
     *
     * @param contactId  SF Contact.Id
     * @param email      SF Contact.Email
     * @param siteKey    jExperience site key
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> findProfile(String contactId, String email, String siteKey) throws Exception {
        // 1. Try by SFDC_ContactId first (fast path — unambiguous)
        if (contactId != null && !contactId.isBlank()) {
            Map<String, Object> profile = searchBySingleProperty("properties." + PROP_SFDC_CONTACT_ID, contactId, siteKey);
            if (profile != null) return profile;
        }

        // 2. Fallback to email
        if (email != null && !email.isBlank()) {
            Map<String, Object> profile = searchBySingleProperty("properties." + PROP_EMAIL, email.toLowerCase(), siteKey);
            if (profile != null) {
                // Store ContactId for future fast lookups
                if (contactId != null && !contactId.isBlank()) {
                    storeContactId(profile, contactId, siteKey);
                }
                return profile;
            }
        }

        return null;
    }

    // ── Profile update ────────────────────────────────────────────────────────

    /**
     * Gives each value the shape its jCustomer property type expects: a list for a multi-valued property
     * (a Salesforce multi-select picklist "a;b" is split, a single value becomes a one-item list), the
     * value unchanged for a single-valued one. jCustomer stores whatever it is sent: a text written to a
     * multi-valued property stays a text, which jExperience shows as empty and list conditions never match.
     * When the property types cannot be read, a value containing ";" is split, as before.
     *
     * @param properties jCustomer property name → value as read from Salesforce
     * @param siteKey    site whose jExperience settings select the jCustomer instance
     * @return a new map with the shaped values
     */
    public Map<String, Object> shapeForPropertyTypes(Map<String, Object> properties, String siteKey) {
        Set<String> multi = multiValuedProperties(siteKey);
        Map<String, Object> shaped = new HashMap<>();
        properties.forEach((k, v) -> {
            boolean isMulti = multi != null ? multi.contains(k)
                    : v instanceof String && ((String) v).contains(SF_MULTI_SEPARATOR);
            shaped.put(k, isMulti ? asList(v) : v);
        });
        return shaped;
    }

    private static List<String> asList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof Collection) {
            for (Object o : (Collection<?>) v) if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
            return out;
        }
        for (String part : String.valueOf(v).split(SF_MULTI_SEPARATOR)) {
            if (!part.isBlank()) out.add(part.trim());
        }
        return out;
    }

    /**
     * Names of the multi-valued profile properties of the site's jCustomer, read through jExperience and
     * reused for {@value #PROPERTY_TYPES_TTL_MS} ms; {@code null} when they cannot be read.
     */
    @SuppressWarnings("unchecked")
    private Set<String> multiValuedProperties(String siteKey) {
        MultiValued cached = multiValuedBySite.get(siteKey);
        if (cached != null && System.currentTimeMillis() - cached.loadedAt < PROPERTY_TYPES_TTL_MS) {
            return cached.names;
        }
        try {
            List<Object> types = contextServerService.executeGetRequest(
                    siteKey, "/cxs/profiles/properties/targets/profiles", null, null, List.class);
            if (types == null) return cached != null ? cached.names : null;
            Set<String> names = new HashSet<>();
            for (Object t : types) {
                if (!(t instanceof Map)) continue;
                Map<String, Object> type = (Map<String, Object>) t;
                if (Boolean.TRUE.equals(type.get("multivalued")) && type.get("itemId") != null) {
                    names.add(type.get("itemId").toString());
                }
            }
            multiValuedBySite.put(siteKey, new MultiValued(names, System.currentTimeMillis()));
            return names;
        } catch (Exception e) {
            logger.warn("[JC-Profile] Could not read jCustomer property types for site {}: {}", siteKey, e.getMessage());
            return cached != null ? cached.names : null;
        }
    }

    /**
     * Returns the entries of {@code properties} whose value differs from the one the profile holds, so
     * only real changes are sent (and reported). Values compare as text, element by element for lists,
     * because jCustomer may return a number or a single value where Salesforce sent text.
     *
     * @param profile    profile map as returned by a jCustomer search (its {@code properties} are read)
     * @param properties jCustomer property name → value to write
     * @return a new map holding only the changed entries; empty when the profile is already up to date
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> changedProperties(Map<String, Object> profile, Map<String, Object> properties) {
        Object current = profile != null ? profile.get("properties") : null;
        Map<String, Object> stored = current instanceof Map ? (Map<String, Object>) current : Map.of();
        Map<String, Object> changed = new HashMap<>();
        properties.forEach((k, v) -> {
            if (!sameValue(stored.get(k), v)) changed.put(k, v);
        });
        return changed;
    }

    private static boolean sameValue(Object stored, Object value) {
        // A list to write never matches a stored text: a multi-valued property holding a plain text
        // (written before 2.5.0) must be rewritten as a list.
        if (value instanceof Collection && stored != null && !(stored instanceof Collection)) return false;
        return Objects.equals(asText(stored), asText(value));
    }

    private static Object asText(Object v) {
        if (v == null) return null;
        if (v instanceof Collection) {
            List<String> out = new ArrayList<>();
            for (Object o : (Collection<?>) v) out.add(String.valueOf(asText(o)));
            return out.size() == 1 ? out.get(0) : out;
        }
        if (v instanceof Number) {
            try { return new BigDecimal(v.toString()).stripTrailingZeros().toPlainString(); }
            catch (NumberFormatException e) { return v.toString(); }
        }
        return v.toString().trim();
    }

    /**
     * Updates a jCustomer profile with the provided properties, through Unomi's {@code updateProperties}
     * event signed with the site's jExperience third-party key.
     *
     * <p>jCustomer answers {@code updated=0} both when it refuses the event (key, address or allowed
     * events) and when the profile already holds these values. On {@code 0} the profile is read back:
     * values already in place mean "no change", anything else is reported as a refusal.
     *
     * @param profileId  jCustomer profile itemId
     * @param properties Map of jCustomer property name → value
     * @param siteKey    site whose jExperience settings select the jCustomer instance
     * @return {@code true} when jCustomer changed the profile, {@code false} when it already held these values
     * @throws IllegalStateException when jExperience is unavailable, or jCustomer refused the update
     *                               (the message says what to check)
     */
    public boolean updateProfile(String profileId, Map<String, Object> properties, String siteKey) throws Exception {
        if (siteKey == null || siteKey.isBlank()) {
            throw new IllegalStateException("[JC-Profile] No site key resolved - cannot select the jExperience settings");
        }
        if (contextServerService == null || !contextServerService.isAvailable(siteKey)) {
            throw new IllegalStateException("[JC-Profile] ContextServerService not available for site " + siteKey);
        }

        // Build "properties.X" prefixed update map
        Map<String, Object> update = new HashMap<>();
        properties.forEach((k, v) -> update.put("properties." + k, v));

        Map<String, Object> eventProps = new HashMap<>();
        eventProps.put("targetId",   profileId);
        eventProps.put("targetType", "profile");
        eventProps.put("update",     update);

        Map<String, Object> source = new HashMap<>();
        source.put("itemType", "site");
        source.put("scope",    siteKey);
        source.put("itemId",   siteKey);

        Map<String, Object> target = new HashMap<>();
        target.put("itemType", "profile");
        target.put("itemId",   profileId);

        Map<String, Object> event = new HashMap<>();
        event.put("eventType",  EVENT_TYPE);
        event.put("scope",      siteKey);
        event.put("source",     source);
        event.put("target",     target);
        event.put("properties", eventProps);

        Map<String, Object> body = new HashMap<>();
        body.put("profileId", profileId);
        body.put("events",    List.of(event));

        Map<?, ?> response = contextServerService.executePostRequest(
                siteKey, "/cxs/eventcollector", body, null, peerHeaders(siteKey), Map.class);

        int updated = response != null && response.get("updated") instanceof Number
                ? ((Number) response.get("updated")).intValue() : -1;
        if (updated == -1) {
            logger.warn("[JC-Profile] Unexpected event collector response for profile {}: {}", profileId, response);
        } else if ((updated & UNOMI_ERROR) == 0 && (updated & UNOMI_PROFILE_UPDATED) == 0
                && changedProperties(getProfileById(profileId, siteKey), properties).isEmpty()) {
            logger.debug("[JC-Profile] Profile {} already holds these {} properties - no change", profileId, properties.size());
            return false;
        } else if ((updated & UNOMI_PROFILE_UPDATED) == 0 || (updated & UNOMI_ERROR) != 0) {
            throw new IllegalStateException("[JC-Profile] jCustomer did not update profile " + profileId
                    + " (event collector returned updated=" + updated + "). updateProperties is a privileged event:"
                    + " jExperience's jCustomer key must be set for site '" + siteKey + "', and jCustomer must allow"
                    + " that key from this Jahia server's address with updateProperties among its allowed events"
                    + " (UNOMI_THIRDPARTY_PROVIDER1_KEY / _IPADDRESSES / _ALLOWEDEVENTS).");
        }
        logger.debug("[JC-Profile] Updated profile {} - {} properties", profileId, properties.size());
        return true;
    }

    /**
     * Returns the {@code X-Unomi-Peer} header carrying the jCustomer third-party key jExperience is
     * configured with for this site, or {@code null} when it cannot be read (the update is then
     * refused by jCustomer and reported by {@link #updateProfile}).
     */
    private Map<String, String> peerHeaders(String siteKey) {
        JExperienceConfigFactory factory = jexperienceConfig;
        ContextServerSettings settings = factory != null ? factory.getSettings(siteKey) : null;
        String key = settings != null ? settings.getUnomiKey() : null;
        if (key == null || key.isBlank()) {
            logger.warn("[JC-Profile] No jCustomer key in the jExperience settings of site '{}' - "
                    + "jCustomer will refuse profile updates", siteKey);
            return null;
        }
        Map<String, String> headers = new HashMap<>();
        headers.put(PEER_HEADER, key);
        return headers;
    }

    /**
     * One page of the profiles (with an email) changed after {@code fromIso} and up to {@code toIso},
     * oldest first. Pages follow each other by keyset: pass the {@code lastUpdated} and {@code itemId}
     * of the last profile of the previous page as {@code afterTime} / {@code afterId} ({@code null} for
     * the first page). Sorting on both and continuing strictly after the pair never skips nor repeats a
     * profile, even when many share the same second (bulk imports), and has no offset limit. Used by the
     * outbound poll to catch profile changes that emit no Unomi event (console/REST edits).
     *
     * @param fromIso   exclusive lower bound of {@code systemProperties.lastUpdated}
     * @param toIso     inclusive upper bound (the start of the run), so profiles the run itself
     *                  updates are left for the next run instead of being read again
     * @param afterTime {@code lastUpdated} of the last profile of the previous page, or {@code null}
     * @param afterId   {@code itemId} of that profile, or {@code null}
     * @param limit     page size
     */
    public List<Map<String, Object>> searchProfilesChanged(String fromIso, String toIso, String afterTime, String afterId,
                                                           String siteKey, int limit) throws Exception {
        if (contextServerService == null || !contextServerService.isAvailable(siteKey)) {
            throw new IllegalStateException("[JC-Profile] ContextServerService not available for site " + siteKey);
        }
        Map<String, Object> emailParams = new HashMap<>();
        emailParams.put("propertyName", "properties.email");
        emailParams.put("comparisonOperator", "exists");

        // Evict the technical healthCheck profile (pattern from Jahia's Workato Unomi connector).
        Map<String, Object> notHealthCheck = new HashMap<>();
        notHealthCheck.put("propertyName", "itemId");
        notHealthCheck.put("comparisonOperator", "notEquals");
        notHealthCheck.put("propertyValue", "healthCheck");

        Map<String, Object> boolParams = new HashMap<>();
        boolParams.put("operator", "and");
        boolParams.put("subConditions", List.of(
                condition("profilePropertyCondition", emailParams),
                condition("profilePropertyCondition", notHealthCheck),
                dateBound("profilePropertyCondition", "systemProperties.lastUpdated", "lessThanOrEqualTo", toIso),
                keyset("profilePropertyCondition", "systemProperties.lastUpdated", fromIso, afterTime, afterId)));

        return page("/cxs/profiles/search", condition("booleanCondition", boolParams),
                "systemProperties.lastUpdated:asc,itemId:asc", siteKey, limit);
    }

    /**
     * One page of the {@code form} events after {@code fromIso} and up to {@code toIso}, oldest first,
     * paged by keyset on ({@code timeStamp}, {@code itemId}) as {@link #searchProfilesChanged}. Each event
     * carries a {@code profileId}: the poll loads that profile and pushes it. Pattern from Jahia's
     * Workato Unomi connector ({@code /cxs/events/search}).
     */
    public List<Map<String, Object>> searchFormEvents(String fromIso, String toIso, String afterTime, String afterId,
                                                      String siteKey, int limit) throws Exception {
        if (contextServerService == null || !contextServerService.isAvailable(siteKey)) {
            throw new IllegalStateException("[JC-Profile] ContextServerService not available for site " + siteKey);
        }
        Map<String, Object> typeParams = new HashMap<>();
        typeParams.put("eventTypeId", "form");

        Map<String, Object> boolParams = new HashMap<>();
        boolParams.put("operator", "and");
        boolParams.put("subConditions", List.of(
                condition("eventTypeCondition", typeParams),
                dateBound("eventPropertyCondition", "timeStamp", "lessThanOrEqualTo", toIso),
                keyset("eventPropertyCondition", "timeStamp", fromIso, afterTime, afterId)));

        return page("/cxs/events/search", condition("booleanCondition", boolParams),
                "timeStamp:asc,itemId:asc", siteKey, limit);
    }

    /**
     * Items strictly after a keyset position: {@code date > fromIso} for the first page, then
     * {@code date > afterTime OR (date == afterTime AND itemId > afterId)}.
     */
    private static Map<String, Object> keyset(String conditionType, String dateProperty, String fromIso,
                                              String afterTime, String afterId) {
        if (afterTime == null || afterId == null) {
            return dateBound(conditionType, dateProperty, "greaterThan", fromIso);
        }
        Map<String, Object> idParams = new HashMap<>();
        idParams.put("propertyName", "itemId");
        idParams.put("comparisonOperator", "greaterThan");
        idParams.put("propertyValue", afterId);

        Map<String, Object> sameTime = new HashMap<>();
        sameTime.put("operator", "and");
        sameTime.put("subConditions", List.of(
                dateBound(conditionType, dateProperty, "equals", afterTime),
                condition(conditionType, idParams)));

        Map<String, Object> after = new HashMap<>();
        after.put("operator", "or");
        after.put("subConditions", List.of(
                dateBound(conditionType, dateProperty, "greaterThan", afterTime),
                condition("booleanCondition", sameTime)));
        return condition("booleanCondition", after);
    }

    private static Map<String, Object> dateBound(String conditionType, String dateProperty, String operator, String iso) {
        Map<String, Object> params = new HashMap<>();
        params.put("propertyName", dateProperty);
        params.put("comparisonOperator", operator);
        params.put("propertyValueDate", iso);
        return condition(conditionType, params);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> page(String path, Map<String, Object> condition, String sortBy,
                                           String siteKey, int limit) throws Exception {
        Map<String, Object> searchRequest = new HashMap<>();
        searchRequest.put("condition", condition);
        searchRequest.put("offset", 0);
        searchRequest.put("limit", limit);
        searchRequest.put("sortby", sortBy);
        Map<String, Object> result = contextServerService.executePostRequest(
                siteKey, path, searchRequest, null, null, Map.class);
        if (result == null) return List.of();
        List<Map<String, Object>> list = (List<Map<String, Object>>) result.get("list");
        return list != null ? list : List.of();
    }

    /** Loads a single profile by its jCustomer itemId (via search), or {@code null} if not found. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getProfileById(String profileId, String siteKey) throws Exception {
        if (profileId == null || profileId.isBlank()) return null;
        Map<String, Object> idParams = new HashMap<>();
        idParams.put("propertyName", "itemId");
        idParams.put("comparisonOperator", "equals");
        idParams.put("propertyValue", profileId);

        Map<String, Object> searchRequest = new HashMap<>();
        searchRequest.put("condition", condition("profilePropertyCondition", idParams));
        searchRequest.put("offset", 0);
        searchRequest.put("limit", 1);

        Map<String, Object> result = contextServerService.executePostRequest(
                siteKey, "/cxs/profiles/search", searchRequest, null, null, Map.class);
        if (result == null) return null;
        List<Map<String, Object>> list = (List<Map<String, Object>>) result.get("list");
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    private static Map<String, Object> condition(String type, Map<String, Object> parameterValues) {
        Map<String, Object> c = new HashMap<>();
        c.put("type", type);
        c.put("parameterValues", parameterValues);
        return c;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> searchBySingleProperty(String propertyName, String value, String siteKey) throws Exception {
        Map<String, Object> paramValues = new HashMap<>();
        paramValues.put("propertyName",       propertyName);
        paramValues.put("comparisonOperator", "equals");
        paramValues.put("propertyValue",      value);

        Map<String, Object> condition = new HashMap<>();
        condition.put("type",            "profilePropertyCondition");
        condition.put("parameterValues", paramValues);

        Map<String, Object> searchRequest = new HashMap<>();
        searchRequest.put("condition", condition);
        searchRequest.put("offset",    0);
        searchRequest.put("limit",     1);

        Map<String, Object> result = contextServerService.executePostRequest(
            siteKey, "/cxs/profiles/search", searchRequest, null, null, Map.class
        );

        if (result == null) return null;
        List<Map<String, Object>> list = (List<Map<String, Object>>) result.get("list");
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    private void storeContactId(Map<String, Object> profile, String contactId, String siteKey) {
        try {
            String profileId = (String) profile.get("itemId");
            updateProfile(profileId, Map.of(PROP_SFDC_CONTACT_ID, contactId), siteKey);
            logger.debug("[JC-Profile] Stored SFDC_ContactId={} on profile {}", contactId, profileId);
        } catch (Exception e) {
            logger.warn("[JC-Profile] Could not store ContactId {} — {}", contactId, e.getMessage());
        }
    }
}
