package meko.agentic.goap.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The three model tiers the response writer can be routed to. Each tier is any OpenAI-compatible
 * {@code /chat/completions} endpoint: Ollama locally ({@code http://localhost:11434/v1}), Groq, vLLM, llama.cpp,
 * LM Studio, OpenRouter...
 */
@ConfigurationProperties(prefix = "goap.llm")
public class LlmTierProperties {
    /** When false, the planner still routes between tiers but the writer uses a template instead of calling a model. */
    private boolean enabled = true;
    private Map<String, Tier> tiers = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Map<String, Tier> getTiers() { return tiers; }
    public void setTiers(Map<String, Tier> tiers) { this.tiers = tiers; }

    public static class Tier {
        private String provider = "";
        private String baseUrl = "";
        private String model = "";
        private String apiKey = "";
        /** Whether the tier needs an API key to be usable (hosted providers). */
        private boolean requiresKey;
        /**
         * Ollama only: context window to load the model with. Ollama otherwise uses the model's maximum, which for
         * llama3.1 is 128K tokens (23 GB) and does not fit a 16 GB laptop.
         */
        private int numCtx = 8192;
        /**
         * Extra request fields for OpenAI-compatible endpoints, e.g. Groq's {@code reasoning_effort} and
         * {@code include_reasoning} for reasoning models.
         */
        private Map<String, Object> extraBody = new LinkedHashMap<>();
        /** Extra request headers, e.g. OpenRouter's optional {@code HTTP-Referer} and {@code X-Title} app attribution. */
        private Map<String, String> headers = new LinkedHashMap<>();
        /** Output budget. Reasoning models may spend part of it on reasoning tokens. */
        private int maxTokens = 1500;
        /** List price, USD per million input tokens. 0 for local models. */
        private double usdPer1mInput;
        /** List price, USD per million output tokens (reasoning tokens are billed as output). */
        private double usdPer1mOutput;
        /** Typical output tokens per writer call at this tier's reasoning effort, for the planner's price estimate. */
        private int expectedOutputTokens = 300;
        /** Planner price used when the tier has no list price (local models). */
        private double fallbackPlannerPrice = 0.05;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public boolean isRequiresKey() { return requiresKey; }
        public void setRequiresKey(boolean requiresKey) { this.requiresKey = requiresKey; }
        public int getNumCtx() { return numCtx; }
        public void setNumCtx(int numCtx) { this.numCtx = numCtx; }
        public Map<String, Object> getExtraBody() { return extraBody; }
        public void setExtraBody(Map<String, Object> extraBody) { this.extraBody = extraBody; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public double getUsdPer1mInput() { return usdPer1mInput; }
        public void setUsdPer1mInput(double usdPer1mInput) { this.usdPer1mInput = usdPer1mInput; }
        public double getUsdPer1mOutput() { return usdPer1mOutput; }
        public void setUsdPer1mOutput(double usdPer1mOutput) { this.usdPer1mOutput = usdPer1mOutput; }
        public int getExpectedOutputTokens() { return expectedOutputTokens; }
        public void setExpectedOutputTokens(int expectedOutputTokens) { this.expectedOutputTokens = expectedOutputTokens; }
        public double getFallbackPlannerPrice() { return fallbackPlannerPrice; }
        public void setFallbackPlannerPrice(double fallbackPlannerPrice) { this.fallbackPlannerPrice = fallbackPlannerPrice; }
        public boolean ollama() { return "ollama".equalsIgnoreCase(provider); }

        /** List-price cost of one call, in USD. */
        public double usd(Integer promptTokens, Integer completionTokens) {
            return ((promptTokens == null ? 0 : promptTokens) * usdPer1mInput
                    + (completionTokens == null ? 0 : completionTokens) * usdPer1mOutput) / 1_000_000.0;
        }

        public boolean configured() {
            return !baseUrl.isBlank() && !model.isBlank() && (!requiresKey || (apiKey != null && !apiKey.isBlank()));
        }
    }
}
