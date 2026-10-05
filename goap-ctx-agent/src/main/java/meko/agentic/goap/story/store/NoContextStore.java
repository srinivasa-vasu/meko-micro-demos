package meko.agentic.goap.story.store;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The baseline: no durable context. Writes go nowhere and reads find nothing, so each agent knows only
 * what was passed to it directly (for example a ticket note) and what it works out itself.
 */
@Component
public class NoContextStore implements ContextStore {

    @Override
    public Kind kind() {
        return Kind.NONE;
    }

    @Override
    public void addTurn(String caseId, String agentId, String thread, String input, String output, String reasoning) {
    }

    @Override
    public List<Turn> transcript(String caseId, String agentId, String thread) {
        return List.of();
    }

    @Override
    public List<StoreRecord> recallCase(String caseId, String agentId, String query) {
        return List.of();
    }

    @Override
    public List<StoreRecord> searchMemory(String caseId, String agentId, String query) {
        return List.of();
    }

    @Override
    public List<StoreRecord> searchKnowledge(String caseId, String agentId, String query) {
        return List.of();
    }

    @Override
    public Optional<String> remember(String caseId, String agentId, String text) {
        return Optional.empty();
    }

    @Override
    public boolean update(String caseId, String agentId, String memoryId, String text) {
        return false;
    }

    @Override
    public boolean forget(String caseId, String writtenBy, String memoryId) {
        return false;
    }

    @Override
    public boolean promote(String caseId, String agentId, List<String> memoryIds) {
        return false;
    }

    @Override
    public Optional<String> putArtifact(String caseId, String agentId, String filename, Object content) {
        return Optional.empty();
    }

    @Override
    public Optional<String> getArtifact(String caseId, String agentId, String contentHash) {
        return Optional.empty();
    }
}
