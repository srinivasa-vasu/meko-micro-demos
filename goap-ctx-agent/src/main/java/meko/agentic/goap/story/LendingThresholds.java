package meko.agentic.goap.story;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The credit thresholds an underwriting policy states, read from the policy's own text: nothing here knows any policy's
 * numbers. The underwriter's fallback rule and the fair-lending reviewer's rule both apply what the text they actually
 * have says; with no text, there is nothing to apply.
 *
 * @param minScore           "credit score of at least N"
 * @param approveDti         "up to N% approve"
 * @param conditionalDti     "up to N% approve only with at least M months of reserves", or null when the policy has no such band
 * @param conditionalReserves the M of that band, or null
 */
public record LendingThresholds(int minScore, int approveDti, Integer conditionalDti, Integer conditionalReserves) {

    private static final Pattern SCORE = Pattern.compile("credit score of at least (\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern APPROVE = Pattern.compile("up to (\\d+)%\\s+approve(?!\\s+only)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONDITIONAL = Pattern.compile(
            "up to (\\d+)%\\s+approve only with at least (\\d+) months of reserves", Pattern.CASE_INSENSITIVE);

    public static Optional<LendingThresholds> parse(String policyText) {
        if (policyText == null) return Optional.empty();
        Matcher score = SCORE.matcher(policyText), approve = APPROVE.matcher(policyText), conditional = CONDITIONAL.matcher(policyText);
        if (!score.find() || !approve.find()) return Optional.empty();
        boolean band = conditional.find();
        return Optional.of(new LendingThresholds(Integer.parseInt(score.group(1)), Integer.parseInt(approve.group(1)),
                band ? Integer.valueOf(conditional.group(1)) : null, band ? Integer.valueOf(conditional.group(2)) : null));
    }

    /** What these thresholds say for the figures given: "approve" or "decline". DTI is compared exactly, not rounded. */
    public String apply(int score, double dtiPercent, int reservesMonths) {
        if (score < minScore) return "decline";
        if (dtiPercent <= approveDti) return "approve";
        if (conditionalDti != null && dtiPercent <= conditionalDti && reservesMonths >= conditionalReserves) return "approve";
        return "decline";
    }

    /** The thresholds themselves, for the record: what was applied. */
    public String describe() {
        return "credit score at least " + minScore + "; DTI up to " + approveDti + "% approve"
                + (conditionalDti == null ? "" : "; up to " + conditionalDti + "% approve with at least " + conditionalReserves + " months of reserves")
                + "; above that decline";
    }

    /** Why, in the policy's terms, for the record. */
    public String explain(int score, double dtiPercent, int reservesMonths) {
        String dti = String.format("%.1f", dtiPercent);
        String scoreText = "Score " + score + (score >= minScore ? " meets" : " is below") + " the minimum of " + minScore;
        String dtiText;
        if (dtiPercent <= approveDti) dtiText = "DTI " + dti + "% is within " + approveDti + "%";
        else if (conditionalDti != null && dtiPercent <= conditionalDti)
            dtiText = "DTI " + dti + "% is in the " + approveDti + "-" + conditionalDti + "% band, which needs " + conditionalReserves
                    + " months of reserves; the borrower has " + reservesMonths;
        else dtiText = "DTI " + dti + "% is above " + (conditionalDti != null ? conditionalDti : approveDti) + "%";
        return scoreText + "; " + dtiText;
    }
}
