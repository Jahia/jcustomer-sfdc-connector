/*
 * One-time migration: convert the connector configuration under
 * /settings/jcustomer-sfdc-connector from the legacy nt:unstructured layout to the typed
 * jcsfdcnt:* node types (see META-INF/definitions.cnd).
 *
 * Deploy AFTER the module version that ships the CND (so the types are registered), then run once
 * by dropping this file into <jahia>/patches/groovy/ (the Patcher executes it and renames it
 * .installed). Idempotent — a no-op if the config is already typed.
 */
import org.jahia.osgi.BundleUtils
import org.jahia.services.content.JCRTemplate
import org.jahia.services.content.JCRCallback
import org.jahia.services.content.JCRSessionWrapper
import org.jahia.services.content.nodetypes.NodeTypeRegistry
import org.jahia.osgi.FrameworkService
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("SFDC-Migrate")
def rootPath = "/settings/jcustomer-sfdc-connector"
def svcClassName = "org.jahia.community.modules.jcustomer.sfdc.config.ConnectorConfigService"

// Groovy patches run in Jahia's core classloader, which cannot see module classes.
// Locate the module bundle and load its service class through the bundle's own classloader.
def coreCtx = FrameworkService.getBundleContext()
def bundle = coreCtx.getBundles().find { it.getSymbolicName() == "jcustomer-sfdc-connector" }
if (bundle == null) { logger.warn("[SFDC-Migrate] module bundle jcustomer-sfdc-connector not found - is it installed?"); return ".failed" }

def svcClass = bundle.loadClass(svcClassName)
def svc = BundleUtils.getOsgiService(svcClass, null)
if (svc == null) { logger.warn("[SFDC-Migrate] config service not available - is the module started?"); return ".failed" }

// Safety: never run the destructive delete/recreate unless the target type is registered.
if (!NodeTypeRegistry.getInstance().hasNodeType("jcsfdcnt:connectorConfig")) {
    logger.warn("[SFDC-Migrate] node type jcsfdcnt:connectorConfig not registered yet - is the CND deployed? Aborting without changes"); return ".failed"
}

// Skip if already typed
def already = JCRTemplate.getInstance().doExecuteWithSystemSession({ JCRSessionWrapper s ->
    s.nodeExists(rootPath) && s.getNode(rootPath).isNodeType("jcsfdcnt:connectorConfig")
} as JCRCallback)
if (already) { logger.info("[SFDC-Migrate] already migrated — nothing to do"); return "already migrated" }

if (!JCRTemplate.getInstance().doExecuteWithSystemSession({ JCRSessionWrapper s -> s.nodeExists(rootPath) } as JCRCallback)) {
    logger.info("[SFDC-Migrate] no existing config — nothing to migrate"); return "no config"
}

// 1) Capture current values (the service reads tolerate the legacy nt:unstructured layout)
def conn       = svc.loadConnection()
def trig       = svc.loadTriggers()
def otrig      = svc.loadOutboundTriggers()
def reconcileAt = svc.getOutboundReconcileAt()
def inMap = [:];  ["Contact", "Account", "Lead"].each { inMap[it]  = svc.loadMapping(it) }
def outMap = [:]; ["Lead", "Contact"].each          { outMap[it] = svc.loadOutboundMapping(it) }

// 2) Delete the legacy subtree
JCRTemplate.getInstance().doExecuteWithSystemSession({ JCRSessionWrapper s ->
    if (s.nodeExists(rootPath)) { s.getNode(rootPath).remove(); s.save() }
    return null
} as JCRCallback)

// 3) Recreate typed via the (new) service
if (conn && conn.get("instanceUrl")) svc.saveConnection(conn)
svc.saveTriggers(trig)
svc.saveOutboundTriggers(otrig)
inMap.each  { k, v -> if (v && !v.isEmpty()) svc.saveMapping(k, v) }
outMap.each { k, v -> if (v && !v.isEmpty()) svc.saveOutboundMapping(k, v) }
if (reconcileAt) svc.setOutboundReconcileAt(reconcileAt)

logger.info("[SFDC-Migrate] connector config migrated to typed node types (jcsfdcnt:*)")
return "migrated"
