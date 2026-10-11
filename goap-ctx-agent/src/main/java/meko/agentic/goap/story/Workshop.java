package meko.agentic.goap.story;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The hands-on workshop (profile {@code workshop}): the same use cases, run stage by stage as participants add context
 * to their own datapack. Stage 0, an empty datapack: only what the agents write during a run. Stage 1, the documents
 * uploaded: policies and standards. Stage 2, organizational history seeded: what other teams' agents wrote before.
 * Stage 3, promotion and audit, needs nothing added. What each stage changes is measured from the runs, not stated here.
 */
@Component
public class Workshop {

    /**
     * @param documents the knowledge documents the use case reads, by policy id (each is a file in {@code kb/})
     * @param history   whether it reads organizational history another team's agents wrote before the run
     */
    public record UseCase(String id, List<String> documents, boolean history) {}

    /** What each use case needs from the datapack, in the stage that adds it. */
    static final Map<String, UseCase> NEEDS = Map.of(
            "dont-make-me-explain", new UseCase("dont-make-me-explain", List.of("CARD-DISPUTE-07", "CARD-DISPUTE-03"), false),
            "loan-underwriting", new UseCase("loan-underwriting", List.of("LEND-UW-05", "LEND-UW-09"), false),
            "what-changed", new UseCase("what-changed", List.of("KYC-STD-04"), true),
            "vacation-catch-up", new UseCase("vacation-catch-up", List.of("ADR-194"), true),
            "organization-learned", new UseCase("organization-learned", List.of(), false),
            "agent-died-2am", new UseCase("agent-died-2am", List.of(), false));

    private final boolean enabled;
    private final List<UseCase> useCases;

    public Workshop(@Value("${workshop.enabled:false}") boolean enabled,
                    @Value("${workshop.use-cases:dont-make-me-explain,what-changed,organization-learned}") List<String> ids,
                    StoryCatalog catalog) {
        this.enabled = enabled;
        ids.forEach(catalog::get); // an unknown id fails at startup, not mid-session
        this.useCases = ids.stream().map(String::strip).map(id -> {
            UseCase needs = NEEDS.get(id);
            if (needs == null) throw new IllegalArgumentException("The workshop has no stages for use case " + id);
            return needs;
        }).toList();
    }

    public boolean enabled() {
        return enabled;
    }

    public List<UseCase> useCases() {
        return useCases;
    }

    /** One of the workshop's use cases, by id. */
    public UseCase useCase(String id) {
        return useCases.stream().filter(u -> u.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Not a workshop use case: " + id));
    }

    /** Every document the workshop's use cases read, in order, without repeats. */
    public List<String> documents() {
        return useCases.stream().flatMap(u -> u.documents().stream()).distinct().toList();
    }

    /** The use cases that read organizational history. */
    public List<String> historyUseCases() {
        return useCases.stream().filter(UseCase::history).map(UseCase::id).toList();
    }

    /** The stage a datapack is at, from what it holds. */
    public static Map<String, Object> stage(boolean documents, boolean history) {
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("stage", documents && history ? 2 : documents ? 1 : 0);
        stage.put("note", documents && history ? "Shared knowledge and another desk's earlier reviews are in the datapack."
                : documents ? "Shared knowledge is in the datapack; another desk's earlier reviews are not added yet."
                : history ? "Another desk's earlier reviews are in the datapack; the documents are not uploaded yet."
                : "Empty: only what the agents write during a run.");
        return stage;
    }
}
