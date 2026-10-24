package org.jahia.community.modules.jcustomer.sfdc.history;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sync history: what each run of a sync direction did, on whom, and what.
 *
 * <p>Stored as typed JCR nodes under its own root {@value #ROOT}, in the {@code default} workspace,
 * split by day (UTC) so that no node holds too many children:
 * {@code jcsfdcnt:historyRoot} → {@code inbound|outbound} ({@code jcsfdcnt:syncHistory}) →
 * {@code yyyy} → {@code MM} → {@code dd} ({@code jcsfdcnt:historyFolder}) → runs
 * ({@code jcsfdcnt:syncRun}) → records ({@code jcsfdcnt:syncRecord}). A run's id is its node name,
 * which carries its start time, so the day folder of a run is derived from its id. Runs stored flat
 * under the direction node by earlier versions are moved into their day folder on activation and by
 * the purge job.
 *
 * <p>Retention: each direction has its own automatic purge (on/off and a number of days, stored on
 * its {@code jcsfdcnt:syncHistory} node and set from the admin UI); when nothing is stored it is on,
 * with {@code history.retention.days} days. {@link SyncHistoryPurgeJob} applies it. Caps come from the
 * {@code org.jahia.community.jcustomer.sfdc} configuration ({@code history.max.records.per.run},
 * {@code history.max.value.length}).
 *
 * <p>A run is recorded in memory by the thread that executes it ({@link Run}) and written once, when it
 * finishes. Runs that touched nobody are kept only when someone asked for them (manual run, manual push)
 * or when they failed, so a frequent poll that finds no change does not bury the useful runs. Writing
 * history never fails a sync: errors are logged and swallowed.
 *
 * <p>Thread-safe: writes are serialised on this service; reads use their own system session.
 */
@Component(service = SyncHistoryService.class, immediate = true,
        configurationPid = "org.jahia.community.jcustomer.sfdc")
public class SyncHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(SyncHistoryService.class);

    /** Root node of the history, beside the connector configuration under {@code /settings}. */
    public static final String ROOT = "/settings/jcustomer-sfdc-connector-history";
    private static final String WORKSPACE = "default";

    private static final String NT_ROOT    = "jcsfdcnt:historyRoot";
    private static final String NT_HISTORY = "jcsfdcnt:syncHistory";
    private static final String NT_FOLDER  = "jcsfdcnt:historyFolder";
    private static final String NT_RUN     = "jcsfdcnt:syncRun";
    private static final String NT_RECORD  = "jcsfdcnt:syncRecord";

    private static final String P_PURGE_ENABLED  = "purgeEnabled";
    private static final String P_RETENTION_DAYS = "retentionDays";

    /** {@code run-yyyyMMdd-HHmmss-SSS-hex}: groups 1-3 are the year, month and day of its folder. */
    private static final Pattern RUN_ID = Pattern.compile("^run-(\\d{4})(\\d{2})(\\d{2})-\\d{6}-\\d{3}-[0-9a-f]+$");
    private static final Pattern FOLDER = Pattern.compile("^\\d{2,4}$");
    /** Year, month, day. */
    private static final int DAY_DEPTH = 3;
    private static final DateTimeFormatter RUN_NAME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY_KEY =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A day folder holds runs, a run holds records: neither should pass this many children. */
    private static final int MAX_CHILDREN = 1000;
    public static final int MIN_RETENTION_DAYS = 1;
    public static final int MAX_RETENTION_DAYS = 3650;

    /** Sync direction; the key is the history child node name. */
    public enum Direction {
        INBOUND, OUTBOUND;
        public String key() { return name().toLowerCase(Locale.ROOT); }
        /** @return the direction for {@code key}, or {@code null} when unknown */
        public static Direction of(String key) {
            for (Direction d : values()) if (d.key().equalsIgnoreCase(key == null ? "" : key.trim())) return d;
            return null;
        }
    }

    /** What started a run. */
    public enum Trigger {
        SCHEDULED, MANUAL, LOGIN, PUSH;
        public String key() { return name().toLowerCase(Locale.ROOT); }
    }

    /** What happened to one record. */
    public enum Outcome {
        CREATED, UPDATED, SKIPPED, ERROR;
        public String key() { return name().toLowerCase(Locale.ROOT); }
    }

    @interface Config {
        int history_max_records_per_run() default 500;
        int history_max_value_length() default 200;
        int history_retention_days() default 30;
    }

    private volatile int maxRecords = 1;
    private volatile int maxValueLength = 1;
    private volatile int defaultRetentionDays = 30;

    @Activate
    @Modified
    public void activate(Config config) {
        this.maxRecords     = Math.min(MAX_CHILDREN, Math.max(1, config.history_max_records_per_run()));
        this.maxValueLength = Math.max(16, config.history_max_value_length());
        this.defaultRetentionDays = clampDays(config.history_retention_days());
        if (config.history_max_records_per_run() > MAX_CHILDREN) {
            logger.warn("[SFDC-History] history.max.records.per.run={} lowered to {}: a run node keeps at most {} records",
                    config.history_max_records_per_run(), MAX_CHILDREN, MAX_CHILDREN);
        }
        migrateLegacyRuns();
    }

    /** Starts recording a run. Call {@link Run#finish(String)} when it ends. */
    public Run start(Direction direction, Trigger trigger) {
        return new Run(direction, trigger);
    }

    /**
     * One run being recorded, owned by the thread that executes it. Counts are exact; record details
     * stop at {@code history.max.records.per.run} and the run is then marked truncated.
     */
    public final class Run {
        private final Direction direction;
        private final Trigger trigger;
        private final Instant startedAt = Instant.now();
        private final List<Map<String, Object>> records = new ArrayList<>();
        private long created, updated, skipped, errors;
        private boolean truncated;
        private boolean finished;

        private Run(Direction direction, Trigger trigger) {
            this.direction = direction;
            this.trigger = trigger;
        }

        /**
         * Records what happened to one record.
         *
         * @param outcome   created / updated / skipped / error
         * @param who       the person: email when known
         * @param profileId jCustomer profile id, or {@code null}
         * @param sfObject  Salesforce object (Contact, Lead, …), or {@code null}
         * @param sfId      Salesforce record id, or {@code null}
         * @param fields    fields written (name → value), or {@code null}
         * @param message   why it was skipped or failed, or {@code null}
         */
        public synchronized void record(Outcome outcome, String who, String profileId, String sfObject,
                                        String sfId, Map<String, ?> fields, String message) {
            switch (outcome) {
                case CREATED: created++; break;
                case UPDATED: updated++; break;
                case SKIPPED: skipped++; break;
                default:      errors++;  break;
            }
            if (records.size() >= maxRecords) {
                truncated = true;
                return;
            }
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("outcome", outcome.key());
            rec.put("who", who);
            rec.put("profileId", profileId);
            rec.put("sfObject", sfObject);
            rec.put("sfId", sfId);
            List<String> list = new ArrayList<>();
            if (fields != null) {
                fields.forEach((k, v) -> list.add(k + "=" + clip(v)));
            }
            rec.put("fields", list);
            rec.put("message", message);
            records.add(rec);
        }

        /**
         * Ends the run and writes it, unless it touched nobody and nobody asked for it.
         *
         * @param status short outcome of the run ({@code success}, {@code skipped: …}, {@code error: …})
         */
        public synchronized void finish(String status) {
            if (finished) return;
            finished = true;
            boolean asked = trigger == Trigger.MANUAL || trigger == Trigger.PUSH;
            boolean failed = status != null && status.toLowerCase(Locale.ROOT).startsWith("error");
            if (records.isEmpty() && !asked && !failed) return;
            persist(this, status, Instant.now());
        }
    }

    private String clip(Object value) {
        String s = value == null ? "" : String.valueOf(value);
        return s.length() <= maxValueLength ? s : s.substring(0, maxValueLength) + "…";
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    private synchronized void persist(Run run, String status, Instant finishedAt) {
        String name = "run-" + RUN_NAME.format(run.startedAt) + "-"
                + Integer.toHexString(0x1000 + RANDOM.nextInt(0xF000));
        try {
            inDefault(session -> {
                JCRNodeWrapper node = dayFolder(ensureHistory(session, run.direction), name).addNode(name, NT_RUN);
                node.setProperty("trigger", run.trigger.key());
                node.setProperty("status", status == null ? "" : status);
                node.setProperty("startedAt", calendar(run.startedAt));
                node.setProperty("finishedAt", calendar(finishedAt));
                node.setProperty("createdCount", run.created);
                node.setProperty("updatedCount", run.updated);
                node.setProperty("skippedCount", run.skipped);
                node.setProperty("errorCount", run.errors);
                node.setProperty("truncated", run.truncated);
                int i = 0;
                for (Map<String, Object> rec : run.records) {
                    JCRNodeWrapper r = node.addNode("record-" + (i++), NT_RECORD);
                    for (String p : new String[]{"outcome", "who", "profileId", "sfObject", "sfId", "message"}) {
                        Object v = rec.get(p);
                        if (v != null && !String.valueOf(v).isEmpty()) r.setProperty(p, String.valueOf(v));
                    }
                    @SuppressWarnings("unchecked")
                    List<String> fields = (List<String>) rec.get("fields");
                    if (fields != null && !fields.isEmpty()) r.setProperty("fields", fields.toArray(new String[0]));
                }
                session.save();
                return null;
            });
        } catch (Exception e) {
            logger.warn("[SFDC-History] Could not record {} run: {}", run.direction.key(), e.getMessage());
        }
    }

    private JCRNodeWrapper ensureHistory(JCRSessionWrapper session, Direction direction) throws RepositoryException {
        JCRNodeWrapper root;
        if (session.nodeExists(ROOT)) {
            root = (JCRNodeWrapper) session.getNode(ROOT);
        } else {
            JCRNodeWrapper parent = (JCRNodeWrapper) session.getNode(ROOT.substring(0, ROOT.lastIndexOf('/')));
            root = parent.addNode(ROOT.substring(ROOT.lastIndexOf('/') + 1), NT_ROOT);
        }
        return root.hasNode(direction.key())
                ? (JCRNodeWrapper) root.getNode(direction.key())
                : root.addNode(direction.key(), NT_HISTORY);
    }

    /** The {@code yyyy/MM/dd} folder of a run, created when missing. */
    private static JCRNodeWrapper dayFolder(JCRNodeWrapper history, String runName) throws RepositoryException {
        JCRNodeWrapper folder = history;
        for (String part : dayPath(runName).split("/")) {
            folder = folder.hasNode(part) ? (JCRNodeWrapper) folder.getNode(part) : folder.addNode(part, NT_FOLDER);
        }
        return folder;
    }

    /** {@code run-20261024-...} → {@code 2026/10/24}. */
    private static String dayPath(String runName) {
        Matcher m = RUN_ID.matcher(runName);
        if (!m.matches()) throw new IllegalArgumentException("Not a run id: " + runName);
        return m.group(1) + "/" + m.group(2) + "/" + m.group(3);
    }

    /**
     * Moves runs stored flat under a direction node (before 2.6.0) into their day folder. Safe to call
     * any time: does nothing once they are moved.
     */
    void migrateLegacyRuns() {
        try {
            inDefault(session -> {
                if (!session.nodeExists(ROOT)) return null;
                for (Direction d : Direction.values()) {
                    String path = ROOT + "/" + d.key();
                    if (!session.nodeExists(path)) continue;
                    JCRNodeWrapper history = (JCRNodeWrapper) session.getNode(path);
                    List<String> legacy = names(history, RUN_ID);
                    if (legacy.isEmpty()) continue;
                    Map<String, String> targets = new LinkedHashMap<>();
                    for (String name : legacy) targets.put(name, dayFolder(history, name).getPath());
                    session.save();                                 // the day folders exist before the moves
                    for (Map.Entry<String, String> t : targets.entrySet()) {
                        session.move(history.getPath() + "/" + t.getKey(), t.getValue() + "/" + t.getKey());
                    }
                    session.save();
                    logger.info("[SFDC-History] Moved {} {} run(s) into day folders", legacy.size(), d.key());
                }
                return null;
            });
        } catch (Exception e) {
            logger.warn("[SFDC-History] Could not move earlier runs into day folders: {}", e.getMessage());
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Newest runs of a direction, without their records.
     *
     * @return one map per run: id, trigger, status, startedAt, finishedAt (ISO-8601), created, updated,
     *         skipped, errors, truncated, recordCount (number of detailed records)
     */
    public List<Map<String, Object>> listRuns(Direction direction, int limit) {
        try {
            return inDefault(session -> {
                List<Map<String, Object>> out = new ArrayList<>();
                String path = ROOT + "/" + direction.key();
                if (!session.nodeExists(path)) return out;
                List<JCRNodeWrapper> runs = new ArrayList<>();
                newestRuns((JCRNodeWrapper) session.getNode(path), 0, Math.max(limit, 1), runs);
                for (JCRNodeWrapper run : runs) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", run.getName());
                    m.put("trigger", str(run, "trigger"));
                    m.put("status", str(run, "status"));
                    m.put("startedAt", iso(run, "startedAt"));
                    m.put("finishedAt", iso(run, "finishedAt"));
                    m.put("created", num(run, "createdCount"));
                    m.put("updated", num(run, "updatedCount"));
                    m.put("skipped", num(run, "skippedCount"));
                    m.put("errors", num(run, "errorCount"));
                    m.put("truncated", run.hasProperty("truncated") && run.getProperty("truncated").getBoolean());
                    m.put("recordCount", run.getNodes().getSize());
                    out.add(m);
                }
                return out;
            });
        } catch (Exception e) {
            logger.warn("[SFDC-History] Could not list {} runs: {}", direction.key(), e.getMessage());
            return List.of();
        }
    }

    /** Adds the newest runs under {@code folder} (at {@code depth}: 0 = direction node) until {@code limit}. */
    private static void newestRuns(JCRNodeWrapper folder, int depth, int limit, List<JCRNodeWrapper> out)
            throws RepositoryException {
        if (depth == DAY_DEPTH) {
            List<String> runs = names(folder, RUN_ID);
            runs.sort(Comparator.reverseOrder());
            for (String name : runs) {
                if (out.size() >= limit) return;
                out.add((JCRNodeWrapper) folder.getNode(name));
            }
            return;
        }
        List<String> children = names(folder, FOLDER);
        children.sort(Comparator.reverseOrder());
        for (String name : children) {
            if (out.size() >= limit) return;
            newestRuns((JCRNodeWrapper) folder.getNode(name), depth + 1, limit, out);
        }
    }

    /**
     * Records of one run, in the order they were processed.
     *
     * @return one map per record: outcome, who, profileId, sfObject, sfId, fields (list of
     *         {@code name=value}), message; empty when the run does not exist
     */
    public List<Map<String, Object>> getRecords(Direction direction, String runId) {
        if (runId == null || !RUN_ID.matcher(runId).matches()) return List.of();
        try {
            return inDefault(session -> {
                List<Map<String, Object>> out = new ArrayList<>();
                String path = ROOT + "/" + direction.key() + "/" + dayPath(runId) + "/" + runId;
                if (!session.nodeExists(path)) return out;
                List<JCRNodeWrapper> recs = new ArrayList<>();
                JCRNodeIteratorWrapper it = ((JCRNodeWrapper) session.getNode(path)).getNodes();
                while (it.hasNext()) recs.add((JCRNodeWrapper) it.nextNode());
                recs.sort(Comparator.comparingInt(n -> index(n.getName())));
                for (JCRNodeWrapper r : recs) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (String p : new String[]{"outcome", "who", "profileId", "sfObject", "sfId", "message"}) {
                        m.put(p, str(r, p));
                    }
                    List<String> fields = new ArrayList<>();
                    if (r.hasProperty("fields")) {
                        for (Value v : r.getProperty("fields").getValues()) fields.add(v.getString());
                    }
                    m.put("fields", fields);
                    out.add(m);
                }
                return out;
            });
        } catch (Exception e) {
            logger.warn("[SFDC-History] Could not read run {}: {}", runId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Every recorded run of a direction, newest first: the {@link #listRuns} summary plus its records
     * under {@code records}. Used for the CSV/JSON export.
     */
    public List<Map<String, Object>> exportRuns(Direction direction) {
        List<Map<String, Object>> runs = listRuns(direction, Integer.MAX_VALUE);
        for (Map<String, Object> run : runs) {
            run.put("records", getRecords(direction, String.valueOf(run.get("id"))));
        }
        return runs;
    }

    // ── Retention ─────────────────────────────────────────────────────────────

    /**
     * Automatic purge of a direction.
     *
     * @return {@code enabled}, {@code days}, {@code defaultDays} ({@code history.retention.days}), and the
     *         accepted range {@code minDays} / {@code maxDays}
     */
    public Map<String, Object> getRetention(Direction direction) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean enabled = true;
        int days = defaultRetentionDays;
        try {
            Object[] stored = inDefault(session -> {
                String path = ROOT + "/" + direction.key();
                if (!session.nodeExists(path)) return new Object[]{null, null};
                JCRNodeWrapper h = (JCRNodeWrapper) session.getNode(path);
                return new Object[]{
                    h.hasProperty(P_PURGE_ENABLED) ? h.getProperty(P_PURGE_ENABLED).getBoolean() : null,
                    h.hasProperty(P_RETENTION_DAYS) ? h.getProperty(P_RETENTION_DAYS).getLong() : null
                };
            });
            if (stored[0] != null) enabled = (Boolean) stored[0];
            if (stored[1] != null) days = clampDays((int) Math.min(Integer.MAX_VALUE, (Long) stored[1]));
        } catch (Exception e) {
            logger.warn("[SFDC-History] Could not read the {} retention: {}", direction.key(), e.getMessage());
        }
        out.put("enabled", enabled);
        out.put("days", days);
        out.put("defaultDays", defaultRetentionDays);
        out.put("minDays", MIN_RETENTION_DAYS);
        out.put("maxDays", MAX_RETENTION_DAYS);
        return out;
    }

    /**
     * Saves the automatic purge of a direction, then applies it at once.
     *
     * @param enabled whether runs older than {@code days} are deleted automatically
     * @param days    retention in days, {@value #MIN_RETENTION_DAYS} to {@value #MAX_RETENTION_DAYS}
     * @return the number of runs the purge removed (0 when it failed: logged, the purge job retries)
     * @throws IllegalArgumentException when {@code days} is out of range
     */
    public int saveRetention(Direction direction, boolean enabled, int days) throws Exception {
        if (days < MIN_RETENTION_DAYS || days > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException("days must be between " + MIN_RETENTION_DAYS + " and " + MAX_RETENTION_DAYS);
        }
        synchronized (this) {
            inDefault(session -> {
                JCRNodeWrapper h = ensureHistory(session, direction);
                h.setProperty(P_PURGE_ENABLED, enabled);
                h.setProperty(P_RETENTION_DAYS, (long) days);
                session.save();
                return null;
            });
        }
        logger.info("[SFDC-History] {} automatic purge {} ({} days)", direction.key(), enabled ? "on" : "off", days);
        if (!enabled) return 0;
        try {
            return purgeOlderThan(direction, days);
        } catch (Exception e) {                                    // saved; the hourly job will purge
            logger.warn("[SFDC-History] {} retention saved, immediate purge failed: {}", direction.key(), e.getMessage());
            return 0;
        }
    }

    /**
     * Applies the automatic purge of both directions: deletes the runs older than their retention.
     * Called by {@link SyncHistoryPurgeJob}.
     *
     * @return the number of runs removed
     */
    public int purgeExpired() {
        migrateLegacyRuns();
        int removed = 0;
        for (Direction d : Direction.values()) {
            Map<String, Object> r = getRetention(d);
            if (!Boolean.TRUE.equals(r.get("enabled"))) continue;
            try {
                removed += purgeOlderThan(d, (Integer) r.get("days"));
            } catch (Exception e) {
                logger.warn("[SFDC-History] Automatic purge of {} failed: {}", d.key(), e.getMessage());
            }
        }
        return removed;
    }

    /** Removes the runs of a direction that started more than {@code days} days ago. */
    private synchronized int purgeOlderThan(Direction direction, int days) throws Exception {
        Instant limit = Instant.now().minus(Duration.ofDays(days));
        String cutoff = "run-" + RUN_NAME.format(limit);              // run names sort by start time
        String cutoffDay = DAY_KEY.format(limit);                     // yyyyMMdd
        Integer removed = inDefault(session -> {
            String path = ROOT + "/" + direction.key();
            if (!session.nodeExists(path)) return 0;
            return purgeBefore(session, (JCRNodeWrapper) session.getNode(path), 0, "", cutoffDay, cutoff);
        });
        int n = removed == null ? 0 : removed;
        if (n > 0) logger.info("[SFDC-History] Automatic purge removed {} {} run(s) older than {} days", n, direction.key(), days);
        return n;
    }

    /**
     * Removes what is older than {@code cutoff} (a run name prefix) under {@code folder}, whose path below
     * the direction node is {@code key} ({@code yyyy}, {@code yyyyMM}...). A folder entirely older than
     * {@code cutoffDay} ({@code yyyyMMdd}) is removed whole, one day at a time; the folder holding the
     * cutoff is walked; newer ones are kept.
     */
    private static int purgeBefore(JCRSessionWrapper session, JCRNodeWrapper folder, int depth, String key,
                                   String cutoffDay, String cutoff) throws RepositoryException {
        int removed = 0;
        if (depth == DAY_DEPTH) {
            for (String name : names(folder, RUN_ID)) {
                if (name.compareTo(cutoff) < 0) {
                    folder.getNode(name).remove();
                    removed++;
                }
            }
            session.save();
            return removed;
        }
        for (String name : names(folder, FOLDER)) {
            String childKey = key + name;
            String cutoffKey = cutoffDay.substring(0, childKey.length());
            int cmp = childKey.compareTo(cutoffKey);
            if (cmp > 0) continue;
            JCRNodeWrapper child = (JCRNodeWrapper) folder.getNode(name);
            removed += cmp < 0 ? removeAll(session, child, depth + 1)
                               : purgeBefore(session, child, depth + 1, childKey, cutoffDay, cutoff);
            if (!child.getNodes().hasNext()) {                      // emptied: remove the folder too
                child.remove();
                session.save();
            }
        }
        return removed;
    }

    // ── Purge ─────────────────────────────────────────────────────────────────

    /**
     * Removes every recorded run of a direction.
     *
     * @return the number of runs removed
     */
    public synchronized int purge(Direction direction) throws Exception {
        Integer removed = inDefault(session -> {
            String path = ROOT + "/" + direction.key();
            if (!session.nodeExists(path)) return 0;
            JCRNodeWrapper history = (JCRNodeWrapper) session.getNode(path);
            int n = 0;
            for (String name : names(history, RUN_ID)) {           // runs of an earlier layout
                history.getNode(name).remove();
                n++;
            }
            session.save();
            for (String name : names(history, FOLDER)) {
                JCRNodeWrapper year = (JCRNodeWrapper) history.getNode(name);
                n += removeAll(session, year, 1);
                year.remove();
                session.save();
            }
            return n;
        });
        logger.info("[SFDC-History] Purged {} {} run(s)", removed, direction.key());
        return removed == null ? 0 : removed;
    }

    /**
     * Empties {@code folder} (at {@code depth}), one day folder per save so that a large history is
     * never removed in a single transaction.
     *
     * @return the number of runs removed
     */
    private static int removeAll(JCRSessionWrapper session, JCRNodeWrapper folder, int depth) throws RepositoryException {
        if (depth == DAY_DEPTH) {
            int n = names(folder, RUN_ID).size();
            for (String name : names(folder, Pattern.compile(".*"))) folder.getNode(name).remove();
            session.save();
            return n;
        }
        int n = 0;
        for (String name : names(folder, FOLDER)) {
            JCRNodeWrapper child = (JCRNodeWrapper) folder.getNode(name);
            n += removeAll(session, child, depth + 1);
            child.remove();
            session.save();
        }
        return n;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Runs a callback with a system session on the {@code default} workspace, whatever the request's. */
    private static <T> T inDefault(JCRCallback<T> callback) throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, WORKSPACE, null, callback);
    }

    private static int clampDays(int days) {
        return Math.max(MIN_RETENTION_DAYS, Math.min(MAX_RETENTION_DAYS, days));
    }

    private static List<String> names(JCRNodeWrapper parent, Pattern pattern) throws RepositoryException {
        List<String> names = new ArrayList<>();
        JCRNodeIteratorWrapper it = parent.getNodes();
        while (it.hasNext()) {
            String n = it.nextNode().getName();
            if (pattern.matcher(n).matches()) names.add(n);
        }
        return names;
    }

    private static int index(String recordName) {
        try { return Integer.parseInt(recordName.substring(recordName.lastIndexOf('-') + 1)); }
        catch (Exception e) { return Integer.MAX_VALUE; }
    }

    private static GregorianCalendar calendar(Instant instant) {
        return GregorianCalendar.from(instant.atZone(ZoneOffset.UTC));
    }

    private static String str(JCRNodeWrapper n, String p) throws RepositoryException {
        return n.hasProperty(p) ? n.getProperty(p).getString() : null;
    }

    private static long num(JCRNodeWrapper n, String p) throws RepositoryException {
        return n.hasProperty(p) ? n.getProperty(p).getLong() : 0;
    }

    private static String iso(JCRNodeWrapper n, String p) throws RepositoryException {
        return n.hasProperty(p) ? n.getProperty(p).getDate().toInstant().toString() : null;
    }
}
