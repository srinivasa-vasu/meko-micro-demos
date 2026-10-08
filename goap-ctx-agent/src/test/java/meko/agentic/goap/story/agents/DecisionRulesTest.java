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
}
