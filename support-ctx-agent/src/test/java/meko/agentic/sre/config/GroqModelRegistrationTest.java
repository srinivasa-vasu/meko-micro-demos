package meko.agentic.sre.config;

import com.embabel.agent.spi.LlmService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three Groq tiers register as three distinct services, with no API key present.
 *
 * Deliberately an ApplicationContextRunner rather than @SpringBootTest. Booting the whole app
 * needs the Meko MCP client, which reaches the network at startup and is nothing to do with
 * model registration — a test that needed it would be testing Meko's availability and blaming
 * Groq when it failed.
 *
 * The no-key part is the point of the first assertion. Embabel treats a role naming an
 * unregistered model as fatal at startup, so if registration were made conditional on
 * GROQ_API_KEY, a missing key would stop being "you cannot call a model" and start being "the
 * app does not start" — taking the staging controls, the scenario panel and the service picker
 * down with it.
 */
class GroqModelRegistrationTest {

    @Configuration
    @EnableConfigurationProperties({GroqProperties.class, TokenProperties.class})
    static class Harness {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles(GroqModelsConfig.PROFILE))
                .withUserConfiguration(Harness.class, GroqModelsConfig.class)
            .withPropertyValues("groq.api-key=");

    @Test
    void allThreeTiersRegisterWithoutAnApiKey() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            var names = context.getBeansOfType(LlmService.class).values().stream()
                    .map(LlmService::getName)
                    .toList();

            assertThat(names).containsExactlyInAnyOrder(
                    GroqModelsConfig.SLM_NAME,
                    GroqModelsConfig.LLM_NAME,
                    GroqModelsConfig.FRONTIER_NAME);
        });
    }

    /**
     * Embabel keys a model by its registered name. Two tiers sharing one would not be an error —
     * it would be one tier, with the escalation step quietly escalating to itself.
     */
    @Test
    void theTiersAreThreeServicesNotOneModelRegisteredThreeTimes() {
        runner.run(context -> {
            var tiers = List.copyOf(context.getBeansOfType(LlmService.class).values());

            assertThat(tiers).hasSize(3);
            assertThat(tiers).allSatisfy(t ->
                    assertThat(t.getProvider()).isEqualTo(GroqModelsConfig.PROVIDER));
            assertThat(tiers).extracting(LlmService::getName).doesNotHaveDuplicates();
        });
    }

    /**
     * The ledger and Embabel must agree about what a call costs. Both read token.pricing, so
     * they cannot disagree — this holds that wiring in place, since the alternative (a second
     * hardcoded price table in GroqModelsConfig) is the obvious thing for someone to reach for.
     */
    @Test
    void tierPricingComesFromTheSamePropertiesTheLedgerReports() {
        runner.withPropertyValues(
                "token.pricing.slm.input-per-million=1.5",
                "token.pricing.slm.output-per-million=2.5"
        ).run(context -> {
            var slm = context.getBeansOfType(LlmService.class).values().stream()
                    .filter(s -> GroqModelsConfig.SLM_NAME.equals(s.getName()))
                    .findFirst()
                    .orElseThrow();

            assertThat(slm.getPricingModel()).isNotNull();
            assertThat(slm.getPricingModel().usdPerInputToken()).isEqualTo(1.5 / 1_000_000);
            assertThat(slm.getPricingModel().usdPerOutputToken()).isEqualTo(2.5 / 1_000_000);
        });
    }
}
