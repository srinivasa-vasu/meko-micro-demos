package meko.agentic.goap.story;

import meko.agentic.goap.story.store.ContextStores;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The workshop's stages, on the stand-in: it starts empty, and each step changes what the Meko lane does, as the
 * workshop guide tells participants to look for.
 */
@SpringBootTest(properties = {"meko.enabled=false"})
@ActiveProfiles("workshop")
class WorkshopStagesTest {
    @Autowired StoryService stories;
    @Autowired StorySeeder seeder;
    @Autowired Workshop workshop;

    private static List<String> executed(StoryRun run) {
        return run.phases().stream().flatMap(p -> p.executed().stream()).map(PlanRecorder.Executed::action).toList();
    }

    private static int metric(StoryRun run, String key) {
        return ((Number) run.metrics().getOrDefault(key, 0)).intValue();
    }

    @Test
    void eachStageChangesWhatTheMekoLaneDoes() {
        assertThat(workshop.enabled()).isTrue();
        assertThat(workshop.useCases()).extracting(Workshop.UseCase::id)
                .containsExactly("dont-make-me-explain", "what-changed", "organization-learned");

        // Stage 0, empty: memory written during the run already carries the handoff; the policy is not in reach.
        assertThat(seeder.documentStatus(workshop.documents())).allSatisfy((k, v) -> assertThat(v).isEqualTo("missing"));
        assertThat(seeder.historyStatus(workshop.historyUseCases())).allSatisfy((k, v) -> assertThat(v).isEqualTo("missing"));
        StoryRun dispute0 = stories.run("dont-make-me-explain", ContextStores.Lane.MEKO);
        assertThat(metric(dispute0, "customerQuestions")).isZero();
        assertThat(executed(dispute0)).contains("escalateForPolicy");
        // Nobody answers an escalation from a policy nobody can find: the case goes to a person, undecided.
        assertThat(dispute0.outcome()).startsWith("No refund decided");
        // Each KYC run starts a new review cycle (as the workshop page does), so it never compares with its own last run.
        stories.reset("what-changed");
        StoryRun kyc0 = stories.run("what-changed", ContextStores.Lane.MEKO);
        assertThat(executed(kyc0)).contains("fullReview");
        assertThat(dispute0.metrics().get("modelCalls")).isEqualTo(0);

        // Stage 1, documents: the policy is found in knowledge; the KYC review still has no prior review to compare with.
        seeder.addDocuments(workshop.documents());
        assertThat(seeder.documentStatus(workshop.documents())).allSatisfy((k, v) -> assertThat(v).isEqualTo("uploaded"));
        StoryRun dispute1 = stories.run("dont-make-me-explain", ContextStores.Lane.MEKO);
        assertThat(executed(dispute1)).doesNotContain("escalateForPolicy");
        assertThat(dispute1.outcome()).startsWith("Provisional refund").contains("CARD-DISPUTE-07");
        stories.reset("what-changed");
        assertThat(executed(stories.run("what-changed", ContextStores.Lane.MEKO))).contains("fullReview");

        // Stage 2, history: last quarter's review, written by another desk's agent, is there to compare with.
        seeder.seedHistory(workshop.historyUseCases());
        assertThat(seeder.historyStatus(workshop.historyUseCases())).allSatisfy((k, v) -> assertThat(v).isEqualTo("seeded"));
        stories.reset("what-changed");
        StoryRun kyc2 = stories.run("what-changed", ContextStores.Lane.MEKO);
        assertThat(executed(kyc2)).doesNotContain("fullReview").contains("diffAgainstPrior");
        assertThat(metric(kyc2, "evidenceRequests")).isLessThan(metric(kyc0, "evidenceRequests"));
    }

    @Test
    void theLearningUseCaseRunsTheSameCaseWithoutAndWithAnotherTeamsLesson() {
        // Step 1: your investigator alone, in a new epoch: nothing promoted is in reach, so it tests hypotheses.
        StoryRun without = (StoryRun) stories.learningCase(1, null, false, false).get("run");
        assertThat(executed(without)).contains("testHypotheses").doesNotContain("validateKnownPattern");
        // The baseline promotes nothing: the only lesson in the datapack is the one the other team shares.
        assertThat(without.written()).noneMatch(w -> w.kind().equals("promoted"));
        // Step 2: the other team's agent solves case 1 and promotes the root cause, in an epoch of its own.
        var caseOne = stories.learningCase(0, null, true);
        String epoch = (String) caseOne.get("epoch");
        // The pair exercise's partner finds that lesson from the datapack alone, without the epoch from case 1's browser.
        assertThat(stories.latestLesson()).containsEntry("epoch", epoch);
        // The run carries what it wrote, in full: its records in the case's conversation and the lesson it promoted.
        var written = ((StoryRun) caseOne.get("run")).written();
        assertThat(written).anySatisfy(w -> assertThat(w.kind()).isEqualTo("record"));
        assertThat(written).anySatisfy(w -> {
            assertThat(w.kind()).isEqualTo("promoted");
            assertThat(w.text()).contains(meko.agentic.goap.story.agents.PaymentFailureAgent.PATTERN_MARKER);
        });
        // Step 3: the same case as step 1, in case 1's epoch: the promoted root cause replaces the hypotheses.
        StoryRun with = (StoryRun) stories.learningCase(1, epoch, false).get("run");
        assertThat(executed(with)).contains("validateKnownPattern").doesNotContain("testHypotheses");
        assertThat(metric(with, "hypothesesTested")).isLessThan(metric(without, "hypothesesTested"));
        assertThat(metric(with, "minutes")).isLessThan(metric(without, "minutes"));
    }

    @Test
    void theWorkshopsPolicyExerciseTakesThePolicyOutOfScope() throws Exception {
        // Exercise 1 changes CARD-DISPUTE-07's scope from a capture that timed out to one that was declined: it no longer
        // covers this case, so the specialist finds no policy that does.
        String text = new String(getClass().getResourceAsStream("/kb/card-dispute-07-failed-capture-policy.md").readAllBytes());
        var check = new meko.agentic.goap.story.agents.PaymentsSpecialistAgent.SettlementCheck("c", true, false, false);
        assertThat(meko.agentic.goap.story.agents.PaymentsSpecialistAgentAccess.covers(text, check)).isTrue();
        assertThat(meko.agentic.goap.story.agents.PaymentsSpecialistAgentAccess.covers(
                text.replace("timed out at the gateway", "was declined by the acquirer"), check)).isFalse();
    }
}
