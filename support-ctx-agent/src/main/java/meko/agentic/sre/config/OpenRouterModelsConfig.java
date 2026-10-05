package meko.agentic.sre.config;

import com.embabel.agent.spi.LlmService;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The three OpenRouter tiers. Same models as the groq profile, reached through a router.
 *
 * <h2>The one thing that is genuinely different</h2>
 * OpenRouter normalises reasoning across every provider it routes to, and reads a {@code reasoning}
 * OBJECT rather than OpenAI's top-level {@code reasoning_effort}. It does not reject the field it
 * does not use — it ignores it. Sending Groq's shape here would leave both 120b tiers at the
 * router's default effort: the frontier tier would become the llm tier, every test would still
 * pass, and the escalation step would still appear to work. Hence
 * {@link ReasoningOptionsConverter.Style#REASONING_OBJECT}, asserted against a stub in
 * {@code ProviderRequestShapeTest} rather than taken on trust.
 *
 * <h2>Why this profile exists</h2>
 * Besides offering more models, it is the way around Groq's free-tier tokens-per-minute ceiling,
 * which this demo's cold run exceeds in a single request. The demo's whole argument rests on
 * contrasting that expensive run against a cheap one, so the expensive run has to be able to
 * complete.
 */
@Configuration
@Profile(OpenRouterModelsConfig.PROFILE)
public class OpenRouterModelsConfig extends TieredProviderConfig {

    /** Matches the filename of application-openrouter.yml. */
    public static final String PROFILE = "openrouter";

    public static final String PROVIDER = "OpenRouter";

    public static final String SLM_NAME = "openrouter-gpt-oss-20b";
    public static final String LLM_NAME = "openrouter-gpt-oss-120b-low";
    public static final String FRONTIER_NAME = "openrouter-gpt-oss-120b-high";

    public OpenRouterModelsConfig(
            OpenRouterProperties openRouter,
            TokenProperties tokens,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<RestClient.Builder> restClientBuilder,
            ObjectProvider<WebClient.Builder> webClientBuilder) {

        super(openRouter, tokens, PROVIDER, ReasoningOptionsConverter.Style.REASONING_OBJECT,
                attribution(openRouter), observationRegistry, restClientBuilder, webClientBuilder);
    }

    /**
     * Optional headers that list the app on OpenRouter's leaderboards. Omitted when unset rather
     * than sent blank, since an empty attribution is worse than none.
     */
    private static Map<String, String> attribution(OpenRouterProperties openRouter) {
        var headers = new LinkedHashMap<String, String>();
        if (!openRouter.siteUrl().isBlank()) {
            headers.put("HTTP-Referer", openRouter.siteUrl());
        }
        if (!openRouter.appTitle().isBlank()) {
            headers.put("X-Title", openRouter.appTitle());
        }
        return Map.copyOf(headers);
    }

    @Bean
    public LlmService<?> openRouterSlm() {
        return slmTier(SLM_NAME);
    }

    @Bean
    public LlmService<?> openRouterLlm() {
        return llmTier(LLM_NAME);
    }

    @Bean
    public LlmService<?> openRouterFrontier() {
        return frontierTier(FRONTIER_NAME);
    }
}
