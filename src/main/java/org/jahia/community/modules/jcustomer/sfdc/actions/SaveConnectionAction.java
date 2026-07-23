package org.jahia.community.modules.jcustomer.sfdc.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.community.modules.jcustomer.sfdc.auth.SfdcAuthService;
import org.jahia.community.modules.jcustomer.sfdc.config.ConnectorConfigService;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves SF connection credentials to JCR and invalidates the cached token.
 * POST *.saveConnection.do
 * Body (JSON): { instanceUrl, clientId, sfUsername, privateKey, apiVersion, enabled }
 */
@Component(service = Action.class, immediate = true)
public class SaveConnectionAction extends AbstractSfdcAdminAction {

    private ConnectorConfigService configService;
    private SfdcAuthService        authService;

    @Reference public void setConfigService(ConnectorConfigService s) { this.configService = s; }
    @Reference public void setAuthService(SfdcAuthService s)          { this.authService   = s; }
    @Override public String getName() { return "saveConnection"; }

    @Override
    protected ActionResult doAuthorizedExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {
        try {
            String body = readBody(req);
            JSONObject json = new JSONObject(body);

            Map<String, String> values = new HashMap<>();
            for (String key : new String[]{"instanceUrl","clientId","sfUsername","privateKey","apiVersion","enabled"}) {
                if (json.has(key)) values.put(key, json.getString(key));
            }

            // SSRF guard: the instance URL is later used verbatim as the base for outbound HTTP
            // calls that carry the org's signed JWT / OAuth token, so it must point at Salesforce
            // and nowhere else. Reject anything that is not an https Salesforce host.
            validateInstanceUrl(values.get("instanceUrl"));

            configService.saveConnection(values);
            authService.invalidateToken(); // force re-auth with new credentials

            GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject().put("status", "saved").toString());
        } catch (Exception e) {
            GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject().put("error", e.getMessage()).toString(), 500);
        }
        return ActionResult.OK;
    }

    /**
     * Validates that the Salesforce instance URL is an {@code https} URL whose host is a Salesforce
     * domain. This blocks SSRF / credential-exfiltration: without it, an admin could be tricked
     * (or a compromised admin could choose) to point the connector at an internal address or an
     * attacker-controlled host, which then receives the org's RSA-signed JWT bearer assertion.
     *
     * @throws IllegalArgumentException if the URL is missing, not https, or not a Salesforce host
     */
    static void validateInstanceUrl(String instanceUrl) {
        if (instanceUrl == null || instanceUrl.isBlank()) {
            throw new IllegalArgumentException("instanceUrl is required");
        }
        URI uri;
        try {
            uri = URI.create(instanceUrl.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("instanceUrl is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("instanceUrl must use https");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("instanceUrl has no host");
        }
        host = host.toLowerCase();
        boolean allowed = host.equals("salesforce.com")
                || host.endsWith(".salesforce.com")
                || host.endsWith(".force.com");
        if (!allowed) {
            throw new IllegalArgumentException(
                    "instanceUrl host '" + host + "' is not a Salesforce domain");
        }
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
