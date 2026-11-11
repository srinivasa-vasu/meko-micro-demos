package meko.agentic.release.api;

import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Budget;
import com.embabel.agent.core.ProcessOptions;
import meko.agentic.release.agent.OrgContext;
import meko.agentic.release.agent.RecoveryAgent;
import meko.agentic.release.agent.RecoveryModels;
import meko.agentic.release.amp.AmpClient;
import meko.agentic.release.rehearsal.IncidentSql;
import meko.agentic.release.agent.Progress;
import meko.agentic.release.agent.ReleaseMemory;
import meko.agentic.release.agent.ReleaseModels;
import meko.agentic.release.agent.ReleaseRehearsalAgent;
import meko.agentic.release.amp.AmpDatabase;
import meko.agentic.client.MekoMcpClient;
import meko.agentic.release.run.Jobs;
import meko.agentic.release.run.PlanEvents;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Map;

/**
 * The workshop page's API. In order: choose or create a datapack; create or choose the AMP cluster in it and seed
 * main; rehearse; add context to the datapack between rehearsals; audit the pull request's record; promote lessons.
 */
@RestController
@RequestMapping("/api")
public class WorkshopController {
    /** The pull request every rehearsal in the workshop is about. */
    public static final String PR = ReleaseMemory.PR;
    /** The Meko project the datapacks this application creates are filed in, and its two folders. */
    static final String PROJECT = "meko-amp-workshop";
    /** What the project was called before; an owned project still under it is renamed rather than duplicated. */
    static final List<String> PROJECT_FORMER_NAMES = List.of("Rehearse Before You Release");
    static final String PROJECT_DESCRIPTION = "Meko × YugabyteDB AMP workshop: rehearsing a release and undoing a bad batch job on AMP branches. "
            + "The team's datapack, and the organization's shared context it reads.";
    static final String TEAM_FOLDER = "Release team";
    static final String TEAM_FOLDER_DESCRIPTION = "The release team's datapack: its AMP cluster, the pull request's records and evidence, and the lessons its rehearsals teach.";
    static final String ORG_FOLDER = "Org context (shared)";
    static final String ORG_FOLDER_DESCRIPTION = "Other teams' datapacks the release agent reads: platform SRE incidents, warehouse and checkout critical queries.";

    private final MekoMcpClient meko;
    private final AmpDatabase database;
    private final ReleaseMemory memory;
    private final Jobs jobs;
    private final AgentPlatform platform;
    private final PlanEvents planEvents;
    private final Progress progress;
    private final OrgContext org;
    /** The shared datapack to connect at start, by name ({@code MEKO_SHARED_DATAPACK}); empty: chosen on the page. */
    private final String sharedName;
    /** Whether each shared datapack holds other teams' context: checked in the background, once per datapack. */
    private final Map<String, CompletableFuture<Boolean>> orgTeams = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean sharedTried;
    /** The datapacks whose recorded shared datapack has been looked up since this application started. */
    private final java.util.Set<String> sharedRecalled = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public WorkshopController(MekoMcpClient meko, AmpDatabase database, ReleaseMemory memory, Jobs jobs,
                              AgentPlatform platform, PlanEvents planEvents, Progress progress, OrgContext org,
                              @org.springframework.beans.factory.annotation.Value("${release.shared-datapack:}") String sharedName) {
        this.progress = progress;
        this.org = org;
        this.sharedName = sharedName;
        this.meko = meko;
        meko.formerlyNamed(PROJECT, PROJECT_FORMER_NAMES);
        this.database = database;
        this.memory = memory;
        this.jobs = jobs;
        this.platform = platform;
        this.planEvents = planEvents;
    }

    /** The status's own threads: they wait on Meko and AMP, so they do not borrow the common pool's few. */
    private static final java.util.concurrent.ExecutorService STATUS = java.util.concurrent.Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "status");
        t.setDaemon(true);
        return t;
    });

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("pr", PR);
        status.put("keyed", meko.keyed());
        boolean chosen = meko.keyed() && meko.datapackName() != null && !meko.datapackName().isBlank();
        boolean exists = chosen && meko.datapackStillExists();
        // Meko, AMP and the progress do not depend on each other: asked side by side, the slowest sets the time.
        CompletableFuture<Map<String, Object>> m = CompletableFuture.supplyAsync(() -> exists ? meko.status() : Map.of("connected", false), STATUS);
        CompletableFuture<Map<String, Object>> a = exists ? CompletableFuture.supplyAsync(database::status, STATUS) : null;
        CompletableFuture<Map<String, Object>> p = exists ? CompletableFuture.supplyAsync(() -> {
            try {
                return progress.snapshot(PR);
            } catch (Exception e) {
                return Map.<String, Object>of("error", String.valueOf(e.getMessage()));
            }
        }, STATUS) : null;
        Map<String, Object> mekoStatus = m.join();
        // Only a datapack that exists has clusters and progress: a configured name that does not resolve has neither.
        boolean connected = Boolean.TRUE.equals(mekoStatus.get("connected"));
        status.put("meko", mekoStatus);
        status.put("amp", connected && a != null ? a.join() : Map.of("publicIp", String.valueOf(database.publicIp())));
        status.put("lessons", memory.lessons().size());
        if (connected && p != null) {
            Map<String, Object> snapshot = p.join();
            if (snapshot.containsKey("error")) status.put("progressError", snapshot.get("error"));
            else status.put("progress", snapshot);
            try {
                // The incident is the record's current cycle: read back from Meko, so it survives a restart.
                Map<String, Object> incidentProgress = new java.util.LinkedHashMap<>(progress.snapshot(ReleaseMemory.INCIDENT));
                incidentProgress.put("job", IncidentSql.JOB);
                incidentProgress.put("jobSelects", IncidentSql.JOB_SELECTS);
                incidentProgress.put("jobValue", IncidentSql.JOB_VALUE);
                status.put("incidentProgress", incidentProgress);
            } catch (Exception e) {
                status.put("progressError", e.getMessage());
            }
        }
        if (connected) status.put("org", orgStatus());
        Jobs.Job job = jobs.current();
        status.put("job", job == null ? null : Map.of("id", job.id(), "type", job.type(), "status", job.status(), "started", job.started()));
        return status;
    }

    // ------------------------------------------------------------------
    // Step 1: the datapack (context and clusters live in it)
    // ------------------------------------------------------------------

    @GetMapping("/datapacks")
    public List<Map<String, String>> datapacks() throws Exception {
        return meko.listDatapacks();
    }

    public record DatapackChoice(String name, String id, boolean create) {}

    /**
     * Creates or chooses the datapack. One at a time, and creating is idempotent: a name this account already has (a
     * double submit, a retry) is used, not created twice.
     */
    @PostMapping("/datapacks")
    public synchronized Map<String, Object> useDatapack(@RequestBody DatapackChoice choice) throws Exception {
        if (choice.name() == null || choice.name().isBlank()) throw new IllegalArgumentException("Name the datapack.");
        // A running job writes to the datapack in use, and its branch lives in that datapack's cluster.
        jobs.requireIdle("switch the datapack");
        String name = choice.name().strip();
        boolean create = choice.create();
        String knownId = choice.id();
        if (create) {
            for (Map<String, String> existing : meko.listDatapacks()) {
                if (name.equalsIgnoreCase(existing.get("name")) && "owner".equals(existing.get("grant"))) {
                    create = false;
                    knownId = existing.get("id");
                    break;
                }
            }
        }
        // A datapack created here is filed in the project's release-team folder.
        String id = meko.useDatapack(name, create, knownId, create ? folder(TEAM_FOLDER, TEAM_FOLDER_DESCRIPTION, null) : null);
        if (org.shared() != null && org.shared().id().equals(id)) org.disconnect();
        return Map.of("name", name, "id", id, "created", create);
    }

    // ------------------------------------------------------------------
    // The organization's shared datapack: other teams' context, read besides the release team's own
    // ------------------------------------------------------------------

    private Map<String, Object> orgStatus() {
        // After a restart: the shared datapack this team last connected, as its own datapack records it.
        try {
            String own = meko.datapack();
            if (org.shared() == null && sharedRecalled.add(own)) {
                String[] last = memory.recallShared();
                if (last != null && !last[0].isBlank()) connectShared(last[1], last[0]);
            }
        } catch (Exception e) {
            meko.recordError("Could not reconnect the shared datapack: " + e.getMessage());
        }
        if (org.shared() == null && !sharedTried && sharedName != null && !sharedName.isBlank()) {
            sharedTried = true;
            try {
                connectShared(sharedName, null);
            } catch (Exception e) {
                meko.recordError("MEKO_SHARED_DATAPACK " + sharedName + ": " + e.getMessage());
            }
        }
        OrgContext.Shared shared = org.shared();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connected", shared != null);
        if (shared == null) return out;
        out.put("id", shared.id());
        out.put("name", shared.name());
        out.put("grant", shared.grant());
        out.put("owner", shared.owner());
        CompletableFuture<Boolean> teams = orgTeams.computeIfAbsent(shared.id(), id -> CompletableFuture.supplyAsync(() -> {
            try {
                return memory.teamContextPresent(id);
            } catch (Exception e) {
                return false;
            }
        }, STATUS));
        out.put("teams", teams.isDone() ? teams.join() : null);
        return out;
    }

    /** Connects a datapack this account can see, by name or id, as the organization's shared context. */
    private OrgContext.Shared connectShared(String name, String id) throws Exception {
        for (Map<String, String> row : meko.listDatapacks()) {
            if ((id != null && id.equals(row.get("id"))) || (id == null && name.equalsIgnoreCase(row.get("name")))) {
                if (row.get("id").equals(meko.datapack())) {
                    throw new IllegalArgumentException("That is the release team's own datapack: choose the organization's shared one.");
                }
                OrgContext.Shared shared = new OrgContext.Shared(row.get("id"), row.get("name"), row.get("grant"), row.get("owner"));
                if (org.shared() == null || !org.shared().id().equals(shared.id())) memory.rememberShared(shared.id(), shared.name());
                org.connect(shared);
                return shared;
            }
        }
        throw new IllegalArgumentException("No datapack " + (id != null ? id : name) + " is visible to this account.");
    }

    @GetMapping("/org/datapacks")
    public List<Map<String, String>> orgCandidates() throws Exception {
        String own = meko.datapack();
        return meko.listDatapacks().stream().filter(d -> !own.equals(d.get("id"))).toList();
    }

    public record OrgChoice(String id, String name, boolean create) {}

    /**
     * Connects the organization's shared datapack: one shared with this account (or any it can see), or, for a solo run,
     * a new one created in this account and filled with the other teams' memory.
     */
    @PostMapping("/org")
    public synchronized Map<String, Object> connectOrg(@RequestBody OrgChoice choice) throws Exception {
        jobs.requireIdle("change the shared datapack");
        if (!choice.create()) {
            OrgContext.Shared shared = connectShared(choice.name(), choice.id());
            return Map.of("connected", shared.name());
        }
        String name = choice.name() == null || choice.name().isBlank() ? "org-context" : choice.name().strip();
        Jobs.Job job = jobs.start("org", j -> {
            String id = null;
            for (Map<String, String> row : meko.listDatapacks()) {
                if (name.equalsIgnoreCase(row.get("name")) && "owner".equals(row.get("grant"))) id = row.get("id");
            }
            if (id == null) {
                id = meko.createDatapack(name, folder(ORG_FOLDER, ORG_FOLDER_DESCRIPTION, j));
                j.log("Created datapack " + name + " for the organization's shared context. In a real organization another team owns it and shares it with release management in the Meko app.");
            }
            OrgContext.Shared shared = new OrgContext.Shared(id, name, "owner", "you");
            memory.rememberShared(id, name);
            org.connect(shared);
            fill(shared, j);
            return Map.of("connected", name);
        });
        return Map.of("job", job.id());
    }

    /** Writes the other teams' memory into the shared datapack, in their names: possible only with write access to it. */
    @PostMapping("/org/fill")
    public Map<String, String> fillOrg() {
        OrgContext.Shared shared = org.shared();
        if (shared == null) throw new IllegalStateException("Connect the organization's shared datapack first.");
        Jobs.Job job = jobs.start("org", j -> {
            fill(shared, j);
            return null;
        });
        return Map.of("job", job.id());
    }

    /** Fills the shared datapack once: one that already holds other teams' context is left as it is. */
    private void fill(OrgContext.Shared shared, Jobs.Job j) throws Exception {
        if (!memory.runbookPresent(shared.id())) memory.uploadRunbook(shared.id(), j::log);
        if (memory.teamContextPresent(shared.id())) {
            j.log(shared.name() + " already holds other teams' incidents and critical queries: nothing to add.");
            orgTeams.remove(shared.id());
            return;
        }
        j.log("The platform SRE, warehouse and checkout teams' agents write what they know to " + shared.name() + ", each in its own name.");
        try {
            memory.addOtherTeams(shared.id(), j::log);
        } finally {
            orgTeams.remove(shared.id());
        }
    }

    @PostMapping("/org/disconnect")
    public Map<String, Boolean> disconnectOrg() {
        jobs.requireIdle("change the shared datapack");
        org.disconnect();
        memory.rememberShared(null, null);
        return Map.of("connected", false);
    }

    /** A folder of this application's project, created the first time; null (and a note) if Meko's projects are unavailable. */
    private String folder(String folder, String description, Jobs.Job j) {
        try {
            return meko.projectFolder(PROJECT, PROJECT_DESCRIPTION, folder, description);
        } catch (Exception e) {
            if (j != null) j.log("Could not file it in the project " + PROJECT + " (" + e.getMessage() + "): created outside any project.");
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Step 2: the AMP cluster, and main's data
    // ------------------------------------------------------------------

    @GetMapping("/clusters")
    public List<Map<String, String>> clusters() throws Exception {
        return database.clusters().stream().map(c -> Map.of("id", c.id(), "name", c.name())).toList();
    }

    /** {@code password} empty: {@code AMP_PASSWORD} if set, else AMP generates one, shown once on the page. */
    public record NewCluster(String name, String cidr, String password) {}

    @PostMapping("/clusters")
    public Map<String, String> createCluster(@RequestBody NewCluster request) {
        String cidr = cidr(request.cidr());
        String name = request.name() == null || request.name().isBlank() ? "checkout" : request.name().strip();
        Jobs.Job job = jobs.start("cluster", j -> {
            String source = request.password() != null && !request.password().isBlank() ? "the password you chose"
                    : database.passwordConfigured() ? "AMP_PASSWORD" : null;
            j.log("Creating cluster " + name + " in datapack " + meko.datapackName() + ": main gets a 0.5 vCPU writer that admits "
                    + cidr + " only, with " + (source != null ? source : "a password AMP generates") + ".");
            var created = database.create(name, cidr, request.password(), j::log, password -> {
                // Shown on the page at once and never logged: AMP cannot return it again.
                j.event("generatedPassword", "AMP generated the admin password", password);
                j.log("AMP generated the admin password: it is shown above, once. Save it now.");
            });
            j.log("Cluster " + created.cluster().name() + " is ready. Its password is held in this application's memory only.");
            database.seed(j::log);
            return Map.of("cluster", created.cluster().id());
        });
        return Map.of("job", job.id());
    }

    public record ClusterChoice(String id, String password) {}

    @PostMapping("/clusters/use")
    public synchronized Map<String, String> useCluster(@RequestBody ClusterChoice choice) throws Exception {
        jobs.requireIdle("switch the cluster");
        database.use(choice.id(), choice.password());
        return Map.of("cluster", choice.id());
    }

    @PostMapping("/seed")
    public Map<String, String> seed() {
        Jobs.Job job = jobs.start("seed", j -> {
            database.seed(j::log);
            return null;
        });
        return Map.of("job", job.id());
    }

    // ------------------------------------------------------------------
    // The rehearsal
    // ------------------------------------------------------------------

    public record RunRequest(String cidr) {}

    @PostMapping("/rehearsals")
    public Map<String, String> rehearse(@RequestBody RunRequest request) {
        String cidr = cidr(request.cidr());
        if (database.cluster() == null) throw new IllegalStateException("Create or choose a cluster first.");
        Jobs.Job job = jobs.start("rehearsal", j -> {
            Agent agent = platform.agents().stream().filter(a -> a.getName().equals(ReleaseRehearsalAgent.class.getSimpleName()))
                    .findFirst().orElseThrow();
            ProcessOptions options = new ProcessOptions().withBudget(new Budget(20.0, 60, Budget.DEFAULT_TOKEN_LIMIT));
            AgentProcess process = platform.createAgentProcessFrom(agent, options,
                    new ReleaseRehearsalAgent.Run(new ReleaseModels.RehearsalRequest(j.id(), PR), cidr));
            planEvents.register(process.getId(), j);
            try {
                process.run();
            } finally {
                planEvents.unregister(process.getId());
            }
            if (!process.getFinished()) throw new IllegalStateException("The agent stopped before deciding (" + process.getStatus() + ").");
            return process.lastResult();
        });
        return Map.of("job", job.id());
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<Map<String, Object>> job(@PathVariable String id) {
        Jobs.Job job = jobs.get(id);
        if (job == null) return ResponseEntity.notFound().build();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", job.id());
        out.put("type", job.type());
        out.put("status", job.status());
        out.put("started", job.started());
        out.put("ended", job.ended());
        out.put("entries", job.entries());
        out.put("result", job.result());
        out.put("error", job.error());
        return ResponseEntity.ok(out);
    }

    // ------------------------------------------------------------------
    // Context added between rehearsals, and what is read back afterwards
    // ------------------------------------------------------------------

    @PostMapping("/knowledge")
    public Map<String, String> uploadKnowledge() {
        Jobs.Job job = jobs.start("knowledge", j -> {
            try {
                memory.uploadKnowledge(j::log);
            } finally {
                meko.forgetKnowledgeCounts();
            }
            return null;
        });
        return Map.of("job", job.id());
    }

    @GetMapping("/audit")
    public Map<String, Object> audit(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "PR-4821") String record) throws Exception {
        if (!record.equals(PR) && !record.equals(ReleaseMemory.INCIDENT)) throw new IllegalArgumentException("Unknown record " + record);
        return memory.audit(record);
    }

    // ------------------------------------------------------------------
    // The recovery use case: the incident on orders, and its recovery
    // ------------------------------------------------------------------

    /**
     * Runs the bad nightly job on main's orders, the way it happens in production: the batch scheduler's agent writes its
     * marker (the WAL position before the job) to the organization's shared datapack and starts the job; the job marks
     * every paid order pending; checkout keeps writing; the SRE agent records the incident with what it saw and when.
     */
    @PostMapping("/incident")
    public Map<String, String> incident() {
        AmpClient.Cluster cluster = database.cluster();
        if (cluster == null) throw new IllegalStateException("Create or choose a seeded cluster first.");
        OrgContext.Shared shared = org.shared();
        if (shared == null || !shared.owned()) {
            throw new IllegalStateException("The scheduler and the SRE agent write to the organization's shared datapack: connect one you own (create one for a solo run).");
        }
        try {
            if (progress.incidentActive(ReleaseMemory.INCIDENT)) {
                throw new IllegalStateException("The incident is still unrepaired: recover it first, or start over.");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the incident's record: " + e.getMessage());
        }
        Jobs.Job job = jobs.start("incident", j -> {
            if (!memory.runbookPresent(shared.id())) memory.uploadRunbook(shared.id(), j::log);
            try (java.sql.Connection connection = database.main().connect("platform-batch:" + IncidentSql.JOB)) {
                // A run of the job, as a scheduler runs it: an id, a start marker, the job, an end marker once it committed.
                String run = "r-" + java.util.HexFormat.of().formatHex(java.nio.ByteBuffer.allocate(4).putInt(new java.security.SecureRandom().nextInt()).array());
                IncidentSql.Position before = IncidentSql.position(connection);
                Map<String, Integer> countsBefore = IncidentSql.statusCounts(connection);
                memory.writeRunStart(shared.id(), IncidentSql.JOB, "orders", run, before.lsn(), before.at());
                j.log("The batch scheduler's agent recorded the start of " + IncidentSql.JOB + " run " + run + " in " + shared.name() + ": "
                        + before.at() + " UTC, position " + before.lsn() + ".");
                int changed = IncidentSql.runBadJob(connection);
                IncidentSql.Position afterJob = IncidentSql.position(connection);
                memory.writeRunEnd(shared.id(), IncidentSql.JOB, "orders", run, afterJob.lsn(), afterJob.at(), changed);
                j.log(IncidentSql.JOB + " run " + run + " ran with its defect: it set " + changed + " " + IncidentSql.JOB_SELECTS + " orders to " + IncidentSql.JOB_VALUE + ". The scheduler's agent recorded its end: "
                        + afterJob.at() + " UTC, position " + afterJob.lsn() + ", " + changed + " rows changed.");
                Map<String, Integer> written = IncidentSql.checkoutContinues(connection, 200, 100);
                j.log("Checkout kept writing: " + written.values().stream().mapToInt(Integer::intValue).sum() + " new orders " + written + ".");
                Map<String, Integer> countsAfter = IncidentSql.statusCounts(connection);
                IncidentSql.Position detected = IncidentSql.position(connection);
                // What the SRE agent sees: the counts by status, against what they were. It knows no WAL position.
                memory.writeIncident(shared.id(), ReleaseMemory.INCIDENT, "orders", detected.at(),
                        IncidentSql.JOB_SELECTS + " orders on the orders table fell from " + countsBefore.getOrDefault(IncidentSql.JOB_SELECTS, 0) + " to "
                                + countsAfter.getOrDefault(IncidentSql.JOB_SELECTS, 0) + ", and " + IncidentSql.JOB_VALUE + " orders rose from "
                                + countsBefore.getOrDefault(IncidentSql.JOB_VALUE, 0) + " to " + countsAfter.getOrDefault(IncidentSql.JOB_VALUE, 0)
                                + "; the warehouse has nothing to pick.");
                j.log("The SRE agent recorded " + ReleaseMemory.INCIDENT + " in " + shared.name() + " at " + detected.at()
                        + " UTC: what it saw and when. It knows no WAL position.");
                // A new occurrence: a new cycle of the incident's record, which the recoveries that follow belong to.
                Progress.Incident occurrence = new Progress.Incident(before.at(), before.lsn(), detected.at(), changed,
                        countsBefore, countsAfter, written, run);
                j.event("counts", "Orders by status, before and after the job", Map.of("before", countsBefore, "after", countsAfter));
                memory.note(ReleaseMemory.INCIDENT, j.id(), "incident", "Run " + IncidentSql.JOB + " with its defect on orders",
                        progress.incidentTurn(occurrence));
                progress.newCycle(ReleaseMemory.INCIDENT, occurrence);
            } finally {
                database.invalidate();
            }
            return null;
        });
        return Map.of("job", job.id());
    }

    public record RecoverRequest(String cidr, boolean readShared) {}

    public record StartOver(String record) {}

    /**
     * Starts a use case over in the same datapack: a {@code cycle} turn on its record, after which only new runs count.
     * Nothing is deleted: the record keeps every earlier run, for an audit. For the incident, the next step is to run the
     * faulty job again.
     */
    @PostMapping("/start-over")
    public synchronized Map<String, String> startOver(@RequestBody StartOver request) throws Exception {
        jobs.requireIdle("start over");
        String record = request.record();
        if (!PR.equals(record) && !ReleaseMemory.INCIDENT.equals(record)) throw new IllegalArgumentException("Unknown record " + record);
        memory.note(record, "start-over", "cycle", "Start over", "A new cycle starts: runs before this turn belong to an earlier one.");
        progress.newCycle(record, null);
        return Map.of("record", record);
    }

    @PostMapping("/recover")
    public Map<String, String> recover(@RequestBody RecoverRequest request) {
        String cidr = cidr(request.cidr());
        AmpClient.Cluster cluster = database.cluster();
        if (cluster == null) throw new IllegalStateException("Create or choose a seeded cluster first.");
        Jobs.Job job = jobs.start("recovery", j -> {
            Agent agent = platform.agents().stream().filter(a -> a.getName().equals(RecoveryAgent.class.getSimpleName()))
                    .findFirst().orElseThrow();
            ProcessOptions options = new ProcessOptions().withBudget(new Budget(20.0, 60, Budget.DEFAULT_TOKEN_LIMIT));
            AgentProcess process = platform.createAgentProcessFrom(agent, options, new RecoveryAgent.Run(
                    new RecoveryModels.RecoveryRequest(j.id(), ReleaseMemory.INCIDENT, request.readShared(), cidr)));
            planEvents.register(process.getId(), j);
            try {
                process.run();
            } finally {
                planEvents.unregister(process.getId());
                database.invalidate();
            }
            if (!process.getFinished()) throw new IllegalStateException("The agent stopped before deciding (" + process.getStatus() + ").");
            return process.lastResult();
        });
        return Map.of("job", job.id());
    }

    /** The lessons the agents wrote in this application's run, ready to promote: their text, by memory id. */
    @GetMapping("/lessons")
    public List<Map<String, String>> lessons() {
        return memory.lessons().entrySet().stream().map(e -> Map.of("id", e.getKey(), "text", e.getValue())).toList();
    }

    @PostMapping("/promote")
    public Map<String, String> promote() {
        Jobs.Job job = jobs.start("promote", j -> {
            try {
                return memory.promoteLessons(j::log);
            } finally {
                meko.forgetKnowledgeCounts();
            }
        });
        return Map.of("job", job.id());
    }

    /** One address, as AMP's allow-list wants it: an explicit prefix no wider than /24 (IPv4) or /48 (IPv6). */
    private String cidr(String given) {
        String value = given == null || given.isBlank() ? database.publicIp() : given.strip();
        if (value == null) throw new IllegalArgumentException("This machine's public IP could not be found: enter it.");
        if (!value.contains("/")) value = value + (value.contains(":") ? "/128" : "/32");
        return value;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> failed(Exception e) {
        HttpStatus status = e instanceof IllegalArgumentException || e instanceof IllegalStateException ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage() == null ? e.toString() : e.getMessage()));
    }
}
