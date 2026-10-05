package meko.agentic.goap.story;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileFingerprintsTest {
    private final ProfileFingerprints fingerprints = new ProfileFingerprints("test-key");

    @Test
    void sameValueSameCustomerMatchesSoTheNextReviewCanDiff() {
        assertThat(fingerprints.of("CUST-1", "address", "12 Harbour Road")).isEqualTo(fingerprints.of("CUST-1", "address", " 12  harbour road "));
    }

    @Test
    void theSameBeneficiaryUnderTwoCustomersCannotBeLinked() {
        assertThat(fingerprints.of("CUST-1", "beneficiary", "Orion Trading FZE")).isNotEqualTo(fingerprints.of("CUST-2", "beneficiary", "Orion Trading FZE"));
    }

    @Test
    void withoutTheKeyAGuessedValueDoesNotMatch() {
        assertThat(new ProfileFingerprints("another-key").of("CUST-1", "address", "12 Harbour Road"))
                .isNotEqualTo(fingerprints.of("CUST-1", "address", "12 Harbour Road"));
    }

    @Test
    void theStoredReviewRoundTripsAndHoldsNoProfileValue() {
        KycReviewMemory review = KycReviewMemory.of(fingerprints, "CUST-1", "2026-10-04", "KYC-1", "c1", "MEDIUM",
                "12 Harbour Road", "60/40 between two directors", List.of("Orion Trading FZE"), 44800);
        assertThat(review.text()).doesNotContain("Harbour", "directors", "Orion", "44800");
        assertThat(KycReviewMemory.parse(review.text())).contains(review);
    }

    @Test
    void volumeIsABandAndHowFarItMovedIsMeasuredAgainstIt() {
        assertThat(ProfileFingerprints.volumeBand(44800)).isEqualTo("40000-50000");
        assertThat(ProfileFingerprints.percentOutside("40000-50000", 44800)).isZero();
        assertThat(ProfileFingerprints.percentOutside("40000-50000", 62500)).isEqualTo(25.0);
    }
}
