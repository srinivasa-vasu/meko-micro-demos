package meko.agentic.release.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunSelectionTest {

    private static RecoveryModels.Marker run(String id, String started, String finished, int rows) {
        return new RecoveryModels.Marker("nightly-reprice", id, "0/" + id, started, finished == null ? null : "0/after", finished, rows,
                "text", "platform-batch:scheduler-agent", "org");
    }

    @Test
    void theLatestRunBeforeTheIncidentWinsWhateverOrderTheSearchReturned() {
        var older = run("r-1", "2026-11-09T06:50:32", "2026-11-09T06:50:33", 7500);
        var newer = run("r-2", "2026-11-09T07:15:52", "2026-11-09T07:15:53", 7700);
        // The search ranks by relevance: the older run can come last.
        assertThat(ReleaseMemory.runBefore(List.of(newer, older), "2026-11-09T07:15:55").runId()).isEqualTo("r-2");
        assertThat(ReleaseMemory.runBefore(List.of(older, newer), "2026-11-09T07:15:55").runId()).isEqualTo("r-2");
    }

    @Test
    void aRunThatFinishedAfterTheIncidentWasDetectedIsNotTheCause() {
        var cause = run("r-1", "2026-11-09T07:00:00", "2026-11-09T07:00:01", 7500);
        var later = run("r-2", "2026-11-09T07:10:00", "2026-11-09T07:10:01", 10);
        assertThat(ReleaseMemory.runBefore(List.of(cause, later), "2026-11-09T07:05:00").runId()).isEqualTo("r-1");
    }

    @Test
    void aCompletedRunIsPreferredToAMarkerWithoutAnEnd() {
        var legacy = run("", "2026-11-09T07:20:00", null, -1);
        var completed = run("r-2", "2026-11-09T07:15:52", "2026-11-09T07:15:53", 7700);
        assertThat(ReleaseMemory.runBefore(List.of(legacy, completed), "2026-11-09T07:30:00").runId()).isEqualTo("r-2");
        assertThat(ReleaseMemory.runBefore(List.of(legacy), "2026-11-09T07:30:00").startedAt()).isEqualTo("2026-11-09T07:20:00");
    }

    @Test
    void damageAccountsForTheRunOrIsHeld() {
        var exact = new RecoveryModels.Damage("run", java.util.Map.of(1L, "PAID", 2L, "PAID"), 5, 2, 1, "0/1", 1, 2, java.util.Map.of());
        var short1 = new RecoveryModels.Damage("run", java.util.Map.of(1L, "PAID"), 5, 2, 1, "0/1", 1, 2, java.util.Map.of());
        var unknown = new RecoveryModels.Damage("run", java.util.Map.of(1L, "PAID"), 5, 2, 1, "0/1", 1, -1, java.util.Map.of());
        assertThat(exact.accounted()).isTrue();
        assertThat(short1.accounted()).isFalse();
        assertThat(unknown.accounted()).isTrue();
    }
}
