package meko.agentic.goap.api;

import meko.agentic.goap.context.MekoMcpClient;
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

    public WorkshopController(Workshop workshop, StorySeeder seeder, StoryCatalog catalog, ContextStores stores, MekoMcpClient meko,
                              @org.springframework.beans.factory.annotation.Value("${goap.llm.enabled:true}") boolean models,
                              KnowledgeDocuments files, meko.agentic.goap.story.StoryService stories) {
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
        var store = java.util.concurrent.CompletableFuture.supplyAsync(this::storeStatus);
        var docs = knownDocuments != null ? java.util.concurrent.CompletableFuture.completedFuture(knownDocuments)
                : java.util.concurrent.CompletableFuture.supplyAsync(() -> seeder.documentStatus(workshop.documents()));
        var hist = knownHistory != null ? java.util.concurrent.CompletableFuture.completedFuture(knownHistory)
                : java.util.concurrent.CompletableFuture.supplyAsync(() -> seeder.historyStatus(workshop.historyUseCases()));
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

    /** The stand-in is emptied on every start, so its runs belong to this start of the application only. */
    private static final String STAND_IN_ID = "stand-in-" + java.util.UUID.randomUUID();

    /** Pre-flight: which store the Meko lane uses, and whether it is reachable. */
    @GetMapping("/preflight")
    public Map<String, Object> storeStatus() {
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
                .sorted(java.util.Comparator.<Map<String, String>, Boolean>comparing(d -> !d.get("name").startsWith("meko-workshop"))
                        .thenComparing(d -> d.get("created"), java.util.Comparator.reverseOrder()))
                .map(d -> Map.<String, Object>of("name", d.get("name"), "created", d.get("created"), "workshop", d.get("name").startsWith("meko-workshop"),
                        // Shared with this key by someone else (the pair exercise): who owns it.
                        "sharedBy", d.getOrDefault("grant", "owner").equals("owner") || d.getOrDefault("grant", "").isBlank() ? "" : d.getOrDefault("owner", "")))
                .toList();
    }

    /** Step 1: create a fresh datapack (or use an existing one by name), and point the Meko lane at it. */
    @PostMapping("/datapack")
    public Map<String, Object> datapack(@org.springframework.web.bind.annotation.RequestParam String name,
                                        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "true") boolean create) throws Exception {
        if (!name.matches("[a-z0-9][a-z0-9-]{2,62}")) {
            throw new IllegalArgumentException("Use 3 to 63 lowercase letters, digits and hyphens, starting with a letter or digit.");
        }
        meko.useDatapack(name, create);
        return status();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public org.springframework.http.ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /**
     * The newest lesson promoted in the datapack in use (the pair exercise: what the partner's case 1 promoted), with its
     * learning epoch and who promoted it; empty when there is none.
     */
    @GetMapping("/lesson")
    public Map<String, Object> lesson() {
        return stories.latestLesson();
    }

    /** Stage 1 when the documents cannot be uploaded in the Meko app (see {@link StorySeeder#addDocuments}). */
    @PostMapping("/documents")
    public Map<String, Object> addDocuments(@org.springframework.web.bind.annotation.RequestParam(required = false) String useCase) {
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
