package meko.agentic.sre.api;

import com.embabel.agent.api.event.AbstractAgentProcessEvent;
import com.embabel.agent.api.event.ActionExecutionResultEvent;
import com.embabel.agent.api.event.ActionExecutionStartEvent;
import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.agent.api.event.ReplanRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Delivers the planner's own events to the run that produced them.
 * <p>
 * Embabel(1.5.1) sends a process's events two ways. Object bindings and LLM
 * calls go through the process context, which includes the listeners passed in ProcessOptions.
 * But the plan, each action's start and finish, and replans go ONLY to the platform-wide
 * listener (SimpleAgentProcess / AbstractAgentProcess call platformServices.eventListener
 * directly). So a per-run listener saw what the agent produced and never saw what the planner
 * did: the live lanes drew detail lines with no step chips, and the report's executed-action
 * trace came back empty.
 * <p>
 * Every Spring bean implementing AgenticEventListener is folded into that platform listener
 * (AgentPlatformConfiguration.eventListener), so this bean receives those events for every
 * process — and each event carries its process, whose ProcessOptions still hold that run's own
 * listeners. Forwarding to them needs no registry and no correlation id.
 * <p>
 * ONLY the platform-only event types are forwarded. Everything else already reaches the run's
 * listeners through the process context, and forwarding it here would deliver it twice.
 */
@Component
public class RunEventRouter implements AgenticEventListener {

    private static final Logger log = LoggerFactory.getLogger(RunEventRouter.class);

    @Override
    public void onProcessEvent(AgentProcessEvent event) {
        if (!platformOnly(event) || !(event instanceof AbstractAgentProcessEvent e)) {
            return;
        }
        var listeners = e.getAgentProcess().getProcessContext().getProcessOptions().getListeners();
        for (var listener : listeners) {
            if (listener == this) {
                continue;
            }
            try {
                listener.onProcessEvent(event);
            } catch (RuntimeException ex) {
                // A run's listener failing must not break the platform's event dispatch.
                log.warn("Run listener {} failed on {}", listener, event.getClass().getSimpleName(), ex);
            }
        }
    }

    /** The event types Embabel sends only to the platform listener. */
    static boolean platformOnly(AgentProcessEvent event) {
        return event instanceof AgentProcessPlanFormulatedEvent
                || event instanceof ActionExecutionStartEvent
                || event instanceof ActionExecutionResultEvent
                || event instanceof ReplanRequestedEvent;
    }
}
