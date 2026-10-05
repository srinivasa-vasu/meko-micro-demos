package meko.agentic.sre.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Consumer;
import meko.agentic.sre.meko.MekoDatapack;
import meko.agentic.sre.meko.MekoToolBridge;
import meko.agentic.sre.meko.MekoToolBridge.MekoMemory;
import meko.agentic.sre.service.MemoryRecordParser;
import org.junit.jupiter.api.Test;

/** The custom form's note says what memory holds now, not what the seed wrote once. */
class HistoryControllerTest {

    private final MekoToolBridge meko = mock(MekoToolBridge.class);
    private final MekoDatapack datapack = mock(MekoDatapack.class);
    private final HistoryController controller = new HistoryController(meko, datapack, new MemoryRecordParser());

    HistoryControllerTest() {
        when(datapack.configured()).thenReturn(true);
        when(datapack.id()).thenReturn("dp");
        when(meko.available()).thenReturn(true);
    }

    @Test
    void searchHistoryWrittenByARunIsHistory() {
        when(meko.memorySearchRecords(anyString(), anyString(), anyInt(), any())).thenReturn(List.of(
                row("INCIDENT_RECORD; id=INC-7730; service=search; signature=stale; scopeServices=search", "sre-agent"),
                row("INCIDENT_RECORD; id=INC-7731; service=search; signature=stale; scopeServices=search", "sre-agent"),
                row("INCIDENT_RECORD; id=INC-1842; service=checkout; signature=t; scopeServices=checkout; seedKey=incident-1842", "sre-agent")));

        var h = controller.history("search", "INC-7731");

        assertTrue(h.readable());
        assertEquals(List.of("INC-7730", "INC-7731"), h.records().stream().map(HistoryController.Record::incidentId).toList(),
                "checkout history is not search history");
        assertFalse(h.records().getFirst().seeded(), "written by a run, not the seed");
        assertTrue(h.records().get(1).self(), "the incident being investigated is flagged as its own echo");
    }

    @Test
    void aFailedReadIsUnknownNotEmpty() {
        doAnswer(inv -> {
            inv.<Consumer<String>>getArgument(3).accept("memory_search: timed out");
            return List.of();
        }).when(meko).memorySearchRecords(anyString(), anyString(), anyInt(), any());

        var h = controller.history("search", null);

        assertFalse(h.readable());
        assertEquals("memory_search: timed out", h.failure());
    }

    private static MekoMemory row(String text, String writer) {
        return new MekoMemory(text.hashCode() + "", text, 0.6, 0.6, writer, "2026-10-01T00:00:00Z");
    }
}
