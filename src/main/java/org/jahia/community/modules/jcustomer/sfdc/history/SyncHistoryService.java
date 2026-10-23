package org.jahia.community.modules.jcustomer.sfdc.history;

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
import java.util.regex.Pattern;

/**
 * Sync history: what each run of a sync direction did, on whom, and what.
 *
 * <p>Stored as typed JCR nodes under its own root {@value #ROOT}
 * ({@code jcsfdcnt:historyRoot} → {@code inbound|outbound} ({@code jcsfdcnt:syncHistory}) →
 * runs ({@code jcsfdcnt:syncRun}) → records ({@code jcsfdcnt:syncRecord})), so it is browsable in the
 * JCR browser. Retention and caps come from the {@code org.jahia.community.jcustomer.sfdc}
 * configuration ({@code history.max.runs}, {@code history.max.records.per.run},
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

    private static final String NT_ROOT    = "jcsfdcnt:historyRoot";
    private static final String NT_HISTORY = "jcsfdcnt:syncHistory";
    private static final String NT_RUN     = "jcsfdcnt:syncRun";
    private static final String NT_RECORD  = "jcsfdcnt:syncRecord";

    private static final Pattern RUN_ID = Pattern.compile("^run-[0-9A-Za-z-]+$");
    private static final DateTimeFormatter RUN_NAME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();

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
        int history_max_runs() default 100;
        int history_max_records_per_run() default 500;
        int history_max_value_length() default 200;
    }

    private volatile int maxRuns = 1;
    private volatile int maxRecords = 1;
    private volatile int maxValueLength = 1;

    @Activate
    @Modified
    public void activate(Config config) {
        this.maxRuns        = Math.max(1, config.history_max_runs());
        this.maxRecords     = Math.max(1, config.history_max_records_per_run());
        this.maxValueLength = Math.max(16, config.history_max_value_length());
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
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                JCRNodeWrapper history = ensureHistory(session, run.direction);
                String name = "run-" + RUN_NAME.format(run.startedAt) + "-"
                        + Integer.toHexString(0x1000 + RANDOM.nextInt(0xF000));
                JCRNodeWrapper node = history.addNode(name, NT_RUN);
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
                prune(session, history);
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

    /** Removes the oldest runs beyond {@code history.max.runs} (run names sort chronologically). */
    private void prune(JCRSessionWrapper session, JCRNodeWrapper history) throws RepositoryException {
        List<String> names = runNames(history);
        if (names.size() <= maxRuns) return;
        names.sort(Comparator.naturalOrder());
        for (String old : names.subList(0, names.size() - maxRuns)) {
            history.getNode(old).remove();
        }
        session.save();
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
            return JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                List<Map<String, Object>> out = new ArrayList<>();
                String path = ROOT + "/" + direction.key();
                if (!session.nodeExists(path)) return out;
                JCRNodeWrapper history = (JCRNodeWrapper) session.getNode(path);
                List<String> names = runNames(history);
                names.sort(Comparator.reverseOrder());
                for (String name : names.subList(0, Math.min(Math.max(limit, 1), names.size()))) {
                    JCRNodeWrapper run = (JCRNodeWrapper) history.getNode(name);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", name);
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

    /**
     * Records of one run, in the order they were processed.
     *
     * @return one map per record: outcome, who, profileId, sfObject, sfId, fields (list of
     *         {@code name=value}), message; empty when the run does not exist
     */
    public List<Map<String, Object>> getRecords(Direction direction, String runId) {
        if (runId == null || !RUN_ID.matcher(runId).matches()) return List.of();
        try {
            return JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                List<Map<String, Object>> out = new ArrayList<>();
                String path = ROOT + "/" + direction.key() + "/" + runId;
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

    // ── Purge ─────────────────────────────────────────────────────────────────

    /**
     * Removes every recorded run of a direction.
     *
     * @return the number of runs removed
     */
    public synchronized int purge(Direction direction) throws Exception {
        Integer removed = JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
            String path = ROOT + "/" + direction.key();
            if (!session.nodeExists(path)) return 0;
            JCRNodeWrapper history = (JCRNodeWrapper) session.getNode(path);
            List<String> names = runNames(history);
            for (String name : names) history.getNode(name).remove();
            session.save();
            return names.size();
        });
        logger.info("[SFDC-History] Purged {} {} run(s)", removed, direction.key());
        return removed == null ? 0 : removed;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static List<String> runNames(JCRNodeWrapper history) throws RepositoryException {
        List<String> names = new ArrayList<>();
        JCRNodeIteratorWrapper it = history.getNodes();
        while (it.hasNext()) {
            String n = it.nextNode().getName();
            if (RUN_ID.matcher(n).matches()) names.add(n);
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
