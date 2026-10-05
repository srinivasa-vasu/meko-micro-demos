package meko.agentic.sre.api;

import com.embabel.agent.api.event.ActionExecutionStartEvent;
import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.ProcessOptions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import meko.agentic.sre.domain.IncidentReport;
import meko.agentic.sre.domain.IncidentRequest;
import meko.agentic.sre.domain.IncidentSeverity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    private final IncidentInvoker invoker;

    public IncidentController(IncidentInvoker invoker) {
        this.invoker = invoker;
    }

    @PostMapping("/investigate")
    public IncidentReport investigate(@Valid @RequestBody InvestigationRequest body) {
        return invoker.invoke(new IncidentRequest(
                body.incidentId(), body.service(), body.symptom(), body.useMeko(), body.severityOrDefault()));
    }

    public record InvestigationRequest(
            @NotBlank String incidentId,
            @NotBlank String service,
            @NotBlank String symptom,
            boolean useMeko,
            IncidentSeverity severity
    ) {
        IncidentSeverity severityOrDefault() {
            return severity == null ? IncidentSeverity.SEV2 : severity;
        }
    }

    /** Single place that builds an Embabel invocation, so options are configured once. */
    @Component
    public static class IncidentInvoker {
        private final AgentPlatform agentPlatform;

        public IncidentInvoker(AgentPlatform agentPlatform) {
            this.agentPlatform = agentPlatform;
        }

        public IncidentReport invoke(IncidentRequest request) {
            return invoke(request, List.of());
        }

        /** With per-run listeners, which is how the streaming endpoints watch the planner work. */
        public IncidentReport invoke(IncidentRequest request, List<AgenticEventListener> listeners) {
            // Every run records what the planner actually executed, streamed or not, so the
            // report's path is the planner's own trace rather than a reconstruction of it.
            var taken = new java.util.concurrent.CopyOnWriteArrayList<String>();
            AgenticEventListener trace = new AgenticEventListener() {
                @Override
                public void onProcessEvent(AgentProcessEvent event) {
                    if (event instanceof ActionExecutionStartEvent start) {
                        taken.add(RunEventListener.shortName(start.getAction().getName()));
                    }
                }
            };
            var all = new java.util.ArrayList<AgenticEventListener>(listeners);
            all.add(trace);
            var report = AgentInvocation.builder(agentPlatform)
                    .options(new ProcessOptions().withListeners(all))
                    .build(IncidentReport.class)
                    .invoke(request);
            return report == null ? null : report.withActionsTaken(List.copyOf(taken));
        }
    }
}
