package io.vantage.loganalyzer;

import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

/**
 * Proxies log uploads to log-mcp-server (Server2) instead of having the
 * browser call it directly.
 *
 * <p><strong>Bug found and fixed (2026-07-27):</strong> the original design
 * had the browser call log-mcp-server directly (cross-origin, CORS
 * configured for it). That worked fine tested via curl run ON Server3's own
 * shell — but the browser itself runs on the user's own workstation, a
 * genuinely different network position than Server3. Confirmed via browser
 * console: {@code net::ERR_CONNECTION_TIMED_OUT} trying to reach Server2
 * directly, despite Server3-to-Server2 connectivity working fine (curl
 * proved this). CORS was never the actual problem — fixing the CORS origin
 * address earlier was necessary but not sufficient, since CORS only matters
 * once a connection succeeds, and this one never did.
 *
 * <p>The real fix: proxy through Server3, which the browser already
 * demonstrably reaches (the rest of the UI works), letting the
 * server-to-server Server3-&gt;Server2 hop — already confirmed working —
 * carry the file instead of asking the browser to reach Server2 directly.
 * This also removes the need to hardcode Server2's address in the frontend
 * at all, a real, valid complaint about the original design.
 */
@RestController
public class LogUploadProxyController {

    private static final Logger log = LoggerFactory.getLogger(LogUploadProxyController.class);

    private final RestClient restClient = RestClient.create();

    @Value("${vantage.vantage-mcp-server.upload-url}")
    private String logMcpUploadUrl;

    @PostMapping("/api/logs/upload")
    public Map<String, Object> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "caseName", required = false) String caseName,
            @RequestParam(value = "jiraTicket", required = false) String jiraTicket) {

        try {
            ByteArrayResource fileResource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename();
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", fileResource);
            if (caseName != null) {
                body.add("caseName", caseName);
            }
            if (jiraTicket != null) {
                body.add("jiraTicket", jiraTicket);
            }

            return restClient.post()
                    .uri(logMcpUploadUrl)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (IOException e) {
            log.error("Failed to read uploaded file '{}'", file.getOriginalFilename(), e);
            return Map.of("success", false, "error", "Failed to read uploaded file: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to forward upload to log-mcp-server", e);
            return Map.of("success", false, "error", "Couldn't reach the log indexing service: " + e.getMessage());
        }
    }
}
