package meko.agentic.goap.story;

import meko.agentic.goap.story.store.ContextStores;
import meko.agentic.goap.story.store.OutageSwitch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every story through the real Embabel planner, without Meko and with the offline stand-in, and checks
 * that the plans diverge for the reason each story claims. Guards against framework upgrades silently
 * changing planning behaviour (a stuck plan, an action that never gets scheduled).
 */
// Model calls disabled: the writer plans its tier from price and risk, then uses a template instead of calling a model.
@SpringBootTest(properties = {"meko.enabled=false", "goap.llm.enabled=false"})
class StoryPlanningTest {

    @Autowired
    StoryService stories;

    @Autowired
    OutageSwitch outage;

    @Autowired
    StoryLedgers ledgers;

    @Autowired
    ContextStores stores;

    /** KYC reviews write their result back, so each test starts a fresh review cycle to compare with last quarter's. */
    @org.junit.jupiter.api.BeforeEach
    void freshReviewCycle() {
        stories.reset("what-changed");
        stories.reset("vacation-catch-up");
    }

    @Test
    void theCatchUpWritesBackThatTheRequestWasAddressed() {
        StoryRun first = stories.run("vacation-catch-up", ContextStores.Lane.MEKO);
        assertThat(executed(first)).contains("addIdempotencyTest");
        // The next catch-up reads that the request was addressed: nothing open, no test to add, no bounce.
        StoryRun second = stories.run("vacation-catch-up", ContextStores.Lane.MEKO);
        assertThat(executed(second)).contains("keepExistingTests").doesNotContain("addIdempotencyTest");
        assertThat(metric(second, "missedRequirements")).isZero();
        assertThat(second.outcome()).contains("already addressed");
        assertThat(second.found()).anyMatch(f -> f.writtenBy().equals("sdlc:catch-up-agent") && f.text().contains("was addressed by"));
        // Without Meko nothing was written back: the request is still open and the PR bounces again.
        stories.run("vacation-catch-up", ContextStores.Lane.WITHOUT);
        assertThat(metric(stories.run("vacation-catch-up", ContextStores.Lane.WITHOUT), "missedRequirements")).isEqualTo(1);
        // Reopening the request starts the story over.
        stories.reset("vacation-catch-up");
        assertThat(executed(stories.run("vacation-catch-up", ContextStores.Lane.MEKO))).contains("addIdempotencyTest");
    }

    @Test
    void theIncidentRecordHoldsTheTimelineAndTheLearningStoryEachCaseResult() {
        // Story 2: the incident's conversation is the postmortem timeline, written by both agents.
        StoryRun incident = stories.run("agent-died-2am", true);
        var timeline = stores.forLane(ContextStores.Lane.MEKO).transcript("t", "t", incident.caseId());
        assertThat(timeline).extracting(t -> t.event().orElse("") + " by " + t.writtenBy())
                .contains("deploy correlated by payments-team:oncall-agent", "database ruled out by payments-team:oncall-agent",
                        "connection pool verified by platform-sre:takeover-agent", "mitigated by platform-sre:takeover-agent");
        StoryRun redone = stories.run("agent-died-2am", ContextStores.Lane.DATABASE);
        assertThat(stores.forLane(ContextStores.Lane.DATABASE).transcript("t", "t", redone.caseId()))
                .anyMatch(t -> t.isEvent("platform-sre:takeover-agent", "deploy correlated") && t.why().startsWith("redone"));
        // Story 5: each case's result is written back, saying how it was diagnosed.
        var series = stories.learningSeries(2, false);
        var results = stores.forLane(ContextStores.Lane.MEKO).searchMemory("t", "t", "Payment case closed root cause diagnosed")
                .stream().map(meko.agentic.goap.story.store.StoreRecord::text).toList();
        assertThat(results).anyMatch(r -> r.contains(series.get("withMeko").get(0).caseId()) && r.contains("diagnosed by testing 3 hypotheses"));
        assertThat(results).anyMatch(r -> r.contains(series.get("withMeko").get(1).caseId()) && r.contains("diagnosed by reusing the learned pattern"));
    }

    @Test
    void aKycReviewWritesBackItsResultAndTheNextReviewUsesIt() {
        StoryRun first = stories.run("what-changed", ContextStores.Lane.MEKO);
        assertThat(metric(first, "evidenceRequests")).isEqualTo(1);
        // The second review in the same cycle compares with the first one's result, finds nothing material, asks for nothing.
        StoryRun second = stories.run("what-changed", ContextStores.Lane.MEKO);
        assertThat(executed(second)).contains("diffAgainstPrior", "noEvidenceNeeded").doesNotContain("targetedEvidence", "fullReview");
        assertThat(metric(second, "evidenceRequests")).isZero();
        assertThat(second.found()).anyMatch(f -> f.writtenBy().equals("kyc:review-agent"));
        // Your own database: the first review starts cold, the second uses the first one's write-back.
        StoryRun dbFirst = stories.run("what-changed", ContextStores.Lane.DATABASE);
        StoryRun dbSecond = stories.run("what-changed", ContextStores.Lane.DATABASE);
        assertThat(executed(dbFirst)).contains("fullReview");
        assertThat(executed(dbSecond)).contains("noEvidenceNeeded").doesNotContain("fullReview");
        // One current review per cycle: the second write-back replaced the first.
        assertThat(stores.forLane(ContextStores.Lane.MEKO).searchMemory("check", "check", "KYC review of customer CUST-7788 review cycle profile fingerprint")
                .stream().filter(r -> r.writtenBy().equals("kyc:review-agent")).count()).isEqualTo(1);
        // A new cycle removes this application's write-backs: the comparison is with last quarter's review again,
        // and the message says so from what the store now holds.
        String note = String.valueOf(stories.reset("what-changed").get("note"));
        assertThat(note).startsWith("Started a new review cycle. Removed ").doesNotContainPattern("cycle [a-z0-9]{6,}")
                .endsWith("the next review compares with the 2026-04-14 review written by kyc-analyst:2026-q2.");
        StoryRun nextCycle = stories.run("what-changed", ContextStores.Lane.MEKO);
        assertThat(metric(nextCycle, "evidenceRequests")).isEqualTo(1);
        assertThat(nextCycle.found()).anyMatch(f -> f.writtenBy().equals("kyc-analyst:2026-q2"));
    }

    @Test
    void nothingTheKycReviewWritesHoldsTheProfile() {
        StoryRun run = stories.run("what-changed", ContextStores.Lane.MEKO);
        stories.run("what-changed", ContextStores.Lane.MEKO);
        List<String> profileValues = List.of("Harbour", "Dock Street", "Quay Lane", "directors", "single director",
                "Northwind", "Acme", "Orion", "Helix", "44800");
        var store = stores.forLane(ContextStores.Lane.MEKO);
        // Memory: the seeded reviews and the write-backs hold decisions and fingerprints only.
        List<String> memories = store.searchMemory("check", "check", "KYC review of customer CUST-7788 CUST-7789 profile fingerprint risk class")
                .stream().map(meko.agentic.goap.story.store.StoreRecord::text).toList();
        assertThat(memories).isNotEmpty().allSatisfy(m -> assertThat(m).doesNotContain(profileValues.toArray(String[]::new)));
        // The review's conversation, including its decision traces: references and fingerprints only.
        var record = store.transcript("check", "check", run.caseId());
        assertThat(record).isNotEmpty().allSatisfy(t -> assertThat(t.input() + " " + t.output() + " " + t.reasoning())
                .doesNotContain(profileValues.toArray(String[]::new)));
        // Every turn reads naturally: the input is a request or question, never a tag.
        assertThat(record).allSatisfy(t -> assertThat(t.input()).doesNotContain(" · "));
        // What the writer (an external model) is given is minimized the same way.
        var task = (meko.agentic.goap.story.agents.ResponseWriterAgent.WritingTask) ledgers.forCase(run.caseId()).writingTask();
        assertThat(task.facts()).allSatisfy(f -> assertThat(f).doesNotContain(profileValues.toArray(String[]::new)));
    }

    @Test
    void customerIsNotAskedAgainWhenTheHandoffCarriesContext() {
        StoryRun without = stories.run("dont-make-me-explain", false);
        StoryRun with = stories.run("dont-make-me-explain", true);
        assertCompleted(without, with);
        assertThat(with.store()).isEqualTo("LOCAL_STAND_IN");
        assertThat(metric(without, "customerQuestions")).isEqualTo(3);
        assertThat(metric(with, "customerQuestions")).isZero();
        assertThat(executed(without)).contains("askForTransaction", "escalateForPolicy");
        assertThat(executed(with)).doesNotContain("askForTransaction", "escalateForPolicy");
        assertThat(without.phases().get(1).plans()).hasSizeGreaterThan(1);
    }

    @Test
    void borrowerIsNotAskedToExplainAgainAndARulingBecomesPrecedent() {
        stories.reset("loan-underwriting");
        StoryRun without = stories.run("loan-underwriting", ContextStores.Lane.WITHOUT);
        StoryRun db = stories.run("loan-underwriting", ContextStores.Lane.DATABASE);
        StoryRun with = stories.run("loan-underwriting", ContextStores.Lane.MEKO);
        assertCompleted(without, db, with);
        // Two applications per run; the file crossed in every lane: one credit pull each.
        assertThat(List.of(without, db, with)).allSatisfy(r -> assertThat(metric(r, "hardInquiries")).isEqualTo(2));
        // Without the borrowers' explanations: application 1's deposit and gap, application 2's deposit, go back as conditions.
        assertThat(executed(without)).contains("reviewFlags", "issueConditions", "escalateForPolicy");
        assertThat(metric(without, "borrowerReexplained")).isEqualTo(3);
        assertThat(without.phases().get(1).plans()).hasSizeGreaterThan(1);
        // The bill of sale is not evidence the policy lists: with nowhere to keep the ruling, credit risk is asked twice.
        assertThat(metric(without, "rulingsRequested")).isEqualTo(2);
        // Your own database cannot see what the vendor's system wrote, but keeps your underwriter's own ruling for its next case.
        assertThat(db.store()).isEqualTo("SHARED_DB");
        assertThat(metric(db, "borrowerReexplained")).isEqualTo(3);
        assertThat(metric(db, "rulingsRequested")).isEqualTo(1);
        // With Meko the explanations clear the conditions; the ruling on application 1 is promoted, and found for application 2.
        assertThat(executed(with)).contains("reviewFlags", "acceptEvidence").doesNotContain("issueConditions", "escalateForPolicy");
        assertThat(metric(with, "borrowerReexplained")).isZero();
        assertThat(metric(with, "rulingsRequested")).isEqualTo(1);
        assertThat(metric(with, "rulingsShared")).isEqualTo(1);
        assertThat(with.receipts()).anyMatch(r -> r.tool().equals("memory_promote") && r.ok());
        assertThat(with.phases().get(1).executed()).extracting(PlanRecorder.Executed::action).contains("requestRuling");
        assertThat(with.phases().get(3).executed()).extracting(PlanRecorder.Executed::action).doesNotContain("requestRuling");
        assertThat(with.found()).anyMatch(f -> f.writtenBy().equals("lending:underwriter") && f.text().startsWith("Credit-risk ruling under LEND-UW-05"));
        assertThat(with.found()).anyMatch(f -> f.writtenBy().equals("vendor:loan-origination"));
        // The look-alike self-employed policy was retrieved and passed over, with the reason.
        assertThat(with.passedOver()).anyMatch(p -> p.text().contains("LEND-UW-09") && p.reason().contains("self-employed"));
        // The credit calls apply the thresholds of the policy text the underwriter had, in every lane, to the same figures.
        // 25,000 USD over 36 months at 18.9% APR is 915 a month: DTI (1,850 + 915) / 7,400 = 37.4%, within 40%.
        assertThat(List.of(without, db, with)).allSatisfy(r -> assertThat(r.outcome()).contains("approve under LEND-UW-05").contains("DTI 37.4%").contains("DTI 31.4%"));
        // Without the policy in reach, credit risk sends it: a written basis, recorded as such.
        assertThat(without.outcome()).contains("(sent by credit risk on request)");
        // Your own database: each condition re-asked before it was cleared, and its evidence's basis recorded.
        var record = stores.forLane(ContextStores.Lane.DATABASE).transcript("t", "t", findApplication(db));
        int ask = indexOf(record, t -> t.isEvent("lending:underwriter", "re-asked") && t.output().contains("deposit"));
        int cleared = indexOf(record, t -> t.isEvent("lending:underwriter", "condition cleared") && t.input().contains("deposit"));
        assertThat(ask).isNotNegative().isLessThan(cleared);
        assertThat(record.get(cleared).output()).startsWith("cleared on the borrower's answer to the condition").contains("evidence accepted: the bill of sale");
        // The credit call's record states the thresholds applied and the factors it rested on.
        assertThat(record).anyMatch(t -> t.isEvent("lending:underwriter", "credit call") && t.output().contains("Thresholds applied: ")
                && t.output().contains("Factors: credit score 712, DTI 37.4%, reserves 4 months"));
        assertThat(metric(with, "minutes")).isLessThan(metric(db, "minutes"));
        assertThat(metric(db, "minutes")).isLessThan(metric(without, "minutes"));
        // Each borrower gets their own message.
        assertThat(ledgers.forCase(with.caseId()).writingTasks()).hasSize(2);
        // The precedent set outlives the run: the next run finds the ruling for both applications and promotes nothing new.
        StoryRun again = stories.run("loan-underwriting", ContextStores.Lane.MEKO);
        assertThat(metric(again, "rulingsRequested")).isZero();
        assertThat(again.receipts()).noneMatch(r -> r.tool().equals("memory_promote"));
        // A reset starts a new set: the ruling is requested, and promoted, again.
        stories.reset("loan-underwriting");
        assertThat(metric(stories.run("loan-underwriting", ContextStores.Lane.MEKO), "rulingsRequested")).isEqualTo(1);
    }

    @Test
    void thePolicysConditionsAreReadFromItsText() {
        var c = LendingConditions.parse("A single deposit larger than 25% of monthly income must be sourced. An employment gap longer than 30 days in the last two years needs a letter of explanation.").orElseThrow();
        assertThat(c.depositNeedsSource(8_000, 7_400)).isTrue();
        assertThat(c.depositNeedsSource(1_500, 7_400)).isFalse();
        assertThat(c.gapNeedsExplanation(92)).isTrue();
        assertThat(c.gapNeedsExplanation(21)).isFalse();
        var withEvidence = LendingConditions.parse("A single deposit larger than 25% of monthly income must be sourced. An employment gap longer than 30 days needs a letter. "
                + "Acceptable evidence of a deposit's source: a gift letter, or a statement of the account the money came from. "
                + "Acceptable evidence for an employment gap: the employer's letter. Any other evidence needs a ruling from credit risk.").orElseThrow();
        assertThat(withEvidence.listsEvidence("deposit", "the bill of sale")).isFalse();
        assertThat(withEvidence.listsEvidence("deposit", "a gift letter from a parent")).isTrue();
        assertThat(withEvidence.listsEvidence("employment gap", "the employer's letter confirming the leave")).isTrue();
        assertThat(LendingConditions.parse("No conditions here.")).isEmpty();
    }

    private static String findApplication(StoryRun run) {
        return run.transcript().stream().map(StoryLedger.Line::text).map(t -> java.util.regex.Pattern.compile("(APP-\\d+)").matcher(t))
                .filter(java.util.regex.Matcher::find).map(m -> m.group(1)).findFirst().orElseThrow();
    }

    private static int indexOf(List<meko.agentic.goap.story.store.ContextStore.Turn> turns, java.util.function.Predicate<meko.agentic.goap.story.store.ContextStore.Turn> p) {
        for (int i = 0; i < turns.size(); i++) if (p.test(turns.get(i))) return i;
        return -1;
    }

    @Test
    void theUnderwriterChoosesThePolicyFromTheFileAndReadsTheModelsAnswerSafely() {
        var salaried = file(25_000, "salaried");
        var selfEmployed = file(25_000, "self-employed");
        var large = file(60_000, "salaried");
        String uw05 = "LEND-UW-05: Unsecured personal loans to salaried borrowers. Use this policy for unsecured personal loans up to 50,000 USD to salaried borrowers.";
        String uw09 = "LEND-UW-09: Use this policy only for self-employed borrowers: sole traders and contractors.";
        assertThat(meko.agentic.goap.story.agents.UnderwriterAgent.covers(uw05, salaried)).isTrue();
        assertThat(meko.agentic.goap.story.agents.UnderwriterAgent.covers(uw09, salaried)).isFalse();
        assertThat(meko.agentic.goap.story.agents.UnderwriterAgent.covers(uw09, selfEmployed)).isTrue();
        assertThat(meko.agentic.goap.story.agents.UnderwriterAgent.covers(uw05, large)).as("above the policy's amount limit").isFalse();
        assertThat(List.of("Approved.", "approve", "APPROVE")).allSatisfy(d -> assertThat(meko.agentic.goap.story.agents.UnderwriterAgent.normalize(d)).isEqualTo("approve"));
        assertThat(meko.agentic.goap.story.agents.UnderwriterAgent.normalize("Declined")).isEqualTo("decline");
        assertThat(meko.agentic.goap.story.agents.UnderwriterAgent.normalize("needs a human")).isEqualTo("refer");
    }

    private static meko.agentic.goap.story.agents.UnderwriterAgent.ApplicationFile file(int amount, String employment) {
        return new meko.agentic.goap.story.agents.UnderwriterAgent.ApplicationFile("c", null, "APP-1", "r", amount, 915, 7_400, employment, 4, 712, 1_850,
                8_000, "2026-03-03", 92, "June to September 2025", null, List.of(), null, false);
    }

    @Test
    void theCreditCallFollowsWhateverThresholdsThePolicyTextStates() {
        String text = "Credit score of at least 680. DTI up to 40% approve; above 40% and up to 45%\n   approve only with at least 3 months of reserves; above 45% decline.";
        var t = LendingThresholds.parse(text).orElseThrow();
        assertThat(t.apply(712, 38, 0)).isEqualTo("approve");
        assertThat(t.apply(712, 43, 4)).isEqualTo("approve");
        assertThat(t.apply(712, 43, 2)).isEqualTo("decline");
        assertThat(t.apply(650, 30, 6)).isEqualTo("decline");
        // DTI is compared exactly: 40.4% is above 40%, so it needs the reserves band, not a straight approval.
        assertThat(t.apply(712, 40.4, 2)).isEqualTo("decline");
        assertThat(t.apply(712, 40.0, 0)).isEqualTo("approve");
        // A stricter policy, the same figures: a different call.
        assertThat(LendingThresholds.parse("Credit score of at least 700; DTI up to 36% approve; above 36% decline.").orElseThrow().apply(712, 38, 4)).isEqualTo("decline");
        assertThat(LendingThresholds.parse("No thresholds here.")).isEmpty();
    }

    @Test
    void takeoverResumesFromTheCheckpoint() {
        StoryRun without = stories.run("agent-died-2am", false);
        StoryRun with = stories.run("agent-died-2am", true);
        assertThat(without.phases().getFirst().status()).isEqualTo("TERMINATED");
        assertThat(with.phases().getFirst().status()).isEqualTo("TERMINATED");
        assertThat(without.phases().get(1).status()).isEqualTo("COMPLETED");
        assertThat(with.phases().get(1).status()).isEqualTo("COMPLETED");
        assertThat(metric(without, "repeatedSteps")).isEqualTo(2);
        assertThat(metric(with, "repeatedSteps")).isZero();
        assertThat(metric(with, "minutes")).isLessThan(metric(without, "minutes"));
    }

    @Test
    void reviewRequestsEvidenceOnlyForWhatChanged() {
        StoryRun without = stories.run("what-changed", false);
        StoryRun with = stories.run("what-changed", true);
        assertCompleted(without, with);
        assertThat(executed(without)).contains("fullReview");
        assertThat(executed(with)).contains("diffAgainstPrior", "targetedEvidence");
        assertThat(metric(with, "evidenceRequests")).isEqualTo(1);
        assertThat(metric(without, "evidenceRequests")).isEqualTo(5);
    }

    @Test
    void richContextRoutesTheWriterToTheSmallModel() {
        StoryRun without = stories.run("what-changed", false);
        StoryRun with = stories.run("what-changed", true);
        assertThat(with.metrics().get("writerTier")).isEqualTo("slm");
        assertThat(without.metrics().get("writerTier")).isNotEqualTo("slm");
        assertThat(executed(with)).contains("writeWithSlm");
        assertThat(with.phases().getLast().agent()).isEqualTo("ResponseWriterAgent");
    }

    @Test
    void catchUpFindsTheOpenReviewRequest() {
        StoryRun without = stories.run("vacation-catch-up", false);
        StoryRun with = stories.run("vacation-catch-up", true);
        assertCompleted(without, with);
        assertThat(executed(without)).contains("reconstructManually", "keepExistingTests");
        assertThat(executed(with)).contains("addIdempotencyTest");
        assertThat(metric(without, "missedRequirements")).isEqualTo(1);
        assertThat(with.found()).extracting(StoryLedger.Found::writtenBy).contains("claude_code:payments-service", "pr-review-bot");
    }

    @Test
    void laterCasesReuseWhatTheFirstCaseLearned() {
        Map<String, List<StoryRun>> series = stories.learningSeries(2);
        assertThat(series.get("withoutMeko")).allSatisfy(r -> assertThat(executed(r)).contains("testHypotheses"));
        assertThat(executed(series.get("withMeko").get(0))).contains("testHypotheses");
        assertThat(executed(series.get("withMeko").get(1))).contains("validateKnownPattern").doesNotContain("testHypotheses");
    }

    @Test
    void anOutageDegradesToTheBaselinePathAndSaysWhy() {
        outage.set(true);
        try {
            StoryRun with = stories.run("dont-make-me-explain", true);
            assertThat(metric(with, "customerQuestions")).isEqualTo(3);
            assertThat(metric(with, "contextFailures")).isPositive();
            assertThat(with.receipts()).allSatisfy(r -> assertThat(r.ok()).isFalse());
        } finally {
            outage.set(false);
        }
    }

    @Test
    void aRetrievedPatternThatDoesNotFitIsRejected() {
        stories.resetLearning();
        StoryRun mismatch = stories.patternMismatch(true);
        // Retrieved, judged, and passed over with the reason; never counted as context used.
        assertThat(mismatch.found()).extracting(StoryLedger.Found::text).noneMatch(t -> t.contains("LEARNED PATTERN"));
        assertThat(mismatch.passedOver()).anyMatch(p -> p.text().contains("LEARNED PATTERN") && p.reason().contains("does not fit"));
        assertThat(metric(mismatch, "patternsRejected")).isEqualTo(1);
        assertThat(executed(mismatch)).contains("testHypotheses").doesNotContain("validateKnownPattern");
        assertThat(mismatch.decisions()).isNotEmpty();
    }

    @Test
    void theResolutionNoteSaysOnlyWhatTheRunDid() {
        // Without a context store nothing is retrieved, so the note must not claim a pattern was retrieved and rejected.
        StoryRun without = stories.patternMismatch(false);
        var task = (meko.agentic.goap.story.agents.ResponseWriterAgent.WritingTask) ledgers.forCase(without.caseId()).writingTask();
        assertThat(metric(without, "patternsRejected")).isZero();
        assertThat(task.facts()).noneMatch(f -> f.contains("retrieved but rejected"));
        // Hypotheses are counted as they are tested: the duplicate charge is confirmed first.
        assertThat(metric(without, "hypothesesTested")).isEqualTo(1);
        assertThat(task.facts()).anyMatch(f -> f.startsWith("Diagnosis came from testing 1 hypothesis"));

        StoryRun support = stories.run("dont-make-me-explain", false);
        var reply = (meko.agentic.goap.story.agents.ResponseWriterAgent.WritingTask) ledgers.forCase(support.caseId()).writingTask();
        assertThat(reply.facts()).anyMatch(f -> f.startsWith("After the handoff the customer had to be asked 3 times"));
        assertThat(reply.facts()).anyMatch(f -> f.contains("at CityMart"));
    }

    @Test
    void anAuditorGetsTheWholeRecordFromTheTransactionIdAlone() {
        for (ContextStores.Lane lane : ContextStores.Lane.values()) stories.run("dont-make-me-explain", lane);
        StoryRun without = stories.followUp("dont-make-me-explain", ContextStores.Lane.WITHOUT, StoryService.newCaseId("audit"));
        StoryRun db = stories.followUp("dont-make-me-explain", ContextStores.Lane.DATABASE, StoryService.newCaseId("audit"));
        StoryRun with = stories.followUp("dont-make-me-explain", ContextStores.Lane.MEKO, StoryService.newCaseId("audit"));
        assertCompleted(without, db, with);
        // With Meko one conversation_get by the transaction id returns the customer's account, the handoff, the checks and the result.
        assertThat(executed(with)).doesNotContain("requestVendorTranscript", "requestCaseNotes", "requestPolicy");
        assertThat(metric(with, "recordsRequested")).isZero();
        assertThat(with.found()).extracting(StoryLedger.Found::writtenBy).contains("vendor:helpbot", "support:payments-specialist");
        assertThat(with.receipts()).anyMatch(r -> r.tool().equals("conversation_get") && r.hits() > 0);
        // The record carries how each decision was made: the specialist's gate decisions are traced, with who decided.
        assertThat(with.found()).anyMatch(f -> f.writtenBy().equals("support:payments-specialist") && f.text().startsWith("decision support-policy: Which policy governs"));
        // Natural input and output, like the bot's turns: the question in, the answer out; the tag and who decided in the reasoning.
        var transaction = stories.lastSubject("dont-make-me-explain", ContextStores.Lane.MEKO).orElseThrow();
        var policyTrace = stores.forLane(ContextStores.Lane.MEKO).transcript("t", "t", transaction).stream()
                .filter(t -> t.decisionGate().filter("support-policy"::equals).isPresent()).findFirst().orElseThrow();
        assertThat(policyTrace.input()).startsWith("Which policy governs");
        assertThat(policyTrace.output()).contains("CARD-DISPUTE-07");
        assertThat(policyTrace.reasoning()).startsWith("support:payments-specialist · decision support-policy: decided by");
        // Decision traces exist only where an agent wrote them: Meko, or your own database for your own app's agents.
        assertThat(metric(with, "decisionTraces")).isPositive();
        assertThat(metric(without, "decisionTraces")).isZero();
        assertThat(with.outcome()).isNotBlank();
        var auditTask = (meko.agentic.goap.story.agents.ResponseWriterAgent.WritingTask) ledgers.forCase(with.caseId()).writingTask();
        assertThat(auditTask.facts()).anyMatch(f -> f.startsWith("Decision support-policy: ") && f.contains("decided by"));
        assertThat(auditTask.facts()).anyMatch(f -> f.matches("Decision traces on record: [1-9]\\d* from the payments specialist"));
        // Re-asks are dialogue turns marked in their reasoning, so the auditor counts them without a model.
        assertThat(auditTask.facts()).contains("Customer asked to repeat information after the handoff: 0 times");
        var withoutTask = (meko.agentic.goap.story.agents.ResponseWriterAgent.WritingTask) ledgers.forCase(without.caseId()).writingTask();
        assertThat(withoutTask.facts()).contains("Customer asked to repeat information after the handoff: 3 times");
        // Your own database holds your specialist's records, not the vendor's conversation or the policy text.
        assertThat(executed(db)).contains("requestVendorTranscript", "requestPolicy").doesNotContain("requestCaseNotes");
        assertThat(metric(db, "recordsRequested")).isEqualTo(2);
        // Without a shared record every piece has to be requested from the party that holds it.
        assertThat(executed(without)).contains("requestVendorTranscript", "requestCaseNotes", "requestPolicy");
        assertThat(metric(without, "recordsRequested")).isEqualTo(3);
        // The audit trail, entry by entry. With Meko: the specialist's decisions, read from the conversation.
        assertThat(with.auditTrail()).anyMatch(e -> e.kind().equals("decision") && e.who().equals("support:payments-specialist")
                && e.how().startsWith("Gate support-policy, decided by") && e.source().startsWith("conversation in"));
        assertThat(with.auditTrail().getLast()).satisfies(e -> {
            assertThat(e.kind()).isEqualTo("finding");
            assertThat(e.response()).contains("Handled in compliance with CARD-DISPUTE-07");
        });
        // Without Meko: outcomes requested from the parties, the re-asks flagged, and no decision of theirs on record.
        assertThat(without.auditTrail()).filteredOn(e -> e.issue().startsWith("The customer had to repeat information")).hasSize(3);
        assertThat(without.auditTrail()).filteredOn(e -> e.kind().equals("decision")).allMatch(e -> e.source().equals("this audit"));
        assertThat(without.auditTrail()).anyMatch(e -> e.source().equals("requested from the payments specialist team"));
        assertThat(without.auditTrail().getLast().issue()).contains("No decision traces on record");
        // Every lane reaches the same verdict from the same facts; only the cost differs.
        assertThat(List.of(without, db, with)).allSatisfy(r -> assertThat(r.outcome()).contains("Handled in compliance with CARD-DISPUTE-07"));
        assertThat(metric(with, "minutes")).isLessThan(metric(db, "minutes"));
        assertThat(metric(db, "minutes")).isLessThan(metric(without, "minutes"));
    }

    @Test
    void aFairLendingReviewerChecksTheCreditCallFromTheApplicationIdAlone() {
        stories.reset("loan-underwriting");
        for (ContextStores.Lane lane : ContextStores.Lane.values()) stories.run("loan-underwriting", lane);
        StoryRun without = stories.followUp("loan-underwriting", ContextStores.Lane.WITHOUT, stories.newFollowUpCaseId("loan-underwriting"));
        StoryRun db = stories.followUp("loan-underwriting", ContextStores.Lane.DATABASE, stories.newFollowUpCaseId("loan-underwriting"));
        StoryRun with = stories.followUp("loan-underwriting", ContextStores.Lane.MEKO, stories.newFollowUpCaseId("loan-underwriting"));
        assertCompleted(without, db, with);
        // With Meko one conversation_get by the application id returns the checks, the policy and the credit call.
        assertThat(executed(with)).doesNotContain("requestOriginationFile", "requestUnderwritingNotes", "requestPolicy");
        assertThat(metric(with, "recordsRequested")).isZero();
        assertThat(with.receipts()).anyMatch(r -> r.tool().equals("conversation_get") && r.hits() > 0);
        assertThat(with.found()).extracting(StoryLedger.Found::writtenBy).contains("vendor:loan-origination", "lending:underwriter");
        assertThat(with.found()).anyMatch(f -> f.writtenBy().equals("lending:underwriter") && f.text().startsWith("decision lending-credit-call: "));
        assertThat(metric(with, "decisionTraces")).isPositive();
        assertThat(metric(without, "decisionTraces")).isZero();
        var task = (meko.agentic.goap.story.agents.ResponseWriterAgent.WritingTask) ledgers.forCase(with.caseId()).writingTask();
        assertThat(task.facts()).contains("Hard inquiries on the borrower's credit file: 1", "Borrower asked to explain again what they explained at origination: 0 times",
                "Policy conditions cleared on documented evidence before the credit call: yes",
                "Credit call matches the policy's thresholds: yes", "Thresholds and factors recorded with the credit call: yes",
                "Credit call rests only on factors the policy names: yes", "Principal reasons recorded for a decline: not applicable");
        assertThat(task.facts()).anyMatch(f -> f.contains("10-day window for the employment verification cannot be checked"));
        var withoutTask = (meko.agentic.goap.story.agents.ResponseWriterAgent.WritingTask) ledgers.forCase(without.caseId()).writingTask();
        assertThat(withoutTask.facts()).contains("Hard inquiries on the borrower's credit file: 1", "Borrower asked to explain again what they explained at origination: 2 times",
                "Policy conditions cleared on documented evidence before the credit call: yes");
        assertThat(task.facts()).anyMatch(f -> f.contains("(deposit, employment gap) were each cleared on documented evidence before the credit call: 2 from the borrower's explanation at origination, 0 on"));
        assertThat(without.auditTrail()).anyMatch(e -> e.issue().startsWith("The borrower had to explain this again"));
        // Your own database holds your underwriter's records, not the vendor's file or the policy text.
        assertThat(executed(db)).contains("requestOriginationFile", "requestPolicy").doesNotContain("requestUnderwritingNotes");
        assertThat(executed(without)).contains("requestOriginationFile", "requestUnderwritingNotes", "requestPolicy");
        // Every lane reaches the same verdict from the same facts; only the cost differs.
        assertThat(List.of(without, db, with)).allSatisfy(r -> assertThat(r.outcome()).contains("Credit call made consistently with LEND-UW-05"));
        assertThat(metric(with, "minutes")).isLessThan(metric(db, "minutes"));
        assertThat(metric(db, "minutes")).isLessThan(metric(without, "minutes"));
        // The finding is filed where the next reader finds it: in the application's conversation, and as a result in memory.
        String application = stories.lastSubject("loan-underwriting", ContextStores.Lane.MEKO).orElseThrow().split(",")[0];
        assertThat(stores.forLane(ContextStores.Lane.MEKO).transcript("t", "t", application))
                .anyMatch(t -> t.isEvent("compliance:fair-lending-reviewer", "finding") && t.output().startsWith("Credit call made consistently with LEND-UW-05"));
        assertThat(with.receipts()).anyMatch(r -> r.tool().equals("memory_add") && r.agent().equals("compliance:fair-lending-reviewer")
                && r.detail().startsWith("Fair-lending review of " + application));
    }

    @Test
    void aReviewerChecksAKycReviewFromItsDecisionTraces() {
        for (ContextStores.Lane lane : ContextStores.Lane.values()) stories.run("what-changed", lane);
        StoryRun without = stories.followUp("what-changed", ContextStores.Lane.WITHOUT, stories.newFollowUpCaseId("what-changed"));
        StoryRun db = stories.followUp("what-changed", ContextStores.Lane.DATABASE, stories.newFollowUpCaseId("what-changed"));
        StoryRun with = stories.followUp("what-changed", ContextStores.Lane.MEKO, stories.newFollowUpCaseId("what-changed"));
        assertCompleted(without, db, with);
        // With Meko the review's conversation holds the analyst's record, its decision traces and the standard is in knowledge.
        assertThat(executed(with)).doesNotContain("requestWorkingPapers", "requestStandard");
        assertThat(metric(with, "recordsRequested")).isZero();
        assertThat(with.outcome()).contains("Reviewed in line with KYC-STD-04");
        var task = (meko.agentic.goap.story.agents.ResponseWriterAgent.WritingTask) ledgers.forCase(with.caseId()).writingTask();
        assertThat(task.facts()).contains("Compared against the latest review of this customer: yes", "Evidence proportionate to the material changes: yes");
        assertThat(task.facts()).anyMatch(f -> f.startsWith("Decision kyc-materiality: ") && f.contains("decided by"));
        assertThat(metric(with, "decisionTraces")).isPositive();
        assertThat(metric(without, "decisionTraces")).isZero();
        assertThat(task.facts()).anyMatch(f -> f.startsWith("Passed over: outdated"));
        // Your own database holds the analyst's record, not the standard; without one, both are requested.
        assertThat(executed(db)).contains("requestStandard").doesNotContain("requestWorkingPapers");
        assertThat(executed(without)).contains("requestWorkingPapers", "requestStandard");
        // Without a prior review the analyst re-verified everything, which is what the standard requires then.
        var withoutTask = (meko.agentic.goap.story.agents.ResponseWriterAgent.WritingTask) ledgers.forCase(without.caseId()).writingTask();
        assertThat(withoutTask.facts()).anyMatch(f -> f.contains("full re-review"));
        assertThat(metric(with, "minutes")).isLessThan(metric(db, "minutes"));
        assertThat(metric(db, "minutes")).isLessThan(metric(without, "minutes"));
    }

    @Test
    void everyStoryTracesItsGateDecisionsToItsCaseConversation() {
        for (String story : List.of("agent-died-2am", "what-changed", "vacation-catch-up")) {
            StoryRun run = stories.run(story, true);
            assertThat(run.receipts()).as(story).anyMatch(r -> r.tool().equals("conversation_add_message") && r.detail().contains("· decision "));
        }
        // Story 5: the second case judges the learned pattern; that judgement is traced, and cited in its resolution note.
        StoryRun second = stories.learningSeries(2, false).get("withMeko").get(1);
        assertThat(second.receipts()).anyMatch(r -> r.tool().equals("conversation_add_message") && r.detail().contains("· decision payments-pattern-fit"));
        var note = (meko.agentic.goap.story.agents.ResponseWriterAgent.WritingTask) ledgers.forCase(second.caseId()).writingTask();
        assertThat(note.facts()).anyMatch(f -> f.startsWith("Pattern-fit decision: ") && f.contains("decided by"));
    }

    @Autowired
    StoryCatalog catalog;

    @Test
    void everyAgentInACastActsInItsStory() {
        stories.resetLearning();
        for (StoryCatalog.Story story : catalog.all()) {
            List<StoryRun> runs = new java.util.ArrayList<>(story.id().equals("organization-learned")
                    ? stories.learningSeries(2, false).get("withMeko") : List.of(stories.run(story.id(), ContextStores.Lane.MEKO)));
            if (story.followUp() != null) runs.add(stories.followUp(story.id(), ContextStores.Lane.MEKO, stories.newFollowUpCaseId(story.id())));
            assertThat(story.cast()).as(story.id()).isNotEmpty();
            for (StoryCatalog.CastMember m : story.cast()) {
                if (m.when().equals("earlier")) {
                    // An agent from another system acted before this run: what it wrote is used.
                    assertThat(runs).as(story.id() + " uses what " + m.id() + " wrote")
                            .anyMatch(r -> r.found().stream().anyMatch(f -> f.writtenBy().equals(m.id())));
                } else {
                    assertThat(runs).as(story.id() + ": " + m.id() + " calls Meko")
                            .anyMatch(r -> r.receipts().stream().anyMatch(x -> x.agent().equals(m.id())));
                }
            }
        }
    }

    @Test
    void aHandoffSurvivesAFailedMemoryWriteByReadingTheConversation() {
        outage.failMemoryWrites(true);
        try {
            // The origination system's memory write fails; the underwriter reads the explanations from the application's conversation.
            StoryRun loan = stories.run("loan-underwriting", ContextStores.Lane.MEKO);
            assertThat(loan.steps()).anyMatch(st -> st.text().startsWith("The write of the borrower's explanations to Meko failed: memory_add failed: simulated failure"));
            assertThat(metric(loan, "borrowerReexplained")).isZero();
            assertThat(executed(loan)).doesNotContain("issueConditions");
            assertThat(loan.found()).anyMatch(f -> f.source().equals("application conversation") && f.writtenBy().equals("vendor:loan-origination"));
            // The dead investigator's checkpoint memory fails; the takeover reads what it finished from the incident's conversation.
            StoryRun incident = stories.run("agent-died-2am", true);
            assertThat(metric(incident, "repeatedSteps")).isZero();
            assertThat(incident.found()).anyMatch(f -> f.source().equals("incident conversation"));
        } finally {
            outage.failMemoryWrites(false);
        }
    }

    @Test
    void runsReportCost() {
        StoryRun run = stories.run("what-changed", true);
        assertThat(run.metrics()).containsKeys("modelUsd", "effortUsd", "hourlyRateUsd", "decisions");
        assertThat(((Number) run.metrics().get("effortUsd")).doubleValue()).isPositive();
    }

    @Test
    void distractorsDoNotFoolTheAgents() {
        StoryRun kyc = stories.run("what-changed", true);
        assertThat(kyc.found()).extracting(StoryLedger.Found::text)
                .anyMatch(t -> t.contains("2026-04-14"))
                .noneMatch(t -> t.contains("2025-10-03") || t.contains("CUST-7789"));
        // The look-alikes were retrieved and are named, with the reason they were not used.
        assertThat(kyc.passedOver()).extracting(StoryLedger.Passed::reason)
                .anyMatch(r -> r.startsWith("outdated"))
                .anyMatch(r -> r.contains("different customer"));
        assertThat(metric(kyc, "evidenceRequests")).isEqualTo(1);

        StoryRun catchUp = stories.run("vacation-catch-up", true);
        assertThat(executed(catchUp)).contains("addIdempotencyTest");
        assertThat(catchUp.outcome()).contains("idempotency").doesNotContain("load test");
        assertThat(catchUp.passedOver()).extracting(StoryLedger.Passed::reason).anyMatch(r -> r.contains("PR-4812"));

        StoryRun support = stories.run("dont-make-me-explain", true);
        assertThat(support.outcome()).contains("CARD-DISPUTE-07");
        assertThat(support.passedOver()).anyMatch(p -> p.text().contains("CARD-DISPUTE-03"));
    }

    @Test
    void yourOwnDatabaseCannotSeeWhatOtherSystemsWrote() {
        var db = ContextStores.Lane.DATABASE;
        // The vendor chatbot wrote the handoff facts into its own store: your specialist asks again.
        StoryRun support = stories.run("dont-make-me-explain", db);
        assertThat(support.store()).isEqualTo("SHARED_DB");
        assertThat(metric(support, "customerQuestions")).isEqualTo(3);
        // The payments team's agent checkpointed into the payments team's application: your platform agent redoes it.
        assertThat(metric(stories.run("agent-died-2am", db), "repeatedSteps")).isEqualTo(2);
        // Last quarter's analyst tool, a Claude Code session, the PR bot.
        assertThat(executed(stories.run("what-changed", db))).contains("fullReview");
        assertThat(metric(stories.run("vacation-catch-up", db), "missedRequirements")).isEqualTo(1);
        // The EU team's lesson stays in the EU team's database; your app rediscovers it once, then reuses its own copy.
        var series = stories.learningSeries(3, true);
        assertThat(series.get("database").get(0).store()).isEqualTo("OTHER_SYSTEM_DB");
        assertThat(executed(series.get("database").get(1))).contains("testHypotheses");
        assertThat(executed(series.get("database").get(2))).contains("validateKnownPattern");
        assertThat(executed(series.get("withMeko").get(1))).contains("validateKnownPattern");
        // The same agents, both sides on Meko: no re-asks, nothing repeated.
        assertThat(metric(stories.run("dont-make-me-explain", true), "customerQuestions")).isZero();
        assertThat(metric(stories.run("agent-died-2am", true), "repeatedSteps")).isZero();
    }

    private static void assertCompleted(StoryRun... runs) {
        for (StoryRun run : runs) {
            assertThat(run.phases()).allSatisfy(p -> assertThat(p.status()).isEqualTo("COMPLETED"));
        }
    }

    private static List<String> executed(StoryRun run) {
        return run.phases().stream().flatMap(p -> p.executed().stream()).map(PlanRecorder.Executed::action).toList();
    }

    private static int metric(StoryRun run, String key) {
        return ((Number) run.metrics().getOrDefault(key, 0)).intValue();
    }
}
