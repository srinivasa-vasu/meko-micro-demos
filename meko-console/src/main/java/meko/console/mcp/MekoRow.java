package meko.console.mcp;

/**
 * A row from any Meko collection: memories, promoted memories, knowledge chunks, datapacks.
 * <p>
 * Field names differ across endpoints — memory_search says "memory", memory_get_all says
 * "data", knowledgebase_search says "chunk_text" — so the extractor does not hard-code them.
 * See MekoClient.payloadOf.
 */
public record MekoRow(
        String id,
        String text,
        /* Hybrid/blended score where the endpoint reports one. Negative means unranked. */
        double score,
        /* Pure vector similarity, when reported separately. Differs from score under hybrid. */
        double rawScore,
        String writtenBy,
        String createdAt) {

    public static final double UNRANKED = -1.0;

    public boolean ranked() {
        return score >= 0;
    }

    /**
     * True when a second ranking stage moved the score off raw similarity.
     * <p>
     * Worth surfacing: score == rawScore across every row of a response is the signature of
     * pure vector search, and a query where they diverge is one where hybrid ranking engaged.
     */
    public boolean hybridApplied() {
        return ranked() && rawScore >= 0 && score != rawScore;
    }
}
