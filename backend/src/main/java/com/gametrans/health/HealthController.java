package com.gametrans.health;

import com.gametrans.config.AppProperties;
import com.gametrans.llm.LlmClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {
    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final AppProperties props;
    private final LlmClient llm;
    private Boolean cachedOk;
    private Instant cachedAt = Instant.EPOCH;

    public HealthController(AppProperties props, LlmClient llm) {
        this.props = props;
        this.llm = llm;
    }

    @GetMapping("/llm")
    public Map<String, Object> llm() {
        boolean configured = props.openai().apiKey() != null && !props.openai().apiKey().isBlank();
        boolean ok = configured && refreshedOk();
        return Map.of("configured", configured, "ok", ok, "model", props.openai().model(), "lightModel", props.openai().lightModelOrDefault());
    }

    /** Pings the LLM at most once per 10 minutes; synchronized so concurrent requests
     * within the TTL window share one cached result instead of racing or double-pinging. */
    private synchronized boolean refreshedOk() {
        if (cachedOk == null || Duration.between(cachedAt, Instant.now()).toMinutes() >= 10) {
            try {
                cachedOk = llm.ping();
            } catch (RuntimeException e) {
                log.warn("LLM ping failed: {}", e.toString());
                cachedOk = false;
            }
            cachedAt = Instant.now();
        }
        return cachedOk;
    }
}
