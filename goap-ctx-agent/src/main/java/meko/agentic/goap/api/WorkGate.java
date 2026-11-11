package meko.agentic.goap.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One piece of work that changes the datapack at a time: a run, an upload, adding the reviews, switching datapacks.
 * The page already allows one at a time; this holds across tabs, a reload in the middle of a run (the run carries on
 * here), and a click that slips past the page. A second request is refused with what is running, never queued.
 */
@Component
public class WorkGate {

    /** Refused: something else is running. Answered 409 with what it is. */
    public static class Busy extends RuntimeException {
        public Busy(String running) {
            super("Wait for the work in progress to finish: " + running + ".");
        }
    }

    /** Released when closed: try-with-resources for a request, or {@link #close()} when a streamed run ends. */
    public final class Pass implements AutoCloseable {
        private boolean open = true;

        @Override
        public void close() {
            if (open) {
                open = false;
                running.set(null);
            }
        }
    }

    private final AtomicReference<String> running = new AtomicReference<>();

    /** Enters, or throws {@link Busy} naming what runs. */
    public Pass enter(String what) {
        if (!running.compareAndSet(null, what)) throw new Busy(running.get());
        return new Pass();
    }

    /** What is running now, or null. */
    public String running() {
        return running.get();
    }

    @RestControllerAdvice
    static class Answer {
        @ExceptionHandler(Busy.class)
        ResponseEntity<Map<String, String>> busy(Busy e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }
}
