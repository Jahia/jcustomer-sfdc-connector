package org.jahia.community.modules.jcustomer.sfdc.auth;

import org.jahia.community.modules.jcustomer.sfdc.config.ConnectorConfigService;
import org.json.JSONObject;
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
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Handles Salesforce JWT Bearer OAuth 2.0 authentication.
 *
 * Flow:
 *   1. Build JWT: header + payload (iss=clientId, sub=sfUsername, aud=SF token endpoint, exp=now+3min)
 *   2. Sign with RSA-SHA256 using the stored private key (PEM)
 *   3. POST to {instanceUrl}/services/oauth2/token with grant_type=jwt-bearer
 *   4. Cache access_token; refresh automatically when within 60s of expiry
 *
 * No dependencies beyond java.security — no third-party JWT libraries needed.
 */
@Component(service = SfdcAuthService.class, immediate = true)
public class SfdcAuthService {

    private static final Logger logger = LoggerFactory.getLogger(SfdcAuthService.class);
    private static final String TOKEN_PATH = "/services/oauth2/token";
    private static final int JWT_TTL_SECONDS = 180;   // SF allows max 3 minutes
    private static final int REFRESH_BEFORE_SECONDS = 60;

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    private ConnectorConfigService configService;

    @Reference
    public void setConfigService(ConnectorConfigService configService) {
        this.configService = configService;
    }

    // ── Token cache ───────────────────────────────────────────────────────────

    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    /**
     * Returns a valid Salesforce access token.
     * Refreshes automatically when the token is within 60 seconds of expiry.
     */
    public synchronized String getAccessToken() throws Exception {
        if (cachedToken == null || Instant.now().isAfter(tokenExpiry.minusSeconds(REFRESH_BEFORE_SECONDS))) {
            cachedToken = requestNewToken();
            tokenExpiry = Instant.now().plusSeconds(3600); // SF tokens typically last 1h
            logger.info("[SFDC-Auth] Access token refreshed");
        }
        return cachedToken;
    }

    /** Invalidate the cached token (call after a 401 response). */
    public synchronized void invalidateToken() {
        cachedToken = null;
        tokenExpiry = Instant.EPOCH;
    }

    // ── Token request ─────────────────────────────────────────────────────────

    private String requestNewToken() throws Exception {
        Map<String, String> cfg = configService.loadConnection();
        String instanceUrl = cfg.get("instanceUrl");
        String clientId    = cfg.get("clientId");
        String sfUsername  = cfg.get("sfUsername");
        String privateKeyPem = cfg.get("privateKey");

        if (instanceUrl == null || clientId == null || sfUsername == null || privateKeyPem == null) {
            throw new IllegalStateException("[SFDC-Auth] Connector not configured — run the admin UI Connection tab first");
        }

        String jwt = buildJwt(instanceUrl, clientId, sfUsername, privateKeyPem);
        return exchangeJwtForToken(instanceUrl, jwt);
    }

    // ── JWT construction ──────────────────────────────────────────────────────

    private String buildJwt(String instanceUrl, String clientId, String sfUsername, String pem) throws Exception {
        long now = Instant.now().getEpochSecond();

        // Build header and payload with a real JSON encoder so any characters in clientId /
        // sfUsername / instanceUrl (backslash, control chars, …) are escaped correctly.
        String header  = base64Url(new JSONObject().put("alg", "RS256").toString());
        String payload = base64Url(new JSONObject()
                .put("iss", clientId)
                .put("sub", sfUsername)
                .put("aud", instanceUrl)
                .put("exp", now + JWT_TTL_SECONDS)
                .toString());

        String signingInput = header + "." + payload;

        // Sign with RS256
        PrivateKey privateKey = loadPrivateKey(pem);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());

        return signingInput + "." + signature;
    }

    private String exchangeJwtForToken(String instanceUrl, String jwt) throws Exception {
        String body = "grant_type=" + URLEncoder.encode(
            "urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8) +
            "&assertion=" + URLEncoder.encode(jwt, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(instanceUrl + TOKEN_PATH))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(30))
            .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("[SFDC-Auth] Token request failed HTTP " + response.statusCode() + ": " + response.body());
        }

        String responseBody = response.body();
        String token = new JSONObject(responseBody).optString("access_token", null);
        if (token == null || token.isBlank()) {
            throw new RuntimeException("[SFDC-Auth] No access_token in response: " + responseBody);
        }
        return token;
    }

    // ── Key loading ───────────────────────────────────────────────────────────

    private PrivateKey loadPrivateKey(String pem) throws Exception {
        // Strip PEM headers and whitespace
        String b64 = pem
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(b64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String base64Url(String input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }
}
