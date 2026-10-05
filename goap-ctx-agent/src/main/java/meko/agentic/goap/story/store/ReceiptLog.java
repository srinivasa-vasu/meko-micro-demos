package meko.agentic.goap.story.store;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import meko.agentic.goap.story.RunEvents;

/** Every context-store call made during a run, so the UI can show what Meko actually did. */
@Component
public class ReceiptLog {

    public record Receipt(String agent, String tool, String detail, int hits, long millis, boolean ok, String store) {}

    private final Map<String, List<Receipt>> receipts = new ConcurrentHashMap<>();
    private final RunEvents events;

    public ReceiptLog(RunEvents events) {
        this.events = events;
    }

    public void record(String caseId, Receipt receipt) {
        receipts.computeIfAbsent(caseId, k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(receipt);
        events.publish(caseId, "receipt", receipt);
    }

    public List<Receipt> forCase(String caseId) {
        List<Receipt> list = receipts.get(caseId);
        if (list == null) return List.of();
        synchronized (list) {
            return List.copyOf(list);
        }
    }
}
