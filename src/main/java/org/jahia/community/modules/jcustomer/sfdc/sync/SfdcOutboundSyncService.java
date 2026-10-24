package org.jahia.community.modules.jcustomer.sfdc.sync;

import org.jahia.community.modules.jcustomer.sfdc.client.SfdcApiClient;
import org.jahia.community.modules.jcustomer.sfdc.config.ConnectorConfigService;
import org.jahia.community.modules.jcustomer.sfdc.history.SyncHistoryService;
import org.jahia.community.modules.jcustomer.sfdc.history.SyncHistoryService.Outcome;
import org.jahia.community.modules.jcustomer.sfdc.history.SyncHistoryService.Run;
import org.jahia.community.modules.jcustomer.sfdc.history.SyncHistoryService.Trigger;
import org.jahia.community.modules.jcustomer.sfdc.jcustomer.JCustomerProfileService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private volatile SyncHistoryService history;
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    public void setHistory(SyncHistoryService h) { this.history = h; }
    public void unsetHistory(SyncHistoryService h) { this.history = null; }

    /** Starts recording an outbound run, or {@code null} when the history service is unavailable. */
    private Run startRun(Trigger trigger) {
        SyncHistoryService h = history;
        return h != null ? h.start(SyncHistoryService.Direction.OUTBOUND, trigger) : null;
    }

    private static void rec(Run run, Outcome outcome, String who, String profileId, String sfObject,
                            String sfId, Map<String, ?> fields, String message) {
        if (run != null) run.record(outcome, who, profileId, sfObject, sfId, fields, message);
    }

    private static void finish(Run run, String status) {
        if (run != null) run.finish(status);
    }

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
        Run run = startRun(Trigger.PUSH);
        try {
            Map<String, Object> profile = profileService.findProfile(null, email, siteKey);
            if (profile == null) {
                logger.info("[SFDC-Outbound] No jCustomer profile for email={} — nothing to push", email);
                configService.saveOutboundSyncState("no-profile", 0, 0, 0);
                rec(run, Outcome.SKIPPED, email, null, null, null, null, "no jCustomer profile with this email");
                finish(run, "skipped: no jCustomer profile");
                return false;
            }
            PushResult result = pushProfile(profile, siteKey, run);
            if (result == PushResult.SKIPPED) {
                configService.saveOutboundSyncState("skipped: nothing sent (no mapping, no mapped value, or cannot create)", 0, 0, 0);
                finish(run, "skipped");
                return false;
            }
            boolean created = result == PushResult.CREATED;
            configService.saveOutboundSyncState("success", created ? 0 : 1, created ? 1 : 0, 0);
            finish(run, "success");
            return true;
        } catch (Exception e) {
            logger.error("[SFDC-Outbound] Push failed for email={}: {}", email, e.getMessage());
            configService.saveOutboundSyncState("error: " + e.getMessage(), 0, 0, 1);
            rec(run, Outcome.ERROR, email, null, null, null, null, e.getMessage());
            finish(run, "error: " + e.getMessage());
            return false;
        }
    }

    private static final Pattern UUID_RE =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Profiles or events read from jCustomer per request. */
    static final int PAGE_SIZE = 200;
    /** A profile whose push fails is tried this many times in all (the first push included), then dropped. */
    static final int MAX_ATTEMPTS = 5;
    /** At most this many profiles wait for a retry; beyond it the oldest are dropped. */
    static final int MAX_RETRIES = 1000;

    /** One poll at a time: the scheduled poll and "Sync now" share the watermark and the retry list. */
    private final AtomicBoolean polling = new AtomicBoolean();

    /** Counts of one poll. */
    private static final class Tally {
        long created, updated, skipped, errors;
        final Set<String> attempted = new HashSet<>();
    }

    /**
     * Poll: pushes every jCustomer profile changed since the last run (with an email), and the profile
     * of every form submitted since then, to Salesforce; then retries the profiles whose push failed
     * before. Catches profile edits that emit no Unomi event (console/REST/batch). Idempotent: updates
     * the record matched by the stored id / email, so pushing a profile twice is a harmless update.
     *
     * <p>Every change since the last run is read, page by page ({@link #PAGE_SIZE}), up to the start of
     * this run: nothing is left behind when more changed than a page holds. The watermark then moves to
     * the start of this run. A profile whose push fails is kept in a retry list and pushed again by the
     * next polls, {@link #MAX_ATTEMPTS} times in all. Only one poll runs at a time.
     *
     * @param bufferMinutes clock-skew buffer subtracted from the last-run timestamp
     */
    public void runReconcile(String siteKey, int bufferMinutes, Trigger trigger) {
        Run run = startRun(trigger);
        if (!polling.compareAndSet(false, true)) {
            logger.info("[SFDC-Outbound] Poll skipped - the previous poll is still running");
            finish(run, "skipped: the previous poll is still running");
            return;
        }
        try {
            reconcile(siteKey, bufferMinutes, run);
        } finally {
            polling.set(false);
        }
    }

    private void reconcile(String siteKey, int bufferMinutes, Run run) {
        if (siteKey == null || siteKey.isBlank()) {
            logger.error("[SFDC-Outbound] Reconcile skipped - no site resolved (see the [SFDC-Site] message)");
            finish(run, "error: no site resolved - set jcustomer.site.key");
            return;
        }
        Tally tally = new Tally();
        Instant runStart = Instant.now().truncatedTo(ChronoUnit.MILLIS);   // jCustomer dates are in ms at most
        try {
            Map<String, String> triggers = configService.loadOutboundTriggers();
            boolean doProfiles = "true".equalsIgnoreCase(triggers.get("onProfileUpdate"));
            boolean doForms    = "true".equalsIgnoreCase(triggers.get("onFormSubmission"));
            if (!doProfiles && !doForms) {
                logger.info("[SFDC-Outbound] Poll skipped - neither Profile updates nor Form submissions is on");
                configService.saveOutboundSyncState("skipped: no trigger on", 0, 0, 0);
                finish(run, "skipped: neither Profile updates nor Form submissions is on");
                return;
            }

            Map<String, String> conn = configService.loadConnection();
            String instanceUrl = conn.get("instanceUrl");
            if (!"true".equalsIgnoreCase(conn.get("enabled")) || instanceUrl == null || instanceUrl.isBlank()) {
                logger.debug("[SFDC-Outbound] Poll skipped - Salesforce connection not configured or disabled");
                configService.saveOutboundSyncState("skipped: Salesforce connection not configured or disabled", 0, 0, 0);
                finish(run, "skipped: Salesforce connection not configured or disabled");
                return;
            }

            String last = configService.getOutboundReconcileAt();
            if (last == null) {
                // A trigger was just turned on: push the changes made from now on, never a backfill.
                configService.setOutboundReconcileAt(runStart.toString());
                configService.saveOutboundSyncState("started: pushing changes from " + runStart, 0, 0, 0);
                logger.info("[SFDC-Outbound] First poll - pushing jCustomer changes made from {} on", runStart);
                finish(run, "started: pushing changes made from " + runStart);
                return;
            }
            String fromIso = Instant.parse(last).minus(bufferMinutes, ChronoUnit.MINUTES).toString();
            String toIso = runStart.toString();
            logger.info("[SFDC-Outbound] Poll starting site={} changes in ({}, {}] (profiles={}, forms={})",
                    siteKey, fromIso, toIso, doProfiles, doForms);

            Map<String, Integer> retries = configService.loadOutboundRetries();

            // 1) Profiles changed in the window (covers profile updates, incl. form-driven property changes).
            if (doProfiles) {
                String afterTime = null, afterId = null;
                List<Map<String, Object>> page;
                do {
                    page = profileService.searchProfilesChanged(fromIso, toIso, afterTime, afterId, siteKey, PAGE_SIZE);
                    for (Map<String, Object> profile : page) {
                        String id = asString(profile.get("itemId"));
                        if (id == null || !UUID_RE.matcher(id).matches()) continue; // skip technical / merged profiles
                        push(profile, id, siteKey, run, tally, retries);
                    }
                    if (!page.isEmpty()) {
                        Map<String, Object> lastProfile = page.get(page.size() - 1);
                        afterTime = asString(systemProperty(lastProfile, "lastUpdated"));
                        afterId = asString(lastProfile.get("itemId"));
                    }
                } while (page.size() == PAGE_SIZE && afterTime != null && afterId != null);
            }

            // 2) Form events in the window: push each event's profile once (not already pushed above).
            if (doForms) {
                String afterTime = null, afterId = null;
                List<Map<String, Object>> page;
                do {
                    page = profileService.searchFormEvents(fromIso, toIso, afterTime, afterId, siteKey, PAGE_SIZE);
                    for (Map<String, Object> event : page) {
                        String pid = asString(event.get("profileId"));
                        if (pid == null || !UUID_RE.matcher(pid).matches() || tally.attempted.contains(pid)) continue;
                        Map<String, Object> profile = profileService.getProfileById(pid, siteKey);
                        if (profile != null) push(profile, pid, siteKey, run, tally, retries);
                    }
                    if (!page.isEmpty()) {
                        Map<String, Object> lastEvent = page.get(page.size() - 1);
                        afterTime = asString(lastEvent.get("timeStamp"));
                        afterId = asString(lastEvent.get("itemId"));
                    }
                } while (page.size() == PAGE_SIZE && afterTime != null && afterId != null);
            }

            // 3) Profiles whose push failed in an earlier poll, not pushed again above.
            for (String pid : new ArrayList<>(retries.keySet())) {
                if (tally.attempted.contains(pid)) continue;
                Map<String, Object> profile = profileService.getProfileById(pid, siteKey);
                if (profile == null) {
                    retries.remove(pid);                       // deleted or merged in jCustomer
                    continue;
                }
                push(profile, pid, siteKey, run, tally, retries);
            }
            dropExhausted(retries, run);
            configService.saveOutboundRetries(retries);

            // Every change up to the start of this run was read: move the watermark there.
            configService.setOutboundReconcileAt(runStart.toString());
            configService.saveOutboundSyncState("poll: " + tally.attempted.size() + " profiles, " + tally.skipped
                    + " skipped, " + retries.size() + " to retry", tally.updated, tally.created, tally.errors);
            logger.info("[SFDC-Outbound] Poll complete - profiles={}, created={}, updated={}, skipped={}, errors={}, to retry={}",
                    tally.attempted.size(), tally.created, tally.updated, tally.skipped, tally.errors, retries.size());
            finish(run, "success");
        } catch (Exception e) {
            // The watermark stays where it was: the next poll reads this window again.
            configService.saveOutboundSyncState("poll-error: " + e.getMessage(), tally.updated, tally.created, tally.errors + 1);
            logger.error("[SFDC-Outbound] Poll failed", e);
            finish(run, "error: " + e.getMessage());
        }
    }

    /**
     * Pushes one profile of the poll. A success clears it from the retry list; a failure adds it, or
     * counts one more attempt.
     */
    private void push(Map<String, Object> profile, String profileId, String siteKey, Run run, Tally tally,
                      Map<String, Integer> retries) {
        tally.attempted.add(profileId);
        try {
            switch (pushProfile(profile, siteKey, run)) {
                case CREATED: tally.created++; break;
                case UPDATED: tally.updated++; break;
                default:      tally.skipped++; break;
            }
            retries.remove(profileId);
        } catch (Exception e) {
            tally.errors++;
            int attempts = retries.getOrDefault(profileId, 0) + 1;
            retries.remove(profileId);                         // re-inserted last: the list stays oldest first
            retries.put(profileId, attempts);
            String message = e.getMessage() + (attempts < MAX_ATTEMPTS
                    ? " (attempt " + attempts + " of " + MAX_ATTEMPTS + ", retried by the next poll)"
                    : " (attempt " + attempts + " of " + MAX_ATTEMPTS + ", not retried)");
            logger.error("[SFDC-Outbound] Push failed for profile {}: {}", profileId, message);
            rec(run, Outcome.ERROR, emailOf(profile), profileId, null, null, null, message);
        }
    }

    /** Drops profiles that used all their attempts, and the oldest beyond {@link #MAX_RETRIES}. */
    private static void dropExhausted(Map<String, Integer> retries, Run run) {
        retries.entrySet().removeIf(e -> {
            if (e.getValue() < MAX_ATTEMPTS) return false;
            logger.error("[SFDC-Outbound] Profile {} not pushed after {} attempts - dropped from the retry list",
                    e.getKey(), MAX_ATTEMPTS);
            return true;
        });
        int excess = retries.size() - MAX_RETRIES;
        if (excess > 0) {
            List<String> oldest = new ArrayList<>(retries.keySet()).subList(0, excess);
            logger.error("[SFDC-Outbound] Retry list full ({} profiles): the {} oldest are dropped", retries.size(), excess);
            for (String pid : oldest) {
                retries.remove(pid);
                rec(run, Outcome.ERROR, null, pid, null, null, null, "dropped from the retry list: more than "
                        + MAX_RETRIES + " profiles waiting");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object systemProperty(Map<String, Object> item, String name) {
        Object sp = item.get("systemProperties");
        return sp instanceof Map ? ((Map<String, Object>) sp).get(name) : null;
    }

    /** Outcome of pushing one profile: a Salesforce record was created or updated, or nothing was sent. */
    public enum PushResult { CREATED, UPDATED, SKIPPED }

    /**
     * Pushes an already-resolved jCustomer profile to the single configured Salesforce target
     * object ({@code outboundObject} = Lead OR Contact).
     *
     * @return {@link PushResult#SKIPPED} when nothing was sent (no mapping, no mapped value, or a
     *         record that cannot be created), else whether the record was created or updated
     */
    @SuppressWarnings("unchecked")
    public PushResult pushProfile(Map<String, Object> profile, String siteKey) throws Exception {
        return pushProfile(profile, siteKey, null);
    }

    /** As {@link #pushProfile(Map, String)}, recording the outcome in {@code run} (may be null). */
    @SuppressWarnings("unchecked")
    public PushResult pushProfile(Map<String, Object> profile, String siteKey, Run run) throws Exception {
        String profileId = asString(profile.get("itemId"));
        Map<String, Object> props = profile.get("properties") instanceof Map
                ? (Map<String, Object>) profile.get("properties") : new HashMap<>();
        String email = asString(props.get(PROP_EMAIL));

        String object = configService.loadOutboundTriggers().getOrDefault("outboundObject", "Lead");
        if (!"Lead".equals(object) && !"Contact".equals(object)) object = "Lead";

        List<Map<String, String>> mapping = configService.loadOutboundMapping(object);
        if (mapping.isEmpty()) {
            logger.warn("[SFDC-Outbound] No outbound mapping configured for target {} - nothing to push", object);
            rec(run, Outcome.SKIPPED, email, profileId, object, null, null, "no outbound mapping configured for " + object);
            return PushResult.SKIPPED;
        }
        return pushToObject(object, profile, props, profileId, email, mapping, siteKey, run);
    }

    /** Pushes the profile to one Salesforce object type. */
    private PushResult pushToObject(String object, Map<String, Object> profile, Map<String, Object> props,
                                 String profileId, String email, List<Map<String, String>> mapping,
                                 String siteKey, Run run) throws Exception {
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
            rec(run, Outcome.SKIPPED, email, profileId, object, null, null, "no mapped jCustomer property has a value");
            return PushResult.SKIPPED;
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
                    rec(run, Outcome.SKIPPED, email, profileId, object, null, fields,
                            "Salesforce requires " + req + " to create a " + object + " (not mapped or empty)");
                    return PushResult.SKIPPED;
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
        String note = null;
        if (recordId != null && !recordId.isBlank() && profileId != null
                && !recordId.equals(asString(props.get(idProp)))) {
            try {
                profileService.updateProfile(profileId, Map.of(idProp, recordId), siteKey);
            } catch (Exception e) {
                // The Salesforce write succeeded; only the id writeback failed. Matching by email still
                // works on the next push, so report it without failing this one.
                logger.warn("[SFDC-Outbound] {} {} saved, but writing {} back to profile {} failed: {}",
                        object, recordId, idProp, profileId, e.getMessage());
                note = "saved in Salesforce, but writing " + idProp + " back to the profile failed: " + e.getMessage();
            }
        }
        rec(run, created ? Outcome.CREATED : Outcome.UPDATED, email, profileId, object, recordId, fields, note);
        return created ? PushResult.CREATED : PushResult.UPDATED;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static String emailOf(Map<String, Object> profile) {
        Object props = profile.get("properties");
        return props instanceof Map ? asString(((Map<String, Object>) props).get(PROP_EMAIL)) : null;
    }

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
