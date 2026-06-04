package org.jahia.community.modules.jcustomer.sfdc.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Returns jCustomer profile property definitions for the mapping UI.
 *
 * Uses HttpURLConnection (not HttpClient) — Java's newer HttpClient rejects hostnames
 * containing underscores (e.g. jcustomer_3) with "unsupported URI", whereas
 * HttpURLConnection handles them correctly.
 *
 * POST *.getJcProperties.do
 */
@Component(service = Action.class, immediate = true,
    configurationPid = "org.jahia.community.jcustomer.sfdc")
public class GetJcPropertiesAction extends Action {

    private static final Logger logger = LoggerFactory.getLogger(GetJcPropertiesAction.class);

    @interface Config {
        String jcustomer_url()      default "http://jcustomer_3:8181";
        String jcustomer_username() default "karaf";
        String jcustomer_password() default "karaf";
    }

    private String jcUrl;
    private String jcAuth;

    @Activate
    public void activate(Config config) {
        this.jcUrl  = config.jcustomer_url().replaceAll("/+$", "");
        this.jcAuth = Base64.getEncoder().encodeToString(
            (config.jcustomer_username() + ":" + config.jcustomer_password()).getBytes(StandardCharsets.UTF_8));
    }

    @Override public String getName() { return "getJcProperties"; }

    @Override
    public ActionResult doExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {
        JSONArray result = new JSONArray();

        // Always seed with well-known fallback properties first
        addFallback(result);

        try {
            // Use HttpURLConnection — tolerates underscores in hostnames unlike java.net.http.HttpClient
            URL endpoint = new URL(jcUrl + "/cxs/profiles/properties/targets/profiles");
            HttpURLConnection conn = (HttpURLConnection) endpoint.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Basic " + jcAuth);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Cache-Control", "no-cache, no-store");
            conn.setRequestProperty("Pragma", "no-cache");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setUseCaches(false);

            int status = conn.getResponseCode();
            if (status == 200) {
                String body;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    body = reader.lines().collect(Collectors.joining());
                }

                JSONArray live    = new JSONArray(body);
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int i = 0; i < result.length(); i++) seen.add(result.getJSONObject(i).getString("id"));

                int added = 0;
                for (int i = 0; i < live.length(); i++) {
                    JSONObject prop = live.getJSONObject(i);
                    JSONObject meta = prop.optJSONObject("metadata");
                    if (meta == null) continue;
                    String id = meta.optString("id", "");
                    if (id.isBlank() || seen.contains(id)) continue;
                    result.put(new JSONObject()
                        .put("id",          id)
                        .put("name",        id)
                        .put("type",        prop.optString("type", "string"))
                        .put("multivalued", prop.optBoolean("multivalued", false)));
                    seen.add(id);
                    added++;
                }
                logger.info("[SFDC-Action] Loaded {} live + {} fallback jCustomer properties",
                    added, result.length() - added);
            } else {
                logger.warn("[SFDC-Action] jCustomer GET /properties returned HTTP {} — using fallback only", status);
            }
            conn.disconnect();

        } catch (Exception e) {
            logger.warn("[SFDC-Action] Could not load jCustomer properties live: {} — using fallback only", e.getMessage());
        }

        GetSfdcFieldsAction.write(ctx.getResponse(), result.toString());
        return ActionResult.OK;
    }

    private void addFallback(JSONArray result) throws Exception {
        for (String[] p : FALLBACK_PROPERTIES) {
            result.put(new JSONObject()
                .put("id", p[0]).put("name", p[0])
                .put("type", p[1]).put("multivalued", Boolean.parseBoolean(p[2])));
        }
    }

    private static final String[][] FALLBACK_PROPERTIES = {
        {"email",          "email",   "false"},
        {"firstName",      "string",  "false"},
        {"lastName",       "string",  "false"},
        {"company",        "string",  "false"},
        {"jobTitle",       "string",  "false"},
        {"phoneNumber",    "string",  "false"},
        {"city",           "string",  "false"},
        {"countryName",    "string",  "false"},
        {"zipCode",        "string",  "false"},
        {"gender",         "string",  "false"},
        {"birthDate",      "date",    "false"},
        {"nationality",    "string",  "false"},
        {"nbOfVisits",     "integer", "false"},
        {"firstVisit",     "date",    "false"},
        {"lastVisit",      "date",    "false"},
        {"SFDC_ContactId", "string",  "false"},
        {"SFDC_AccountId", "string",  "false"},
        {"MySopraHR_Solution",               "string",  "false"},
        {"MySopraHR_Version",                "string",  "false"},
        {"MySopraHR_Mode_Exploitation",      "string",  "false"},
        {"MySopraHR_Modele",                 "string",  "false"},
        {"MySopraHR_Pays",                   "string",  "false"},
        {"MySopraHR_Conventions_Collectives","string",  "false"},
        {"MySopraHR_Persona",                "string",  "false"},
        {"MySopraHR_Role",                   "string",  "false"},
        {"MySopraHR_Type_Compte",            "string",  "false"},
        {"MySopraHR_Department",             "string",  "false"},
        {"MySopraHR_Title",                  "string",  "false"},
        {"MySopraHR_Produits_Services",      "string",  "false"},
        {"MySopraHR_NotifEnabled",           "boolean", "false"},
        {"MySopraHR_NotifFrequency",         "string",  "false"},
        {"MySopraHR_NotifCategories",        "string",  "true"},
    };
}
