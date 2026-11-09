package meko.agentic.release.amp;

import meko.agentic.release.meko.MekoMcpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The AMP cluster the rehearsals run against: its {@code main} branch holds the checkout data, seeded once; every
 * rehearsal forks {@code main}, works on the fork and deletes it. {@code main} is never changed by a rehearsal.
 */
@Component
@EnableConfigurationProperties(AmpProperties.class)
public class AmpDatabase {
    /** A rehearsal's branch, with its writer endpoint, and what each lifecycle step took. */
    public record OpenBranch(String clusterId, AmpClient.Branch branch, AmpClient.Compute compute, Endpoint endpoint,
                             long forkMillis, long readyMillis) {}

    private final AmpClient amp;
    private final AmpProperties properties;
    private final MekoMcpClient meko;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile AmpClient.Cluster cluster;
    private volatile Endpoint main;
    private volatile String publicIp;

    public AmpDatabase(AmpClient amp, AmpProperties properties, MekoMcpClient meko) {
        this.amp = amp;
        this.properties = properties;
        this.meko = meko;
        // Clusters live in a datapack: another datapack means another set of clusters.
        meko.beforeDatapackSwitch(this::forget);
    }

    public synchronized void forget() {
        cluster = null;
        main = null;
        invalidate();
    }

    /** main's facts (version, order count) and the core budget change only when this application changes them. */
    private record MainFacts(String clusterId, String serverVersion, Long orders, String error, long at) {}
    private volatile MainFacts mainFacts;
    private volatile AmpClient.Budget budget;
    private volatile long budgetAt;

    /** Forgets the cached facts: after seeding, creating or choosing a cluster, and opening or closing a branch. */
    public void invalidate() {
        mainFacts = null;
        budget = null;
    }

    private AmpClient.Budget cachedBudget() throws Exception {
        AmpClient.Budget b = budget;
        if (b != null && System.currentTimeMillis() - budgetAt < 10_000) return b;
        b = amp.budget();
        budget = b;
        budgetAt = System.currentTimeMillis();
        return b;
    }

    private MainFacts mainFacts(AmpClient.Cluster c, Endpoint m) {
        MainFacts f = mainFacts;
        if (f != null && f.clusterId().equals(c.id()) && System.currentTimeMillis() - f.at() < 30_000) return f;
        try (Connection connection = m.connect("release-ctx-agent:status");
             ResultSet rs = connection.createStatement().executeQuery(
                     "SELECT to_regclass('public.orders') IS NOT NULL, current_setting('server_version')")) {
            rs.next();
            f = new MainFacts(c.id(), rs.getString(2), rs.getBoolean(1) ? count(connection) : 0L, null, System.currentTimeMillis());
        } catch (Exception e) {
            // Not cached: the next status tries again.
            return new MainFacts(c.id(), null, null, "main is not reachable: " + rootCause(e), 0);
        }
        mainFacts = f;
        return f;
    }

    public AmpClient.Cluster cluster() {
        return cluster;
    }

    public Endpoint main() {
        return main;
    }

    public AmpClient amp() {
        return amp;
    }

    /** The cluster in use, its main endpoint and data, and the account's core budget. Never throws. */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("publicIp", publicIp());
        status.put("passwordConfigured", passwordConfigured());
        AmpClient.Cluster c = cluster;
        if (c == null && properties.getClusterId() != null && !properties.getClusterId().isBlank()) {
            try {
                use(properties.getClusterId(), null);
                c = cluster;
            } catch (Exception e) {
                status.put("error", e.getMessage());
            }
        }
        if (c == null && passwordConfigured()) {
            // After a restart nothing is chosen: with AMP_PASSWORD set and one cluster in the datapack, that is the one.
            try {
                List<AmpClient.Cluster> all = amp.clusters();
                if (all.size() == 1) {
                    use(all.getFirst().id(), null);
                    c = cluster;
                    status.put("autoChosen", true);
                }
            } catch (Exception e) {
                c = cluster;   // chosen even if main does not answer yet
                status.put("error", e.getMessage());
            }
        }
        status.put("cluster", c == null ? null : Map.of("id", c.id(), "name", c.name()));
        status.put("endpointCores", AmpClient.CORES);
        status.put("seedOrders", properties.getSeedOrders());
        // The budget (a Meko call) and main's facts (a database round trip) do not depend on each other.
        java.util.concurrent.CompletableFuture<AmpClient.Budget> b = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return cachedBudget();
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        Endpoint m = main;
        if (c != null && m != null) {
            status.put("main", m.address());
            MainFacts f = mainFacts(c, m);
            status.put("serverVersion", f.serverVersion());
            status.put("orders", f.orders());
            if (f.error() != null) status.put("error", f.error());
        }
        try {
            status.put("budget", b.join());
        } catch (Exception e) {
            status.put("budgetError", rootCause(e));
        }
        return status;
    }

    private static long count(Connection connection) throws Exception {
        // The planner's estimate: exact enough to show, and free on a table of millions.
        try (ResultSet rs = connection.createStatement().executeQuery("SELECT reltuples::bigint FROM pg_class WHERE oid = 'orders'::regclass")) {
            rs.next();
            return Math.max(0, rs.getLong(1));
        }
    }

    public List<AmpClient.Cluster> clusters() throws Exception {
        return amp.clusters();
    }

    /** Whether {@code AMP_PASSWORD} is set: then the page asks for no password, and creating a cluster uses it. */
    public boolean passwordConfigured() {
        return properties.getPassword() != null && !properties.getPassword().isBlank();
    }

    /**
     * Uses an existing cluster of the datapack in use. Its password is the one given, one this application already
     * holds for it, or {@code AMP_PASSWORD}. A wrong password leaves no cluster chosen; an endpoint that does not answer
     * yet leaves the cluster chosen, so the next step can retry.
     */
    public synchronized void use(String clusterId, String password) throws Exception {
        AmpClient.Cluster found = amp.clusters().stream().filter(c -> c.id().equals(clusterId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No AMP cluster " + clusterId + " in this datapack"));
        String secret = password != null && !password.isBlank() ? password : remembered(clusterId);
        if (secret == null) secret = properties.getPassword();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("The password of cluster " + found.name() + " is needed: AMP shows it only when the cluster is created.");
        }
        locate(found, secret);
        try (Connection ignored = main.database("postgres").connect("release-ctx-agent:check")) {
            remember(clusterId, secret);
        } catch (SQLException e) {
            if ("28P01".equals(e.getSQLState())) {
                forget();
                throw new IllegalStateException("The password of cluster " + found.name() + " is wrong.");
            }
            remember(clusterId, secret);
            throw new IllegalStateException("Cluster " + found.name() + " is chosen, but main's endpoint does not answer yet ("
                    + rootCause(e) + "). Try again in a minute.");
        } catch (java.net.UnknownHostException e) {
            remember(clusterId, secret);
            throw new IllegalStateException("Cluster " + found.name() + " is chosen, but " + e.getMessage() + ". Try again in a minute.");
        }
    }

    /** Finds the cluster's main writer and holds it as the cluster in use, without connecting. */
    private void locate(AmpClient.Cluster found, String secret) throws Exception {
        String mainBranch = found.mainBranchId();
        if (mainBranch == null) {
            mainBranch = amp.branches(found.id()).stream().filter(AmpClient.Branch::isDefault).map(AmpClient.Branch::id).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Cluster " + found.name() + " has no default branch"));
        }
        String branchId = mainBranch;
        AmpClient.Compute writer = amp.computes(found.id(), branchId).stream().filter(c -> "read_write".equals(c.role()))
                .findFirst().orElseThrow(() -> new IllegalStateException("main of " + found.name() + " has no writer endpoint"));
        if (!writer.addressed()) throw new IllegalStateException("main's endpoint has no external address yet: try again in a minute");
        cluster = new AmpClient.Cluster(found.id(), found.name(), branchId);
        invalidate();
        main = Endpoint.of(writer.address(), writer.loginUser().isBlank() ? "admin" : writer.loginUser(), secret, properties.getDatabase());
    }

    /**
     * Creates a cluster whose main endpoint admits only {@code cidr}, and uses it. Its admin password is the one given,
     * else {@code AMP_PASSWORD}, else one AMP generates: a generated one goes to {@code generated} as soon as the
     * cluster exists, whatever happens after, since AMP cannot return it again. This application keeps it in memory only.
     * Returns once main's endpoint answers.
     */
    public AmpClient.Created create(String name, String cidr, String password, Consumer<String> progress,
                                    Consumer<String> generated) throws Exception {
        String chosen = password != null && !password.isBlank() ? password : properties.getPassword();
        boolean generate = chosen == null || chosen.isBlank();
        if (!generate && !chosen.matches("[\\x20-\\x7E]{8,128}")) {
            throw new IllegalArgumentException("The password must be 8 to 128 printable ASCII characters.");
        }
        AmpClient.Created created = amp.createCluster(name, List.of(cidr), generate ? null : chosen);
        String secret = created.password();
        if (secret == null) throw new IllegalStateException("amp_cluster_create returned no password");
        if (generate) generated.accept(secret);
        remember(created.cluster().id(), secret);
        progress.accept("AMP created cluster " + created.cluster().name() + ". Waiting for main's endpoint to answer…");
        long deadline = System.currentTimeMillis() + properties.getReadySeconds() * 1000L;
        synchronized (this) {
            while (true) {
                try {
                    locate(created.cluster(), secret);
                    break;
                } catch (IllegalStateException e) {
                    if (!e.getMessage().contains("no external address") || System.currentTimeMillis() > deadline) throw e;
                    Thread.sleep(5_000);
                }
            }
        }
        long started = System.currentTimeMillis();
        awaitReachable(main.database("postgres"), deadline, progress);
        progress.accept("main's endpoint " + main.address() + " answered after " + (System.currentTimeMillis() - started) / 1000 + " s.");
        return new AmpClient.Created(cluster, secret);
    }

    /**
     * Waits until the endpoint accepts a connection. A wrong password ends the wait at once; anything else (a name not
     * published yet, an endpoint still starting) is retried until {@code deadline}, reporting what it waits for.
     */
    private static void awaitReachable(Endpoint endpoint, long deadline, Consumer<String> progress) throws Exception {
        String lastReason = null;
        while (true) {
            try (Connection connection = endpoint.connect("release-ctx-agent:ready");
                 ResultSet rs = connection.createStatement().executeQuery("SELECT 1")) {
                rs.next();
                return;
            } catch (Exception e) {
                if (e instanceof SQLException sql && "28P01".equals(sql.getSQLState())) {
                    throw new IllegalStateException("The endpoint " + endpoint.address() + " rejected the password.");
                }
                String reason = rootCause(e);
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("The endpoint " + endpoint.address() + " did not answer in time: " + reason);
                }
                if (!reason.equals(lastReason)) progress.accept("Not yet: " + reason + ". Retrying…");
                lastReason = reason;
            }
            Thread.sleep(5_000);
        }
    }

    /** The innermost message of an exception chain: what actually went wrong. */
    public static String rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        return t == e ? message : message + " (" + t.getClass().getSimpleName() + ")";
    }

    /**
     * Creates the checkout schema on main and fills it, once: years of order history, sized by
     * {@code amp.seed-customers} and {@code amp.seed-orders}.
     */
    public void seed(Consumer<String> progress) throws Exception {
        Endpoint m = requireMain();
        try (Connection admin = m.database("postgres").connect("release-ctx-agent:seed")) {
            PreparedStatement exists = admin.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?");
            exists.setString(1, m.database());
            if (!exists.executeQuery().next()) {
                admin.createStatement().execute("CREATE DATABASE " + m.database());
                progress.accept("Created database " + m.database() + ".");
            }
        }
        try (Connection connection = m.connect("release-ctx-agent:seed")) {
            for (String statement : statements(read("sql/schema.sql"))) connection.createStatement().execute(statement);
            try (ResultSet rs = connection.createStatement().executeQuery("SELECT EXISTS (SELECT 1 FROM orders)")) {
                rs.next();
                if (rs.getBoolean(1)) {
                    progress.accept("main already holds the checkout data: " + count(connection) + " orders.");
                    return;
                }
            }
            progress.accept("Seeding " + properties.getSeedOrders() + " orders for " + properties.getSeedCustomers()
                    + " customers on main (about a minute on " + AmpClient.CORES + " vCPU)…");
            String seed = read("sql/seed.sql")
                    .replace(":customers", Integer.toString(properties.getSeedCustomers()))
                    .replace(":orders", Integer.toString(properties.getSeedOrders()));
            long started = System.currentTimeMillis();
            for (String statement : statements(seed)) {
                try (Statement s = connection.createStatement()) {
                    s.execute(statement);
                }
            }
            progress.accept("Seeded in " + (System.currentTimeMillis() - started) / 1000 + " s: " + count(connection) + " orders.");
        } finally {
            invalidate();
        }
    }

    /**
     * Forks main and gives the fork a writer endpoint that admits only {@code cidr}; returns once the endpoint answers.
     * Checks the core budget first: the fork's endpoint needs {@link AmpClient#CPU} of it.
     */
    public OpenBranch openBranch(String name, String cidr, Consumer<String> progress) throws Exception {
        return openBranch(name, cidr, null, progress);
    }

    /** As {@link #openBranch(String, String, Consumer)}, forked as of {@code lsn} when given: main as it was then. */
    public OpenBranch openBranch(String name, String cidr, String lsn, Consumer<String> progress) throws Exception {
        AmpClient.Cluster c = requireCluster();
        AmpClient.Budget budget = amp.budget();
        if (budget.maxCores() > 0 && budget.usedCores() + AmpClient.CORES > budget.maxCores()) {
            throw new IllegalStateException("The account's AMP budget is " + budget.usedCores() + " of " + budget.maxCores()
                    + " vCPU (" + budget.tier() + " tier): a branch's endpoint needs " + AmpClient.CORES + " more. Delete a branch or an endpoint first.");
        }
        progress.accept("Core budget: " + budget.usedCores() + " of " + budget.maxCores() + " vCPU in use (" + budget.tier() + " tier).");
        long started = System.currentTimeMillis();
        invalidate();
        AmpClient.Branch branch = amp.fork(c.id(), name, lsn);
        long forkMillis = System.currentTimeMillis() - started;
        progress.accept("Forked main as " + name + " in " + forkMillis + " ms (zero-copy, " + (lsn != null ? "as of the recorded position " : "at LSN ")
                + branch.forkLsn() + ").");
        try {
            long requested = System.currentTimeMillis();
            AmpClient.Compute compute = amp.addWriter(c.id(), branch.id(), List.of(cidr), progress);
            invalidate();   // the endpoint now counts against the core budget
            progress.accept("Requested a " + AmpClient.CPU + " writer endpoint on the branch; waiting for it to answer…");
            Endpoint endpoint = waitUntilReady(c.id(), branch.id(), compute, progress);
            long readyMillis = System.currentTimeMillis() - requested;
            progress.accept("The branch endpoint answered after " + readyMillis / 1000 + " s.");
            return new OpenBranch(c.id(), branch, compute, endpoint, forkMillis, readyMillis);
        } catch (Exception e) {
            // A branch nobody can use is deleted at once: it would hold its compute for nothing.
            try {
                amp.deleteBranch(c.id(), branch.id());
            } catch (Exception ignored) {
                progress.accept("Could not delete branch " + name + " after the failure: delete it in the Meko app.");
            }
            throw e;
        }
    }

    public void close(OpenBranch open) throws Exception {
        try {
            amp.deleteBranch(open.clusterId(), open.branch().id());
        } finally {
            invalidate();
        }
    }

    private Endpoint waitUntilReady(String clusterId, String branchId, AmpClient.Compute compute, Consumer<String> progress) throws Exception {
        long deadline = System.currentTimeMillis() + properties.getReadySeconds() * 1000L;
        AmpClient.Compute current = compute;
        // Only the address AMP reports: another endpoint's host name or port reaches another branch, or main.
        while (!current.addressed()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("AMP reported no address for the branch endpoint within " + properties.getReadySeconds() + " s");
            }
            Thread.sleep(10_000);
            current = amp.compute(clusterId, branchId, compute.id());
        }
        Endpoint endpoint = Endpoint.of(current.address(), main.user(), main.password(), main.database());
        awaitReachable(endpoint, deadline, progress);
        return endpoint;
    }

    /** This machine's public address, which AMP's allow-list must name. Null if it cannot be found. */
    public String publicIp() {
        if (publicIp != null) return publicIp;
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("https://api.ipify.org"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            String ip = response.body().trim();
            if (response.statusCode() == 200 && ip.matches("[0-9.]+|[0-9a-fA-F:]+")) publicIp = ip;
        } catch (Exception ignored) {
            // asked for on the page instead
        }
        return publicIp;
    }

    private AmpClient.Cluster requireCluster() {
        AmpClient.Cluster c = cluster;
        if (c == null) throw new IllegalStateException("No AMP cluster chosen: create or choose one first.");
        return c;
    }

    private Endpoint requireMain() {
        requireCluster();
        return main;
    }

    // ------------------------------------------------------------------
    // Passwords: in memory only, never written to disk
    // ------------------------------------------------------------------

    /** Passwords of clusters this run of the application created or was given. Gone when it stops. */
    private final Map<String, String> passwords = new java.util.concurrent.ConcurrentHashMap<>();

    private String remembered(String clusterId) {
        return passwords.get(clusterId);
    }

    private void remember(String clusterId, String password) {
        passwords.put(clusterId, password);
    }

    // ------------------------------------------------------------------
    // SQL files
    // ------------------------------------------------------------------

    public static String read(String classpath) throws Exception {
        return new ClassPathResource(classpath).getContentAsString(StandardCharsets.UTF_8);
    }

    /** Statements of a file that ends each with a semicolon at the end of a line. */
    static List<String> statements(String sql) {
        List<String> out = new ArrayList<>();
        for (String part : sql.split(";\\s*(\\n|$)")) {
            String body = part.lines().filter(l -> !l.strip().startsWith("--")).reduce("", (a, b) -> a + b + "\n").strip();
            if (!body.isEmpty()) out.add(body);
        }
        return out;
    }
}
