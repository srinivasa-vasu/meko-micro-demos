package meko.agentic.goap.story;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The conditions an underwriting policy sets on an application, read from the policy's own text: nothing here knows
 * any policy's numbers. A deposit above a share of monthly income must be sourced; an employment gap above a number of
 * days needs an explanation.
 *
 * @param depositPercent "a single deposit larger than N% of monthly income"
 * @param gapDays        "an employment gap longer than N days"
 */
public record LendingConditions(int depositPercent, int gapDays, List<String> depositEvidence, List<String> gapEvidence) {

    private static final Pattern DEPOSIT = Pattern.compile("deposit larger than (\\d+)% of monthly income", Pattern.CASE_INSENSITIVE);
    private static final Pattern GAP = Pattern.compile("employment gap longer than (\\d+) days", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEPOSIT_EVIDENCE = Pattern.compile("Acceptable evidence of a deposit's source: ([^.]+)\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern GAP_EVIDENCE = Pattern.compile("Acceptable evidence for an employment gap: ([^.]+)\\.", Pattern.CASE_INSENSITIVE);

    public static Optional<LendingConditions> parse(String policyText) {
        if (policyText == null) return Optional.empty();
        Matcher deposit = DEPOSIT.matcher(policyText), gap = GAP.matcher(policyText);
        if (!deposit.find() || !gap.find()) return Optional.empty();
        return Optional.of(new LendingConditions(Integer.parseInt(deposit.group(1)), Integer.parseInt(gap.group(1)),
                listed(DEPOSIT_EVIDENCE, policyText), listed(GAP_EVIDENCE, policyText)));
    }

    /** "a gift letter, or a statement of the account the money came from" → each item, without its article. */
    private static List<String> listed(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text.replaceAll("\\s+", " "));
        if (!m.find()) return List.of();
        return Arrays.stream(m.group(1).split(",\\s*(?:or\\s+)?|\\s+or\\s+"))
                .map(s -> s.trim().replaceFirst("^(a|an|the)\\s+", "").toLowerCase()).filter(s -> !s.isEmpty()).toList();
    }

    /** Whether the policy lists this evidence as acceptable for the flagged item ("deposit" or "employment gap"). */
    public boolean listsEvidence(String flagId, String evidence) {
        String e = evidence == null ? "" : evidence.toLowerCase();
        return (flagId.equals("deposit") ? depositEvidence : gapEvidence).stream().anyMatch(e::contains);
    }

    /** Whether a deposit needs sourcing at this income. */
    public boolean depositNeedsSource(int depositUsd, int monthlyIncome) {
        return depositUsd * 100.0 / monthlyIncome > depositPercent;
    }

    public boolean gapNeedsExplanation(int gapDays) {
        return gapDays > this.gapDays;
    }
}
