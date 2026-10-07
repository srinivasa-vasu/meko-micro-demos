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
import java.util.Map;

/**
 * Story 4: a developer returns from two weeks away to PR-4821. While they were out, other tools wrote to
 * the shared datapack: a Claude Code pairing session, the PR review bot, the incident bot. The catch-up
 * agent reads what those agents wrote; the planner then schedules only the work that context makes
 * necessary. Without it, the developer reconstructs history by hand and misses what lives only in chat.
 */
@Agent(planner = PlannerType.GOAP, description = "Coding-assistant agent that catches a returning developer up on a pull request")
public class CatchUpAgent {
    public static final String AGENT_ID = "sdlc:catch-up-agent";

    /** @param round the story's round: write-backs from earlier rounds are set aside */
    public record CatchUpRequest(String caseId, ContextStore.Kind store, String round) {}

    public record DiffSummary(String caseId, ContextStore.Kind store, String round, List<String> files) {}

    /** The pull request the developer returns to (the scenario). */
    public static final String PR = "PR-4821";
    /** How the PR review bot's record states the open request, and how this agent's write-back states it was addressed. */
    static final String REQUEST_MARKER = "reviewer requested";
    public static final String ADDRESSED_MARKER = "was addressed by";
    /** Event names of the PR case's record (see {@link ContextStore.Turn#event}). */
    public static final String CHECKED = "architecture checked";
    public static final String TESTS = "tests decided";
    public static final String REVIEW = "review requested";

    public record ProjectContext(String caseId, boolean recalled, boolean adrRelevant, String openReviewAsk,
                                 String incidentLesson, List<StoreRecord> items) {}

    public record TestsDecision(String caseId, boolean idempotencyTestAdded) {}

    public record ArchitectureChecked(String caseId) {}

    public record ReviewReady(String caseId, String summary) {}

    /** The model's reading of the project context. */
    public record ContextReading(Boolean adrRelevant, String openReviewRequest, String incidentLesson) {}

    /** What a developer can search by hand: the wiki is indexed; team chat is not. */
    static final String WIKI_ADR = "Wiki page ADR-194: payment retries must be idempotent and keyed by the original authorization id; "
            + "any change to the retry contract requires architecture review.";


    private final StorySupport support;
    private final DecisionService decisions;
    private final meko.agentic.goap.story.PullRequests pullRequests;

    public CatchUpAgent(StorySupport support, DecisionService decisions, meko.agentic.goap.story.PullRequests pullRequests) {
        this.support = support;
        this.decisions = decisions;
        this.pullRequests = pullRequests;
    }

    /** Writes one of the agent's records to the PR case's conversation, keyed by the case id. */
    private void note(DiffSummary diff, String event, String input, String output) {
        support.recordEvent(support.store(diff.store()), diff.caseId(), AGENT_ID, diff.caseId(), event, null, input, output);
    }

    @Action(description = "Read the current diff of the pull request", post = {"diffRead"}, cost = 0.03, canRerun = false)
    public DiffSummary readDiff(CatchUpRequest request) {
        List<String> files = List.of("RetryPolicy.java", "PaymentGatewayClient.java", "RetryPolicyTest.java");
        support.ledger(request.caseId()).step("Catch-up agent", "readDiff",
                PR + " changes the payment retry contract: " + String.join(", ", files) + ".", support.effort("catch-up", "read-diff"), StoryLedger.Kind.WORK);
        return new DiffSummary(request.caseId(), request.store(), request.round(), files);
    }

    @Action(description = "Recall decisions, review requests and incidents related to this change", pre = {"diffRead"},
            post = {"contextRecalled", "contextMissing", "adrRelevant", "adrNotRelevant", "reviewAskOpen", "noKnownAsks"},
            cost = 0.02, canRerun = false)
    public ProjectContext recallProjectContext(DiffSummary diff, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(diff.caseId());
        ContextStore store = support.store(diff.store());
        // The two searches do not depend on each other: run them together.
        var knowledge = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                store.searchKnowledge(diff.caseId(), AGENT_ID, "ADR-194 payment retry idempotency authorization id"));
        List<StoreRecord> items = new ArrayList<>(store.searchMemory(diff.caseId(), AGENT_ID, PR + " payment retry decision review request incident INC-1877"));
        // This agent's own write-backs from an earlier round of the story are set aside: each round starts from the PR as it was.
        items.removeIf(r -> {
            boolean earlier = AGENT_ID.equals(r.writtenBy()) && r.text().contains(roundTag("")) && !r.text().contains(roundTag(diff.round()));
            if (earlier) support.passedOver(ledger, "memory", r, "written in an earlier round of this story");
            return earlier;
        });
        items.addAll(knowledge.join());
        List<StoreRecord> relevant = items.stream()
                .filter(r -> r.text().contains(PR) || r.text().contains("INC-1877") || r.text().contains("ADR-194"))
                .toList();
        // Everything relevant goes to the gate; the brief and the "used" list leave out what was superseded, and
        // requests on other pull requests are named as passed over.
        List<StoreRecord> current = relevant.stream().filter(r -> !r.text().toLowerCase().contains("superseded")).toList();
        support.found(ledger, "memory and knowledge", current);
        relevant.stream().filter(r -> !current.contains(r)).forEach(r ->
                support.passedOver(ledger, "memory", r, "superseded: an early draft of the retry decision, replaced by ADR-194"));
        items.stream().filter(r -> !relevant.contains(r)).forEach(r -> {
            String pr = StorySupport.extract(List.of(r), "(PR-\\d+)").orElse(null);
            if (pr != null) support.passedOver(ledger, "memory", r, "a different pull request (" + pr + "), not the one you are finishing");
        });
        current.forEach(r -> ledger.note("Catch-up brief", r.text() + "  (" + r.writtenBy() + ")"));
        ContextReading reading = relevant.isEmpty() ? new ContextReading(false, null, null)
                : decisions.decide(diff.caseId(),
                "Catch-up agent",
                "decisions/sdlc-context",
                Map.of("pr", PR),
                "Changed files: " + diff.files() + "\n" + StorySupport.asContext("Shared project context", relevant),
                ContextReading.class,
                () -> readingByRule(relevant),
                ai);
        support.traceDecisions(store, diff.caseId(), AGENT_ID, diff.caseId(), "Catch-up agent");
        ProjectContext context = new ProjectContext(diff.caseId(), !relevant.isEmpty(), Boolean.TRUE.equals(reading.adrRelevant()),
                blankToNull(reading.openReviewRequest()), blankToNull(reading.incidentLesson()), relevant);
        ledger.step("Catch-up agent", "recallProjectContext",
                relevant.isEmpty() ? "No shared project context to recall."
                        : "Recalled " + current.size() + " current items written by " + current.stream().map(StoreRecord::writtenBy).distinct().toList()
                        + (relevant.size() > current.size() ? "; passed over " + (relevant.size() - current.size()) + " superseded" : "") + ".",
                support.effort("catch-up", "recall-project-context"), StoryLedger.Kind.CONTEXT);
        return context;
    }

    private static String roundTag(String round) {
        return "[round " + round;
    }

    private static ContextReading readingByRule(List<StoreRecord> rows) {
        // A request is open until a later record says it was addressed.
        boolean addressed = rows.stream().anyMatch(r -> r.text().contains(PR) && r.text().contains(ADDRESSED_MARKER));
        return new ContextReading(StorySupport.mentions(rows, "ADR-194"),
                addressed ? null : rows.stream().filter(r -> r.text().contains(PR) && r.text().contains(REQUEST_MARKER)).map(StoreRecord::text).findFirst().orElse(null),
                rows.stream().filter(r -> r.text().contains("INC-1877 postmortem")).map(StoreRecord::text).findFirst().orElse(null));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || value.equalsIgnoreCase("null") || value.equalsIgnoreCase("none") ? null : value;
    }

    @Action(description = "Reconstruct the last two weeks by reading PR threads, chat and the wiki", pre = {"contextMissing"},
            post = {"contextRecalled", "adrRelevant", "adrNotRelevant", "reviewAskOpen", "noKnownAsks"}, cost = 0.50, canRerun = false)
    public ProjectContext reconstructManually(DiffSummary diff, ProjectContext recalled, @Provided Ai ai) {
        // Takes the (empty) recalled context as input: Embabel only schedules an action that replaces an
        // object already on the blackboard when the action consumes it.
        StoryLedger ledger = support.ledger(diff.caseId());
        ledger.say("Developer", "@team what did I miss on " + PR + "? Anything decided while I was out?");
        ledger.say("Teammate", "There was an ADR about retries, it's on the wiki. Not sure about the review, ask the reviewer?");
        ledger.step("Developer", "reconstructManually",
                "Read PR threads, chat and the wiki and pinged two teammates.", support.effort("catch-up", "reconstruct-manually"), StoryLedger.Kind.ASK);
        ledger.count("teammateInterruptions", 2);
        // What a person can actually search: the wiki is indexed, the team chat where the review request lives is not.
        String sources = "Changed files: " + diff.files() + "\n"
                + "Wiki search for 'payment retry': " + WIKI_ADR + "\n"
                + "Team chat search: not available (chat is not indexed).\n"
                + "Teammate: there was an ADR about retries on the wiki; not sure about the review.";
        ContextReading reading = decisions.decide(diff.caseId(),
                "Developer",
                "decisions/sdlc-context",
                Map.of("pr", PR),
                sources,
                ContextReading.class,
                () -> new ContextReading(true, null, null),
                ai);
        support.traceDecisions(support.store(diff.store()), diff.caseId(), AGENT_ID, diff.caseId(), "Developer");
        return new ProjectContext(diff.caseId(), true, Boolean.TRUE.equals(reading.adrRelevant()),
                blankToNull(reading.openReviewRequest()), blankToNull(reading.incidentLesson()), List.of());
    }

    @Action(description = "Check the change against the relevant architecture decision", pre = {"adrRelevant"},
            post = {"architectureChecked"}, cost = 0.10, canRerun = false)
    public ArchitectureChecked architectureCheck(DiffSummary diff, ProjectContext context) {
        String result = "Checked retries are keyed by the original authorization id, as ADR-194 requires.";
        support.ledger(context.caseId()).step("Catch-up agent", "architectureCheck", result, support.effort("catch-up", "architecture-check"), StoryLedger.Kind.WORK);
        note(diff, CHECKED, "Check the change against ADR-194", result);
        return new ArchitectureChecked(context.caseId());
    }

    @Action(description = "Record that no architecture decision applies to this change", pre = {"adrNotRelevant"},
            post = {"architectureChecked"}, cost = 0.01, canRerun = false)
    public ArchitectureChecked noArchitectureCheckNeeded(ProjectContext context) {
        support.ledger(context.caseId()).step("Catch-up agent", "noArchitectureCheckNeeded",
                "No architecture decision found that applies to this change.", support.effort("catch-up", "no-architecture-check"), StoryLedger.Kind.WORK);
        return new ArchitectureChecked(context.caseId());
    }

    @Action(description = "Add the idempotency regression test the reviewer asked for", pre = {"architectureChecked", "reviewAskOpen"},
            post = {"testsDecided"}, cost = 0.15, canRerun = false)
    public TestsDecision addIdempotencyTest(DiffSummary diff, ProjectContext context) {
        support.ledger(context.caseId()).step("Catch-up agent", "addIdempotencyTest",
                "Added an idempotency regression test for retries. Reviewer's open request: " + context.openReviewAsk()
                        + (context.incidentLesson() != null ? " Incident lesson: " + context.incidentLesson() : ""),
                support.effort("catch-up", "add-idempotency-test"), StoryLedger.Kind.WORK);
        // The test is in the pull request now: the request is addressed in the PR system.
        pullRequests.address(diff.store(), PR);
        note(diff, TESTS, "Address the reviewer's open request on " + PR, "added an idempotency regression test for the retry path");
        return new TestsDecision(context.caseId(), true);
    }

    @Action(description = "Proceed with the existing tests", pre = {"architectureChecked", "noKnownAsks"},
            post = {"testsDecided"}, cost = 0.01, canRerun = false)
    public TestsDecision keepExistingTests(DiffSummary diff, ProjectContext context) {
        support.ledger(context.caseId()).step("Developer", "keepExistingTests",
                "No open review requests known: kept the existing tests.", support.effort("catch-up", "keep-existing-tests"), StoryLedger.Kind.WORK);
        note(diff, TESTS, "Decide the tests for " + PR, "kept the existing tests: no open review request known");
        return new TestsDecision(context.caseId(), false);
    }

    @AchievesGoal(description = "The pull request is ready for review")
    @Action(description = "Push the update and request review", pre = {"testsDecided"}, post = {"reviewReady"},
            cost = 0.03, canRerun = false)
    public ReviewReady requestReview(DiffSummary diff, ProjectContext context, TestsDecision tests) {
        StoryLedger ledger = support.ledger(context.caseId());
        // What the developer is about to do, read from the steps the planner actually ran (before the review below).
        List<String> ran = ledger.steps().stream().map(StoryLedger.Step::action).toList();
        List<String> next = new ArrayList<>();
        if (ran.contains("architectureCheck")) next.add("check the change against ADR-194");
        if (ran.contains("addIdempotencyTest")) next.add("add the idempotency regression test the reviewer asked for");
        if (ran.contains("keepExistingTests")) next.add("keep the existing tests");
        next.add("request review");
        String summary;
        ContextStore store = support.store(diff.store());
        if (tests.idempotencyTestAdded()) {
            summary = PR + " pushed with " + (context.adrRelevant() ? "ADR-194 compliance and " : "") + "the requested idempotency test: approved on the next review.";
            ledger.step("Catch-up agent", "requestReview", summary, support.effort("catch-up", "request-review"), StoryLedger.Kind.WORK);
            // The result goes back to memory: the request is addressed, so the PR bot, the reviewer and the next catch-up know.
            store.rememberLater(context.caseId(), AGENT_ID, PR + " review: the reviewer's request for an idempotency regression test "
                    + ADDRESSED_MARKER + " " + AGENT_ID + " on " + java.time.LocalDate.now() + " (case " + context.caseId() + ") "
                    + roundTag(diff.round()) + "]: test added, review requested.");
        } else if (!pullRequests.requestOpen(diff.store(), PR)) {
            // Nothing is open on the PR: it was addressed before, as the recalled context said.
            summary = PR + " pushed: the reviewer's earlier request was already addressed; approved on the next review.";
            ledger.step("Catch-up agent", "requestReview", summary, support.effort("catch-up", "request-review"), StoryLedger.Kind.WORK);
        } else {
            // The simulated reviewer's open request on the PR (the PR system's ground truth) was not addressed.
            summary = PR + " bounced in review: the reviewer's open request (idempotency regression test) was never addressed.";
            ledger.step("Developer", "requestReview", "Pushed and requested review.", support.effort("catch-up", "request-review"), StoryLedger.Kind.WORK);
            ledger.step("Reviewer", "requestReview",
                    "Review bounced: idempotency test still missing. Another implement-and-review round trip.", support.effort("catch-up", "review-bounced"), StoryLedger.Kind.REWORK);
            ledger.count("missedRequirements", 1);
        }
        ledger.outcome(summary);
        note(diff, REVIEW, "Push " + PR + " and request review", summary);
        List<String> facts = new ArrayList<>(List.of(
                PR + " changes the payment retry contract (" + String.join(", ", diff.files()) + ")"));
        context.items().forEach(i -> facts.add(i.text() + " (source: " + i.writtenBy() + ")"));
        if (context.items().isEmpty() && context.adrRelevant()) facts.add(WIKI_ADR);
        // The brief is what the developer reads on return, so it gets the plan, not the outcome of the work.
        facts.add("Planned next steps: " + String.join(", ", next));
        List<String> gaps = new ArrayList<>();
        boolean requestKnown = context.items().stream().anyMatch(i -> i.text().contains(REQUEST_MARKER) || i.text().contains(ADDRESSED_MARKER));
        if (context.openReviewAsk() == null && !requestKnown) gaps.add("Whether the reviewer left open requests on " + PR + " is unknown");
        if (context.incidentLesson() == null) gaps.add("Incident history related to payment retries is unknown");
        ledger.handToWriter(new ResponseWriterAgent.WritingTask(context.caseId(), "Catch-up agent",
                "catch-up brief for the developer returning from two weeks away",
                support.render("writer/sdlc-catch-up", Map.of("pr", PR)),
                facts, List.copyOf(gaps), summary));
        return new ReviewReady(context.caseId(), summary);
    }

    @Condition(name = "diffRead")
    public boolean diffRead(DiffSummary diff) {
        return diff != null;
    }

    @Condition(name = "contextRecalled")
    public boolean contextRecalled(ProjectContext context) {
        return context != null && context.recalled();
    }

    @Condition(name = "contextMissing")
    public boolean contextMissing(ProjectContext context) {
        return context != null && !context.recalled();
    }

    @Condition(name = "adrRelevant")
    public boolean adrRelevant(ProjectContext context) {
        return context != null && context.adrRelevant();
    }

    @Condition(name = "adrNotRelevant")
    public boolean adrNotRelevant(ProjectContext context) {
        return context != null && context.recalled() && !context.adrRelevant();
    }

    @Condition(name = "reviewAskOpen")
    public boolean reviewAskOpen(ProjectContext context) {
        return context != null && context.recalled() && context.openReviewAsk() != null;
    }

    @Condition(name = "noKnownAsks")
    public boolean noKnownAsks(ProjectContext context) {
        return context != null && context.recalled() && context.openReviewAsk() == null;
    }

    @Condition(name = "architectureChecked")
    public boolean architectureChecked(ArchitectureChecked checked) {
        return checked != null;
    }

    @Condition(name = "testsDecided")
    public boolean testsDecided(TestsDecision tests) {
        return tests != null;
    }

    @Condition(name = "reviewReady")
    public boolean reviewReady(ReviewReady ready) {
        return ready != null;
    }
}
