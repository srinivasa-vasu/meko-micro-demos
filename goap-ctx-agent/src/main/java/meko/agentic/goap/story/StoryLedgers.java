package meko.agentic.goap.story;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Ledgers by case id, so actions in any agent process can write to the run they belong to. */
@Component
public class StoryLedgers {
    private final Map<String, StoryLedger> ledgers = new ConcurrentHashMap<>();
    private final RunEvents events;

    public StoryLedgers(RunEvents events) {
        this.events = events;
    }

    public StoryLedger forCase(String caseId) {
        return ledgers.computeIfAbsent(caseId, k -> new StoryLedger((type, payload) -> events.publish(caseId, type, payload)));
    }
}
