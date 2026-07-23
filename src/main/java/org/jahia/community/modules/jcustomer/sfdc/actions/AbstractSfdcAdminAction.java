package org.jahia.community.modules.jcustomer.sfdc.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.exceptions.JahiaForbiddenAccessException;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.jahia.services.usermanager.JahiaGroupManagerService;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Base class for all connector admin actions.
 *
 * <p>Every action of this module administers a single, server-wide integration (Salesforce
 * credentials, field mappings, sync triggers) and is exposed on {@code /sites/systemsite}. Two
 * cross-cutting security controls are therefore enforced here, once, for every subclass — they
 * cannot be forgotten on an individual action:
 *
 * <ol>
 *   <li><b>Authorization</b> — the caller must be an administrator: either a <em>server
 *       administrator</em> ({@code root} / the global {@code /groups/administrators} group) or a
 *       <em>site administrator</em> of the connector's configured site ({@code jcustomer.site.key}).
 *       The stock {@link Action} contract only guarantees an <em>authenticated</em> user
 *       ({@link #isRequireAuthenticatedUser()} defaults to {@code true}); without this gate any
 *       logged-in low-privilege user (e.g. a site contributor) could read and overwrite the
 *       Salesforce credentials or retarget the integration.</li>
 *   <li><b>Same-origin (CSRF) check</b> — because the module CSRF-guard whitelist exempts these
 *       {@code .do} endpoints from token enforcement, a state-changing request must originate from
 *       the Jahia host itself. The {@code Origin}/{@code Referer} host (which a browser sets and
 *       page script cannot forge) must match the request host; otherwise the request is a
 *       cross-origin confused-deputy attempt and is rejected.</li>
 * </ol>
 *
 * <p>Subclasses implement {@link #doAuthorizedExecute} instead of {@code doExecute}. When either
 * control fails a {@link JahiaForbiddenAccessException} is thrown, which the render pipeline turns
 * into an HTTP 403 before any action logic runs.
 */
public abstract class AbstractSfdcAdminAction extends Action {

    private static final Logger logger = LoggerFactory.getLogger(AbstractSfdcAdminAction.class);

    /**
     * Enforces authorization + same-origin, then delegates to {@link #doAuthorizedExecute}.
     * Declared {@code final} so a subclass cannot accidentally bypass the security controls.
     */
    @Override
    public final ActionResult doExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                        JCRSessionWrapper session, Map<String, List<String>> params,
                                        URLResolver url) throws Exception {
        requireAdmin();
        requireSameOrigin(req);
        return doAuthorizedExecute(req, ctx, res, session, params, url);
    }

    /**
     * Executes the action logic. Only reached once the caller is an administrator (server or the
     * connector site) and the request is same-origin.
     */
    protected abstract ActionResult doAuthorizedExecute(HttpServletRequest req, RenderContext ctx,
                                                        Resource res, JCRSessionWrapper session,
                                                        Map<String, List<String>> params,
                                                        URLResolver url) throws Exception;

    // ── Security controls ───────────────────────────────────────────────────────

    /** OSGi configuration PID carrying {@code jcustomer.site.key}. */
    private static final String CONFIG_PID = "org.jahia.community.jcustomer.sfdc";
    /** Fallback site key when the configuration is absent (matches the shipped module default). */
    private static final String DEFAULT_SITE_KEY = "mysoprahr";

    /**
     * Rejects any caller that is not an administrator. Allowed principals: the {@code root} user, a
     * member of the global {@code /groups/administrators} group (server administrator), or a member
     * of the connector site's {@code site-administrators} group (site administrator of the site the
     * connector serves — {@code jcustomer.site.key}).
     */
    private void requireAdmin() throws JahiaForbiddenAccessException {
        JahiaUser user = getCurrentUser();
        boolean allowed = false;
        if (user != null && !JahiaUserManagerService.isGuest(user)) {
            JahiaGroupManagerService groups = JahiaGroupManagerService.getInstance();
            allowed = groups.isAdminMember(user.getUsername(), null)                 // server administrator
                    || groups.isAdminMember(user.getUsername(), connectorSiteKey()); // site administrator of the connector site
        }
        if (!allowed) {
            logger.warn("[SFDC-Auth] Forbidden '{}' attempt by non-admin user '{}'",
                    getName(), user == null ? "<anonymous>" : user.getUsername());
            throw new JahiaForbiddenAccessException("Administrator privilege is required");
        }
    }

    /**
     * Resolves the connector's site key from the {@code org.jahia.community.jcustomer.sfdc} OSGi
     * configuration, falling back to {@link #DEFAULT_SITE_KEY}. Read-only; never creates a config.
     */
    private String connectorSiteKey() {
        try {
            ConfigurationAdmin ca = BundleUtils.getOsgiService(ConfigurationAdmin.class, null);
            if (ca != null) {
                Configuration[] cfgs = ca.listConfigurations("(service.pid=" + CONFIG_PID + ")");
                if (cfgs != null && cfgs.length > 0 && cfgs[0].getProperties() != null) {
                    Object v = cfgs[0].getProperties().get("jcustomer.site.key");
                    if (v != null && !v.toString().isBlank()) {
                        return v.toString();
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("[SFDC-Auth] Could not read site key from config, using default '{}': {}",
                    DEFAULT_SITE_KEY, e.getMessage());
        }
        return DEFAULT_SITE_KEY;
    }

    /**
     * Rejects cross-origin requests. A browser always attaches an {@code Origin} (and/or
     * {@code Referer}) header to a state-changing fetch and forbids page script from overriding it,
     * so a mismatch means the request was triggered from another site (CSRF).
     */
    private void requireSameOrigin(HttpServletRequest req) throws JahiaForbiddenAccessException {
        String claimedHost = hostOf(req.getHeader("Origin"));
        if (claimedHost == null) {
            claimedHost = hostOf(req.getHeader("Referer"));
        }
        if (claimedHost != null && !claimedHost.equalsIgnoreCase(req.getServerName())) {
            logger.warn("[SFDC-Auth] Forbidden cross-origin '{}' request — origin host '{}' != server '{}'",
                    getName(), claimedHost, req.getServerName());
            throw new JahiaForbiddenAccessException("Cross-origin request rejected");
        }
    }

    /** Extracts the host component of an Origin/Referer header value, or {@code null} if unparseable. */
    private static String hostOf(String headerValue) {
        if (headerValue == null || headerValue.isBlank() || "null".equals(headerValue)) {
            return null;
        }
        try {
            return URI.create(headerValue.trim()).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
