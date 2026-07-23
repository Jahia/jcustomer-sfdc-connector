package org.jahia.community.modules.jcustomer.sfdc.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.community.modules.jcustomer.sfdc.client.SfdcApiClient;
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
 * Tests the SF connection by fetching the API limits endpoint.
 * POST *.testConnection.do → { ok: true, instanceUrl } or { ok: false, error }
 */
@Component(service = Action.class, immediate = true)
public class TestConnectionAction extends AbstractSfdcAdminAction {

    private SfdcApiClient apiClient;
    @Reference public void setApiClient(SfdcApiClient s) { this.apiClient = s; }
    @Override public String getName() { return "testConnection"; }

    @Override
    protected ActionResult doAuthorizedExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {
        try {
            String instanceUrl = apiClient.testConnection();
            GetSfdcFieldsAction.write(ctx.getResponse(),
                new JSONObject().put("ok", true).put("instanceUrl", instanceUrl).toString());
        } catch (Exception e) {
            GetSfdcFieldsAction.write(ctx.getResponse(),
                new JSONObject().put("ok", false).put("error", e.getMessage()).toString(), 200);
        }
        return ActionResult.OK;
    }
}
