package org.jahia.community.modules.jcustomer.sfdc.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.community.modules.jcustomer.sfdc.config.ConnectorConfigService;
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

/** Returns saved trigger configuration (login enable, scheduler enable+cron). POST *.getTriggers.do */
@Component(service = Action.class, immediate = true)
public class GetTriggersAction extends Action {

    private ConnectorConfigService configService;
    @Reference public void setConfigService(ConnectorConfigService s) { this.configService = s; }
    @Override public String getName() { return "getTriggers"; }

    @Override
    public ActionResult doExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {
        Map<String, String> triggers = configService.loadTriggers();
        JSONObject response = new JSONObject();
        triggers.forEach(response::put);
        GetSfdcFieldsAction.write(ctx.getResponse(), response.toString());
        return ActionResult.OK;
    }
}
