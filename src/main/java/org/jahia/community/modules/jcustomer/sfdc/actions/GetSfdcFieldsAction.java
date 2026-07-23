package org.jahia.community.modules.jcustomer.sfdc.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.community.modules.jcustomer.sfdc.client.SfdcApiClient;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

/**
 * Returns Salesforce field metadata for Contact and Account objects.
 * Used by the mapping UI to populate the SF field dropdowns.
 *
 * POST *.getSfdcFields.do
 * Response: { "Contact": [...fields], "Account": [...fields] }
 */
@Component(service = Action.class, immediate = true)
public class GetSfdcFieldsAction extends AbstractSfdcAdminAction {

    private static final Logger logger = LoggerFactory.getLogger(GetSfdcFieldsAction.class);

    private SfdcApiClient apiClient;

    @Reference
    public void setApiClient(SfdcApiClient s) { this.apiClient = s; }

    @Override public String getName() { return "getSfdcFields"; }

    @Override
    protected ActionResult doAuthorizedExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {
        try {
            List<Map<String, String>> contactFields = apiClient.describeObject("Contact");
            List<Map<String, String>> accountFields = apiClient.describeObject("Account");

            JSONObject response = new JSONObject();
            response.put("Contact", toJsonArray(contactFields));
            response.put("Account", toJsonArray(accountFields));

            write(ctx.getResponse(), response.toString());
            return ActionResult.OK;
        } catch (Exception e) {
            logger.error("[SFDC-Action] getSfdcFields failed", e);
            write(ctx.getResponse(), new JSONObject().put("error", e.getMessage()).toString(), 500);
            return ActionResult.OK;
        }
    }

    private JSONArray toJsonArray(List<Map<String, String>> fields) {
        JSONArray arr = new JSONArray();
        for (Map<String, String> f : fields) {
            JSONObject obj = new JSONObject();
            f.forEach(obj::put);
            arr.put(obj);
        }
        return arr;
    }

    static void write(HttpServletResponse response, String json) throws Exception {
        write(response, json, 200);
    }

    static void write(HttpServletResponse response, String json, int status) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(json);
        response.getWriter().flush();
        // Commit the response now. The render pipeline calls resp.setStatus(ActionResult.getResultCode())
        // after doExecute returns; since every action returns ActionResult.OK (200), that call would
        // otherwise overwrite an error status set above (e.g. 500). Committing here makes the status set
        // by this method authoritative — getWriter().flush() alone does not commit small responses.
        response.flushBuffer();
    }
}
