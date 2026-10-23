package org.jahia.community.modules.jcustomer.sfdc.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.community.modules.jcustomer.sfdc.config.SiteKeyResolver;
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
 * POST *.triggerSync.do?siteKey=&lt;site the admin UI is open on&gt; → { status: "started", siteKey } (async)
 * The site is resolved by {@link SiteKeyResolver}; 409 when none can be determined.
 */
@Component(service = Action.class, immediate = true)
public class TriggerSyncAction extends AbstractSfdcAdminAction {

    private SiteKeyResolver siteKeyResolver;
    @Reference public void setSiteKeyResolver(SiteKeyResolver r) { this.siteKeyResolver = r; }
    @Override public String getName() { return "triggerSync"; }

    @Override
    protected ActionResult doAuthorizedExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {

        List<String> siteKeyParam = params.getOrDefault("siteKey", List.of());
        String siteKey = siteKeyResolver.resolve(siteKeyParam.isEmpty() ? null : siteKeyParam.get(0));
        if (siteKey == null) {
            GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject()
                .put("error", "No site resolved - set jcustomer.site.key in org.jahia.community.jcustomer.sfdc").toString(), 409);
            return ActionResult.OK;
        }

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
