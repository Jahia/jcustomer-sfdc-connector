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
 * Lists the newest recorded runs of one sync direction.
 * POST *.getSyncHistory.do?direction=inbound|outbound[&limit=n][&includeRecords=true]
 * → { runs: [...], retention: { enabled, days, defaultDays } }
 * Without {@code includeRecords} each run carries only its {@code recordCount} (the UI loads records
 * when a run is expanded); with it each run also carries its {@code records} (used for export).
 */
@Component(service = Action.class, immediate = true)
public class GetSyncHistoryAction extends AbstractSfdcAdminAction {

    private SyncHistoryService history;
    @Reference public void setHistory(SyncHistoryService h) { this.history = h; }
    @Override public String getName() { return "getSyncHistory"; }

    @Override
    protected ActionResult doAuthorizedExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {
        SyncHistoryService.Direction direction = SyncHistoryService.Direction.of(first(params, "direction"));
        if (direction == null) {
            GetSfdcFieldsAction.write(ctx.getResponse(),
                new JSONObject().put("error", "direction must be inbound or outbound").toString(), 400);
            return ActionResult.OK;
        }
        int limit = Integer.MAX_VALUE;   // bounded by the retention of the direction
        try { limit = Integer.parseInt(first(params, "limit")); } catch (Exception ignored) {}
        boolean includeRecords = "true".equalsIgnoreCase(first(params, "includeRecords"));
        List<Map<String, Object>> list = includeRecords ? history.exportRuns(direction) : history.listRuns(direction, limit);
        JSONArray runs = new JSONArray();
        list.forEach(m -> runs.put(new JSONObject(m)));
        GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject().put("runs", runs)
            .put("retention", new JSONObject(history.getRetention(direction))).toString());
        return ActionResult.OK;
    }

    static String first(Map<String, List<String>> params, String key) {
        List<String> v = params.get(key);
        return v == null || v.isEmpty() ? null : v.get(0);
    }
}
