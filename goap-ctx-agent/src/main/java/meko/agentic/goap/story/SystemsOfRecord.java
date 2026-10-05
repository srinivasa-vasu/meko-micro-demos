package meko.agentic.goap.story;

import meko.agentic.goap.story.store.ContextStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What each party keeps in its own system regardless of any shared context store: the vendor's chat log, your
 * specialist team's ticket notes. Nobody can search across them; an auditor can only ask each party for its records,
 * which costs a request and a wait. Everything here was written by the agents during the run.
 */
@Component
public class SystemsOfRecord {

    /** thread → party (agent id) → turns, in order. */
    private final Map<String, Map<String, List<ContextStore.Turn>>> records = new ConcurrentHashMap<>();

    public void keep(String thread, ContextStore.Turn turn) {
        records.computeIfAbsent(thread, t -> new ConcurrentHashMap<>())
                .computeIfAbsent(turn.writtenBy(), p -> new CopyOnWriteArrayList<>())
                .add(turn);
    }

    /** What {@code party} hands over when asked for its records on {@code thread}. */
    public List<ContextStore.Turn> request(String thread, String party) {
        return List.copyOf(records.getOrDefault(thread, Map.of()).getOrDefault(party, List.of()));
    }
}
