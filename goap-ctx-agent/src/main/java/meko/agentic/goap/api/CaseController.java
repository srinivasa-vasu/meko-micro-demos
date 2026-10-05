package meko.agentic.goap.api;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import meko.agentic.goap.agent.CaseRunRepository;
import meko.agentic.goap.context.ContextEngines;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CaseController {
    private final AgentPlatform agentPlatform;
    private final CaseRunRepository runs;
    private final ContextEngines engines;
    private final String applicationName;

    public CaseController(AgentPlatform agentPlatform, CaseRunRepository runs, ContextEngines engines,
                          @org.springframework.beans.factory.annotation.Value("${spring.application.name}") String applicationName) {
        this.applicationName = applicationName;
        this.agentPlatform = agentPlatform;
        this.runs = runs;
        this.engines = engines;
    }

    @PostMapping("/cases")
    public CaseModels.CaseResolution create(@RequestBody(required = false) CaseRequest request) {
        CaseRequest effective = request == null ? new CaseRequest(false, true) : request;
        return AgentInvocation.create(agentPlatform, CaseModels.CaseResolution.class).invoke(effective);
    }

    @PostMapping("/cases/compare")
    public Object compare() {
        CaseModels.CaseResolution withoutMeko = AgentInvocation.create(agentPlatform, CaseModels.CaseResolution.class)
                .invoke(new CaseRequest(false, true));
        CaseModels.CaseResolution withMeko = AgentInvocation.create(agentPlatform, CaseModels.CaseResolution.class)
                .invoke(new CaseRequest(true, true));
        return java.util.Map.of("withoutMeko", withoutMeko, "withMeko", withMeko);
    }

    @GetMapping("/cases/{caseId}")
    public ResponseEntity<?> get(@PathVariable String caseId) {
        var run = runs.get(caseId);
        return run == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(run.timeline());
    }

    @GetMapping("/health")
    public Object health() {
        return java.util.Map.of(
                "application", applicationName,
                "mekoConfigured", engines.mekoAvailable(),
                "meko", "/api/meko/status");
    }
}
