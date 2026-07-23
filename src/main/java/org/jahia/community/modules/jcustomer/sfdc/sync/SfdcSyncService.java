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
        logger.info("[SFDC-Sync] Starting batch sync for site={}", siteKey);
        long updated = 0, skipped = 0, errors = 0;

        try {
            // Load mappings
            List<Map<String, String>> contactMappings = configService.loadMapping("Contact");
            List<Map<String, String>> accountMappings = configService.loadMapping("Account");

            if (contactMappings.isEmpty()) {
                logger.warn("[SFDC-Sync] No Contact mappings configured — nothing to sync");
                configService.saveSyncState("skipped-no-mapping", 0, 0);
                return;
            }

            // Build and execute SOQL
            String soql = buildSoql(contactMappings, accountMappings, deltaBufferMinutes);
            logger.info("[SFDC-Sync] Executing SOQL: {}", soql);

            List<Map<String, Object>> contacts = apiClient.query(soql);
            logger.info("[SFDC-Sync] SF returned {} contact record(s)", contacts.size());

            // Process each contact
            for (Map<String, Object> contact : contacts) {
                try {
                    boolean processed = processContact(contact, contactMappings, accountMappings, siteKey);
                    if (processed) updated++; else skipped++;
                } catch (Exception e) {
                    errors++;
                    logger.error("[SFDC-Sync] Error processing contact {}: {}", contact.get("Id"), e.getMessage());
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
        logger.info("[SFDC-Sync] Login sync triggered for email={}", email);

        try {
            List<Map<String, String>> contactMappings = configService.loadMapping("Contact");
            List<Map<String, String>> accountMappings = configService.loadMapping("Account");

            if (contactMappings.isEmpty()) return;

            // Query only this contact — escape the email before interpolating it into the SOQL
            // string literal (SOQL injection guard; the email originates from a login event).
            String soql = buildSoql(contactMappings, accountMappings, 0) +
                " AND Email = '" + soqlEscape(email) + "'";

            List<Map<String, Object>> contacts = apiClient.query(soql);
            if (contacts.isEmpty()) {
                logger.debug("[SFDC-Sync] No SF contact found for email={}", email);
                return;
            }

            processContact(contacts.get(0), contactMappings, accountMappings, siteKey);
            logger.info("[SFDC-Sync] Login sync complete for email={}", email);

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
