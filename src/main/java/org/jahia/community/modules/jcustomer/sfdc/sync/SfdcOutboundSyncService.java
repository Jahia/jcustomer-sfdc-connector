package org.jahia.community.modules.jcustomer.sfdc.sync;

import org.jahia.community.modules.jcustomer.sfdc.client.SfdcApiClient;
import org.jahia.community.modules.jcustomer.sfdc.config.ConnectorConfigService;
import org.jahia.community.modules.jcustomer.sfdc.jcustomer.JCustomerProfileService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Outbound sync orchestration (jCustomer → Salesforce).
 *
 * <p>Pushes a jCustomer profile into a Salesforce <b>Lead</b>:
 * <ol>
 *   <li>Apply the outbound mapping (jCustomer property → Lead field).</li>
 *   <li>Resolve the target Lead — by the {@code SFDC_LeadId} stored on the profile, else by email.</li>
 *   <li><b>Update</b> the Lead if found, otherwise <b>create</b> one (create requires Salesforce's
 *       mandatory {@code LastName} + {@code Company} — skipped with a warning if unavailable).</li>
 *   <li>Write the resulting {@code SFDC_LeadId} back onto the profile so later pushes update the
 *       same Lead instead of creating duplicates.</li>
 * </ol>
 *
 * <p>Targets Lead and/or Contact (whichever has an outbound mapping). Converted leads are excluded
 * from Lead matching (see {@link SfdcApiClient#findSObjectIdByEmail}).
 */
@Component(service = SfdcOutboundSyncService.class, immediate = true)
public class SfdcOutboundSyncService {

    private static final Logger logger = LoggerFactory.getLogger(SfdcOutboundSyncService.class);

    private static final String PROP_EMAIL = "email";
    // Fields Salesforce requires to CREATE each object (updates don't need them).
    private static final Map<String, String[]> REQUIRED_FOR_CREATE = Map.of(
            "Lead",    new String[]{"LastName", "Company"},
            "Contact", new String[]{"LastName"});

    private SfdcApiClient           apiClient;
    private ConnectorConfigService  configService;
    private JCustomerProfileService profileService;

    @Reference public void setApiClient(SfdcApiClient s)            { this.apiClient      = s; }
    @Reference public void setConfigService(ConnectorConfigService s) { this.configService = s; }
    @Reference public void setProfileService(JCustomerProfileService s) { this.profileService = s; }

    /**
     * Finds the jCustomer profile matching {@code email} and pushes it to Salesforce as a Lead.
     * Returns {@code true} if a Lead was created or updated.
     */
    public boolean pushForEmail(String email, String siteKey) {
        if (email == null || email.isBlank()) return false;
        if (siteKey == null || siteKey.isBlank()) {
            logger.error("[SFDC-Outbound] Push skipped for email={} - no site resolved (see the [SFDC-Site] message)", email);
            return false;
        }
        logger.info("[SFDC-Outbound] Push triggered for email={}", email);
        try {
            Map<String, Object> profile = profileService.findProfile(null, email, siteKey);
            if (profile == null) {
                logger.info("[SFDC-Outbound] No jCustomer profile for email={} — nothing to push", email);
                configService.saveOutboundSyncState("no-profile", 0, 0, 0);
                return false;
            }
            boolean created = pushProfile(profile, siteKey);
            configService.saveOutboundSyncState("success", created ? 0 : 1, created ? 1 : 0, 0);
            return true;
        } catch (Exception e) {
            logger.error("[SFDC-Outbound] Push failed for email={}: {}", email, e.getMessage());
            configService.saveOutboundSyncState("error: " + e.getMessage(), 0, 0, 1);
            return false;
        }
    }

    /**
     * Reconcile sweep: pushes every jCustomer profile changed since the last run (with an email) to
     * Salesforce. Catches profile edits that emit no Unomi event (console/REST/batch), which the
     * event webhook cannot see. Idempotent — updates the Lead matched by the stored {@code
     * SFDC_LeadId} / email, so re-processing a profile is a harmless no-op update.
     *
     * @param bufferMinutes clock-skew buffer subtracted from the last-run timestamp
     * @param maxProfiles   safety cap on profiles processed per run
     */
    private static final Pattern UUID_RE =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    public void runReconcile(String siteKey, int bufferMinutes, int maxProfiles) {
        if (siteKey == null || siteKey.isBlank()) {
            logger.error("[SFDC-Outbound] Reconcile skipped - no site resolved (see the [SFDC-Site] message)");
            return;
        }
        long updated = 0, created = 0, errors = 0;
        Instant runStart = Instant.now();
        try {
            Map<String, String> triggers = configService.loadOutboundTriggers();
            boolean doProfiles = !"false".equalsIgnoreCase(triggers.getOrDefault("onProfileUpdate", "true"));
            boolean doForms    = !"false".equalsIgnoreCase(triggers.getOrDefault("onFormSubmission", "true"));

            String last = configService.getOutboundReconcileAt();
            Instant cutoff = (last != null)
                    ? Instant.parse(last).minus(bufferMinutes, ChronoUnit.MINUTES)
                    : runStart.minus(24, ChronoUnit.HOURS); // first run: look back a day
            String cutoffIso = cutoff.toString();
            logger.info("[SFDC-Outbound] Poll starting site={} since={} (profiles={}, forms={})", siteKey, cutoff, doProfiles, doForms);

            Set<String> pushed = new HashSet<>();

            // 1) Profiles changed since cutoff (covers profile updates, incl. form-driven property changes).
            if (doProfiles) {
                for (Map<String, Object> profile : profileService.searchProfilesModifiedSince(cutoffIso, siteKey, maxProfiles)) {
                    String id = asString(profile.get("itemId"));
                    if (id == null || !UUID_RE.matcher(id).matches()) continue; // skip technical / merged profiles
                    try {
                        if (pushProfile(profile, siteKey)) created++; else updated++;
                        pushed.add(id);
                    } catch (Exception e) {
                        errors++;
                        logger.error("[SFDC-Outbound] Poll error for profile {}: {}", id, e.getMessage());
                    }
                }
            }

            // 2) Form events since cutoff — push each event's profile once (not already pushed above).
            if (doForms) {
                for (Map<String, Object> event : profileService.searchFormEventsSince(cutoffIso, siteKey, maxProfiles)) {
                    String pid = asString(event.get("profileId"));
                    if (pid == null || !UUID_RE.matcher(pid).matches() || pushed.contains(pid)) continue;
                    try {
                        Map<String, Object> profile = profileService.getProfileById(pid, siteKey);
                        if (profile == null) continue;
                        if (pushProfile(profile, siteKey)) created++; else updated++;
                        pushed.add(pid);
                    } catch (Exception e) {
                        errors++;
                        logger.error("[SFDC-Outbound] Poll error for form-event profile {}: {}", pid, e.getMessage());
                    }
                }
            }

            // Advance the watermark to the run start so edits during/after the run are caught next time.
            configService.setOutboundReconcileAt(runStart.toString());
            configService.saveOutboundSyncState("poll: " + pushed.size() + " profiles", updated, created, errors);
            logger.info("[SFDC-Outbound] Poll complete — profiles={}, created={}, updated={}, errors={}", pushed.size(), created, updated, errors);
        } catch (Exception e) {
            configService.saveOutboundSyncState("poll-error: " + e.getMessage(), updated, created, errors + 1);
            logger.error("[SFDC-Outbound] Poll failed", e);
        }
    }

    /**
     * Pushes an already-resolved jCustomer profile to the single configured Salesforce target
     * object ({@code outboundObject} = Lead OR Contact). Returns {@code true} if a new record was
     * created.
     */
    @SuppressWarnings("unchecked")
    public boolean pushProfile(Map<String, Object> profile, String siteKey) throws Exception {
        String profileId = asString(profile.get("itemId"));
        Map<String, Object> props = profile.get("properties") instanceof Map
                ? (Map<String, Object>) profile.get("properties") : new HashMap<>();
        String email = asString(props.get(PROP_EMAIL));

        String object = configService.loadOutboundTriggers().getOrDefault("outboundObject", "Lead");
        if (!"Lead".equals(object) && !"Contact".equals(object)) object = "Lead";

        List<Map<String, String>> mapping = configService.loadOutboundMapping(object);
        if (mapping.isEmpty()) {
            logger.warn("[SFDC-Outbound] No outbound mapping configured for target {} — nothing to push", object);
            return false;
        }
        return pushToObject(object, profile, props, profileId, email, mapping, siteKey);
    }

    /** Pushes the profile to one Salesforce object type. Returns {@code true} if a record was created. */
    private boolean pushToObject(String object, Map<String, Object> profile, Map<String, Object> props,
                                 String profileId, String email, List<Map<String, String>> mapping,
                                 String siteKey) throws Exception {
        // Build the field set from the mapping
        Map<String, Object> fields = new HashMap<>();
        for (Map<String, String> m : mapping) {
            String jcProp  = m.get("jcProperty");
            String sfField = m.get("sfField");
            if (jcProp == null || sfField == null || jcProp.isBlank() || sfField.isBlank()) continue;
            Object value = props.get(jcProp);
            if (value != null) fields.put(sfField, toSfValue(value));
        }
        if (fields.isEmpty()) {
            logger.debug("[SFDC-Outbound] Profile {} has no mapped {} values", profileId, object);
            return false;
        }

        String idProp = "SFDC_" + object + "Id"; // SFDC_LeadId / SFDC_ContactId (SFDC_ContactId also set by inbound)

        // Resolve the target record: stored id first, then email lookup
        String recordId = asString(props.get(idProp));
        if (recordId == null || recordId.isBlank()) {
            recordId = apiClient.findSObjectIdByEmail(object, email);
        }

        boolean created = false;
        if (recordId != null && !recordId.isBlank()) {
            apiClient.updateSObject(object, recordId, fields);
            logger.info("[SFDC-Outbound] Updated {} {} from profile {} ({} fields)", object, recordId, profileId, fields.size());
        } else {
            // Salesforce requires certain fields to CREATE each object.
            for (String req : REQUIRED_FOR_CREATE.getOrDefault(object, new String[0])) {
                if (isBlank(fields.get(req))) {
                    logger.warn("[SFDC-Outbound] Cannot create {} for email={} — Salesforce requires {}, "
                            + "not mapped/available; skipping (updates would still work).", object, email, req);
                    return false;
                }
            }
            if (email != null && !email.isBlank() && !fields.containsKey("Email")) {
                fields.put("Email", email);
            }
            recordId = apiClient.createSObject(object, fields);
            created = true;
            logger.info("[SFDC-Outbound] Created {} {} from profile {} ({} fields)", object, recordId, profileId, fields.size());
        }

        // Persist the record id back onto the profile for future idempotent pushes
        if (recordId != null && !recordId.isBlank() && profileId != null
                && !recordId.equals(asString(props.get(idProp)))) {
            try {
                profileService.updateProfile(profileId, Map.of(idProp, recordId), siteKey);
            } catch (Exception e) {
                // The Salesforce write succeeded; only the id writeback failed. Matching by email still
                // works on the next push, so report it without failing this one.
                logger.warn("[SFDC-Outbound] {} {} saved, but writing {} back to profile {} failed: {}",
                        object, recordId, idProp, profileId, e.getMessage());
            }
        }
        return created;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Converts a jCustomer value to a Salesforce-ready value (multi-value → ';'-joined picklist). */
    private static Object toSfValue(Object value) {
        if (value instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (List<?>) value) {
                if (o == null) continue;
                if (sb.length() > 0) sb.append(';');
                sb.append(o);
            }
            return sb.toString();
        }
        return value.toString();
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static boolean isBlank(Object o) {
        return o == null || o.toString().isBlank();
    }
}
