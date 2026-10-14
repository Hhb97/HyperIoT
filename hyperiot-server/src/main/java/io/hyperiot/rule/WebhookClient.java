package io.hyperiot.rule;

import io.hyperiot.config.HyperIotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Component
public class WebhookClient {

    private static final Logger log = LoggerFactory.getLogger(WebhookClient.class);

    private final HyperIotProperties.Webhook cfg;
    private final HttpClient http;

    public WebhookClient(HyperIotProperties props) {
        this.cfg = props.getWebhook();
        // redirects are never followed: a redirect could point to an internal address the guard did not check
        this.http = HttpClient.newBuilder().connectTimeout(cfg.getTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public String validate(String url) {
        return UrlGuard.check(url, cfg.isAllowPrivateAddresses());
    }

    public CompletableFuture<Void> post(String url, String json) {
        String problem = validate(url);
        if (problem != null) {
            log.warn("Webhook to {} blocked: {}", url, problem);
            return CompletableFuture.completedFuture(null);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(cfg.getTimeout())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).handle((resp, ex) -> {
            if (ex != null) {
                log.warn("Webhook to {} failed: {}", url, ex.toString());
            } else if (resp.statusCode() >= 400) {
                log.warn("Webhook to {} returned HTTP {}", url, resp.statusCode());
            }
            return null;
        });
    }
}
