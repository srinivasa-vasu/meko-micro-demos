package meko.agentic.sre.config;

import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.ai.model.OptionsConverter;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Turns Embabel's per-call options into a Groq request, pinning the model id and the
 * reasoning effort this tier was registered for.
 *
 * <p>TWO THINGS HERE ARE NOT OBVIOUS.
 *
 * <p>First, the {@code name} Embabel passes in is the name the LLM was REGISTERED under, not a
 * model id. Embabel's own converter does {@code builder.model(name)} and gets away with it
 * because its registered names are model ids. Ours are not: two of our three tiers are the same
 * Groq model at different reasoning efforts, so they need distinct Embabel names and cannot both
 * be called {@code openai/gpt-oss-120b}. The id is therefore carried on the converter and the
 * passed name is deliberately ignored — if it were used, the tiers would resolve to model ids
 * Groq has never heard of and every call would 404.
 *
 * <p>Second, reasoning effort is the whole difference between the llm and frontier tiers. It is
 * not a sampling nicety that can be dropped if unsupported, the way temperature can be: dropping
 * it silently collapses two tiers into one and the demo's escalation story stops being real while
 * continuing to look fine. So it is always sent.
 */
public final class GroqOptionsConverter implements OptionsConverter {

    private final String modelId;
    private final String reasoningEffort;

    public GroqOptionsConverter(String modelId, String reasoningEffort) {
        this.modelId = modelId;
        this.reasoningEffort = reasoningEffort;
    }

    public String modelId() {
        return modelId;
    }

    public String reasoningEffort() {
        return reasoningEffort;
    }

    @NotNull
    @Override
    public ChatOptions convertOptions(@NotNull LlmOptions options, @NotNull String registeredName) {
        var builder = OpenAiChatOptions.builder()
                .model(modelId)
                .reasoningEffort(reasoningEffort);

        // Pass sampling parameters through only when the caller set them. Groq accepts all of
        // these for gpt-oss, but sending explicit nulls is a different request from omitting
        // the field, and the provider is entitled to treat it as one.
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
