package path.to._40c.nqCore.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Thin HTTP client for the nqTicker sidecar. Extracted from SignalService so the open-buffer
 * delegation path has an injectable seam — tests mock this instead of standing up HTTP.
 */
@Service
@Slf4j
public class NqTickerClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${nq.ticker.url:http://localhost:9192}")
    private String nqTickerUrl;

    /**
     * Arms nqTicker's 9:15 open buffer with the bar's open price. nqTicker calls back on
     * /api/execute-close when its target or deadline hits. Returns false on any failure so
     * the caller can fall back to an immediate inline close.
     */
    public boolean armBuffer(String openPrice) {
        String url = nqTickerUrl + "/arm-buffer?openPrice=" + openPrice;
        try {
            HttpResponse<String> resp = httpClient.send(
                    HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() == 200;
            if (!ok) log.warn("arm-buffer returned HTTP {} — {}", resp.statusCode(), resp.body());
            return ok;
        } catch (Exception e) {
            log.error("arm-buffer HTTP call failed — is nqTicker open-buffer running on {}?", nqTickerUrl, e);
            return false;
        }
    }
}
