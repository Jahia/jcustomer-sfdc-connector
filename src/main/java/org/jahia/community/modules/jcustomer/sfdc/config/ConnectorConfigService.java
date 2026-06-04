package org.jahia.community.modules.jcustomer.sfdc.config;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes connector configuration from/to JCR.
 *
 * JCR structure (under /settings/jcustomer-sfdc-connector):
 *   connection/   → instanceUrl, clientId, sfUsername, privateKey, apiVersion, enabled
 *   mapping/
 *     Contact/    → orderable child nodes: sfField, jcProperty
 *     Account/    → orderable child nodes: sfField, jcProperty (via Account.X relationship)
 *   syncState/    → lastSyncAt, lastSyncStatus, lastSyncCount, lastSyncErrors
 */
@Component(service = ConnectorConfigService.class, immediate = true)
public class ConnectorConfigService {

    private static final Logger logger = LoggerFactory.getLogger(ConnectorConfigService.class);

    public static final String CONFIG_ROOT  = "/settings/jcustomer-sfdc-connector";
    public static final String CONN_NODE    = CONFIG_ROOT + "/connection";
    public static final String MAPPING_NODE = CONFIG_ROOT + "/mapping";
    public static final String STATE_NODE   = CONFIG_ROOT + "/syncState";

    // ── Connection ────────────────────────────────────────────────────────────

    public Map<String, String> loadConnection() {
        Map<String, String> result = new HashMap<>();
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                if (!session.nodeExists(CONN_NODE)) return null;
                JCRNodeWrapper node = (JCRNodeWrapper) session.getNode(CONN_NODE);
                for (String prop : new String[]{"instanceUrl","clientId","sfUsername","privateKey","apiVersion","enabled"}) {
                    if (node.hasProperty(prop)) {
                        result.put(prop, node.getProperty(prop).getString());
                    }
                }
                result.putIfAbsent("apiVersion", "v66.0"); // default
                return null;
            });
        } catch (Exception e) {
            logger.error("[SFDC-Config] Failed to load connection config", e);
        }
        return result;
    }

    public void saveConnection(Map<String, String> values) throws Exception {
        JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
            JCRNodeWrapper root = ensurePath(session, CONFIG_ROOT);
            JCRNodeWrapper conn = ensureChild(root, "connection");
            for (Map.Entry<String, String> e : values.entrySet()) {
                conn.setProperty(e.getKey(), e.getValue());
            }
            session.save();
            return null;
        });
        logger.info("[SFDC-Config] Connection config saved");
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    /** Returns mapping for a given SF object ("Contact" or "Account"). */
    public List<Map<String, String>> loadMapping(String sfObject) {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                String path = MAPPING_NODE + "/" + sfObject;
                if (!session.nodeExists(path)) return null;
                JCRNodeWrapper folder = (JCRNodeWrapper) session.getNode(path);
                NodeIterator it = folder.getNodes();
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
            logger.error("[SFDC-Config] Failed to load mapping for {}", sfObject, e);
        }
        return result;
    }

    /** Replaces the full mapping for a given SF object. */
    public void saveMapping(String sfObject, List<Map<String, String>> mappings) throws Exception {
        JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
            JCRNodeWrapper root    = ensurePath(session, CONFIG_ROOT);
            JCRNodeWrapper mapRoot = ensureChild(root, "mapping");
            JCRNodeWrapper folder  = ensureChild(mapRoot, sfObject);

            // Remove existing children
            NodeIterator it = folder.getNodes();
            while (it.hasNext()) it.nextNode().remove();

            // Add new mappings
            int i = 0;
            for (Map<String, String> m : mappings) {
                JCRNodeWrapper node = folder.addNode("mapping-" + i, "nt:unstructured");
                if (m.containsKey("sfField"))    node.setProperty("sfField",    m.get("sfField"));
                if (m.containsKey("jcProperty")) node.setProperty("jcProperty", m.get("jcProperty"));
                if (m.containsKey("required"))   node.setProperty("required",   m.get("required"));
                i++;
            }
            session.save();
            return null;
        });
        logger.info("[SFDC-Config] Mapping saved for {} ({} entries)", sfObject, mappings.size());
    }

    // ── Sync state ────────────────────────────────────────────────────────────

    public Map<String, String> loadSyncState() {
        Map<String, String> state = new HashMap<>();
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                if (!session.nodeExists(STATE_NODE)) return null;
                JCRNodeWrapper node = (JCRNodeWrapper) session.getNode(STATE_NODE);
                for (String prop : new String[]{"lastSyncAt","lastSyncStatus","lastSyncCount","lastSyncErrors"}) {
                    if (node.hasProperty(prop)) state.put(prop, node.getProperty(prop).getString());
                }
                return null;
            });
        } catch (Exception e) {
            logger.error("[SFDC-Config] Failed to load sync state", e);
        }
        return state;
    }

    public void saveSyncState(String status, long count, long errors) {
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                JCRNodeWrapper root  = ensurePath(session, CONFIG_ROOT);
                JCRNodeWrapper state = ensureChild(root, "syncState");
                state.setProperty("lastSyncAt",     java.time.Instant.now().toString());
                state.setProperty("lastSyncStatus",  status);
                state.setProperty("lastSyncCount",   String.valueOf(count));
                state.setProperty("lastSyncErrors",  String.valueOf(errors));
                session.save();
                return null;
            });
        } catch (Exception e) {
            logger.error("[SFDC-Config] Failed to save sync state", e);
        }
    }

    public String getLastSyncAt() {
        return loadSyncState().getOrDefault("lastSyncAt", null);
    }

    // ── Triggers ──────────────────────────────────────────────────────────────

    public static final String TRIGGERS_NODE = CONFIG_ROOT + "/triggers";

    public Map<String, String> loadTriggers() {
        Map<String, String> result = new HashMap<>();
        // defaults
        result.put("loginEnabled",    "true");
        result.put("schedulerEnabled","true");
        result.put("schedulerCron",   "0 0 5 * * ?");
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                if (!session.nodeExists(TRIGGERS_NODE)) return null;
                JCRNodeWrapper node = (JCRNodeWrapper) session.getNode(TRIGGERS_NODE);
                for (String prop : new String[]{"loginEnabled","schedulerEnabled","schedulerCron"}) {
                    if (node.hasProperty(prop)) result.put(prop, node.getProperty(prop).getString());
                }
                return null;
            });
        } catch (Exception e) {
            logger.warn("[SFDC-Config] Could not load triggers — using defaults: {}", e.getMessage());
        }
        return result;
    }

    public void saveTriggers(Map<String, String> values) throws Exception {
        JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
            JCRNodeWrapper root     = ensurePath(session, CONFIG_ROOT);
            JCRNodeWrapper triggers = ensureChild(root, "triggers");
            for (Map.Entry<String, String> e : values.entrySet()) {
                triggers.setProperty(e.getKey(), e.getValue());
            }
            session.save();
            return null;
        });
        logger.info("[SFDC-Config] Triggers saved: {}", values);
    }

    // ── JCR helpers ───────────────────────────────────────────────────────────

    private JCRNodeWrapper ensurePath(JCRSessionWrapper session, String path) throws RepositoryException {
        if (session.nodeExists(path)) return (JCRNodeWrapper) session.getNode(path);
        String[] parts = path.substring(1).split("/");
        Node current = session.getRootNode();
        for (String part : parts) {
            if (!current.hasNode(part)) current = current.addNode(part, "nt:unstructured");
            else current = current.getNode(part);
        }
        return (JCRNodeWrapper) current;
    }

    private JCRNodeWrapper ensureChild(JCRNodeWrapper parent, String name) throws RepositoryException {
        if (parent.hasNode(name)) return (JCRNodeWrapper) parent.getNode(name);
        return (JCRNodeWrapper) parent.addNode(name, "nt:unstructured");
    }
}
