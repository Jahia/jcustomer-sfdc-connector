package org.jahia.community.modules.jcustomer.sfdc.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.community.modules.jcustomer.sfdc.sync.SfdcOutboundSyncService;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.util.List;
import java.util.Map;

/**
 * Manually pushes a single jCustomer profile (matched by email) to a Salesforce Lead.
 * POST *.triggerOutboundSync.do
 * Body (JSON): { "email": "user@example.com", "siteKey"?: "mysoprahr" }
 *
 * Bulk/event-driven outbound is handled by the reconcile job and the Unomi event webhook.
 */
@Component(service = Action.class, immediate = true)
public class TriggerOutboundSyncAction extends AbstractSfdcAdminAction {

    private SfdcOutboundSyncService outboundService;
    @Reference public void setOutboundService(SfdcOutboundSyncService s) { this.outboundService = s; }
    @Override public String getName() { return "triggerOutboundSync"; }

    @Override
    protected ActionResult doAuthorizedExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {
        try {
            JSONObject json = new JSONObject(readBody(req));
            String email   = json.optString("email", "").trim();
            String siteKey = json.optString("siteKey", "mysoprahr");

            if (email.isEmpty()) {
                GetSfdcFieldsAction.write(ctx.getResponse(),
                    new JSONObject().put("error", "email is required").toString(), 400);
                return ActionResult.OK;
            }

            boolean pushed = outboundService.pushForEmail(email, siteKey);
            GetSfdcFieldsAction.write(ctx.getResponse(),
                new JSONObject().put("status", pushed ? "pushed" : "skipped").put("email", email).toString());
        } catch (Exception e) {
            GetSfdcFieldsAction.write(ctx.getResponse(),
                new JSONObject().put("error", e.getMessage()).toString(), 500);
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
