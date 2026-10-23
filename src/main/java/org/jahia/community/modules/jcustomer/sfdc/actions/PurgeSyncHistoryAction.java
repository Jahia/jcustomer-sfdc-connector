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
 * Deletes every recorded run of one sync direction.
 * POST *.purgeSyncHistory.do?direction=inbound|outbound → { status: "purged", removed: n }
 */
@Component(service = Action.class, immediate = true)
public class PurgeSyncHistoryAction extends AbstractSfdcAdminAction {

    private SyncHistoryService history;
    @Reference public void setHistory(SyncHistoryService h) { this.history = h; }
    @Override public String getName() { return "purgeSyncHistory"; }

    @Override
    protected ActionResult doAuthorizedExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {
        SyncHistoryService.Direction direction =
            SyncHistoryService.Direction.of(GetSyncHistoryAction.first(params, "direction"));
        if (direction == null) {
            GetSfdcFieldsAction.write(ctx.getResponse(),
                new JSONObject().put("error", "direction must be inbound or outbound").toString(), 400);
            return ActionResult.OK;
        }
        int removed = history.purge(direction);
        GetSfdcFieldsAction.write(ctx.getResponse(),
            new JSONObject().put("status", "purged").put("removed", removed).toString());
        return ActionResult.OK;
    }
}
