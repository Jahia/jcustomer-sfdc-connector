package org.jahia.community.modules.jcustomer.sfdc.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.community.modules.jcustomer.sfdc.config.ConnectorConfigService;
import org.jahia.community.modules.jcustomer.sfdc.sync.SfdcOutboundSyncService;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Webhook called by jCustomer (Unomi) when a monitored event fires, to push the affected profile
 * to Salesforce as a Lead. Server-to-server (Unomi → Jahia) — so, unlike the admin actions, it is
 * <b>not</b> behind the server/site-admin + same-origin gate. It does NOT extend
 * {@code AbstractSfdcAdminAction}.
 *
 * <p>Instead it authenticates with a <b>shared secret</b> ({@code X-Sfdc-Webhook-Secret} header,
 * constant-time compared to {@code outbound.webhook.secret} from the OSGi config). It is declared
 * anonymous ({@code requireAuthenticatedUser=false}) so Unomi can call it as guest against a
 * guest-readable live node; the only capability exposed is "push the profile with this email
 * through the configured Lead mapping". Fail-closed: if no secret is configured, it returns 503.
 *
 * <p>Endpoint: {@code POST /cms/render/live/{lang}{publicLiveNode}.sfdcOutboundWebhook.do}
 * Body (JSON): {@code { "email": "...", "eventType": "..." }}. The Salesforce push runs on a
 * background thread so the response returns immediately (Unomi runs rule actions synchronously).
 */
@Component(service = Action.class, immediate = true, configurationPid = "org.jahia.community.jcustomer.sfdc")
public class SfdcOutboundWebhookAction extends Action {

    private static final Logger logger = LoggerFactory.getLogger(SfdcOutboundWebhookAction.class);
    private static final String SECRET_HEADER = "X-Sfdc-Webhook-Secret";

    @interface Config {
        String jcustomer_site_key() default "mysoprahr";
    }

    private volatile String siteKey = "mysoprahr";

    private SfdcOutboundSyncService outboundService;
    private ConnectorConfigService  configService;
    @Reference public void setOutboundService(SfdcOutboundSyncService s) { this.outboundService = s; }
    @Reference public void setConfigService(ConnectorConfigService s)    { this.configService = s; }

    public SfdcOutboundWebhookAction() {
        // Anonymous, server-to-server: authenticated by the shared secret, not by a Jahia session.
        setRequireAuthenticatedUser(false);
        setRequiredPermission(null);
    }

    @Activate @Modified
    public void activate(Config config) {
        this.siteKey = config.jcustomer_site_key();
        logger.info("[SFDC-Webhook] Configured — site={}", siteKey);
    }

    @Override public String getName() { return "sfdcOutboundWebhook"; }

    @Override
    public ActionResult doExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                  JCRSessionWrapper session, Map<String, List<String>> params,
                                  URLResolver url) throws Exception {
        String secret = configService.getOrCreateWebhookSecret();
        if (secret == null || secret.isBlank()) {
            GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject().put("error", "not configured").toString(), 503);
            return ActionResult.OK;
        }
        if (!constantTimeEquals(req.getHeader(SECRET_HEADER), secret)) {
            logger.warn("[SFDC-Webhook] Rejected request with invalid/missing secret from {}", req.getRemoteAddr());
            GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject().put("error", "forbidden").toString(), 403);
            return ActionResult.OK;
        }

        String email;
        String eventType;
        try {
            JSONObject body = new JSONObject(readBody(req));
            email     = body.optString("email", "").trim();
            eventType = body.optString("eventType", "unknown");
        } catch (Exception e) {
            GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject().put("error", "invalid body").toString(), 400);
            return ActionResult.OK;
        }

        if (email.isEmpty()) {
            // Profile-update trigger requires an email to match/create the Lead — nothing to do.
            GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject().put("status", "skipped-no-email").toString());
            return ActionResult.OK;
        }

        // Hand off to a background thread — Unomi runs actions synchronously, so return fast.
        final String pushEmail = email;
        final String site = siteKey;
        Thread t = new Thread(() -> {
            try {
                outboundService.pushForEmail(pushEmail, site);
            } catch (Exception e) {
                logger.error("[SFDC-Webhook] Async push failed for {}: {}", pushEmail, e.getMessage());
            }
        });
        t.setName("sfdc-webhook-" + email);
        t.setDaemon(true);
        t.start();

        logger.info("[SFDC-Webhook] Accepted push for email={} (eventType={})", email, eventType);
        GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject().put("status", "accepted").put("email", email).toString());
        return ActionResult.OK;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private String readBody(HttpServletRequest req) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
