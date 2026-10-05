package meko.agentic.goap.api;

import meko.agentic.goap.story.StoryCatalog;
import meko.agentic.goap.story.StoryRun;
import meko.agentic.goap.story.StoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import meko.agentic.goap.story.store.ContextStores;

@RestController
@RequestMapping("/api/stories")
public class StoryController {
    private final StoryCatalog catalog;
    private final StoryService stories;

    public StoryController(StoryCatalog catalog, StoryService stories) {
        this.catalog = catalog;
        this.stories = stories;
    }

    @GetMapping
    public List<StoryCatalog.Story> list() {
        return catalog.all();
    }

    @PostMapping("/{id}/run")
    public StoryRun run(@PathVariable String id, @RequestBody(required = false) RunRequest request) {
        return stories.run(id, meko(request));
    }

    /** Same story, same agents, same planner, three context stores: none, the application's own database, Meko. */
    @PostMapping("/{id}/compare")
    public Map<String, StoryRun> compare(@PathVariable String id, @RequestParam(defaultValue = "false") boolean db) {
        Map<String, StoryRun> runs = new java.util.LinkedHashMap<>();
        runs.put("withoutMeko", stories.run(id, false));
        if (db) runs.put("database", stories.run(id, ContextStores.Lane.DATABASE));
        runs.put("withMeko", stories.run(id, true));
        return runs;
    }

    @PostMapping("/agent-died-2am/start")
    public StoryRun startIncident(@RequestBody(required = false) RunRequest request) {
        return stories.startIncident(meko(request));
    }

    @PostMapping("/agent-died-2am/takeover")
    public StoryRun takeOver(@RequestBody TakeoverRequest request) {
        return stories.takeOverIncident(request.caseId(), Boolean.TRUE.equals(request.meko()));
    }

    /** A story's reset: a new learning epoch, a new review cycle. */
    @PostMapping("/{id}/reset")
    public Map<String, Object> reset(@PathVariable String id) {
        return stories.reset(id);
    }

    /** Trust scenario: a case the learned pattern does not fit. */
    @PostMapping("/organization-learned/mismatch")
    public StoryRun mismatch(@RequestBody(required = false) RunRequest request) {
        return stories.patternMismatch(request == null || !Boolean.FALSE.equals(request.meko()));
    }

    @PostMapping("/organization-learned/series")
    public Map<String, List<StoryRun>> series(@RequestParam(defaultValue = "3") int cases,
                                              @RequestParam(defaultValue = "false") boolean db) {
        return stories.learningSeries(Math.max(1, Math.min(cases, 4)), db);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private static boolean meko(RunRequest request) {
        return request != null && Boolean.TRUE.equals(request.meko());
    }

    public record RunRequest(Boolean meko) {}

    public record TakeoverRequest(String caseId, Boolean meko) {}
}
