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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Core sync orchestration service.
 *
 * Called by:
 *   - SfdcSyncJob (Quartz scheduler — batch sync)
 *   - SfdcLoginEventHandler (OSGi event — single user on login)
 *
 * Algorithm:
 *   1. Build SOQL query including Account relationship fields from mapping
 *   2. Apply delta filter: LastModifiedDate >= lastSyncAt - buffer  (full sync if no lastSyncAt)
 *   3. For each Contact record: match jCustomer profile → apply mapped properties → update
 *   4. Persist sync state (lastSyncAt, counts) to JCR
 */
@Component(service = SfdcSyncService.class, immediate = true)
public class SfdcSyncService {

    private static final Logger logger = LoggerFactory.getLogger(SfdcSyncService.class);

    private SfdcApiClient            apiClient;
    private ConnectorConfigService   configService;
    private JCustomerProfileService  profileService;

    @Reference public void setApiClient(SfdcApiClient s)           { this.apiClient      = s; }
    @Reference public void setConfigService(ConnectorConfigService s) { this.configService = s; }
    @Reference public void setProfileService(JCustomerProfileService s) { this.profileService = s; }

    // ── Batch sync (called from Quartz job) ───────────────────────────────────

    public void runBatchSync(String siteKey, int deltaBufferMinutes) {
        if (siteKey == null || siteKey.isBlank()) {
            logger.error("[SFDC-Sync] Batch sync skipped - no site resolved (see the [SFDC-Site] message)");
            configService.saveSyncState("skipped-no-site", 0, 0);
            return;
        }
        logger.info("[SFDC-Sync] Starting batch sync for site={}", siteKey);
        long updated = 0, skipped = 0, errors = 0;

        try {
            // Load mappings
            List<Map<String, String>> contactMappings = configService.loadMapping("Contact");
            List<Map<String, String>> accountMappings = configService.loadMapping("Account");
            List<Map<String, String>> leadMappings    = configService.loadMapping("Lead");

            if (contactMappings.isEmpty() && leadMappings.isEmpty()) {
                logger.warn("[SFDC-Sync] No Contact or Lead mappings configured — nothing to sync");
                configService.saveSyncState("skipped-no-mapping", 0, 0);
                return;
            }

            // ── Contact sync (Contact + related Account fields) ──
            if (!contactMappings.isEmpty()) {
                String soql = buildSoql(contactMappings, accountMappings, deltaBufferMinutes);
                logger.info("[SFDC-Sync] Executing Contact SOQL: {}", soql);

                List<Map<String, Object>> contacts = apiClient.query(soql);
                logger.info("[SFDC-Sync] SF returned {} contact record(s)", contacts.size());

                for (Map<String, Object> contact : contacts) {
                    try {
                        boolean processed = processContact(contact, contactMappings, accountMappings, siteKey);
                        if (processed) updated++; else skipped++;
                    } catch (Exception e) {
                        errors++;
                        logger.error("[SFDC-Sync] Error processing contact {}: {}", contact.get("Id"), e.getMessage());
                    }
                }
            }

            // ── Lead sync (top-level object, matched to profiles by email) ──
            if (!leadMappings.isEmpty()) {
                String leadSoql = buildLeadSoql(leadMappings, deltaBufferMinutes);
                logger.info("[SFDC-Sync] Executing Lead SOQL: {}", leadSoql);

                List<Map<String, Object>> leads = apiClient.query(leadSoql);
                logger.info("[SFDC-Sync] SF returned {} lead record(s)", leads.size());

                for (Map<String, Object> lead : leads) {
                    try {
                        boolean processed = processLead(lead, leadMappings, siteKey);
                        if (processed) updated++; else skipped++;
                    } catch (Exception e) {
                        errors++;
                        logger.error("[SFDC-Sync] Error processing lead {}: {}", lead.get("Id"), e.getMessage());
                    }
                }
            }

            configService.saveSyncState("success", updated, errors);
            logger.info("[SFDC-Sync] Batch complete — updated={}, skipped={}, errors={}", updated, skipped, errors);

        } catch (Exception e) {
            configService.saveSyncState("error: " + e.getMessage(), updated, errors + 1);
            logger.error("[SFDC-Sync] Batch sync failed", e);
        }
    }

    // ── Single-user sync (called from login event handler) ────────────────────

    public void syncForEmail(String email, String siteKey) {
        if (email == null || email.isBlank()) return;
        if (siteKey == null || siteKey.isBlank()) {
            logger.error("[SFDC-Sync] Login sync skipped for email={} - no site resolved (see the [SFDC-Site] message)", email);
            return;
        }
        logger.info("[SFDC-Sync] Login sync triggered for email={} site={}", email, siteKey);

        try {
            List<Map<String, String>> contactMappings = configService.loadMapping("Contact");
            List<Map<String, String>> accountMappings = configService.loadMapping("Account");
            List<Map<String, String>> leadMappings    = configService.loadMapping("Lead");

            if (contactMappings.isEmpty() && leadMappings.isEmpty()) {
                logger.info("[SFDC-Sync] Login sync skipped for email={} - no Contact or Lead mapping configured", email);
                return;
            }

            String escapedEmail = soqlEscape(email);
            boolean found   = false;   // a Salesforce record exists for this email
            boolean updated = false;   // a jCustomer profile was actually updated

            // Contact first (a converted lead is a Contact) — escape the email before interpolating
            // it into the SOQL string literal (SOQL injection guard; email comes from a login event).
            if (!contactMappings.isEmpty()) {
                String soql = buildSoql(contactMappings, accountMappings, 0) +
                    " AND Email = '" + escapedEmail + "'";
                List<Map<String, Object>> contacts = apiClient.query(soql);
                if (!contacts.isEmpty()) {
                    found = true;
                    updated |= processContact(contacts.get(0), contactMappings, accountMappings, siteKey);
                }
            }

            // Fall back to Lead (a not-yet-converted prospect)
            if (!leadMappings.isEmpty()) {
                String leadSoql = buildLeadSoql(leadMappings, 0) +
                    " AND Email = '" + escapedEmail + "'";
                List<Map<String, Object>> leads = apiClient.query(leadSoql);
                if (!leads.isEmpty()) {
                    found = true;
                    updated |= processLead(leads.get(0), leadMappings, siteKey);
                }
            }

            if (updated) {
                logger.info("[SFDC-Sync] Login sync complete for email={} - jCustomer profile updated", email);
            } else if (found) {
                logger.info("[SFDC-Sync] Login sync for email={}: Salesforce record found but nothing updated - "
                        + "no jCustomer profile with this email, or no mapped field has a value", email);
            } else {
                logger.info("[SFDC-Sync] Login sync for email={}: no Salesforce Contact or Lead with this email", email);
            }

        } catch (Exception e) {
            logger.error("[SFDC-Sync] Login sync failed for email={}: {}", email, e.getMessage());
        }
    }

    // ── SOQL builder ──────────────────────────────────────────────────────────

    private String buildSoql(List<Map<String, String>> contactMappings,
                              List<Map<String, String>> accountMappings,
                              int deltaBufferMinutes) {

        // Track already-added fields to prevent SOQL "duplicate field selected" error
        java.util.Set<String> added = new java.util.LinkedHashSet<>();
        added.add("Id");
        added.add("Email");

        // Add Contact mapped fields (skip duplicates, skip Account.* here)
        for (Map<String, String> m : contactMappings) {
            String sfField = m.get("sfField");
            if (sfField != null && !sfField.startsWith("Account.")) {
                added.add(sfField);  // Set ignores duplicates automatically
            }
        }

        // Add Account relationship fields (always prefix Account.)
        for (Map<String, String> m : accountMappings) {
            String sfField = m.get("sfField");
            if (sfField != null) {
                String fullField = sfField.startsWith("Account.") ? sfField : "Account." + sfField;
                added.add(fullField);
            }
        }

        // Build SELECT from the de-duplicated set
        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(String.join(", ", added));
        sb.append(" FROM Contact WHERE Email != null");

        // Delta filter
        if (deltaBufferMinutes > 0) {
            String lastSync = configService.getLastSyncAt();
            if (lastSync != null) {
                Instant cutoff = Instant.parse(lastSync).minus(deltaBufferMinutes, ChronoUnit.MINUTES);
                sb.append(" AND LastModifiedDate >= ").append(cutoff.toString());
            }
        }

        return sb.toString();
    }

    /**
     * Builds the SOQL for the Lead object. Leads are a top-level object matched to jCustomer
     * profiles by email; converted leads are excluded (they already exist as Contacts).
     */
    private String buildLeadSoql(List<Map<String, String>> leadMappings, int deltaBufferMinutes) {
        java.util.Set<String> added = new java.util.LinkedHashSet<>();
        added.add("Id");
        added.add("Email");

        for (Map<String, String> m : leadMappings) {
            String sfField = m.get("sfField");
            if (sfField != null && !sfField.isBlank()) {
                added.add(sfField);
            }
        }

        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(String.join(", ", added));
        sb.append(" FROM Lead WHERE IsConverted = false AND Email != null");

        if (deltaBufferMinutes > 0) {
            String lastSync = configService.getLastSyncAt();
            if (lastSync != null) {
                Instant cutoff = Instant.parse(lastSync).minus(deltaBufferMinutes, ChronoUnit.MINUTES);
                sb.append(" AND LastModifiedDate >= ").append(cutoff.toString());
            }
        }

        return sb.toString();
    }

    // ── Per-record processing ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private boolean processContact(Map<String, Object> contact,
                                    List<Map<String, String>> contactMappings,
                                    List<Map<String, String>> accountMappings,
                                    String siteKey) throws Exception {

        String contactId = str(contact, "Id");
        String email     = str(contact, "Email");

        if (email == null || email.isBlank()) {
            logger.debug("[SFDC-Sync] Skipping contact {} — no email", contactId);
            return false;
        }

        // Find matching jCustomer profile
        Map<String, Object> profile = profileService.findProfile(contactId, email, siteKey);
        if (profile == null) {
            logger.debug("[SFDC-Sync] No jCustomer profile for email={} — skipping (user must login first)", email);
            return false;
        }

        String profileId = (String) profile.get("itemId");

        // Build property map from mappings
        Map<String, Object> properties = new HashMap<>();

        // Contact fields
        for (Map<String, String> m : contactMappings) {
            String sfField = m.get("sfField");
            String jcProp  = m.get("jcProperty");
            if (sfField == null || jcProp == null || sfField.startsWith("Account.")) continue;
            Object value = contact.get(sfField);
            if (value != null) properties.put(jcProp, toJcValue(value));
        }

        // Account fields (nested in the "Account" sub-object)
        Object accountObj = contact.get("Account");
        if (accountObj instanceof Map) {
            Map<String, Object> account = (Map<String, Object>) accountObj;
            for (Map<String, String> m : accountMappings) {
                String sfField = m.get("sfField");
                String jcProp  = m.get("jcProperty");
                if (sfField == null || jcProp == null) continue;
                String accountField = sfField.startsWith("Account.") ? sfField.substring(8) : sfField;
                Object value = account.get(accountField);
                if (value != null) properties.put(jcProp, toJcValue(value));
            }
        }

        if (properties.isEmpty()) {
            logger.debug("[SFDC-Sync] No properties to update for profile {}", profileId);
            return false;
        }

        profileService.updateProfile(profileId, properties, siteKey);
        logger.debug("[SFDC-Sync] Updated profile {} ({}) — {} properties", profileId, email, properties.size());
        return true;
    }

    /**
     * Processes a single Lead: matches the jCustomer profile by email and applies the Lead field
     * mapping. Unlike Contact, a Lead has no Account relationship and carries no stored ContactId,
     * so matching is by email only.
     */
    private boolean processLead(Map<String, Object> lead,
                                 List<Map<String, String>> leadMappings,
                                 String siteKey) throws Exception {

        String email = str(lead, "Email");
        if (email == null || email.isBlank()) {
            return false;
        }

        Map<String, Object> profile = profileService.findProfile(null, email, siteKey);
        if (profile == null) {
            logger.debug("[SFDC-Sync] No jCustomer profile for lead email={} — skipping", email);
            return false;
        }

        String profileId = (String) profile.get("itemId");

        Map<String, Object> properties = new HashMap<>();
        for (Map<String, String> m : leadMappings) {
            String sfField = m.get("sfField");
            String jcProp  = m.get("jcProperty");
            if (sfField == null || jcProp == null) continue;
            Object value = lead.get(sfField);
            if (value != null) properties.put(jcProp, toJcValue(value));
        }

        if (properties.isEmpty()) {
            logger.debug("[SFDC-Sync] No properties to update for lead profile {}", profileId);
            return false;
        }

        profileService.updateProfile(profileId, properties, siteKey);
        logger.debug("[SFDC-Sync] Updated profile {} from lead ({}) — {} properties", profileId, email, properties.size());
        return true;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    /**
     * Escapes a value for safe inclusion in a SOQL single-quoted string literal.
     * Backslash is escaped first (so it cannot cancel a following escape), then the quote
     * characters Salesforce requires escaped. Prevents SOQL injection / query breakage.
     */
    private static String soqlEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"");
    }

    /**
     * Converts a Salesforce field value to the appropriate jCustomer value.
     *
     * SF multi-select picklists are returned as semicolon-separated strings
     * (e.g. "Syntec;Métallurgie"). jCustomer multi-valued properties expect
     * a List<String>. We split on ";" whenever the value contains one.
     *
     * Single-value fields are kept as plain strings.
     */
    private static Object toJcValue(Object sfValue) {
        if (sfValue == null) return null;

        // If SF already sent a list (rare but possible in bulk API), pass it through
        if (sfValue instanceof List) return sfValue;

        String str = sfValue.toString().trim();
        if (str.isBlank()) return str;

        // SF multi-select picklist separator is ";"
        if (str.contains(";")) {
            List<String> values = new ArrayList<>();
            for (String part : str.split(";")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) values.add(trimmed);
            }
            return values;
        }

        return str;
    }
}
