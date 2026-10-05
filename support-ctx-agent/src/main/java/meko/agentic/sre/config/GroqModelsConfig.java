package meko.agentic.sre.config;

import com.embabel.agent.spi.LlmService;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * The three Groq tiers: gpt-oss-20b, and gpt-oss-120b at low and high reasoning effort.
 *
 * <p>Groq takes OpenAI's top-level {@code reasoning_effort} field, so the tiers are distinguished
 * with {@link ReasoningOptionsConverter.Style#EFFORT_FIELD}. See {@link TieredProviderConfig} for
 * why two tiers can share a model id but not a registered name.
 *
 * <p>ON RATE LIMITS. Groq's free tier caps tokens per minute far below what this demo's cold run
 * sends in a single request, so a full sweep there returns 413 rather than an answer. That is a
 * quota, not a bug, and it lands on exactly the run the demo exists to contrast against. The
 * openrouter profile exists partly for that reason.
 */
@Configuration
@Profile(GroqModelsConfig.PROFILE)
public class GroqModelsConfig extends TieredProviderConfig {

    /** Matches the filename of application-groq.yml, which carries the role map for these tiers. */
    public static final String PROFILE = "groq";

    public static final String PROVIDER = "Groq";

    public static final String SLM_NAME = "groq-gpt-oss-20b";
    public static final String LLM_NAME = "groq-gpt-oss-120b-low";
    public static final String FRONTIER_NAME = "groq-gpt-oss-120b-high";

    public GroqModelsConfig(
            GroqProperties groq,
            TokenProperties tokens,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<RestClient.Builder> restClientBuilder,
            ObjectProvider<WebClient.Builder> webClientBuilder) {

        super(groq, tokens, PROVIDER, ReasoningOptionsConverter.Style.EFFORT_FIELD,
                Map.of(), observationRegistry, restClientBuilder, webClientBuilder);
    }

    @Bean
    public LlmService<?> groqSlm() {
        return slmTier(SLM_NAME);
    }

    @Bean
    public LlmService<?> groqLlm() {
        return llmTier(LLM_NAME);
    }

    @Bean
    public LlmService<?> groqFrontier() {
        return frontierTier(FRONTIER_NAME);
    }
}
