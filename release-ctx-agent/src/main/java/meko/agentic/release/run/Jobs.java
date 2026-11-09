package meko.agentic.release.run;

import meko.agentic.release.amp.AmpDatabase;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The work the page starts (seeding main, uploading knowledge, a rehearsal) runs one job at a time in the background:
 * a rehearsal holds a branch and its compute, and the account's core budget is shared. The page reads a job's log as
 * it grows.
 */
@Component
public class Jobs {
    public record Entry(long at, String kind, String text, Object data) {}

    public interface Work {
        Object run(Job job) throws Exception;
    }

    public static final class Job {
        private final String id;
        private final String type;
        private final long started = System.currentTimeMillis();
        private final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());
        private volatile String status = "running";
        private volatile Object result;
        private volatile String error;
        private volatile long ended;
        /** The branch a rehearsal has open: deleted when the job ends, whatever happened. */
        volatile AmpDatabase.OpenBranch openBranch;

        Job(String id, String type) {
            this.id = id;
            this.type = type;
        }

        public String id() { return id; }
        public String type() { return type; }
        public String status() { return status; }
        public Object result() { return result; }
        public String error() { return error; }
        public long started() { return started; }
        public long ended() { return ended; }

        public List<Entry> entries() {
            synchronized (entries) {
                return List.copyOf(entries);
            }
        }

        public void log(String text) {
            event("log", text, null);
        }

        public void event(String kind, String text, Object data) {
            entries.add(new Entry(System.currentTimeMillis(), kind, text, data));
        }

        public void opened(AmpDatabase.OpenBranch branch) {
            openBranch = branch;
        }

        public void closed() {
            openBranch = null;
        }
    }

    private final AtomicLong ids = new AtomicLong();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final AmpDatabase database;
    private volatile Job current;

    public Jobs(AmpDatabase database) {
        this.database = database;
    }

    public synchronized Job start(String type, Work work) {
        if (current != null && "running".equals(current.status)) {
            throw new IllegalStateException("Another job is running (" + current.type + "): wait for it to finish.");
        }
        Job job = new Job(type + "-" + ids.incrementAndGet(), type);
        jobs.put(job.id, job);
        current = job;
        Thread.ofPlatform().name("job-" + job.id).daemon(true).start(() -> {
            try {
                job.result = work.run(job);
                job.status = "done";
            } catch (Exception e) {
                job.error = AmpDatabase.rootCause(e).equals(String.valueOf(e.getMessage())) ? String.valueOf(e.getMessage())
                        : e.getMessage() + ": " + AmpDatabase.rootCause(e);
                job.event("error", job.error, null);
                job.status = "failed";
            } finally {
                AmpDatabase.OpenBranch left = job.openBranch;
                if (left != null) {
                    try {
                        database.close(left);
                        job.log("Deleted branch " + left.branch().name() + " (the job ended before it did).");
                    } catch (Exception e) {
                        job.event("error", "Could not delete branch " + left.branch().name() + ": delete it in the Meko app. " + e.getMessage(), null);
                    }
                }
                job.ended = System.currentTimeMillis();
            }
        });
        return job;
    }

    public Job current() {
        return current;
    }

    /** Whether a job is running: then nothing that changes what it works on (the datapack, the cluster) may change. */
    public boolean running() {
        Job job = current;
        return job != null && "running".equals(job.status);
    }

    /** Refuses a change while a job runs. */
    public void requireIdle(String what) {
        Job job = current;
        if (job != null && "running".equals(job.status)) {
            throw new IllegalStateException("Cannot " + what + " while a job (" + job.type + ") is running: wait for it to finish.");
        }
    }

    public Job get(String id) {
        return jobs.get(id);
    }
}
