package org.jahia.community.modules.jcustomer.sfdc.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.community.modules.jcustomer.sfdc.config.ConnectorConfigService;
import org.jahia.community.modules.jcustomer.sfdc.sync.SfdcSyncJob;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves trigger configuration to JCR.
 * Also reschedules the Quartz job if the cron expression changed.
 * POST *.saveTriggers.do
 * Body: { loginEnabled, schedulerEnabled, schedulerCron }
 */
@Component(service = Action.class, immediate = true)
public class SaveTriggersAction extends AbstractSfdcAdminAction {

    private static final Logger logger = LoggerFactory.getLogger(SaveTriggersAction.class);

    private ConnectorConfigService configService;
    @Reference public void setConfigService(ConnectorConfigService s) { this.configService = s; }
    @Override public String getName() { return "saveTriggers"; }

    @Override
    protected ActionResult doAuthorizedExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {
        try {
            String body = readBody(req);
            JSONObject json = new JSONObject(body);

            Map<String, String> values = new HashMap<>();
            for (String key : new String[]{"loginEnabled", "schedulerEnabled", "schedulerCron"}) {
                if (json.has(key)) values.put(key, json.getString(key));
            }

            // Load old cron to detect changes
            String oldCron = configService.loadTriggers().getOrDefault("schedulerCron", "");
            configService.saveTriggers(values);

            // If cron changed and scheduler is enabled, reschedule the Quartz job
            String newCron = values.getOrDefault("schedulerCron", oldCron);
            boolean schedulerEnabled = !"false".equalsIgnoreCase(values.getOrDefault("schedulerEnabled", "true"));

            if (schedulerEnabled && !newCron.equals(oldCron)) {
                SfdcSyncJob job = BundleUtils.getOsgiService(SfdcSyncJob.class, null);
                if (job != null) {
                    job.rescheduleCron(newCron);
                    logger.info("[SFDC-Triggers] Quartz rescheduled with cron: {}", newCron);
                }
            } else if (!schedulerEnabled) {
                SfdcSyncJob job = BundleUtils.getOsgiService(SfdcSyncJob.class, null);
                if (job != null) {
                    job.unschedule();
                    logger.info("[SFDC-Triggers] Quartz job unscheduled");
                }
            }

            GetSfdcFieldsAction.write(ctx.getResponse(),
                new JSONObject().put("status", "saved").toString());
        } catch (Exception e) {
            logger.error("[SFDC-Triggers] Failed to save triggers", e);
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
