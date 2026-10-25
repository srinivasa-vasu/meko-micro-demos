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

    /** @param background a write the agents did not wait for (it ran alongside them, and the run waited only at the end) */
    public record Receipt(String agent, String tool, String detail, int hits, long millis, boolean ok, String store, boolean background) {
        public Receipt(String agent, String tool, String detail, int hits, long millis, boolean ok, String store) {
            this(agent, tool, detail, hits, millis, ok, store, false);
        }
    }

    /**
     * Something a run wrote to the context store, in full (a receipt keeps only a short detail).
     *
     * @param kind  {@code turn} (dialogue), {@code record} (an agent's record of what it did), {@code memory}, or
     *              {@code promoted} (a memory moved to shared knowledge)
     * @param label for a record, what it records; otherwise the conversation it went to, or null
     */
    public record Written(String agent, String kind, String label, String text) {}

    private final Map<String, List<Receipt>> receipts = new ConcurrentHashMap<>();
    private final Map<String, List<Written>> written = new ConcurrentHashMap<>();
    private final RunEvents events;

    public ReceiptLog(RunEvents events) {
        this.events = events;
    }

    /** Cases in the order first seen: only the most recent are kept, so a long session does not grow without bound. */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> order = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final int KEEP_CASES = 500;

    /** The cases in {@code order}: added once, even when two calls for a new case arrive at the same moment. */
    private final java.util.Set<String> known = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void seen(String caseId) {
        if (!known.add(caseId)) return;
        order.add(caseId);
        while (order.size() > KEEP_CASES) {
            String oldest = order.poll();
            if (oldest == null) break;
            receipts.remove(oldest);
            written.remove(oldest);
            known.remove(oldest);
        }
    }

    /** Forgets a case's receipts and writes (a status check's, which is repeated and never shown as a run). */
    public void clear(String caseId) {
        receipts.remove(caseId);
        written.remove(caseId);
        order.remove(caseId);
        known.remove(caseId);
    }

    public void record(String caseId, Receipt receipt) {
        seen(caseId);
        receipts.computeIfAbsent(caseId, k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(receipt);
        events.publish(caseId, "receipt", receipt);
    }

    public void wrote(String caseId, Written item) {
        seen(caseId);
        written.computeIfAbsent(caseId, k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(item);
    }

    /** What the run for {@code caseId} wrote, in the order it was written. */
    public List<Written> writtenFor(String caseId) {
        List<Written> list = written.get(caseId);
        if (list == null) return List.of();
        synchronized (list) {
            return List.copyOf(list);
        }
    }

    public List<Receipt> forCase(String caseId) {
        List<Receipt> list = receipts.get(caseId);
        if (list == null) return List.of();
        synchronized (list) {
            return List.copyOf(list);
        }
    }
}
