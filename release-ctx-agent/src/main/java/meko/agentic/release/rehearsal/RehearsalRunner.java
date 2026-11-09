package meko.agentic.release.rehearsal;

import meko.agentic.release.amp.Endpoint;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs migration scripts on a rehearsal branch while the branch carries the workloads production would: checkout
 * inserting orders, and (when the rehearsal knows about it) the reporting job's long transaction. Every result comes
 * from the database itself:
 * <ul>
 *   <li>whether a statement rewrote the table: {@code pg_relation_filenode('orders')} changed;</li>
 *   <li>the WAL it wrote: {@code pg_current_wal_lsn()} before and after;</li>
 *   <li>who waited on a lock: {@code pg_stat_activity}, sampled while the statement ran;</li>
 *   <li>what checkout felt: the latency of each insert that overlapped the statement (network round trip included).</li>
 * </ul>
 */
public class RehearsalRunner {
    public static final String MIGRATION = "rehearsal:migration";
    public static final String CHECKOUT = "rehearsal:checkout";
    public static final String REPORTING = "rehearsal:reporting-job";

    /** What runs alongside the migration. */
    public record Workloads(boolean reportingJob, int reportingHoldSeconds) {}

    /**
     * How the migration session runs: {@code lockTimeout} null runs it as written (PostgreSQL's default: wait for
     * locks indefinitely); otherwise a lock timeout cancels the statement, and it is retried up to {@code maxAttempts}.
     */
    public record Settings(String lockTimeout, int maxAttempts, long stallMillis) {}

    public record StepResult(String script, int number, String text, boolean ok, String error, int attempts, long millis,
                             boolean rewrote, long walBytes, boolean migrationWaitedOnLock, boolean checkoutWaitedOnLock,
                             long worstCheckoutMillis, int checkoutStalls) {}

    public record ScriptResult(String script, List<StepResult> steps, boolean completed, long walBytes, long millis) {}

    public record CheckoutStats(int inserts, long medianMillis, long worstMillis, int stalls) {}

    public record Result(List<ScriptResult> scripts, CheckoutStats checkout, boolean reportingJob) {
        public boolean completed() {
            return scripts.stream().allMatch(ScriptResult::completed);
        }
    }

    public interface Listener {
        void note(String text);

        void step(StepResult step);
    }

    private final Endpoint endpoint;
    private final Settings settings;
    private final Listener listener;

    public RehearsalRunner(Endpoint endpoint, Settings settings, Listener listener) {
        this.endpoint = endpoint;
        this.settings = settings;
        this.listener = listener;
    }

    public Result run(List<MigrationScript> scripts, Workloads workloads) throws Exception {
        Checkout checkout = new Checkout();
        Monitor monitor = new Monitor();
        Thread writer = Thread.ofVirtual().name("checkout").start(checkout);
        Thread sampler = Thread.ofVirtual().name("activity-monitor").start(monitor);
        Thread reporting = null;
        List<ScriptResult> results = new ArrayList<>();
        try {
            Thread.sleep(1_500);   // checkout is writing before anything changes
            if (workloads.reportingJob()) {
                reporting = Thread.ofVirtual().name("reporting-job").start(() -> reportingJob(workloads.reportingHoldSeconds()));
                listener.note("The reporting job opened its transaction on orders and holds it for " + workloads.reportingHoldSeconds() + " s.");
                Thread.sleep(1_000);
            }
            try (Connection migration = endpoint.connect(MIGRATION)) {
                if (settings.lockTimeout() != null) execute(migration, "SET lock_timeout = '" + settings.lockTimeout() + "'");
                for (MigrationScript script : scripts) {
                    ScriptResult result = run(migration, script, checkout, monitor);
                    results.add(result);
                    if (!result.completed()) break;
                }
            }
        } finally {
            checkout.stop.set(true);
            monitor.stop.set(true);
            writer.join(10_000);
            sampler.join(10_000);
            if (reporting != null) reporting.join(workloads.reportingHoldSeconds() * 1000L + 10_000);
        }
        return new Result(List.copyOf(results), checkout.stats(settings.stallMillis()), workloads.reportingJob());
    }

    private ScriptResult run(Connection migration, MigrationScript script, Checkout checkout, Monitor monitor) throws Exception {
        List<StepResult> steps = new ArrayList<>();
        long started = System.currentTimeMillis();
        String scriptLsn = lsn(migration);
        int number = 0;
        for (MigrationScript.Step step : script.steps()) {
            number++;
            long filenode = filenode(migration);
            String lsn = lsn(migration);
            long from = System.currentTimeMillis();
            int attempts = 0;
            String error = null;
            while (true) {
                attempts++;
                try {
                    if (step instanceof MigrationScript.Backfill backfill) backfill(migration, backfill);
                    else if (step instanceof MigrationScript.Sql sql && sql.concurrently() && settings.lockTimeout() != null) {
                        // A concurrent index build waits without blocking writers, and a cancelled one leaves an
                        // invalid index behind: it runs without the lock timeout.
                        execute(migration, "SET lock_timeout = 0");
                        try {
                            execute(migration, sql.text());
                        } finally {
                            execute(migration, "SET lock_timeout = '" + settings.lockTimeout() + "'");
                        }
                    } else execute(migration, step.text());
                    error = null;
                    break;
                } catch (SQLException e) {
                    error = e.getSQLState() + ": " + e.getMessage();
                    boolean lockTimeout = "55P03".equals(e.getSQLState());
                    if (!lockTimeout || settings.lockTimeout() == null || attempts >= settings.maxAttempts()) break;
                    listener.note("Step " + number + " gave up waiting for its lock after " + settings.lockTimeout() + " (attempt " + attempts + "): retrying.");
                    Thread.sleep(1_000);
                }
            }
            long to = System.currentTimeMillis();
            StepResult result = new StepResult(script.name(), number, step.text(), error == null, error, attempts, to - from,
                    filenode != 0 && filenode(migration) != filenode, walSince(migration, lsn),
                    monitor.waited(MIGRATION, from, to), monitor.waited(CHECKOUT, from, to),
                    checkout.worst(from, to), checkout.stalls(from, to, settings.stallMillis()));
            steps.add(result);
            listener.step(result);
            if (error != null) break;
        }
        boolean completed = steps.size() == script.steps().size() && steps.stream().allMatch(StepResult::ok);
        return new ScriptResult(script.name(), List.copyOf(steps), completed, walSince(migration, scriptLsn),
                System.currentTimeMillis() - started);
    }

    private void backfill(Connection connection, MigrationScript.Backfill backfill) throws Exception {
        long low;
        long high;
        try (ResultSet rs = connection.createStatement().executeQuery("SELECT coalesce(min(id), 0), coalesce(max(id), 0) FROM " + backfill.table())) {
            rs.next();
            low = rs.getLong(1);
            high = rs.getLong(2);
        }
        String update = "UPDATE " + backfill.table() + " SET " + backfill.column() + " = " + backfill.expression()
                + " WHERE id BETWEEN ? AND ? AND " + backfill.column() + " IS NULL";
        int batches = 0;
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            for (long from = low; from <= high; from += backfill.batch()) {
                statement.setLong(1, from);
                statement.setLong(2, from + backfill.batch() - 1);
                statement.executeUpdate();
                batches++;
            }
        }
        listener.note("Backfilled " + backfill.table() + "." + backfill.column() + " in " + batches + " batches of " + backfill.batch() + " rows.");
    }

    // ------------------------------------------------------------------
    // Plans of the queries that matter, and the schema a rollback must restore
    // ------------------------------------------------------------------

    public record QueryPlan(String name, String sql, long buffers, String node, String index) {}

    /** {@code EXPLAIN (ANALYZE, BUFFERS)}: the buffers a query touched, its top plan node and the index it used. */
    public static QueryPlan explain(Endpoint endpoint, String name, String sql) throws Exception {
        try (Connection connection = endpoint.connect("rehearsal:plan-check");
             ResultSet rs = connection.createStatement().executeQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql)) {
            rs.next();
            JsonNode plan = new ObjectMapper().readTree(rs.getString(1)).path(0).path("Plan");
            long buffers = plan.path("Shared Hit Blocks").asLong() + plan.path("Shared Read Blocks").asLong();
            return new QueryPlan(name, sql, buffers, describe(plan), indexOf(plan));
        }
    }

    private static String describe(JsonNode plan) {
        // The node that does the work: below a Limit, Gather or Sort.
        JsonNode node = plan;
        while (Set.of("Limit", "Gather", "Gather Merge", "Sort", "Incremental Sort").contains(node.path("Node Type").asString(""))
                && node.path("Plans").size() > 0) {
            node = node.path("Plans").path(0);
        }
        return node.path("Node Type").asString("");
    }

    private static String indexOf(JsonNode plan) {
        if (plan.has("Index Name")) return plan.path("Index Name").asString();
        for (JsonNode child : plan.path("Plans").values()) {
            String index = indexOf(child);
            if (index != null) return index;
        }
        return null;
    }

    /** The orders table's columns, indexes and constraints, by definition. */
    public static Set<String> schema(Endpoint endpoint) throws Exception {
        Set<String> schema = new TreeSet<>();
        try (Connection connection = endpoint.connect("rehearsal:schema")) {
            for (String query : List.of(
                    "SELECT 'column ' || attname || ' ' || format_type(atttypid, atttypmod) || CASE WHEN attnotnull THEN ' not null' ELSE '' END"
                            + " FROM pg_attribute WHERE attrelid = 'orders'::regclass AND attnum > 0 AND NOT attisdropped",
                    "SELECT 'index ' || indexdef FROM pg_indexes WHERE tablename = 'orders'",
                    "SELECT 'constraint ' || conname || ' ' || pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'orders'::regclass")) {
                try (ResultSet rs = connection.createStatement().executeQuery(query)) {
                    while (rs.next()) schema.add(rs.getString(1));
                }
            }
        }
        return schema;
    }

    // ------------------------------------------------------------------
    // Workloads
    // ------------------------------------------------------------------

    private record Insert(long started, long millis) {}

    /** Checkout: one order every 200 ms on its own session, each insert timed. */
    private class Checkout implements Runnable {
        final AtomicBoolean stop = new AtomicBoolean();
        final List<Insert> inserts = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void run() {
            try (Connection connection = endpoint.connect(CHECKOUT);
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO orders (customer_id, status, amount) VALUES (1 + (random() * 999)::int, 'PENDING', 25.00)")) {
                while (!stop.get()) {
                    long started = System.currentTimeMillis();
                    try {
                        insert.executeUpdate();
                    } catch (SQLException e) {
                        // A failed insert is what checkout would have seen too: counted by its latency.
                    }
                    inserts.add(new Insert(started, System.currentTimeMillis() - started));
                    Thread.sleep(200);
                }
            } catch (Exception e) {
                listener.note("Checkout workload stopped: " + e.getMessage());
            }
        }

        /** The slowest insert that was in flight at any moment between {@code from} and {@code to}. */
        long worst(long from, long to) {
            synchronized (inserts) {
                return inserts.stream().filter(i -> i.started() <= to && i.started() + i.millis() >= from)
                        .mapToLong(Insert::millis).max().orElse(0);
            }
        }

        int stalls(long from, long to, long threshold) {
            synchronized (inserts) {
                return (int) inserts.stream().filter(i -> i.started() <= to && i.started() + i.millis() >= from)
                        .filter(i -> i.millis() > threshold).count();
            }
        }

        CheckoutStats stats(long threshold) {
            synchronized (inserts) {
                long[] sorted = inserts.stream().mapToLong(Insert::millis).sorted().toArray();
                if (sorted.length == 0) return new CheckoutStats(0, 0, 0, 0);
                return new CheckoutStats(sorted.length, sorted[sorted.length / 2], sorted[sorted.length - 1],
                        (int) inserts.stream().filter(i -> i.millis() > threshold).count());
            }
        }
    }

    /** The nightly reporting job: a long read-only transaction that holds its lock on orders until it commits. */
    private void reportingJob(int holdSeconds) {
        try (Connection connection = endpoint.connect(REPORTING)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeQuery("SELECT count(*) FROM orders WHERE created_at > now() - interval '1 day'").close();
                statement.executeQuery("SELECT pg_sleep(" + holdSeconds + ")").close();
            }
            connection.commit();
        } catch (Exception e) {
            listener.note("Reporting job ended early: " + e.getMessage());
        }
    }

    private record Sample(long at, String application, String waitType) {}

    /** Samples which rehearsal sessions are waiting, and on what. */
    private class Monitor implements Runnable {
        final AtomicBoolean stop = new AtomicBoolean();
        final List<Sample> samples = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void run() {
            try (Connection connection = endpoint.connect("rehearsal:monitor");
                 PreparedStatement query = connection.prepareStatement(
                         "SELECT application_name, coalesce(wait_event_type, '') FROM pg_stat_activity WHERE application_name LIKE 'rehearsal:%' AND state = 'active'")) {
                while (!stop.get()) {
                    long at = System.currentTimeMillis();
                    try (ResultSet rs = query.executeQuery()) {
                        while (rs.next()) samples.add(new Sample(at, rs.getString(1), rs.getString(2)));
                    }
                    Thread.sleep(250);
                }
            } catch (Exception e) {
                listener.note("Activity monitor stopped: " + e.getMessage());
            }
        }

        boolean waited(String application, long from, long to) {
            synchronized (samples) {
                return samples.stream().anyMatch(s -> s.at() >= from && s.at() <= to
                        && s.application().equals(application) && "Lock".equals(s.waitType()));
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long filenode(Connection connection) throws SQLException {
        try (ResultSet rs = connection.createStatement().executeQuery("SELECT coalesce(pg_relation_filenode(to_regclass('orders')), 0)")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String lsn(Connection connection) throws SQLException {
        try (ResultSet rs = connection.createStatement().executeQuery("SELECT pg_current_wal_lsn()::text")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static long walSince(Connection connection, String lsn) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), ?::pg_lsn)::bigint")) {
            statement.setString(1, lsn);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
