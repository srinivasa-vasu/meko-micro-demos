package meko.agentic.sre.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenRouter: the same three tiers, reached through a router rather than a single provider.
 *
 * <p>OpenRouter fronts many providers behind one OpenAI-compatible endpoint and namespaces models
 * by author, so the gpt-oss ids happen to read the same as Groq's. They are not the same thing:
 * here the id is a routing instruction, and which provider actually serves it — and therefore what
 * it costs and how fast it is — can vary between calls.
 *
 * @param siteUrl  optional attribution header for OpenRouter's leaderboards. Blank means not sent.
 * @param appTitle optional attribution header. Blank means not sent.
 */
@ConfigurationProperties(prefix = "openrouter")
public record OpenRouterProperties(
        String baseUrl,
        String apiKey,
        String slmModel,
        String llmModel,
        String frontierModel,
        String llmEffort,
        String frontierEffort,
        String siteUrl,
        String appTitle
) implements ProviderTiers {

    public OpenRouterProperties {
        baseUrl = ProviderTiers.blankTo(baseUrl, "https://openrouter.ai/api/v1");
        apiKey = apiKey == null ? "" : apiKey.trim();
        slmModel = ProviderTiers.blankTo(slmModel, "openai/gpt-oss-20b");
        llmModel = ProviderTiers.blankTo(llmModel, "openai/gpt-oss-120b");
        frontierModel = ProviderTiers.blankTo(frontierModel, "openai/gpt-oss-120b");
        llmEffort = ProviderTiers.validEffort(
                ProviderTiers.blankTo(llmEffort, "low"), "openrouter.llm-effort");
        frontierEffort = ProviderTiers.validEffort(
                ProviderTiers.blankTo(frontierEffort, "high"), "openrouter.frontier-effort");
        siteUrl = siteUrl == null ? "" : siteUrl.trim();
        appTitle = appTitle == null ? "" : appTitle.trim();
    }
}
