package meko.agentic.goap.api;

import meko.agentic.goap.model.LlmTiers;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/llm")
public class LlmController {
    private final LlmTiers tiers;

    public LlmController(LlmTiers tiers) {
        this.tiers = tiers;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", tiers.enabled(), "tiers", tiers.statuses());
    }

    /** Loads the local models before the first story run. */
    @PostMapping("/warmup")
    public Map<String, String> warmUp() {
        return tiers.warmUp();
    }
}
