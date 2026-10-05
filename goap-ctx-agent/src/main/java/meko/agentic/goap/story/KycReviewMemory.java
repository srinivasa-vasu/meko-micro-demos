package meko.agentic.goap.story;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a KYC review leaves in shared memory: the decision and the profile's keyed fingerprints (see
 * {@link ProfileFingerprints}), never the profile itself. The next review compares fingerprints to find what changed.
 * One format, written by the review agent and the seed, read back without a model.
 *
 * @param reviewId    the review case that wrote it, or null for one written before this application existed
 * @param cycle       the review cycle of a review this application wrote, or null; see {@code StoryService#reset}
 * @param riskClass   null when no class was set
 * @param volumeBand  monthly volume band in USD, "40000-50000"
 */
public record KycReviewMemory(String customer, String date, String reviewId, String cycle, String riskClass,
                              String address, String ownership, List<String> beneficiaries, String volumeBand) {

    private static final Pattern FORMAT = Pattern.compile(
            "KYC review of customer (\\S+) completed (\\d{4}-\\d{2}-\\d{2})(?: \\(review ([^)]+)\\))?(?: \\[review cycle ([^]]+)])?: "
                    + "(?:risk class (\\w+)\\. )?Profile fingerprint: address (\\w+); ownership (\\w+); beneficiaries ([\\w, ]*); "
                    + "monthly volume band (\\d+-\\d+) USD\\.");

    public String text() {
        return "KYC review of customer " + customer + " completed " + date
                + (reviewId == null ? "" : " (review " + reviewId + ")")
                + (cycle == null ? "" : " [review cycle " + cycle + "]") + ": "
                + (riskClass == null ? "" : "risk class " + riskClass + ". ")
                + "Profile fingerprint: address " + address + "; ownership " + ownership + "; beneficiaries "
                + String.join(", ", beneficiaries) + "; monthly volume band " + volumeBand + " USD.";
    }

    public static Optional<KycReviewMemory> parse(String text) {
        Matcher m = FORMAT.matcher(text == null ? "" : text);
        if (!m.find()) return Optional.empty();
        List<String> beneficiaries = m.group(8).isBlank() ? List.of() : Arrays.stream(m.group(8).split(",")).map(String::trim).toList();
        return Optional.of(new KycReviewMemory(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5),
                m.group(6), m.group(7), beneficiaries, m.group(9)));
    }

    /** Builds the memory from a profile held in the run (from core banking), fingerprinting every field. */
    public static KycReviewMemory of(ProfileFingerprints fingerprints, String customer, String date, String reviewId, String cycle,
                                     String riskClass, String address, String ownership, List<String> beneficiaries, int monthlyVolume) {
        return new KycReviewMemory(customer, date, reviewId, cycle, riskClass, fingerprints.of(customer, "address", address),
                fingerprints.of(customer, "ownership", ownership), fingerprints.beneficiaries(customer, beneficiaries),
                ProfileFingerprints.volumeBand(monthlyVolume));
    }
}
