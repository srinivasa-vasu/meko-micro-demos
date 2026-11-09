package meko.agentic.release.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.common.PlannerType;
import meko.agentic.release.agent.RecoveryModels.Damage;
import meko.agentic.release.agent.RecoveryModels.ForensicBranch;
import meko.agentic.release.agent.RecoveryModels.ForensicClosed;
import meko.agentic.release.agent.RecoveryModels.IncidentContext;
import meko.agentic.release.agent.RecoveryModels.Outcome;
import meko.agentic.release.agent.RecoveryModels.RecoveryDecision;
import meko.agentic.release.agent.RecoveryModels.RecoveryRequest;
import meko.agentic.release.agent.RecoveryModels.RepairRehearsal;
import meko.agentic.release.agent.RecoveryModels.Verdict;
import meko.agentic.release.amp.AmpDatabase;
import meko.agentic.release.amp.Endpoint;
import meko.agentic.release.rehearsal.IncidentSql;
import meko.agentic.release.run.Jobs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The data recovery agent: given only an incident id, it puts back exactly the rows a bad job changed on {@code orders}.
 * It needs a point in time to compare against: the WAL position from just before the job, which only the batch
 * scheduler recorded, in the organization's shared datapack. With it, AMP forks main as of that position in seconds,
 * and the difference between the fork and main is the damage. Without it, the damaged rows cannot be told apart from
 * the genuine ones, and nothing is repaired.
 * <p>
 * Deterministic: no action prompts a model. The planner chooses the path by what the agent found.
 */
@Agent(planner = PlannerType.GOAP, description = "Repairs exactly the rows a bad job changed, by comparing main with a fork from before the job")
public class RecoveryAgent {
    public static final String AGENT_ID = "platform-data:recovery-agent";

    /** The request, with the address AMP's allow-list admits for the branches' endpoints. */
    public record Run(RecoveryRequest request) {}

    private final AmpDatabase database;
    private final ReleaseMemory memory;
    private final Jobs jobs;
    private final Progress progress;

    public RecoveryAgent(AmpDatabase database, ReleaseMemory memory, Jobs jobs, Progress progress) {
        this.database = database;
        this.memory = memory;
        this.jobs = jobs;
        this.progress = progress;
    }

    private Jobs.Job job(String runId) {
        return jobs.get(runId);
    }

    @Action(description = "Recall the incident, the scheduled jobs' markers on orders, and the data repair runbook",
            post = {"incidentRecalled", "positionKnown", "positionUnknown"}, cost = 0.05, canRerun = false)
    public IncidentContext recallIncident(Run run) throws Exception {
        RecoveryRequest request = run.request();
        Jobs.Job job = job(request.runId());
        IncidentContext context = memory.recallIncident(request.runId(), request.incidentId(), request.readShared(), job::log);
        job.event("incidentContext", "What the agent knows before it touches a database", context);
        memory.note(request.incidentId(), request.runId(), "context", "Recall the incident, the job markers on orders and the runbook",
                "Runbook: " + (context.runbook().isEmpty() ? "none" : String.join(", ", new java.util.TreeSet<>(context.runbook().keySet())))
                        + ". Marker: " + (context.marker() == null ? "none" : context.marker().job()
                        + (context.marker().runId().isBlank() ? "" : " run " + context.marker().runId()) + " at " + context.marker().startedAt()
                        + ", position " + context.marker().lsn() + " (" + context.marker().writtenBy() + ", in " + context.marker().datapack() + ")")
                        + ". Incident: " + (context.incident() == null ? "only the alert" : context.incident().writtenBy() + ", in " + context.incident().datapack())
                        + ". Read: " + context.sources() + ".");
        return context;
    }

    @Action(description = "No position to compare against: count what cannot be told apart, and stop",
            pre = {"incidentRecalled", "positionUnknown"}, post = {"resolved"}, cost = 0.05, canRerun = false)
    public Outcome noPositionToCompare(IncidentContext context) throws Exception {
        Jobs.Job job = job(context.runId());
        Endpoint main = database.main();
        int pending = IncidentSql.count(main, "status = '" + IncidentSql.JOB_VALUE + "'");
        List<String> checks = new ArrayList<>();
        checks.add(pending + " orders are " + IncidentSql.JOB_VALUE + " on main now. Without a point in time from before the job, the ones the job changed cannot be told apart from the ones that were " + IncidentSql.JOB_VALUE + " already.");
        for (ReleaseModels.CriticalQuery q : context.queries()) {
            checks.add("The " + q.name() + " query returns " + IncidentSql.rows(main, q.sql()).size() + " row(s) on main now.");
        }
        checks.add("Forking main now would copy the damage; restoring a backup over orders would lose every order written since it was taken.");
        checks.forEach(job::log);
        Map<String, Integer> now = IncidentSql.statusCounts(main);
        job.event("counts", "Orders by status now", Map.of("now", now));
        return new Outcome(context.runId(), Verdict.NOT_RECOVERED, 0, pending, checks, now);
    }

    @Action(description = "Fork main as of the position recorded just before the job",
            pre = {"incidentRecalled", "positionKnown"}, post = {"forensicReady"}, cost = 0.30, canRerun = false)
    public ForensicBranch forkBeforeJob(IncidentContext context, Run run) throws Exception {
        Jobs.Job job = job(context.runId());
        String name = ("before-" + context.marker().job() + "-" + context.runId()).toLowerCase().replaceAll("[^a-z0-9-]", "-");
        AmpDatabase.OpenBranch open = database.openBranch(name, run.request().cidr(), context.marker().lsn(), job::log);
        job.opened(open);
        job.event("branch", "Fork " + name + " is main as of " + context.marker().lsn(), Map.of("name", name, "forkMillis", open.forkMillis(),
                "readyMillis", open.readyMillis(), "forkLsn", String.valueOf(open.branch().forkLsn()), "endpoint", open.endpoint().address()));
        memory.note(context.incidentId(), context.runId(), "fork", "Fork main as of the position before " + context.marker().job(),
                "Forked main as " + name + " as of " + open.branch().forkLsn() + " in " + open.forkMillis() + " ms; its endpoint answered after "
                        + open.readyMillis() / 1000 + " s.");
        return new ForensicBranch(context.runId(), open);
    }

    @Action(description = "Compare the fork with main: the rows the job changed, and nothing else",
            pre = {"forensicReady"}, post = {"damageKnown", "damageAccounted", "damageUnaccounted"}, cost = 0.10, canRerun = false)
    public Damage findDamage(ForensicBranch fork, IncidentContext context) throws Exception {
        Jobs.Job job = job(fork.runId());
        List<Long> pending = IncidentSql.pendingIds(database.main());
        Map<Long, String> then = IncidentSql.statusOn(fork.open().endpoint(), pending);
        Map<Long, String> before = new LinkedHashMap<>();
        int genuine = 0;
        for (Map.Entry<Long, String> e : then.entrySet()) {
            if (IncidentSql.JOB_VALUE.equals(e.getValue())) genuine++;
            else before.put(e.getKey(), e.getValue());
        }
        int createdAfter = pending.size() - then.size();
        // What each critical query returned before the job: the repair must give the same answers back (REC-03).
        Map<String, List<String>> critical = new LinkedHashMap<>();
        for (ReleaseModels.CriticalQuery q : context.queries()) critical.put(q.name(), IncidentSql.rows(fork.open().endpoint(), q.sql()));
        Damage damage = new Damage(fork.runId(), before, pending.size(), genuine, createdAfter, context.marker().lsn(), fork.open().forkMillis(),
                context.marker().rowsChanged(), critical);
        String summary = pending.size() + " orders are " + IncidentSql.JOB_VALUE + " on main. Compared with the fork: " + before.size() + " were changed by the job (they were "
                + String.join("/", new java.util.TreeSet<>(before.values())) + " before it), " + genuine + " were already " + IncidentSql.JOB_VALUE + " before it, and "
                + createdAfter + " were created after it. "
                + (damage.expected() < 0 ? "The run reported no row count to check against. Only the " + before.size() + " are repaired."
                : damage.accounted() ? "That accounts for exactly the " + damage.expected() + " rows run " + context.marker().runId() + " reported changing: only those are repaired."
                : "But run " + context.marker().runId() + " reported changing " + damage.expected() + " rows: the comparison does not account for them, so nothing is repaired.");
        job.event("damage", summary, Map.of("damaged", before.size(), "pendingNow", pending.size(), "genuine", genuine, "createdAfter", createdAfter,
                "expected", damage.expected(), "run", context.marker().runId()));
        memory.note(context.incidentId(), context.runId(), "damage", "Compare main with the fork from before the job (REC-02)", summary);
        return damage;
    }

    @Action(description = "Delete the fork from before the job", pre = {"damageKnown"}, post = {"forensicClosed"}, cost = 0.02, canRerun = false)
    public ForensicClosed closeFork(ForensicBranch fork) throws Exception {
        database.close(fork.open());
        job(fork.runId()).closed();
        job(fork.runId()).log("Deleted the fork " + fork.open().branch().name() + ".");
        return new ForensicClosed(fork.runId());
    }

    @Action(description = "The damage does not account for the rows the job's run changed: hold the repair for a person",
            pre = {"forensicClosed", "damageUnaccounted"}, post = {"resolved"}, cost = 0.05, canRerun = false)
    public Outcome holdUnaccounted(Damage damage, IncidentContext context) throws Exception {
        List<String> checks = List.of("The comparison found " + damage.before().size() + " changed row(s), but run " + context.marker().runId()
                + " of " + context.marker().job() + " reported changing " + damage.expected()
                + ". A repair that does not account for every row the job changed is held for a person (REC-02).");
        checks.forEach(job(damage.runId())::log);
        return new Outcome(damage.runId(), Verdict.REPAIR_HELD, 0, damage.pendingNow(), checks, IncidentSql.statusCounts(database.main()));
    }

    /**
     * Runs each critical query on {@code target} and compares its answer with what it returned on the fork, before the
     * job; adds a check per query. True when every one gives its answer from before the job back.
     */
    private static boolean criticalQueriesRestored(Endpoint target, Damage damage, IncidentContext context, List<String> checks) throws Exception {
        if (context.queries().isEmpty()) {
            checks.add("No critical query on orders is known: none was checked.");
            return true;
        }
        boolean all = true;
        for (ReleaseModels.CriticalQuery q : context.queries()) {
            List<String> before = damage.criticalBefore().getOrDefault(q.name(), List.of());
            List<String> now = IncidentSql.rows(target, q.sql());
            boolean same = now.equals(before);
            all &= same;
            checks.add("The " + q.name() + " query (" + q.writtenBy() + ") returns " + now.size() + " row(s): "
                    + (same ? "the same answer as before the job." : "not its answer from before the job (" + before.size() + " row(s) then)."));
        }
        return all;
    }

    @Action(description = "Rehearse the repair on a fresh branch of main, and check it",
            pre = {"forensicClosed", "damageAccounted"}, post = {"repairRehearsed", "rehearsalPassed", "rehearsalFailed"}, cost = 0.30, canRerun = false)
    public RepairRehearsal rehearseRepair(Damage damage, IncidentContext context, Run run) throws Exception {
        Jobs.Job job = job(damage.runId());
        String name = ("repair-" + context.incidentId() + "-" + damage.runId()).toLowerCase().replaceAll("[^a-z0-9-]", "-");
        AmpDatabase.OpenBranch open = database.openBranch(name, run.request().cidr(), job::log);
        job.opened(open);
        List<String> checks = new ArrayList<>();
        boolean passed;
        int repaired;
        long started = System.currentTimeMillis();
        try {
            Endpoint branch = open.endpoint();
            String value = "status = '" + IncidentSql.JOB_VALUE + "'";
            int pendingBefore = IncidentSql.count(branch, value);
            repaired = IncidentSql.repair(branch, damage.before());
            int restored = IncidentSql.restored(branch, damage.before());
            int pendingAfter = IncidentSql.count(branch, value);
            boolean exact = repaired == damage.before().size() && restored == damage.before().size();
            boolean untouched = pendingAfter == pendingBefore - repaired && pendingAfter == damage.genuine() + damage.createdAfter();
            checks.add("The repair changed " + repaired + " row(s); " + restored + " of " + damage.before().size() + " damaged rows hold their value from before the job.");
            checks.add(IncidentSql.JOB_VALUE + " orders: " + pendingBefore + " → " + pendingAfter + (untouched
                    ? ": exactly the " + damage.genuine() + " that were " + IncidentSql.JOB_VALUE + " before the job and the " + damage.createdAfter() + " created since."
                    : ", but " + (damage.genuine() + damage.createdAfter()) + " were expected: rows outside the damage changed, or damaged rows did not."));
            boolean queries = criticalQueriesRestored(branch, damage, context, checks);
            passed = exact && untouched && queries;
        } finally {
            database.close(open);
            job.closed();
        }
        long millis = System.currentTimeMillis() - started;
        checks.forEach(job::log);
        RepairRehearsal rehearsal = new RepairRehearsal(damage.runId(), passed, repaired, millis, checks);
        job.event("repairRehearsal", passed ? "The repair rehearsal passed." : "The repair rehearsal did not pass: the repair is held.", rehearsal);
        memory.note(context.incidentId(), context.runId(), "rehearsal", "Rehearse the repair on a branch of main (REC-03)", String.join(" ", checks));
        return rehearsal;
    }

    @Action(description = "Run the rehearsed repair on main, and check it", pre = {"repairRehearsed", "rehearsalPassed"},
            post = {"resolved"}, cost = 0.10, canRerun = false)
    public Outcome applyRepair(Damage damage, RepairRehearsal rehearsal, IncidentContext context) throws Exception {
        Jobs.Job job = job(damage.runId());
        Endpoint main = database.main();
        int repaired = IncidentSql.repair(main, damage.before());
        int restored = IncidentSql.restored(main, damage.before());
        int pending = IncidentSql.count(main, "status = '" + IncidentSql.JOB_VALUE + "'");
        List<String> checks = new ArrayList<>();
        checks.add("Repaired " + repaired + " row(s) on main; " + restored + " of " + damage.before().size() + " hold their value from before the job.");
        checks.add(pending + " orders are " + IncidentSql.JOB_VALUE + " on main: the ones that were before the job, and the ones written since.");
        boolean queries = criticalQueriesRestored(main, damage, context, checks);
        checks.forEach(job::log);
        Map<String, Integer> after = IncidentSql.statusCounts(main);
        job.event("counts", "Orders by status after the repair", Map.of("repaired", after));
        memory.note(context.incidentId(), context.runId(), "repair", "Run the repair on main", String.join(" ", checks) + " Orders by status after the repair: " + after + ".");
        Verdict verdict = restored == damage.before().size() && queries ? Verdict.RECOVERED : Verdict.REPAIR_HELD;
        if (verdict == Verdict.RECOVERED) {
            memory.learn(context.incidentId(), "Recovering " + context.incidentId() + " on orders: " + context.marker().job() + " set "
                    + String.join("/", new java.util.TreeSet<>(damage.before().values())) + " orders to " + IncidentSql.JOB_VALUE + " (" + damage.before().size()
                    + " rows in run " + context.marker().runId() + "). Fork main as of the position its scheduler records before it runs, compare, and repair only the rows it changed.");
        }
        return new Outcome(damage.runId(), verdict, repaired, pending, checks, after);
    }

    @Action(description = "The repair rehearsal did not pass: hold the repair for a person", pre = {"repairRehearsed", "rehearsalFailed"},
            post = {"resolved"}, cost = 0.05, canRerun = false)
    public Outcome holdRepair(Damage damage, RepairRehearsal rehearsal) throws Exception {
        return new Outcome(damage.runId(), Verdict.REPAIR_HELD, 0, damage.pendingNow(), rehearsal.checks(), IncidentSql.statusCounts(database.main()));
    }

    @AchievesGoal(description = "The incident has a recorded recovery decision")
    @Action(description = "Decide, and record the decision and its evidence on the incident's record", pre = {"resolved"},
            post = {"recoveryDecided"}, cost = 0.05, canRerun = false)
    public RecoveryDecision decide(IncidentContext context, Outcome outcome) throws Exception {
        Jobs.Job job = job(context.runId());
        List<String> reasons = new ArrayList<>(outcome.checks());
        if (context.marker() == null) {
            reasons.addFirst("No position from before the job: " + (context.readShared() ? "no job marker on orders was found."
                    : "the job markers are in the organization's shared datapack, which the recovery team cannot read yet."));
        } else {
            reasons.addFirst("Compared main with a fork as of " + context.marker().lsn() + ", the position " + context.marker().writtenBy()
                    + " recorded before " + context.marker().job() + (context.marker().runId().isBlank() ? "" : " run " + context.marker().runId())
                    + " started at " + context.marker().startedAt() + " UTC"
                    + (context.marker().completed() ? "; the run reported changing " + context.marker().rowsChanged() + " rows." : "."));
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("incident", context.incidentId());
        evidence.put("run", context.runId());
        evidence.put("marker", context.marker());
        evidence.put("jobRun", context.marker() == null ? null : context.marker().runId());
        evidence.put("runbook", context.runbook().keySet());
        evidence.put("verdict", outcome.verdict());
        evidence.put("repaired", outcome.repaired());
        evidence.put("checks", outcome.checks());
        evidence.put("ordersByStatusAfter", outcome.countsAfter());
        String hash = memory.putEvidence(context.incidentId(), context.runId(), evidence);
        RecoveryDecision decision = new RecoveryDecision(context.runId(), context.incidentId(), outcome.verdict(), reasons, hash);
        memory.note(context.incidentId(), context.runId(), "decision", "Decide on " + context.incidentId(),
                outcome.verdict() + ". " + String.join(" ", reasons) + (hash != null ? " Evidence artifact " + hash + "." : ""));
        progress.recorded(context.incidentId(), new Progress.Run(context.runId(), !context.runbook().isEmpty(), context.marker() != null, outcome.verdict().name()));
        job.event("recoveryDecision", outcome.verdict().name(), decision);
        return decision;
    }

    @Condition(name = "incidentRecalled")
    public boolean incidentRecalled(IncidentContext context) {
        return context != null;
    }

    @Condition(name = "positionKnown")
    public boolean positionKnown(IncidentContext context) {
        return context != null && context.marker() != null;
    }

    @Condition(name = "positionUnknown")
    public boolean positionUnknown(IncidentContext context) {
        return context != null && context.marker() == null;
    }

    @Condition(name = "forensicReady")
    public boolean forensicReady(ForensicBranch fork) {
        return fork != null;
    }

    @Condition(name = "damageKnown")
    public boolean damageKnown(Damage damage) {
        return damage != null;
    }

    @Condition(name = "damageAccounted")
    public boolean damageAccounted(Damage damage) {
        return damage != null && damage.accounted();
    }

    @Condition(name = "damageUnaccounted")
    public boolean damageUnaccounted(Damage damage) {
        return damage != null && !damage.accounted();
    }

    @Condition(name = "forensicClosed")
    public boolean forensicClosed(ForensicClosed closed) {
        return closed != null;
    }

    @Condition(name = "repairRehearsed")
    public boolean repairRehearsed(RepairRehearsal rehearsal) {
        return rehearsal != null;
    }

    @Condition(name = "rehearsalPassed")
    public boolean rehearsalPassed(RepairRehearsal rehearsal) {
        return rehearsal != null && rehearsal.passed();
    }

    @Condition(name = "rehearsalFailed")
    public boolean rehearsalFailed(RepairRehearsal rehearsal) {
        return rehearsal != null && !rehearsal.passed();
    }

    @Condition(name = "resolved")
    public boolean resolved(Outcome outcome) {
        return outcome != null;
    }

    @Condition(name = "recoveryDecided")
    public boolean recoveryDecided(RecoveryDecision decision) {
        return decision != null;
    }
}
