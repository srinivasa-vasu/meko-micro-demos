package meko.agentic.release.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.common.PlannerType;
import meko.agentic.release.agent.ReleaseModels.Baseline;
import meko.agentic.release.agent.ReleaseModels.BranchDeleted;
import meko.agentic.release.agent.ReleaseModels.CriticalQuery;
import meko.agentic.release.agent.ReleaseModels.Finding;
import meko.agentic.release.agent.ReleaseModels.PlanCheck;
import meko.agentic.release.agent.ReleaseModels.PlanComparison;
import meko.agentic.release.agent.ReleaseModels.Rehearsal;
import meko.agentic.release.agent.ReleaseModels.RehearsalBranch;
import meko.agentic.release.agent.ReleaseModels.RehearsalRequest;
import meko.agentic.release.agent.ReleaseModels.ReleaseContext;
import meko.agentic.release.agent.ReleaseModels.ReleaseDecision;
import meko.agentic.release.agent.ReleaseModels.ReleasePlan;
import meko.agentic.release.agent.ReleaseModels.RollbackCheck;
import meko.agentic.release.agent.ReleaseModels.Verdict;
import meko.agentic.release.amp.AmpDatabase;
import meko.agentic.release.rehearsal.MigrationScript;
import meko.agentic.release.rehearsal.RehearsalRunner;
import meko.agentic.release.run.Jobs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Release management's agent: proves a pull request's migrations on {@code orders} safe before the change board sees
 * them. It forks {@code main}, rehearses on the fork under production's workloads, checks what it knows to check, and
 * records the evidence and its decision on the pull request's record. What it knows to check comes from the datapack:
 * the standard and policy (shared knowledge), and other teams' incidents and critical queries (their memory).
 * <p>
 * Deterministic: no action prompts a model. The planner chooses between the plan-check and rollback actions and their
 * skips by what the context holds.
 */
@Agent(planner = PlannerType.GOAP, description = "Rehearses a pull request's migrations on an AMP branch and decides whether they are safe to release")
public class ReleaseRehearsalAgent {
    /** The pull request in this demo: "Buy online, pick up in store", two migrations on orders. */
    public static final List<String> AS_WRITTEN = List.of("release-1-as-written.sql", "release-2-as-written.sql");
    public static final List<String> REWRITTEN = List.of("release-1-safe.sql", "release-2-safe.sql");
    /** Rolled back newest first. */
    public static final List<String> ROLLBACK = List.of("release-2-rollback.sql", "release-1-rollback.sql");
    /**
     * Below this many buffers a plan is too small to call a regression, whatever the factor: a noise floor, not a policy.
     */
    static final long MIN_REGRESSION_BUFFERS = 100;
    /**
     * The reporting job's long transaction, compressed: the incident names a transaction of up to 40 minutes; the
     * rehearsal holds one for long enough that every statement of the release meets it.
     */
    static final int REPORTING_HOLD_SECONDS = 15;

    /** The request, with the address AMP's allow-list admits for the rehearsal branch's endpoint. */
    public record Run(RehearsalRequest request, String cidr) {}

    private final AmpDatabase database;
    private final ReleaseMemory memory;
    private final Jobs jobs;
    private final Progress progress;

    public ReleaseRehearsalAgent(AmpDatabase database, ReleaseMemory memory, Jobs jobs, Progress progress) {
        this.database = database;
        this.memory = memory;
        this.jobs = jobs;
        this.progress = progress;
    }

    private Jobs.Job job(String runId) {
        return jobs.get(runId);
    }

    @Action(description = "Recall the migration standard, the release policy, and other teams' incidents and critical queries",
            post = {"contextRecalled", "queriesKnown", "queriesUnknown", "rollbackRequired", "rollbackNotRequired"},
            cost = 0.05, canRerun = false)
    public ReleaseContext recallContext(Run run) throws Exception {
        RehearsalRequest request = run.request();
        Jobs.Job job = job(request.runId());
        ReleaseContext context = memory.recall(request.runId(), request.prId(), job::log);
        job.event("context", "What the agent knows before it touches a database", context);
        memory.note(request.prId(), request.runId(), "context",
                "Recall the standard, the policy, and other teams' incidents and critical queries on orders",
                "Rules: " + (context.rules().isEmpty() ? "none" : String.join(", ", context.rules().keySet()))
                        + ". Incidents: " + context.incidents().stream().map(i -> i.id() + " (" + i.writtenBy() + ", in " + i.datapack() + ")").toList()
                        + ". Critical queries: " + context.queries().stream().map(q -> q.name() + " (" + q.writtenBy() + ", in " + q.datapack() + ")").toList()
                        + ". Set aside: " + context.setAside().stream().map(s -> s.reason()).toList() + ".");
        return context;
    }

    @Action(description = "Review the pull request's migrations against the rules found, and rewrite them if the standard asks",
            pre = {"contextRecalled"}, post = {"reviewed"}, cost = 0.05, canRerun = false)
    public ReleasePlan reviewMigrations(ReleaseContext context) throws Exception {
        Jobs.Job job = job(context.runId());
        List<MigrationScript> asWritten = scripts(AS_WRITTEN);
        List<Finding> findings = MigrationReview.review(asWritten, context);
        boolean rewrite = !findings.isEmpty();
        List<MigrationScript> scripts = rewrite ? scripts(REWRITTEN) : asWritten;
        ReleasePlan plan = new ReleasePlan(context.runId(), context.prId(), rewrite, scripts, scripts(ROLLBACK), findings, asWritten);
        job.event("review", findings.isEmpty()
                ? (context.rules().isEmpty() ? "No standard to review against: the migrations are rehearsed as written."
                        : "The migrations break none of the rules found: rehearsed as written.")
                : findings.size() + " finding(s) against the standard: the migrations are rehearsed as rewritten to it.", plan);
        memory.note(context.prId(), context.runId(), "review", "Review the migrations against the standard",
                findings.isEmpty() ? "No findings" + (context.rules().isEmpty() ? " (no standard found)" : "") + "; rehearsing as written."
                        : findings.stream().map(f -> f.ruleId() + " " + f.script() + " #" + f.statement() + ": " + f.problem()).toList()
                        + "; rehearsing the rewritten migrations.");
        return plan;
    }

    @Action(description = "Fork main into a rehearsal branch and give it a writer endpoint",
            pre = {"reviewed"}, post = {"branchReady"}, cost = 0.30, canRerun = false)
    public RehearsalBranch forkBranch(ReleasePlan plan, Run run) throws Exception {
        Jobs.Job job = job(plan.runId());
        String name = ("rehearse-" + plan.prId() + "-" + plan.runId()).toLowerCase().replaceAll("[^a-z0-9-]", "-");
        AmpDatabase.OpenBranch open = database.openBranch(name, run.cidr(), job::log);
        job.opened(open);
        job.event("branch", "Rehearsal branch " + name + " is ready", Map.of("name", name, "forkMillis", open.forkMillis(),
                "readyMillis", open.readyMillis(), "forkLsn", String.valueOf(open.branch().forkLsn()), "endpoint", open.endpoint().address()));
        memory.note(plan.prId(), plan.runId(), "branch", "Fork main for the rehearsal",
                "Forked main as " + name + " at LSN " + open.branch().forkLsn() + " in " + open.forkMillis() + " ms; its endpoint answered after "
                        + open.readyMillis() / 1000 + " s.");
        return new RehearsalBranch(plan.runId(), open);
    }

    @Action(description = "Record the fork's schema and the critical queries' plans before anything changes",
            pre = {"branchReady"}, post = {"baselineTaken"}, cost = 0.05, canRerun = false)
    public Baseline takeBaseline(RehearsalBranch branch, ReleaseContext context) throws Exception {
        Jobs.Job job = job(branch.runId());
        Set<String> schema = RehearsalRunner.schema(branch.open().endpoint());
        List<RehearsalRunner.QueryPlan> plans = new ArrayList<>();
        for (CriticalQuery query : context.queries()) plans.add(RehearsalRunner.explain(branch.open().endpoint(), query.name(), query.sql()));
        job.event("baseline", "Before the migrations: " + schema.size() + " schema entries on orders"
                + (plans.isEmpty() ? "; no critical query known." : "; " + plans.size() + " critical query plan(s)."), plans);
        return new Baseline(branch.runId(), schema, plans);
    }

    @Action(description = "Run the migrations on the branch under checkout's writes and every long transaction an incident names",
            pre = {"baselineTaken"}, post = {"rehearsed"}, cost = 0.40, canRerun = false)
    public Rehearsal rehearseMigrations(RehearsalBranch branch, ReleasePlan plan, ReleaseContext context) throws Exception {
        Jobs.Job job = job(branch.runId());
        boolean reporting = context.reportingJobKnown();
        job.log("Workloads: checkout inserting orders" + (reporting
                ? ", and the nightly reporting job's long transaction (from " + context.reportingIncident().id() + ", held "
                        + REPORTING_HOLD_SECONDS + " s)." : "."));
        job.log(context.lockTimeout() == null ? "Lock timeout: none (PostgreSQL's default, as the migrations are written)."
                : "Lock timeout: " + context.lockTimeout() + ", up to " + attempts(context) + " attempts (MIG-01).");
        RehearsalRunner runner = new RehearsalRunner(branch.open().endpoint(),
                new RehearsalRunner.Settings(context.lockTimeout(), attempts(context), stallMillis(context)), listener(job, "statement"));
        RehearsalRunner.Result result = runner.run(plan.scripts(), new RehearsalRunner.Workloads(reporting, REPORTING_HOLD_SECONDS));
        job.event("rehearsal", "Rehearsal finished: checkout's worst insert took " + result.checkout().worstMillis() + " ms", result);
        memory.note(plan.prId(), plan.runId(), "rehearsal", "Rehearse " + (plan.rewritten() ? "the rewritten" : "the as-written") + " migrations"
                        + (reporting ? " with the reporting job's long transaction" : ""),
                summary(result));
        return new Rehearsal(branch.runId(), result);
    }

    @Action(description = "Compare each critical query's plan with its plan before the migrations",
            pre = {"rehearsed", "queriesKnown"}, post = {"plansChecked"}, cost = 0.10, canRerun = false)
    public PlanCheck checkCriticalQueries(RehearsalBranch branch, Baseline baseline, ReleaseContext context) throws Exception {
        Jobs.Job job = job(branch.runId());
        List<PlanComparison> comparisons = new ArrayList<>();
        // The factor REL-03 states; without it, plans are compared and shown, and none is called a regression.
        long factor = context.regressionFactor();
        if (factor == 0) job.log("No release policy states a plan regression limit: the plans are compared, not judged.");
        Set<String> now = RehearsalRunner.schema(branch.open().endpoint());
        for (RehearsalRunner.QueryPlan before : baseline.plans()) {
            RehearsalRunner.QueryPlan after = RehearsalRunner.explain(branch.open().endpoint(), before.name(), before.sql());
            boolean regressed = factor > 0 && after.buffers() >= MIN_REGRESSION_BUFFERS && after.buffers() > before.buffers() * factor;
            RehearsalRunner.QueryPlan kept = null;
            boolean keptFixes = false;
            // The release dropped the index this query used: put that exact index back on the branch, plan the query
            // again, and drop it once more (the rollback is rehearsed against the release as it ships). Keeping the
            // index is a change only once this shows it brings the query back.
            String definition = regressed ? droppedIndex(baseline.schema(), now, before.index()) : null;
            if (definition != null) {
                RehearsalRunner.execute(branch.open().endpoint(), definition.replaceFirst("(?i)^CREATE (UNIQUE )?INDEX ", "CREATE $1INDEX CONCURRENTLY IF NOT EXISTS "));
                try {
                    kept = RehearsalRunner.explain(branch.open().endpoint(), before.name(), before.sql());
                    keptFixes = kept.buffers() <= Math.max(MIN_REGRESSION_BUFFERS, before.buffers() * factor);
                } finally {
                    RehearsalRunner.execute(branch.open().endpoint(), "DROP INDEX CONCURRENTLY IF EXISTS " + before.index());
                }
                job.log("Put " + before.index() + " back on the branch: the " + before.name() + " query reads " + kept.buffers()
                        + " buffers (" + kept.node() + "), against " + before.buffers() + " before the migrations"
                        + (keptFixes ? ": keeping it fixes the query." : ": keeping it does not fix the query."));
            }
            comparisons.add(new PlanComparison(before, after, regressed, kept, keptFixes));
        }
        PlanCheck check = new PlanCheck(branch.runId(), false, comparisons);
        job.event("plans", check.regressions().isEmpty() ? (factor > 0 ? "No critical query read more than " + factor + "× its buffers before (REL-03)."
                        : "No regression limit known: plans compared only.")
                : check.regressions().size() + " critical query(ies) read more than " + factor + "× their buffers before (REL-03).", comparisons);
        memory.note(context.prId(), context.runId(), "plans", "Check the critical queries' plans after the migrations",
                comparisons.stream().map(c -> c.before().name() + ": " + c.before().buffers() + " → " + c.after().buffers() + " buffers ("
                        + c.before().node() + " → " + c.after().node() + ")" + (c.regressed() ? " REGRESSED" : "")).toList().toString());
        return check;
    }

    @Action(description = "No critical query is known on orders: nothing to compare",
            pre = {"rehearsed", "queriesUnknown"}, post = {"plansChecked"}, cost = 0.0, canRerun = false)
    public PlanCheck noCriticalQueries(Rehearsal rehearsal) {
        job(rehearsal.runId()).event("plans", "No critical query on orders is known: no plan was checked.", List.of());
        return new PlanCheck(rehearsal.runId(), true, List.of());
    }

    @Action(description = "Rehearse the rollback on the same branch and check that it restores the schema exactly",
            pre = {"plansChecked", "rollbackRequired"}, post = {"rollbackChecked"}, cost = 0.20, canRerun = false)
    public RollbackCheck rehearseRollback(RehearsalBranch branch, ReleasePlan plan, Baseline baseline, ReleaseContext context) throws Exception {
        Jobs.Job job = job(branch.runId());
        RehearsalRunner runner = new RehearsalRunner(branch.open().endpoint(),
                new RehearsalRunner.Settings(context.lockTimeout(), attempts(context), stallMillis(context)), listener(job, "rollback"));
        RehearsalRunner.Result result = runner.run(plan.rollback(), new RehearsalRunner.Workloads(false, 0));
        Set<String> after = RehearsalRunner.schema(branch.open().endpoint());
        List<String> missing = new ArrayList<>(new TreeSet<>(baseline.schema()));
        missing.removeAll(after);
        List<String> extra = new ArrayList<>(after);
        extra.removeAll(baseline.schema());
        boolean restored = result.completed() && missing.isEmpty() && extra.isEmpty();
        RollbackCheck check = new RollbackCheck(branch.runId(), false, result, restored, missing, extra);
        job.event("rollbackCheck", restored ? "The rollback restored the orders schema exactly."
                : "The rollback did not restore the schema: " + missing.size() + " missing, " + extra.size() + " extra.", check);
        memory.note(plan.prId(), plan.runId(), "rollback", "Rehearse the rollback on the same branch (REL-02)",
                restored ? "Restored the orders schema exactly." : "Did not restore the schema. Missing: " + missing + ". Extra: " + extra + ".");
        return check;
    }

    @Action(description = "No rule asks for a rollback rehearsal",
            pre = {"plansChecked", "rollbackNotRequired"}, post = {"rollbackChecked"}, cost = 0.0, canRerun = false)
    public RollbackCheck noRollbackRequired(Rehearsal rehearsal) {
        job(rehearsal.runId()).event("rollbackCheck", "No rule found asks for a rollback rehearsal (REL-02): the rollback was not rehearsed.", null);
        return new RollbackCheck(rehearsal.runId(), true, null, false, List.of(), List.of());
    }

    @Action(description = "Delete the rehearsal branch and its compute", pre = {"rollbackChecked"}, post = {"branchDeleted"},
            cost = 0.02, canRerun = false)
    public BranchDeleted deleteBranch(RehearsalBranch branch) throws Exception {
        Jobs.Job job = job(branch.runId());
        database.close(branch.open());
        job.closed();
        job.log("Deleted branch " + branch.open().branch().name() + " and its endpoint; main was never changed.");
        return new BranchDeleted(branch.runId(), branch.open().branch().name());
    }

    @AchievesGoal(description = "The pull request's migrations have a recorded release decision")
    @Action(description = "Decide, and record the decision and its evidence on the pull request's record",
            pre = {"branchDeleted"}, post = {"decided"}, cost = 0.05, canRerun = false)
    public ReleaseDecision decide(ReleaseContext context, ReleasePlan plan, Rehearsal rehearsal, PlanCheck plans,
                                  RollbackCheck rollback, BranchDeleted deleted) throws Exception {
        Jobs.Job job = job(context.runId());
        RehearsalRunner.Result result = rehearsal.result();
        List<String> reasons = new ArrayList<>();
        List<String> blocking = new ArrayList<>();
        List<String> changes = new ArrayList<>();
        List<String> lessons = new ArrayList<>();
        Verdict verdict;
        boolean policy = context.knows("REL-03");
        long stall = stallMillis(context);
        List<RehearsalRunner.StepResult> stalled = result.scripts().stream().flatMap(s -> s.steps().stream())
                .filter(s -> s.worstCheckoutMillis() > stall).toList();
        List<RehearsalRunner.StepResult> rewrote = result.scripts().stream().flatMap(s -> s.steps().stream())
                .filter(RehearsalRunner.StepResult::rewrote).toList();
        for (RehearsalRunner.StepResult s : rewrote) {
            reasons.add(s.script() + " #" + s.number() + " rewrote the orders table (" + mb(s.walBytes()) + " of WAL; checkout's worst insert "
                    + s.worstCheckoutMillis() + " ms): " + oneLine(s.text()));
            if (s.text().toLowerCase().matches("(?s).*default\\s+(uuidv7|gen_random_uuid|random|clock_timestamp)\\s*\\(.*")) {
                lessons.add("Rehearsing " + context.prId() + " on orders showed that ADD COLUMN with a volatile default (" + oneLine(s.text())
                        + ") rewrites the whole table: " + mb(s.walBytes()) + " of WAL, and checkout stalled for " + s.worstCheckoutMillis()
                        + " ms. Add the column without a default, SET DEFAULT for new rows, then backfill in batches.");
            }
        }
        for (RehearsalRunner.StepResult s : stalled) {
            if (!s.rewrote()) reasons.add(s.script() + " #" + s.number() + " stalled checkout for " + s.worstCheckoutMillis() + " ms"
                    + (s.migrationWaitedOnLock() ? " while it waited for its lock" : "") + ": " + oneLine(s.text()));
            blocking.add(s.script() + " #" + s.number() + " stalled checkout for " + s.worstCheckoutMillis() + " ms (limit " + stall + " ms, REL-03)"
                    + (s.rewrote() ? ": it rewrote the table." : "."));
        }
        if (!result.completed()) {
            reasons.add("A statement failed: " + result.scripts().getLast().steps().getLast().error());
            blocking.add("A statement failed (REL-03): " + result.scripts().getLast().steps().getLast().error());
        }
        for (PlanComparison c : plans.regressions()) {
            reasons.add("The " + c.before().name() + " query went from " + c.before().buffers() + " to " + c.after().buffers()
                    + " buffers (" + c.before().node() + " on " + c.before().index() + " → " + c.after().node() + "), more than "
                    + context.regressionFactor() + "× (REL-03).");
            if (c.keptFixes()) {
                // Proven on the branch: with the index put back, the query reads what it read before.
                reasons.add("With " + c.before().index() + " put back on the branch, it reads " + c.kept().buffers() + " buffers again ("
                        + c.kept().node() + "): keeping the index is enough.");
                changes.add("Remove DROP INDEX " + c.before().index() + " from the release: the " + c.before().name() + " query needs it ("
                        + c.after().buffers() + " buffers without it, " + c.kept().buffers() + " with it, on the branch).");
                lessons.add("The " + c.before().name() + " query on orders depends on " + c.before().index() + ": dropping it took the query from "
                        + c.before().buffers() + " to " + c.after().buffers() + " buffers in the rehearsal of " + context.prId() + ". Keep it.");
            } else {
                blocking.add("REL-03: the " + c.before().name() + " query read " + c.after().buffers() + " buffers against " + c.before().buffers()
                        + (c.kept() != null ? ", and putting " + c.before().index() + " back did not bring it back (" + c.kept().buffers() + ")."
                        : ", and no index the release drops explains it."));
            }
        }
        boolean dropsIndex = plan.scripts().stream().flatMap(s -> s.steps().stream()).anyMatch(s -> s.text().toUpperCase().contains("DROP INDEX"));
        boolean dropUnproven = dropsIndex && plans.skipped() && context.knows("MIG-05");
        if (dropUnproven) {
            String dropped = String.join(", ", droppedIndexes(plan));
            reasons.add("MIG-05: the release drops " + dropped + ", and no critical query on orders is known to prove nothing needs it.");
            blocking.add("MIG-05: the release drops " + dropped + ", and no critical query on orders is known to prove nothing needs it.");
            changes.add("Keep " + dropped + " in the release, or prove nothing needs it: a plan check of every critical query on orders, "
                    + "which needs the teams that query orders to register their critical queries (MIG-05).");
        }
        if (!rollback.skipped() && !rollback.restored()) {
            reasons.add("REL-02: the rollback did not restore the schema.");
            blocking.add("REL-02: the rollback did not restore the schema.");
        }
        if (plan.rewritten()) changes.addFirst("Ship the migrations as rewritten to the standard (" + plan.findings().size() + " findings).");

        boolean blocked = !blocking.isEmpty();
        if (!policy) {
            verdict = Verdict.NOT_DECIDED;
            reasons.addFirst("No release policy found: the evidence goes to the change board, and nothing is approved.");
            blocking.addFirst("No release policy found: nothing can be approved without one.");
        } else if (blocked) {
            verdict = Verdict.CHANGES_REQUESTED;
        } else if (!changes.isEmpty()) {
            verdict = Verdict.APPROVED_WITH_CHANGES;
        } else {
            verdict = Verdict.APPROVED;
        }
        if (verdict == Verdict.APPROVED_WITH_CHANGES || verdict == Verdict.APPROVED) {
            reasons.add("Every statement completed; checkout's worst insert took " + result.checkout().worstMillis() + " ms (limit " + stall + " ms)"
                    + (context.reportingJobKnown() ? ", with the reporting job's long transaction running" : "") + ".");
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("pr", context.prId());
        evidence.put("run", context.runId());
        evidence.put("rules", context.rules().keySet());
        evidence.put("incidents", context.incidents());
        evidence.put("criticalQueries", context.queries());
        evidence.put("findings", plan.findings());
        evidence.put("rewritten", plan.rewritten());
        evidence.put("rehearsal", result);
        evidence.put("plans", plans.comparisons());
        evidence.put("rollback", rollback);
        evidence.put("verdict", verdict);
        String hash = memory.putEvidence(context.prId(), context.runId(), evidence);
        ReleaseDecision decision = new ReleaseDecision(context.runId(), context.prId(), verdict, reasons, blocking, changes, hash);
        memory.note(context.prId(), context.runId(), "decision", "Decide on " + context.prId(),
                verdict + ". " + String.join(" ", reasons) + (changes.isEmpty() ? "" : " Changes: " + String.join(" ", changes))
                        + (hash != null ? " Evidence artifact " + hash + "." : ""));
        lessons.forEach(l -> memory.learn(context.prId(), l));
        progress.recorded(context.prId(), new Progress.Run(context.runId(), !context.rules().isEmpty(),
                !context.incidents().isEmpty() || !context.queries().isEmpty(), verdict.name()));
        if (!lessons.isEmpty()) job.log("Wrote " + lessons.size() + " lesson(s) to memory: the promote step can share them with every team.");
        job.event("decision", verdict.name(), decision);
        return decision;
    }

    @Condition(name = "contextRecalled")
    public boolean contextRecalled(ReleaseContext context) {
        return context != null;
    }

    @Condition(name = "queriesKnown")
    public boolean queriesKnown(ReleaseContext context) {
        return context != null && !context.queries().isEmpty();
    }

    @Condition(name = "queriesUnknown")
    public boolean queriesUnknown(ReleaseContext context) {
        return context != null && context.queries().isEmpty();
    }

    @Condition(name = "rollbackRequired")
    public boolean rollbackRequired(ReleaseContext context) {
        return context != null && context.knows("REL-02");
    }

    @Condition(name = "rollbackNotRequired")
    public boolean rollbackNotRequired(ReleaseContext context) {
        return context != null && !context.knows("REL-02");
    }

    @Condition(name = "reviewed")
    public boolean reviewed(ReleasePlan plan) {
        return plan != null;
    }

    @Condition(name = "branchReady")
    public boolean branchReady(RehearsalBranch branch) {
        return branch != null;
    }

    @Condition(name = "baselineTaken")
    public boolean baselineTaken(Baseline baseline) {
        return baseline != null;
    }

    @Condition(name = "rehearsed")
    public boolean rehearsed(Rehearsal rehearsal) {
        return rehearsal != null;
    }

    @Condition(name = "plansChecked")
    public boolean plansChecked(PlanCheck check) {
        return check != null;
    }

    @Condition(name = "rollbackChecked")
    public boolean rollbackChecked(RollbackCheck check) {
        return check != null;
    }

    @Condition(name = "branchDeleted")
    public boolean branchDeleted(BranchDeleted deleted) {
        return deleted != null;
    }

    @Condition(name = "decided")
    public boolean decided(ReleaseDecision decision) {
        return decision != null;
    }

    // ------------------------------------------------------------------

    private static List<MigrationScript> scripts(List<String> files) throws Exception {
        List<MigrationScript> scripts = new ArrayList<>();
        for (String file : files) scripts.add(MigrationScript.parse(file, AmpDatabase.read("sql/releases/" + file)));
        return scripts;
    }

    /** An index the query used before the migrations and that is gone after them: its definition then, or null. */
    private static String droppedIndex(Set<String> before, Set<String> after, String index) {
        if (index == null) return null;
        String prefix = "index ";
        for (String entry : before) {
            if (!entry.startsWith(prefix) || after.contains(entry)) continue;
            String definition = entry.substring(prefix.length());
            if (definition.matches("(?is)CREATE (UNIQUE )?INDEX " + java.util.regex.Pattern.quote(index) + " ON .*")) return definition;
        }
        return null;
    }

    /** The indexes the release drops, by name. */
    private static List<String> droppedIndexes(ReleasePlan plan) {
        List<String> out = new ArrayList<>();
        for (MigrationScript script : plan.scripts()) {
            for (MigrationScript.Step step : script.steps()) {
                var m = java.util.regex.Pattern.compile("(?i)DROP\\s+INDEX\\s+(?:CONCURRENTLY\\s+)?(?:IF\\s+EXISTS\\s+)?([\\w.]+)").matcher(step.text());
                if (m.find()) out.add(m.group(1));
            }
        }
        return out;
    }

    /** The stall limit REL-03 states; without it no verdict is given (NOT_DECIDED), and this only labels the evidence. */
    static final long UNSTATED_STALL_MILLIS = 3_000;

    private static long stallMillis(ReleaseContext context) {
        return context.stallMillis() > 0 ? context.stallMillis() : UNSTATED_STALL_MILLIS;
    }

    /** The attempts MIG-01 states; without a lock timeout a statement is tried once. */
    private static int attempts(ReleaseContext context) {
        return context.lockTimeout() == null ? 1 : Math.max(1, context.maxAttempts());
    }

    private static RehearsalRunner.Listener listener(Jobs.Job job, String kind) {
        return new RehearsalRunner.Listener() {
            @Override
            public void note(String text) {
                job.log(text);
            }

            @Override
            public void step(RehearsalRunner.StepResult step) {
                job.event(kind, step.script() + " #" + step.number(), step);
            }
        };
    }

    private static String summary(RehearsalRunner.Result result) {
        StringBuilder out = new StringBuilder();
        for (RehearsalRunner.ScriptResult script : result.scripts()) {
            out.append(script.script()).append(": ");
            for (RehearsalRunner.StepResult s : script.steps()) {
                out.append("#").append(s.number()).append(" ").append(s.ok() ? "ok" : "FAILED").append(" ").append(s.millis()).append(" ms")
                        .append(s.rewrote() ? ", REWROTE the table" : "")
                        .append(s.attempts() > 1 ? ", " + s.attempts() + " attempts" : "")
                        .append(s.migrationWaitedOnLock() ? ", waited for its lock" : "")
                        .append(", checkout worst ").append(s.worstCheckoutMillis()).append(" ms; ");
            }
            out.append("WAL ").append(mb(script.walBytes())).append(". ");
        }
        out.append("Checkout: ").append(result.checkout().inserts()).append(" inserts, median ").append(result.checkout().medianMillis())
                .append(" ms, worst ").append(result.checkout().worstMillis()).append(" ms.");
        return out.toString();
    }

    private static String mb(long bytes) {
        return bytes >= 1_048_576 ? String.format("%.1f MB", bytes / 1_048_576.0) : String.format("%.0f KB", bytes / 1024.0);
    }

    private static String oneLine(String sql) {
        String line = sql.replaceAll("\\s+", " ").strip();
        return line.length() <= 90 ? line : line.substring(0, 90) + "…";
    }
}
