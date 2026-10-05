package meko.agentic.sre.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.event.ActionExecutionStartEvent;
import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.agent.api.event.ObjectAddedEvent;
import com.embabel.agent.core.Action;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.ProcessContext;
import com.embabel.agent.core.ProcessOptions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Embabel sends plan and action events only to the platform listener. The router is what gets
 * them to the run's own listeners — the live step chips and the report's executed-action trace
 * depend on it.
 */
class RunEventRouterTest {

    private final List<AgentProcessEvent> received = new ArrayList<>();
    private final AgenticEventListener runListener = new AgenticEventListener() {
        @Override
        public void onProcessEvent(AgentProcessEvent event) {
            received.add(event);
        }
    };
    private final RunEventRouter router = new RunEventRouter();
    private final AgentProcess process = mock(AgentProcess.class);

    RunEventRouterTest() {
        var context = mock(ProcessContext.class);
        when(context.getProcessOptions()).thenReturn(new ProcessOptions().withListeners(List.of(runListener, router)));
        when(process.getProcessContext()).thenReturn(context);
    }

    @Test
    void actionEventsReachTheRunThatProducedThem() {
        var action = mock(Action.class);
        when(action.getName()).thenReturn("IncidentCommanderAgent.evaluateReuse");

        router.onProcessEvent(new ActionExecutionStartEvent(process, action));

        assertEquals(1, received.size(), "delivered once, and not back to the router itself");
    }

    @Test
    void eventsTheRunAlreadyGetsAreNotDeliveredTwice() {
        router.onProcessEvent(new ObjectAddedEvent(process, "anything"));

        assertEquals(0, received.size(), "object bindings already reach run listeners via the process context");
    }
}
