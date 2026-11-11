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
    private final WorkGate gate;
    private final RunJobs jobs;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public StoryStreamController(StoryService stories, RunEvents events, WorkGate gate, RunJobs jobs) {
        this.stories = stories;
        this.events = events;
        this.gate = gate;
        this.jobs = jobs;
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id, @RequestParam(defaultValue = "compare") String mode,
                             @RequestParam(defaultValue = "false") boolean db,
                             @RequestParam(defaultValue = "false") boolean mekoOnly,
                             @RequestParam(required = false) String useCase,
                             @RequestParam(required = false) Integer stage,
                             @RequestParam(defaultValue = "run") String kind) {
        SseEmitter emitter = new SseEmitter(15 * 60 * 1000L);
        // One run at a time: refused (as a failed run) while another is going, from this page or another.
        WorkGate.Pass pass;
        try {
            pass = gate.enter("a run of " + id);
        } catch (WorkGate.Busy e) {
            send(emitter, "failed", Map.of("lane", "with", "data", e.getMessage()));
            send(emitter, "done", Map.of());
            emitter.complete();
            return emitter;
        }
        // A job: the page follows it, and can follow it again (a reload, another browser) until its result is recorded.
        RunJobs.Job job = jobs.start(id, useCase, stage, kind);
        java.util.function.BiConsumer<String, Object> toPage = toPage(emitter);
        jobs.follow(job, toPage);
        emitter.onCompletion(() -> jobs.unfollow(job, toPage));
        jobs.emit(job, "job", job.describe());
        List<Lane> lanes = new ArrayList<>();
        // A follow-up runs in every lane like a comparison, on what each lane last handled for the story.
        boolean followUp = mode.equals("followup");
        boolean compare = mode.equals("compare") || followUp;
        java.util.function.Supplier<String> newCaseId = () -> followUp ? stories.newFollowUpCaseId(id) : StoryService.newCaseId(id);
        // The workshop follows the Meko lane alone, stage by stage: mekoOnly drops the other lanes from a comparison.
        if ((compare && !mekoOnly) || mode.equals("without")) lanes.add(new Lane("without", ContextStores.Lane.WITHOUT, newCaseId.get()));
        if ((compare && db && !mekoOnly) || mode.equals("db")) lanes.add(new Lane("db", ContextStores.Lane.DATABASE, newCaseId.get()));
        if (compare || mode.equals("with") || mode.equals("mismatch")) lanes.add(new Lane("with", ContextStores.Lane.MEKO, newCaseId.get()));

        jobs.emit(job, "lanes", lanes);
        lanes.forEach(lane -> events.subscribe(lane.caseId(),
                (type, payload) -> jobs.emit(job, type, Map.of("lane", lane.name(), "data", payload))));

        List<CompletableFuture<Void>> runs = lanes.stream().map(lane -> CompletableFuture.runAsync(() -> {
            try {
                Object run = followUp ? stories.followUp(id, lane.lane(), lane.caseId())
                        : mode.equals("mismatch") ? stories.patternMismatch(true, lane.caseId())
                        : stories.run(id, lane.lane(), lane.caseId());
                jobs.emit(job, "run", Map.of("lane", lane.name(), "data", run));
            } catch (RuntimeException e) {
                jobs.emit(job, "failed", Map.of("lane", lane.name(), "data", String.valueOf(e.getMessage())));
            } finally {
                events.unsubscribe(lane.caseId());
            }
        }, executor)).toList();

        CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).whenComplete((ok, error) -> {
            pass.close();   // the run ended here, whether or not the browser is still listening
            jobs.finish(job);   // "done" to whoever follows it; the job and its result stay until the page records them
        });
        return emitter;
    }

    /** Follows a run again (after a reload, or from another browser): its events from the start, then the rest. */
    @GetMapping(value = "/jobs/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter follow(@PathVariable String jobId) {
        SseEmitter emitter = new SseEmitter(15 * 60 * 1000L);
        RunJobs.Job job = jobs.get(jobId);
        if (job == null) {
            send(emitter, "failed", Map.of("lane", "with", "data", "That run is no longer kept: run the step again."));
            send(emitter, "done", Map.of());
            emitter.complete();
            return emitter;
        }
        java.util.function.BiConsumer<String, Object> toPage = toPage(emitter);
        jobs.follow(job, toPage);
        emitter.onCompletion(() -> jobs.unfollow(job, toPage));
        return emitter;
    }

    /** The page recorded the run's result in the progress datapack: nothing is left to pick up. */
    @org.springframework.web.bind.annotation.PostMapping("/jobs/{jobId}/recorded")
    public Map<String, Object> recorded(@PathVariable String jobId) {
        jobs.recorded(jobId);
        return Map.of("recorded", true);
    }

    /** Sends a job's events to one page, and ends the page's stream with the job. */
    private java.util.function.BiConsumer<String, Object> toPage(SseEmitter emitter) {
        return (type, payload) -> {
            send(emitter, type, payload);
            if ("done".equals(type)) emitter.complete();
        };
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
