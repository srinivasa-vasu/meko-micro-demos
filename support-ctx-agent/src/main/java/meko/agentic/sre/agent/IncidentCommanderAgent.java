package meko.agentic.sre.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.annotation.Cost;
import com.embabel.agent.api.annotation.Provided;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.core.AgentProcess;
import com.embabel.common.textio.template.TemplateRenderer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import meko.agentic.sre.config.TokenProperties;
import meko.agentic.sre.domain.ContextSource;
import meko.agentic.sre.domain.Diagnosis;
import meko.agentic.sre.domain.DiagnosisAttempt;
import meko.agentic.sre.domain.IncidentEvidence;
import meko.agentic.sre.domain.IncidentOutcome;
import meko.agentic.sre.domain.IncidentReport;
import meko.agentic.sre.domain.IncidentRequest;
import meko.agentic.sre.domain.MekoContext;
import meko.agentic.sre.domain.ModelTier;
import meko.agentic.sre.domain.ReuseDecision;
import meko.agentic.sre.domain.ReuseMode;
import meko.agentic.sre.domain.RoutingPolicy;
import meko.agentic.sre.domain.TokenLedger;
import meko.agentic.sre.domain.ValidationResult;
import meko.agentic.sre.meko.MekoDatapack;
import meko.agentic.sre.meko.MekoToolBridge;
import meko.agentic.sre.service.IncidentDataService;
import meko.agentic.sre.service.MemoryRecordParser;
import meko.agentic.sre.service.RemediationValidator;
import meko.agentic.sre.service.ReuseGate;
import meko.agentic.sre.service.RoutingPolicyService;
import meko.agentic.sre.service.TokenAccounting;
import org.jspecify.annotations.Nullable;

/**
 * Incident commander.
 *  - Meko retrieval is now the FIRST action, with no precondition on telemetry. Previously
 *    {@code retrieveMekoContext} required {@code incidentEvidenceCollected}, which meant the
 *    expensive log sweep ran unconditionally before memory was ever consulted — so the warm
 *    run paid the same bill as the cold run and there was no saving left to demonstrate.
 *  - A deterministic reuse gate sits between retrieval and telemetry. It decides whether the
 *    plan needs the full sweep, a narrowed slice, or no generative call at all. Because that
 *    decision mutates world state, the planner produces genuinely different plans for the
 *    same goal rather than running a fixed pipeline with a branch inside it.
 *  - Model tier is an attribute of each action rather than one global routing decision, and
 *    planner costs are derived from estimated dollars against the severity budget instead of
 *    hand-tuned constants.
 *  - Every terminating path writes back to Meko, including short-circuit runs. Without a
 *    write on the reuse path the corpus ossifies around whatever it learned first.
 */
@Agent(
		planner = PlannerType.GOAP,
		description = "AI SRE incident commander. Consults Meko organizational memory/context before spending "
				+ "anything, lets the recovered context prune the investigation, routes each action to the "
				+ "cheapest sufficient model, escalates only when confidence or validation demands it, and "
				+ "persists durable learning and routing outcomes."
)
public class IncidentCommanderAgent {

	private static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.75;

	private static final int ME_SEARCH_LIMIT = 10;
	private static final int KO_SEARCH_LIMIT = 5;
	private static final double FAILED_VALIDATION_CONFIDENCE = 0.15;

	private final IncidentDataService incidentData;
	private final MekoToolBridge meko;
	private final MemoryRecordParser parser;
	private final ReuseGate reuseGate;
	private final RoutingPolicyService routingPolicy;
	private final RemediationValidator validator;
	private final TokenAccounting accounting;
	private final TokenProperties properties;
	private final MekoDatapack datapack;
	private final TemplateRenderer templates;

	public IncidentCommanderAgent(
			IncidentDataService incidentData,
			MekoToolBridge meko,
			MemoryRecordParser parser,
			ReuseGate reuseGate,
			RoutingPolicyService routingPolicy,
			RemediationValidator validator,
			TokenAccounting accounting,
			TokenProperties properties,
			MekoDatapack datapack,
			TemplateRenderer templates) {
		this.incidentData = incidentData;
		this.meko = meko;
		this.parser = parser;
		this.reuseGate = reuseGate;
		this.routingPolicy = routingPolicy;
		this.validator = validator;
		this.accounting = accounting;
		this.properties = properties;
		this.datapack = datapack;
		this.templates = templates;
	}

	// ------------------------------------------------------------------
	// 1. Memory first. Cheapest action in the graph, no preconditions.
	// ------------------------------------------------------------------

	@Action(
			description = "Retrieve historical incidents, operational knowledge, spend policy and learned "
					+ "model-routing outcomes from Meko before any telemetry is gathered",
			post = {"mekoContextAvailable"},
			cost = 0.02,
			value = 0.95,
			canRerun = false
	)
	public MekoContext retrieveMekoContext(IncidentRequest request) {
		double fallbackBudget = properties.budgetFor(request.severity());

		if (!request.useMeko() || !mekoConfigured()) {
			return new MekoContext(false, List.of(), List.of(), List.of(), ModelTier.LLM,
					DEFAULT_CONFIDENCE_THRESHOLD, fallbackBudget,
					"Meko is disabled, so there is no historical context and no learned routing "
							+ "policy. Routing falls back to the configured default tier (" + ModelTier.LLM
							+ ") and the planner must assume a full investigation.",
					"application.yml default (memory switched off for this run)",
					RoutingPolicy.configDefault(request.service(), ModelTier.LLM,
							DEFAULT_CONFIDENCE_THRESHOLD),
					0, List.of(), null);
		}

		var query = request.service() + " " + request.symptom();
		var typeQuery = "INCIDENT_RECORD " + request.service();
		var policyQuery = "SPEND_POLICY ROUTING_POLICY budgetUsd preferredTier " + request.service();
		var routingQuery = "ROUTING_OUTCOME ROUTING_POLICY model tier escalation " + request.service();

		// FIVE INDEPENDENT READS, ISSUED TOGETHER. None depends on another's result, and run in
		// sequence they were five MCP round trips on the critical path of every warm run. Each
		// bridge method already degrades to empty on failure, so a join cannot throw here.
		// Failed reads come back empty so the run survives; this is where they are counted, so
		// the report can say recall was partial instead of passing it off as nothing found.
		var failures = new java.util.concurrent.ConcurrentLinkedQueue<String>();
		java.util.function.Consumer<String> failed = failures::add;
		List<MekoToolBridge.MekoMemory> searchRows;
		List<MekoToolBridge.MekoMemory> symptomRows;
		List<MekoToolBridge.MekoMemory> runbookRows;
		String knowledgeResult;
		String routingResult;
		try (var io = Executors.newVirtualThreadPerTaskExecutor()) {
			var byType = CompletableFuture.supplyAsync(
					() -> meko.memorySearchRecords(typeQuery, datapack.id(), ME_SEARCH_LIMIT, failed), io);
			var bySymptom = CompletableFuture.supplyAsync(
					() -> meko.memorySearchRecords(query, datapack.id(), ME_SEARCH_LIMIT, failed), io);
			// Why two knowledge queries and a separate routing query: see below.
			var runbooks = CompletableFuture.supplyAsync(() -> request.useKnowledge()
					? meko.knowledgeSearchRecords(query, datapack.id(), KO_SEARCH_LIMIT, failed)
					: List.<MekoToolBridge.MekoMemory>of(), io);
			var policies = CompletableFuture.supplyAsync(
					() -> meko.knowledgeSearch(policyQuery, datapack.id(), KO_SEARCH_LIMIT, failed), io);
			var routing = CompletableFuture.supplyAsync(
					() -> meko.memorySearch(routingQuery, datapack.id(), 12, failed), io);
			searchRows = byType.join();
			symptomRows = bySymptom.join();
			runbookRows = runbooks.join();
			knowledgeResult = policies.join();
			routingResult = routing.join();
		}

		var memoryRows = new ArrayList<MekoToolBridge.MekoMemory>(searchRows);
		memoryRows.addAll(symptomRows);

		// The symptom search is what the model should read: it ranks by relevance to THIS
		// incident, where the type-led search ranks by record type.
		//
		// THIS INCIDENT'S OWN RECORDS ARE LEFT OUT OF THE PROMPT. The gate already ignores them as
		// echoes, but the prompt did not: every finished run writes an INCIDENT_RECORD and a
		// ROUTING_OUTCOME under its incident id, so a re-run showed the model its own previous
		// answer as history. Observed: a run that had once concluded "roll back to 4.7.0" was
		// shown that conclusion on every later attempt, and repeated it through three rounds of
		// validation feedback. The rows still reach the gate (which counts them) and the routing
		// policy (where one outcome per incident is the evidence it aggregates).
		var memoryResult = symptomRows.stream()
				.map(MekoToolBridge.MekoMemory::text)
				.filter(text -> !isOwnRecord(text, request.incidentId()))
				.collect(java.util.stream.Collectors.joining("\n"));

		// TWO KNOWLEDGE QUERIES, for the same reason there are two memory queries.
		//
		// "runbook spend policy routing " + service + " " + symptom asked one diluted question
		// on behalf of three different consumers: the model wants runbook prose, parseDouble
		// wants a SPEND_POLICY line, and RoutingPolicyService wants a ROUTING_POLICY line. The
		// knowledge base holds uploaded documents AND promoted policy records, so a mixed query
		// ranks chunks and records against each other and serves neither well. The policy query
		// is short and type-led, like the incident search: policy records are single lines and
		// lose badly to prose chunks on a natural-language query.
		//
		// Routing gets its own query and its own window. It used to share the incident
		// search, where the policy row was scoring 0.45 against incident records at 0.69 and
		// was one corpus-growth away from falling out of the result set unnoticed.
		var runbookText = runbookRows.stream()
				.map(MekoToolBridge.MekoMemory::text)
				.collect(java.util.stream.Collectors.joining("\n\n"));

		// Incidents come from the scored rows; everything else is prose context for the model.
		var priorIncidents = parser.parseIncidents(memoryRows);

		// Spend policy is organizational context, not a constant: read it from Meko knowledge
		// and fall back to configuration only when the datapack has nothing to say.
		double budget = parser.parseDouble(
				knowledgeResult,
				"budgetUsd_" + request.severity().name().toLowerCase(Locale.ROOT),
				fallbackBudget);

		// Promoted knowledge first, then this agent's own outcome history, then config.
		// Which of the three answered is carried through to the report.
		var policy = routingPolicy.resolve(
				request.service(), knowledgeResult, routingResult,
				ModelTier.LLM, DEFAULT_CONFIDENCE_THRESHOLD);

		// Priced from the queries actually sent: each search embeds its query. The runbook search
		// only runs when knowledge is on, so the count and the text follow that, not a constant.
		var sent = new ArrayList<String>(List.of(typeQuery, query, policyQuery, routingQuery));
		if (request.useKnowledge()) {
			sent.add(query);
		}
		var retrievalCost = accounting.recordRetrieval("meko:retrieve", sent.size(), sent.toArray(String[]::new));

		return new MekoContext(
				true,
				List.of("MEMORY:\n" + memoryResult, "ROUTING:\n" + withoutOwnRecords(routingResult, request.incidentId())),
				List.of("RUNBOOK:\n" + runbookText, "POLICY:\n" + knowledgeResult),
				priorIncidents,
				policy.preferredTier(),
				policy.confidenceThreshold(),
				budget,
				policy.rationale(),
				routingPolicy.describe(policy),
				policy,
				memoryRows.size(),
				runbookRows.stream().map(MekoToolBridge.MekoMemory::text).toList(),
				retrievalCost,
				List.copyOf(failures)
		);
	}

	// ------------------------------------------------------------------
	// 2. Deterministic gate. Zero tokens. Decides how much work follows.
	// ------------------------------------------------------------------

	@Action(
			description = "Score historical matches and check the staleness of any stored remediation using "
					+ "deterministic signals only, then decide how much of the investigation can be skipped",
			pre = {"mekoContextAvailable"},
			post = {"reuseDecided", "reuseIsFull", "reuseIsNarrowed", "reuseIsShortCircuit", "reuseNeedsModel"},
			cost = 0.01,
			value = 0.9,
			canRerun = false
	)
	public ReuseDecision evaluateReuse(IncidentRequest request, MekoContext mekoContext) {
		return reuseGate.evaluate(request, mekoContext);
	}

	// ------------------------------------------------------------------
	// 3. Telemetry. Two mutually exclusive actions; the gate picks.
	// ------------------------------------------------------------------

	@Action(
			description = "Gather telemetry narrowed to the services that historical context implicates",
			pre = {"reuseDecided", "reuseIsNarrowed"},
			post = {"evidenceCollected"},
			cost = 0.04,
			value = 0.88,
			canRerun = false
	)
	public IncidentEvidence collectScopedEvidence(IncidentRequest request, ReuseDecision reuseDecision) {
		var scope = reuseDecision.match() == null
				? List.of(request.service())
				: reuseDecision.match().scopeServices();
		var logs = incidentData.scopedLogs(request, scope);
		int skipped = Math.max(0, incidentData.allLogs(request).size() - logs.size());
		return new IncidentEvidence(
				request,
				incidentData.metrics(request),
				logs,
				incidentData.recentChanges(request),
				reuseDecision.mode(),
				scope,
				skipped,
				request.useMeko()
		);
	}

	@Action(
			description = "Gather the full telemetry sweep across every service when no usable history exists",
			pre = {"reuseDecided", "reuseIsFull"},
			post = {"evidenceCollected"},
			cost = 0.30,
			value = 0.9,
			canRerun = false
	)
	public IncidentEvidence collectFullEvidence(IncidentRequest request, ReuseDecision reuseDecision) {
		return new IncidentEvidence(
				request,
				incidentData.metrics(request),
				incidentData.allLogs(request),
				incidentData.recentChanges(request),
				ReuseMode.FULL,
				List.of(),
				0,
				request.useMeko()
		);
	}

	// ------------------------------------------------------------------
	// 4a. The zero-LLM path.
	// ------------------------------------------------------------------

	@Action(
			description = "Serve the stored remediation directly from Meko without any generative call",
			pre = {"evidenceCollected", "reuseIsShortCircuit"},
			post = {"diagnosisAttemptAvailable", "confidenceSufficient", "confidenceInsufficient", "attemptBelowLlm"},
			cost = 0.01,
			value = 0.93,
			canRerun = false
	)
	public DiagnosisAttempt reuseStoredRemediation(ReuseDecision reuseDecision) {
		var match = reuseDecision.match();
		var diagnosis = new Diagnosis(
				match == null ? "unknown" : match.rootCause(),
				Math.min(0.95, reuseDecision.matchScore()),
				"Served from Meko incident %s without a generative call. Deterministic match score %.2f, "
						.formatted(match == null ? "unknown" : match.incidentId(), reuseDecision.matchScore())
						+ "deployment state confirmed current.",
				match == null ? "unknown" : match.remediation(),
				true,
				match == null ? List.of() : List.of(match.remediation())
		);

		// Validated on the way out, exactly like a generated answer. A stored remediation is
		// not privileged just because it was right last time.
		var validation = validator.validate(diagnosis);
		var settled = validation.valid() ? diagnosis : demote(diagnosis, validation);

		return new DiagnosisAttempt(settled, ModelTier.NONE, "none", false, true, validation, List.of());
	}

	// ------------------------------------------------------------------
	// 4b. Tiered diagnosis. Tier is per-action, not a global switch.
	// ------------------------------------------------------------------

	@Action(
			description = "Diagnose with the small model, the cheapest sufficient option for routine regressions",
			pre = {"evidenceCollected", "reuseNeedsModel"},
			post = {"diagnosisAttemptAvailable", "confidenceSufficient", "confidenceInsufficient", "attemptBelowLlm"},
			costMethod = "slmCost",
			value = 0.90,
			canRerun = false
	)
	public DiagnosisAttempt diagnoseWithSlm(
			IncidentEvidence evidence, MekoContext mekoContext, ReuseDecision reuseDecision, @Provided Ai ai) {
		return diagnose(evidence, mekoContext, reuseDecision, ai, "slm", ModelTier.SLM, false, null);
	}

	@Action(
			description = "Diagnose with the balanced model",
			pre = {"evidenceCollected", "reuseNeedsModel"},
			post = {"diagnosisAttemptAvailable", "confidenceSufficient", "confidenceInsufficient", "attemptBelowLlm"},
			costMethod = "llmCost",
			value = 0.92,
			canRerun = false
	)
	public DiagnosisAttempt diagnoseWithLlm(
			IncidentEvidence evidence, MekoContext mekoContext, ReuseDecision reuseDecision, @Provided Ai ai) {
		return diagnose(evidence, mekoContext, reuseDecision, ai, "llm", ModelTier.LLM, false, null);
	}

	@Action(
			description = "Diagnose with the strongest available model when the incident warrants it",
			pre = {"evidenceCollected", "reuseNeedsModel"},
			post = {"diagnosisAttemptAvailable", "confidenceSufficient", "confidenceInsufficient", "attemptBelowLlm"},
			costMethod = "frontierCost",
			value = 0.98,
			canRerun = false
	)
	public DiagnosisAttempt diagnoseWithFrontier(
			IncidentEvidence evidence, MekoContext mekoContext, ReuseDecision reuseDecision, @Provided Ai ai) {
		return diagnose(evidence, mekoContext, reuseDecision, ai, "frontier", ModelTier.FRONTIER, false, null);
	}

	// ------------------------------------------------------------------
	// 5. Escalation. Fires on low confidence OR failed validation, because a
	//    failed validation demotes confidence below the threshold.
	// ------------------------------------------------------------------

	// ONLY UPWARD. Without attemptBelowLlm this ran after a FRONTIER attempt had failed — once
	// diagnoseWithFrontier and escalateWithFrontier were spent, it was the one escalation left,
	// so the planner "escalated" down to a weaker model and reported it as an escalation.
	@Action(
			description = "Escalate an insufficient diagnosis to the balanced model",
			pre = {"diagnosisAttemptAvailable", "confidenceInsufficient", "attemptBelowLlm"},
			post = {"diagnosisAttemptAvailable", "confidenceSufficient", "confidenceInsufficient", "attemptBelowLlm"},
			costMethod = "llmEscalationCost",
			value = 0.96,
			canRerun = false
	)
	public DiagnosisAttempt escalateWithLlm(
			IncidentEvidence evidence, MekoContext mekoContext, ReuseDecision reuseDecision,
			DiagnosisAttempt previous, @Provided Ai ai) {
		return diagnose(evidence, mekoContext, reuseDecision, ai, "llm", ModelTier.LLM, true, previous);
	}

	@Action(
			description = "Escalate an insufficient diagnosis to the frontier model",
			pre = {"diagnosisAttemptAvailable", "confidenceInsufficient"},
			post = {"diagnosisAttemptAvailable", "confidenceSufficient", "confidenceInsufficient", "attemptBelowLlm"},
			costMethod = "frontierEscalationCost",
			value = 1.0,
			canRerun = false
	)
	public DiagnosisAttempt escalateWithFrontier(
			IncidentEvidence evidence, MekoContext mekoContext, ReuseDecision reuseDecision,
			DiagnosisAttempt previous, @Provided Ai ai) {
		return diagnose(evidence, mekoContext, reuseDecision, ai, "frontier", ModelTier.FRONTIER, true, previous);
	}

	// ------------------------------------------------------------------
	// 6. Finalize and write back.
	// ------------------------------------------------------------------

	/**
	 * TWO GOALS, AND THAT IS WHAT MAKES @Action.value MEAN ANYTHING.
	 * <p>
	 * Embabel ranks PLANS by netValue = goal.value + sum(action.value) - sum(action.cost), and
	 * ranking only bites when more than one goal is reachable from the same state. With a single
	 * goal there was nothing to rank: every routing decision went through cost, and the 0.88-1.0
	 * spread of action values was decoration.
	 * <p>
	 * The second goal is reachable ALONGSIDE this one — handOffToHuman needs only a diagnosis
	 * attempt, with no confidence requirement — so from any state carrying an attempt the planner
	 * genuinely chooses between "escalate again and finalize" and "stop and hand over". That is a
	 * real decision: escalation cost is normalised against the severity budget Meko supplies, so
	 * a SEV3 at its ceiling hands off where a SEV1 keeps paying. Spend policy stops being a
	 * number on a report and starts selecting the plan.
	 */
	@AchievesGoal(
			description = "Produce a validated incident diagnosis, durable learning and a routing outcome",
			value = 1.0)
	@Action(
			pre = {"diagnosisAttemptAvailable", "confidenceSufficient"},
			post = {"incidentReportProduced"},
			cost = 0.02,
			value = 1.0,
			canRerun = false
	)
	public IncidentReport finalizeIncident(
			IncidentEvidence evidence,
			MekoContext mekoContext,
			ReuseDecision reuseDecision,
			DiagnosisAttempt attempt) {

		var diagnosis = attempt.diagnosis();

		var ledger = new TokenLedger();
		attempt.ledgerEntries().forEach(ledger::add);
		ledger.addRetrieval(mekoContext.retrievalCost());
		accounting.recordCounterfactual(ledger, counterfactualPrompt(evidence, mekoContext), "frontier",
				observedOutput(attempt));

		var writeBack = writeBack(evidence, mekoContext, reuseDecision, attempt, ledger);
		ledger.addRetrieval(writeBack.cost());

		var models = new ArrayList<String>();
		attempt.ledgerEntries().forEach(e -> models.add(e.modelRole()));
		if (models.isEmpty()) {
			models.add("none (served from Meko)");
		}

		return report(
				evidence, mekoContext, reuseDecision, attempt, ledger, models,
				IncidentOutcome.DIAGNOSED,
				summaryFor(reuseDecision, attempt),
				writeBack.persisted(),
				writeBack.policyShared());
	}

    /*
      Both goals produce an IncidentReport, and both must: AgentInvocation is built for that
      type, so a goal returning anything else makes last(IncidentReport) null and the caller
      NPEs. Outcome is what distinguishes them.
     */

	/**
	 * Break the prompt into where each part came from.
	 * <p>
	 * Deliberately measured from the SAME blocks the template renders, so the numbers describe
	 * the prompt that was actually sent rather than an estimate of one. Telemetry is the bulk on
	 * a cold run; on a warm run the interesting line is how little memory and knowledge cost to
	 * add relative to the telemetry they let the plan skip.
	 */
	private List<ContextSource> contextSources(IncidentEvidence evidence, MekoContext mekoContext) {
		var sources = new ArrayList<ContextSource>();
		sources.add(new ContextSource("Telemetry", "live systems",
				evidence.metrics().size() + evidence.logs().size() + evidence.recentChanges().size(),
				accounting.estimate(evidence.asPromptBlock())));

		if (!mekoContext.enabled()) {
			return List.copyOf(sources);
		}
		sources.add(new ContextSource("Prior incidents", "learned memory",
				mekoContext.priorIncidents().size(),
				accounting.estimate(mekoContext.memoryBlock())));
		sources.add(new ContextSource("Runbooks", "curated knowledge",
				mekoContext.runbookChunks().size(),
				accounting.estimate(String.join("\n", mekoContext.runbookChunks()))));
		sources.add(new ContextSource("Routing policy", "learned memory",
				mekoContext.routingPolicy() == null ? 0 : 1,
				accounting.estimate(mekoContext.routingRationale())));
		return List.copyOf(sources);
	}

	private IncidentReport report(
			IncidentEvidence evidence,
			MekoContext mekoContext,
			ReuseDecision reuseDecision,
			DiagnosisAttempt attempt,
			TokenLedger ledger,
			List<String> models,
			IncidentOutcome outcome,
			String summary,
			boolean persisted,
			boolean policyShared) {

		var diagnosis = attempt.diagnosis();
		var modelsUsed = models.isEmpty() ? List.of("none (served from Meko)") : models;

		return new IncidentReport(
				evidence.incident().incidentId(),
				evidence.incident().useMeko() ? "WITH_MEKO" : "WITHOUT_MEKO",
				outcome,
				summary,
				diagnosis.rootCause(),
				diagnosis.confidence(),
				diagnosis.recommendedAction(),
				learningRecord(evidence, attempt),
				diagnosis.resemblesHistoricalIncident(),
				persisted,
				mekoContext.contextItems(),
				mekoContext.priorIncidents(),
				mekoContext.memoryRowsReturned(),
				mekoContext.runbookChunks(),
				contextSources(evidence, mekoContext),
				contextSources(evidence, mekoContext).stream()
						.mapToLong(ContextSource::tokens).sum(),
				attempt.modelTier(),
				attempt.modelRole(),
				modelsUsed,
				mekoContext.routingRationale(),
				mekoContext.policySource(),
				mekoContext.routingPolicy() == null ? 0 : mekoContext.routingPolicy().observations(),
				policyShared,
				attempt.escalation(),

				reuseDecision.mode(),
				reuseDecision.matchScore(),
				reuseDecision.match() == null ? 0.0 : reuseDecision.match().semanticScore(),
				reuseDecision.match() == null ? -1.0 : reuseDecision.match().rawScore(),
				reuseDecision.match() == null ? "" : reuseDecision.match().incidentId(),
				reuseDecision.match() == null ? "" : reuseDecision.match().signature(),
				reuseDecision.match() == null ? "" : reuseDecision.match().writtenBy(),
				reuseDecision.selfMatchesIgnored(),
				reuseDecision.stale(),
				reuseDecision.stalenessReason(),
				evidence.scopedTo(),
				evidence.logLinesSkipped(),
				attempt.validation().valid(),
				attempt.validation().failedAssumptions(),

				ledger.llmCalls(),
				ledger.inputTokens(),
				ledger.outputTokens(),
				ledger.usd(),
				ledger.counterfactualTokens(),
				ledger.counterfactualUsd(),
				ledger.tokensSaved(),
				ledger.usdSaved(),
				ledger.fullyMeasured(),
				mekoContext.budgetCeilingUsd(),
				ledger.retrievalCalls(),
				ledger.retrievalUsd(),
				ledger.totalUsd(),
				ledger.entries(),
				ledger.retrievals(),
				// Filled in by the invoker once the process has finished: the report is built
				// inside the final action, before that action's own execution is recorded.
				List.of(),
				mekoContext.recallFailures()
		);
	}

	/**
	 * The other ending: stop, and hand a human everything gathered so far.
	 * <p>
	 * Reachable from ANY state carrying a diagnosis attempt — no confidence precondition — which
	 * is the point. It competes with finalizeIncident rather than replacing it, so the planner
	 * weighs "escalate again, then finalize" against "hand over now" on netValue. Escalation cost
	 * is normalised against the severity budget from Meko, so the same code hands off on a SEV3
	 * at its ceiling and keeps escalating on a SEV1.
	 * <p>
	 * DELIBERATELY WRITES NO INCIDENT_RECORD. The diagnosis was not accepted, and a remediation
	 * nobody accepted must never become history a later run can short-circuit on — that is the
	 * corpus poisoning itself with its own unfinished work. The ROUTING_OUTCOME is still written,
	 * because "this tier could not close this incident" is exactly the evidence routing needs.
	 */
	@AchievesGoal(
			description = "Hand the incident to a human with the evidence gathered, when another "
					+ "escalation is not worth its cost against the severity budget",
			value = 0.6)
	@Action(
			pre = {"diagnosisAttemptAvailable"},
			post = {"incidentReportProduced"},
			cost = 0.01,
			value = 0.55,
			canRerun = false
	)
	public IncidentReport handOffToHuman(
			IncidentEvidence evidence,
			MekoContext mekoContext,
			ReuseDecision reuseDecision,
			DiagnosisAttempt attempt) {

		var diagnosis = attempt.diagnosis();

		var ledger = new TokenLedger();
		attempt.ledgerEntries().forEach(ledger::add);
		ledger.addRetrieval(mekoContext.retrievalCost());
		accounting.recordCounterfactual(ledger, counterfactualPrompt(evidence, mekoContext), "frontier",
				observedOutput(attempt));

		var handoff = handOffWriteBack(evidence, reuseDecision, attempt, ledger);
		ledger.addRetrieval(handoff.cost());

		var models = new ArrayList<String>();
		attempt.ledgerEntries().forEach(e -> models.add(e.modelRole()));

		return report(
				evidence, mekoContext, reuseDecision, attempt, ledger, models,
				IncidentOutcome.HANDED_OFF,
				("Handed to a human at %.0f%% confidence, below the %.0f%% bar. Another escalation "
						+ "was not worth its cost against the $%.2f ceiling for this severity, so the "
						+ "evidence gathered goes to an engineer instead. No remediation was stored: "
						+ "an answer nobody accepted must not become reusable history.")
						.formatted(diagnosis.confidence() * 100,
								mekoContext.confidenceThreshold() * 100,
								mekoContext.budgetCeilingUsd()),
				handoff.persisted(),
				handoff.policyShared());
	}


	@Condition(name = "mekoContextAvailable")
	public boolean mekoContextAvailable(MekoContext mekoContext) {
		return mekoContext != null;
	}

	@Condition(name = "reuseDecided")
	public boolean reuseDecided(ReuseDecision reuseDecision) {
		return reuseDecision != null;
	}

	@Condition(name = "evidenceCollected")
	public boolean evidenceCollected(IncidentEvidence incidentEvidence) {
		return incidentEvidence != null;
	}

	@Condition(name = "diagnosisAttemptAvailable")
	public boolean diagnosisAttemptAvailable(DiagnosisAttempt diagnosisAttempt) {
		return diagnosisAttempt != null;
	}

	@Condition(name = "incidentReportProduced")
	public boolean incidentReportProduced(IncidentReport incidentReport) {
		return incidentReport != null;
	}

	@Condition(name = "confidenceSufficient")
	public boolean confidenceSufficient(DiagnosisAttempt diagnosisAttempt, MekoContext mekoContext) {
		return diagnosisAttempt != null && mekoContext != null
				&& diagnosisAttempt.diagnosis().confidence() >= mekoContext.confidenceThreshold();
	}

	@Condition(name = "confidenceInsufficient")
	public boolean confidenceInsufficient(DiagnosisAttempt diagnosisAttempt, MekoContext mekoContext) {
		return diagnosisAttempt != null && mekoContext != null
				&& diagnosisAttempt.diagnosis().confidence() < mekoContext.confidenceThreshold();
	}

	/** The latest attempt ran below the balanced tier, so escalating to it is a step up. */
	@Condition(name = "attemptBelowLlm")
	public boolean attemptBelowLlm(DiagnosisAttempt diagnosisAttempt) {
		return diagnosisAttempt != null && diagnosisAttempt.modelTier().ordinal() < ModelTier.LLM.ordinal();
	}

		@Condition(name = "reuseIsFull")
	public boolean reuseIsFull(ReuseDecision reuseDecision) {
		return reuseDecision != null && reuseDecision.mode() == ReuseMode.FULL;
	}

	/** SCOPED or SHORT_CIRCUIT: history told us something, so the sweep can be narrowed. */
	@Condition(name = "reuseIsNarrowed")
	public boolean reuseIsNarrowed(ReuseDecision reuseDecision) {
		return reuseDecision != null && reuseDecision.mode() != ReuseMode.FULL;
	}

	@Condition(name = "reuseIsShortCircuit")
	public boolean reuseIsShortCircuit(ReuseDecision reuseDecision) {
		return reuseDecision != null && reuseDecision.mode() == ReuseMode.SHORT_CIRCUIT;
	}

	/** Anything that is not a short circuit still needs a generative call. */
	@Condition(name = "reuseNeedsModel")
	public boolean reuseNeedsModel(ReuseDecision reuseDecision) {
		return reuseDecision != null && reuseDecision.mode() != ReuseMode.SHORT_CIRCUIT;
	}

	// ------------------------------------------------------------------
	// Dynamic planner costs, derived from estimated dollars.
	// ------------------------------------------------------------------

	@Cost(name = "slmCost")
	public double slmCost(@Nullable MekoContext context, @Nullable IncidentEvidence evidence) {
		return plannerCost(ModelTier.SLM, "slm", context, evidence, false);
	}

	@Cost(name = "llmCost")
	public double llmCost(@Nullable MekoContext context, @Nullable IncidentEvidence evidence) {
		return plannerCost(ModelTier.LLM, "llm", context, evidence, false);
	}

	@Cost(name = "frontierCost")
	public double frontierCost(@Nullable MekoContext context, @Nullable IncidentEvidence evidence) {
		return plannerCost(ModelTier.FRONTIER, "frontier", context, evidence, false);
	}

	@Cost(name = "llmEscalationCost")
	public double llmEscalationCost(
			@Nullable MekoContext context, @Nullable IncidentEvidence evidence, @Nullable DiagnosisAttempt previous) {
		return plannerCost(ModelTier.LLM, "llm", context, evidence, true) + wastePenalty(previous, 0.10);
	}

	@Cost(name = "frontierEscalationCost")
	public double frontierEscalationCost(
			@Nullable MekoContext context, @Nullable IncidentEvidence evidence, @Nullable DiagnosisAttempt previous) {
		return plannerCost(ModelTier.FRONTIER, "frontier", context, evidence, true) + wastePenalty(previous, 0.05);
	}

	/**
	 * Planner cost is the estimated dollar cost of the call as a fraction of the severity
	 * budget. This replaces the previous hand-tuned constants, which included a literal
	 * string match on "error_rate = 18.2%" to detect risk — a magic value that made the
	 * demo's central mechanic look like a trick the moment anyone read the source.
	 */
	private double plannerCost(
			ModelTier tier, String role, @Nullable MekoContext context,
			@Nullable IncidentEvidence evidence, boolean escalation) {

		long inputTokens = evidence == null ? 40_000 : accounting.estimate(evidence.asPromptBlock());
		if (context != null && context.enabled()) {
			inputTokens += accounting.estimate(context.memoryBlock()) + accounting.estimate(context.knowledgeBlock());
		}
		double usd = accounting.cost(role, inputTokens, 600);
		double ceiling = context == null ? 0.15 : context.budgetCeilingUsd();
		double normalized = usd / Math.max(ceiling, 0.0001);

		// Learned routing has to be able to route UP, not just discount its favourite.
		//
		// Discounting the preferred tier alone was not enough to change any decision. SLM costs
		// $0.10/$0.40 per million against LLM's $0.30/$2.50, so on a 28k-token prompt SLM lands
		// near $0.0030 and LLM near $0.0099 — a 55% discount on LLM still loses. The policy
		// could say "SLM is not reliable enough for this service, start at LLM", the planner
		// would pick SLM anyway on price, and the report would print the policy's recommendation
		// as though it were the decision taken. It was advice nothing acted on.
		//
		// So a tier BELOW the preferred one is penalised, not merely un-discounted. Escalation
		// history is the only thing that knows a cheap tier keeps failing here, and a routing
		// policy that cannot act on it is decoration.
		//
		// APPLIED WHETHER OR NOT MEKO IS ON, and that matters for the honesty of the A/B.
		//
		// Gating this on enabled() meant the cold run had no routing policy at all and simply
		// took the cheapest tier, while its own report read "no learned routing policy, the
		// planner must assume a full investigation" and then quietly used the SMALLEST model.
		// Two variables moved between the runs, not one, and the page claims memory is the only
		// difference.
		//
		// Turning memory off does not delete your routing policy; it falls back to the
		// configured default, which is what an agent without history would actually do. So the
		// cold run routes on application.yml's default and the warm run routes on what the
		// outcomes taught. Config constant versus learned policy — that IS the comparison.
		//
		// Note this makes the cold run MORE expensive and the saving look bigger. It is still
		// the right baseline: "always pick the cheapest model" is not a policy a real system
		// would run, because it escalates constantly.
		if (context != null) {
			var preferred = context.preferredTier();
			if (preferred == tier) {
				normalized *= 0.45;
			}
			else if (preferred != null && tier.ordinal() < preferred.ordinal()) {
				normalized += 0.5;
			}
		}
		if (escalation) {
			normalized += 0.08;
		}
		return clamp(normalized);
	}

	private double wastePenalty(@Nullable DiagnosisAttempt previous, double weight) {
		if (previous == null) {
			return 0.0;
		}
		return Math.min(0.15, (1.0 - previous.diagnosis().confidence()) * weight);
	}

	private double clamp(double v) {
		return Math.clamp(v, 0.01, 0.99);
	}

	// ------------------------------------------------------------------
	// Diagnosis execution
	// ------------------------------------------------------------------

	private DiagnosisAttempt diagnose(
			IncidentEvidence evidence,
			MekoContext mekoContext,
			ReuseDecision reuseDecision,
			Ai ai,
			String role,
			ModelTier tier,
			boolean escalation,
			@Nullable DiagnosisAttempt previous) {

		var prompt = buildPrompt(evidence, mekoContext, reuseDecision, role, tier, previous);

		var process = AgentProcess.get();
		int callsBefore = process == null ? 0 : process.getLlmInvocations().size();

		Diagnosis raw = ai.withLlmByRole(role).createObject(prompt, Diagnosis.class);

		var validation = validator.validate(raw, reuseDecision);
		var diagnosis = validation.valid() ? raw : demote(raw, validation);

		// ACCOUNTING SEAM. Embabel records provider-reported usage on the agent process for
		// every HTTP call, so the delta across createObject is what this diagnosis cost —
		// data-binding retries included, and reasoning tokens included, since providers bill
		// those as completion tokens. Falls back to estimation when usage is absent.
		var usage = usageSince(process, callsBefore);
		var entry = accounting.record(
				(escalation ? "escalation:" : "diagnosis:") + role,
				tier,
				role,
				usage == null ? null : usage.input(),
				usage == null ? null : usage.output(),
				prompt,
				String.valueOf(raw)
		);

		var entries = new ArrayList<TokenLedger.Entry>();
		if (previous != null) {
			entries.addAll(previous.ledgerEntries());
		}
		entries.add(entry);

		return new DiagnosisAttempt(diagnosis, tier, role, escalation, false, validation, entries);
	}

	private record ReportedUsage(long input, long output) {}

	/** A record this incident wrote about itself: one that carries its incident id. */
	static boolean isOwnRecord(String text, @Nullable String incidentId) {
		if (text == null || incidentId == null || incidentId.isBlank()) {
			return false;
		}
		return java.util.regex.Pattern.compile(
				"(?i)(?:^|[;\\s])id=" + java.util.regex.Pattern.quote(incidentId.strip()) + "\\s*(?:;|$)",
				java.util.regex.Pattern.MULTILINE).matcher(text).find();
	}

	/** The flattened search text, minus the lines that are this incident's own records. */
	static String withoutOwnRecords(String flattened, @Nullable String incidentId) {
		if (flattened == null || flattened.isBlank()) {
			return "";
		}
		return java.util.Arrays.stream(flattened.split("\n"))
				.filter(line -> !isOwnRecord(line, incidentId))
				.collect(java.util.stream.Collectors.joining("\n"));
	}

	/** Output tokens of this run's most recent diagnosis call, or null when it made none. */
	private static @Nullable Long observedOutput(DiagnosisAttempt attempt) {
		var entries = attempt.ledgerEntries();
		return entries.isEmpty() ? null : entries.getLast().outputTokens();
	}

	/**
	 * Provider-reported tokens for the calls made since {@code callsBefore}, or null when any of
	 * them came back without usage. Partial usage is not reported as measured: a ledger line
	 * that is half read and half guessed would claim a provenance it does not have.
	 */
	private static @Nullable ReportedUsage usageSince(@Nullable AgentProcess process, int callsBefore) {
		if (process == null) {
			return null;
		}
		var calls = process.getLlmInvocations();
		if (calls.size() <= callsBefore) {
			return null;
		}
		long input = 0;
		long output = 0;
		for (var call : calls.subList(callsBefore, calls.size())) {
			var usage = call.getUsage();
			// Some providers answer with an empty usage block rather than none; zero prompt
			// tokens is never a real measurement of a prompt this size.
			if (usage.getPromptTokens() == null || usage.getCompletionTokens() == null
					|| usage.getPromptTokens() == 0) {
				return null;
			}
			input += usage.getPromptTokens();
			output += usage.getCompletionTokens();
		}
		return new ReportedUsage(input, output);
	}

	private Diagnosis demote(Diagnosis diagnosis, ValidationResult validation) {
		return new Diagnosis(
				diagnosis.rootCause(),
				FAILED_VALIDATION_CONFIDENCE,
				diagnosis.reasoning() + "\n\nVALIDATION FAILED: " + String.join("; ", validation.failedAssumptions()),
				diagnosis.recommendedAction(),
				diagnosis.resemblesHistoricalIncident(),
				diagnosis.remediationAssumptions()
		);
	}

	/**
	 * Renders classpath:/prompts/incident-diagnosis.jinja.
	 * <p>
	 * The text used to be a Java text block. Prompt wording is content, not code — it gets
	 * revised far more often than the logic around it, and by people who should not have to
	 * open a .java file and trigger a rebuild to do it. Embabel already registers a Jinjava
	 * TemplateRenderer as a bean, so this needs no new dependency and no new machinery.
	 */
	private String buildPrompt(
			IncidentEvidence evidence,
			MekoContext mekoContext,
			ReuseDecision reuseDecision,
			String role,
			ModelTier tier,
			@Nullable DiagnosisAttempt previous) {

		var previousText = previous == null
				? "No previous model attempt."
				: "Previous attempt (%s) reached confidence %.2f with root cause: %s. %s".formatted(
				previous.modelTier(),
				previous.diagnosis().confidence(),
				previous.diagnosis().rootCause(),
				previous.validation().valid() ? "" : "It failed validation: "
						+ String.join(" ", previous.validation().failedAssumptions()));

		// Jinjava renders an absent key as empty rather than failing, so every key the template
		// names is supplied here even when the value is empty. A silently half-rendered prompt
		// is a much worse failure than a missing-key exception would have been.
		var model = new LinkedHashMap<String, Object>();
		model.put("tier", tier.name().toLowerCase(Locale.ROOT));
		model.put("role", role);
		model.put("previousAttempt", previousText);
		model.put("incident", String.valueOf(evidence.incident()));
		model.put("scope", String.valueOf(evidence.scope()));
		model.put("scopedTo", evidence.scopedTo());
		model.put("evidence", evidence.asPromptBlock());
		model.put("memory", mekoContext.memoryBlock());
		model.put("knowledge", mekoContext.knowledgeBlock());
		model.put("reuseAssessment", reuseDecision.explanation());
		model.put("routingRationale", mekoContext.routingRationale());
		return templates.renderLoadedTemplate("incident-diagnosis", model);
	}

	/**
	 * The prompt the pruned plan would have sent. Built from strings; costs nothing.
	 * <p>
	 * Rendered from a template for the same reason as the real prompt, and kept next to it so
	 * the two are edited together — the counterfactual is only a valid baseline while its
	 * evidence section matches what the real prompt would have carried.
	 */
	private String counterfactualPrompt(IncidentEvidence evidence, MekoContext mekoContext) {
		var full = new IncidentEvidence(
				evidence.incident(),
				incidentData.metrics(evidence.incident()),
				incidentData.allLogs(evidence.incident()),
				incidentData.recentChanges(evidence.incident()),
				ReuseMode.FULL, List.of(), 0, evidence.mekoEnabled()
		);
		return templates.renderLoadedTemplate("counterfactual", Map.of(
				"evidence", full.asPromptBlock(),
				"memory", mekoContext.memoryBlock()
		));
	}

	// ------------------------------------------------------------------
	// Write-back
	// ------------------------------------------------------------------

	/**
	 * Upsert this run's learning, then let the routing policy graduate if it has earned it.
	 * IDEMPOTENT ON INCIDENT ID, and that is not a nicety. Every run used to append, so
	 * re-running one incident left two rows whose prose differed only because the model
	 * reworded it. Measured on the live datapack, two such rows scored 0.690 and 0.689 while
	 * the hand-seeded ground truth for the same incident scored 0.643 — the agent's own
	 * paraphrases outranked the corpus they were supposed to be learning from. A demo that is
	 * run repeatedly would degrade a little each time, and the reuse gate would start matching
	 * against restatements of restatements.
	 */
	private WriteBack writeBack(
			IncidentEvidence evidence,
			MekoContext mekoContext,
			ReuseDecision reuseDecision,
			DiagnosisAttempt attempt,
			TokenLedger ledger) {

		if (!evidence.incident().useMeko() || !mekoConfigured()) {
			return WriteBack.skipped();
		}

		var incidentId = evidence.incident().incidentId();
		var service = evidence.incident().service();

		// One lookup covers both upserts. Semantic search is imprecise, so the id match below
		// is what actually decides: a miss costs a duplicate, never a wrong overwrite.
		var lookupQuery = "INCIDENT_RECORD ROUTING_OUTCOME " + incidentId + " " + service;
		var existing = meko.memorySearchRecords(lookupQuery, datapack.id(), 20);

		// The two upserts touch different rows, and sharing acts on the policy read at retrieval
		// rather than on these writes, so none waits on another here. The bridge still sends Meko
		// writes one at a time (see MekoToolBridge.call): each runs a server-side extraction, and
		// sent together they queued past the client timeout.
		boolean learningPersisted;
		boolean routingPersisted;
		RoutingPolicyService.ShareOutcome sharing;
		var learning = learningRecord(evidence, attempt);
		var outcome = routingOutcome(evidence, reuseDecision, attempt, ledger);
		try (var io = Executors.newVirtualThreadPerTaskExecutor()) {
			var learningWrite = CompletableFuture.supplyAsync(
					() -> upsert(existing, "INCIDENT_RECORD", incidentId, learning), io);
			var routingWrite = CompletableFuture.supplyAsync(
					() -> upsert(existing, "ROUTING_OUTCOME", incidentId, outcome), io);
			// Observations accumulate cheaply; only a confirmed one is worth sharing. This is the
			// step that turns one agent's private experience into organizational policy.
			var share = CompletableFuture.supplyAsync(
					() -> routingPolicy.shareIfReady(mekoContext.routingPolicy(), datapack.id()), io);
			learningPersisted = learningWrite.join();
			routingPersisted = routingWrite.join();
			sharing = share.join();
		}

		// Priced from what was sent: the lookup query, the two records written, and whatever
		// sharing actually did — which is usually nothing, and was counted as either 0 or 2 calls.
		int calls = 3 + sharing.mekoCalls();
		return new WriteBack(
				learningPersisted && routingPersisted,
				sharing.shared(),
				accounting.recordRetrieval("meko:write", calls, lookupQuery, learning, outcome, sharing.written()));
	}

	/**
	 * Write-back for a handed-off incident: the routing outcome only.
	 * <p>
	 * No INCIDENT_RECORD, deliberately. The diagnosis did not clear the bar and no human has
	 * accepted it, so storing it as history would let a later run short-circuit on an answer
	 * this run explicitly declined to stand behind. The routing outcome IS written, because
	 * "this tier could not close this incident" is precisely the evidence the routing policy
	 * needs — a handoff is a failure for the tier, and hiding it would flatter the next
	 * routing decision.
	 */
	private WriteBack handOffWriteBack(
			IncidentEvidence evidence, ReuseDecision reuseDecision, DiagnosisAttempt attempt,
			TokenLedger ledger) {

		if (!evidence.incident().useMeko() || !mekoConfigured()) {
			return WriteBack.skipped();
		}
		var incidentId = evidence.incident().incidentId();
		var lookupQuery = "ROUTING_OUTCOME " + incidentId + " " + evidence.incident().service();
		var existing = meko.memorySearchRecords(lookupQuery, datapack.id(), 20);

		var outcome = routingOutcome(evidence, reuseDecision, attempt, ledger) + "; handedOff=true";
		boolean persisted = upsert(existing, "ROUTING_OUTCOME", incidentId, outcome);

		return new WriteBack(persisted, false,
				accounting.recordRetrieval("meko:handoff", 2, lookupQuery, outcome));
	}

	/** Update the row for this incident if there is one, otherwise create it. */
	private boolean upsert(
			List<MekoToolBridge.MekoMemory> existing, String prefix, String incidentId, String record) {
		var match = existing.stream()
				.filter(m -> m.text().startsWith(prefix))
				.filter(m -> m.text().contains("id=" + incidentId + ";"))
				.findFirst();
		return match.map(mekoMemory -> meko.memoryUpdate(mekoMemory.id(), record, datapack.id()))
				.orElseGet(() -> meko.memoryAdd(record, datapack.id()).ok());
	}

	/**
	 * One observation per incident — not an aggregate. RoutingPolicyService does the
	 * aggregation, so a rerun of the same incident corrects its row instead of inflating the
	 * sample it is about to be judged by.
	 */
	private String routingOutcome(
			IncidentEvidence evidence, ReuseDecision reuseDecision, DiagnosisAttempt attempt, TokenLedger ledger) {
		return ("ROUTING_OUTCOME; id=%s; service=%s; reuseMode=%s; matchScore=%.2f; modelTier=%s; "
				+ "escalated=%s; llmCalls=%d; inputTokens=%d; outputTokens=%d; usd=%.5f; "
				+ "confidence=%.2f; validated=%s; confirmationOfPriorLearning=%s")
				.formatted(
						evidence.incident().incidentId(),
						evidence.incident().service(),
						reuseDecision.mode(),
						reuseDecision.matchScore(),
						attempt.modelTier(),
						attempt.escalation(),
						ledger.llmCalls(),
						ledger.inputTokens(),
						ledger.outputTokens(),
						ledger.usd(),
						attempt.diagnosis().confidence(),
						attempt.validation().valid(),
						attempt.servedFromMemory());
	}

	/** Outcome of the write-back, including what it cost us in Meko calls. */
	private record WriteBack(boolean persisted, boolean policyShared, TokenLedger.Retrieval cost) {
		static WriteBack skipped() {
			return new WriteBack(false, false, null);
		}
	}

	private String learningRecord(IncidentEvidence evidence, DiagnosisAttempt attempt) {
		var d = attempt.diagnosis();
		var deployService = incidentData.deployServiceFor(evidence.incident().service());
		// Blank, not "null", when the service has no tracked version: the gate then reports the
		// record as unconfirmable and will narrow with it but never short-circuit on it.
		var deployVersion = java.util.Objects.toString(incidentData.currentVersion(deployService), "");
		return ("INCIDENT_RECORD; id=%s; service=%s; signature=%s; deployService=%s; "
				+ "deployVersion=%s; rootCause=%s; "
				+ "remediation=%s; scopeServices=%s; observedAt=%s; modelTier=%s; confidence=%.2f")
				.formatted(
						evidence.incident().incidentId(),
						evidence.incident().service(),
						evidence.incident().symptom(),
						deployService,
						deployVersion,
						d.rootCause(),
						d.recommendedAction(),
						evidence.scopedTo().isEmpty()
								? evidence.incident().service()
								: String.join(",", evidence.scopedTo()),
						java.time.Instant.now(),
						attempt.modelTier(),
						d.confidence());
	}

	private String summaryFor(ReuseDecision reuseDecision, DiagnosisAttempt attempt) {
		if (attempt.servedFromMemory()) {
			return "Answered from Meko with no generative call. " + reuseDecision.explanation();
		}
		if (reuseDecision.stale()) {
			return "A strong historical match was found but rejected as stale, so it was used only to "
					+ "narrow telemetry. " + reuseDecision.stalenessReason();
		}
		if (reuseDecision.scoped()) {
			return "Historical context pruned the investigation; the planner ran a narrowed sweep and "
					+ "routed to the " + attempt.modelRole() + " tier.";
		}
		if (reuseDecision.selfMatchesIgnored() > 0) {
			return "This incident's own earlier write-up was ignored as an echo, and no other stored "
					+ "incident matched, so the planner ran the full sweep and routed to the "
					+ attempt.modelRole() + " tier.";
		}
		return "No usable history: the planner ran the full sweep and routed to the "
				+ attempt.modelRole() + " tier.";
	}

	private boolean mekoConfigured() {
		return datapack.configured() && meko.available();
	}
}