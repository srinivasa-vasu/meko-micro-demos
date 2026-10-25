package meko.agentic.goap.api;

import meko.agentic.goap.sessions.HarnessSessions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** "New session. Same context.": a minimal harness's sessions, with and without Meko (see {@link HarnessSessions}). */
@RestController
@RequestMapping("/api/sessions")
public class SessionsController {
    private final HarnessSessions sessions;

    public SessionsController(HarnessSessions sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestParam String harness) {
        return sessions.start(harness);
    }

    @PostMapping("/{id}/say")
    public Map<String, Object> say(@PathVariable String id, @RequestBody Map<String, String> body) {
        String text = body.getOrDefault("text", "").strip();
        if (text.isEmpty()) throw new IllegalArgumentException("Type something to say.");
        return sessions.say(id, text);
    }

    @PostMapping("/{id}/end")
    public Map<String, Object> end(@PathVariable String id) {
        return sessions.end(id);
    }

    @GetMapping("/{id}/facts")
    public Map<String, Object> facts(@PathVariable String id) {
        return sessions.facts(id);
    }

    /** A new session given a task: what it knows before anything is typed, and what it would have to be told again. */
    @PostMapping("/begin")
    public Map<String, Object> begin(@RequestParam String harness, @RequestParam boolean meko,
                                     @RequestParam(required = false) String after, @RequestBody Map<String, String> body) {
        return sessions.begin(harness, body.getOrDefault("task", "").strip(), meko, after);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
