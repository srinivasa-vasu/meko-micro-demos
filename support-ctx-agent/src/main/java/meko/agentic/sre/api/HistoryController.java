package meko.agentic.sre.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import meko.agentic.sre.meko.MekoDatapack;
import meko.agentic.sre.meko.MekoToolBridge;
import meko.agentic.sre.service.MemoryRecordParser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What memory holds about a service right now, read from Meko.
 * <p>
 * The custom form used to say "No seeded history for search — expect a full investigation"
 * from a constant list of the services the seed writes. That stopped being true the moment a
 * run wrote back: a finished search incident is search history, and a recurrence under a new id
 * can match it. What the gate will consider is whatever is in memory, so that is what this reads
 * — the same search the agent's retrieval makes, parsed the same way.
 * <p>
 * It reports, it does not predict. Whether a record is reused depends on the gate's scoring and
 * the deployment state at run time; this says what is there to be scored.
 */
@RestController
@RequestMapping("/api/v1")
public class HistoryController {

    private static final int LIMIT = 20;

    private final MekoToolBridge meko;
    private final MekoDatapack datapack;
    private final MemoryRecordParser parser;

    public HistoryController(MekoToolBridge meko, MekoDatapack datapack, MemoryRecordParser parser) {
        this.meko = meko;
        this.datapack = datapack;
        this.parser = parser;
    }

    @GetMapping("/history")
    public History history(@RequestParam String service, @RequestParam(required = false) String incidentId) {
        if (!datapack.configured() || !meko.available()) {
            return new History(service, false, null, List.of());
        }
        var failures = new ArrayList<String>();
        var rows = meko.memorySearchRecords("INCIDENT_RECORD " + service, datapack.id(), LIMIT, failures::add);
        if (!failures.isEmpty()) {
            return new History(service, false, String.join("; ", failures), List.of());
        }
        var records = parser.parseIncidents(rows).stream()
                .filter(r -> about(r.service(), r.scopeServices(), service))
                .map(r -> new Record(
                        r.incidentId(),
                        r.writtenBy(),
                        r.raw() != null && r.raw().contains("seedKey="),
                        incidentId != null && incidentId.equalsIgnoreCase(r.incidentId())))
                .toList();
        return new History(service, true, null, records);
    }

    /** The same relevance the gate applies first: the record is about this service, or covers it. */
    private static boolean about(String recordService, List<String> scope, String service) {
        var wanted = service.toLowerCase(Locale.ROOT).strip();
        return wanted.equalsIgnoreCase(recordService == null ? "" : recordService.strip())
                || scope.stream().anyMatch(s -> wanted.equalsIgnoreCase(s.strip()));
    }

    /**
     * @param readable false when Meko is not configured or the read failed — then nothing is
     *                 known, which is different from knowing there is nothing
     */
    public record History(String service, boolean readable, String failure, List<Record> records) {}

    /**
     * @param seeded true when the seed wrote it; false when an agent run did
     * @param self   true when it carries the incident id being investigated: the gate will
     *               ignore it as an echo
     */
    public record Record(String incidentId, String writtenBy, boolean seeded, boolean self) {}
}
