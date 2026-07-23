package org.jahia.community.modules.jcustomer.sfdc.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.community.modules.jcustomer.sfdc.config.ConnectorConfigService;
import org.jahia.community.modules.jcustomer.sfdc.sync.SfdcSyncService;
import org.jahia.osgi.BundleUtils;
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
 * Triggers an immediate batch sync from the admin UI.
 * POST *.triggerSync.do → { status: "started" } (async)
 */
@Component(service = Action.class, immediate = true)
public class TriggerSyncAction extends AbstractSfdcAdminAction {

    private ConnectorConfigService configService;
    @Reference public void setConfigService(ConnectorConfigService s) { this.configService = s; }
    @Override public String getName() { return "triggerSync"; }

    @Override
    protected ActionResult doAuthorizedExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {

        List<String> siteKeyParam = params.getOrDefault("siteKey", List.of());
        String siteKey = siteKeyParam.isEmpty()
            ? configService.loadConnection().getOrDefault("siteKey", "mysoprahr")
            : siteKeyParam.get(0);

        // Run async so the HTTP response returns immediately
        Thread t = new Thread(() -> {
            SfdcSyncService syncService = BundleUtils.getOsgiService(SfdcSyncService.class, null);
            if (syncService != null) {
                syncService.runBatchSync(siteKey, 0); // 0 = full sync (ignore lastSyncAt)
            }
        });
        t.setName("sfdc-manual-sync");
        t.setDaemon(true);
        t.start();

        GetSfdcFieldsAction.write(ctx.getResponse(),
            new JSONObject().put("status", "started").put("siteKey", siteKey).toString());
        return ActionResult.OK;
    }
}
