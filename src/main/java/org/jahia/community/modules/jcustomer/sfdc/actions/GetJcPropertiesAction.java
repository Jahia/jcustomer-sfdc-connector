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
 * containing underscores (e.g. a Docker service such as jcustomer_1) with "unsupported URI", whereas
 * HttpURLConnection handles them correctly.
 *
 * POST *.getJcProperties.do
 */
@Component(service = Action.class, immediate = true,
    configurationPid = "org.jahia.community.jcustomer.sfdc")
public class GetJcPropertiesAction extends AbstractSfdcAdminAction {

    private static final Logger logger = LoggerFactory.getLogger(GetJcPropertiesAction.class);

    // Credentials are intentionally NOT defaulted here. Shipping working jCustomer credentials in
    // source (the former "karaf"/"karaf" default) is a hardcoded-secret risk; they must be supplied
    // per-environment via the org.jahia.community.jcustomer.sfdc OSGi config. When unset, the live
    // property lookup is disabled and only the built-in fallback list is returned (fail-closed).
    @interface Config {
        String jcustomer_url()      default "";
        String jcustomer_username() default "";
        String jcustomer_password() default "";
    }

    private String jcUrl;
    private String jcAuth;   // null when URL/credentials are not configured → live lookup disabled

    @Activate
    public void activate(Config config) {
        this.jcUrl = config.jcustomer_url() == null ? "" : config.jcustomer_url().replaceAll("/+$", "");
        String user = config.jcustomer_username();
        String pass = config.jcustomer_password();
        if (jcUrl.isBlank() || user == null || user.isBlank() || pass == null || pass.isBlank()) {
            this.jcAuth = null;
            logger.warn("[SFDC-Action] jCustomer URL/credentials not configured "
                + "(org.jahia.community.jcustomer.sfdc) — live property listing disabled, using fallback list only");
        } else {
            this.jcAuth = Base64.getEncoder().encodeToString(
                (user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override public String getName() { return "getJcProperties"; }

    @Override
    protected ActionResult doAuthorizedExecute(HttpServletRequest req, RenderContext ctx, Resource res,
                                   JCRSessionWrapper session, Map<String, List<String>> params,
                                   URLResolver url) throws Exception {
        JSONArray result = new JSONArray();

        // Always seed with well-known fallback properties first
        addFallback(result);

        // Fail closed: no live lookup unless URL + credentials were configured (see @Activate).
        if (jcAuth == null || jcUrl.isBlank()) {
            GetSfdcFieldsAction.write(ctx.getResponse(), result.toString());
            return ActionResult.OK;
        }

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

    // Generic, instance-independent fallback: Unomi well-known profile properties plus the
    // connector's own Salesforce writeback ids. Returned only when the live jCustomer lookup is
    // unavailable, and merged (deduplicated by id) under the live list when it succeeds, so a real
    // Unomi's own properties always drive the mapping UI. Never put a specific customer's property
    // names here — they would surface on every other instance.
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
        {"SFDC_LeadId",    "string",  "false"},
    };
}
