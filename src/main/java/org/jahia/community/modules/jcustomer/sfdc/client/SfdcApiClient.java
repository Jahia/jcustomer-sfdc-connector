package org.jahia.community.modules.jcustomer.sfdc.client;

import org.jahia.community.modules.jcustomer.sfdc.auth.SfdcAuthService;
import org.jahia.community.modules.jcustomer.sfdc.config.ConnectorConfigService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Salesforce REST API client.
 *
 * Responsibilities:
 *   - Execute SOQL queries with automatic pagination (nextRecordsUrl)
 *   - Fetch SF object field descriptions (Describe API) for the mapping UI
 *   - Retry once on 401 with a refreshed token
 */
@Component(service = SfdcApiClient.class, immediate = true)
public class SfdcApiClient {

    private static final Logger logger = LoggerFactory.getLogger(SfdcApiClient.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    /**
     * Salesforce sObject API-name pattern — a letter/underscore followed by letters, digits or
     * underscores. Applied at every sink that concatenates {@code type} into a SOQL {@code FROM}
     * clause or a REST URL path, so an admin-supplied string that reaches here as user input
     * (Describe UI, outbound object) cannot break out of the intended shape.
     */
    private static final Pattern SOBJECT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static String requireSObjectName(String type) {
        if (type == null || !SOBJECT_NAME.matcher(type).matches()) {
            throw new IllegalArgumentException("Invalid Salesforce object name: " + type);
        }
        return type;
    }

    /** A Salesforce record id: 15 or 18 letters and digits. Ids read from profiles are checked before a URL or a query. */
    private static final Pattern SOBJECT_ID = Pattern.compile("[a-zA-Z0-9]{15}([a-zA-Z0-9]{3})?");

    static String requireSObjectId(String id) {
        if (id == null || !SOBJECT_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid Salesforce record id: " + id);
        }
        return id;
    }

    private SfdcAuthService    authService;
    private ConnectorConfigService configService;

    @Reference public void setAuthService(SfdcAuthService s)       { this.authService    = s; }
    @Reference public void setConfigService(ConnectorConfigService s) { this.configService = s; }

    // ── SOQL query ────────────────────────────────────────────────────────────

    /**
     * Executes a SOQL query and returns ALL records, following nextRecordsUrl pages.
     * Each record is a Map<String, Object> of field name → value.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> query(String soql) throws Exception {
        Map<String, String> cfg = configService.loadConnection();
        String instanceUrl = cfg.get("instanceUrl");
        if (instanceUrl != null) instanceUrl = instanceUrl.replaceAll("/+$", ""); // strip trailing slashes
        String apiVersion  = apiVersion(cfg);
        String baseUrl     = instanceUrl + "/services/data/" + apiVersion;

        List<Map<String, Object>> allRecords = new ArrayList<>();
        String url = baseUrl + "/query?q=" + URLEncoder.encode(soql, StandardCharsets.UTF_8);

        while (url != null) {
            Map<String, Object> page = getJson(url, baseUrl);
            Object records = page.get("records");
            if (records instanceof List) allRecords.addAll((List<Map<String, Object>>) records);

            Object next = page.get("nextRecordsUrl");
            url = (next instanceof String) ? instanceUrl + next : null;
        }

        logger.debug("[SFDC-Client] SOQL returned {} total records", allRecords.size());
        return allRecords;
    }

    // ── Describe API ──────────────────────────────────────────────────────────

    /**
     * Returns field metadata for a Salesforce object (e.g., "Contact", "Account").
     * Each entry: { name, label, type, relationshipName (if lookup) }, and for a {@code picklist} or
     * {@code multipicklist}: {@code picklistValues} (its active values, in Salesforce's order) and
     * {@code restrictedPicklist} (Salesforce refuses any other value).
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> describeObject(String sobjectName) throws Exception {
        requireSObjectName(sobjectName);
        Map<String, String> cfg = configService.loadConnection();
        String instanceUrl = cfg.get("instanceUrl");
        if (instanceUrl != null) instanceUrl = instanceUrl.replaceAll("/+$", ""); // strip trailing slashes
        String apiVersion  = apiVersion(cfg);

        String url = instanceUrl + "/services/data/" + apiVersion + "/sobjects/" + sobjectName + "/describe";
        Map<String, Object> response = getJson(url, null);

        List<Map<String, Object>> fields = new ArrayList<>();
        Object fieldsList = response.get("fields");
        if (fieldsList instanceof List) {
            for (Object f : (List<?>) fieldsList) {
                if (!(f instanceof Map)) continue;
                Map<String, Object> field = (Map<String, Object>) f;
                Map<String, Object> entry = new HashMap<>();
                entry.put("name",  str(field, "name"));
                entry.put("label", str(field, "label"));
                entry.put("type",  str(field, "type"));
                // For relationship fields, expose the relationship name
                if (field.containsKey("relationshipName") && field.get("relationshipName") != null) {
                    entry.put("relationshipName", str(field, "relationshipName"));
                }
                String type = str(field, "type");
                if ("picklist".equals(type) || "multipicklist".equals(type)) {
                    List<String> values = new ArrayList<>();
                    if (field.get("picklistValues") instanceof List) {
                        for (Object v : (List<?>) field.get("picklistValues")) {
                            if (v instanceof Map && !Boolean.FALSE.equals(((Map<String, Object>) v).get("active"))) {
                                Object value = ((Map<String, Object>) v).get("value");
                                if (value != null) values.add(value.toString());
                            }
                        }
                    }
                    entry.put("picklistValues", values);
                    entry.put("restrictedPicklist", Boolean.TRUE.equals(field.get("restrictedPicklist")));
                }
                fields.add(entry);
            }
        }
        return fields;
    }

    /**
     * Validates the current connection by fetching the identity endpoint.
     * Returns org display name on success, throws on failure.
     */
    public String testConnection() throws Exception {
        Map<String, String> cfg = configService.loadConnection();
        String instanceUrl = cfg.get("instanceUrl");
        if (instanceUrl != null) instanceUrl = instanceUrl.replaceAll("/+$", ""); // strip trailing slashes
        String apiVersion  = apiVersion(cfg);

        String url = instanceUrl + "/services/data/" + apiVersion + "/limits";
        Map<String, Object> resp = getJson(url, null);
        // If we got here without exception, connection works
        logger.info("[SFDC-Client] Connection test OK — instance: {}", instanceUrl);
        return instanceUrl;
    }

    // ── Writes (jCustomer → SFDC) ───────────────────────────────────────────────

    /**
     * Returns the Id of the most recently modified record of {@code type} (e.g. "Lead" or
     * "Contact") with the given email, or {@code null} if none. For Lead, converted leads are
     * excluded. The email is escaped for the SOQL string literal.
     */
    public String findSObjectIdByEmail(String type, String email) throws Exception {
        if (email == null || email.isBlank()) return null;
        requireSObjectName(type);
        String escaped = email.replace("\\", "\\\\").replace("'", "\\'");
        String where = "Email = '" + escaped + "'";
        if ("Lead".equals(type)) where += " AND IsConverted = false";
        String soql = "SELECT Id FROM " + type + " WHERE " + where + " ORDER BY LastModifiedDate DESC LIMIT 1";
        List<Map<String, Object>> rows = query(soql);
        if (rows.isEmpty()) return null;
        Object id = rows.get(0).get("Id");
        return id == null ? null : id.toString();
    }

    /**
     * The Contact a Lead was converted to.
     *
     * @param leadId a Salesforce Lead id
     * @return the {@code ConvertedContactId}, or {@code null} when the Lead is not converted or does not exist
     */
    public String findConvertedContactId(String leadId) throws Exception {
        List<Map<String, Object>> rows = query("SELECT ConvertedContactId FROM Lead WHERE Id = '"
                + requireSObjectId(leadId) + "' AND IsConverted = true LIMIT 1");
        return rows.isEmpty() ? null : stringOrNull(rows.get(0).get("ConvertedContactId"));
    }

    /**
     * The Contact of the most recently converted Lead with this email, so that a person who is already a
     * Contact is not created again as a Lead.
     *
     * @return the {@code ConvertedContactId}, or {@code null} when no converted Lead has this email
     */
    public String findConvertedContactIdByEmail(String email) throws Exception {
        if (email == null || email.isBlank()) return null;
        String escaped = email.replace("\\", "\\\\").replace("'", "\\'");
        List<Map<String, Object>> rows = query("SELECT ConvertedContactId FROM Lead WHERE Email = '" + escaped
                + "' AND IsConverted = true AND ConvertedContactId != null ORDER BY ConvertedDate DESC LIMIT 1");
        return rows.isEmpty() ? null : stringOrNull(rows.get(0).get("ConvertedContactId"));
    }

    private static String stringOrNull(Object o) {
        return o == null || o.toString().isBlank() ? null : o.toString();
    }

    /**
     * Creates an sObject of the given type and returns the new record Id.
     * POST /services/data/{v}/sobjects/{type}
     */
    public String createSObject(String type, Map<String, Object> fields) throws Exception {
        requireSObjectName(type);
        String url = dataBaseUrl() + "/sobjects/" + type;
        HttpResponse<String> resp = writeJson("POST", url, new org.json.JSONObject(fields).toString());
        if (resp.statusCode() != 201 && resp.statusCode() != 200) {
            throw new RuntimeException("[SFDC-Client] Create " + type + " failed HTTP "
                    + resp.statusCode() + ": " + resp.body());
        }
        return new org.json.JSONObject(resp.body()).optString("id", null);
    }

    /**
     * Updates fields on an existing sObject.
     * PATCH /services/data/{v}/sobjects/{type}/{id} (Salesforce returns 204 No Content).
     */
    public void updateSObject(String type, String id, Map<String, Object> fields) throws Exception {
        requireSObjectName(type);
        requireSObjectId(id);
        String url = dataBaseUrl() + "/sobjects/" + type + "/" + id;
        HttpResponse<String> resp = writeJson("PATCH", url, new org.json.JSONObject(fields).toString());
        if (resp.statusCode() != 204 && resp.statusCode() != 200) {
            throw new RuntimeException("[SFDC-Client] Update " + type + "/" + id + " failed HTTP "
                    + resp.statusCode() + ": " + resp.body());
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> getJson(String url, String baseUrl) throws Exception {
        String token = authService.getAccessToken();
        HttpResponse<String> resp = doGet(url, token);

        // Retry once on 401 with a fresh token
        if (resp.statusCode() == 401) {
            authService.invalidateToken();
            token = authService.getAccessToken();
            resp = doGet(url, token);
        }

        if (resp.statusCode() != 200) {
            throw new RuntimeException("[SFDC-Client] HTTP " + resp.statusCode() + " for " + url + ": " + resp.body());
        }

        return parseJsonObject(resp.body());
    }

    private HttpResponse<String> doGet(String url, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** POST/PATCH JSON with the bearer token, retrying once on 401 with a refreshed token. */
    private HttpResponse<String> writeJson(String method, String url, String jsonBody) throws Exception {
        String token = authService.getAccessToken();
        HttpResponse<String> resp = doSend(method, url, token, jsonBody);
        if (resp.statusCode() == 401) {
            authService.invalidateToken();
            token = authService.getAccessToken();
            resp = doSend(method, url, token, jsonBody);
        }
        return resp;
    }

    private HttpResponse<String> doSend(String method, String url, String token, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(30))
            .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** {instanceUrl without trailing slash}/services/data/{apiVersion} */
    private String dataBaseUrl() {
        Map<String, String> cfg = configService.loadConnection();
        String instanceUrl = cfg.get("instanceUrl");
        if (instanceUrl != null) instanceUrl = instanceUrl.replaceAll("/+$", "");
        String apiVersion = apiVersion(cfg);
        return instanceUrl + "/services/data/" + apiVersion;
    }

    // ── Minimal JSON parser ───────────────────────────────────────────────────
    // Uses org.json which is available in Jahia's platform bundle

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String json) {
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            return toMap(obj);
        } catch (Exception e) {
            throw new RuntimeException("[SFDC-Client] Failed to parse JSON: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(org.json.JSONObject obj) {
        Map<String, Object> map = new HashMap<>();
        for (String key : obj.keySet()) {
            Object val = obj.get(key);
            if (val instanceof org.json.JSONObject) map.put(key, toMap((org.json.JSONObject) val));
            else if (val instanceof org.json.JSONArray) map.put(key, toList((org.json.JSONArray) val));
            else if (val == org.json.JSONObject.NULL) map.put(key, null);
            else map.put(key, val);
        }
        return map;
    }

    private static List<Object> toList(org.json.JSONArray arr) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            Object val = arr.get(i);
            if (val instanceof org.json.JSONObject) list.add(toMap((org.json.JSONObject) val));
            else if (val instanceof org.json.JSONArray) list.add(toList((org.json.JSONArray) val));
            else if (val == org.json.JSONObject.NULL) list.add(null);
            else list.add(val);
        }
        return list;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }

    /**
     * Salesforce REST API version: the Connection's value, else {@code sfdc.api.version} from the OSGi
     * configuration ({@link ConnectorConfigService#getDefaultApiVersion()}). No version is invented
     * in code.
     */
    private String apiVersion(Map<String, String> cfg) {
        String v = cfg.get("apiVersion");
        if (v == null || v.isBlank()) {
            v = configService.getDefaultApiVersion();
        }
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("No Salesforce API version: set it on the Connection tab or "
                    + "sfdc.api.version in org.jahia.community.jcustomer.sfdc");
        }
        return v.trim();
    }
}
