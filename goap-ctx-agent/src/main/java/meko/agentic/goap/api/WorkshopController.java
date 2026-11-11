package meko.agentic.goap.api;

import meko.agentic.client.MekoMcpClient;
import meko.agentic.goap.story.KnowledgeDocuments;
import meko.agentic.goap.story.StoryCatalog;
import meko.agentic.goap.story.StorySeeder;
import meko.agentic.goap.story.Workshop;
import meko.agentic.goap.story.store.ContextStore;
import meko.agentic.goap.story.store.ContextStores;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The workshop's own calls: where a participant's datapack is (its stage, read from what it holds), and the two steps
 * that move it on: adding the documents and seeding the history. Runs use the same story endpoints as the demo.
 */
@RestController
@RequestMapping("/api/workshop")
public class WorkshopController {
    private final Workshop workshop;
    private final StorySeeder seeder;
    private final StoryCatalog catalog;
    private final ContextStores stores;
    private final MekoMcpClient meko;
    private final boolean models;
    private final KnowledgeDocuments files;
    private final meko.agentic.goap.story.StoryService stories;
    private final meko.agentic.goap.context.MekoSelfLearning selfLearning;

    /** One piece of work that changes the datapack at a time: switching datapacks and adding to one enter it. */
    private final WorkGate gate;
    /** The run the page can follow again, and the progress kept in Meko. */
    private final RunJobs jobs;
    private final WorkshopProgress progress;

    public WorkshopController(Workshop workshop, StorySeeder seeder, StoryCatalog catalog, ContextStores stores, MekoMcpClient meko,
                              @org.springframework.beans.factory.annotation.Value("${goap.llm.enabled:true}") boolean models,
                              KnowledgeDocuments files, meko.agentic.goap.story.StoryService stories,
                              meko.agentic.goap.context.MekoSelfLearning selfLearning, WorkGate gate, RunJobs jobs, WorkshopProgress progress) {
        this.gate = gate;
        this.jobs = jobs;
        this.progress = progress;
        this.selfLearning = selfLearning;
        this.stories = stories;
        this.files = files;
        this.models = models;
        this.workshop = workshop;
        this.seeder = seeder;
        this.catalog = catalog;
        this.stores = stores;
        this.meko = meko;
    }

    /** The use cases in order, what each needs, and what the datapack holds now. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return status(null, null);
    }

    /**
     * The status, with the documents' or history's state passed in when the caller already knows it (a step that found
     * everything already there): only what may have changed is asked again. The rest is asked at once, not in turn.
     */
    private Map<String, Object> status(Map<String, String> knownDocuments, Map<String, String> knownHistory) {
        var store = java.util.concurrent.CompletableFuture.supplyAsync(this::storeStatus, CHECKS);
        var docs = knownDocuments != null ? java.util.concurrent.CompletableFuture.completedFuture(knownDocuments)
                : java.util.concurrent.CompletableFuture.supplyAsync(() -> seeder.documentStatus(workshop.documents()), CHECKS);
        var hist = knownHistory != null ? java.util.concurrent.CompletableFuture.completedFuture(knownHistory)
                : java.util.concurrent.CompletableFuture.supplyAsync(() -> seeder.historyStatus(workshop.historyUseCases()), CHECKS);
        Map<String, String> documents = docs.join();
        Map<String, String> history = hist.join();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("useCases", workshop.useCases().stream().map(u -> Map.of(
                "id", u.id(), "title", catalog.get(u.id()).title(), "domain", catalog.get(u.id()).domain(),
                "documents", u.documents(), "history", u.history())).toList());
        status.put("store", store.join());
        status.put("documents", documents);
        // The file to upload for each document, from src/main/resources/kb/.
        status.put("files", files.all().stream().filter(d -> documents.containsKey(d.key()))
                .collect(java.util.stream.Collectors.toMap(KnowledgeDocuments.Document::key, KnowledgeDocuments.Document::filename, (a, b) -> a, LinkedHashMap::new)));
        status.put("history", history);
        status.putAll(Workshop.stage(documents.values().stream().allMatch(v -> !v.equals("missing")),
                history.values().stream().allMatch(v -> v.equals("seeded"))));
        return status;
    }

    /** The status checks' own threads: they wait on Meko, so they do not borrow the common pool's few. */
    private static final java.util.concurrent.ExecutorService CHECKS = java.util.concurrent.Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "workshop-status");
        t.setDaemon(true);
        return t;
    });

    /** The stand-in is emptied on every start, so its runs belong to this start of the application only. */
    private static final String STAND_IN_ID = "stand-in-" + java.util.UUID.randomUUID();

    /** Pre-flight: which store the Meko lane uses, and whether it is reachable. */
    @GetMapping("/preflight")
    public Map<String, Object> storeStatus() {
        warmProject();
        // A datapack deleted in the Meko app is forgotten here, so the page asks for a new one.
        boolean deleted = meko.configured() && !meko.datapackStillExists();
        ContextStore store = stores.forLane(ContextStores.Lane.MEKO);
        Map<String, Object> s = new LinkedHashMap<>();
        boolean live = store.kind() == ContextStore.Kind.MEKO_LIVE;
        s.put("live", live);
        // A key without a datapack: the participant names one on the workshop page, and the Meko lane switches to it.
        s.put("keyed", meko.keyed());
        s.put("needsDatapack", meko.keyed() && !live);
        if (deleted) s.put("error", "The datapack you were using was deleted: create a new one.");
        s.put("models", models ? "on" : "off: gates decide by rule, the writer uses its template");
        if (live) {
            Map<String, Object> m = meko.status();
            s.put("connected", m.get("connected"));
            s.put("datapack", m.get("datapackName"));
            s.put("datapackId", m.get("datapackId"));
            if (m.get("diagnosis") != null) s.put("error", m.get("diagnosis"));
            // What the page's saved runs belong to: the datapack they ran against, and only while it is connected. A
            // datapack that cannot be reached (deleted, misnamed) gets this start's own id, so nothing carries over.
            boolean connected = Boolean.TRUE.equals(m.get("connected")) && m.get("datapackId") != null;
            s.put("contextId", connected ? m.get("datapackId") : STAND_IN_ID);
            if (!connected && m.get("diagnosis") != null && String.valueOf(m.get("diagnosis")).startsWith("No Meko datapack named")) {
                s.put("error", "The datapack " + m.get("datapackName") + " does not exist (deleted?): create a new one.");
            }
        } else {
            s.put("note", meko.keyed() ? "No datapack chosen yet: create one in step 1."
                    : "Meko is not configured: the Meko lane uses the in-memory stand-in, which starts empty and is lost on restart.");
            s.put("contextId", STAND_IN_ID);
        }
        return s;
    }

    /**
     * The datapacks in the account, for "Use existing": workshop datapacks first, newest first. Any other datapack is
     * marked as not created for the workshop: it already holds context, so the stages would not start clean.
     */
    @GetMapping("/datapacks")
    public List<Map<String, Object>> datapacks() throws Exception {
        return meko.listDatapacks().stream()
                // The progress datapack holds the page's records, not context: never offered as a workshop datapack.
                .filter(d -> !WorkshopProgress.isProgress(d.get("name")))
                .sorted(java.util.Comparator.<Map<String, String>, Boolean>comparing(d -> !d.get("name").startsWith("meko-workshop"))
                        .thenComparing(d -> d.get("created"), java.util.Comparator.reverseOrder()))
                .map(d -> Map.<String, Object>of("id", d.get("id"), "name", d.get("name"), "created", d.get("created"), "workshop", d.get("name").startsWith("meko-workshop"),
                        // Shared with this key by someone else (the pair exercise): who owns it.
                        "sharedBy", d.getOrDefault("grant", "owner").equals("owner") || d.getOrDefault("grant", "").isBlank() ? "" : d.getOrDefault("owner", "")))
                .toList();
    }

    /** The project every datapack created by the workshop is filed into, one folder per vertical. */
    static final String PROJECT = "meko-workshop";
    static final String PROJECT_DESCRIPTION = "Datapacks created by the Meko workshop, by vertical: SDLC (New Session, Same Context) "
            + "and BFSI (disputes, KYC, the organization learned, lending). A datapack shared by all use cases is in its own folder.";
    /** The folder for each group the page names: its name and what belongs in it. */
    private static final Map<String, List<String>> FOLDERS = Map.of(
            "sdlc", List.of("SDLC", "Workshop datapacks for the SDLC use case: New Session, Same Context (AI assistants)."),
            "bfsi", List.of("BFSI", "Workshop datapacks for the BFSI use cases: disputes, KYC, the organization learned, lending."),
            "all", List.of("All use cases", "Workshop datapacks shared by every use case, SDLC and BFSI alike."));

    /**
     * Step 1: create a fresh datapack (or use an existing one by name), and point the Meko lane at it. A created one is
     * filed into the workshop's project, in the folder for {@code group} (sdlc, bfsi, or all for one datapack for all
     * use cases); an existing one is used as it is, wherever it is filed.
     */
    @PostMapping("/datapack")
    public Map<String, Object> datapack(@org.springframework.web.bind.annotation.RequestParam String name,
                                        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "true") boolean create,
                                        @org.springframework.web.bind.annotation.RequestParam(required = false) String id,
                                        @org.springframework.web.bind.annotation.RequestParam(required = false) String group) throws Exception {
        try (var pass = gate.enter("switching datapacks")) {
            return datapackGuarded(name, create, id, group);
        }
    }

    private Map<String, Object> datapackGuarded(String name, boolean create, String id, String group) throws Exception {
        if (!name.matches("[a-z0-9][a-z0-9-]{2,62}")) {
            throw new IllegalArgumentException("Use 3 to 63 lowercase letters, digits and hyphens, starting with a letter or digit.");
        }
        if (group != null && !FOLDERS.containsKey(group)) throw new IllegalArgumentException("Unknown group " + group + ": use one of " + FOLDERS.keySet());
        if (!create) id = existingId(name, id);
        // Filing is a convenience: the datapack is created and in use either way. The folder is resolved first (usually
        // already known, from the warm-up), so the datapack is created straight into it.
        List<String> folder = create && group != null ? FOLDERS.get(group) : null;
        String folderId = null, fileError = null;
        if (folder != null) {
            try {
                folderId = meko.projectFolder(PROJECT, PROJECT_DESCRIPTION, folder.get(0), folder.get(1));
            } catch (Exception e) {
                fileError = e.getMessage();
            }
        }
        try {
            meko.useDatapack(name, create, id, folderId);
        } catch (IllegalStateException e) {
            if (folderId == null) throw e;
            // The folder was refused (deleted in the Meko app, say): nothing was created, so create it unfiled.
            meko.forgetProject(PROJECT);
            meko.useDatapack(name, create, id, null);
            folderId = null;
            fileError = e.getMessage();
        }
        // A datapack created a moment ago holds nothing: its documents and history are known, not searched for.
        Map<String, Object> r = new LinkedHashMap<>(create ? status(nothing(workshop.documents()), nothing(workshop.historyUseCases())) : status());
        if (folderId != null) r.put("filed", PROJECT + " / " + folder.get(0));
        else if (folder != null) r.put("fileError", "Created, but not filed in the " + PROJECT + " project: " + fileError);
        return r;
    }

    /**
     * The id of an existing datapack chosen by name: checked against the account's listing, the one the page showed if
     * it is recent (a choice made from a list just shown needs no second listing), else a new one.
     */
    private String existingId(String name, String id) throws Exception {
        for (long maxAge : new long[] {LISTING_REUSE_MILLIS, 0}) {
            for (Map<String, String> d : meko.listDatapacks(maxAge)) {
                // A known id must be the named datapack's, as this key sees them: never one name shown and another used.
                if (name.equals(d.get("name")) && (id == null || id.isBlank() || id.equals(d.get("id")))) return d.get("id");
            }
        }
        if (id != null && !id.isBlank()) throw new IllegalArgumentException("No datapack " + name + " with that id in this account: choose it again.");
        return null;   // not listed: resolved by name, which says what is visible
    }

    /** How long a listing the page just showed stands for checking a choice from it. */
    private static final long LISTING_REUSE_MILLIS = 60_000;

    private static Map<String, String> nothing(List<String> keys) {
        Map<String, String> m = new LinkedHashMap<>();
        keys.forEach(k -> m.put(k, "missing"));
        return m;
    }

    /** The workshop project's lookup, started once, ahead of the first datapack the participant creates. */
    private final java.util.concurrent.atomic.AtomicBoolean projectWarmed = new java.util.concurrent.atomic.AtomicBoolean();

    private void warmProject() {
        if (!meko.keyed() || !projectWarmed.compareAndSet(false, true)) return;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                meko.findProject(PROJECT);
            } catch (Exception e) {
                projectWarmed.set(false);   // tried again on the next look; the create resolves it anyway
            }
        }, CHECKS);
    }

    /** The questions a run's agents asked, as the page sends them for a self-learning check. */
    public record Questions(List<String> questions) {}

    /**
     * Self-learning: asks the run's questions again through context_search (all sources), and returns the band Meko gave
     * each with the datapack's quality and to-dos. Only on a live datapack: the stand-in has no search log.
     */
    @PostMapping("/selflearning/check")
    public Map<String, Object> selfLearningCheck(@org.springframework.web.bind.annotation.RequestBody Questions body) throws Exception {
        Map<String, Object> off = selfLearningOff();
        if (off != null) return off;
        if (body == null || body.questions() == null || body.questions().isEmpty()) throw new IllegalArgumentException("No questions to check: run the use case first.");
        Map<String, Object> r = new LinkedHashMap<>(Map.of("available", true));
        r.putAll(selfLearning.check(body.questions()));
        return r;
    }

    /** Self-learning: replays the weak searches among {@code questions} to see whether what was added since fixed them. */
    @PostMapping("/selflearning/verify")
    public Map<String, Object> selfLearningVerify(@org.springframework.web.bind.annotation.RequestBody Questions body) throws Exception {
        Map<String, Object> off = selfLearningOff();
        if (off != null) return off;
        Map<String, Object> r = new LinkedHashMap<>(Map.of("available", true, "replays", selfLearning.verify(body == null ? null : body.questions())));
        r.putAll(selfLearning.status());
        return r;
    }

    /** Why self-learning cannot run here (no live, connected datapack), or null when it can. */
    private Map<String, Object> selfLearningOff() {
        boolean live = stores.forLane(ContextStores.Lane.MEKO).kind() == ContextStore.Kind.MEKO_LIVE;
        if (live && Boolean.TRUE.equals(meko.status().get("connected"))) return null;
        return Map.of("available", false, "reason", "Self-learning reads the search log of a live Meko datapack: connect one at step 1 (the offline stand-in has none).");
    }

    /**
     * Deletes a workshop datapack the participant is done with, and everything in it (memory, knowledge, and the
     * conversations its agents opened, which count against the account's limit). Only a workshop datapack this key owns:
     * never the progress datapack, and never one the workshop did not create (delete those in the Meko app).
     */
    @PostMapping("/datapack/delete")
    public Map<String, Object> deleteDatapack(@org.springframework.web.bind.annotation.RequestParam String id) throws Exception {
        try (var pass = gate.enter("deleting a datapack")) {
            Map<String, String> row = meko.listDatapacks().stream().filter(d -> id.equals(d.get("id"))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("This account has no datapack with that id."));
            String name = row.get("name");
            if (!"owner".equals(row.getOrDefault("grant", "owner"))) {
                throw new IllegalArgumentException("Only a datapack you own can be deleted: " + name + " is shared with you.");
            }
            if (WorkshopProgress.isProgress(name) || !name.startsWith("meko-workshop")) {
                throw new IllegalArgumentException(name + " was not created by the workshop: delete it in the Meko app if you mean to.");
            }
            meko.deleteDatapack(id);
            progress.forget(id);
            Map<String, Object> r = new LinkedHashMap<>(status());
            r.put("deleted", name);
            return r;
        }
    }

    /**
     * The workshop's progress for a workshop datapack, read back from its record in the participant's progress
     * datapack; {@code available: false} without a Meko key (the page then keeps progress in the browser). With it, the
     * run the page can follow again or still has to record, if any.
     */
    @GetMapping("/progress")
    public Map<String, Object> progress(@org.springframework.web.bind.annotation.RequestParam String context) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>(progress.available() ? progress.load(context) : Map.of("available", false));
        RunJobs.Job pending = jobs.pending();
        if (pending != null) out.put("job", pending.describe());
        return out;
    }

    /** One run (or audit) of a use case's stage, as the page kept it: recorded in the progress datapack. */
    public record ProgressRun(String context, String contextName, String useCase, int stage, String kind, Object run, String job) {}

    @PostMapping("/progress")
    public Map<String, Object> record(@org.springframework.web.bind.annotation.RequestBody ProgressRun r) throws Exception {
        if (!progress.available()) return Map.of("recorded", false);
        if (r.useCase() == null || r.context() == null || r.run() == null) throw new IllegalArgumentException("A run needs its datapack, use case and stage.");
        progress.save(r.context(), r.contextName(), r.useCase(), r.stage(), r.kind() == null ? "run" : r.kind(), r.run());
        if (r.job() != null) jobs.recorded(r.job());
        return Map.of("recorded", true);
    }

    /** A request this application cannot act on (a bad name, an unknown datapack): 400. */
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /** Meko refused or failed (an error from its tools, an HTTP 5xx): 502, the error as Meko gave it. */
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalStateException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> mekoFailed(RuntimeException e) {
        return org.springframework.http.ResponseEntity.status(502).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /**
     * The newest lesson promoted in the datapack in use (the pair exercise: what the partner's case 1 promoted), with its
     * learning epoch and who promoted it; empty when there is none.
     */
    @GetMapping("/lesson")
    public Map<String, Object> lesson() {
        return stories.latestLesson();
    }

    /**
     * Uploads a use case's documents to the datapack through Meko's MCP (knowledgebase_upload_document); Meko indexes
     * them on its own, and the status reports each as indexing until it is searchable.
     */
    @PostMapping("/upload")
    public Map<String, Object> upload(@org.springframework.web.bind.annotation.RequestParam(required = false) String useCase) {
        try (var pass = gate.enter("the documents' upload")) {
            return uploadGuarded(useCase);
        }
    }

    private Map<String, Object> uploadGuarded(String useCase) {
        Map<String, Object> r = new LinkedHashMap<>(Map.of("uploaded",
                seeder.uploadDocuments(useCase == null ? workshop.documents() : workshop.useCase(useCase).documents())));
        r.putAll(status());
        return r;
    }

    /** The fallback when uploading through Meko's MCP fails: a one-line version of each document, promoted (see {@link StorySeeder#addDocuments}). */
    @PostMapping("/documents")
    public Map<String, Object> addDocuments(@org.springframework.web.bind.annotation.RequestParam(required = false) String useCase) {
        try (var pass = gate.enter("adding the one-line documents")) {
            return addDocumentsGuarded(useCase);
        }
    }

    private Map<String, Object> addDocumentsGuarded(String useCase) {
        // Only the use case's own documents: another use case's earlier stages stay as they are.
        Map<String, String> added = seeder.addDocuments(useCase == null ? workshop.documents() : workshop.useCase(useCase).documents());
        Map<String, Object> r = new LinkedHashMap<>(Map.of("added", added));
        // Nothing written (everything was already there): the state just checked stands; otherwise it is asked again.
        r.putAll(status(useCase == null ? alreadyThere(added, "already in the datapack (") : null, null));
        return r;
    }

    /** Each document's state as a step reported it, when it found every one already there; null if it wrote anything. */
    private static Map<String, String> alreadyThere(Map<String, String> outcome, String prefix) {
        if (outcome.isEmpty() || !outcome.values().stream().allMatch(v -> v.startsWith(prefix))) return null;
        Map<String, String> known = new LinkedHashMap<>();
        outcome.forEach((k, v) -> known.put(k, v.substring(prefix.length(), v.length() - 1)));
        return known;
    }

    /** Stage 2: the organizational history the use cases read. */
    @PostMapping("/history")
    public Map<String, Object> seedHistory(@org.springframework.web.bind.annotation.RequestParam(required = false) String useCase) {
        try (var pass = gate.enter("adding last quarter's reviews")) {
            return seedHistoryGuarded(useCase);
        }
    }

    private Map<String, Object> seedHistoryGuarded(String useCase) {
        Map<String, String> seeded = seeder.seedHistory(useCase == null ? workshop.historyUseCases() : List.of(workshop.useCase(useCase).id()));
        Map<String, Object> r = new LinkedHashMap<>(Map.of("seeded", seeded));
        // Every review written (or already there): seeded, as the writes report it. Searching again straight away would
        // say "missing": a memory just written takes a moment to become searchable in Meko.
        boolean allIn = !seeded.isEmpty() && seeded.values().stream().allMatch(v -> v.startsWith("already in memory") || v.startsWith("memory written by"));
        Map<String, String> history = null;
        if (allIn) {
            List<String> done = useCase == null ? workshop.historyUseCases() : List.of(useCase);
            // The other use cases' history is asked as usual (none, when only this one reads history).
            Map<String, String> others = seeder.historyStatus(workshop.historyUseCases().stream().filter(id -> !done.contains(id)).toList());
            history = new LinkedHashMap<>();
            for (String id : workshop.historyUseCases()) history.put(id, done.contains(id) ? "seeded" : others.get(id));
        }
        r.putAll(status(null, history));
        return r;
    }
}
