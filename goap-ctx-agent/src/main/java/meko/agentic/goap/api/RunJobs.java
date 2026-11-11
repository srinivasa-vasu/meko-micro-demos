package meko.agentic.goap.api;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * The workshop's runs as server-side jobs. A run started from the page keeps every event it sends, so a page that
 * reloads, closes or opens in another browser can follow it again from its start, and a run that finished with no
 * page listening still has its result, until the page records it in the progress datapack. One run at a time (the
 * {@link WorkGate} holds that); the last one is kept.
 */
@Component
public class RunJobs {

    /** One run: what it is for (the use case and stage the page ran it at), its events so far, and how it ended. */
    public static final class Job {
        private final String id = UUID.randomUUID().toString();
        private final String story;
        private final String useCase;
        private final Integer stage;
        private final String kind;
        private final long startedAt = System.currentTimeMillis();
        private final List<Object[]> events = new ArrayList<>();
        private final List<BiConsumer<String, Object>> listeners = new CopyOnWriteArrayList<>();
        private volatile boolean done;
        private volatile boolean recorded;

        Job(String story, String useCase, Integer stage, String kind) {
            this.story = story;
            this.useCase = useCase;
            this.stage = stage;
            this.kind = kind;
        }

        public String id() {
            return id;
        }

        public boolean done() {
            return done;
        }

        /** What the page shows about it: enough to follow it again, at the step it belongs to. */
        public Map<String, Object> describe() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("story", story);
            if (useCase != null) out.put("useCase", useCase);
            if (stage != null) out.put("stage", stage);
            out.put("kind", kind);
            out.put("startedAt", startedAt);
            out.put("done", done);
            out.put("recorded", recorded);
            return out;
        }
    }

    private volatile Job last;

    /** A new run; it becomes the one the page can follow. */
    public Job start(String story, String useCase, Integer stage, String kind) {
        Job job = new Job(story, useCase, stage, kind);
        last = job;
        return job;
    }

    /** An event of the run: kept, and sent to whoever follows it now. */
    public void emit(Job job, String type, Object payload) {
        // Inside the lock follow() takes: a page that starts following now gets each event once, replayed or sent.
        synchronized (job.events) {
            job.events.add(new Object[]{type, payload});
            job.listeners.forEach(l -> l.accept(type, payload));
        }
    }

    public void finish(Job job) {
        synchronized (job.events) {
            job.done = true;
            job.listeners.forEach(l -> l.accept("done", Map.of()));
            job.listeners.clear();
        }
    }

    /**
     * Follows a run: every event so far, then the rest as they come, then {@code done}. A run that ended replays and
     * ends at once. The replay and the subscription are taken together, so no event is lost or sent twice.
     */
    public void follow(Job job, BiConsumer<String, Object> listener) {
        // One lock for the replay and the subscription: a live event can neither be lost nor overtake the replay.
        synchronized (job.events) {
            job.events.forEach(e -> listener.accept((String) e[0], e[1]));
            if (!job.done) {
                job.listeners.add(listener);
                return;
            }
        }
        listener.accept("done", Map.of());
    }

    public void unfollow(Job job, BiConsumer<String, Object> listener) {
        job.listeners.remove(listener);
    }

    /** The page recorded the run's result in the progress datapack: nothing left to pick up. */
    public void recorded(String id) {
        Job job = last;
        if (job != null && job.id.equals(id)) job.recorded = true;
    }

    /** The run to follow or to record, by id; null when it is no longer the last one. */
    public Job get(String id) {
        Job job = last;
        return job != null && job.id.equals(id) ? job : null;
    }

    /** The last run, while it is running or its result is not recorded yet; null otherwise. */
    public Job pending() {
        Job job = last;
        return job != null && (!job.done || !job.recorded) ? job : null;
    }
}
