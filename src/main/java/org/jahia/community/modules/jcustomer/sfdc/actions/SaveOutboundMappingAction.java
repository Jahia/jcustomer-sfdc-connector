package org.jahia.community.modules.jcustomer.sfdc.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.community.modules.jcustomer.sfdc.config.ConnectorConfigService;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves the outbound (jCustomer → SFDC) field mapping for a given SF object.
 * POST *.saveOutboundMapping.do
 * Body (JSON): { "sfObject": "Lead", "mappings": [ { jcProperty, sfField, required?, defaultValue? } ] }
 * {@code defaultValue} is sent when the jCustomer property is empty; a row without {@code jcProperty} sends it always.
 */
@Component(service = Action.class, immediate = true)
public class SaveOutboundMappingAction extends AbstractSfdcAdminAction {

    private ConnectorConfigService configService;
    @Reference public void setConfigService(ConnectorConfigService s) { this.configService = s; }
    @Override public String getName() { return "saveOutboundMapping"; }

    @Override
    protected ActionResult doAuthorizedExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {
        try {
            JSONObject json = new JSONObject(readBody(req));
            String sfObject = json.getString("sfObject");
            JSONArray arr   = json.getJSONArray("mappings");

            List<Map<String, String>> mappings = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.getJSONObject(i);
                Map<String, String> entry = new HashMap<>();
                entry.put("jcProperty", m.optString("jcProperty"));
                entry.put("sfField",    m.optString("sfField"));
                if (m.has("required")) entry.put("required", m.optString("required"));
                String defaultValue = m.optString("defaultValue", "").trim();   // used when the property is empty
                if (!defaultValue.isEmpty()) entry.put("defaultValue", defaultValue);
                mappings.add(entry);
            }

            configService.saveOutboundMapping(sfObject, mappings);
            GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject().put("status", "ok").toString());
        } catch (Exception e) {
            GetSfdcFieldsAction.write(ctx.getResponse(), new JSONObject().put("error", e.getMessage()).toString(), 500);
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
