package meko.agentic.sre.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import meko.agentic.sre.meko.MekoDatapack;
import meko.agentic.sre.meko.MekoToolBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Writes the demo corpus into Meko, in two tiers.
 * <p>
 * Extracted from MekoController so the scenario endpoints can arm the whole room in one
 * call instead of asking the operator to remember the order: seed, then set the deployment
 * state, then fill the form. On stage that order is exactly the thing that gets forgotten.
 * <p>
 * THE TWO TIERS ARE THE POINT.
 * <p>
 * Everything used to go through memory_add, which put SPEND_POLICY and ROUTING_POLICY in mem0
 * alongside incident records. Two consequences, both silent:
 * <p>
 *   - IncidentCommanderAgent reads the spend ceiling with
 *     parseDouble(knowledgeResult, "budgetUsd_sev2", fallback). SPEND_POLICY was never in the
 *     knowledge base, so that lookup ALWAYS returned the application.yml fallback. The demo
 *     claimed the budget was organizational policy read from Meko while using a constant.
 * <p>
 *   - Policy rows competed with incident records for one top-k window. Measured on the live
 *     datapack, the seeded ROUTING_POLICY scored 0.450 against incident records at 0.64-0.69.
 * <p>
 * There is no knowledgebase_add on the MCP surface — the only path from an agent into the
 * knowledge base is memory_add followed by memory_promote. That is not a workaround, it is the
 * intended shape: a record earns its way out of one agent's private store into shared
 * knowledge. The seed does deliberately, once, what the routing policy does gradually.
 * <p>
 * Runbook PROSE is not seeded here at all. It belongs in uploaded Markdown where the chunker
 * can split it usefully — see sample-data/knowledge/. Structured records must survive intact
 * on one line, so the two can never share a file.
 */
@Service
public class SeedService {

	/**
	 * The March incident the corpus is seeded with. Public because the scenarios predict a match
	 * against it and the page checks that prediction: one constant, so the seed and the claim
	 * about it cannot drift apart.
	 */
	public static final String RECORDED_INCIDENT_ID = "INC-1842";

	/**
	 * The deployment the recorded remediation depends on. Staleness is checked against exactly
	 * this pair, so the page reads it from here rather than restating it.
	 */
	public static final String RECORDED_DEPLOY_SERVICE = "payment-service";
	public static final String RECORDED_DEPLOY_VERSION = IncidentDataService.INCIDENT_ERA_PAYMENT_VERSION;

	/**
	 * Services the seeded corpus actually knows about.
	 * <p>
	 * Surfaced to the UI so "this service has history" is visible BEFORE a run rather than
	 * inferred from a disappointing result. Picking a service with no history is a legitimate
	 * thing to demo — it is scenario 3 — but it should be a choice, not a surprise.
	 */
	public static final java.util.Set<String> SEEDED_SERVICES =
			java.util.Set.of("checkout", "payment-client", "payment-api");

	private static final Logger log = LoggerFactory.getLogger(SeedService.class);

	private final MekoToolBridge meko;
	private final MekoDatapack datapack;
	private final String deployBotAgentId;

	public SeedService(
			MekoToolBridge meko,
			IncidentDataService incidentData,
			MekoDatapack datapack,
			@Value("${meko.deploy-bot-agent-id:platform-deploy-bot}") String deployBotAgentId) {
		this.meko = meko;
		this.datapack = datapack;
		this.deployBotAgentId = deployBotAgentId;
	}

	/**
	 * One seeded row: the text to write, and a stable key for finding it again.
	 * <p>
	 * The key is carried in the record itself as {@code seedKey=...} rather than derived from
	 * the content, because the content changes — that is the whole point of re-seeding — and a
	 * key that changes with the content is not a key.
	 */
	private record SeedRecord(String key, String text, String agentId) {
		String withKey() {
			return text + "; seedKey=" + key;
		}
	}

	/**
	 * Put the demo corpus in place, without duplicating what is already there.
	 * <p>
	 * IDEMPOTENCE IS NOT OPTIONAL HERE. Arming a scenario seeds, and a presenter arms all three
	 * in a row, so a non-idempotent seed triples the corpus before the demo has even started.
	 * Two different mechanisms are needed, because the two tiers fail differently:
	 * <p>
	 *   MEMORY    mem0 hashes on text, so an identical re-seed is a no-op — but the incident
	 *             record embeds a version, so it is NOT always identical. Matched on seedKey
	 *             and updated in place.
	 * <p>
	 *   KNOWLEDGE Promotion EVICTS the row from mem0. So on the next seed mem0 has nothing to
	 *             deduplicate against, writes a fresh row, and promotes that too — one more
	 *             copy of SPEND_POLICY in the knowledge base on every single arm, each one
	 *             competing with the others in knowledgebase_search. The only reliable check is
	 *             to ask the knowledge base what it already holds, before writing anything.
	 */
	public SeedResult seed() {
		if (!datapack.configured()) {
			// Carry the diagnosis. "No datapack resolved" alone fires identically whether the
			// MCP client never connected, the key is wrong, datapack_list is not in the tool
			// set, or the name genuinely does not exist — four different fixes, one message.
			return new SeedResult(false,
					("No datapack resolved (name='%s'): %s  GET /api/demo/meko/status for the "
							+ "registered tool list, or pin meko.datapack-id to skip the lookup.")
							.formatted(datapack.configuredName(), datapack.diagnosis()),
					List.of(), List.of(), false, 0, 0, false);
		}
		var datapackId = datapack.id();

		// ---- Tier 1: observations. These belong in memory and stay there. ----
		var observations = List.of(
				new SeedRecord("incident-1842",
						"INCIDENT_RECORD; id=" + RECORDED_INCIDENT_ID + "; service=checkout; "
								+ "signature=checkout failures payment provider timeout after deployment; "
								+ "deployService=" + RECORDED_DEPLOY_SERVICE + "; deployVersion="
								+ RECORDED_DEPLOY_VERSION + "; "
								+ "rootCause=Payment client upstream timeout was lowered below the provider "
								+ "p95, so normal provider latency began failing checkout; "
								+ "remediation=Roll back payment-service to the previous release and restore "
								+ "the 5s upstream timeout, then alert on payment-api timeout rate after "
								+ "deploys; scopeServices=checkout,payment-client,payment-api; "
								+ "observedAt=2026-03-02T09:14:00Z",
						null),

				new SeedRecord("routing-checkout",
						"ROUTING_OUTCOME; service=checkout; modelTier=SLM; attempts=17; escalations=1; "
								+ "successRate=0.94; meanInputTokens=4100; meanUsd=0.00121; "
								+ "note=SLM sufficient for scoped payment-timeout regressions.",
						null),

				// Written by a DIFFERENT agent, to prove cross-agent recall.
				new SeedRecord("deploy-payment",
						"DEPLOY_EVENT; service=payment-service; version="
								+ IncidentDataService.INCIDENT_ERA_PAYMENT_VERSION + "; "
								+ "changedBy=" + deployBotAgentId + "; observedAt=2026-03-02T08:51:00Z; "
								+ "change=Lowered upstream payment provider timeout from 5s to 3s to shed "
								+ "load; note=Provider p95 measured 4.8s in the week before this change.",
						deployBotAgentId)
		);

		// Both listings up front and together: they are independent, and on a re-arm they are
		// usually the only Meko calls the seed needs to make.
		List<MekoToolBridge.MekoMemory> existing;
		List<MekoToolBridge.MekoMemory> promotedRows;
		try (var io = Executors.newVirtualThreadPerTaskExecutor()) {
			var memoryListing = CompletableFuture.supplyAsync(() -> meko.memoryGetAll(datapackId, false), io);
			var sharedListing = CompletableFuture.supplyAsync(() -> meko.memoryGetAll(datapackId, true), io);
			existing = memoryListing.join();
			promotedRows = sharedListing.join();
		}

		// WRITE ONLY WHAT DIFFERS. Every arm used to rewrite all three seed rows, although their
		// content is constant — arming is the step a presenter repeats most, and each update is a
		// Meko write that re-processes the row. A row whose text already matches is left alone;
		// one that drifted (a schema change, a hand edit) is still corrected in place.
		var writes = new ArrayList<Supplier<String>>();
		int unchanged = 0;
		for (var record : observations) {
			var match = existing.stream()
					.filter(m -> m.text().contains("seedKey=" + record.key()))
					.findFirst();
			if (match.isPresent()) {
				if (sameText(match.get().text(), record.withKey())) {
					unchanged++;
					continue;
				}
				var id = match.get().id();
				writes.add(() -> meko.memoryUpdate(id, record.withKey(), datapackId) ? "updated" : "failed");
			}
			else {
				writes.add(() -> (record.agentId() == null
						? meko.memoryAdd(record.withKey(), datapackId)
						: meko.memoryAdd(record.withKey(), datapackId, record.agentId())).ok() ? "written" : "failed");
			}
		}

		// The rows are independent, so none waits on another here; the bridge still sends Meko
		// writes one at a time, because each runs a server-side extraction (see MekoToolBridge.call).
		List<String> outcomes;
		try (var io = Executors.newVirtualThreadPerTaskExecutor()) {
			var futures = writes.stream().map(w -> CompletableFuture.supplyAsync(w, io)).toList();
			outcomes = futures.stream().map(CompletableFuture::join).toList();
		}
		int written = (int) outcomes.stream().filter("written"::equals).count();
		int updated = (int) outcomes.stream().filter("updated"::equals).count();

		// ---- Tier 2: policy. Written, then promoted into shared knowledge. ----
		var knowledgeRecords = List.of(
				"SPEND_POLICY; budgetUsd_sev1=0.50; budgetUsd_sev2=0.15; budgetUsd_sev3=0.05; "
						+ "rationale=Spend ceiling is set by severity. SEV-3 must not exceed five cents; "
						+ "SEV-1 may use frontier models immediately.",

				"ROUTING_POLICY; service=checkout; preferredTier=SLM; confidenceThreshold=0.75; "
						+ "observations=17; successRate=0.94; meanUsd=0.00121; "
						+ "rationale=Checkout deployment regressions are reliably classified by the small "
						+ "model when telemetry is scoped. Escalate to the balanced model below 0.75 "
						+ "confidence, and to frontier only when evidence conflicts."
		);
		boolean alreadyShared =
				promotedRows.stream().anyMatch(m -> m.text().startsWith("SPEND_POLICY"))
						&& promotedRows.stream().anyMatch(m -> m.text().startsWith("ROUTING_POLICY"));

		boolean promoted = alreadyShared;
		if (!alreadyShared) {
			var promotable = new ArrayList<String>();
			for (var record : knowledgeRecords) {
				meko.memoryAdd(record, datapackId).memoryId().ifPresent(promotable::add);
			}
			promoted = meko.memoryPromote(promotable, datapackId);
			if (!promoted && !promotable.isEmpty()) {
				log.warn("Policy records were written but not promoted. They are still readable via "
						+ "memory_search, but knowledgebase_search will not see them — so the spend "
						+ "ceiling will fall back to application.yml. Promotion needs owner or "
						+ "maintainer rights on datapack {}.", datapackId);
			}
		}

		return new SeedResult(
				true,
				summarise(written, updated, unchanged, alreadyShared, promoted),
				observations.stream().map(SeedRecord::withKey).toList(),
				knowledgeRecords,
				promoted,
				written,
				updated,
				alreadyShared);
	}

	/**
	 * Remove duplicate rows the non-idempotent seed left behind, newest of each kind kept.
	 * <p>
	 * Needed because the damage is already done and it does not heal on its own: a corpus of
	 * near-identical restatements crowds the real records out of every top-k window, so the
	 * reuse gate stops seeing the history it is supposed to match against. Destructive and
	 * deliberately manual — it is exposed as its own endpoint, never run as part of arming.
	 * <p>
	 * Identity is the record's kind plus its id, or its seedKey. Rows that are not seeded
	 * records and not agent write-backs are left alone; this is a demo tidy-up, not a reaper.
	 */
	public PruneResult prune(boolean includePromoted) {
		if (!datapack.configured()) {
			return new PruneResult(false, "No datapack resolved.", 0, 0, List.of());
		}
		var datapackId = datapack.id();

		int memoriesRemoved = countDuplicatesRemoved(meko.memoryGetAll(datapackId, false), datapackId);
		int promotedRemoved = includePromoted
				? countDuplicatesRemoved(meko.memoryGetAll(datapackId, true), datapackId)
				: 0;

		var remaining = meko.memoryGetAll(datapackId, false).stream()
				.map(m -> m.text().length() > 90 ? m.text().substring(0, 90) + "..." : m.text())
				.toList();

		return new PruneResult(true,
				"Removed %d duplicate memory row(s) and %d promoted row(s)."
						.formatted(memoriesRemoved, promotedRemoved),
				memoriesRemoved, promotedRemoved, remaining);
	}

	private int countDuplicatesRemoved(List<MekoToolBridge.MekoMemory> rows, String datapackId) {
		// memoryGetAll returns newest first, so the first sighting of an identity is the keeper.
		var seen = new java.util.HashSet<String>();
		int removed = 0;
		for (var row : rows) {
			var identity = identityOf(row.text());
			if (identity == null) {
				continue;
			}
			if (!seen.add(identity) && meko.memoryDelete(row.id(), datapackId)) {
				removed++;
			}
		}
		return removed;
	}

	/** Null when the row is not one of ours, which means leave it alone. */
	private String identityOf(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		var kind = text.split("[;\\s]", 2)[0];
		if (!List.of("INCIDENT_RECORD", "ROUTING_OUTCOME", "DEPLOY_EVENT",
				"SPEND_POLICY", "ROUTING_POLICY").contains(kind)) {
			return null;
		}
		var seedKey = field(text, "seedKey");
		if (seedKey != null) {
			return kind + "/seed/" + seedKey;
		}
		var id = field(text, "id");
		if (id != null) {
			return kind + "/id/" + id;
		}
		// Policy rows carry no id; one per service is the most that is ever wanted.
		var service = field(text, "service");
		return kind + "/service/" + (service == null ? "all" : service);
	}

	private String field(String text, String key) {
		var m = java.util.regex.Pattern.compile("(?:^|;\\s*)" + java.util.regex.Pattern.quote(key)
				+ "\\s*=\\s*([^;\\n]+)").matcher(text);
		return m.find() ? m.group(1).trim() : null;
	}

	/**
	 * Whether a stored row already says what the seed would write. Whitespace-insensitive, since
	 * a store may trim or reflow text; anything more different than that is rewritten.
	 */
	static boolean sameText(String stored, String wanted) {
		return stored != null && wanted != null
				&& stored.replaceAll("\\s+", " ").strip().equals(wanted.replaceAll("\\s+", " ").strip());
	}

	public record PruneResult(
			boolean ran, String message, int memoriesRemoved, int promotedRemoved,
			List<String> remaining) { }

	/** Say what actually happened. "Seeded" every time, when nothing was written, is a lie. */
	private String summarise(int written, int updated, int unchanged, boolean alreadyShared, boolean promoted) {
		var corpus = written == 0 && updated == 0
				? "History already in place (%d record(s) unchanged).".formatted(unchanged)
				: written == 0
				? "Refreshed %d existing record(s).".formatted(updated)
				: "Wrote %d new record(s)%s."
				.formatted(written, updated > 0 ? " and refreshed " + updated : "");
		var policy = alreadyShared
				? " Policy is already in the shared knowledge base."
				: promoted
				? " Policy promoted to the shared knowledge base."
				: " Policy could NOT be promoted, so the spend ceiling falls back to config.";
		return corpus + policy;
	}

	public record SeedResult(
			boolean seeded,
			String message,
			List<String> memoryRecords,
			List<String> knowledgeRecords,
			boolean promotedToKnowledgeBase,
			int recordsWritten,
			int recordsUpdated,
			boolean policyAlreadyShared) { }
}
