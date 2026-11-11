package meko.agentic.goap.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunJobsTest {

    @Test
    void aPageThatFollowsLateGetsEveryEventOnceThenTheRest() {
        RunJobs jobs = new RunJobs();
        RunJobs.Job job = jobs.start("what-changed", "what-changed", 0, "run");
        jobs.emit(job, "lanes", "a");
        jobs.emit(job, "plan", "b");
        List<String> seen = new ArrayList<>();
        jobs.follow(job, (type, payload) -> seen.add(type));
        jobs.emit(job, "run", "c");
        jobs.finish(job);
        assertThat(seen).containsExactly("lanes", "plan", "run", "done");
    }

    @Test
    void aFinishedRunReplaysAndEndsAtOnce() {
        RunJobs jobs = new RunJobs();
        RunJobs.Job job = jobs.start("what-changed", "what-changed", 0, "run");
        jobs.emit(job, "run", "c");
        jobs.finish(job);
        List<String> seen = new ArrayList<>();
        jobs.follow(job, (type, payload) -> seen.add(type));
        assertThat(seen).containsExactly("run", "done");
    }

    @Test
    void aRunIsPendingUntilItsResultIsRecorded() {
        RunJobs jobs = new RunJobs();
        RunJobs.Job job = jobs.start("what-changed", "what-changed", 0, "run");
        assertThat(jobs.pending()).isSameAs(job);          // running
        jobs.finish(job);
        assertThat(jobs.pending()).isSameAs(job);          // finished, not recorded: a page picks it up
        jobs.recorded(job.id());
        assertThat(jobs.pending()).isNull();
        assertThat(job.describe()).containsEntry("stage", 0).containsEntry("useCase", "what-changed");
    }
}
