package meko.agentic.goap.api;

import meko.agentic.goap.story.RunEvents;
import meko.agentic.goap.story.StoryService;
import meko.agentic.goap.story.store.ContextStores;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Streams a run live as server-sent events. In compare mode all three lanes (no durable context, the application's own
 * database, Meko) run at the same time, so the audience watches the planners diverge. Events: {@code lanes}, then per lane {@code phase}, {@code plan},
 * {@code action}, {@code step}, {@code line}, {@code found}, {@code decision}, {@code receipt}, {@code model},
 * {@code phaseEnd}, then {@code run} (the full result) or {@code failed}, and finally {@code done}.
 */
@RestController
@RequestMapping("/api/stories")
public class StoryStreamController {
    private record Lane(String name, ContextStores.Lane lane, String caseId) {}

    private final StoryService stories;
    private final RunEvents events;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public StoryStreamController(StoryService stories, RunEvents events) {
        this.stories = stories;
        this.events = events;
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id, @RequestParam(defaultValue = "compare") String mode,
                             @RequestParam(defaultValue = "false") boolean db,
                             @RequestParam(defaultValue = "false") boolean mekoOnly) {
        SseEmitter emitter = new SseEmitter(15 * 60 * 1000L);
        List<Lane> lanes = new ArrayList<>();
        // A follow-up runs in every lane like a comparison, on what each lane last handled for the story.
        boolean followUp = mode.equals("followup");
        boolean compare = mode.equals("compare") || followUp;
        java.util.function.Supplier<String> newCaseId = () -> followUp ? stories.newFollowUpCaseId(id) : StoryService.newCaseId(id);
        // The workshop follows the Meko lane alone, stage by stage: mekoOnly drops the other lanes from a comparison.
        if ((compare && !mekoOnly) || mode.equals("without")) lanes.add(new Lane("without", ContextStores.Lane.WITHOUT, newCaseId.get()));
        if ((compare && db && !mekoOnly) || mode.equals("db")) lanes.add(new Lane("db", ContextStores.Lane.DATABASE, newCaseId.get()));
        if (compare || mode.equals("with") || mode.equals("mismatch")) lanes.add(new Lane("with", ContextStores.Lane.MEKO, newCaseId.get()));

        send(emitter, "lanes", lanes);
        lanes.forEach(lane -> events.subscribe(lane.caseId(),
                (type, payload) -> send(emitter, type, Map.of("lane", lane.name(), "data", payload))));

        List<CompletableFuture<Void>> runs = lanes.stream().map(lane -> CompletableFuture.runAsync(() -> {
            try {
                Object run = followUp ? stories.followUp(id, lane.lane(), lane.caseId())
                        : mode.equals("mismatch") ? stories.patternMismatch(true, lane.caseId())
                        : stories.run(id, lane.lane(), lane.caseId());
                send(emitter, "run", Map.of("lane", lane.name(), "data", run));
            } catch (RuntimeException e) {
                send(emitter, "failed", Map.of("lane", lane.name(), "data", String.valueOf(e.getMessage())));
            } finally {
                events.unsubscribe(lane.caseId());
            }
        }, executor)).toList();

        CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).whenComplete((ok, error) -> {
            send(emitter, "done", Map.of());
            emitter.complete();
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, String type, Object payload) {
        synchronized (emitter) {
            try {
                emitter.send(SseEmitter.event().name(type).data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                // The browser went away; the run still completes.
            }
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
