package org.jahia.community.modules.jcustomer.sfdc.events;

import org.jahia.community.modules.jcustomer.sfdc.config.ConnectorConfigService;
import org.jahia.community.modules.jcustomer.sfdc.sync.SfdcSyncService;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.usermanager.JahiaUser;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Listens to Jahia login events and triggers an immediate single-user SF sync.
 *
 * Fires on every login (OIDC, local, or any other method).
 * The sync runs on a daemon thread so the login is never delayed.
 *
 * Event topic: org/jahia/usersgroups/login/LOGIN
 * Config:  sync.on.login=true (default), jcustomer.site.key
 */
@Component(
    service = EventHandler.class,
    immediate = true,
    property = EventConstants.EVENT_TOPIC + "=org/jahia/usersgroups/login/LOGIN",
    configurationPid = "org.jahia.community.jcustomer.sfdc"
)
public class SfdcLoginEventHandler implements EventHandler {

    private static final Logger logger = LoggerFactory.getLogger(SfdcLoginEventHandler.class);

    @interface Config {
        boolean sync_on_login()      default true;
        String  jcustomer_site_key() default "mysoprahr";
    }

    private volatile boolean defaultEnabled = true;
    private volatile String  siteKey        = "mysoprahr";

    private volatile ConnectorConfigService configService;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    public void setConfigService(ConnectorConfigService s) { this.configService = s; }
    public void unsetConfigService(ConnectorConfigService s) { this.configService = null; }

    @Activate
    public void activate(Config config) {
        this.defaultEnabled = config.sync_on_login();
        this.siteKey        = config.jcustomer_site_key();
        logger.info("[SFDC-Login] Handler activated — defaultEnabled={}, site={}", defaultEnabled, siteKey);
    }

    /** Reads loginEnabled from JCR triggers config (falls back to OSGi default). */
    private boolean isLoginSyncEnabled() {
        if (configService != null) {
            try {
                String val = configService.loadTriggers().get("loginEnabled");
                if (val != null) return !"false".equalsIgnoreCase(val);
            } catch (Exception ignored) {}
        }
        return defaultEnabled;
    }

    @Override
    public void handleEvent(Event event) {
        if (!isLoginSyncEnabled()) {
            logger.debug("[SFDC-Login] Login sync disabled — skipping");
            return;
        }

        try {
            // Log all event properties for diagnostic purposes
            if (logger.isDebugEnabled()) {
                logger.debug("[SFDC-Login] Event received. Properties: {}", Arrays.toString(event.getPropertyNames()));
            }

            // ── Extract user email — try multiple strategies ──────────────────

            String email = null;

            // Strategy 1: "user" property as JahiaUser
            Object userObj = event.getProperty("user");
            if (userObj instanceof JahiaUser) {
                JahiaUser user = (JahiaUser) userObj;
                // Skip guest
                if ("guest".equals(user.getUsername())) return;

                // Try j:email property first
                email = user.getProperty("j:email");

                // Fallback: username might be the email (common for OIDC)
                if ((email == null || email.isBlank()) && user.getUsername().contains("@")) {
                    email = user.getUsername();
                }

                // Fallback: look up user JCR node for email
                if (email == null || email.isBlank()) {
                    try {
                        email = JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                            String path = user.getLocalPath();
                            if (path != null && session.nodeExists(path)) {
                                org.jahia.services.content.JCRNodeWrapper node =
                                    (org.jahia.services.content.JCRNodeWrapper) session.getNode(path);
                                if (node.hasProperty("j:email")) {
                                    return node.getProperty("j:email").getString();
                                }
                            }
                            return null;
                        });
                    } catch (Exception ignored) {}
                }

                logger.info("[SFDC-Login] Login event — user={}, email={}", user.getUsername(), email);
            }

            // Strategy 2: "username" property as string (some event variants)
            if ((email == null || email.isBlank())) {
                Object usernameObj = event.getProperty("username");
                if (usernameObj instanceof String) {
                    String username = (String) usernameObj;
                    if (username.contains("@")) email = username;
                }
            }

            if (email == null || email.isBlank()) {
                logger.info("[SFDC-Login] Could not determine user email from event — skipping sync");
                return;
            }

            // ── Fire async sync ───────────────────────────────────────────────
            final String userEmail = email;
            final String site      = this.siteKey;

            Thread t = new Thread(() -> {
                try {
                    SfdcSyncService syncService = BundleUtils.getOsgiService(SfdcSyncService.class, null);
                    if (syncService == null) {
                        logger.warn("[SFDC-Login] SfdcSyncService not available");
                        return;
                    }
                    logger.info("[SFDC-Login] Starting login sync for {}", userEmail);
                    syncService.syncForEmail(userEmail, site);
                } catch (Exception e) {
                    logger.error("[SFDC-Login] Async sync failed for {}: {}", userEmail, e.getMessage());
                }
            });
            t.setName("sfdc-login-" + email);
            t.setDaemon(true);
            t.start();

        } catch (Exception e) {
            logger.error("[SFDC-Login] Login event handler error", e);
        }
    }
}
