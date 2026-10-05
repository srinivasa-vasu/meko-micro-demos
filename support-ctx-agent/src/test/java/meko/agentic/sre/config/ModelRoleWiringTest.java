package meko.agentic.sre.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each provider profile is complete and internally consistent.
 *
 * Embabel resolves `embabel.models.llms.<role>` against registered model NAMES and fails at
 * startup if one does not resolve, so a one-character slip in a yaml string is a crash found by
 * running the app. The names invite one: `groq-gpt-oss-120b-low` is a tier handle that merely
 * resembles the model id `openai/gpt-oss-120b`, and Embabel registers Gemini under its dotted
 * model_id while the bundled catalogue also carries an underscored bean name.
 *
 * The second thing checked here is that a profile is a complete switch. Pricing and the role map
 * are one decision — which models run, and what they cost — so a profile that moved the roles and
 * left the prices behind would report another provider's dollars without anything looking wrong.
 * That is the failure the split exists to prevent, so it is the one under test.
 */
class ModelRoleWiringTest {

    /** `${AI_SLM_MODEL:groq-gpt-oss-20b}` -> `groq-gpt-oss-20b`. */
    private static final Pattern PLACEHOLDER_DEFAULT = Pattern.compile("^\\$\\{[^:}]+:(.*)}$");

    private static final Set<String> ROLES = Set.of("slm", "llm", "frontier");

    private static final Set<String> GROQ_TIERS = Set.of(
            GroqModelsConfig.SLM_NAME,
            GroqModelsConfig.LLM_NAME,
            GroqModelsConfig.FRONTIER_NAME);

    private static final Set<String> OPENROUTER_TIERS = Set.of(
            OpenRouterModelsConfig.SLM_NAME,
            OpenRouterModelsConfig.LLM_NAME,
            OpenRouterModelsConfig.FRONTIER_NAME);

    /** Every profile the app ships. Adding one without adding it here leaves it untested. */
    private static final List<String> PROFILES = List.of("groq", "gemini", "openrouter");

    /** Registered by Embabel's google-genai starter under model_id — see its bundled catalogue. */
    private static final Set<String> GEMINI_MODELS = Set.of(
            "gemini-3.5-flash", "gemini-3.1-flash-lite", "gemini-3.1-pro-preview",
            "gemini-3.1-pro-preview-customtools", "gemini-3-flash-preview",
            "gemini-3.1-flash-lite-preview", "gemini-2.5-pro", "gemini-2.5-flash",
            "gemini-2.5-flash-lite");

    @Test
    void theGroqProfileRoutesEveryRoleToARegisteredTier() {
        assertRolesResolve("groq", GROQ_TIERS);
    }

    @Test
    void theGeminiProfileRoutesEveryRoleToARegisteredModel() {
        assertRolesResolve("gemini", GEMINI_MODELS);
    }

    @Test
    void theOpenRouterProfileRoutesEveryRoleToARegisteredTier() {
        assertRolesResolve("openrouter", OPENROUTER_TIERS);
    }

    /**
     * Three roles, three different models — otherwise the routing the demo is about is one model
     * called three times. On groq the distinction is carried by the tier NAMES, since two of them
     * are one model at two reasoning efforts.
     */
    @Test
    void neitherProfileCollapsesItsRolesOntoOneTier() {
        for (var profile : PROFILES) {
            var resolved = roleMap(profile).values().stream()
                    .map(ModelRoleWiringTest::defaultOf)
                    .distinct()
                    .toList();

            assertEquals(3, resolved.size(),
                    profile + " collapsed its roles onto the same tier: " + resolved);
        }
    }

    /**
     * A profile that switched the models and kept the previous provider's prices would report
     * plausible, wrong dollars — the one failure the file split exists to make impossible.
     */
    @Test
    void everyProfileCarriesItsOwnPricesForEveryRole() {
        for (var profile : PROFILES) {
            var pricing = pricing(profile);

            assertTrue(pricing.keySet().containsAll(ROLES),
                    profile + " prices only " + pricing.keySet() + "; a role priced in the base "
                            + "file would keep the other provider's rate");

            ROLES.forEach(role -> {
                var entry = pricing.get(role);
                assertTrue(entry.containsKey("input-per-million"), profile + "/" + role);
                assertTrue(entry.containsKey("output-per-million"), profile + "/" + role);
            });
        }
    }

    /** The base file holds what every provider shares, and nothing that picks one. */
    @Test
    void theBaseFileStaysProviderNeutral() {
        var base = yaml("application.yml");

        var embabel = child(base, "embabel");
        assertFalse(embabel.containsKey("models"),
                "the role map belongs to a profile; in the base file it would apply to all of them");

        var pricing = child(child(base, "token"), "pricing");
        assertTrue(pricing.keySet().stream().noneMatch(ROLES::contains),
                "model-role prices belong next to the role map, not in the base file: "
                        + pricing.keySet());
        assertTrue(pricing.containsKey("embedding"),
                "the Meko embedding rate is not provider-specific and stays in the base file");
    }

    /**
     * The default profile is one the app actually ships.
     *
     * Deliberately not pinned to a particular provider: which one leads is an operational choice
     * that moves — free-tier rate limits alone are reason enough to switch it. What must not
     * happen is the default naming a profile with no file behind it, which starts the app with
     * no role map and fails on the first model call rather than at boot.
     */
    @Test
    void theDefaultProfileIsOneThatShips() {
        var profiles = child(child(yaml("application.yml"), "spring"), "profiles");
        var active = defaultOf((String) profiles.get("active"));

        assertTrue(PROFILES.contains(active),
                "spring.profiles.active defaults to '" + active + "', which has no "
                        + "application-" + active + ".yml behind it; shipped profiles are " + PROFILES);
    }

    @Test
    void theGroqModelIdsCarryGroqsNamespacePrefix() {
        // On Groq the open-weight models are namespaced by origin. Dropping the prefix is a 404
        // at call time, which is both the easiest mistake here and the latest-arriving one.
        var groq = new GroqProperties(null, null, null, null, null, null, null);

        assertTrue(groq.slmModel().startsWith("openai/"), groq.slmModel());
        assertTrue(groq.llmModel().startsWith("openai/"), groq.llmModel());
        assertTrue(groq.frontierModel().startsWith("openai/"), groq.frontierModel());
    }

    // ------------------------------------------------------------------

    private void assertRolesResolve(String profile, Set<String> registered) {
        var llms = roleMap(profile);

        assertEquals(ROLES, llms.keySet(), profile + " must map exactly the three routed roles");

        llms.forEach((role, raw) -> {
            var name = defaultOf(raw);
            assertTrue(registered.contains(name),
                    profile + "/" + role + " defaults to '" + name + "', which is not a model "
                            + "that profile registers; Embabel fails at startup on this");
        });

        var defaultLlm = defaultOf((String) models(profile).get("default-llm"));
        assertTrue(registered.contains(defaultLlm),
                profile + " default-llm is '" + defaultLlm + "', which is not registered");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> roleMap(String profile) {
        return (Map<String, String>) models(profile).get("llms");
    }

    private Map<String, Object> models(String profile) {
        return child(yaml("application-" + profile + ".yml"), "embabel", "models");
    }

    private Map<String, Map<String, Object>> pricing(String profile) {
        var raw = child(yaml("application-" + profile + ".yml"), "token", "pricing");
        @SuppressWarnings("unchecked")
        var typed = (Map<String, Map<String, Object>>) (Map<?, ?>) raw;
        return typed;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> child(Map<String, Object> node, String... path) {
        var current = node;
        for (var key : path) {
            current = (Map<String, Object>) current.get(key);
            if (current == null) {
                throw new AssertionError("missing yaml path segment: " + key);
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yaml(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return (Map<String, Object>) new Yaml().load(in);
        } catch (Exception e) {
            throw new AssertionError("could not read " + resource, e);
        }
    }

    private static String defaultOf(String placeholder) {
        var matcher = PLACEHOLDER_DEFAULT.matcher(placeholder.trim());
        return matcher.matches() ? matcher.group(1) : placeholder.trim();
    }
}
