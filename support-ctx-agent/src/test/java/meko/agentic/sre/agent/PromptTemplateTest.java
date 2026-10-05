package meko.agentic.sre.agent;

import com.embabel.common.textio.template.JinjavaTemplateRenderer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The prompt templates are now files, which means a typo in one is a runtime problem rather
 * than a compile error — and Jinjava's default is to render an unknown key as empty rather
 * than to fail. A prompt that quietly loses its telemetry section still gets sent, still gets
 * an answer, and still gets a confidence score. Nothing looks wrong.
 *
 * These tests are the compile step that externalising the prompts gave away.
 */
class PromptTemplateTest {

    private final JinjavaTemplateRenderer renderer = new JinjavaTemplateRenderer();

    private Map<String, Object> fullModel() {
        var m = new LinkedHashMap<String, Object>();
        m.put("tier", "slm");
        m.put("role", "slm");
        m.put("previousAttempt", "No previous model attempt.");
        m.put("incident", "INC-4821 checkout");
        m.put("scope", "SCOPED");
        m.put("scopedTo", List.of("checkout", "payment-client"));
        m.put("evidence", "EVIDENCE-BLOCK-MARKER");
        m.put("memory", "MEMORY-BLOCK-MARKER");
        m.put("knowledge", "KNOWLEDGE-BLOCK-MARKER");
        m.put("reuseAssessment", "REUSE-MARKER");
        m.put("routingRationale", "ROUTING-MARKER");
        return m;
    }

    @Test
    void everySectionOfTheDiagnosisPromptIsPopulated() {
        var out = renderer.renderLoadedTemplate("incident-diagnosis", fullModel());

        for (var marker : List.of("EVIDENCE-BLOCK-MARKER", "MEMORY-BLOCK-MARKER",
                "KNOWLEDGE-BLOCK-MARKER", "REUSE-MARKER", "ROUTING-MARKER")) {
            assertTrue(out.contains(marker), "template dropped " + marker + ":\n" + out);
        }
        assertTrue(out.contains("remediationAssumptions"),
                "the assumptions instruction is what makes RemediationValidator able to work");
        assertFalse(out.contains("{{"), "unrendered placeholder left in prompt:\n" + out);
    }

    @Test
    void theNarrowingClauseAppearsOnlyWhenTelemetryWasNarrowed() {
        var scoped = renderer.renderLoadedTemplate("incident-diagnosis", fullModel());
        assertTrue(scoped.contains("narrowed to checkout, payment-client"));

        var model = fullModel();
        model.put("scopedTo", List.of());
        var full = renderer.renderLoadedTemplate("incident-diagnosis", model);
        assertFalse(full.contains("narrowed to"),
                "a full sweep must not claim it was narrowed:\n" + full);
    }

    @Test
    void theCounterfactualCarriesTheFullEvidence() {
        var out = renderer.renderLoadedTemplate("counterfactual", Map.of(
                "evidence", "FULL-SWEEP-MARKER", "memory", "MEMORY-BLOCK-MARKER"));

        assertTrue(out.contains("FULL-SWEEP-MARKER"));
        assertTrue(out.contains("MEMORY-BLOCK-MARKER"));
        assertFalse(out.contains("{{"));
    }

    @Test
    void aMissingKeyRendersEmptyRatherThanFailing() {
        // Documents the hazard this test class exists for: Jinjava does not throw here.
        var out = renderer.renderLoadedTemplate("incident-diagnosis", Map.of("tier", "slm"));

        assertFalse(out.contains("EVIDENCE-BLOCK-MARKER"));
        assertTrue(out.contains("You are a production SRE"),
                "the prompt still renders and would still be sent — which is the whole problem");
    }
}
