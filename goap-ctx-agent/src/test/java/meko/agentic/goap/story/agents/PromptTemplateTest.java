package meko.agentic.goap.story.agents;

import com.embabel.common.textio.template.JinjaProperties;
import com.embabel.common.textio.template.JinjavaTemplateRenderer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prompts are Jinja files, so a typo in one is a runtime problem rather than a compile error, and Jinjava renders
 * an unknown variable as empty by default. These tests render every template in strict mode (unknown tokens fail),
 * which is the compile step that externalising the prompts gave away.
 */
class PromptTemplateTest {
    private final JinjavaTemplateRenderer strict =
            new JinjavaTemplateRenderer(new JinjaProperties("classpath:/prompts/", ".jinja", true));

    @ParameterizedTest
    @ValueSource(strings = {"support-known-facts", "support-policy", "sre-progress", "kyc-prior-review",
            "kyc-materiality", "sdlc-context", "payments-pattern-fit", "audit-record", "audit-compliance", "kyc-check-record", "kyc-check-standard",
            "lending-flags", "lending-precedent", "lending-share-ruling", "lending-policy", "lending-credit-call", "lending-review-record", "lending-review-compliance"})
    void everyDecisionRendersItsQuestionContextAndAnswerShape(String gate) {
        String prompt = DecisionService.prompt(strict, "SUP-TEST01", "decisions/" + gate,
                Map.of("customer", "CUST-7788", "pr", "PR-4821", "transaction", "TX-10001", "review", "KYC-TEST01", "application", "APP-10001", "flags", "deposit: a deposit", "evidence", "the bill of sale", "item", "evidence of a deposit's source", "kind", "a vehicle sale"), "CONTEXT-MARKER");
        assertThat(prompt).startsWith("[case SUP-TEST01] [decision " + gate + "]");
        assertThat(DecisionService.question(prompt)).isNotBlank();
        assertThat(prompt).contains("CONTEXT-MARKER").contains("Use ONLY the context");
        assertThat(prompt.substring(prompt.indexOf("in this shape:"))).contains("{\"");
        assertThat(prompt).doesNotContain("{{").doesNotContain("{%");
    }

    @Test
    void gateVariablesAreRendered() {
        assertThat(DecisionService.prompt(strict, "C", "decisions/kyc-prior-review", Map.of("customer", "CUST-7788"), "x"))
                .contains("customer CUST-7788 (exactly this customer id)");
        assertThat(DecisionService.prompt(strict, "C", "decisions/sdlc-context", Map.of("pr", "PR-4821"), "x"))
                .contains("Only items about PR-4821 itself count");
    }

    @Test
    void writerListsFactsAndOnlyMentionsGapsWhenThereAreSome() {
        Map<String, Object> model = new HashMap<>(Map.of("caseTag", "[case C]", "persona", "KYC analyst agent",
                "purpose", "review rationale", "instructions", "INSTRUCTIONS-MARKER", "facts", List.of("FACT-1", "FACT-2"),
                "gaps", List.of()));
        String noGaps = strict.renderLoadedTemplate("writer", model);
        assertThat(noGaps).contains("You are the KYC analyst agent").contains("- FACT-1").contains("- FACT-2")
                .contains("INSTRUCTIONS-MARKER").doesNotContain("NOT known");
        model.put("gaps", List.of("GAP-1"));
        assertThat(strict.renderLoadedTemplate("writer", model)).contains("NOT known").contains("- GAP-1");
    }

    @Test
    void theCustomerReplyApologisesOnlyWhenTheCustomerHadToRepeatThemselves() {
        assertThat(strict.renderLoadedTemplate("writer/support-reply", Map.of("reasked", 3, "redidTroubleshooting", true))).contains("Apologise specifically").contains("redo troubleshooting");
        assertThat(strict.renderLoadedTemplate("writer/support-reply", Map.of("reasked", 0, "redidTroubleshooting", false))).contains("Do not apologise");
    }

    @Test
    void theBorrowerMessageGivesReasonsOnlyForADeclineAndApologisesOnlyForRepetition() {
        assertThat(strict.renderLoadedTemplate("writer/borrower-credit-call", Map.of("reexplained", 2, "decision", "approve")))
                .contains("Apologise specifically").doesNotContain("adverse-action");
        assertThat(strict.renderLoadedTemplate("writer/borrower-credit-call", Map.of("reexplained", 0, "decision", "decline")))
                .contains("adverse-action").contains("Do not apologise");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sre-handover", "kyc-rationale", "payments-resolution-note", "lending-review-finding"})
    void writerInstructionsRender(String name) {
        assertThat(strict.renderLoadedTemplate("writer/" + name, Map.of())).startsWith("Write ");
    }

    @Test
    void catchUpInstructionsNameThePr() {
        assertThat(strict.renderLoadedTemplate("writer/sdlc-catch-up", Map.of("pr", "PR-4821"))).contains("PR-4821");
    }
}
