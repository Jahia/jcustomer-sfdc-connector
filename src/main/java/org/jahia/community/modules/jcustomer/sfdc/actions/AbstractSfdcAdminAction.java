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
import org.jahia.community.modules.jcustomer.sfdc.config.SiteKeyResolver;
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
 *       <em>site administrator</em> of the connector's site ({@code jcustomer.site.key}, or the auto-detected jExperience site).
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

    /**
     * Rejects any caller that is not an administrator. Allowed principals: the {@code root} user, a
     * member of the global {@code /groups/administrators} group (server administrator), or a member
     * of the connector site's {@code site-administrators} group (site administrator of the site the
     * connector serves, see {@link SiteKeyResolver}).
     */
    private void requireAdmin() throws JahiaForbiddenAccessException {
        JahiaUser user = getCurrentUser();
        boolean allowed = false;
        if (user != null && !JahiaUserManagerService.isGuest(user)) {
            JahiaGroupManagerService groups = JahiaGroupManagerService.getInstance();
            String site = connectorSiteKey();
            allowed = groups.isAdminMember(user.getUsername(), null)                         // server administrator
                    || (site != null && groups.isAdminMember(user.getUsername(), site));    // site administrator of the connector site
        }
        if (!allowed) {
            logger.warn("[SFDC-Auth] Forbidden '{}' attempt by non-admin user '{}'",
                    getName(), user == null ? "<anonymous>" : user.getUsername());
            throw new JahiaForbiddenAccessException("Administrator privilege is required");
        }
    }

    /**
     * Returns the connector's site ({@link SiteKeyResolver}: configured {@code jcustomer.site.key}, else
     * the single jExperience site), or {@code null} when none resolves; then only server
     * administrators are allowed. The request's own site key is deliberately not used: the connector
     * configuration is global, so a caller-supplied site must not widen who may change it.
     */
    private String connectorSiteKey() {
        SiteKeyResolver resolver = BundleUtils.getOsgiService(SiteKeyResolver.class, null);
        return resolver != null ? resolver.resolve() : null;
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
