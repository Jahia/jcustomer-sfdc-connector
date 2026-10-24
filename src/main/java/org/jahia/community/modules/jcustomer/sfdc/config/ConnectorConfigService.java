package org.jahia.community.modules.jcustomer.sfdc.config;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NodeIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes connector configuration from/to JCR, under
 * {@code /settings/jcustomer-sfdc-connector}, using the typed node types defined in
 * {@code META-INF/definitions.cnd} ({@code jcsfdcnt:*}). Properties are stored with their real JCR
 * type (boolean / long / date), while the public API stays string-based for the actions/UI.
 *
 * <p>Reads are tolerant of the legacy {@code nt:unstructured} + all-String layout (JCR converts on
 * read), so it works both before and after the one-time migration.
 */
@Component(service = ConnectorConfigService.class, immediate = true,
        configurationPid = "org.jahia.community.jcustomer.sfdc")
public class ConnectorConfigService {

    private static final Logger logger = LoggerFactory.getLogger(ConnectorConfigService.class);

    /**
     * Connector configuration ({@code org.jahia.community.jcustomer.sfdc}). A product default that must
     * also reach existing installs lives here, in one place: a shipped {@code .cfg} marked
     * {@code # default configuration} is never rewritten on upgrade, so a key added to it later
     * would never arrive. The {@code .cfg} documents these keys; it does not repeat their values.
     */
    @interface Config {
        String sfdc_api_version() default "";
        /** How often the outbound poll runs while an outbound trigger is on (Quartz cron). */
        String outbound_poll_cron() default "0 0/5 * * * ?";
    }

    /** Salesforce REST API version used when the Connection does not set one ({@code sfdc.api.version}). */
    private volatile String defaultApiVersion = "";
    /** Outbound poll schedule used when the admin has not chosen one ({@code outbound.poll.cron}). */
    private volatile String defaultPollCron = "";

    @Activate
    @Modified
    public void activate(Config config) {
        String v = config.sfdc_api_version();
        this.defaultApiVersion = v == null ? "" : v.trim();
        String c = config.outbound_poll_cron();
        this.defaultPollCron = c == null ? "" : c.trim();
    }

    /** Outbound poll schedule applied when the admin has not chosen one; empty when not configured. */
    public String getDefaultPollCron() {
        return defaultPollCron;
    }

    public static final String CONFIG_ROOT  = "/settings/jcustomer-sfdc-connector";
    public static final String CONN_NODE    = CONFIG_ROOT + "/connection";
    public static final String MAPPING_NODE = CONFIG_ROOT + "/mapping";
    public static final String STATE_NODE   = CONFIG_ROOT + "/syncState";
    public static final String TRIGGERS_NODE = CONFIG_ROOT + "/triggers";

    public static final String OUTBOUND_MAPPING_NODE  = CONFIG_ROOT + "/outbound/mapping";
    public static final String OUTBOUND_STATE_NODE    = CONFIG_ROOT + "/outbound/syncState";
    public static final String OUTBOUND_TRIGGERS_NODE = CONFIG_ROOT + "/outbound/triggers";

    // Node types (see META-INF/definitions.cnd)
    public static final String NT_CONFIG          = "jcsfdcnt:connectorConfig";
    public static final String NT_CONNECTION      = "jcsfdcnt:connection";
    public static final String NT_MAPPING_SET     = "jcsfdcnt:mappingSet";
    public static final String NT_OBJECT_MAPPING  = "jcsfdcnt:objectMapping";
    public static final String NT_FIELD_MAPPING   = "jcsfdcnt:fieldMapping";
    public static final String NT_TRIGGERS        = "jcsfdcnt:triggers";
    public static final String NT_OUTBOUND        = "jcsfdcnt:outbound";
    public static final String NT_OUTBOUND_TRIG   = "jcsfdcnt:outboundTriggers";
    public static final String NT_SYNC_STATE      = "jcsfdcnt:syncState";

    // Which properties are booleans (so the String-based public API converts correctly on save).
    private static final Set<String> CONNECTION_BOOLEANS = Set.of("enabled");
    private static final Set<String> TRIGGER_BOOLEANS    = Set.of("loginEnabled", "schedulerEnabled");
    private static final Set<String> OUTBOUND_BOOLEANS   = Set.of("onProfileUpdate", "onFormSubmission", "reconcileEnabled");

    // ── Connection ────────────────────────────────────────────────────────────

    public Map<String, String> loadConnection() {
        Map<String, String> result = new HashMap<>();
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                if (!session.nodeExists(CONN_NODE)) return null;
                JCRNodeWrapper node = (JCRNodeWrapper) session.getNode(CONN_NODE);
                for (String prop : new String[]{"instanceUrl","clientId","sfUsername","privateKey","apiVersion","enabled"}) {
                    if (node.hasProperty(prop)) result.put(prop, node.getProperty(prop).getString());
                }
                return null;
            });
        } catch (Exception e) {
            logger.error("[SFDC-Config] Failed to load connection config", e);
        }
        return result;
    }

    /**
     * Salesforce REST API version from the OSGi configuration ({@code sfdc.api.version}), used when the
     * Connection leaves it empty. Empty when not configured.
     */
    public String getDefaultApiVersion() {
        return defaultApiVersion;
    }

    public void saveConnection(Map<String, String> values) throws Exception {
        JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
            JCRNodeWrapper conn = ensureChild(getOrCreateRoot(session), "connection", NT_CONNECTION);
            writeStringOrBoolean(conn, values, CONNECTION_BOOLEANS);
            session.save();
            return null;
        });
        logger.info("[SFDC-Config] Connection config saved");
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    public List<Map<String, String>> loadMapping(String sfObject)         { return loadMappingAt(MAPPING_NODE, sfObject); }
    public void saveMapping(String sfObject, List<Map<String, String>> m) throws Exception { saveMapping(false, sfObject, m); }
    public List<Map<String, String>> loadOutboundMapping(String sfObject) { return loadMappingAt(OUTBOUND_MAPPING_NODE, sfObject); }
    public void saveOutboundMapping(String sfObject, List<Map<String, String>> m) throws Exception { saveMapping(true, sfObject, m); }

    private List<Map<String, String>> loadMappingAt(String baseNode, String sfObject) {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                String path = baseNode + "/" + sfObject;
                if (!session.nodeExists(path)) return null;
                NodeIterator it = ((JCRNodeWrapper) session.getNode(path)).getNodes();
                while (it.hasNext()) {
                    JCRNodeWrapper m = (JCRNodeWrapper) it.nextNode();
                    Map<String, String> entry = new HashMap<>();
                    if (m.hasProperty("sfField"))    entry.put("sfField",    m.getProperty("sfField").getString());
                    if (m.hasProperty("jcProperty")) entry.put("jcProperty", m.getProperty("jcProperty").getString());
                    if (m.hasProperty("required"))   entry.put("required",   m.getProperty("required").getString());
                    entry.put("id", m.getName());
                    result.add(entry);
                }
                return null;
            });
        } catch (Exception e) {
            logger.error("[SFDC-Config] Failed to load mapping at {}/{}", baseNode, sfObject, e);
        }
        return result;
    }

    private void saveMapping(boolean outbound, String sfObject, List<Map<String, String>> mappings) throws Exception {
        JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
            JCRNodeWrapper folder = ensureObjectMapping(session, outbound, sfObject);
            NodeIterator it = folder.getNodes();
            while (it.hasNext()) it.nextNode().remove();
            int i = 0;
            for (Map<String, String> m : mappings) {
                JCRNodeWrapper node = folder.addNode("mapping-" + i, NT_FIELD_MAPPING);
                if (m.containsKey("sfField"))    node.setProperty("sfField",    orEmpty(m.get("sfField")));
                if (m.containsKey("jcProperty")) node.setProperty("jcProperty", orEmpty(m.get("jcProperty")));
                if (m.containsKey("required"))   node.setProperty("required",   toBool(m.get("required")));
                i++;
            }
            session.save();
            return null;
        });
        logger.info("[SFDC-Config] Mapping saved ({}{}) — {} entries", outbound ? "outbound/" : "", sfObject, mappings.size());
    }

    // ── Inbound triggers ──────────────────────────────────────────────────────

    public Map<String, String> loadTriggers() {
        Map<String, String> result = new HashMap<>();
        result.put("loginEnabled", "true");
        result.put("schedulerEnabled", "true");
        result.put("schedulerCron", "0 0 5 * * ?");
        readInto(TRIGGERS_NODE, result, "loginEnabled", "schedulerEnabled", "schedulerCron");
        return result;
    }

    public void saveTriggers(Map<String, String> values) throws Exception {
        JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
            JCRNodeWrapper triggers = ensureChild(getOrCreateRoot(session), "triggers", NT_TRIGGERS);
            writeStringOrBoolean(triggers, values, TRIGGER_BOOLEANS);
            session.save();
            return null;
        });
        logger.info("[SFDC-Config] Triggers saved: {}", values);
    }

    // ── Outbound triggers ─────────────────────────────────────────────────────

    /**
     * Outbound trigger configuration as stored. An absent trigger is off. {@code reconcileCron} is
     * present only when the admin chose a schedule (else {@link #getDefaultPollCron()} applies).
     */
    public Map<String, String> loadOutboundTriggers() {
        Map<String, String> result = new HashMap<>();
        result.put("outboundObject", "Lead");
        readInto(OUTBOUND_TRIGGERS_NODE, result,
                "onProfileUpdate", "onFormSubmission", "outboundObject", "reconcileEnabled", "reconcileCron");
        // Up to 2.3.4 a separate switch enabled the poll. A cron stored while that switch was off was
        // never in effect (the UI saved its own default), so it is not treated as the admin's choice.
        if ("false".equalsIgnoreCase(result.remove("reconcileEnabled"))) {
            result.remove("reconcileCron");
        }
        String cron = result.get("reconcileCron");
        if (cron != null && cron.isBlank()) {
            result.remove("reconcileCron");
        }
        return result;
    }

    /** Removes the outbound poll watermark, so the next enabling of a trigger starts from that moment. */
    public void clearOutboundReconcileAt() {
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                if (session.nodeExists(OUTBOUND_STATE_NODE)) {
                    JCRNodeWrapper state = (JCRNodeWrapper) session.getNode(OUTBOUND_STATE_NODE);
                    if (state.hasProperty("lastReconcileAt")) {
                        state.getProperty("lastReconcileAt").remove();
                        session.save();
                    }
                }
                return null;
            });
        } catch (Exception e) {
            logger.warn("[SFDC-Config] Could not clear lastReconcileAt: {}", e.getMessage());
        }
    }

    public void saveOutboundTriggers(Map<String, String> values) throws Exception {
        Map<String, String> toWrite = new HashMap<>(values);
        toWrite.remove("reconcileEnabled");                      // legacy switch, no longer used
        String cron = toWrite.get("reconcileCron");
        boolean clearCron = cron != null && cron.isBlank();      // empty = use the default schedule
        if (clearCron) toWrite.remove("reconcileCron");
        JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
            JCRNodeWrapper triggers = ensureChild(ensureOutbound(session), "triggers", NT_OUTBOUND_TRIG);
            writeStringOrBoolean(triggers, toWrite, OUTBOUND_BOOLEANS);
            if (triggers.hasProperty("reconcileEnabled")) triggers.getProperty("reconcileEnabled").remove();
            if (clearCron && triggers.hasProperty("reconcileCron")) triggers.getProperty("reconcileCron").remove();
            session.save();
            return null;
        });
        logger.info("[SFDC-Config] Outbound triggers saved: {}", values);
    }

    // ── Sync state ────────────────────────────────────────────────────────────

    public Map<String, String> loadSyncState() {
        Map<String, String> state = new HashMap<>();
        readState(STATE_NODE, state, "lastSyncCount", "lastSyncErrors");
        return state;
    }

    public void saveSyncState(String status, long count, long errors) {
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                JCRNodeWrapper state = ensureChild(getOrCreateRoot(session), "syncState", NT_SYNC_STATE);
                state.setProperty("lastSyncAt", nowCalendar());
                state.setProperty("lastSyncStatus", orEmpty(status));
                state.setProperty("lastSyncCount", count);
                state.setProperty("lastSyncErrors", errors);
                session.save();
                return null;
            });
        } catch (Exception e) {
            logger.error("[SFDC-Config] Failed to save sync state", e);
        }
    }

    public String getLastSyncAt() { return loadSyncState().getOrDefault("lastSyncAt", null); }

    public Map<String, String> loadOutboundSyncState() {
        Map<String, String> state = new HashMap<>();
        readState(OUTBOUND_STATE_NODE, state, "lastSyncUpdated", "lastSyncCreated", "lastSyncErrors");
        return state;
    }

    public void saveOutboundSyncState(String status, long updated, long created, long errors) {
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                JCRNodeWrapper state = ensureChild(ensureOutbound(session), "syncState", NT_SYNC_STATE);
                state.setProperty("lastSyncAt", nowCalendar());
                state.setProperty("lastSyncStatus", orEmpty(status));
                state.setProperty("lastSyncUpdated", updated);
                state.setProperty("lastSyncCreated", created);
                state.setProperty("lastSyncErrors", errors);
                session.save();
                return null;
            });
        } catch (Exception e) {
            logger.error("[SFDC-Config] Failed to save outbound sync state", e);
        }
    }

    /** ISO-8601 timestamp of the last outbound reconcile run, or {@code null}. */
    public String getOutboundReconcileAt() {
        try {
            return JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                if (!session.nodeExists(OUTBOUND_STATE_NODE)) return null;
                return isoOf((JCRNodeWrapper) session.getNode(OUTBOUND_STATE_NODE), "lastReconcileAt");
            });
        } catch (Exception e) {
            logger.warn("[SFDC-Config] Could not read lastReconcileAt: {}", e.getMessage());
            return null;
        }
    }

    public void setOutboundReconcileAt(String iso) {
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                JCRNodeWrapper state = ensureChild(ensureOutbound(session), "syncState", NT_SYNC_STATE);
                setDate(state, "lastReconcileAt", iso);
                session.save();
                return null;
            });
        } catch (Exception e) {
            logger.error("[SFDC-Config] Could not save lastReconcileAt", e);
        }
    }

    /**
     * Profiles whose outbound push failed and that the next poll retries, oldest first.
     *
     * @return profile id → failed attempts so far; empty when none
     */
    public Map<String, Integer> loadOutboundRetries() {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                if (!session.nodeExists(OUTBOUND_STATE_NODE)) return null;
                JCRNodeWrapper state = (JCRNodeWrapper) session.getNode(OUTBOUND_STATE_NODE);
                if (!state.hasProperty("retryProfiles")) return null;
                for (javax.jcr.Value v : state.getProperty("retryProfiles").getValues()) {
                    String entry = v.getString();
                    int at = entry.lastIndexOf(':');
                    if (at < 1) continue;
                    try {
                        out.put(entry.substring(0, at), Integer.parseInt(entry.substring(at + 1)));
                    } catch (NumberFormatException ignored) {
                        // a malformed entry is dropped
                    }
                }
                return null;
            });
        } catch (Exception e) {
            logger.warn("[SFDC-Config] Could not read the outbound retry list: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Replaces the list of profiles the next outbound poll retries.
     *
     * @param retries profile id → failed attempts so far, oldest first; empty clears the list
     */
    public void saveOutboundRetries(Map<String, Integer> retries) {
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                JCRNodeWrapper state = ensureChild(ensureOutbound(session), "syncState", NT_SYNC_STATE);
                if (retries.isEmpty()) {
                    if (state.hasProperty("retryProfiles")) state.getProperty("retryProfiles").remove();
                } else {
                    state.setProperty("retryProfiles", retries.entrySet().stream()
                            .map(e -> e.getKey() + ":" + e.getValue()).toArray(String[]::new));
                }
                session.save();
                return null;
            });
        } catch (Exception e) {
            logger.error("[SFDC-Config] Could not save the outbound retry list", e);
        }
    }

    // ── Typed-node helpers ────────────────────────────────────────────────────

    private JCRNodeWrapper getOrCreateRoot(JCRSessionWrapper session) throws RepositoryException {
        if (session.nodeExists(CONFIG_ROOT)) return (JCRNodeWrapper) session.getNode(CONFIG_ROOT);
        JCRNodeWrapper settings = (JCRNodeWrapper) session.getNode("/settings");
        return (JCRNodeWrapper) settings.addNode("jcustomer-sfdc-connector", NT_CONFIG);
    }

    private JCRNodeWrapper ensureOutbound(JCRSessionWrapper session) throws RepositoryException {
        return ensureChild(getOrCreateRoot(session), "outbound", NT_OUTBOUND);
    }

    private JCRNodeWrapper ensureObjectMapping(JCRSessionWrapper session, boolean outbound, String sfObject) throws RepositoryException {
        JCRNodeWrapper parent = outbound ? ensureOutbound(session) : getOrCreateRoot(session);
        JCRNodeWrapper mappingSet = ensureChild(parent, "mapping", NT_MAPPING_SET);
        return ensureChild(mappingSet, sfObject, NT_OBJECT_MAPPING);
    }

    private static JCRNodeWrapper ensureChild(JCRNodeWrapper parent, String name, String type) throws RepositoryException {
        if (parent.hasNode(name)) return (JCRNodeWrapper) parent.getNode(name);
        return (JCRNodeWrapper) parent.addNode(name, type);
    }

    /** Writes each value as a boolean when its key is in {@code booleanKeys}, else as a string. */
    private static void writeStringOrBoolean(JCRNodeWrapper node, Map<String, String> values, Set<String> booleanKeys)
            throws RepositoryException {
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (booleanKeys.contains(e.getKey())) node.setProperty(e.getKey(), toBool(e.getValue()));
            else node.setProperty(e.getKey(), orEmpty(e.getValue()));
        }
    }

    /** Reads named properties (as strings) into {@code target}, tolerant of missing node/props. */
    private void readInto(String path, Map<String, String> target, String... props) {
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                if (!session.nodeExists(path)) return null;
                JCRNodeWrapper node = (JCRNodeWrapper) session.getNode(path);
                for (String p : props) if (node.hasProperty(p)) target.put(p, node.getProperty(p).getString());
                return null;
            });
        } catch (Exception e) {
            logger.warn("[SFDC-Config] Could not read {} — using defaults: {}", path, e.getMessage());
        }
    }

    /** Reads a sync-state node: lastSyncAt (as ISO), lastSyncStatus, plus the given count props. */
    private void readState(String path, Map<String, String> target, String... countProps) {
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                if (!session.nodeExists(path)) return null;
                JCRNodeWrapper node = (JCRNodeWrapper) session.getNode(path);
                String iso = isoOf(node, "lastSyncAt");
                if (iso != null) target.put("lastSyncAt", iso);
                if (node.hasProperty("lastSyncStatus")) target.put("lastSyncStatus", node.getProperty("lastSyncStatus").getString());
                for (String p : countProps) if (node.hasProperty(p)) target.put(p, node.getProperty(p).getString());
                return null;
            });
        } catch (Exception e) {
            logger.error("[SFDC-Config] Failed to load sync state at {}", path, e);
        }
    }

    private static void setDate(JCRNodeWrapper node, String name, String iso) throws RepositoryException {
        if (iso == null || iso.isBlank()) return;
        node.setProperty(name, GregorianCalendar.from(Instant.parse(iso).atZone(ZoneOffset.UTC)));
    }

    private static Calendar nowCalendar() {
        return GregorianCalendar.from(Instant.now().atZone(ZoneOffset.UTC));
    }

    /** Returns a property as an ISO-8601 instant string, whether it is stored as date or (legacy) string. */
    private static String isoOf(JCRNodeWrapper node, String name) throws RepositoryException {
        if (!node.hasProperty(name)) return null;
        Value v = node.getProperty(name).getValue();
        if (v.getType() == PropertyType.DATE) return v.getDate().toInstant().toString();
        return v.getString();
    }

    private static boolean toBool(String s) { return "true".equalsIgnoreCase(s); }
    private static String orEmpty(String s) { return s == null ? "" : s; }
}
