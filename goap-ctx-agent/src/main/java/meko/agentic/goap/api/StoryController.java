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
    /** One piece of work that changes the datapack at a time: every run below enters it. */
    private final WorkGate gate;
    private final RunJobs jobs;

    public StoryController(StoryCatalog catalog, StoryService stories, WorkGate gate, RunJobs jobs) {
        this.catalog = catalog;
        this.stories = stories;
        this.gate = gate;
        this.jobs = jobs;
    }

    @GetMapping
    public List<StoryCatalog.Story> list() {
        return catalog.all();
    }

    @PostMapping("/{id}/run")
    public StoryRun run(@PathVariable String id, @RequestBody(required = false) RunRequest request) {
        try (var pass = gate.enter("a run of " + id)) {
            return stories.run(id, meko(request));
        }
    }

    /** Same story, same agents, same planner, three context stores: none, the application's own database, Meko. */
    @PostMapping("/{id}/compare")
    public Map<String, StoryRun> compare(@PathVariable String id, @RequestParam(defaultValue = "false") boolean db) {
        try (var pass = gate.enter("a comparison of " + id)) {
            Map<String, StoryRun> runs = new java.util.LinkedHashMap<>();
            runs.put("withoutMeko", stories.run(id, false));
            if (db) runs.put("database", stories.run(id, ContextStores.Lane.DATABASE));
            runs.put("withMeko", stories.run(id, true));
            return runs;
        }
    }

    @PostMapping("/agent-died-2am/start")
    public StoryRun startIncident(@RequestBody(required = false) RunRequest request) {
        try (var pass = gate.enter("the incident's start")) {
            return stories.startIncident(meko(request));
        }
    }

    @PostMapping("/agent-died-2am/takeover")
    public StoryRun takeOver(@RequestBody TakeoverRequest request) {
        try (var pass = gate.enter("the incident's takeover")) {
            return stories.takeOverIncident(request.caseId(), Boolean.TRUE.equals(request.meko()));
        }
    }

    /** A story's reset: a new learning epoch, a new review cycle. */
    @PostMapping("/{id}/reset")
    public Map<String, Object> reset(@PathVariable String id) {
        try (var pass = gate.enter("a reset of " + id)) {
            return stories.reset(id);
        }
    }

    /** Trust scenario: a case the learned pattern does not fit. */
    @PostMapping("/organization-learned/mismatch")
    public StoryRun mismatch(@RequestBody(required = false) RunRequest request) {
        try (var pass = gate.enter("the pattern-mismatch run")) {
            return stories.patternMismatch(request == null || !Boolean.FALSE.equals(request.meko()));
        }
    }

    @PostMapping("/organization-learned/series")
    public Map<String, List<StoryRun>> series(@RequestParam(defaultValue = "3") int cases,
                                              @RequestParam(defaultValue = "false") boolean db,
                                              @RequestParam(defaultValue = "false") boolean mekoOnly) {
        try (var pass = gate.enter("the learning series")) {
            return stories.learningSeries(Math.max(1, Math.min(cases, 4)), db && !mekoOnly, !mekoOnly);
        }
    }

    /** One case of the series, with Meko: without an epoch it starts a new one, so nothing learned before is in reach. */
    @PostMapping("/organization-learned/case")
    public Map<String, Object> learningCase(@RequestParam int index,
                                            @RequestParam(required = false) String epoch,
                                            @RequestParam(defaultValue = "false") boolean otherTeam,
                                            @RequestParam(defaultValue = "true") boolean share,
                                            @RequestParam(required = false) String useCase,
                                            @RequestParam(required = false) Integer stage) {
        try (var pass = gate.enter("a case of the learning use case")) {
            // A job too: a page that reloads while the case runs picks its result up from it.
            RunJobs.Job job = jobs.start("organization-learned", useCase, stage, "case");
            jobs.emit(job, "job", job.describe());
            try {
                Map<String, Object> result = stories.learningCase(index, epoch, otherTeam, share);
                jobs.emit(job, "case", result);
                Map<String, Object> out = new java.util.LinkedHashMap<>(result);
                out.put("job", job.id());
                return out;
            } catch (RuntimeException e) {
                jobs.emit(job, "failed", Map.of("lane", "with", "data", String.valueOf(e.getMessage())));
                throw e;
            } finally {
                jobs.finish(job);
            }
        }
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
