package meko.agentic.sre.api;

import com.embabel.agent.api.event.ActionExecutionResultEvent;
import com.embabel.agent.api.event.ActionExecutionStartEvent;
import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.agent.api.event.LlmInvocationEvent;
import com.embabel.agent.api.event.ObjectBindingEvent;
import com.embabel.agent.api.event.ReplanRequestedEvent;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import meko.agentic.sre.domain.DiagnosisAttempt;
import meko.agentic.sre.domain.IncidentEvidence;
import meko.agentic.sre.domain.MekoContext;
import meko.agentic.sre.domain.ReuseDecision;

/**
 * Turns one agent run's Embabel events into frames the page can draw as they happen.
 * <p>
 * Every object an action returns lands on the blackboard, and the blackboard announces it. So the reuse decision, the evidence scope
 * and each diagnosis attempt stream out exactly as the planner sees them, with no reporting code
 * threaded through the actions themselves. The plan frames are GOAP's own: it replans after
 * every action, so each one is the remaining route as the planner currently intends it — which
 * is where an escalation becomes visible before it runs.
 * <p>
 * Frames carry a {@code run} label so several runs can share one stream.
 */
final class RunEventListener implements AgenticEventListener {

	/** Something that accepts a named frame. Implementations must tolerate concurrent calls. */
	@FunctionalInterface
	interface Sink {
		void send(String event, Map<String, Object> data);
	}

	private final String run;
	private final Sink sink;

	/** An object can be both added and bound; it is reported once. */
	private final Set<Object> reported = Collections.newSetFromMap(new IdentityHashMap<>());

	RunEventListener(String run, Sink sink) {
		this.run = run;
		this.sink = sink;
	}

	@Override
	public void onProcessEvent(AgentProcessEvent event) {
		switch (event) {
		case AgentProcessPlanFormulatedEvent e -> emit("plan", "steps",
				e.getPlan().getActions().stream().map(a -> shortName(a.getName())).toList());
		case ActionExecutionStartEvent e -> emit("step",
				"action", shortName(e.getAction().getName()), "state", "running");
		case ActionExecutionResultEvent e -> emit("step",
				"action", shortName(e.getAction().getName()),
				"state", e.getActionStatus().getStatus().name(),
				"ms", e.getRunningTime().toMillis());
		case ReplanRequestedEvent e -> emit("replan", "reason", e.getReason());
		case LlmInvocationEvent e -> emit("llm",
				"model", e.getInvocation().getLlmMetadata().getName(),
				"inputTokens", e.getInvocation().getUsage().getPromptTokens(),
				"outputTokens", e.getInvocation().getUsage().getCompletionTokens());
		case ObjectBindingEvent e -> domain(e.getValue());
		default -> {
		}
		}
	}

	private void domain(Object value) {
		synchronized (reported) {
			if (!reported.add(value)) {
				return;
			}
		}
		switch (value) {
		case MekoContext c -> emit("context",
				"enabled", c.enabled(),
				"priorIncidents", c.priorIncidents().size(),
				"memoryRows", c.memoryRowsReturned(),
				"runbookChunks", c.runbookChunks().size(),
				"preferredTier", c.preferredTier(),
				"policySource", c.policySource(),
				"budgetUsd", c.budgetCeilingUsd(),
				"confidenceThreshold", c.confidenceThreshold(),
				"recallFailures", c.recallFailures());
		case ReuseDecision d -> emit("reuse",
				"mode", d.mode(),
				"matchScore", d.matchScore(),
				"matchId", d.match() == null ? null : d.match().incidentId(),
				"stale", d.stale(),
				"stalenessReason", d.stalenessReason(),
				"selfMatchesIgnored", d.selfMatchesIgnored(),
				"explanation", d.explanation());
		case IncidentEvidence ev -> emit("evidence",
				"scope", ev.scope(),
				"logLines", ev.logs().size(),
				"logLinesSkipped", ev.logLinesSkipped(),
				"scopedTo", ev.scopedTo());
		case DiagnosisAttempt a -> emit("diagnosis",
				"tier", a.modelTier(),
				"role", a.modelRole(),
				"escalation", a.escalation(),
				"servedFromMemory", a.servedFromMemory(),
				"confidence", a.diagnosis().confidence(),
				"valid", a.validation().valid(),
				"failedAssumptions", a.validation().failedAssumptions(),
				"rootCause", a.diagnosis().rootCause());
		default -> {
		}
		}
	}

	/** Alternating keys and values. A LinkedHashMap because several values are legitimately null. */
	private void emit(String event, Object... kv) {
		var data = new LinkedHashMap<String, Object>();
		data.put("run", run);
		for (int i = 0; i < kv.length; i += 2) {
			data.put((String) kv[i], kv[i + 1]);
		}
		sink.send(event, data);
	}

	/** Embabel qualifies action names with the agent class; the page only needs the method. */
	static String shortName(String name) {
		return name == null ? "" : name.substring(name.lastIndexOf('.') + 1);
	}
}
