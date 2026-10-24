package org.jahia.community.modules.jcustomer.sfdc.actions;

import org.jahia.community.modules.jcustomer.sfdc.history.SyncHistoryService;
import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.community.modules.jcustomer.sfdc.config.SiteKeyResolver;
import org.jahia.community.modules.jcustomer.sfdc.sync.SfdcOutboundSyncService;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * Runs the outbound reconcile immediately (pushes profiles changed since the last run to Salesforce).
 * POST *.triggerOutboundReconcile.do?siteKey=&lt;site the admin UI is open on&gt; → { status: "started", siteKey }
 * (async). The site is resolved by {@link SiteKeyResolver}; 409 when none can be determined.
 */
@Component(service = Action.class, immediate = true, configurationPid = "org.jahia.community.jcustomer.sfdc")
public class TriggerOutboundReconcileAction extends AbstractSfdcAdminAction {

    @interface Config {
        int sync_delta_buffer_minutes() default 5;
    }

    private volatile int buffer = 5;

    private SfdcOutboundSyncService outboundService;
    private SiteKeyResolver         siteKeyResolver;
    @Reference public void setOutboundService(SfdcOutboundSyncService s) { this.outboundService = s; }
    @Reference public void setSiteKeyResolver(SiteKeyResolver r) { this.siteKeyResolver = r; }

    @Activate @Modified
    public void activate(Config config) {
        this.buffer = config.sync_delta_buffer_minutes();
    }

    @Override public String getName() { return "triggerOutboundReconcile"; }

    @Override
    protected ActionResult doAuthorizedExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {
        List<String> siteKeyParam = params.getOrDefault("siteKey", List.of());
        final String site = siteKeyResolver.resolve(siteKeyParam.isEmpty() ? null : siteKeyParam.get(0));
        if (site == null) {
            GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject()
                .put("error", "No site resolved - set jcustomer.site.key in org.jahia.community.jcustomer.sfdc").toString(), 409);
            return ActionResult.OK;
        }
        final int buf = buffer;
        Thread t = new Thread(() -> outboundService.runReconcile(site, buf, SyncHistoryService.Trigger.MANUAL));
        t.setName("sfdc-outbound-reconcile");
        t.setDaemon(true);
        t.start();
        GetSfdcFieldsAction.write(ctx.getResponse(),
            new JSONObject().put("status", "started").put("siteKey", site).toString());
        return ActionResult.OK;
    }
}
