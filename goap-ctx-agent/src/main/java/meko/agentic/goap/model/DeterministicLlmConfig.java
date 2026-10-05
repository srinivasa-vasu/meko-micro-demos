package meko.agentic.goap.model;

import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embabel refuses to start without at least one registered LLM, even when no action prompts one.
 * The story agents are deterministic, so this registers a model that only exists to satisfy that
 * check: any accidental prompt fails loudly instead of silently calling a provider.
 * <p>
 * To use real models, add an Embabel provider starter (e.g. embabel-agent-starter-google-genai)
 * and point {@code embabel.models.default-llm} at one of its models.
 */
@Configuration
public class DeterministicLlmConfig {
    public static final String MODEL_NAME = "deterministic-placeholder";

    @Bean
    SpringAiLlmService deterministicPlaceholderLlm() {
        ChatModel refuse = prompt -> {
            throw new UnsupportedOperationException(
                    "The story agents are deterministic; no LLM provider is configured. Prompt: " + prompt);
        };
        return new SpringAiLlmService(MODEL_NAME, "local", refuse);
    }
}
