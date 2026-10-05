package meko.agentic.sre.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import meko.agentic.sre.domain.ContextClaim;
import meko.agentic.sre.domain.IncidentReport;
import meko.agentic.sre.domain.ReuseMode;
import meko.agentic.sre.domain.StoredIncident;
import org.springframework.stereotype.Component;

/**
 * Context claims for an incident nobody wrote claims for.
 * <p>
 * A scenario's claims are written in advance by someone who knows the right answer. A custom
 * incident has no such author, so the claims come from the one thing that is known after the
 * run: the stored record the warm run actually matched. Each step of that record's remediation
 * becomes a claim, checked against both answers.
 * <p>
 * DETERMINISTIC AND FREE, deliberately. A model judging model output would cost tokens on a
 * demo whose point is an auditable bill, and would grade differently on every run.
 * <p>
 * WHAT IT CAN AND CANNOT SAY. It only knows memory records: runbook prose does not split into
 * checkable steps. And on a short circuit the warm answer IS the stored record, so the warm side
 * passes by construction — the information is in the other column, which shows what the run
 * without memory did not produce. {@link Derived#servedFromMemory} lets the page say so.
 * <p>
 * Presence is keyword overlap, not phrase matching, because phrasing was the failure mode of
 * the hand-written claims: "restore the 5s upstream timeout" and "roll back to the previous 5s
 * value" are the same fix and must count as the same fix.
 */
@Component
public class ClaimDeriver {

    /**
     * Ways a model offers to fix forward by raising the timeout. Shared with the scenarios so a
     * derived claim and a hand-written one cannot disagree about what counts.
     * <p>
     * Phrase matching is brittle, and this list is where that shows: an observed cold answer
     * read "increase the payment client timeout back to >=5s", which matched none of the
     * earlier entries — not "increase the timeout", and not "&gt;5s" either, because of the "=".
     */
    public static final List<String> FIX_FORWARD = List.of(
            "increase the timeout", "increase the current timeout", "increase the upstream timeout",
            "increase the payment client timeout", "increase the client timeout",
            "raise the timeout", "raising the timeout", "raise the upstream timeout",
            "increasing the timeout", ">5s", ">=5s", "≥5s", "or increase");

    /** A step counts as present when at least this share of its key words appear. */
    private static final double PRESENT_AT = 0.5;
    /** ...and never on fewer than this many words, so a one-word overlap cannot pass a step. */
    private static final int MIN_WORDS = 2;

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "to", "of", "on", "in", "at", "for", "and", "or", "then", "after",
            "with", "is", "be", "it", "its", "by", "from", "as", "this", "that", "any", "all");

    /**
     * Verbs that say HOW a step is done rather than WHAT it is. "Force a commit or flush" and
     * "invoke a manual commit or flush" are the same step; anchoring on the first word made them
     * different ones, and a run that took the recorded step scored "neither run".
     */
    private static final Set<String> LIGHT_VERBS = Set.of(
            "force", "invoke", "trigger", "perform", "run", "apply", "do", "execute", "issue",
            "make", "ensure", "add", "manually", "manual", "immediately", "first", "please");

    /** Where a remediation turns from one step to the next. */
    private static final Pattern STEPS = Pattern.compile("(?i)\\s*(?:;|,\\s*then\\s+|\\bthen\\b|,\\s*and\\s+|\\band\\s+(?=[a-z]+\\s))\\s*");

    public record Derived(List<ContextClaim.Result> claims, String fromIncident, boolean servedFromMemory) {
        public static Derived none() {
            return new Derived(List.of(), "", false);
        }
    }

    /**
     * @param withContext the run that had memory
     * @param baseline    the run to compare against: no memory at all
     */
    public Derived derive(IncidentReport withContext, IncidentReport baseline) {
        if (withContext == null || baseline == null || withContext.reuseMode() == ReuseMode.FULL) {
            return Derived.none();
        }
        var record = matched(withContext);
        if (record == null || record.remediation() == null || record.remediation().isBlank()) {
            return Derived.none();
        }

        // THE ACTION ONLY. These are remediation steps, so they are checked against what each
        // answer says to DO. Checking the root cause as well counted a run that named "segment
        // commits" as the symptom as having taken the step "force a commit".
        var warm = actionOf(withContext);
        var cold = actionOf(baseline);
        var source = "learned memory (" + record.incidentId() + ")";
        var out = new ArrayList<ContextClaim.Result>();

        for (var step : steps(record.remediation())) {
            if (keywords(step).size() < MIN_WORDS) {
                continue;
            }
            var claim = new ContextClaim(capitalise(step), List.of(step), source);
            out.add(new ContextClaim.Result(claim, present(step, warm), present(step, cold)));
        }

        // The hedge: a recorded rollback against an answer that also offers to fix forward.
        // Only asked when the record IS a rollback, otherwise the question does not apply.
        if (keywords(record.remediation()).contains("revert")) {
            var claim = new ContextClaim(
                    "Commits to the recorded rollback instead of offering to raise the timeout as "
                            + "an alternative",
                    List.of("roll back", "rollback"), FIX_FORWARD, source);
            out.add(new ContextClaim.Result(claim, claim.presentIn(warm), claim.presentIn(cold)));
        }

        return new Derived(List.copyOf(out), record.incidentId(),
                withContext.reuseMode() == ReuseMode.SHORT_CIRCUIT);
    }

    /** The step's action: its first word that is not a light verb (see LIGHT_VERBS). */
    private static String anchor(Set<String> keywords) {
        return keywords.stream()
                .filter(k -> !LIGHT_VERBS.contains(k))
                .findFirst()
                .orElse(keywords.iterator().next());
    }

    private StoredIncident matched(IncidentReport report) {
        var id = report.matchedIncidentId();
        if (id == null || id.isBlank() || report.recalled() == null) {
            return null;
        }
        return report.recalled().stream()
                .filter(r -> id.equalsIgnoreCase(r.incidentId()))
                .findFirst()
                .orElse(null);
    }

    List<String> steps(String remediation) {
        return STEPS.splitAsStream(remediation.strip().replaceAll("[.]+$", ""))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * A step is present when its ACTION is — the first word, "alert", "revert" — and at least half
     * its words overall. Overlap alone is not enough: "alert on payment-api timeout rate after
     * deploys" shares timeout, rate and deploy with an answer that says "monitor error_rate" and
     * never alerts on anything. The words around an action are context; the action is the step.
     */
    boolean present(String step, String answer) {
        var want = keywords(step);
        if (want.size() < MIN_WORDS) {
            return false;
        }
        var have = keywords(answer);
        if (!have.contains(anchor(want))) {
            return false;
        }
        long hits = want.stream().filter(have::contains).count();
        return hits >= MIN_WORDS && hits >= Math.ceil(want.size() * PRESENT_AT);
    }

    /**
     * Lower-cased content words, with the variations that actually occur in these answers
     * folded together: "5 seconds"/"5s", plurals and -ing forms, and restore/revert/roll back,
     * which are one action — "roll back to the previous 5s value" restores the 5s timeout.
     */
    Set<String> keywords(String text) {
        if (text == null) {
            return Set.of();
        }
        var normalised = text.toLowerCase(Locale.ROOT)
                .replaceAll("\\broll(?:ed|ing)?[\\s-]+back\\b", "revert")
                .replaceAll("\\b(?:rollback|restor(?:e|ed|ing)|revert(?:ed|ing)?)\\b", "revert")
                .replaceAll("\\b(\\d+)\\s*(?:seconds?|secs?)\\b", "$1s")
                .replaceAll("\\b(\\d+)000\\s*ms\\b", "$1s");
        var out = new LinkedHashSet<String>();
        for (var raw : normalised.split("[^a-z0-9.\\-]+")) {
            var word = raw.replaceAll("^[.\\-]+|[.\\-]+$", "");
            if (word.length() < 2 || STOPWORDS.contains(word)) {
                continue;
            }
            out.add(stem(word));
        }
        return out;
    }

    private String stem(String word) {
        if (word.length() > 6 && word.endsWith("ing")) {
            return word.substring(0, word.length() - 3);
        }
        if (word.length() > 4 && word.endsWith("s") && !word.endsWith("ss")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private static String actionOf(IncidentReport report) {
        return report.recommendedAction() == null ? "" : report.recommendedAction();
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
