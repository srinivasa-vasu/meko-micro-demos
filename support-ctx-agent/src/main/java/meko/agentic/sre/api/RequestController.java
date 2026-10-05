package meko.agentic.sre.api;

import meko.agentic.sre.config.SweepProperties;
import meko.agentic.sre.domain.IncidentRequest;
import meko.agentic.sre.domain.IncidentSeverity;
import meko.agentic.sre.service.SeedService;
import meko.agentic.sre.service.IncidentDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class RequestController {

    private final IncidentDataService incidentData;

    public RequestController(IncidentDataService incidentData) {
        this.incidentData = incidentData;
    }

    @GetMapping("/incidents/checkout")
    public IncidentRequest demoIncident() {
        return new IncidentRequest(
                "INC-4821",
                "checkout",
                "Checkout failures increased sharply after a payment-service deployment",
                true,
                IncidentSeverity.SEV2
        );
    }

    /**
     * The services the demo can investigate, and whether the corpus knows anything about each.
     * <p>
     * The service field used to be free text, which makes the most consequential choice on the
     * form invisible: typing a service with no seeded history produces a full sweep and looks
     * like memory failing, when it is memory correctly declining.
     * <p>
     * The list is deliberately the investigable services, not the whole estate. Offering a
     * service the fixtures do not cover is worse than not offering it: it answers, from the
     * wrong family, with nothing on screen saying so.
     * <p>
     * defaultSymptom travels with each option so the form can keep the symptom and the service
     * agreeing. The server owns the family mapping; duplicating it in the page would let the
     * two drift.
     */
    @GetMapping("/services")
    public List<ServiceOption> services() {
        return incidentData.knownServices().stream()
                .map(name -> new ServiceOption(
                        name,
                        SeedService.SEEDED_SERVICES.contains(name),
                        incidentData.currentVersion(name),
                        incidentData.defaultSymptom(name)))
                .toList();
    }

    public record ServiceOption(String name, boolean hasSeededHistory, String deployedVersion,
                                String defaultSymptom) {}

    @GetMapping("/state")
    public Map<String, String> state() {
        return incidentData.deploymentState();
    }

    /**
     * What the seeded history is pinned to, and whether it is still live.
     *
     * The page used to state "pinned to payment-service 4.7.0" in its own copy. That is a fact
     * about the seed, so it is read from the seed — and "is it current" is computed from the
     * deployment state the gate checks, rather than left for the reader to work out.
     */
    @GetMapping("/pin")
    public Pin pin() {
        var service = SeedService.RECORDED_DEPLOY_SERVICE;
        var version = SeedService.RECORDED_DEPLOY_VERSION;
        return new Pin(SeedService.RECORDED_INCIDENT_ID, service, version,
                incidentData.currentVersion(service), incidentData.versionStillDeployed(service, version));
    }

    public record Pin(String incidentId, String service, String version, String deployedNow, boolean current) {}

    /**
     * How much telemetry the full sweep reads, and whether that has been cut.
     *
     * Separate from /state because that endpoint is a map of service to deployed version and the
     * page renders it as such. This is about the demo's own honesty rather than the world it is
     * describing: a sweep lowered to fit a rate limit produces a smaller saving, and the room has
     * to be told that rather than left to assume the default.
     */
    @GetMapping("/sweep")
    public SweepStatus sweep() {
        var sweep = incidentData.sweep();
        return new SweepStatus(
                sweep.linesPerService(),
                SweepProperties.FULL_DEFAULT,
                incidentData.knownServices().size(),
                sweep.reduced(),
                sweep.disclosure());
    }

    public record SweepStatus(int linesPerService, int defaultLinesPerService,
                              int investigableServices, boolean reduced, String disclosure) {}
}
