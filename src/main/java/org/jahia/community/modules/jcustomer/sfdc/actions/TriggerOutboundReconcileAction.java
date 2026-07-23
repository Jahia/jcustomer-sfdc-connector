package org.jahia.community.modules.jcustomer.sfdc.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
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
 * POST *.triggerOutboundReconcile.do → { status: "started" } (async)
 */
@Component(service = Action.class, immediate = true, configurationPid = "org.jahia.community.jcustomer.sfdc")
public class TriggerOutboundReconcileAction extends AbstractSfdcAdminAction {

    @interface Config {
        String jcustomer_site_key()        default "mysoprahr";
        int    sync_delta_buffer_minutes() default 5;
    }

    private volatile String siteKey = "mysoprahr";
    private volatile int    buffer  = 5;

    private SfdcOutboundSyncService outboundService;
    @Reference public void setOutboundService(SfdcOutboundSyncService s) { this.outboundService = s; }

    @Activate @Modified
    public void activate(Config config) {
        this.siteKey = config.jcustomer_site_key();
        this.buffer  = config.sync_delta_buffer_minutes();
    }

    @Override public String getName() { return "triggerOutboundReconcile"; }

    @Override
    protected ActionResult doAuthorizedExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {
        final String site = siteKey;
        final int buf = buffer;
        Thread t = new Thread(() -> outboundService.runReconcile(site, buf, 500));
        t.setName("sfdc-outbound-reconcile");
        t.setDaemon(true);
        t.start();
        GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject().put("status", "started").toString());
        return ActionResult.OK;
    }
}
