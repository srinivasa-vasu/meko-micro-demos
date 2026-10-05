package meko.agentic.goap.story.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.core.AgentProcess;
import meko.agentic.goap.story.StoryLedger;
import meko.agentic.goap.story.store.ContextStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Story 2, phase 1: the payments team's on-call agent (the payments team's own application) works the incident and
 * checkpoints its progress, then dies mid-plan. The death is real: the action asks Embabel to terminate the agent process, so the plan's
 * remaining actions never run.
 */
@Agent(planner = PlannerType.GOAP, description = "On-call SRE investigator for a payment API latency incident")
public class SreInvestigatorAgent {
    public static final String AGENT_ID = "payments-team:oncall-agent";

    public record IncidentPage(String caseId, ContextStore.Kind store) {}

    public record DeployFinding(String caseId, ContextStore.Kind store, String finding) {}

    public record DatabaseFinding(String caseId, ContextStore.Kind store, String finding) {}

    public record Checkpointed(String caseId, ContextStore.Kind store, String contentHash) {}

    public record PoolCheckStarted(String caseId) {}

    public record Mitigated(String caseId) {}

    static final String PERSONA = "Payments team's on-call agent";
    /** Event names of the incident's record (see {@link ContextStore.Turn#event}). */
    public static final String DEPLOY_CORRELATED = "deploy correlated";
    public static final String DATABASE_RULED_OUT = "database ruled out";
    static final String DEPLOY = "Latency began at 02:01, four minutes after deploy payments-api v2.14.0 (01:57).";
    static final String DATABASE = "Database ruled out: p99 query latency and lock waits normal throughout.";

    private final StorySupport support;

    public SreInvestigatorAgent(StorySupport support) {
        this.support = support;
    }

    @Action(description = "Correlate the latency spike with recent deployments", post = {"deployCorrelated"}, cost = 0.20, canRerun = false)
    public DeployFinding correlateDeploy(IncidentPage page) {
        StoryLedger ledger = support.ledger(page.caseId());
        ledger.note("PagerDuty", "02:03 SEV-2 payments-api p99 latency 5.4s (threshold 2s).");
        ledger.step(PERSONA, "correlateDeploy", DEPLOY, support.effort("sre-investigator", "correlate-deploy"), StoryLedger.Kind.WORK);
        support.recordEvent(support.store(page.store()), page.caseId(), AGENT_ID, page.caseId(), DEPLOY_CORRELATED, null,
                "Correlate the latency spike with recent deployments", DEPLOY);
        return new DeployFinding(page.caseId(), page.store(), DEPLOY);
    }

    @Action(description = "Check database saturation and lock contention", pre = {"deployCorrelated"},
            post = {"databaseRuledOut"}, cost = 0.25, canRerun = false)
    public DatabaseFinding ruleOutDatabase(DeployFinding deploy) {
        support.ledger(deploy.caseId()).step(PERSONA, "ruleOutDatabase", DATABASE, support.effort("sre-investigator", "rule-out-database"), StoryLedger.Kind.WORK);
        support.recordEvent(support.store(deploy.store()), deploy.caseId(), AGENT_ID, deploy.caseId(), DATABASE_RULED_OUT, null,
                "Check database saturation and lock contention", DATABASE);
        return new DatabaseFinding(deploy.caseId(), deploy.store(), DATABASE);
    }

    @Action(description = "Checkpoint the investigation so another agent can resume it", pre = {"databaseRuledOut"},
            post = {"checkpointed"}, cost = 0.02, canRerun = false)
    public Checkpointed checkpoint(DeployFinding deploy, DatabaseFinding database) {
        StoryLedger ledger = support.ledger(deploy.caseId());
        ContextStore store = support.store(deploy.store());
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("incident", deploy.caseId());
        state.put("completed", List.of("correlateDeploy", "ruleOutDatabase"));
        state.put("findings", List.of(deploy.finding(), database.finding()));
        state.put("next", "verify connection-pool saturation on payments-api");
        String hash = store.putArtifact(deploy.caseId(), AGENT_ID, deploy.caseId() + "-checkpoint.json", state).orElse(null);
        if (hash != null) {
            store.remember(deploy.caseId(), AGENT_ID, "Incident " + deploy.caseId() + " investigation checkpoint artifact " + hash
                    + ". Completed: deploy correlated, database ruled out. Next: verify connection-pool saturation.");
        }
        ledger.step(PERSONA, "checkpoint",
                hash != null ? "Checkpointed findings and next step to " + StorySupport.storeName(deploy.store()) + " (artifact " + hash.substring(0, 12) + "…)."
                        : "Checkpoint kept in process memory only (no durable store).", support.effort("sre-investigator", "checkpoint"), StoryLedger.Kind.CONTEXT);
        return new Checkpointed(deploy.caseId(), deploy.store(), hash);
    }

    @Action(description = "Verify connection-pool saturation on the payments service", pre = {"checkpointed"},
            post = {"poolCheckStarted"}, cost = 0.10, canRerun = false)
    public PoolCheckStarted verifyConnectionPool(Checkpointed checkpoint) {
        StoryLedger ledger = support.ledger(checkpoint.caseId());
        ledger.note("Kubernetes", "02:41 pod sre-investigator-7f9c OOMKilled; agent process lost.");
        ledger.step(PERSONA, "verifyConnectionPool", "Agent died while starting the connection-pool check.", support.effort("sre-investigator", "verify-connection-pool-interrupted"), StoryLedger.Kind.WORK);
        AgentProcess current = AgentProcess.get();
        if (current != null) current.terminateAgent("SRE investigator pod OOMKilled at 02:41");
        return new PoolCheckStarted(checkpoint.caseId());
    }

    @AchievesGoal(description = "The incident is mitigated")
    @Action(description = "Mitigate the incident", pre = {"poolCheckStarted"}, post = {"mitigated"}, cost = 0.05, canRerun = false)
    public Mitigated mitigate(PoolCheckStarted pool) {
        // Never reached in the story: the agent dies in verifyConnectionPool.
        return new Mitigated(pool.caseId());
    }

    @Condition(name = "deployCorrelated")
    public boolean deployCorrelated(DeployFinding finding) {
        return finding != null;
    }

    @Condition(name = "databaseRuledOut")
    public boolean databaseRuledOut(DatabaseFinding finding) {
        return finding != null;
    }

    @Condition(name = "checkpointed")
    public boolean checkpointed(Checkpointed checkpoint) {
        return checkpoint != null;
    }

    @Condition(name = "poolCheckStarted")
    public boolean poolCheckStarted(PoolCheckStarted started) {
        return started != null;
    }

    @Condition(name = "mitigated")
    public boolean mitigated(Mitigated mitigated) {
        return mitigated != null;
    }
}
