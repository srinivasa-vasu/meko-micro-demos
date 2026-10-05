package meko.agentic.goap.story.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One memory or knowledge-base row. {@code writtenBy} makes cross-agent context visible: the reader is
 * usually not the agent that wrote it.
 */
public record StoreRecord(String id, String text, String writtenBy, String createdAt, String documentId) {
    /** Attribution for chunks of a document uploaded to the knowledge base by a person, not written by an agent. */
    public static final String UPLOADED_DOCUMENT = "uploaded document";

    public boolean uploadedDocument() {
        return writtenBy != null && writtenBy.startsWith(UPLOADED_DOCUMENT);
    }

    /**
     * Knowledge search returns chunks; agents and the UI reason about documents. Merges the chunks of each uploaded
     * document into one record, in rank order, at the position of its best chunk. Other rows pass through unchanged.
     */
    public static List<StoreRecord> onePerDocument(List<StoreRecord> rows) {
        Map<String, List<StoreRecord>> byDocument = new LinkedHashMap<>();
        List<Object> order = new ArrayList<>();
        for (StoreRecord row : rows) {
            if (!row.uploadedDocument() || row.documentId() == null) {
                order.add(row);
                continue;
            }
            List<StoreRecord> chunks = byDocument.computeIfAbsent(row.documentId(), k -> new ArrayList<>());
            if (chunks.isEmpty()) order.add(row.documentId());
            chunks.add(row);
        }
        return order.stream().map(o -> o instanceof StoreRecord r ? r : merge(byDocument.get((String) o))).toList();
    }

    private static StoreRecord merge(List<StoreRecord> chunks) {
        StoreRecord first = chunks.getFirst();
        String text = chunks.stream().map(StoreRecord::text).collect(Collectors.joining("\n\n"));
        return new StoreRecord(first.id(), text, first.writtenBy(), first.createdAt(), first.documentId());
    }
}
