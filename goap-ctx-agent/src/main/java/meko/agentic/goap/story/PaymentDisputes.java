package meko.agentic.goap.story;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The simulated customer's side of story 1: which payment they are disputing. Each case gets its own transaction id, so
 * the conversation keyed by it holds exactly one case and an audit never mixes runs. Agents never read this to learn
 * a fact; only the simulated customer (answering a question) and the simulated parties (answering a records request) do.
 */
@Component
public class PaymentDisputes {

    public record Dispute(String caseId, String transaction, String amount, String merchant) {}

    static final String AMOUNT = "84.20 USD";
    static final String MERCHANT = "CityMart";

    private final Map<String, Dispute> byCase = new ConcurrentHashMap<>();
    private final Map<String, Dispute> byTransaction = new ConcurrentHashMap<>();

    /** A new dispute for a support case: a fresh transaction id, the same amount and merchant. */
    public Dispute open(String caseId) {
        Dispute dispute = new Dispute(caseId, "TX-" + ThreadLocalRandom.current().nextInt(10_000, 100_000), AMOUNT, MERCHANT);
        byCase.put(caseId, dispute);
        byTransaction.put(dispute.transaction(), dispute);
        return dispute;
    }

    public Dispute forCase(String caseId) {
        Dispute dispute = byCase.get(caseId);
        if (dispute == null) throw new IllegalStateException("No dispute opened for case " + caseId);
        return dispute;
    }

    public Optional<Dispute> forTransaction(String transaction) {
        return Optional.ofNullable(byTransaction.get(transaction));
    }
}
