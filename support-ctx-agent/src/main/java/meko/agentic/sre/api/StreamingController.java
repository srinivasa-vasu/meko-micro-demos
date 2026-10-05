package meko.agentic.sre.api;

import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import meko.agentic.sre.domain.IncidentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The same three operations as the JSON endpoints, streamed as Server-Sent Events.
 * <p>
 * A comparison is two full planner runs and an ablation is three; returned as one JSON body,
 * the room watches a spinner for the part of the demo that IS the demo. Streamed, it watches
 * the plan form, the gate decide, the sweep narrow and the tier get chosen, in both runs side
 * by side.
 * <p>
 * Frames, each JSON with a {@code run} label where it belongs to one run:
 * <pre>
 *   plan       the planner's current route         step       an action started or finished
 *   context    what Meko recalled                   reuse      the gate's decision
 *   evidence   how much telemetry was read          diagnosis  each attempt, served or generated
 *   llm        one provider call and its usage      replan     the planner changed course
 *   result     the same body the JSON endpoint returns, then the stream closes
 *   error      the run failed; message included, then the stream closes
 * </pre>
 * POST rather than GET because the request has a body, so the page reads it with fetch() and a
 * stream reader rather than EventSource, which is GET-only.
 */
@RestController
@RequestMapping("/api/v1")
public class StreamingController {

    private static final Logger log = LoggerFactory.getLogger(StreamingController.class);

    /** Long enough for an ablation through a slow provider with escalations. */
    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    private final ComparisonService comparisons;
    private final ExecutorService runs = Executors.newVirtualThreadPerTaskExecutor();

    public StreamingController(ComparisonService comparisons) {
        this.comparisons = comparisons;
    }

    @PostMapping(path = "/incidents/investigate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter investigate(@Valid @RequestBody IncidentController.InvestigationRequest body) {
        var request = new IncidentRequest(
                body.incidentId(), body.service(), body.symptom(), body.useMeko(), body.severityOrDefault());
        return stream(listeners -> comparisons.investigate(request, listeners));
    }

    @PostMapping(path = "/compare/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter compare(@Valid @RequestBody ComparisonController.ComparisonRequest body) {
        return stream(listeners -> comparisons.compare(body, listeners));
    }

    @PostMapping(path = "/ablate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ablate(@Valid @RequestBody ComparisonController.ComparisonRequest body) {
        return stream(listeners -> comparisons.ablate(body, listeners));
    }

    private SseEmitter stream(Function<ComparisonService.Listeners, Object> work) {
        var channel = new Channel(new SseEmitter(TIMEOUT.toMillis()));
        runs.execute(() -> {
            try {
                var result = work.apply(run -> List.of(new RunEventListener(run, channel::send)));
                channel.send("result", result);
                channel.complete();
            } catch (RuntimeException e) {
                log.warn("Streamed run failed", e);
                channel.send("error", Map.of("message", String.valueOf(e.getMessage())));
                channel.complete();
            }
        });
        return channel.emitter;
    }

    /**
     * One emitter written from several threads — the cold and warm runs report concurrently — so
     * sends are serialised here. A closed or failed connection stops further sends quietly; the
     * runs themselves continue to completion, because a planner run cannot be cancelled midway
     * and its write-back should still land.
     */
    private static final class Channel {
        private final SseEmitter emitter;
        private volatile boolean open = true;

        Channel(SseEmitter emitter) {
            this.emitter = emitter;
            emitter.onCompletion(() -> open = false);
            emitter.onTimeout(() -> open = false);
            emitter.onError(e -> open = false);
        }

        synchronized void send(String event, Object data) {
            if (!open) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                open = false;
                log.debug("Stream closed by the client; the run continues without it", e);
            }
        }

        synchronized void complete() {
            if (open) {
                open = false;
                emitter.complete();
            }
        }
    }
}
