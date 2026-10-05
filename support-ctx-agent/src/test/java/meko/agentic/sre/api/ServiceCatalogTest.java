package meko.agentic.sre.api;

import meko.agentic.sre.domain.IncidentRequest;
import meko.agentic.sre.domain.IncidentSeverity;
import meko.agentic.sre.service.IncidentDataService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every service the picker offers can actually be answered, and says what it is about.
 *
 * The dropdown used to be filled from the full estate — twenty services against two fixture
 * families — so picking `shipping` or `wallet` fell through to the payment default and showed
 * checkout error rates with nothing on screen admitting the substitution. The noticeable
 * version of the same bug was `recommendations`: search-family evidence paired with a symptom
 * textarea hardcoded to checkout, which the model dutifully blended.
 *
 * The invariant is that offering a service is a promise there is evidence written for it.
 * Adding a name to INVESTIGABLE_SERVICES without adding its fixtures must fail here.
 */
class ServiceCatalogTest {

    private final IncidentDataService data = new IncidentDataService();
    private final RequestController controller = new RequestController(data);

    @Test
    void everyOfferedServiceHasASymptomDescribingItsOwnFixtures() {
        for (var option : controller.services()) {
            assertNotNull(option.defaultSymptom(),
                    option.name() + " is offered with no symptom, so the form cannot stay consistent");
            assertFalse(option.defaultSymptom().isBlank(), option.name() + " has a blank symptom");
        }
    }

    @Test
    void searchFamilyServicesAreNotDescribedAsCheckoutIncidents() {
        var option = option("search");
        assertFalse(option.defaultSymptom().toLowerCase().contains("checkout"),
                "search is a search-family service and must not default to a checkout symptom: "
                        + option.defaultSymptom());
    }

    /**
     * A fixture family is not a corpus entry.
     *
     * recommendations mapped to the search family, so the symptom and the metrics agreed with
     * each other — and both disagreed with the Service field. The service map defines the
     * search path as indexer -> search index -> search with catalog as source of truth;
     * recommendations is in neither that diagram nor the escalation table, so the agent could
     * not scope it or name an owning team. Offering it was a promise of evidence that the
     * corpus never made.
     *
     * catalog failed the weaker half of the same test: described by the corpus, but still
     * handed search-prefixed telemetry and its own logs at status=200. The family speaks for
     * exactly one service, so it is offered under exactly one name.
     */
    @Test
    void theSearchFamilyIsOfferedUnderOneNameOnly() {
        var searchFamily = controller.services().stream()
                .filter(s -> s.defaultSymptom().startsWith("Stale results"))
                .map(RequestController.ServiceOption::name)
                .toList();

        assertEquals(List.of("search"), searchFamily,
                "only the service the search fixtures actually name may be offered");
    }

    /**
     * The payment family's version of the rule above. Its fixtures are about checkout calling
     * payment-api through payment-client; cart fell through to them by default and was offered
     * with a checkout symptom and checkout telemetry, none of it about cart.
     */
    @Test
    void thePaymentFamilyIsOfferedOnlyUnderTheServicesItsFixturesName() {
        var paymentFamily = controller.services().stream()
                .filter(s -> s.defaultSymptom().startsWith("Checkout failures"))
                .map(RequestController.ServiceOption::name)
                .toList();

        assertEquals(List.of("checkout", "payment-client", "payment-api"), paymentFamily,
                "only services the payment fixtures actually name may be offered");
    }

    @Test
    void theSymptomMatchesTheEvidenceTheServiceActuallyReturns() {
        // A search service must not be handed payment telemetry, and vice versa. This is the
        // pairing the screen showed as one incoherent answer.
        assertTrue(metricsFor("recommendations").stream().anyMatch(m -> m.startsWith("search.")),
                "recommendations belongs to the search family and must get search telemetry");
        assertTrue(metricsFor("checkout").stream().anyMatch(m -> m.startsWith("checkout.")),
                "checkout must get payment telemetry");
    }

    @Test
    void theEstateIsBiggerThanThePickerSoTheFullSweepStaysExpensive() {
        var offered = controller.services().size();
        var sweptServices = data.allLogs(request("checkout")).stream()
                .map(line -> line.split(" ")[1])
                .distinct()
                .count();

        assertTrue(sweptServices > offered,
                "the full sweep reads the whole estate, not just what can be picked: "
                        + sweptServices + " swept vs " + offered + " offered");
    }

    @Test
    void servicesWithoutSeededHistoryAreStillOfferedAndFlagged() {
        // Declining to reuse is a result the demo wants to show, so an unseeded service stays
        // on the list — it just has to announce itself.
        var unseeded = controller.services().stream().filter(s -> !s.hasSeededHistory()).toList();

        assertFalse(unseeded.isEmpty(), "the demo needs a service memory knows nothing about");
        assertTrue(unseeded.stream().anyMatch(s -> s.name().equals("search")));
    }

    private RequestController.ServiceOption option(String name) {
        return controller.services().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " is not offered"));
    }

    private List<String> metricsFor(String service) {
        return data.metrics(request(service));
    }

    private IncidentRequest request(String service) {
        return new IncidentRequest("INC-TEST-1", service, "symptom", true, IncidentSeverity.SEV2);
    }
}
