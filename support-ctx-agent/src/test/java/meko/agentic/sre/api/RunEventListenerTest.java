package meko.agentic.sre.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.event.ActionExecutionStartEvent;
import com.embabel.agent.api.event.ObjectAddedEvent;
import com.embabel.agent.api.event.ObjectBoundEvent;
import com.embabel.agent.core.Action;
import com.embabel.agent.core.ActionStatus;
import com.embabel.agent.core.ActionStatusCode;
import com.embabel.agent.core.AgentProcess;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import meko.agentic.sre.domain.ReuseDecision;
import meko.agentic.sre.domain.ReuseMode;
import org.junit.jupiter.api.Test;

/**
 * The stream is only worth watching if its frames describe what the planner actually did, so
 * this pins the mapping from Embabel's events to the frames the page draws.
 */
class RunEventListenerTest {

    private record Frame(String event, Map<String, Object> data) {}

    private final List<Frame> frames = new ArrayList<>();
    private final RunEventListener listener =
            new RunEventListener("warm", (event, data) -> frames.add(new Frame(event, data)));
    private final AgentProcess process = mock(AgentProcess.class);

    @Test
    void actionStartAndFinishBecomeStepFramesUnderTheMethodName() {
        var action = mock(Action.class);
        when(action.getName()).thenReturn("meko.agentic.sre.agent.IncidentCommanderAgent.evaluateReuse");

        var start = new ActionExecutionStartEvent(process, action);
        listener.onProcessEvent(start);
        listener.onProcessEvent(start.resultEvent(new ActionStatus(Duration.ofMillis(3), ActionStatusCode.SUCCEEDED)));

        assertEquals(2, frames.size());
        assertEquals("step", frames.get(0).event());
        assertEquals("evaluateReuse", frames.get(0).data().get("action"));
        assertEquals("running", frames.get(0).data().get("state"));
        assertEquals("SUCCEEDED", frames.get(1).data().get("state"));
        assertEquals("warm", frames.get(1).data().get("run"), "every frame carries its run label");
    }

    @Test
    void reuseDecisionOnTheBlackboardStreamsTheGateVerdict() {
        var decision = ReuseDecision.of(ReuseMode.SCOPED, 0.71, true,
                "Stored remediation references payment-service 4.7.0 but 5.0.0 is deployed now.",
                null, "stale");

        listener.onProcessEvent(new ObjectAddedEvent(process, decision));

        assertEquals(1, frames.size());
        var f = frames.getFirst();
        assertEquals("reuse", f.event());
        assertEquals(ReuseMode.SCOPED, f.data().get("mode"));
        assertEquals(true, f.data().get("stale"));
        assertTrue(f.data().containsKey("matchId"), "a null match is reported as null, not dropped");
    }

    @Test
    void anObjectAddedAndThenBoundIsReportedOnce() {
        var decision = ReuseDecision.none("nothing matched");

        listener.onProcessEvent(new ObjectAddedEvent(process, decision));
        listener.onProcessEvent(new ObjectBoundEvent(process, "it", decision));

        assertEquals(1, frames.size());
    }

    @Test
    void objectsThePageDoesNotDrawAreIgnored() {
        listener.onProcessEvent(new ObjectAddedEvent(process, "the incident request, say"));

        assertTrue(frames.isEmpty());
    }

    @Test
    void shortNameToleratesUnqualifiedNames() {
        assertEquals("finalizeIncident", RunEventListener.shortName("finalizeIncident"));
        assertEquals("", RunEventListener.shortName(null));
    }
}
