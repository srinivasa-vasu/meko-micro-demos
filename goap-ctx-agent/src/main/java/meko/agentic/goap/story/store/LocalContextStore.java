package meko.agentic.goap.story.store;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * An in-memory context store, used twice:
 * <ul>
 *   <li>{@link Kind#LOCAL_STAND_IN}: a stand-in for Meko when it is not configured, seeded with the same organizational
 *       context (cross-agent memory, shared knowledge). The UI labels runs on it as a stand-in.</li>
 *   <li>{@link Kind#SHARED_DB}: the application's own database. Same lookups and keyword search, but it holds only what
 *       this application's agents wrote; nothing written by other tools or systems is in it.</li>
 * </ul>
 * Search is keyword overlap rather than hybrid semantic search, and nothing survives a restart.
 */
public class LocalContextStore implements ContextStore {
    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "that", "this", "from", "what", "have", "after");

    private record Row(String id, String caseId, String text, String writtenBy, Instant createdAt, String documentId) {
        Row(String id, String caseId, String text, String writtenBy, Instant createdAt) {
            this(id, caseId, text, writtenBy, createdAt, id);
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReceiptLog receipts;
    private final OutageSwitch outage;
    private final Kind kind;
    private final List<Row> memories = new CopyOnWriteArrayList<>();
    private final List<Row> knowledge = new CopyOnWriteArrayList<>();
    private final Map<String, List<Turn>> conversations = new ConcurrentHashMap<>();
    private final Map<String, String> artifacts = new ConcurrentHashMap<>();

    public LocalContextStore(ReceiptLog receipts, OutageSwitch outage, Kind kind) {
        this.receipts = receipts;
        this.outage = outage;
        this.kind = kind;
    }

    /** During a simulated outage every call fails: records the failed receipt and tells the caller to return nothing. */
    private boolean down(String caseId, String agentId, String tool, String detail) {
        // The outage scenario takes Meko down, not the application's own database.
        if (!outage.on() || kind == Kind.SHARED_DB || kind == Kind.OTHER_SYSTEM_DB) return false;
        receipts.record(caseId, new ReceiptLog.Receipt(agentId, tool, abbreviate(detail) + " — store unreachable (simulated outage)", 0, 0, false, kind().name()));
        return true;
    }

    private static String abbreviate(String detail) {
        return detail.length() <= 110 ? detail : detail.substring(0, 110) + "…";
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public void addTurn(String caseId, String agentId, String thread, String input, String output, String reasoning) {
        if (down(caseId, agentId, "conversation_add_message", "[" + thread + "] " + (reasoning != null ? reasoning : input))) return;
        conversations.computeIfAbsent(thread, k -> new CopyOnWriteArrayList<>()).add(new Turn(input, output, agentId, reasoning));
        log(caseId, agentId, "conversation_add_message", "[" + thread + "] " + (reasoning != null ? reasoning : input), 1);
    }

    @Override
    public List<Turn> transcript(String caseId, String agentId, String thread) {
        if (down(caseId, agentId, "conversation_get", "conversation " + thread)) return List.of();
        List<Turn> turns = List.copyOf(conversations.getOrDefault(thread, List.of()));
        log(caseId, agentId, "conversation_get", "conversation " + thread, turns.size());
        return turns;
    }

    @Override
    public List<StoreRecord> recallCase(String caseId, String agentId, String query) {
        if (down(caseId, agentId, "memory_search", "[this case] " + query)) return List.of();
        List<StoreRecord> rows = rank(memories.stream().filter(r -> r.caseId().equals(caseId)).toList(), query);
        log(caseId, agentId, kind == Kind.SHARED_DB || kind == Kind.OTHER_SYSTEM_DB ? "SELECT notes WHERE case_id" : "memory_search", "[this case] " + query, rows.size());
        return rows;
    }

    @Override
    public List<StoreRecord> searchMemory(String caseId, String agentId, String query) {
        if (down(caseId, agentId, "memory_search", query)) return List.of();
        List<StoreRecord> rows = rank(memories, query);
        log(caseId, agentId, kind == Kind.SHARED_DB || kind == Kind.OTHER_SYSTEM_DB ? "keyword search: own notes" : "memory_search", query, rows.size());
        return rows;
    }

    @Override
    public List<StoreRecord> searchKnowledge(String caseId, String agentId, String query) {
        if (down(caseId, agentId, "knowledgebase_search", query)) return List.of();
        List<StoreRecord> rows = rank(knowledge, query);
        log(caseId, agentId, "knowledgebase_search", query, rows.size());
        return StoreRecord.onePerDocument(rows);
    }

    @Override
    public Optional<String> remember(String caseId, String agentId, String text) {
        if (down(caseId, agentId, "memory_add", text)) return Optional.empty();
        if (outage.memoryWritesFail() && kind == Kind.LOCAL_STAND_IN) {
            receipts.record(caseId, new ReceiptLog.Receipt(agentId, "memory_add", abbreviate(text) + " — memory_add failed: simulated failure", 0, 0, false, kind().name()));
            return Optional.empty();
        }
        String id = UUID.randomUUID().toString();
        memories.add(new Row(id, caseId, text, agentId, Instant.now()));
        log(caseId, agentId, "memory_add", text, 1);
        return Optional.of(id);
    }

    @Override
    public boolean update(String caseId, String agentId, String memoryId, String text) {
        if (down(caseId, agentId, "memory_update", text)) return false;
        for (int i = 0; i < memories.size(); i++) {
            Row r = memories.get(i);
            if (r.id().equals(memoryId) && r.writtenBy().equals(agentId)) {
                memories.set(i, new Row(r.id(), caseId, text, agentId, Instant.now()));
                log(caseId, agentId, "memory_update", text, 1);
                return true;
            }
        }
        log(caseId, agentId, "memory_update", text, 0);
        return false;
    }

    @Override
    public boolean forget(String caseId, String writtenBy, String memoryId) {
        if (down(caseId, writtenBy, "memory_delete_by_id", memoryId)) return false;
        boolean removed = memories.removeIf(r -> r.id().equals(memoryId));
        log(caseId, writtenBy, "memory_delete_by_id", memoryId, removed ? 1 : 0);
        return removed;
    }

    @Override
    public boolean promote(String caseId, String agentId, List<String> memoryIds) {
        if (down(caseId, agentId, "memory_promote", memoryIds.size() + " memory row(s)")) return false;
        List<Row> moved = memories.stream().filter(r -> memoryIds.contains(r.id())).toList();
        memories.removeAll(moved);
        knowledge.addAll(moved);
        log(caseId, agentId, "memory_promote", moved.size() + " memory row(s) to the shared knowledge base", moved.size());
        return !moved.isEmpty();
    }

    @Override
    public Optional<String> putArtifact(String caseId, String agentId, String filename, Object content) {
        if (down(caseId, agentId, "artifact_put", filename)) return Optional.empty();
        String json = objectMapper.writeValueAsString(content);
        String hash = sha256(json);
        artifacts.put(hash, json);
        log(caseId, agentId, "artifact_put", filename, 1);
        return Optional.of(hash);
    }

    @Override
    public Optional<String> getArtifact(String caseId, String agentId, String contentHash) {
        if (down(caseId, agentId, "artifact_get", contentHash)) return Optional.empty();
        Optional<String> json = Optional.ofNullable(artifacts.get(contentHash));
        log(caseId, agentId, "artifact_get", contentHash.substring(0, Math.min(12, contentHash.length())) + "…", json.isPresent() ? 1 : 0);
        return json;
    }

    /** Seeds knowledge directly, as {@code POST /api/meko/seed} does for the live datapack. */
    public void seedKnowledge(String agentId, String text) {
        if (knowledge.stream().noneMatch(r -> r.text().equals(text))) {
            knowledge.add(new Row(UUID.randomUUID().toString(), "seed", text, agentId, Instant.now()));
        }
    }

    /** Seeds one chunk of an uploaded document, as uploading it through the Meko app does for the live datapack. */
    public void seedDocumentChunk(String filename, String chunk) {
        if (knowledge.stream().noneMatch(r -> r.text().equals(chunk))) {
            knowledge.add(new Row(UUID.randomUUID().toString(), "seed", chunk, StoreRecord.UPLOADED_DOCUMENT + ": " + filename, Instant.now(), filename));
        }
    }

    /** Seeds a memory directly, attributed to another agent. */
    public void seedMemory(String agentId, String text) {
        if (memories.stream().noneMatch(r -> r.text().equals(text))) {
            memories.add(new Row(UUID.randomUUID().toString(), "seed", text, agentId, Instant.now()));
        }
    }

    private List<StoreRecord> rank(List<Row> rows, String query) {
        Set<String> terms = tokens(query);
        int needed = terms.size() <= 2 ? 1 : 2;
        return rows.stream()
                .map(r -> Map.entry(r, tokens(r.text()).stream().filter(terms::contains).count()))
                .filter(e -> e.getValue() >= needed)
                .sorted(Comparator.comparing((Map.Entry<Row, Long> e) -> e.getValue()).reversed())
                .limit(8)
                .map(e -> new StoreRecord(e.getKey().id(), e.getKey().text(), e.getKey().writtenBy(),
                        e.getKey().createdAt().toString(), e.getKey().documentId()))
                .toList();
    }

    /** In the database lane, receipts name the database operation rather than a Meko tool. */
    private String label(String tool) {
        if (kind != Kind.SHARED_DB && kind != Kind.OTHER_SYSTEM_DB) return tool;
        return switch (tool) {
            case "conversation_add_message" -> "INSERT conversation turn";
            case "conversation_get" -> "SELECT turns WHERE case_id";
            case "memory_add" -> "INSERT note";
            case "memory_promote" -> "UPDATE note → knowledge table";
            case "knowledgebase_search" -> "keyword search: own knowledge table";
            case "artifact_put" -> "INSERT artifact";
            case "artifact_get" -> "SELECT artifact WHERE hash";
            default -> tool;
        };
    }

    private static Set<String> tokens(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9-]+"))
                .filter(t -> t.length() > 2 && !STOP.contains(t))
                .collect(Collectors.toSet());
    }

    private void log(String caseId, String agentId, String tool, String detail, int hits) {
        String shortDetail = detail.length() <= 110 ? detail : detail.substring(0, 110) + "…";
        receipts.record(caseId, new ReceiptLog.Receipt(agentId, label(tool), shortDetail, hits, 0, true, kind().name()));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
