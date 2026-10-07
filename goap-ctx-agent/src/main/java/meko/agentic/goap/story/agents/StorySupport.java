package meko.agentic.goap.story.agents;

import meko.agentic.goap.story.EffortEstimates;
import meko.agentic.goap.story.LoanApplications;
import meko.agentic.goap.story.PaymentDisputes;
import meko.agentic.goap.story.SystemsOfRecord;
import meko.agentic.goap.story.StoryLedger;
import meko.agentic.goap.story.StoryLedgers;
import meko.agentic.goap.story.store.ContextStore;
import meko.agentic.goap.story.store.ContextStores;
import meko.agentic.goap.story.store.StoreRecord;
import com.embabel.common.textio.template.TemplateRenderer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared plumbing for the story agents: the run's context store and ledger. */
@Component
public class StorySupport {
    private final ContextStores stores;
    private final StoryLedgers ledgers;
    private final TemplateRenderer templates;
    private final EffortEstimates efforts;
    private final SystemsOfRecord systemsOfRecord;
    private final PaymentDisputes disputes;
    private final LoanApplications loans;
    private final meko.agentic.goap.story.store.ReceiptLog receipts;

    public StorySupport(ContextStores stores, StoryLedgers ledgers, TemplateRenderer templates, EffortEstimates efforts,
                        SystemsOfRecord systemsOfRecord, PaymentDisputes disputes, LoanApplications loans, meko.agentic.goap.story.store.ReceiptLog receipts) {
        this.receipts = receipts;
        this.stores = stores;
        this.ledgers = ledgers;
        this.templates = templates;
        this.efforts = efforts;
        this.systemsOfRecord = systemsOfRecord;
        this.disputes = disputes;
        this.loans = loans;
    }

    /**
     * Writes a turn to the conversation keyed by {@code thread} in the run's context store, and keeps it in the writing
     * party's own system of record, which every party has whatever the store.
     */
    public void record(ContextStore store, String caseId, String agentId, String thread, String input, String output) {
        store.addTurn(caseId, agentId, thread, input, output);
        systemsOfRecord.keep(thread, new ContextStore.Turn(input, output, agentId));
    }

    /**
     * Writes an agent's record of something it did: a natural input and output, tagged in the reasoning as
     * {@code "<agent id> · <event>"} (followed by {@code ": <why>"} when given). Also kept in the party's own system of record.
     */
    public void recordEvent(ContextStore store, String caseId, String agentId, String thread, String event, String why,
                            String input, String output) {
        String reasoning = ContextStore.Turn.tag(agentId, event) + (why == null || why.isBlank() ? "" : ": " + why);
        store.addTurn(caseId, agentId, thread, input, output, reasoning);
        systemsOfRecord.keep(thread, new ContextStore.Turn(input, output, agentId, reasoning));
    }

    /**
     * Writes {@code persona}'s gate decisions not yet traced to the conversation keyed by {@code thread}: the question as
     * input, the answer as output, and in the reasoning the decision's tag and who decided on how much context.
     * <p>
     * Only to the context store: a decision trace exists only where an agent wrote it. A party's own system of record
     * keeps what was done (the outcome), not how a model reached a judgement.
     */
    public void traceDecisions(ContextStore store, String caseId, String agentId, String thread, String persona) {
        for (StoryLedger.Decision d : ledger(caseId).takeUntraced(persona)) {
            store.addTurn(caseId, agentId, thread, d.question(), d.answer(),
                    ContextStore.Turn.decisionTag(agentId, d.gate()) + ": decided by " + d.decidedBy() + ", reading " + d.context() + ".");
        }
    }

    /** A conversation turn as one line: what it records (a decision's gate, an event), its input and its output. */
    public static String describe(ContextStore.Turn t) {
        String label = t.decisionGate().map(g -> ContextStore.DECISION + " " + g).or(t::event).map(l -> l + ": ").orElse("");
        return label + t.input() + " → " + t.output();
    }

    /** Turns as context lines for a decision prompt, in order, with who wrote each and the reasoning recorded with it. */
    public static String asContext(String heading, List<ContextStore.Turn> turns, boolean inOrder) {
        if (turns.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(heading).append(inOrder ? " (in order):\n" : ":\n");
        turns.forEach(t -> sb.append("- ").append(describe(t)).append(" (written by ").append(t.writtenBy())
                .append(t.why().isEmpty() ? "" : "; " + t.why()).append(")\n"));
        return sb.toString();
    }

    /** The decision traces {@code agentId} wrote to a conversation. */
    public static List<ContextStore.Turn> decisionTraces(List<ContextStore.Turn> turns, String agentId) {
        return turns.stream().filter(t -> t.decisionGate().isPresent() && t.taggedBy().map(agentId::equals).orElse(false)).toList();
    }

    /** One finding per decision trace: the gate, the answer, and who decided on how much context. */
    public static List<String> traceFindings(List<ContextStore.Turn> traces) {
        return traces.stream().map(t -> "Decision " + t.decisionGate().orElse("") + ": " + t.output() + " (" + t.why() + ")").toList();
    }

    /**
     * A follow-up's audit trail: every turn of the record it read, in order, then its own decisions.
     *
     * @param origins  where each turn came from, parallel to {@code turns}
     * @param dialogue which turns are dialogue (said to an agent and its reply) rather than an agent's record
     * @param issueOf  the issue the audit flags for the turn at an index, or empty
     * @param reader   the follow-up's persona, whose decisions close the trail
     */
    public List<StoryLedger.AuditEntry> auditTrail(String caseId, List<ContextStore.Turn> turns, List<String> origins,
                                                   java.util.function.Predicate<ContextStore.Turn> dialogue,
                                                   java.util.function.IntFunction<String> issueOf, String reader, String readerId) {
        List<StoryLedger.AuditEntry> trail = new java.util.ArrayList<>();
        for (int i = 0; i < turns.size(); i++) {
            ContextStore.Turn t = turns.get(i);
            String kind = t.decisionGate().isPresent() ? "decision" : dialogue.test(t) ? "dialogue" : "record";
            String what = switch (kind) {
                case "decision" -> t.input();
                case "record" -> t.input();
                default -> "Said to the agent: " + t.input();
            };
            String how = switch (kind) {
                case "decision" -> "Gate " + t.decisionGate().get() + ", " + t.why();
                case "record" -> t.event().get() + (t.why().isEmpty() ? "" : ": " + t.why());
                default -> t.why().isEmpty() ? "Conversation turn" : t.event().map(e -> e + ": ").orElse("") + t.why();
            };
            trail.add(new StoryLedger.AuditEntry(i + 1, t.writtenBy(), kind, what, how, t.output(), issueOf.apply(i), origins.get(i)));
        }
        for (StoryLedger.Decision d : ledger(caseId).decisions()) {
            if (!d.persona().equals(reader)) continue;
            trail.add(new StoryLedger.AuditEntry(trail.size() + 1, readerId, "decision", d.question(),
                    "Gate " + d.gate() + ", decided by " + d.decidedBy() + ", reading " + d.context(), d.answer(), "", "this audit"));
        }
        return trail;
    }

    /** Where a turn read from the run's own context store came from, as an audit trail names it. */
    public static String conversationIn(ContextStore.Kind kind) {
        return "conversation in " + storeName(kind);
    }

    /** The issue a decision trace raises by itself: its model call failed and the fallback rule decided. */
    public static String decisionIssue(ContextStore.Turn t) {
        return t.decisionGate().isPresent() && t.why().contains("failed") ? "The model call failed; the fallback rule decided" : "";
    }

    /**
     * Reads a conversation back by its key. Where writes are queued (live Meko), a record written moments ago may not
     * be readable yet: it retries a few times, briefly, until {@code enough} holds. Elsewhere it reads once.
     */
    public List<ContextStore.Turn> readBack(ContextStore store, String caseId, String agentId, String thread,
                                            java.util.function.Predicate<List<ContextStore.Turn>> enough) {
        List<ContextStore.Turn> turns = store.transcript(caseId, agentId, thread);
        for (int attempt = 0; attempt < 4 && store.writesAreQueued() && !enough.test(turns); attempt++) {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            turns = store.transcript(caseId, agentId, thread);
        }
        return turns;
    }

    /**
     * What became of a memory write, for the step that made it: saved; the store failed (and why, from its receipt);
     * the store kept nothing new (Meko's fact extraction can judge a fact already known); or nowhere durable to write.
     */
    public String writeOutcome(String caseId, String agentId, ContextStore.Kind kind, boolean saved, String what) {
        if (saved) return "Saved " + what + " to " + storeName(kind);
        if (kind == ContextStore.Kind.NONE) return "Nothing durable to save " + what + " to";
        Optional<meko.agentic.goap.story.store.ReceiptLog.Receipt> last = receipts.forCase(caseId).stream()
                .filter(r -> r.agent().equals(agentId) && r.tool().contains("memory_add")).reduce((a, b) -> b);
        if (last.isPresent() && !last.get().ok()) {
            String detail = last.get().detail();
            int dash = detail.lastIndexOf(" — ");
            return "The write of " + what + " to " + storeName(kind) + " failed" + (dash < 0 ? "" : ": " + detail.substring(dash + 3));
        }
        return storeName(kind) + " stored nothing new from " + what + " (its memory extraction kept no new fact)";
    }

    /** The simulated customers' disputes (see {@link PaymentDisputes} for who may read them). */
    public PaymentDisputes disputes() {
        return disputes;
    }

    /** The simulated borrowers' loan applications (see {@link LoanApplications} for who may read them). */
    public LoanApplications loans() {
        return loans;
    }

    public SystemsOfRecord systemsOfRecord() {
        return systemsOfRecord;
    }

    /** The effort estimate for {@code agent.step}, from estimates.yml. */
    public EffortEstimates.Estimate effort(String agent, String step) {
        return efforts.of(agent, step);
    }

    /** Renders a prompt template from {@code prompts/} (e.g. "writer/support-reply"). */
    public String render(String template, java.util.Map<String, Object> model) {
        return templates.renderLoadedTemplate(template, model).strip();
    }

    public ContextStore store(ContextStore.Kind kind) {
        return stores.byKind(kind);
    }

    /** How a step should name the store it wrote to. */
    public static String storeName(ContextStore.Kind kind) {
        return switch (kind) {
            case SHARED_DB -> "the application database";
            case OTHER_SYSTEM_DB -> "its own system's store";
            default -> "Meko";
        };
    }

    /** Records a retrieved row the agent chose not to use, and why. */
    public void passedOver(StoryLedger ledger, String source, StoreRecord row, String reason) {
        ledger.passedOver(source, row.text(), row.writtenBy(), reason);
    }

    public StoryLedger ledger(String caseId) {
        return ledgers.forCase(caseId);
    }

    /** Records each retrieved row as context found from the store, so the UI can attribute it. */
    public void found(StoryLedger ledger, String source, List<StoreRecord> rows) {
        rows.forEach(r -> ledger.found(source, r.text(), r.writtenBy()));
    }

    /** Rows as context lines for a decision prompt, with who wrote each. */
    public static String asContext(String heading, List<StoreRecord> rows) {
        if (rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(heading).append(":\n");
        rows.forEach(r -> sb.append("- ").append(r.text()).append(" (written by ").append(r.writtenBy()).append(")\n"));
        return sb.toString();
    }

    /** First match of {@code regex} (group 1 if present) across the rows' text. */
    public static Optional<String> extract(List<StoreRecord> rows, String regex) {
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        for (StoreRecord row : rows) {
            Matcher m = pattern.matcher(row.text());
            if (m.find()) return Optional.of(m.groupCount() > 0 && m.group(1) != null ? m.group(1) : m.group());
        }
        return Optional.empty();
    }

    /** A policy or standard id as documents write it: {@code CARD-DISPUTE-07}, {@code RB-PAY-12}. */
    public static final String POLICY_ID = "\\b([A-Z][A-Z]+(?:-[A-Z]+)*-\\d+)\\b";

    public static boolean mentions(List<StoreRecord> rows, String regex) {
        return extract(rows, regex).isPresent();
    }
}
