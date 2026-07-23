package org.jahia.community.unomi.sfdc;

import org.apache.unomi.api.Event;
import org.apache.unomi.api.Profile;
import org.apache.unomi.api.actions.Action;
import org.apache.unomi.api.actions.ActionExecutor;
import org.apache.unomi.api.services.EventService;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Unomi action that notifies the Jahia SFDC connector when a monitored event fires, so it can push
 * the affected profile to Salesforce as a Lead.
 *
 * <p>Registered as action executor {@code sfdcWebhook} (see
 * {@code META-INF/cxs/actions/sfdcWebhookAction.json}). Rules pass two parameters:
 * <ul>
 *   <li>{@code url}    — the Jahia webhook URL (…/sites/systemsite.sfdcOutboundWebhook.do)</li>
 *   <li>{@code secret} — the shared secret sent as {@code X-Sfdc-Webhook-Secret}</li>
 * </ul>
 *
 * <p>The call is <b>fire-and-forget</b> (async, short connect timeout): Unomi executes rule actions
 * synchronously in the event pipeline, so this must not block. Requires the profile to carry an
 * {@code email} (used by Jahia to match/create the Lead); otherwise it is a no-op.
 */
@Component(service = ActionExecutor.class, property = {"actionExecutorId=sfdcWebhook"})
public class SfdcWebhookActionExecutor implements ActionExecutor {

    private static final Logger logger = LoggerFactory.getLogger(SfdcWebhookActionExecutor.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Override
    public int execute(Action action, Event event) {
        try {
            Object urlObj = action.getParameterValues().get("url");
            if (urlObj == null || urlObj.toString().isBlank()) {
                return EventService.NO_CHANGE;
            }
            String url = urlObj.toString();
            Object secretObj = action.getParameterValues().get("secret");
            String secret = secretObj == null ? "" : secretObj.toString();

            Profile profile = event.getProfile();
            Object emailObj = profile != null ? profile.getProperty("email") : null;
            if (emailObj == null || emailObj.toString().isBlank()) {
                // The profile-update / form trigger needs an email to match or create the Lead.
                return EventService.NO_CHANGE;
            }

            String body = "{\"email\":\"" + esc(emailObj.toString())
                    + "\",\"eventType\":\"" + esc(event.getEventType()) + "\"}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("X-Sfdc-Webhook-Secret", secret)
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            // Fire-and-forget: do not block the synchronous event pipeline on the response.
            HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((resp, err) -> {
                    if (err != null) {
                        logger.warn("[SFDC-Unomi] Webhook call failed: {}", err.getMessage());
                    } else if (resp.statusCode() >= 300) {
                        logger.warn("[SFDC-Unomi] Webhook returned HTTP {}", resp.statusCode());
                    }
                });
        } catch (Exception e) {
            logger.warn("[SFDC-Unomi] Webhook action error: {}", e.getMessage());
        }
        return EventService.NO_CHANGE;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
