package meko.agentic.sre.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where Groq lives and which model answers for each tier.
 *
 * <p>Every field is configuration rather than a constant because the two things most likely to be
 * wrong on someone else's machine are the model ids and the reasoning efforts, and neither should
 * need a rebuild to correct. Groq namespaces the open-weight models under their origin —
 * {@code openai/gpt-oss-120b}, not {@code gpt-oss-120b} — which is the single easiest thing to get
 * wrong here, and it fails as a 404 at call time rather than at startup.
 *
 * @param frontierModel normally the SAME id as {@code llmModel}: the tiers differ by reasoning
 *                      effort, not by model. See {@link TieredProviderConfig}.
 */
@ConfigurationProperties(prefix = "groq")
public record GroqProperties(
        String baseUrl,
        String apiKey,
        String slmModel,
        String llmModel,
        String frontierModel,
        String llmEffort,
        String frontierEffort
) implements ProviderTiers {

    public GroqProperties {
        baseUrl = ProviderTiers.blankTo(baseUrl, "https://api.groq.com/openai/v1");
        apiKey = apiKey == null ? "" : apiKey.trim();
        slmModel = ProviderTiers.blankTo(slmModel, "openai/gpt-oss-20b");
        llmModel = ProviderTiers.blankTo(llmModel, "openai/gpt-oss-120b");
        frontierModel = ProviderTiers.blankTo(frontierModel, "openai/gpt-oss-120b");
        llmEffort = ProviderTiers.validEffort(
                ProviderTiers.blankTo(llmEffort, "low"), "groq.llm-effort");
        frontierEffort = ProviderTiers.validEffort(
                ProviderTiers.blankTo(frontierEffort, "high"), "groq.frontier-effort");
    }
}
