package org.jahia.community.modules.jcustomer.sfdc.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.community.modules.jcustomer.sfdc.config.ConnectorConfigService;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * Returns saved connection settings from JCR.
 * The private key is masked — only its presence is indicated, not the value.
 * POST *.getConnection.do
 */
@Component(service = Action.class, immediate = true)
public class GetConnectionAction extends AbstractSfdcAdminAction {

    private ConnectorConfigService configService;
    @Reference public void setConfigService(ConnectorConfigService s) { this.configService = s; }
    @Override public String getName() { return "getConnection"; }

    @Override
    protected ActionResult doAuthorizedExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {
        Map<String, String> conn = configService.loadConnection();
        JSONObject response = new JSONObject();

        // Return all fields — private key is returned in full so the form can re-save it
        // JCR access is already admin-only; the action itself requires a Jahia session
        for (String key : new String[]{"instanceUrl", "clientId", "sfUsername", "apiVersion", "enabled"}) {
            response.put(key, conn.getOrDefault(key, ""));
        }
        // Indicate if private key is stored (don't expose value — user must re-paste to update)
        response.put("privateKeyStored", conn.containsKey("privateKey") && !conn.get("privateKey").isBlank());

        GetSfdcFieldsAction.write(ctx.getResponse(), response.toString());
        return ActionResult.OK;
    }
}
