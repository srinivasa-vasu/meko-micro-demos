package meko.agentic.goap.model;

import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers one Embabel model per tier ({@code tier-slm}, {@code tier-llm}, {@code tier-frontier}, bound to the
 * {@code slm}/{@code llm}/{@code frontier} roles in application.yml) and reports which tiers are usable right now.
 * The response writer's planner costs read {@link #available}, so an unusable tier is priced out of the plan
 * instead of failing at call time.
 */
@Configuration
@EnableConfigurationProperties(LlmTierProperties.class)
public class LlmTiers {
    public static final List<String> TIERS = List.of("slm", "llm", "frontier");
    private static final Duration CACHE = Duration.ofSeconds(30);

    /** @param effort the reasoning effort sent to the model, if any: mid and frontier can be the same model at different effort */
    public record TierStatus(String tier, String provider, String model, String effort, boolean available, String diagnosis) {}

    private record Cached(TierStatus status, long at) {}

    private final LlmTierProperties properties;
    private final ModelCallLog log;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public LlmTiers(LlmTierProperties properties, ModelCallLog log) {
        this.properties = properties;
        this.log = log;
    }

    @Bean
    SpringAiLlmService slmTierLlm() {
        return service("slm");
    }

    @Bean
    SpringAiLlmService llmTierLlm() {
        return service("llm");
    }

    @Bean
    SpringAiLlmService frontierTierLlm() {
        return service("frontier");
    }

    private SpringAiLlmService service(String tier) {
        LlmTierProperties.Tier config = tier(tier);
        return new SpringAiLlmService("tier-" + tier, config.getProvider().isBlank() ? "openai-compatible" : config.getProvider(),
                new OpenAiCompatibleChatModel(tier, config, log));
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public LlmTierProperties.Tier tier(String tier) {
        return properties.getTiers().getOrDefault(tier, new LlmTierProperties.Tier());
    }

    public boolean available(String tier) {
        return status(tier).available();
    }

    public Map<String, TierStatus> statuses() {
        Map<String, TierStatus> all = new LinkedHashMap<>();
        TIERS.forEach(t -> all.put(t, status(t)));
        return all;
    }

    public TierStatus status(String tier) {
        Cached cached = cache.get(tier);
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE.toMillis()) return cached.status();
        TierStatus status = probe(tier);
        cache.put(tier, new Cached(status, System.currentTimeMillis()));
        return status;
    }

    private TierStatus probe(String tier) {
        LlmTierProperties.Tier config = tier(tier);
        if (!properties.isEnabled()) return new TierStatus(tier, config.getProvider(), config.getModel(), effort(config), false, "model calls disabled (goap.llm.enabled=false)");
        if (!config.configured()) {
            return new TierStatus(tier, config.getProvider(), config.getModel(), effort(config), false,
                    config.isRequiresKey() && (config.getApiKey() == null || config.getApiKey().isBlank())
                            ? "no API key (set it to enable this tier)" : "not configured");
        }
        try {
            String base = config.getBaseUrl().endsWith("/") ? config.getBaseUrl().substring(0, config.getBaseUrl().length() - 1) : config.getBaseUrl();
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/models")).timeout(Duration.ofSeconds(4)).GET();
            if (config.getApiKey() != null && !config.getApiKey().isBlank()) request.header("Authorization", "Bearer " + config.getApiKey());
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                return new TierStatus(tier, config.getProvider(), config.getModel(), effort(config), false, "HTTP " + response.statusCode() + " from " + base);
            }
            JsonNode models = objectMapper.readTree(response.body()).path("data");
            boolean present = models.valueStream().anyMatch(m -> config.getModel().equals(m.path("id").asString("")));
            if (present && "openrouter".equalsIgnoreCase(config.getProvider())) {
                // OpenRouter's model list is public, so it proves nothing about the key; /key does.
                HttpResponse<String> key = http.send(HttpRequest.newBuilder(URI.create(base + "/key")).timeout(Duration.ofSeconds(4))
                        .header("Authorization", "Bearer " + config.getApiKey()).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (key.statusCode() >= 300) {
                    return new TierStatus(tier, config.getProvider(), config.getModel(), effort(config), false,
                            "API key rejected (HTTP " + key.statusCode() + " from " + base + "/key)");
                }
            }
            return present ? new TierStatus(tier, config.getProvider(), config.getModel(), effort(config), true, "ready")
                    : new TierStatus(tier, config.getProvider(), config.getModel(), effort(config), false, "model " + config.getModel() + " not available at " + base
                    + ("ollama".equalsIgnoreCase(config.getProvider()) ? " (run: ollama pull " + config.getModel() + ")" : ""));
        } catch (Exception e) {
            return new TierStatus(tier, config.getProvider(), config.getModel(), effort(config), false, "unreachable: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()));
        }
    }

    /** Planner price of one typical frontier call (~600 prompt tokens), leaving room for the per-gap risk terms. */
    private static final double FRONTIER_PLANNER_PRICE = 0.30;
    private static final int TYPICAL_PROMPT_TOKENS = 600;
    /** Used when the frontier tier has no list price (local): Groq's frontier call, about $0.001, prices at ~0.30. */
    private static final double DEFAULT_USD_PER_PLANNER_UNIT = 0.0033;

    /**
     * USD per planner unit, scaled to the configured frontier tier so that routing (no gaps: small, one gap: mid,
     * two: frontier) does not depend on which provider's prices are configured. OpenRouter lists these models at
     * roughly a quarter of Groq's prices; a fixed exchange rate would make the frontier tier look cheaper than the
     * risk of the mid tier and skip it.
     */
    private double usdPerPlannerUnit() {
        LlmTierProperties.Tier frontier = tier("frontier");
        double usd = frontier.usd(TYPICAL_PROMPT_TOKENS, frontier.getExpectedOutputTokens());
        return usd > 0 ? usd / FRONTIER_PLANNER_PRICE : DEFAULT_USD_PER_PLANNER_UNIT;
    }

    /**
     * The tier's price term for the planner: the estimated list-price cost of one call with this prompt, in planner
     * units. Local tiers have no list price and use their configured fallback.
     */
    public double plannerPrice(String tier, int promptTokens) {
        LlmTierProperties.Tier config = tier(tier);
        double usd = config.usd(promptTokens, config.getExpectedOutputTokens());
        return usd <= 0 ? config.getFallbackPlannerPrice() : usd / usdPerPlannerUnit();
    }

    private static String effort(LlmTierProperties.Tier config) {
        Object effort = config.getExtraBody().get("reasoning_effort");
        return effort == null ? null : effort.toString();
    }

    public void invalidate() {
        cache.clear();
    }

    /** Sends a one-line prompt to every available tier so the first story run doesn't pay Ollama's model-load time. */
    public Map<String, String> warmUp() {
        invalidate();
        Map<String, String> result = new LinkedHashMap<>();
        for (String tier : TIERS) {
            TierStatus status = status(tier);
            if (!status.available()) {
                result.put(tier, "skipped: " + status.diagnosis());
                continue;
            }
            long start = System.nanoTime();
            try {
                new OpenAiCompatibleChatModel(tier, tier(tier), log)
                        .call(new org.springframework.ai.chat.prompt.Prompt(ModelCallLog.caseTag("warmup") + " Reply with the single word: ready"));
                result.put(tier, status.model() + " ready in " + (System.nanoTime() - start) / 1_000_000 + " ms");
            } catch (RuntimeException e) {
                result.put(tier, "failed: " + e.getMessage());
            }
        }
        return result;
    }
}
