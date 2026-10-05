package meko.agentic.sre.config;

import com.embabel.common.ai.model.LlmOptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

import static meko.agentic.sre.config.ReasoningOptionsConverter.Style.EFFORT_FIELD;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Groq tiers put the right model and the right reasoning effort on the wire.
 *
 * Everything here is deliberately testable without a key or a network: the two ways this
 * integration can be wrong in a way that still looks fine are both pure functions of
 * configuration, and both would otherwise only show up as a 404 or as a silently collapsed tier
 * during a live demo.
 */
class GroqTierConfigurationTest {

    private GroqProperties defaults() {
        return new GroqProperties(null, null, null, null, null, null, null);
    }

    @Test
    void defaultsAreGroqsNamespacedGptOssIds() {
        var groq = defaults();

        assertEquals("https://api.groq.com/openai/v1", groq.baseUrl());
        assertEquals("openai/gpt-oss-20b", groq.slmModel());
        assertEquals("openai/gpt-oss-120b", groq.llmModel());
        assertEquals("openai/gpt-oss-120b", groq.frontierModel());
    }

    /**
     * The tiers are two efforts of one model, and that is the whole point of the escalation step.
     * If these ever came back equal, the agent would still run and the report would still render
     * — it would just be escalating to itself.
     */
    @Test
    void theTwoLargeTiersShareAModelButNotAnEffort() {
        var groq = defaults();

        assertEquals(groq.llmModel(), groq.frontierModel(), "the tiers are one model");
        assertNotEquals(groq.llmEffort(), groq.frontierEffort(), "...at two efforts");
        assertEquals("low", groq.llmEffort());
        assertEquals("high", groq.frontierEffort());
    }

    @Test
    void gptOssOnlyEffortsAreRejectedAtStartupNotOnFirstCall() {
        // "none" and "default" are real Groq values — for Qwen, not for gpt-oss. Accepting them
        // here would turn a config typo into a 400 in front of an audience.
        var bad = assertThrows(IllegalArgumentException.class,
                () -> new GroqProperties(null, null, null, null, null, "none", null));
        assertTrue(bad.getMessage().contains("groq.llm-effort"), bad.getMessage());
        assertTrue(bad.getMessage().contains("low"), bad.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> new GroqProperties(null, null, null, null, null, null, "exhaustive"));
    }

    @Test
    void effortIsCaseInsensitiveAndTrimmed() {
        var groq = new GroqProperties(null, null, null, null, null, "  LOW  ", "High");

        assertEquals("low", groq.llmEffort());
        assertEquals("high", groq.frontierEffort());
    }

    @Test
    void aMissingKeyIsReportedRatherThanGuessedAt() {
        assertFalse(defaults().hasApiKey());
        assertFalse(new GroqProperties(null, "   ", null, null, null, null, null).hasApiKey());
        assertTrue(new GroqProperties(null, "gsk_live", null, null, null, null, null).hasApiKey());
    }

    /**
     * The registered name is an Embabel handle; the model id is what Groq is asked for.
     *
     * Embabel's own converter treats the name it is passed AS the model id, which works only
     * because its registered names are model ids. Ours are not — they describe the tier — so a
     * converter that trusted that argument would send "groq-gpt-oss-120b-high" to Groq and 404.
     */
    @Test
    void theRegisteredNameNeverReachesTheWire() {
        var converter = new ReasoningOptionsConverter("openai/gpt-oss-120b", "high", EFFORT_FIELD);

        var options = (OpenAiChatOptions) converter.convertOptions(
                LlmOptions.withDefaultLlm(), GroqModelsConfig.FRONTIER_NAME);

        assertEquals("openai/gpt-oss-120b", options.getModel());
        assertEquals("high", options.getReasoningEffort());
    }

    @Test
    void eachTierSendsItsOwnEffort() {
        var low = (OpenAiChatOptions) new ReasoningOptionsConverter("openai/gpt-oss-120b", "low", EFFORT_FIELD)
                .convertOptions(LlmOptions.withDefaultLlm(), GroqModelsConfig.LLM_NAME);
        var high = (OpenAiChatOptions) new ReasoningOptionsConverter("openai/gpt-oss-120b", "high", EFFORT_FIELD)
                .convertOptions(LlmOptions.withDefaultLlm(), GroqModelsConfig.FRONTIER_NAME);

        assertEquals(low.getModel(), high.getModel());
        assertEquals("low", low.getReasoningEffort());
        assertEquals("high", high.getReasoningEffort());
    }

    /**
     * Reasoning effort is not a sampling nicety. Unset sampling params are omitted so the
     * request says nothing about them, but the effort is sent on every call — dropping it would
     * collapse the two tiers into one while everything still appeared to work.
     */
    @Test
    void unsetSamplingParametersAreOmittedButEffortIsNot() {
        var options = (OpenAiChatOptions) new ReasoningOptionsConverter("openai/gpt-oss-20b", "low", EFFORT_FIELD)
                .convertOptions(LlmOptions.withDefaultLlm(), GroqModelsConfig.SLM_NAME);

        assertNull(options.getTemperature());
        assertNull(options.getMaxTokens());
        assertNull(options.getTopP());
        assertEquals("low", options.getReasoningEffort());
    }

    @Test
    void callerSuppliedSamplingParametersArePassedThrough() {
        var options = (OpenAiChatOptions) new ReasoningOptionsConverter("openai/gpt-oss-20b", "low", EFFORT_FIELD)
                .convertOptions(
                        LlmOptions.withDefaultLlm().withTemperature(0.2).withMaxTokens(512),
                        GroqModelsConfig.SLM_NAME);

        assertEquals(0.2, options.getTemperature());
        assertEquals(512, options.getMaxTokens());
    }
}
