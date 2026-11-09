package meko.agentic.release.agent;

import meko.agentic.release.amp.AmpDatabase;

import java.util.List;
import java.util.Map;

/** What passes between the recovery agent's actions: each is a fact on the planner's blackboard. */
public final class RecoveryModels {
    private RecoveryModels() {}

    /**
     * Recover from an incident. {@code readShared}: whether the organization's shared datapack is shared with the
     * recovery team yet (the first stage runs without it).
     */
    public record RecoveryRequest(String runId, String incidentId, boolean readShared, String cidr) {}

    /** A fact from memory: what it says, who wrote it, in which datapack, and its time (for an incident, when it was detected). */
    public record Fact(String text, String writtenBy, String datapack, String at) {}

    /**
     * A run of a scheduled job, from its scheduler's markers: the start marker (run id, start time, the WAL position just
     * before it) and, once it committed, the end marker (finish time, the position after it, the rows it changed).
     * {@code finishedAt} null and {@code rowsChanged} -1: no end marker (a run still going, or one recorded before runs had ids).
     */
    public record Marker(String job, String runId, String lsn, String startedAt, String lsnAfter, String finishedAt, int rowsChanged,
                         String text, String writtenBy, String datapack) {
        public boolean completed() {
            return finishedAt != null;
        }
    }

    /** {@code queries}: the critical queries on orders that teams registered, which the repair's checks run (REC-03). */
    public record IncidentContext(String runId, String incidentId, boolean readShared, Fact incident, Marker marker,
                                  List<Marker> markers, Map<String, ReleaseModels.Rule> runbook, List<ReleaseModels.CriticalQuery> queries,
                                  List<String> sources) {
        public boolean knows(String ruleId) {
            return runbook.containsKey(ruleId);
        }
    }

    /** The branch forked as of the marker's position: main as it was just before the job. */
    public record ForensicBranch(String runId, AmpDatabase.OpenBranch open) {}

    /**
     * The rows the job changed, by comparing the fork with main now: each id with its value before the job. Rows pending
     * now that were pending before the job ({@code genuine}) and rows created after it ({@code createdAfter}) are left alone.
     * {@code criticalBefore}: what each critical query returned on the fork, as of before the job, by query name.
     */
    public record Damage(String runId, Map<Long, String> before, int pendingNow, int genuine, int createdAfter, String lsn, long forkMillis,
                         int expected, Map<String, List<String>> criticalBefore) {
        /** Whether the damage found accounts for exactly the rows the job's run reported changing (unknown: -1, taken as yes). */
        public boolean accounted() {
            return expected < 0 || expected == before.size();
        }
    }

    public record ForensicClosed(String runId) {}

    /** The repair, rehearsed on a fresh branch of main: what it changed and what the checks found. */
    public record RepairRehearsal(String runId, boolean passed, int repaired, long millis, List<String> checks) {}

    public enum Verdict { RECOVERED, REPAIR_HELD, NOT_RECOVERED }

    /** How the incident ended: the repair applied, held back, or impossible without a position to compare against. */
    public record Outcome(String runId, Verdict verdict, int repaired, int pendingNow, List<String> checks, Map<String, Integer> countsAfter) {}

    public record RecoveryDecision(String runId, String incidentId, Verdict verdict, List<String> reasons, String evidenceHash) {}
}
