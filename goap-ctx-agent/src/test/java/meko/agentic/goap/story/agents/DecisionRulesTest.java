package meko.agentic.goap.story.agents;

import meko.agentic.goap.story.store.StoreRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules that settle a decision without a model: each reads only what the context states. */
class DecisionRulesTest {

    private static StoreRecord row(String text) {
        return new StoreRecord("", text, "test", "", null);
    }

    @Test
    void theDisputePolicyIsSettledByScopeOnlyWhenExactlyOnePolicyCoversTheCase() {
        var check = new PaymentsSpecialistAgent.SettlementCheck("c", true, false, false);
        var failedCapture = row("CARD-DISPUTE-07 policy: when the capture times out after a successful authorization and nothing settled, issue a provisional refund.");
        var chargeback = row("CARD-DISPUTE-03 policy: a chargeback the customer raised with their card issuer; do not refund while it is open.");
        assertThat(PaymentsSpecialistAgent.onlyCovering(List.of(failedCapture, chargeback), check)).contains("CARD-DISPUTE-07");
        assertThat(PaymentsSpecialistAgent.onlyCovering(List.of(chargeback), check)).isEmpty();
    }

    @Test
    void theVolumeThresholdComesFromTheStandardsText() {
        var standard = row("KYC-STD-04: a material increase in expected monthly volume needs an explanation: monthly volume more than 25%\n  outside the previous band is material.");
        assertThat(KycReviewAgent.volumeThreshold(List.of(standard))).contains(25.0);
        assertThat(KycReviewAgent.volumeThreshold(List.of(row("KYC-STD-04: no number here.")))).isEmpty();
        assertThat(KycReviewAgent.volumeThreshold(List.of())).isEmpty();
    }

    private static String kb(String file) throws Exception {
        return new String(DecisionRulesTest.class.getResourceAsStream("/kb/" + file).readAllBytes());
    }

    @Test
    void theRefundFollowsThePolicysOwnRuleOnRefunds() throws Exception {
        var timedOut = new PaymentsSpecialistAgent.SettlementCheck("c", true, false, false);
        var settled = new PaymentsSpecialistAgent.SettlementCheck("c", true, true, true);
        String failedCapture = kb("card-dispute-07-failed-capture-policy.md");
        assertThat(PaymentsSpecialistAgent.refundRule(failedCapture, timedOut).getKey()).isEqualTo(PaymentsSpecialistAgent.Refund.APPROVE);
        assertThat(PaymentsSpecialistAgent.refundRule(failedCapture, timedOut).getValue()).contains("may be approved");
        // Its condition (no settled capture) is checked against what the settlement check found.
        assertThat(PaymentsSpecialistAgent.refundRule(failedCapture, settled).getKey()).isEqualTo(PaymentsSpecialistAgent.Refund.REFUSE);
        assertThat(PaymentsSpecialistAgent.refundRule(kb("card-dispute-03-chargeback-policy.md"), timedOut).getKey()).isEqualTo(PaymentsSpecialistAgent.Refund.REFUSE);
        // The one-line version says the same; a policy silent on refunds decides nothing.
        String oneLine = meko.agentic.goap.story.StorySeederAccess.knowledge("CARD-DISPUTE-07");
        assertThat(PaymentsSpecialistAgent.refundRule(oneLine, timedOut).getKey()).isEqualTo(PaymentsSpecialistAgent.Refund.APPROVE);
        assertThat(PaymentsSpecialistAgent.refundRule(failedCapture.replaceAll("(?s)3\\. A provisional refund.*?exists\\.", ""), timedOut).getKey())
                .isEqualTo(PaymentsSpecialistAgent.Refund.NOT_STATED);
        assertThat(PaymentsSpecialistAgent.refundRule(null, timedOut).getKey()).isEqualTo(PaymentsSpecialistAgent.Refund.NOT_STATED);
    }

    @Test
    void kycMaterialityFollowsTheStandardsTextAndWithoutOneGoesToTheReviewer() throws Exception {
        var rules = KycReviewAgent.rules(List.of(row(kb("kyc-periodic-review-standard.md")))).orElseThrow();
        assertThat(rules.beneficiaryMaterial()).isTrue();
        assertThat(rules.beneficiaryEvidence()).isEqualTo("targeted source-of-funds evidence and sanctions screening");
        assertThat(rules.onlyUnchangedExempt()).isTrue();
        assertThat(rules.volumeThreshold()).contains(25.0);
        var prior = new KycReviewAgent.PriorReview("c", true, "2026-01-01", "x", null, "B", "a", "o", List.of(), "10000-20000");
        var diff = new KycReviewAgent.Diff(false, false, List.of("BEN-1"), 0, 10);
        var judged = KycReviewAgent.materialityByRule(diff, prior, java.util.Optional.of(rules));
        assertThat(judged.material()).containsExactly("new beneficiary BEN-1");
        assertThat(judged.notMaterial()).anyMatch(n -> n.startsWith("monthly volume 10%"));
        // No standard in reach: nothing can be judged immaterial, so every change goes to the reviewer, saying why.
        var unjudged = KycReviewAgent.materialityByRule(diff, prior, java.util.Optional.empty());
        assertThat(unjudged.material()).hasSize(2).allMatch(m -> m.contains("no written standard in reach"));
    }

    @Test
    void aLearnedPatternFitsOnlyByTheConditionsItsTextStates() {
        var timeout = new PaymentFailureAgent.Signals("c", null, "C", "T", "e", false, "", "", true, false, 0);
        var duplicate = new PaymentFailureAgent.Signals("c", null, "C", "T", "e", true, "", "", false, false, 2);
        String pattern = PaymentFailureAgent.PATTERN_MARKER + ": when card authorization succeeds but the gateway capture times out and no settlement exists, ...";
        assertThat(PaymentFailureAgent.misfit(timeout, pattern)).isEmpty();
        assertThat(PaymentFailureAgent.misfit(duplicate, pattern)).isPresent();
        assertThat(PaymentFailureAgent.misfit(timeout, "LEARNED PATTERN something: refund it.")).isPresent();
        assertThat(PaymentFailureAgent.rootCauseOf(pattern)).isEqualTo(PaymentFailureAgent.CAPTURE_TIMEOUT);
    }
}
