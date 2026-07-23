package org.jahia.community.modules.jcustomer.sfdc.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.community.modules.jcustomer.sfdc.config.ConnectorConfigService;
import org.jahia.community.modules.jcustomer.sfdc.sync.SfdcOutboundReconcileJob;
import org.jahia.osgi.BundleUtils;
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
 * Saves the outbound trigger configuration.
 * POST *.saveOutboundTriggers.do
 * Body (JSON): { enabled, onProfileUpdate, onFormSubmission }
 */
@Component(service = Action.class, immediate = true)
public class SaveOutboundTriggersAction extends AbstractSfdcAdminAction {

    private ConnectorConfigService configService;
    @Reference public void setConfigService(ConnectorConfigService s) { this.configService = s; }
    @Override public String getName() { return "saveOutboundTriggers"; }

    @Override
    protected ActionResult doAuthorizedExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {
        try {
            JSONObject json = new JSONObject(readBody(req));
            Map<String, String> values = new HashMap<>();
            for (String key : new String[]{"enabled", "onProfileUpdate", "onFormSubmission", "outboundObject", "reconcileEnabled", "reconcileCron"}) {
                if (json.has(key)) values.put(key, json.get(key).toString());
            }
            configService.saveOutboundTriggers(values);

            // Apply the reconcile schedule change to the Quartz job.
            try {
                SfdcOutboundReconcileJob job = BundleUtils.getOsgiService(SfdcOutboundReconcileJob.class, null);
                if (job != null) job.reschedule();
            } catch (Exception e) {
                // saving succeeded; scheduling is best-effort (e.g. non-processing server)
            }

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
