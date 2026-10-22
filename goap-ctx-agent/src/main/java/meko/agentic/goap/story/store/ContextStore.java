package meko.agentic.goap.story.store;

import java.util.List;
import java.util.Optional;

/**
 * The durable context an agent can reach beyond its own process. The story agents are written once
 * against this interface; only the implementation changes between "without Meko" and "with Meko",
 * so every difference in the plans comes from context, not from different code.
 * <p>
 * {@code caseId} is passed everywhere so each call can be attributed to a run in the receipts.
 */
public interface ContextStore {

    enum Kind {
        /** No durable context: an agent knows only what it was handed. */
        NONE,
        /** The live Meko datapack. */
        MEKO_LIVE,
        /** In-memory stand-in with Meko's semantics, for running without network. */
        LOCAL_STAND_IN,
        /**
         * The application's own database: only what this application's agents wrote, looked up by case id or keyword.
         * What other tools and systems wrote lives in their systems, not here.
         */
        SHARED_DB,
        /**
         * Another system's own store (a vendor product, another team's application): what its agents wrote, invisible
         * to this application.
         */
        OTHER_SYSTEM_DB
    }

    Kind kind();

    /** Whether a conversation turn becomes readable only some time after it is written (Meko queues them). */
    default boolean writesAreQueued() {
        return false;
    }

    /**
     * Appends a turn to a conversation thread (Meko: conversation_add_message). {@code thread} is the conversation's key,
     * such as a transaction id: any agent, in any process, that knows the key reaches the same conversation.
     */
    default void addTurn(String caseId, String agentId, String thread, String input, String output) {
        addTurn(caseId, agentId, thread, input, output, null);
    }

    /** As {@link #addTurn(String, String, String, String, String)}, with the why behind the turn (Meko: the reasoning field). */
    void addTurn(String caseId, String agentId, String thread, String input, String output, String reasoning);

    /**
     * Appends an agent's own record (something it did or decided, not dialogue) to a conversation thread. Read back,
     * it is a turn like any other, with {@code request} as its input. A store that derives memories from a turn's input
     * side (Meko does) stores the short {@code label} there instead and keeps the request with the turn, so an agent's
     * log line does not become a memory of its own.
     */
    default void addRecord(String caseId, String agentId, String thread, String label, String request, String output, String reasoning) {
        addTurn(caseId, agentId, thread, request, output, reasoning);
    }

    /** Every turn of a conversation thread, in order, with who wrote each (Meko: conversation_get). */
    List<Turn> transcript(String caseId, String agentId, String thread);

    /** Memories written during this case by any agent (Meko: memory_search scoped by run_id). */
    List<StoreRecord> recallCase(String caseId, String agentId, String query);

    /**
     * A document upload to shared knowledge: the document's key, whether identical content was already there, or why it
     * failed (then {@code key} is null).
     */
    record Uploaded(String key, boolean alreadyThere, String error) {}

    /**
     * Uploads a document to shared knowledge (Meko: knowledgebase_upload_document, then the file to the storage URL it
     * issues). Meko indexes it on its own; {@link #documentState} follows that. Empty where the store has no upload.
     */
    default Optional<Uploaded> uploadDocument(String caseId, String agentId, String filename, byte[] bytes) {
        return Optional.empty();
    }

    /** An uploaded document's state (Meko: knowledgebase_document_status), as the store reports it; empty if unknown. */
    default Optional<String> documentState(String caseId, String agentId, String key) {
        return Optional.empty();
    }

    /** Memories across all cases and agents (Meko: memory_search). */
    List<StoreRecord> searchMemory(String caseId, String agentId, String query);

    /**
     * As {@link #searchMemory(String, String, String)}, asking for up to {@code limit} results: for a lookup that knows
     * how many memories its answer can be among (a customer's few earlier reviews).
     */
    default List<StoreRecord> searchMemory(String caseId, String agentId, String query, int limit) {
        return searchMemory(caseId, agentId, query);
    }

    /** Shared, curated knowledge (Meko: knowledgebase_search). */
    List<StoreRecord> searchKnowledge(String caseId, String agentId, String query);

    /** Writes a memory, attributed to {@code agentId}; returns the new memory id if one was created. */
    Optional<String> remember(String caseId, String agentId, String text);

    /**
     * Overwrites the text of a memory {@code agentId} wrote earlier (Meko: memory_update), for a result that supersedes
     * it. Only a memory's own writer may update it. True if it was updated.
     */
    boolean update(String caseId, String agentId, String memoryId, String text);

    /** Deletes one memory, written by {@code writtenBy} (Meko: memory_delete_by_id); true if it was removed. */
    boolean forget(String caseId, String writtenBy, String memoryId);

    /**
     * Writes a memory that nothing reads in this run (a result written back for later cases). Where writes are slow
     * (Meko's fact extraction), it happens in the background; {@link #flush} waits for it. By default, at once.
     */
    default void rememberLater(String caseId, String agentId, String text) {
        remember(caseId, agentId, text);
    }

    /** Waits for every background write of {@code caseId}, so the run's receipts are complete. By default, nothing to wait for. */
    default void flush(String caseId) {
    }

    /** Moves memories into the shared knowledge base (Meko: memory_promote). */
    boolean promote(String caseId, String agentId, List<String> memoryIds);

    /** Stores a JSON artifact and returns its content hash (Meko: artifact_put). */
    Optional<String> putArtifact(String caseId, String agentId, String filename, Object content);

    /** Reads a JSON artifact back by content hash (Meko: artifact_get). */
    Optional<String> getArtifact(String caseId, String agentId, String contentHash);

    /**
     * One turn of a conversation: a natural input and output, as Meko shows them. An agent's own record of something it
     * did or decided is tagged in the reasoning, {@code "<agent id> · <event>"} optionally followed by {@code ": <why>"},
     * so a reader can recognise records without a model (see {@link #tag}).
     */
    record Turn(String input, String output, String writtenBy, String reasoning) {
        public Turn(String input, String output, String writtenBy) {
            this(input, output, writtenBy, null);
        }

        /** The tag that marks {@code agentId}'s record of {@code event}. */
        public static String tag(String agentId, String event) {
            return agentId + EVENT_SEPARATOR + event;
        }

        /** The tag of a decision trace for {@code gate}. */
        public static String decisionTag(String agentId, String gate) {
            return tag(agentId, DECISION + " " + gate);
        }

        /** Whether this turn is {@code agentId}'s record of {@code event}. */
        public boolean isEvent(String agentId, String event) {
            String tag = tag(agentId, event);
            return reasoning != null && (reasoning.equals(tag) || reasoning.startsWith(tag + ": "));
        }

        /** The gate this turn traces a decision of, if it is a decision trace (by any agent). */
        public java.util.Optional<String> decisionGate() {
            if (reasoning == null) return java.util.Optional.empty();
            java.util.regex.Matcher m = DECISION_TAG.matcher(reasoning);
            return m.find() ? java.util.Optional.of(m.group(1)) : java.util.Optional.empty();
        }

        /** The event this turn records, if it is an agent's tagged record. */
        public java.util.Optional<String> event() {
            if (reasoning == null) return java.util.Optional.empty();
            java.util.regex.Matcher m = TAG.matcher(reasoning);
            return m.find() ? java.util.Optional.of(m.group(2)) : java.util.Optional.empty();
        }

        /** The agent id that leads a tagged record, if any. */
        public java.util.Optional<String> taggedBy() {
            if (reasoning == null) return java.util.Optional.empty();
            java.util.regex.Matcher m = TAG.matcher(reasoning);
            return m.find() ? java.util.Optional.of(m.group(1)) : java.util.Optional.empty();
        }

        /** The why after the tag ({@code "<tag>: <why>"}), or empty. */
        public String why() {
            if (reasoning == null) return "";
            int colon = reasoning.indexOf(": ", reasoning.indexOf(EVENT_SEPARATOR) + 1);
            return colon < 0 ? "" : reasoning.substring(colon + 2);
        }
    }

    String EVENT_SEPARATOR = " · ";

    /** The event name of a decision trace: tagged {@code "<agent id> · decision <gate>"}. */
    String DECISION = "decision";

    /** {@code "<agent id> · <event>"}, where an agent id has a colon ({@code support:payments-specialist}). */
    java.util.regex.Pattern TAG = java.util.regex.Pattern.compile("^(\\S+:\\S+)" + java.util.regex.Pattern.quote(" · ") + "([^:]+?)(?:: |$)");
    java.util.regex.Pattern DECISION_TAG = java.util.regex.Pattern.compile("^\\S+:\\S+" + java.util.regex.Pattern.quote(" · ") + DECISION + " ([^:\\s]+)");

}
