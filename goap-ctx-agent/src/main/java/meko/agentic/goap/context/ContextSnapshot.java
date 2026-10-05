package meko.agentic.goap.context;

import java.util.List;

public record ContextSnapshot(
        int completeness,
        List<String> currentFacts,
        List<String> memories,
        List<String> knowledge,
        List<String> artifacts,
        List<String> decisions,
        List<String> missingContext,
        String conversationId) {
}
