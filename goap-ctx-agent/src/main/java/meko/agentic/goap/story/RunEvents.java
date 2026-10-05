package meko.agentic.goap.story;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live events of a run (plans, actions, steps, context-store calls, decisions, model calls), keyed by case id, so the
 * UI can watch the planner work instead of waiting for the result. Publishing with no subscriber is a no-op.
 */
@Component
public class RunEvents {

    @FunctionalInterface
    public interface Sink {
        void send(String type, Object payload);
    }

    private final Map<String, Sink> sinks = new ConcurrentHashMap<>();

    public void subscribe(String caseId, Sink sink) {
        sinks.put(caseId, sink);
    }

    public void unsubscribe(String caseId) {
        sinks.remove(caseId);
    }

    public void publish(String caseId, String type, Object payload) {
        Sink sink = sinks.get(caseId);
        if (sink == null) return;
        try {
            sink.send(type, payload);
        } catch (RuntimeException ignored) {
            // A closed browser tab must never break the run.
        }
    }
}
