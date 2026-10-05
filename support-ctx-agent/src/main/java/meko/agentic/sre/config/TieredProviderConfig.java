package meko.agentic.sre.config;

import com.embabel.agent.openai.OpenAiCompatibleModelFactory;
import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.model.PerTokenPricingModel;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Shared machinery for a provider that serves the three model roles over the OpenAI wire protocol.
 *
 * <h2>Why a factory subclass rather than configuration</h2>
 * Embabel ships endpoints for OpenAI, DeepSeek, Mistral, Gemini and Atlas Cloud. Groq and
 * OpenRouter are not among them, but both speak the same protocol, so
 * {@link OpenAiCompatibleModelFactory} takes either with nothing more than a base URL. Subclassing
 * is how that class is meant to be extended; {@code chatModelOf} is protected for exactly this.
 *
 * <h2>The constraint that shapes all of this</h2>
 * Embabel keys a model by the name it was registered under and hands that name to the options
 * converter as the model id. Two tiers registered under one name would be ONE service — so a
 * provider whose llm and frontier tiers are the same model at two reasoning efforts cannot
 * register them under the model id. Tiers are registered under names describing the tier, and
 * {@link ReasoningOptionsConverter} carries the real id onto the wire.
 *
 * <h2>Booting without a key</h2>
 * Within its own profile a provider registers its tiers whether or not the key is set. Embabel
 * treats a role naming an unregistered model as fatal at startup, so keying registration on the
 * API key would turn a missing key into a crash that takes down the whole demo, including the
 * parts that never call a model. An absent key surfaces as a 401 on the first run instead, which
 * says what is actually wrong. The PROFILE is the right thing to condition on, and subclasses do.
 */
public abstract class TieredProviderConfig extends OpenAiCompatibleModelFactory {

    private static final Logger log = LoggerFactory.getLogger(TieredProviderConfig.class);

    private final ProviderTiers tiers;
    private final TokenProperties tokens;
    private final String provider;
    private final ReasoningOptionsConverter.Style style;

    protected TieredProviderConfig(
            ProviderTiers tiers,
            TokenProperties tokens,
            String provider,
            ReasoningOptionsConverter.Style style,
            Map<String, String> httpHeaders,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<RestClient.Builder> restClientBuilder,
            ObjectProvider<WebClient.Builder> webClientBuilder) {

        super(tiers.baseUrl(),
                tiers.apiKey(),
                null,
                null,
                httpHeaders,
                observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP),
                restClientBuilder,
                webClientBuilder);

        this.tiers = tiers;
        this.tokens = tokens;
        this.provider = provider;
        this.style = style;

        if (!tiers.hasApiKey()) {
            log.warn("No API key for {}. Its tiers are registered so the app still starts, but any "
                    + "run that reaches a model will fail with a 401.", provider);
        }
    }

    protected LlmService<?> slmTier(String registeredName) {
        // The small tier exists to be the cheap one, so it is left at the provider's default
        // effort rather than being asked to think harder.
        return tier(registeredName, tiers.slmModel(), "low", "slm");
    }

    protected LlmService<?> llmTier(String registeredName) {
        return tier(registeredName, tiers.llmModel(), tiers.llmEffort(), "llm");
    }

    protected LlmService<?> frontierTier(String registeredName) {
        return tier(registeredName, tiers.frontierModel(), tiers.frontierEffort(), "frontier");
    }

    /**
     * One tier, priced from the same {@code token.pricing} entries the ledger reports with.
     * <p>
     * Embabel carries pricing per model and the demo carries pricing per role, and the screen shows
     * both. Deriving one from the other means they cannot drift into saying different things about
     * the same call — which, in a demo whose subject is cost, is the failure that would matter most.
     * <p>
     * The knowledge cutoff is left null on purpose: Embabel turns a cutoff date into a system
     * prompt line asserting it, and an unverified date is a claim this demo would be making on the
     * model's behalf.
     */
    private LlmService<?> tier(String registeredName, String modelId, String effort, String role) {
        var price = tokens.priceFor(role);
        var converter = new ReasoningOptionsConverter(modelId, effort, style);

        log.info("{} tier '{}' -> model {} (effort={} as {}), priced at ${}/${} per 1M in/out",
                provider, registeredName, modelId, effort, style,
                price.inputPerMillion(), price.outputPerMillion());

        return new SpringAiLlmService(
                registeredName,
                provider,
                chatModelOf(modelId),
                converter,
                null,
                List.of(),
                new PerTokenPricingModel(price.inputPerMillion(), price.outputPerMillion()));
    }
}
