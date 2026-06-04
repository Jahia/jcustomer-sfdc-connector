package org.jahia.community.modules.jcustomer.sfdc.jcustomer;

import org.jahia.modules.jexperience.admin.ContextServerService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds and updates jCustomer (Apache Unomi) profiles.
 *
 * Matching strategy:
 *   1. Primary: search by SFDC_ContactId (stored after first successful sync)
 *   2. Fallback: search by email
 *   3. On first match by email: store ContactId for future runs
 *
 * Updates use the updateUserEfficyPortalData public event (not restricted to thirdparty IPs).
 */
@Component(service = JCustomerProfileService.class, immediate = true)
public class JCustomerProfileService {

    private static final Logger logger = LoggerFactory.getLogger(JCustomerProfileService.class);

    private static final String PROP_SFDC_CONTACT_ID = "SFDC_ContactId";
    private static final String PROP_EMAIL           = "email";
    private static final String EVENT_TYPE           = "updateUserEfficyPortalData";

    private volatile ContextServerService contextServerService;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    public void setContextServerService(ContextServerService s) { this.contextServerService = s; }
    public void unsetContextServerService(ContextServerService s) { this.contextServerService = null; }

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
     * Updates a jCustomer profile with the provided properties.
     * Uses the updateUserEfficyPortalData public event (no thirdparty restriction).
     *
     * @param profileId  jCustomer profile itemId
     * @param properties Map of jCustomer property name → value (strings)
     * @param siteKey    jExperience site key
     */
    public void updateProfile(String profileId, Map<String, Object> properties, String siteKey) throws Exception {
        if (contextServerService == null || !contextServerService.isAvailable(siteKey)) {
            throw new IllegalStateException("[JC-Profile] ContextServerService not available for site " + siteKey);
        }

        // Build "properties.X" prefixed update map
        Map<String, Object> update = new HashMap<>();
        properties.forEach((k, v) -> update.put("properties." + k, v));

        Map<String, Object> eventProps = new HashMap<>();
        eventProps.put("update", update);

        Map<String, Object> event = new HashMap<>();
        event.put("eventType",  EVENT_TYPE);
        event.put("scope",      siteKey);
        event.put("properties", eventProps);

        Map<String, Object> body = new HashMap<>();
        body.put("profileId", profileId);
        body.put("events",    List.of(event));

        contextServerService.executePostRequest(siteKey, "/cxs/eventcollector", body, null, null, Map.class);
        logger.debug("[JC-Profile] Updated profile {} — {} properties", profileId, properties.size());
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
