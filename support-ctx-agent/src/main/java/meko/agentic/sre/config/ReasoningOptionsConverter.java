package meko.agentic.sre.config;

import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.ai.model.OptionsConverter;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Map;

/**
 * Builds the request for one tier: its model id, and the reasoning effort that tier exists for.
 *
 * <h2>Why the registered name is ignored</h2>
 * The {@code registeredName} Embabel passes in is the name the LLM was REGISTERED under, not a
 * model id. Embabel's own converter does {@code builder.model(name)} and gets away with it because
 * its registered names are model ids. Ours are not: on every provider here, two of the three tiers
 * are the same model at different reasoning efforts, so they need distinct Embabel names and cannot
 * both be called {@code openai/gpt-oss-120b}. The id is carried on the converter instead, and the
 * passed name is deliberately discarded — using it would send a model id the provider has never
 * heard of and 404 every call.
 *
 * <h2>Why reasoning has two shapes</h2>
 * Reasoning effort is the whole difference between the llm and frontier tiers, and providers
 * disagree about how to express it. Groq takes OpenAI's top-level {@code reasoning_effort} string.
 * OpenRouter normalises reasoning across the providers it routes to and reads a {@code reasoning}
 * OBJECT instead — it does not accept {@code reasoning_effort}, and ignores it silently.
 * <p>
 * Silently is the problem. A dropped sampling parameter costs a little quality; a dropped reasoning
 * effort makes the frontier tier identical to the llm tier while every test still passes, the app
 * still runs, and the escalation step still appears to work. So the shape is explicit per provider
 * and asserted on the wire rather than inferred.
 */
public final class ReasoningOptionsConverter implements OptionsConverter {

    /** How a provider wants reasoning effort expressed. Neither form is a superset of the other. */
    public enum Style {
        /** OpenAI and Groq: {@code "reasoning_effort": "high"} at the top level. */
        EFFORT_FIELD,
        /** OpenRouter: {@code "reasoning": {"effort": "high"}}. */
        REASONING_OBJECT
    }

    private final String modelId;
    private final String reasoningEffort;
    private final Style style;

    public ReasoningOptionsConverter(String modelId, String reasoningEffort, Style style) {
        this.modelId = modelId;
        this.reasoningEffort = reasoningEffort;
        this.style = style;
    }

    public String modelId() {
        return modelId;
    }

    public String reasoningEffort() {
        return reasoningEffort;
    }

    public Style style() {
        return style;
    }

    @NotNull
    @Override
    public ChatOptions convertOptions(@NotNull LlmOptions options, @NotNull String registeredName) {
        var builder = OpenAiChatOptions.builder().model(modelId);

        switch (style) {
            case EFFORT_FIELD -> builder.reasoningEffort(reasoningEffort);
            case REASONING_OBJECT -> builder.extraBody(Map.of("reasoning", Map.of("effort", reasoningEffort)));
        }

        // Sampling parameters pass through only when the caller set them. These models accept all
        // of them, but sending an explicit null is a different request from omitting the field and
        // a provider is entitled to treat it as one.
        if (options.getMaxTokens() != null) {
            builder.maxTokens(options.getMaxTokens());
        }
        if (options.getTemperature() != null) {
            builder.temperature(options.getTemperature());
        }
        if (options.getTopP() != null) {
            builder.topP(options.getTopP());
        }
        if (options.getPresencePenalty() != null) {
            builder.presencePenalty(options.getPresencePenalty());
        }
        if (options.getFrequencyPenalty() != null) {
            builder.frequencyPenalty(options.getFrequencyPenalty());
        }

        return builder.build();
    }
}
