package meko.agentic.goap.story;

import meko.agentic.goap.story.store.ContextStore;
import meko.agentic.goap.story.store.ContextStores;
import meko.agentic.goap.story.store.StoreRecord;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synthetic organizational context the stories rely on. All content is fictional.
 * <ul>
 *   <li><b>Knowledge</b>: curated, shared guidance (policies, a runbook, review standards, an ADR). Its primary form is
 *       the documents in {@link KnowledgeDocuments}, uploaded to the knowledge base by a person. Where a document is
 *       not in the knowledge base, a one-line version is written as a memory and promoted instead, so the stories
 *       still work on a datapack nobody has uploaded to.</li>
 *   <li><b>Memories by other agents</b>: what other tools and agents in the organization wrote while doing
 *       their own work. The story agents read these without having written them.</li>
 * </ul>
 */
@Component
public class StorySeeder {
    private static final String SEED_CASE = "seed";
    /** Agents and tools in other systems whose earlier writes the seed stands for. */
    public static final String ANALYST_DESK = "kyc-analyst:2026-q2";
    public static final String CODING_ASSISTANT = "claude_code:payments-service";
    public static final String PR_BOT = "pr-review-bot";
    public static final String INCIDENT_BOT = "incident-bot";
    private static final String CURATOR = "org-knowledge:curator";

    /** @param docKey for knowledge: the key of the uploaded document that supersedes this one-line version */
    record Seed(String agentId, String query, String text, String docKey) {
        Seed(String agentId, String query, String text) {
            this(agentId, query, text, null);
        }
    }

    static final List<Seed> KNOWLEDGE = List.of(
            new Seed(CURATOR, "CARD-DISPUTE-07 failed capture refund policy",
                    "Demo policy CARD-DISPUTE-07: when card authorization succeeds but the merchant capture times out and no settled capture exists, "
                            + "treat it as an unsettled payment incident, verify settlement state first, and a provisional refund may be approved while settlement is verified. "
                            + "Do not conclude duplicate charge just because two gateway messages exist; compare authorization and capture identifiers. "
                            + "Escalate to a human payments specialist when evidence is unavailable or conflicting.", "CARD-DISPUTE-07"),
            new Seed(CURATOR, "payments support handoff checklist",
                    "Demo support standard: when a payment case is handed from first-line support to a payments specialist, the handoff must carry the transaction id, "
                            + "what the customer already confirmed, and any hypothesis already rejected, so the customer is never asked to repeat themselves.", "SUPPORT-STD-02"),
            new Seed(CURATOR, "payments API latency connection pool runbook",
                    "Demo SRE runbook RB-PAY-12: for payment API latency above 5 seconds after a deployment, first correlate with the deploy timestamp, "
                            + "then check database saturation, then verify connection-pool saturation on the payments service before rolling back.", "RB-PAY-12"),
            new Seed(CURATOR, "KYC periodic review material change beneficiary",
                    "Demo KYC review standard: in a periodic re-review, compare against the previous assessment; unchanged address and ownership need no new evidence, "
                            + "but a newly added beneficiary is a material change that requires targeted source-of-funds evidence and a human reviewer sign-off; "
                            + "monthly volume more than 25% outside the previous band is material.", "KYC-STD-04"),
            // Distractor: a look-alike policy for a different situation (issuer chargebacks).
            new Seed(CURATOR, "CARD-DISPUTE-03 chargeback policy",
                    "Demo policy CARD-DISPUTE-03: when the customer raises a chargeback through their card issuer, follow the card network's "
                            + "chargeback process. Do not issue a provisional refund from our side while the chargeback is open, to avoid refunding twice.", "CARD-DISPUTE-03"),
            new Seed(CURATOR, "LEND-UW-05 salaried personal loan underwriting policy",
                    "Demo policy LEND-UW-05: unsecured personal loans to salaried borrowers. Verify income from two recent pay stubs, employment with the employer, "
                            + "and assets from two months of bank statements; pull one credit report per application with consent, and reuse checks the origination system completed. "
                            + "A single deposit larger than 25% of monthly income must be sourced; an employment gap longer than 30 days in the last two years needs a letter of explanation; "
                            + "a condition the borrower already answered during origination is cleared from that record. "
                            + "Acceptable evidence of a deposit's source: a gift letter, or a statement of the account the money came from. "
                            + "Acceptable evidence for an employment gap: the employer's letter. Any other evidence needs a ruling from credit risk; "
                            + "a ruling applies to later applications with the same kind of evidence. "
                            + "Credit score of at least 680. DTI up to 40% approve; above 40% and up to 45% approve only with at least 3 months of reserves; above 45% decline.", "LEND-UW-05"),
            // Distractor: a look-alike policy for a different borrower (self-employed).
            new Seed(CURATOR, "LEND-UW-09 self-employed personal loan underwriting policy",
                    "Demo policy LEND-UW-09: unsecured personal loans to self-employed borrowers only. Verify income from two years of tax returns and profit and loss statements. "
                            + "Credit score of at least 700; DTI up to 36% approve; above 36% decline.", "LEND-UW-09"),
            new Seed(CURATOR, "ADR-194 payment retry idempotency decision",
                    "Demo ADR-194: payment retries must be idempotent and keyed by the original authorization id; any change to the retry contract requires architecture review. "
                            + "Related incident INC-1877 was caused by a retry that re-captured an already settled payment.", "ADR-194"));

    static final List<Seed> MEMORIES = List.of(
            new Seed(CODING_ASSISTANT, "PR-4821 payment retry decision authorization id",
                    "PR-4821 (payment retry): while pairing on the change we decided retries must be keyed by the original authorization id, per ADR-194, "
                            + "because of incident INC-1877."),
            new Seed(PR_BOT, "PR-4821 review requested idempotency regression test",
                    "PR-4821 review: the reviewer requested an idempotency regression test for the retry path before approval. Still open."),
            // Distractors: a superseded decision on the same PR, and an open request on a near-identical PR number.
            new Seed(CODING_ASSISTANT, "PR-4821 early draft retry idempotency token superseded",
                    "PR-4821 early draft (superseded by ADR-194): retries were keyed by a client-generated idempotency token."),
            new Seed(PR_BOT, "PR-4812 review requested load test checkout retry banner",
                    "PR-4812 review: the reviewer requested a load test for the checkout retry banner before approval. Still open."),
            new Seed(INCIDENT_BOT, "INC-1877 postmortem retry re-captured settled payment",
                    "INC-1877 postmortem: a payment retry re-captured an already settled payment. Action item: idempotency regression test for payment retries."));

    private final ContextStores stores;
    private final KnowledgeDocuments documents;
    private final ProfileFingerprints fingerprints;

    public StorySeeder(ContextStores stores, KnowledgeDocuments documents, ProfileFingerprints fingerprints) {
        this.stores = stores;
        this.documents = documents;
        this.fingerprints = fingerprints;
    }

    /**
     * Earlier KYC reviews, written by the analyst desk's agent as a decision and keyed fingerprints of the profile (see
     * {@link KycReviewMemory}), never the profile itself. The profiles below stand in for what that desk's system held
     * at the time; only their fingerprints are stored. The latest review of CUST-7788 is the one to compare with; the
     * others are distractors: an outdated review of the same customer, and a review of a near-identical customer id.
     */
    List<Seed> kycReviews() {
        return List.of(
                new Seed(ANALYST_DESK, "KYC review CUST-7788 risk class profile fingerprint", KycReviewMemory.of(fingerprints,
                        "CUST-7788", "2026-04-14", null, null, "MEDIUM", "12 Harbour Road", "60/40 between two directors",
                        List.of("Northwind Supplies", "Acme Logistics"), 40000).text()),
                new Seed("kyc-analyst:2025-q4", "KYC review CUST-7788 2025 risk class profile fingerprint", KycReviewMemory.of(fingerprints,
                        "CUST-7788", "2025-10-03", null, null, "LOW", "4 Dock Street", "100% single director",
                        List.of("Northwind Supplies"), 15000).text()),
                new Seed(ANALYST_DESK, "KYC review CUST-7789 risk class profile fingerprint", KycReviewMemory.of(fingerprints,
                        "CUST-7789", "2026-04-15", null, null, "HIGH", "90 Quay Lane", "50/50 between two directors",
                        List.of("Orion Trading FZE", "Helix Metals"), 120000).text()));
    }

    /** Every memory seed: the KYC reviews in fingerprint form, then the rest. */
    List<Seed> memories() {
        List<Seed> all = new java.util.ArrayList<>(kycReviews());
        all.addAll(MEMORIES);
        return all;
    }

    /** The stand-in is in memory, so it is seeded on every start, with the documents as if they had been uploaded. */
    @EventListener(ApplicationReadyEvent.class)
    public void seedStandIn() {
        documents.all().forEach(d -> d.chunks().forEach(c -> stores.standIn().seedDocumentChunk(d.filename(), c)));
        KNOWLEDGE.stream().filter(s -> !documents.covers(s.docKey()))
                .forEach(s -> stores.standIn().seedKnowledge(s.agentId(), s.text()));
        memories().forEach(s -> stores.standIn().seedMemory(s.agentId(), s.text()));
    }

    /** Seeds the live datapack. Entries already present are skipped, so this is safe to repeat. */
    public Map<String, String> seedLive() {
        ContextStore store = stores.byKind(ContextStore.Kind.MEKO_LIVE);
        Map<String, String> outcome = new LinkedHashMap<>();
        for (Seed seed : KNOWLEDGE) {
            List<StoreRecord> rows = store.searchKnowledge(SEED_CASE, seed.agentId(), seed.text());
            if (rows.stream().anyMatch(r -> r.uploadedDocument() && r.text().contains(seed.docKey()))) {
                outcome.put(seed.query(), "already in knowledge base (uploaded document " + seed.docKey() + ")");
                continue;
            }
            if (present(rows, seed.text())) {
                outcome.put(seed.query(), "already in knowledge base (promoted one-line version; upload the "
                        + seed.docKey() + " document for the full text)");
                continue;
            }
            outcome.put(seed.query(), store.remember(SEED_CASE, seed.agentId(), seed.text())
                    .map(id -> store.promote(SEED_CASE, seed.agentId(), List.of(id))
                            ? "promoted to knowledge base" : "stored as memory; promotion failed (owner or maintainer rights needed)")
                    .orElse("memory_add stored nothing"));
        }
        // KYC reviews once seeded as plain text (address, ownership, beneficiary names) are removed: only the
        // fingerprint form below belongs in a shared datapack.
        for (StoreRecord r : store.searchMemory(SEED_CASE, CURATOR, "KYC review of customer CUST-7788 CUST-7789 verified registered address ownership beneficiaries")) {
            if (r.text().startsWith("KYC review of customer") && KycReviewMemory.parse(r.text()).isEmpty()) {
                outcome.put("plaintext review " + r.id(), store.forget(SEED_CASE, r.writtenBy(), r.id())
                        ? "removed: a KYC review stored as plain text (written by " + r.writtenBy() + ")" : "could not remove a KYC review stored as plain text");
            }
        }
        for (Seed seed : memories()) {
            if (present(store.searchMemory(SEED_CASE, seed.agentId(), seed.text()), seed.text())) {
                outcome.put(seed.query(), "already in memory (written by " + seed.agentId() + ")");
                continue;
            }
            outcome.put(seed.query(), store.remember(SEED_CASE, seed.agentId(), seed.text()).isPresent()
                    ? "memory written by " + seed.agentId() : "memory_add stored nothing");
        }
        return outcome;
    }

    /**
     * Searched with the seed's own text, so an existing copy ranks first even in a busy datapack; compared on the
     * full text, because seeds can share a long prefix (two reviews of the same customer, a year apart).
     */
    private static boolean present(List<StoreRecord> rows, String text) {
        return rows.stream().anyMatch(r -> text.equals(r.text()));
    }
}
