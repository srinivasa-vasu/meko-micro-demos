package meko.agentic.release.agent;

import meko.agentic.release.amp.AmpDatabase;
import meko.agentic.release.rehearsal.MigrationScript;
import meko.agentic.release.rehearsal.RehearsalRunner;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** What passes between the release agent's actions: each is a fact on the planner's blackboard. */
public final class ReleaseModels {
    private ReleaseModels() {}

    /** Rehearse a pull request's migrations. {@code runId} names this run's log. */
    public record RehearsalRequest(String runId, String prId) {}

    /** A rule from a knowledge base, by id, with the text the search returned and the datapack it came from. */
    public record Rule(String id, String text, String datapack) {}

    /** Context from memory keeps who wrote it ({@code writtenBy}, an agent) and where ({@code datapack}). */
    public record Incident(String id, String text, String writtenBy, String datapack, String workload) {}

    public record CriticalQuery(String name, String sql, String writtenBy, String datapack) {}

    /** A memory the search returned that does not apply here, and why. */
    public record SetAside(String text, String writtenBy, String datapack, String reason) {}

    /**
     * Everything the agent knows before it touches a database. Limits come from the knowledge base's text when it has
     * them, read from the rule that states each: {@code lockTimeout} null, or a number 0, means no rule found states it.
     */
    public record ReleaseContext(String runId, String prId, Map<String, Rule> rules, String lockTimeout, int maxAttempts, long stallMillis,
                                 long regressionFactor, List<Incident> incidents, List<CriticalQuery> queries, List<SetAside> setAside) {
        public boolean knows(String ruleId) {
            return rules.containsKey(ruleId);
        }

        public boolean reportingJobKnown() {
            return reportingIncident() != null;
        }

        /** The incident that names the reporting job's long transaction, or null. */
        public Incident reportingIncident() {
            return incidents.stream().filter(i -> "reporting-long-transaction".equals(i.workload())).findFirst().orElse(null);
        }
    }

    /** A statement of the pull request that breaks a known rule, and what the rule asks for instead. */
    public record Finding(String script, int statement, String sql, String ruleId, String problem) {}

    /** The migrations to rehearse: the pull request as written, or rewritten to the standard. */
    public record ReleasePlan(String runId, String prId, boolean rewritten, List<MigrationScript> scripts,
                              List<MigrationScript> rollback, List<Finding> findings) {}

    public record RehearsalBranch(String runId, AmpDatabase.OpenBranch open) {}

    /** The fork's state before the migrations: the schema a rollback must restore, and the critical queries' plans. */
    public record Baseline(String runId, Set<String> schema, List<RehearsalRunner.QueryPlan> plans) {}

    public record Rehearsal(String runId, RehearsalRunner.Result result) {}

    public record PlanComparison(RehearsalRunner.QueryPlan before, RehearsalRunner.QueryPlan after, boolean regressed) {}

    /** The critical queries' plans after the migrations; {@code skipped} when no critical query was known. */
    public record PlanCheck(String runId, boolean skipped, List<PlanComparison> comparisons) {
        public List<PlanComparison> regressions() {
            return comparisons.stream().filter(PlanComparison::regressed).toList();
        }
    }

    /** The rollback's rehearsal; {@code skipped} when no rule required one. */
    public record RollbackCheck(String runId, boolean skipped, RehearsalRunner.Result result, boolean restored,
                                List<String> missing, List<String> extra) {}

    public record BranchDeleted(String runId, String branch) {}

    public enum Verdict { APPROVED, APPROVED_WITH_CHANGES, CHANGES_REQUESTED, NOT_DECIDED }

    public record ReleaseDecision(String runId, String prId, Verdict verdict, List<String> reasons, List<String> changes,
                                  String evidenceHash) {}
}
