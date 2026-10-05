package meko.agentic.sre.config;

import meko.agentic.sre.domain.IncidentSeverity;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The profiles resolve the way Spring will resolve them at run time.
 *
 * ModelRoleWiringTest reads the yaml as text, which proves the files say the right things but not
 * that Boot's layering puts them together — that the profile file overlays the base, that a role
 * map living only in a profile still binds, and that switching profiles moves the prices with the
 * models. ConfigDataApplicationContextInitializer runs the real config-data machinery, so this
 * asserts the merged result rather than the inputs.
 *
 * Deliberately not @SpringBootTest: the full context needs the Meko MCP client, which reaches the
 * network at startup and has nothing to do with which provider a profile selects.
 */
class ProfileResolutionTest {

    @Configuration
    @EnableConfigurationProperties({TokenProperties.class, GroqProperties.class,
            OpenRouterProperties.class, SweepProperties.class})
    static class Harness {
    }

    /**
     * Activation goes through the property, not setActiveProfiles: config-data processing reads
     * `spring.profiles.active` while deciding which files to load, and a programmatic call on the
     * environment lands after that decision — so the base file's own default wins and the test
     * silently measures the wrong profile.
     */
    private ApplicationContextRunner onProfile(String profile) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profile)
                .withUserConfiguration(Harness.class);
    }

    @Test
    void theGroqProfilePricesTheRolesAtGptOssRates() {
        onProfile("groq").run(context -> {
            var tokens = context.getBean(TokenProperties.class);

            assertThat(tokens.priceFor("slm").inputPerMillion()).isEqualTo(0.075);
            assertThat(tokens.priceFor("slm").outputPerMillion()).isEqualTo(0.30);
            assertThat(tokens.priceFor("frontier").inputPerMillion()).isEqualTo(0.15);
        });
    }

    @Test
    void theGeminiProfilePricesTheRolesAtGeminiRates() {
        onProfile("gemini").run(context -> {
            var tokens = context.getBean(TokenProperties.class);

            assertThat(tokens.priceFor("slm").inputPerMillion()).isEqualTo(0.10);
            assertThat(tokens.priceFor("llm").inputPerMillion()).isEqualTo(1.50);
            assertThat(tokens.priceFor("frontier").outputPerMillion()).isEqualTo(12.00);
        });
    }

    /**
     * The role map lives only in the profile files, so this is also the check that a profile's
     * yaml is being picked up at all. A typo in a filename would leave these unset, and
     * TokenProperties' fallback price would quietly stand in for the missing one.
     */
    @Test
    void switchingProfileMovesTheRoleMapAndThePricesTogether() {
        onProfile("groq").run(context -> {
            var env = context.getEnvironment();

            assertThat(env.getProperty("embabel.models.llms.slm"))
                    .isEqualTo(GroqModelsConfig.SLM_NAME);
            assertThat(env.getProperty("embabel.models.llms.frontier"))
                    .isEqualTo(GroqModelsConfig.FRONTIER_NAME);
        });

        onProfile("gemini").run(context -> {
            var env = context.getEnvironment();

            assertThat(env.getProperty("embabel.models.llms.slm")).startsWith("gemini-");
            assertThat(env.getProperty("embabel.models.llms.frontier")).startsWith("gemini-");
            assertThat(env.getProperty("embabel.models.llms.frontier"))
                    .isNotEqualTo(env.getProperty("embabel.models.llms.llm"));
        });
    }

    /** What every profile shares has to survive the switch, or the split has broken the base. */
    @Test
    void providerNeutralSettingsSurviveEitherProfile() {
        for (var profile : new String[]{"groq", "gemini", "openrouter"}) {
            onProfile(profile).run(context -> {
                var tokens = context.getBean(TokenProperties.class);

                assertThat(tokens.reuse().shortCircuitThreshold()).isEqualTo(0.85);
                assertThat(tokens.reuse().scopedThreshold()).isEqualTo(0.55);
                assertThat(tokens.budgetFor(IncidentSeverity.SEV2)).isEqualTo(0.15);
                assertThat(tokens.priceFor("embedding").inputPerMillion()).isEqualTo(0.02);
                assertThat(tokens.projection().incidentsPerMonth()).isEqualTo(2000);
            });
        }
    }

    @Test
    void theOpenRouterProfilePricesTheRolesAndRoutesItsOwnTiers() {
        onProfile("openrouter").run(context -> {
            var env = context.getEnvironment();

            assertThat(env.getProperty("embabel.models.llms.slm"))
                    .isEqualTo(OpenRouterModelsConfig.SLM_NAME);
            assertThat(env.getProperty("embabel.models.llms.frontier"))
                    .isEqualTo(OpenRouterModelsConfig.FRONTIER_NAME);
            assertThat(context.getBean(OpenRouterProperties.class).baseUrl())
                    .isEqualTo("https://openrouter.ai/api/v1");
            assertThat(context.getBean(TokenProperties.class).priceFor("slm").inputPerMillion())
                    .isEqualTo(0.075);
        });
    }

    /**
     * Only the groq profile cuts the sweep, and only because its free tier rejects the full one.
     *
     * If this ever starts failing for another profile, that profile has quietly started reporting
     * a smaller saving than it could — which is the direction of error nobody notices, because a
     * demo that undersells itself still looks like it works.
     */
    @Test
    void onlyTheRateLimitedProfileReducesTheSweep() {
        onProfile("groq").run(context -> {
            var sweep = context.getBean(SweepProperties.class);

            assertThat(sweep.reduced()).isTrue();
            assertThat(sweep.linesPerService()).isEqualTo(10);
            assertThat(sweep.disclosure()).contains("60").contains("measured");
        });

        for (var profile : new String[]{"gemini", "openrouter"}) {
            onProfile(profile).run(context -> {
                var sweep = context.getBean(SweepProperties.class);

                assertThat(sweep.linesPerService()).isEqualTo(SweepProperties.FULL_DEFAULT);
                assertThat(sweep.reduced()).isFalse();
                assertThat(sweep.disclosure()).isEmpty();
            });
        }
    }

    /** A paid Groq key puts the honest sweep back, and the banner with it. */
    @Test
    void theSweepKnobCanBeRaisedBackOnTheGroqProfile() {
        onProfile("groq")
                .withPropertyValues("incident.sweep.lines-per-service=60")
                .run(context -> assertThat(context.getBean(SweepProperties.class).reduced()).isFalse());
    }

    /** Each provider's config is confined to its own profile — no dead clients, no stray warning. */
    @Test
    void eachProvidersTiersExistOnlyOnItsOwnProfile() {
        onProfile("groq")
                .withUserConfiguration(GroqModelsConfig.class, OpenRouterModelsConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(GroqModelsConfig.class);
                    assertThat(context).doesNotHaveBean(OpenRouterModelsConfig.class);
                });

        onProfile("openrouter")
                .withUserConfiguration(GroqModelsConfig.class, OpenRouterModelsConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenRouterModelsConfig.class);
                    assertThat(context).doesNotHaveBean(GroqModelsConfig.class);
                });

        onProfile("gemini")
                .withUserConfiguration(GroqModelsConfig.class, OpenRouterModelsConfig.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(GroqModelsConfig.class);
                    assertThat(context).doesNotHaveBean(OpenRouterModelsConfig.class);
                });
    }
}
