package meko.agentic.goap.story;

import meko.agentic.goap.model.ModelCallLog;
import meko.agentic.goap.story.agents.CatchUpAgent;
import meko.agentic.goap.story.agents.DisputeAuditorAgent;
import meko.agentic.goap.story.agents.FairLendingReviewAgent;
import meko.agentic.goap.story.agents.LoanIntakeAgent;
import meko.agentic.goap.story.agents.KycReviewCheckAgent;
import meko.agentic.goap.story.agents.ResponseWriterAgent;
import meko.agentic.goap.story.agents.KycReviewAgent;
import meko.agentic.goap.story.agents.PaymentFailureAgent;
import meko.agentic.goap.story.agents.SreInvestigatorAgent;
import meko.agentic.goap.story.agents.SreTakeoverAgent;
import meko.agentic.goap.story.agents.SupportFrontlineAgent;
import meko.agentic.goap.story.agents.UnderwriterAgent;
import meko.agentic.goap.story.store.ContextStore;
import meko.agentic.goap.story.store.ContextStores;
import meko.agentic.goap.story.store.ReceiptLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the stories as real Embabel agent processes and assembles the measured result. */
@Service
public class StoryService {
    private static final List<String[]> FAILURE_CASES = List.of(
            new String[]{"CUST-3001", "TX-99018"},
            new String[]{"CUST-4410", "TX-99107"},
            new String[]{"CUST-5120", "TX-99230"},
            new String[]{"CUST-6034", "TX-99342"});

    private final StoryCatalog catalog;
    private final StoryRunner runner;
    private final StoryLedgers ledgers;
    private final ReceiptLog receipts;
    private final ContextStores stores;
    private final ModelCallLog modelCalls;
    private final double hourlyRateUsd;
    private final AtomicInteger failureCase = new AtomicInteger();
    private final PaymentDisputes disputes;
    private final LoanApplications loans;
    /** What each story last handled in each lane (story|lane → transaction or review id): what its follow-up looks at. */
    private final Map<String, String> lastSubject = new java.util.concurrent.ConcurrentHashMap<>();
    /** See {@link PaymentFailureAgent.FailureCase#epoch()}. */
    private volatile String learningEpoch = "1";
    /** The KYC review cycle: reviews this application wrote back in an earlier cycle are set aside by the next review. */
    private volatile String reviewCycle = "1";
    /** The catch-up story's round: the agent's write-backs from earlier rounds are set aside, the request reopened. */
    private volatile String catchUpRound = "1";
    /**
     * The loan story's precedent set: credit-risk rulings promoted while it is current count as precedent, in this run
     * and later ones, so a ruling is promoted once rather than per run. A reset starts a new set; rulings from earlier
     * sets stay in shared knowledge (promoted knowledge cannot be deleted through MCP) but are set aside by their tag.
     */
    private volatile String precedentSet = Long.toString(System.currentTimeMillis(), 36);
    private final PullRequests pullRequests;

    public StoryService(StoryCatalog catalog, StoryRunner runner, StoryLedgers ledgers,
                        ReceiptLog receipts, ContextStores stores, ModelCallLog modelCalls, PaymentDisputes disputes, LoanApplications loans, PullRequests pullRequests,
                        @Value("${goap.cost.hourly-rate-usd:60}") double hourlyRateUsd) {
        this.disputes = disputes;
        this.loans = loans;
        this.pullRequests = pullRequests;
        this.catalog = catalog;
        this.runner = runner;
        this.ledgers = ledgers;
        this.receipts = receipts;
        this.stores = stores;
        this.modelCalls = modelCalls;
        this.hourlyRateUsd = hourlyRateUsd;
    }

    public StoryRun run(String storyId, boolean meko) {
        return run(storyId, meko, newCaseId(storyId));
    }

    /** Runs a story under a case id chosen by the caller (the streaming endpoint subscribes to it first). */
    public StoryRun run(String storyId, boolean meko, String caseId) {
        return run(storyId, meko ? ContextStores.Lane.MEKO : ContextStores.Lane.WITHOUT, caseId);
    }

    public StoryRun run(String storyId, ContextStores.Lane lane) {
        return run(storyId, lane, newCaseId(storyId));
    }

    /** Runs a story in one lane: no durable context, the application's own database, or Meko. */
    public StoryRun run(String storyId, ContextStores.Lane lane, String caseId) {
        StoryCatalog.Story story = catalog.get(storyId);
        ContextStore.Kind kind = stores.forLane(lane).kind();
        boolean meko = lane == ContextStores.Lane.MEKO;
        StoryLedger ledger = ledgers.forCase(caseId);
        List<StoryRunner.Phase> phases = new ArrayList<>();
        // Stories 1, 2 and the loan story hand off across a system boundary. With Meko both sides share the datapack; in the database
        // lane the first agent writes to its own system's store and your agent reads your database.
        ContextStore.Kind otherSide = lane == ContextStores.Lane.DATABASE ? ContextStore.Kind.OTHER_SYSTEM_DB : kind;

        switch (storyId) {
            case "dont-make-me-explain" -> {
                lastSubject.put(subjectKey(storyId, lane), disputes.open(caseId).transaction());
                StoryRunner.Phase frontline = runner.run("Vendor chatbot · a separate product with its own store", "SupportFrontlineAgent", ledger,
                        new SupportFrontlineAgent.SupportChat(caseId, otherSide));
                phases.add(frontline);
                if (frontline.result() instanceof SupportFrontlineAgent.TicketNote ticket) {
                    // Only the CRM ticket crosses the boundary.
                    phases.add(runner.run("Your payments specialist · new agent process, given only the CRM ticket",
                            "PaymentsSpecialistAgent", ledger, new SupportFrontlineAgent.TicketNote(ticket.caseId(), kind, ticket.note())));
                }
            }
            case "loan-underwriting" -> {
                // Two applications in turn. A credit-risk ruling promoted on the first is precedent for the second, and for
                // later runs, until the precedent set is reset.
                String precedents = precedentSet;
                String first = loans.open(caseId).applicationId();
                underwrite("Application 1", caseId, kind, otherSide, precedents, ledger, phases);
                String second = loans.openNext(caseId).applicationId();
                underwrite("Application 2, a later borrower", caseId, kind, otherSide, precedents, ledger, phases);
                // The follow-up reviews both applications.
                lastSubject.put(subjectKey(storyId, lane), first + SUBJECTS + second);
            }
            case "agent-died-2am" -> {
                phases.add(investigate(caseId, otherSide, ledger));
                phases.add(takeOver(caseId, kind, ledger));
            }
            case "what-changed" -> {
                // The review id is the case id: the analyst's record is keyed by it.
                lastSubject.put(subjectKey(storyId, lane), caseId);
                phases.add(runner.run("KYC analyst agent", "KycReviewAgent", ledger,
                    new KycReviewAgent.ReviewRequest(caseId, kind, reviewCycle)));
            }
            case "vacation-catch-up" -> phases.add(runner.run("Catch-up agent", "CatchUpAgent", ledger,
                    new CatchUpAgent.CatchUpRequest(caseId, kind, catchUpRound)));
            case "organization-learned" -> {
                String[] next = FAILURE_CASES.get(Math.floorMod(failureCase.getAndIncrement(), FAILURE_CASES.size()));
                phases.add(runner.run("APAC payments desk · case for " + next[0], "PaymentFailureAgent", ledger,
                        new PaymentFailureAgent.FailureCase(caseId, kind, next[0], next[1], learningEpoch)));
            }
            default -> throw new IllegalArgumentException("Unknown story: " + storyId);
        }
        return assemble(story, lane, kind, caseId, ledger, phases);
    }

    /** One loan application: the vendor's origination system takes it, then your underwriter, given the referral and its file. */
    private void underwrite(String label, String caseId, ContextStore.Kind kind, ContextStore.Kind otherSide, String precedents,
                            StoryLedger ledger, List<StoryRunner.Phase> phases) {
        ledger.count("cases", 1); // a run of this story is several applications: projections count per application
        StoryRunner.Phase intake = runner.run(label + " · loan-origination system, a vendor's product with its own store", "LoanIntakeAgent", ledger,
                new LoanIntakeAgent.LoanApplicationStart(caseId, otherSide));
        phases.add(intake);
        if (intake.result() instanceof LoanIntakeAgent.Referral referral) {
            // The referral and the application file cross the boundary; the borrower's explanations do not.
            phases.add(runner.run(label + " · your credit underwriter, new agent process, given the referral and the file", "UnderwriterAgent", ledger,
                    new LoanIntakeAgent.Referral(referral.caseId(), kind, referral.applicationId(), referral.note(), referral.file(), precedents)));
        }
    }

    // ------------------------------------------------------------------
    // Follow-ups: someone accountable looks at a finished case later, given only its id, and reads the decision traces.
    // ------------------------------------------------------------------

    /** Runs a story's follow-up on what {@code lane} last handled for that story. */
    public StoryRun followUp(String storyId, ContextStores.Lane lane, String caseId) {
        StoryCatalog.Story story = catalog.get(storyId);
        if (story.followUp() == null) throw new IllegalArgumentException(story.title() + " has no follow-up.");
        String subject = lastSubject.get(subjectKey(storyId, lane));
        if (subject == null) {
            throw new IllegalArgumentException("Run \"" + story.title() + "\" in this lane first: there is no " + story.followUp().subject() + " to look at yet.");
        }
        return followUp(storyId, lane, caseId, subject);
    }

    /** Runs a story's follow-up on {@code subject}: the transaction id for story 1, the review id for story 3, the application id for the loan story. */
    public StoryRun followUp(String storyId, ContextStores.Lane lane, String caseId, String subject) {
        StoryCatalog.Story story = catalog.get(storyId);
        StoryCatalog.FollowUp followUp = story.followUp();
        ContextStore.Kind kind = stores.forLane(lane).kind();
        StoryLedger ledger = ledgers.forCase(caseId);
        List<StoryRunner.Phase> phases = new ArrayList<>();
        for (String one : subject.split(SUBJECTS)) {
            Object ask = switch (followUp.agent()) {
                case "DisputeAuditorAgent" -> new DisputeAuditorAgent.AuditRequest(caseId, kind, one);
                case "KycReviewCheckAgent" -> new KycReviewCheckAgent.CheckRequest(caseId, kind, one);
                case "FairLendingReviewAgent" -> new FairLendingReviewAgent.ReviewRequest(caseId, kind, one);
                default -> throw new IllegalStateException("No request shape for " + followUp.agent());
            };
            phases.add(runner.run(followUp.who() + " · later, given only " + followUp.subject() + " " + one, followUp.agent(), ledger, ask));
        }
        return assemble(story, lane, kind, caseId, ledger, phases, followUp.headline());
    }

    /** What {@code lane} last handled for {@code storyId}: the subject its follow-up looks at. */
    public java.util.Optional<String> lastSubject(String storyId, ContextStores.Lane lane) {
        return java.util.Optional.ofNullable(lastSubject.get(subjectKey(storyId, lane)));
    }

    /** Case id for a follow-up run of {@code storyId}. */
    public String newFollowUpCaseId(String storyId) {
        StoryCatalog.FollowUp followUp = catalog.get(storyId).followUp();
        return newCaseId(followUp == null ? storyId : followUp.caseKind());
    }

    /** Separates the subjects of a run that handled several (the loan story's two applications). */
    static final String SUBJECTS = ",";

    private static String subjectKey(String storyId, ContextStores.Lane lane) {
        return storyId + "|" + lane;
    }

    // ------------------------------------------------------------------
    // Story 2 in two steps, so the presenter can restart the server in between.
    // ------------------------------------------------------------------

    public StoryRun startIncident(boolean meko) {
        ContextStore.Kind kind = stores.forRun(meko).kind();
        String caseId = newCaseId("agent-died-2am");
        StoryLedger ledger = ledgers.forCase(caseId);
        return assemble(catalog.get("agent-died-2am"), lane(meko), kind, caseId, ledger, List.of(investigate(caseId, kind, ledger)));
    }

    public StoryRun takeOverIncident(String caseId, boolean meko) {
        ContextStore.Kind kind = stores.forRun(meko).kind();
        StoryLedger ledger = ledgers.forCase(caseId);
        return assemble(catalog.get("agent-died-2am"), lane(meko), kind, caseId, ledger, List.of(takeOver(caseId, kind, ledger)));
    }

    private StoryRunner.Phase investigate(String caseId, ContextStore.Kind kind, StoryLedger ledger) {
        return runner.run("Payments team's on-call agent · the payments team's application", "SreInvestigatorAgent", ledger,
                new SreInvestigatorAgent.IncidentPage(caseId, kind));
    }

    private StoryRunner.Phase takeOver(String caseId, ContextStore.Kind kind, StoryLedger ledger) {
        return runner.run("Your platform SRE agent · new agent process, given only the incident id", "SreTakeoverAgent", ledger,
                new SreTakeoverAgent.TakeoverRequest(caseId, kind));
    }

    // ------------------------------------------------------------------
    // Story 5: learning that compounds across cases.
    // ------------------------------------------------------------------

    /**
     * Starts a new learning epoch, so the story can be shown from the start again. Patterns learned in earlier
     * epochs stay in the knowledge base (Meko's MCP cannot currently delete promoted knowledge) but are ignored.
     */
    public Map<String, Object> resetLearning() {
        learningEpoch = Long.toString(System.currentTimeMillis(), 36);
        failureCase.set(0);
        return Map.of("learningEpoch", learningEpoch,
                "note", "Started a new learning epoch: lessons learned earlier are ignored, so the next case is investigated from scratch.");
    }

    /**
     * Starts a new KYC review cycle. Each review writes its result back (a decision and fingerprints), so the next review
     * in the same cycle compares with it and finds nothing new; a new cycle sets those aside and compares with the last one.
     */
    public Map<String, Object> newReviewCycle() {
        reviewCycle = Long.toString(System.currentTimeMillis(), 36);
        // This application's own write-backs from earlier cycles are removed from every store it writes to; seeded
        // history (reviews other desks wrote) stays. Anything that cannot be removed is set aside by its cycle tag.
        int removed = removeOwn(KycReviewAgent.AGENT_ID, "KYC review of customer review cycle profile fingerprint",
                r -> KycReviewMemory.parse(r.text()).map(m -> m.cycle() != null && !m.cycle().equals(reviewCycle)).orElse(false));
        // What the next review will compare with, as the Meko lane's memory now holds it.
        String next = stores.forLane(ContextStores.Lane.MEKO)
                .searchMemory("review-cycle", KycReviewAgent.AGENT_ID, "KYC review of customer " + KycReviewAgent.CUSTOMER + " profile fingerprint")
                .stream().map(r -> KycReviewMemory.parse(r.text()).filter(m -> m.customer().equals(KycReviewAgent.CUSTOMER))
                        .map(m -> Map.entry(m.date(), r.writtenBy())))
                .flatMap(java.util.Optional::stream).max(Map.Entry.comparingByKey())
                .map(e -> "the next review compares with the " + e.getKey() + " review written by " + e.getValue() + ".")
                .orElse("the next review has no previous review to compare with.");
        return Map.of("reviewCycle", reviewCycle,
                "note", "Started a new review cycle. " + (removed == 0 ? "No earlier reviews from this app to remove; "
                        : "Removed " + removed + " earlier review" + (removed == 1 ? "" : "s") + " written by this app; ") + next);
    }

    /**
     * Starts the catch-up story over: the reviewer's request on the PR is open again, and the catch-up agent's own
     * write-backs (that it addressed the request) are removed from every store it writes to.
     */
    public Map<String, Object> reopenReviewRequest() {
        catchUpRound = Long.toString(System.currentTimeMillis(), 36);
        pullRequests.reopenAll();
        int removed = removeOwn(CatchUpAgent.AGENT_ID, CatchUpAgent.PR + " review request addressed round",
                r -> r.text().contains(CatchUpAgent.ADDRESSED_MARKER));
        return Map.of("round", catchUpRound, "note", "Reopened the reviewer's request on " + CatchUpAgent.PR + ". "
                + (removed == 0 ? "Nothing from earlier catch-ups to remove" : "Removed " + removed + " earlier note" + (removed == 1 ? "" : "s")
                + " that it was addressed") + ": the next catch-up starts from the PR as it was.");
    }

    /**
     * Starts a new precedent set for the loan story: rulings promoted in the current set are set aside, so the next
     * application asks credit risk again and its ruling is promoted afresh.
     */
    public Map<String, Object> newPrecedentSet() {
        String previous = precedentSet;
        // How many rulings the set being closed holds, as Meko's shared knowledge returns them.
        long rulings = stores.forLane(ContextStores.Lane.MEKO).searchKnowledge("precedent-reset", UnderwriterAgent.AGENT_ID,
                        UnderwriterAgent.RULING_MARKER + " acceptable evidence " + UnderwriterAgent.precedentTag(previous))
                .stream().filter(r -> r.text().contains(UnderwriterAgent.RULING_MARKER) && r.text().contains(UnderwriterAgent.precedentTag(previous))).count();
        stores.forLane(ContextStores.Lane.MEKO).flush("precedent-reset"); // the count is read fresh next time
        precedentSet = Long.toString(System.currentTimeMillis(), 36);
        return Map.of("precedentSet", precedentSet, "note", "Started a new precedent set. "
                + (rulings == 0 ? "No rulings had been promoted in the previous one" : rulings + " ruling" + (rulings == 1 ? "" : "s")
                + " promoted in the previous set " + (rulings == 1 ? "is" : "are") + " set aside")
                + ": the next application asks credit risk again.");
    }

    /**
     * Removes {@code agentId}'s own memories matching {@code stale} from every store this application writes to (Meko
     * and its own database); returns how many were removed. Never touches what another agent wrote.
     */
    private int removeOwn(String agentId, String query, java.util.function.Predicate<meko.agentic.goap.story.store.StoreRecord> stale) {
        int removed = 0;
        for (ContextStores.Lane lane : List.of(ContextStores.Lane.MEKO, ContextStores.Lane.DATABASE)) {
            ContextStore store = stores.forLane(lane);
            for (int round = 0; round < 20; round++) {
                List<String> ids = store.searchMemory("reset", agentId, query).stream()
                        .filter(r -> agentId.equals(r.writtenBy())).filter(stale)
                        .map(meko.agentic.goap.story.store.StoreRecord::id).toList();
                long gone = ids.stream().filter(id -> store.forget("reset", agentId, id)).count();
                removed += (int) gone;
                if (gone == 0) break;
            }
        }
        return removed;
    }

    /** Runs a story's reset, as its catalog entry names it. */
    public Map<String, Object> reset(String storyId) {
        return switch (storyId) {
            case "organization-learned" -> resetLearning();
            case "what-changed" -> newReviewCycle();
            case "vacation-catch-up" -> reopenReviewRequest();
            case "loan-underwriting" -> newPrecedentSet();
            default -> throw new IllegalArgumentException(catalog.get(storyId).title() + " has no reset.");
        };
    }

    /**
     * Trust scenario: a case that looks like the learned pattern but isn't. Meko retrieves the pattern; the agent checks it
     * against this case's signals, rejects it, and the planner falls back to investigating. If nothing has been learned
     * in the current epoch yet, one normal case runs first to teach it.
     */
    public StoryRun patternMismatch(boolean meko) {
        return patternMismatch(meko, newCaseId("organization-learned"));
    }

    public StoryRun patternMismatch(boolean meko, String caseId) {
        ContextStore store = stores.forRun(meko);
        String probe = "pattern-probe";   // one key: a probe's search reuses one conversation, not a new one each time
        boolean learned = store.searchKnowledge(probe, "payments:pattern-probe", PaymentFailureAgent.PATTERN_MARKER + " capture timeout after successful authorization")
                .stream().anyMatch(r -> r.text().contains(PaymentFailureAgent.PATTERN_MARKER) && r.text().contains("[learning epoch " + learningEpoch + "]"));
        store.flush(probe); // a probe's search is used once
        if (meko && !learned) run("organization-learned", true);
        StoryLedger ledger = ledgers.forCase(caseId);
        if (meko && !learned) ledger.note("Note", "Nothing was learned in this epoch yet, so one normal case ran first to teach the pattern.");
        StoryRunner.Phase phase = runner.run("APAC payments desk · a case that only looks similar", "PaymentFailureAgent", ledger,
                new PaymentFailureAgent.FailureCase(caseId, store.kind(), "CUST-7202", "TX-99415", learningEpoch, true, ""));
        return assemble(catalog.get("organization-learned"), lane(meko), store.kind(), caseId, ledger, List.of(phase));
    }

    /**
     * Resets, then runs {@code cases} consecutive cases in each lane. Case 1 is handled by another desk's agent (the EU
     * payments desk, a different application): what it learns lands in the shared datapack with Meko, but in that
     * desk's own database in the database lane, where this application cannot see it.
     * <p>
     * Synchronized, with the epoch captured once and case numbers explicit, so a concurrent run or a second click cannot
     * change the epoch halfway through a series.
     */
    public synchronized Map<String, List<StoryRun>> learningSeries(int cases) {
        return learningSeries(cases, true);
    }

    /** @param includeDatabase whether to add the "your own database" lane */
    public synchronized Map<String, List<StoryRun>> learningSeries(int cases, boolean includeDatabase) {
        return learningSeries(cases, includeDatabase, true);
    }

    /** @param includeWithout whether to run the lane without Meko (the workshop follows the Meko lane alone) */
    public synchronized Map<String, List<StoryRun>> learningSeries(int cases, boolean includeDatabase, boolean includeWithout) {
        resetLearning();
        String epoch = learningEpoch;
        Map<String, List<StoryRun>> series = new LinkedHashMap<>();
        List<Map.Entry<String, ContextStores.Lane>> lanes = new ArrayList<>();
        if (includeWithout) lanes.add(Map.entry("withoutMeko", ContextStores.Lane.WITHOUT));
        if (includeDatabase) lanes.add(Map.entry("database", ContextStores.Lane.DATABASE));
        lanes.add(Map.entry("withMeko", ContextStores.Lane.MEKO));
        for (var entry : lanes) {
            List<StoryRun> runs = new ArrayList<>();
            for (int i = 0; i < cases; i++) runs.add(failureCase(entry.getValue(), i, epoch, i == 0));
            series.put(entry.getKey(), runs);
        }
        return series;
    }

    /**
     * The newest learned pattern in the Meko lane's shared knowledge: its epoch, who promoted it, and when. The pair
     * exercise's last run reads it, so it does not depend on the browser that ran case 1. Empty when there is none.
     */
    public Map<String, Object> latestLesson() {
        ContextStore store = stores.forLane(ContextStores.Lane.MEKO);
        String probe = "lesson-lookup";   // one key: each lookup reuses one conversation, not a new one each time
        java.util.regex.Pattern epochOf = java.util.regex.Pattern.compile("\\[learning epoch ([a-z0-9]+)]");
        try {
            return store.searchKnowledge(probe, "payments:lesson-lookup", PaymentFailureAgent.PATTERN_MARKER + " capture timeout after successful authorization")
                    .stream().filter(r -> r.text().contains(PaymentFailureAgent.PATTERN_MARKER))
                    .map(r -> Map.entry(r, epochOf.matcher(r.text())))
                    .filter(e -> e.getValue().find())
                    .max(java.util.Comparator.comparing(e -> String.valueOf(e.getKey().createdAt())))
                    .<Map<String, Object>>map(e -> Map.of("epoch", e.getValue().group(1), "writtenBy", String.valueOf(e.getKey().writtenBy()),
                            "createdAt", String.valueOf(e.getKey().createdAt())))
                    .orElse(Map.of());
        } finally {
            store.flush(probe);
        }
    }

    /**
     * One case of the series in the Meko lane, for the workshop's separate steps. {@code index} picks the customer (0 is
     * case 1); {@code epoch} is the learning epoch the case reads and writes, and null starts a new one, so nothing learned
     * before is in reach. Returns the epoch with the run, so a later case can read what this one promoted.
     */
    public synchronized Map<String, Object> learningCase(int index, String epoch, boolean otherTeam) {
        return learningCase(index, epoch, otherTeam, true);
    }

    /** @param share whether the case promotes what it learns; off for the workshop's baseline (the case without the lesson) */
    public synchronized Map<String, Object> learningCase(int index, String epoch, boolean otherTeam, boolean share) {
        if (epoch != null && !epoch.matches("[a-z0-9]{1,20}")) throw new IllegalArgumentException("Unknown learning epoch: " + epoch);
        // A new epoch of its own, so the workshop's steps never change the epoch the demo page's runs use.
        String use = epoch != null ? epoch : Long.toString(System.currentTimeMillis(), 36) + Integer.toString(java.util.concurrent.ThreadLocalRandom.current().nextInt(1296), 36);
        return Map.of("epoch", use, "run", failureCase(ContextStores.Lane.MEKO, Math.floorMod(index, FAILURE_CASES.size()), use, otherTeam, share));
    }

    private StoryRun failureCase(ContextStores.Lane lane, int index, String epoch, boolean otherTeam) {
        return failureCase(lane, index, epoch, otherTeam, true);
    }

    private StoryRun failureCase(ContextStores.Lane lane, int index, String epoch, boolean otherTeam, boolean share) {
        String caseId = newCaseId("organization-learned");
        ContextStore.Kind kind = otherTeam && lane == ContextStores.Lane.DATABASE
                ? ContextStore.Kind.OTHER_SYSTEM_DB : stores.forLane(lane).kind();
        String[] customer = FAILURE_CASES.get(index % FAILURE_CASES.size());
        StoryLedger ledger = ledgers.forCase(caseId);
        String label = (otherTeam ? "EU payments desk · its own application" : "APAC payments desk · this application")
                + " · case for " + customer[0];
        StoryRunner.Phase phase = runner.run(label, "PaymentFailureAgent", ledger, new PaymentFailureAgent.FailureCase(
                caseId, kind, customer[0], customer[1], epoch, false, otherTeam ? PaymentFailureAgent.EU_DESK : "", share));
        return assemble(catalog.get("organization-learned"), lane, kind, caseId, ledger, List.of(phase));
    }

    private static ContextStores.Lane lane(boolean meko) {
        return meko ? ContextStores.Lane.MEKO : ContextStores.Lane.WITHOUT;
    }

    StoryRun assemble(StoryCatalog.Story story, ContextStores.Lane lane, ContextStore.Kind kind, String caseId,
                      StoryLedger ledger, List<StoryRunner.Phase> storyPhases) {
        return assemble(story, lane, kind, caseId, ledger, storyPhases, story.headline());
    }

    StoryRun assemble(StoryCatalog.Story story, ContextStores.Lane lane, ContextStore.Kind kind, String caseId,
                      StoryLedger ledger, List<StoryRunner.Phase> storyPhases, List<List<String>> headline) {
        List<StoryRunner.Phase> phases = new ArrayList<>(storyPhases);
        // The story's last agent may hand text to be written for a human; which model writes it is planned.
        List<Object> tasks = ledger.writingTasks();
        for (Object t : tasks) {
            if (t instanceof ResponseWriterAgent.WritingTask task) {
                phases.add(runner.run("Response writer · " + (tasks.size() > 1 ? task.purpose() + " · " : "") + "model tier chosen by planner cost",
                        "ResponseWriterAgent", ledger, task));
            }
        }
        // Writes that ran in the background (conversation turns, results written back) finish before the run reports.
        long flushStart = System.nanoTime();
        stores.byKind(kind).flush(caseId);
        long flushMillis = (System.nanoTime() - flushStart) / 1_000_000;
        List<ReceiptLog.Receipt> calls = receipts.forCase(caseId);
        List<ModelCallLog.ModelCall> models = modelCalls.forCase(caseId);
        Map<String, Object> metrics = new LinkedHashMap<>(ledger.counters());
        metrics.putIfAbsent("repeatedSteps", 0);
        metrics.put("minutes", ledger.minutes());
        metrics.put("handsOnMinutes", ledger.handsOnMinutes());
        metrics.put("waitingMinutes", ledger.waitingMinutes());
        metrics.put("actionsExecuted", phases.stream().mapToInt(p -> p.executed().size()).sum());
        metrics.put("replans", phases.stream().mapToInt(p -> Math.max(0, p.plans().size() - 1)).sum());
        metrics.put("plannerCost", Math.round(phases.stream().mapToDouble(StoryRunner.Phase::plannedCost).sum() * 100) / 100.0);
        metrics.put("contextCalls", calls.size());
        // Time the agents waited on the store: their own calls, plus waiting at the end for background writes to finish.
        // Background writes run alongside the agents, so their durations are reported separately, not added in.
        metrics.put("contextMillis", calls.stream().filter(r -> !r.background()).mapToLong(ReceiptLog.Receipt::millis).sum() + flushMillis);
        metrics.put("contextBackgroundMillis", calls.stream().filter(ReceiptLog.Receipt::background).mapToLong(ReceiptLog.Receipt::millis).sum());
        metrics.put("contextFailures", calls.stream().filter(r -> !r.ok()).count());
        metrics.put("contextItemsUsed", ledger.found().size());
        metrics.put("contextItemsPassedOver", ledger.passedOver().size());
        phases.stream().filter(p -> p.result() instanceof ResponseWriterAgent.Draft).findFirst().ifPresent(p -> {
            ResponseWriterAgent.Draft draft = (ResponseWriterAgent.Draft) p.result();
            metrics.put("writerTier", draft.tier());
            metrics.put("writerModel", draft.model());
            metrics.put("writerGenerated", draft.generated());
            metrics.put("writerPriced", draft.priced());
            metrics.put("writerUnavailable", draft.unavailable());
            metrics.put("writerGaps", ledger.counters().getOrDefault("gapsForWriter", 0));
        });
        metrics.put("modelTokens", models.stream().mapToInt(m -> (m.promptTokens() == null ? 0 : m.promptTokens())
                + (m.completionTokens() == null ? 0 : m.completionTokens())).sum());
        metrics.put("modelMillis", models.stream().mapToLong(ModelCallLog.ModelCall::millis).sum());
        metrics.put("modelCalls", models.size());
        metrics.put("modelUsd", models.stream().mapToDouble(ModelCallLog.ModelCall::usd).sum());
        // The writer runs last, so when it generated text its call is the run's last model call.
        boolean writerGenerated = Boolean.TRUE.equals(metrics.get("writerGenerated"));
        metrics.put("writerPromptTokens", writerGenerated && !models.isEmpty() && models.getLast().promptTokens() != null
                ? models.getLast().promptTokens() : 0);
        metrics.put("decisions", ledger.decisions().size());
        metrics.put("decisionsByModel", ledger.decisions().stream().filter(StoryLedger.Decision::byModel).count());
        metrics.put("hourlyRateUsd", hourlyRateUsd);
        // Only hands-on minutes are labour; waiting on a customer or another team's queue is not priced.
        metrics.put("effortUsd", Math.round(ledger.handsOnMinutes() / 60.0 * hourlyRateUsd * 100) / 100.0);
        return new StoryRun(story.id(), story.title(), lane == ContextStores.Lane.MEKO, lane.name(), kind.name(), caseId, ledger.outcome(), phases,
                ledger.steps(), ledger.transcript(), ledger.found(), ledger.passedOver(), ledger.decisions(), calls, models, metrics, headline, ledger.auditTrail(), receipts.writtenFor(caseId));
    }

    public static String newCaseId(String storyId) {
        String prefix = switch (storyId) {
            case "dont-make-me-explain" -> "SUP";
            case "agent-died-2am" -> "INC";
            case "what-changed" -> "KYC";
            case "vacation-catch-up" -> "CATCHUP";
            case "organization-learned" -> "PAY";
            case "loan-underwriting" -> "LOAN";
            case "lending-review" -> "FLR";
            case "audit" -> "AUD";
            case "review-check" -> "RVW";
            default -> "CASE";
        };
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
