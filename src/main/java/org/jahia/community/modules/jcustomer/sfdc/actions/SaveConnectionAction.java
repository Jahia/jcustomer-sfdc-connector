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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves SF connection credentials to JCR and invalidates the cached token.
 * POST *.saveConnection.do
 * Body (JSON): { instanceUrl, clientId, sfUsername, privateKey, apiVersion, enabled }
 */
@Component(service = Action.class, immediate = true)
public class SaveConnectionAction extends Action {

    private ConnectorConfigService configService;
    private SfdcAuthService        authService;

    @Reference public void setConfigService(ConnectorConfigService s) { this.configService = s; }
    @Reference public void setAuthService(SfdcAuthService s)          { this.authService   = s; }
    @Override public String getName() { return "saveConnection"; }

    @Override
    public ActionResult doExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {
        try {
            String body = readBody(req);
            JSONObject json = new JSONObject(body);

            Map<String, String> values = new HashMap<>();
            for (String key : new String[]{"instanceUrl","clientId","sfUsername","privateKey","apiVersion","enabled"}) {
                if (json.has(key)) values.put(key, json.getString(key));
            }

            configService.saveConnection(values);
            authService.invalidateToken(); // force re-auth with new credentials

            GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject().put("status", "saved").toString());
        } catch (Exception e) {
            GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject().put("error", e.getMessage()).toString(), 500);
        }
        return ActionResult.OK;
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
