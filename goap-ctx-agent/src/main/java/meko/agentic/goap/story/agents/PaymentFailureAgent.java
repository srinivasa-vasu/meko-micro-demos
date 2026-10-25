package meko.agentic.goap.story.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.annotation.Provided;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PlannerType;
import meko.agentic.goap.story.StoryLedger;
import meko.agentic.goap.story.store.ContextStore;
import meko.agentic.goap.story.store.StoreRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;

/**
 * Story 5: a payment failure that the organization may have solved before. If a learned pattern exists
 * in shared knowledge, the planner validates it cheaply; otherwise it must test every hypothesis. A
 * resolution reached the hard way is written as a memory and promoted into the shared knowledge base,
 * so the next case, handled by a different agent process, starts from it.
 */
@Agent(planner = PlannerType.GOAP, description = "Payments investigator for a recurring card-payment failure")
public class PaymentFailureAgent {
    /** The APAC payments desk's investigator and curator; the EU payments desk's application writes under its own ids. */
    public static final String AGENT_ID = "apac-payments-ops:investigator-agent";
    public static final String CURATOR_ID = "apac-payments-ops:knowledge-curator";
    public static final String EU_AGENT_ID = "eu-payments-ops:investigator-agent";
    public static final String EU_CURATOR_ID = "eu-payments-ops:knowledge-curator";
    /** The two desks, as people read them: the one that sees the failure first, and the one that sees it next. */
    public static final String EU_DESK = "EU desk";
    public static final String APAC_DESK = "APAC desk";

    /** Who investigates for {@code team} ("" for the APAC desk, this application). */
    static String investigator(String team) {
        return team == null || team.isBlank() ? AGENT_ID : EU_AGENT_ID;
    }

    static String curator(String team) {
        return team == null || team.isBlank() ? CURATOR_ID : EU_CURATOR_ID;
    }
    public static final String PATTERN_MARKER = "LEARNED PATTERN gateway-capture-timeout";
    static final String CAPTURE_TIMEOUT = "gateway capture timeout after successful authorization";
    static final String DUPLICATE_CAPTURE = "duplicate capture settled twice";
    static final String DELAYED_CAPTURE = "capture delayed at the acquirer";
    static final String UNDETERMINED = "undetermined";
    /** The gate that judges whether a retrieved pattern fits the case. */
    static final String PATTERN_FIT = "payments-pattern-fit";

    /**
     * @param epoch learning epoch. Meko's MCP cannot currently remove promoted knowledge, so "reset learning"
     *              starts a new epoch and patterns learned in earlier epochs are ignored.
     */
    /**
     * @param team  the team whose agent handles the case ("" for the APAC desk, this application)
     * @param share whether what the case teaches is promoted for others; off for a baseline run (the workshop's case
     *              without the lesson), so the only lesson in the datapack is the one the EU desk promotes
     */
    public record FailureCase(String caseId, ContextStore.Kind store, String customer, String transaction, String epoch,
                              boolean duplicateSettled, String team, boolean share) {
        public FailureCase(String caseId, ContextStore.Kind store, String customer, String transaction, String epoch,
                           boolean duplicateSettled, String team) {
            this(caseId, store, customer, transaction, epoch, duplicateSettled, team, true);
        }

        public FailureCase(String caseId, ContextStore.Kind store, String customer, String transaction, String epoch) {
            this(caseId, store, customer, transaction, epoch, false, "");
        }
    }

    /**
     * What the payments systems report for the failed payment. Simulated from the case's scenario, the same in every lane.
     *
     * @param duplicateSettled the trust scenario: a case that looks similar but where the learned pattern does not apply
     * @param captureTimedOut  the gateway logged a capture timeout
     * @param captureQueued    a capture is still queued at the acquirer
     * @param settledCaptures  how many captures settled
     */
    public record Signals(String caseId, ContextStore.Kind store, String customer, String transaction, String epoch,
                          boolean duplicateSettled, String description, String team,
                          boolean captureTimedOut, boolean captureQueued, int settledCaptures, boolean share) {
        public Signals(String caseId, ContextStore.Kind store, String customer, String transaction, String epoch,
                       boolean duplicateSettled, String description, String team,
                       boolean captureTimedOut, boolean captureQueued, int settledCaptures) {
            this(caseId, store, customer, transaction, epoch, duplicateSettled, description, team, captureTimedOut, captureQueued, settledCaptures, true);
        }
    }

    /** The model's judgement of whether the retrieved pattern fits this case. */
    public record PatternFit(Boolean applies, String reason) {}

    /** @param rejectedReason set when a pattern was retrieved but judged not to fit this case */
    public record PatternLookup(String caseId, boolean known, String pattern, String writtenBy, String rejectedReason) {}

    /**
     * @param rootCause null when validating the learned pattern failed and the case still needs investigating
     * @param tested    each hypothesis tested and its verdict, in order
     * @param evidence  what the diagnosis rests on
     */
    public record Diagnosis(String caseId, String rootCause, boolean fromPattern, List<String> tested, String evidence) {}

    public record Resolution(String caseId, String rootCause, String outcome, boolean fromPattern) {}

    public record CaseClosed(String caseId, String outcome, boolean learningPublished) {}

    private final StorySupport support;
    private final DecisionService decisions;

    public PaymentFailureAgent(StorySupport support, DecisionService decisions) {
        this.support = support;
        this.decisions = decisions;
    }

    @Action(description = "Gather gateway, ledger and settlement signals for the failed payment", post = {"signalsGathered"},
            cost = 0.03, canRerun = false)
    public Signals gatherSignals(FailureCase failure) {
        // The simulated payments systems' answer for this scenario.
        boolean timedOut = !failure.duplicateSettled();
        int settled = failure.duplicateSettled() ? 2 : 0;
        String description = "authorization accepted, "
                + (timedOut ? "capture timed out at the gateway" : "capture settled")
                + (settled > 1 ? ", and a second capture for the same amount also settled two minutes later" : "")
                + (settled == 0 ? ", no settlement record" : "");
        support.ledger(failure.caseId()).step(persona(failure.team()), "gatherSignals",
                failure.customer() + " / " + failure.transaction() + ": " + description + ".", support.effort("payment-failure", "gather-signals"), StoryLedger.Kind.WORK);
        Signals signals = new Signals(failure.caseId(), failure.store(), failure.customer(), failure.transaction(), failure.epoch(),
                failure.duplicateSettled(), description, failure.team(), timedOut, false, settled, failure.share());
        note(signals, SIGNALS, "Gather gateway, ledger and settlement signals for " + failure.transaction(), description);
        return signals;
    }

    /** Event names of the case's record (see {@link ContextStore.Turn#event}). */
    public static final String SIGNALS = "signals gathered";
    public static final String LOOKUP = "learned pattern looked up";
    public static final String VALIDATED = "learned pattern validated";
    public static final String HYPOTHESIS = "hypothesis tested";
    public static final String RESOLUTION = "resolution";

    /** Writes one of the investigator's records to the case's conversation, keyed by the case id. */
    private void note(Signals signals, String event, String input, String output) {
        support.recordEvent(support.store(signals.store()), signals.caseId(), investigator(signals.team()), signals.caseId(), event, null, input, output);
    }

    @Action(description = "Look for a learned pattern matching these signals in shared knowledge", pre = {"signalsGathered"},
            post = {"lookupDone", "patternKnown", "patternUnknown"}, cost = 0.02, canRerun = false)
    public PatternLookup lookUpKnownPatterns(Signals signals, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(signals.caseId());
        List<StoreRecord> matches = support.store(signals.store())
                .searchKnowledge(signals.caseId(), investigator(signals.team()), PATTERN_MARKER + " " + epochTag(signals.epoch()) + " capture timeout after successful authorization")
                .stream().filter(r -> r.text().contains(PATTERN_MARKER) && r.text().contains(epochTag(signals.epoch()))).toList();
        if (matches.isEmpty()) {
            ledger.step(persona(signals.team()), "lookUpKnownPatterns", "No learned pattern for these signals.", support.effort("payment-failure", "look-up-known-patterns"), StoryLedger.Kind.CONTEXT);
            note(signals, LOOKUP, LOOKUP_ASK, "no learned pattern in reach");
            return new PatternLookup(signals.caseId(), false, null, null, null);
        }
        StoreRecord match = matches.getFirst();
        // Retrieval is not reuse: the agent checks the pattern actually fits this case before relying on it.
        // The signals are structured and the pattern states its conditions: whether it fits is an exact check.
        PatternFit fit = decisions.decideByRule(signals.caseId(),
                persona(signals.team()),
                "decisions/" + PATTERN_FIT,
                Map.of(),
                "Case signals: " + signals.description() + ".\n" + StorySupport.asContext("Learned pattern from shared knowledge", List.of(match)),
                () -> misfit(signals, match.text()).map(why -> new PatternFit(false, why))
                        .orElse(new PatternFit(true, "Signals match every condition the pattern states.")),
                "the conditions the pattern's text states, checked against this case's signals");
        support.traceDecisions(support.store(signals.store()), signals.caseId(), investigator(signals.team()), signals.caseId(), persona(signals.team()));
        if (!Boolean.TRUE.equals(fit.applies())) {
            ledger.step(persona(signals.team()), "lookUpKnownPatterns",
                    "Meko returned a learned pattern, but it does not fit this case: " + fit.reason(), support.effort("payment-failure", "look-up-known-patterns"), StoryLedger.Kind.CONTEXT);
            ledger.count("patternsRejected", 1);
            support.passedOver(ledger, "knowledge base", match, "a learned pattern that does not fit this case: " + fit.reason());
            note(signals, LOOKUP, LOOKUP_ASK, "a pattern published by " + match.writtenBy() + ", rejected: " + fit.reason());
            return new PatternLookup(signals.caseId(), false, null, match.writtenBy(), fit.reason());
        }
        support.found(ledger, "knowledge base", List.of(match));
        ledger.step(persona(signals.team()), "lookUpKnownPatterns",
                "Found a learned pattern in shared knowledge, published by " + match.writtenBy() + ", and it fits: " + fit.reason(), support.effort("payment-failure", "look-up-known-patterns"), StoryLedger.Kind.CONTEXT);
        note(signals, LOOKUP, LOOKUP_ASK, "a pattern published by " + match.writtenBy() + ", judged to fit: " + fit.reason());
        return new PatternLookup(signals.caseId(), true, match.text(), match.writtenBy(), null);
    }

    /**
     * Why the learned pattern does not fit this case, from the conditions its own text states; empty if it fits. A pattern
     * that states no condition this agent can check does not fit: retrieval is not reuse.
     */
    static Optional<String> misfit(Signals signals, String patternText) {
        String text = patternText == null ? "" : patternText.toLowerCase();
        boolean needsTimeout = text.matches("(?s).*capture (times|timed) out.*");
        boolean needsNothingSettled = text.matches("(?s).*(no settlement exists|no capture is settled|no settled capture|nothing settled).*");
        if (!needsTimeout && !needsNothingSettled) return Optional.of("the pattern states no condition this case's signals can be checked against.");
        List<String> failed = new ArrayList<>();
        if (needsTimeout && !signals.captureTimedOut()) failed.add("it requires a capture timeout; none was logged");
        if (needsNothingSettled && signals.settledCaptures() > 0) failed.add("it requires no settled capture; here " + signals.settledCaptures() + " settled");
        return failed.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", failed) + ".");
    }

    /** The root cause a learned pattern names, from its id ("LEARNED PATTERN gateway-capture-timeout"). */
    static String rootCauseOf(String patternText) {
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("LEARNED PATTERN ([a-z0-9-]+)").matcher(patternText == null ? "" : patternText);
        String slug = id.find() ? id.group(1) : "";
        return slug.contains("capture-timeout") ? CAPTURE_TIMEOUT : slug.contains("duplicate") ? DUPLICATE_CAPTURE
                : slug.contains("delayed") ? DELAYED_CAPTURE : UNDETERMINED;
    }

    @Action(description = "Validate the learned pattern against this case's signals", pre = {"patternKnown"},
            post = {"diagnosed", "validationFailed"}, cost = 0.08, canRerun = false)
    public Diagnosis validateKnownPattern(Signals signals, PatternLookup lookup) {
        String evidence = "gateway capture timeout " + (signals.captureTimedOut() ? "logged" : "not logged")
                + ", " + signals.settledCaptures() + " settled capture" + (signals.settledCaptures() == 1 ? "" : "s");
        // Validation re-checks the pattern's stated conditions on the evidence just gathered; the diagnosis is the cause it names.
        boolean holds = misfit(signals, lookup.pattern()).isEmpty() && !UNDETERMINED.equals(rootCauseOf(lookup.pattern()));
        support.ledger(signals.caseId()).step(persona(signals.team()), "validateKnownPattern",
                (holds ? "Validated the learned pattern: " : "The learned pattern did not hold on validation: ") + evidence + ".",
                support.effort("payment-failure", "validate-known-pattern"), StoryLedger.Kind.WORK);
        note(signals, VALIDATED, "Validate the learned pattern against this case's signals", (holds ? "holds: " : "does not hold: ") + evidence);
        return new Diagnosis(signals.caseId(), holds ? rootCauseOf(lookup.pattern()) : null, true, List.of(), evidence);
    }

    @Action(description = "Test each hypothesis from scratch", pre = {"patternUnknown"}, post = {"diagnosed"},
            cost = 0.60, canRerun = false)
    public Diagnosis testHypotheses(Signals signals) {
        return investigate(signals);
    }

    @Action(description = "Test each hypothesis after the learned pattern failed validation", pre = {"validationFailed"},
            post = {"diagnosed"}, cost = 0.60, canRerun = false)
    public Diagnosis investigateAfterFailedValidation(Signals signals, Diagnosis failed) {
        return investigate(signals);
    }

    /** Tests the hypotheses in order against the case's signals, stopping at the first one confirmed. */
    private Diagnosis investigate(Signals signals) {
        StoryLedger ledger = support.ledger(signals.caseId());
        record Hypothesis(String name, String estimate, String rootCause, boolean confirmed, String evidence) {}
        List<Hypothesis> hypotheses = List.of(
                new Hypothesis("duplicate charge", "hypothesis-duplicate-charge", DUPLICATE_CAPTURE, signals.settledCaptures() > 1,
                        signals.settledCaptures() > 1 ? signals.settledCaptures() + " captures settled two minutes apart"
                                : "authorization and capture ids differ; " + signals.settledCaptures() + " settled capture" + (signals.settledCaptures() == 1 ? "" : "s")),
                new Hypothesis("delayed capture", "hypothesis-delayed-capture", DELAYED_CAPTURE, signals.captureQueued(),
                        signals.captureQueued() ? "a capture is queued at the acquirer" : "no capture queued at the acquirer"),
                new Hypothesis("gateway capture timeout", "hypothesis-capture-timeout", CAPTURE_TIMEOUT, signals.captureTimedOut(),
                        signals.captureTimedOut() ? "timeout found in the gateway logs" : "no timeout in the gateway logs"));
        List<String> tested = new ArrayList<>();
        for (Hypothesis h : hypotheses) {
            String verdict = h.name() + " " + (h.confirmed() ? "confirmed" : "rejected") + " (" + h.evidence() + ")";
            ledger.step(persona(signals.team()), "testHypotheses", "Hypothesis " + (tested.size() + 1) + ", " + verdict + ".",
                    support.effort("payment-failure", h.estimate()), StoryLedger.Kind.WORK);
            ledger.count("hypothesesTested", 1);
            tested.add(verdict);
            note(signals, HYPOTHESIS, "Test the hypothesis: " + h.name(), verdict);
            if (h.confirmed()) return new Diagnosis(signals.caseId(), h.rootCause(), false, List.copyOf(tested), h.evidence());
        }
        return new Diagnosis(signals.caseId(), UNDETERMINED, false, List.copyOf(tested), "no hypothesis confirmed");
    }

    @Action(description = "Apply the resolution for the diagnosed root cause", pre = {"diagnosed"}, post = {"resolved"},
            cost = 0.05, canRerun = false)
    public Resolution resolve(Signals signals, Diagnosis diagnosis) {
        String outcome = (switch (diagnosis.rootCause()) {
            case DUPLICATE_CAPTURE -> "Duplicate capture refunded";
            case DELAYED_CAPTURE -> "Customer told the capture is still queued; no refund needed";
            case CAPTURE_TIMEOUT -> "Provisional refund issued and capture voided";
            default -> "Escalated to payments operations";
        }) + "; root cause: " + diagnosis.rootCause() + ".";
        support.ledger(diagnosis.caseId()).count(UNDETERMINED.equals(diagnosis.rootCause()) ? "handedToPerson" : "decided", 1);
        support.ledger(diagnosis.caseId()).step(persona(signals.team()), "resolve", outcome, support.effort("payment-failure", "resolve"), StoryLedger.Kind.WORK);
        note(signals, RESOLUTION, "Resolve the failed payment " + signals.transaction(), outcome);
        return new Resolution(diagnosis.caseId(), diagnosis.rootCause(), outcome, diagnosis.fromPattern());
    }

    @AchievesGoal(description = "The case is closed and anything learned is shared")
    @Action(description = "Close the case and publish what was learned to shared knowledge", pre = {"resolved"},
            post = {"caseClosed"}, cost = 0.03, canRerun = false)
    public CaseClosed closeAndShare(Signals signals, PatternLookup lookup, Diagnosis diagnosis, Resolution resolution) {
        StoryLedger ledger = support.ledger(signals.caseId());
        ContextStore store = support.store(signals.store());
        boolean published = false;
        if (!resolution.fromPattern() && !CAPTURE_TIMEOUT.equals(resolution.rootCause())) {
            ledger.step(persona(signals.team()), "closeAndShare", "Closed the case. A one-off " + resolution.rootCause() + ": no new pattern published.", support.effort("payment-failure", "close-case"), StoryLedger.Kind.WORK);
        } else if (!resolution.fromPattern() && !signals.share()) {
            ledger.step(persona(signals.team()), "closeAndShare", "Closed the case. A baseline run: what it found is not promoted, so the only lesson in reach later is another desk's.",
                    support.effort("payment-failure", "close-case"), StoryLedger.Kind.WORK);
        } else if (!resolution.fromPattern() && store.kind() != ContextStore.Kind.NONE) {
            String learning = PATTERN_MARKER + ": when card authorization succeeds but the gateway capture times out and no settlement exists, "
                    + "it is not a duplicate charge or a delayed capture. Validate that capture identifiers differ and no capture is settled, "
                    + "then issue a provisional refund and void the capture. Learned on case " + signals.caseId() + " " + epochTag(signals.epoch()) + ".";
            published = store.remember(signals.caseId(), curator(signals.team()), learning)
                    .map(id -> store.promote(signals.caseId(), curator(signals.team()), List.of(id)))
                    .orElse(false);
            ledger.step("Knowledge curator", "closeAndShare",
                    published ? "Wrote the root cause as a memory and promoted it into the shared knowledge base."
                            : "Could not publish the learning to shared knowledge.", support.effort("payment-failure", "publish-learning"), StoryLedger.Kind.CONTEXT);
        } else if (!resolution.fromPattern()) {
            ledger.step(persona(signals.team()), "closeAndShare", "Closed the case. What was learned stays with this run.", support.effort("payment-failure", "close-case"), StoryLedger.Kind.WORK);
        } else {
            ledger.step(persona(signals.team()), "closeAndShare", "Closed the case using the organization's existing knowledge.", support.effort("payment-failure", "close-case"), StoryLedger.Kind.WORK);
        }
        ledger.outcome(resolution.outcome());
        List<String> facts = new ArrayList<>(List.of(
                signals.customer() + " / " + signals.transaction() + ": " + signals.description(),
                "Resolution: " + resolution.outcome()));
        // Everything below is what this run actually did, read back from its own records.
        // How the reuse decision was made, from the decision recorded at the gate.
        ledger.decisions().stream().filter(d -> d.gate().equals(PATTERN_FIT)).forEach(d ->
                facts.add("Pattern-fit decision: " + d.answer() + " (" + d.how() + ")"));
        if (lookup.rejectedReason() != null) {
            facts.add("A learned pattern published by " + lookup.writtenBy() + " was retrieved but rejected: " + lookup.rejectedReason());
        }
        if (resolution.fromPattern()) {
            facts.add("Diagnosis came from the organization's learned pattern (published by " + lookup.writtenBy()
                    + "), validated against this case: " + diagnosis.evidence());
        } else {
            facts.add("Diagnosis came from testing " + diagnosis.tested().size() + " hypothes" + (diagnosis.tested().size() == 1 ? "is" : "es")
                    + ": " + String.join("; ", diagnosis.tested()));
        }
        boolean nothingRetrieved = !lookup.known() && lookup.rejectedReason() == null;
        List<String> gaps = nothingRetrieved ? List.of("No earlier resolution of this failure was available to compare against") : List.of();
        ledger.handToWriter(new ResponseWriterAgent.WritingTask(signals.caseId(), persona(signals.team()),
                "resolution note for the payments operations log",
                support.render("writer/payments-resolution-note", Map.of()),
                facts, gaps, resolution.outcome()));
        // The case's result goes back to memory: the evidence of how often a lesson was reused, rejected or rediscovered.
        String how = resolution.fromPattern()
                ? "diagnosed by reusing the learned pattern published by " + lookup.writtenBy() + ", validated against this case (" + diagnosis.evidence() + ")"
                : (lookup.rejectedReason() != null ? "a learned pattern published by " + lookup.writtenBy() + " was rejected (" + lookup.rejectedReason() + "); " : "")
                + "diagnosed by testing " + diagnosis.tested().size() + " hypothes" + (diagnosis.tested().size() == 1 ? "is" : "es");
        store.rememberLater(signals.caseId(), investigator(signals.team()), "Payment case " + signals.caseId() + " (" + signals.transaction()
                + ") closed " + java.time.LocalDate.now() + ": root cause " + resolution.rootCause() + "; " + how + ".");
        return new CaseClosed(signals.caseId(), resolution.outcome(), published);
    }

    private static final String LOOKUP_ASK = "Look for a learned pattern matching these signals in shared knowledge";

    static String persona(String team) {
        return "Payments investigator (" + (team == null || team.isBlank() ? APAC_DESK : team) + ")";
    }

    static String epochTag(String epoch) {
        return "[learning epoch " + epoch + "]";
    }

    @Condition(name = "signalsGathered")
    public boolean signalsGathered(Signals signals) {
        return signals != null;
    }

    @Condition(name = "lookupDone")
    public boolean lookupDone(PatternLookup lookup) {
        return lookup != null;
    }

    @Condition(name = "patternKnown")
    public boolean patternKnown(PatternLookup lookup) {
        return lookup != null && lookup.known();
    }

    @Condition(name = "patternUnknown")
    public boolean patternUnknown(PatternLookup lookup) {
        return lookup != null && !lookup.known();
    }

    @Condition(name = "diagnosed")
    public boolean diagnosed(Diagnosis diagnosis) {
        return diagnosis != null && diagnosis.rootCause() != null;
    }

    @Condition(name = "validationFailed")
    public boolean validationFailed(Diagnosis diagnosis) {
        return diagnosis != null && diagnosis.rootCause() == null;
    }

    @Condition(name = "resolved")
    public boolean resolved(Resolution resolution) {
        return resolution != null;
    }

    @Condition(name = "caseClosed")
    public boolean caseClosed(CaseClosed closed) {
        return closed != null;
    }
}
