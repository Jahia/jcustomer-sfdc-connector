package org.jahia.community.modules.jcustomer.sfdc.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.community.modules.jcustomer.sfdc.history.SyncHistoryService;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * Returns the records of one recorded run (what happened, on whom, and what).
 * POST *.getSyncHistoryRun.do?direction=inbound|outbound&runId=run-... → { records: [...] }
 */
@Component(service = Action.class, immediate = true)
public class GetSyncHistoryRunAction extends AbstractSfdcAdminAction {

    private SyncHistoryService history;
    @Reference public void setHistory(SyncHistoryService h) { this.history = h; }
    @Override public String getName() { return "getSyncHistoryRun"; }

    @Override
    protected ActionResult doAuthorizedExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {
        SyncHistoryService.Direction direction =
            SyncHistoryService.Direction.of(GetSyncHistoryAction.first(params, "direction"));
        String runId = GetSyncHistoryAction.first(params, "runId");
        if (direction == null || runId == null) {
            GetSfdcFieldsAction.write(ctx.getResponse(),
                new JSONObject().put("error", "direction and runId are required").toString(), 400);
            return ActionResult.OK;
        }
        JSONArray records = new JSONArray();
        history.getRecords(direction, runId).forEach(m -> records.put(new JSONObject(m)));
        GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject().put("records", records).toString());
        return ActionResult.OK;
    }
}
