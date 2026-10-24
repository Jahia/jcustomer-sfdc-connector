package org.jahia.community.modules.jcustomer.sfdc.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.community.modules.jcustomer.sfdc.history.SyncHistoryService;
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
 * Saves the automatic purge of one sync direction and applies it at once.
 * POST *.saveHistoryRetention.do?direction=inbound|outbound&enabled=true|false&days=n
 * → { status: "saved", removed: n, retention: { enabled, days, defaultDays } }
 */
@Component(service = Action.class, immediate = true)
public class SaveHistoryRetentionAction extends AbstractSfdcAdminAction {

    private SyncHistoryService history;
    @Reference public void setHistory(SyncHistoryService h) { this.history = h; }
    @Override public String getName() { return "saveHistoryRetention"; }

    @Override
    protected ActionResult doAuthorizedExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {
        SyncHistoryService.Direction direction =
            SyncHistoryService.Direction.of(GetSyncHistoryAction.first(params, "direction"));
        int days;
        try {
            days = Integer.parseInt(GetSyncHistoryAction.first(params, "days"));
        } catch (Exception e) {
            days = -1;
        }
        if (direction == null || days < SyncHistoryService.MIN_RETENTION_DAYS || days > SyncHistoryService.MAX_RETENTION_DAYS) {
            GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject().put("error",
                "direction must be inbound or outbound, days between " + SyncHistoryService.MIN_RETENTION_DAYS
                    + " and " + SyncHistoryService.MAX_RETENTION_DAYS).toString(), 400);
            return ActionResult.OK;
        }
        boolean enabled = !"false".equalsIgnoreCase(GetSyncHistoryAction.first(params, "enabled"));
        try {
            int removed = history.saveRetention(direction, enabled, days);
            GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject()
                .put("status", "saved")
                .put("removed", removed)
                .put("retention", new JSONObject(history.getRetention(direction))).toString());
        } catch (Exception e) {
            GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject().put("error", e.getMessage()).toString(), 500);
        }
        return ActionResult.OK;
    }
}
