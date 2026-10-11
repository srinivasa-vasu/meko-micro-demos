package meko.agentic.goap.story.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.annotation.Provided;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PlannerType;
import meko.agentic.goap.story.StoryLedger;
import meko.agentic.goap.story.store.ContextStore;
import meko.agentic.goap.story.store.StoreRecord;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Story 2, phase 2: your platform SRE team's agent, a different application, takes over in a new agent process. It is
 * given only the incident id.
 * Whatever the first agent completed has to be recovered from the context store; anything that cannot be
 * recovered must be done again, and the planner schedules exactly that.
 */
@Agent(planner = PlannerType.GOAP, description = "Replacement SRE agent that takes over an interrupted incident investigation")
public class SreTakeoverAgent {
    public static final String AGENT_ID = "platform-sre:takeover-agent";

    public record TakeoverRequest(String caseId, ContextStore.Kind store) {}

    public record Recovered(String caseId, ContextStore.Kind store, boolean deployKnown, boolean databaseKnown,
                            List<String> findings) {
        Recovered withDeploy(String finding) { return new Recovered(caseId, store, true, databaseKnown, append(finding)); }
        Recovered withDatabase(String finding) { return new Recovered(caseId, store, deployKnown, true, append(finding)); }
        private List<String> append(String f) { List<String> l = new ArrayList<>(findings); l.add(f); return List.copyOf(l); }
    }

    public record PoolFinding(String caseId, String finding) {}

    public record IncidentResolved(String caseId, String summary) {}

    /** The model's reading of how far the interrupted investigation got. */
    public record Progress(Boolean deployCorrelated, Boolean databaseRuledOut, String nextStep) {}

    static final String PERSONA = "Platform SRE agent";
    /** Event names of the incident's record (see {@link ContextStore.Turn#event}). */
    public static final String POOL_VERIFIED = "connection pool verified";
    public static final String MITIGATED = "mitigated";
    /** Why a step is in the record twice: its first run was not recoverable. */
    static final String REDONE = "redone: the first investigator's result could not be recovered";

    private final StorySupport support;
    private final DecisionService decisions;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SreTakeoverAgent(StorySupport support, DecisionService decisions) {
        this.support = support;
        this.decisions = decisions;
    }

    @Action(description = "Restore the interrupted investigation from its last checkpoint",
            post = {"recovered", "deployKnown", "deployUnknown", "databaseKnown", "databaseUnknown"},
            cost = 0.02, canRerun = false)
    public Recovered restoreInvestigation(TakeoverRequest request, @Provided Ai ai) {
        StoryLedger ledger = support.ledger(request.caseId());
        ContextStore store = support.store(request.store());
        List<StoreRecord> notes = store.recallCase(request.caseId(), AGENT_ID, "incident investigation checkpoint completed next step");
        Optional<String> hash = StorySupport.extract(notes, "artifact ([0-9a-f]{64})");
        Optional<String> checkpoint = hash.flatMap(h -> store.getArtifact(request.caseId(), AGENT_ID, h));
        // Without the checkpoint (its memory write failed, or the store kept nothing from it), the first investigator's
        // own records in the incident's conversation, keyed by the incident id, say what it finished.
        List<ContextStore.Turn> record = checkpoint.isPresent() || store.kind() == ContextStore.Kind.NONE ? List.of()
                : support.readBack(store, request.caseId(), AGENT_ID, request.caseId(),
                        turns -> turns.stream().anyMatch(t -> SreInvestigatorAgent.AGENT_ID.equals(t.writtenBy()) && t.event().isPresent()))
                .stream().filter(t -> SreInvestigatorAgent.AGENT_ID.equals(t.writtenBy()) && t.event().isPresent()).toList();
        record.forEach(t -> ledger.found("incident conversation", t.output(), t.writtenBy()));
        List<String> findings = checkpoint.map(j -> objectMapper.readTree(j).path("findings").valueStream().map(JsonNode::asString).toList())
                .orElse(record.stream().map(ContextStore.Turn::output).toList());
        checkpoint.ifPresent(j -> {
            findings.forEach(f -> ledger.found("checkpoint artifact", f, SreInvestigatorAgent.AGENT_ID));
            ledger.found("checkpoint artifact", "Next step: " + objectMapper.readTree(j).path("next").asString(), SreInvestigatorAgent.AGENT_ID);
        });
        // Everything the takeover agent has: the page, plus whatever the context store returned.
        String context = "Alert: payments-api p99 latency 5.4s since 02:03 (incident " + request.caseId() + ").\n"
                + StorySupport.asContext("Investigation notes", notes)
                + checkpoint.map(j -> "Checkpoint artifact (JSON): " + j + "\n").orElse("")
                + StorySupport.asContext("The first investigator's records in the incident's conversation", record, true);
        java.util.function.Supplier<Progress> byRule = () -> {
            List<String> completed = checkpoint.map(j -> objectMapper.readTree(j).path("completed").valueStream().map(JsonNode::asString).toList())
                    .orElse(List.of());
            boolean deploy = completed.contains("correlateDeploy") || record.stream().anyMatch(t -> t.isEvent(SreInvestigatorAgent.AGENT_ID, SreInvestigatorAgent.DEPLOY_CORRELATED));
            boolean database = completed.contains("ruleOutDatabase") || record.stream().anyMatch(t -> t.isEvent(SreInvestigatorAgent.AGENT_ID, SreInvestigatorAgent.DATABASE_RULED_OUT));
            return new Progress(deploy, database,
                    !deploy ? "correlate the spike with recent deployments" : !database ? "check the database" : "verify connection-pool saturation");
        };
        Progress evidence = byRule.get();
        // Settled by rule when the checkpoint or the investigator's tagged records say what finished, or when there is
        // nothing at all; the model reads the notes only when they exist in a form neither states.
        Progress progress = decisions.decideUnlessClear(request.caseId(),
                PERSONA,
                "decisions/sre-progress",
                Map.of(),
                context,
                Progress.class,
                () -> checkpoint.isPresent() || !record.isEmpty() || notes.isEmpty() ? Optional.of(evidence) : Optional.empty(),
                checkpoint.isPresent() ? "the checkpoint lists what finished" : !record.isEmpty() ? "the investigator's records say what finished" : "nothing recovered",
                byRule,
                // A step counts as done only with evidence that it finished: skipping it otherwise would be unsafe.
                p -> (!Boolean.TRUE.equals(p.deployCorrelated()) || !notes.isEmpty()) && (!Boolean.TRUE.equals(p.databaseRuledOut()) || !notes.isEmpty()),
                ai);
        // The judgement goes on the incident's record, keyed by the incident id, with who made it and on what.
        support.traceDecisions(store, request.caseId(), AGENT_ID, request.caseId(), PERSONA);
        boolean deploy = Boolean.TRUE.equals(progress.deployCorrelated());
        boolean database = Boolean.TRUE.equals(progress.databaseRuledOut());
        Recovered recovered = new Recovered(request.caseId(), request.store(), deploy, database, findings);
        int completed = (deploy ? 1 : 0) + (database ? 1 : 0);
        ledger.step(PERSONA, "restoreInvestigation",
                completed == 0 ? "Nothing to restore: starting the investigation from the alert."
                        : "Restored checkpoint: " + completed + " completed steps; next: " + progress.nextStep() + ".", support.effort("sre-takeover", "restore-investigation"), StoryLedger.Kind.CONTEXT);
        return recovered;
    }

    @Action(description = "Correlate the latency spike with recent deployments", pre = {"deployUnknown"},
            post = {"deployKnown"}, cost = 0.20, canRerun = false)
    public Recovered redoDeployCorrelation(Recovered state) {
        support.ledger(state.caseId()).step(PERSONA, "redoDeployCorrelation",
                "Re-correlated the deploy (the first agent had already done this).", support.effort("sre-investigator", "correlate-deploy"), StoryLedger.Kind.REWORK);
        support.recordEvent(support.store(state.store()), state.caseId(), AGENT_ID, state.caseId(), SreInvestigatorAgent.DEPLOY_CORRELATED,
                REDONE, "Correlate the latency spike with recent deployments", SreInvestigatorAgent.DEPLOY);
        return state.withDeploy(SreInvestigatorAgent.DEPLOY);
    }

    @Action(description = "Check database saturation and lock contention", pre = {"deployKnown", "databaseUnknown"},
            post = {"databaseKnown"}, cost = 0.25, canRerun = false)
    public Recovered redoDatabaseCheck(Recovered state) {
        support.ledger(state.caseId()).step(PERSONA, "redoDatabaseCheck",
                "Re-checked the database (the first agent had already ruled it out).", support.effort("sre-investigator", "rule-out-database"), StoryLedger.Kind.REWORK);
        support.recordEvent(support.store(state.store()), state.caseId(), AGENT_ID, state.caseId(), SreInvestigatorAgent.DATABASE_RULED_OUT,
                REDONE, "Check database saturation and lock contention", SreInvestigatorAgent.DATABASE);
        return state.withDatabase(SreInvestigatorAgent.DATABASE);
    }

    @Action(description = "Verify connection-pool saturation on the payments service", pre = {"deployKnown", "databaseKnown"},
            post = {"poolVerified"}, cost = 0.10, canRerun = false)
    public PoolFinding verifyConnectionPool(Recovered state) {
        String finding = "The simulated payments service reports the connection pool exhausted (50/50 in use); v2.14.0 lowered maxPoolSize from 200 to 50.";
        support.ledger(state.caseId()).step(PERSONA, "verifyConnectionPool", finding, support.effort("sre-takeover", "verify-connection-pool"), StoryLedger.Kind.WORK);
        support.recordEvent(support.store(state.store()), state.caseId(), AGENT_ID, state.caseId(), POOL_VERIFIED, null,
                "Verify connection-pool saturation on payments-api", finding);
        return new PoolFinding(state.caseId(), finding);
    }

    @AchievesGoal(description = "The incident is mitigated")
    @Action(description = "Roll back the pool configuration and confirm recovery", pre = {"poolVerified"},
            post = {"incidentResolved"}, cost = 0.05, canRerun = false)
    public IncidentResolved mitigate(Recovered state, PoolFinding pool) {
        StoryLedger ledger = support.ledger(state.caseId());
        String summary = "Rolled back maxPoolSize to 200; the simulated service reports p99 latency back under 400 ms.";
        ledger.step(PERSONA, "mitigate", summary, support.effort("sre-takeover", "mitigate"), StoryLedger.Kind.WORK);
        support.recordEvent(support.store(state.store()), state.caseId(), AGENT_ID, state.caseId(), MITIGATED, null,
                "Mitigate the incident", summary);
        ledger.outcome(summary);
        ledger.count("decided", 1);
        int repeated = ledger.counters().getOrDefault("repeatedSteps", 0);
        java.util.List<String> facts = new java.util.ArrayList<>(state.findings());
        facts.add(pool.finding());
        facts.add("Mitigation: " + summary);
        facts.add(repeated == 0 ? "The takeover resumed from the first investigator's checkpoint; nothing was repeated"
                : "The takeover had to repeat " + repeated + " investigation steps the first investigator had already done");
        java.util.List<String> gaps = repeated == 0 ? java.util.List.of()
                : java.util.List.of("The first investigator's notes and timeline were lost when its process died");
        ledger.handToWriter(new ResponseWriterAgent.WritingTask(state.caseId(), PERSONA,
                "handover note for the next on-call shift",
                support.render("writer/sre-handover", Map.of()),
                facts, gaps, "Handover: " + pool.finding() + " " + summary));
        support.store(state.store()).rememberLater(state.caseId(), AGENT_ID, "Incident " + state.caseId() + " resolved: " + pool.finding() + " " + summary);
        return new IncidentResolved(state.caseId(), summary);
    }

    @Condition(name = "recovered")
    public boolean recovered(Recovered state) {
        return state != null;
    }

    @Condition(name = "deployKnown")
    public boolean deployKnown(Recovered state) {
        return state != null && state.deployKnown();
    }

    @Condition(name = "deployUnknown")
    public boolean deployUnknown(Recovered state) {
        return state != null && !state.deployKnown();
    }

    @Condition(name = "databaseKnown")
    public boolean databaseKnown(Recovered state) {
        return state != null && state.databaseKnown();
    }

    @Condition(name = "databaseUnknown")
    public boolean databaseUnknown(Recovered state) {
        return state != null && !state.databaseKnown();
    }

    @Condition(name = "poolVerified")
    public boolean poolVerified(PoolFinding finding) {
        return finding != null;
    }

    @Condition(name = "incidentResolved")
    public boolean incidentResolved(IncidentResolved resolved) {
        return resolved != null;
    }
}
