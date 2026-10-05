package meko.agentic.sre.service;

import meko.agentic.sre.config.SweepProperties;
import meko.agentic.sre.domain.IncidentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stands in for the real telemetry, log and deployment integrations.
 *
 * Two things matter for the demo to be honest:
 *   1. the full log sweep has to be genuinely large, or the token delta is theatre;
 *   2. deployment state has to be queryable, because that is how staleness is detected
 *      without spending a token.
 */
@Service
public class IncidentDataService {

    private static final Logger log = LoggerFactory.getLogger(IncidentDataService.class);

    private final SweepProperties sweep;

    public IncidentDataService(SweepProperties sweep) {
        this.sweep = sweep;
        if (sweep.reduced()) {
            log.warn("{} Raise incident.sweep.lines-per-service, or move to a provider without "
                    + "that ceiling, before presenting an unqualified saving.", sweep.disclosure());
        }
    }

    /** Full sweep, for tests and for anything that wants the honest default. */
    public IncidentDataService() {
        this(SweepProperties.full());
    }

    /** What the sweep is set to, so the UI can disclose a reduced one rather than imply a full one. */
    public SweepProperties sweep() {
        return sweep;
    }

    /**
     * The estate, for log volume only.
     *
     * This list exists to make allLogs() genuinely expensive — 60 lines across 20 services is
     * what makes the token delta real rather than theatre. It is NOT the set of services the
     * demo can answer for, and using it as the picker was a bug: only two fixture families
     * exist, so thirteen of these silently fell through to the payment default and showed
     * checkout error rates for a shipping incident. See INVESTIGABLE_SERVICES.
     */
    private static final List<String> NOISY_SERVICES = List.of(
            "checkout", "payment-client", "payment-api", "cart", "catalog", "search",
            "recommendations", "session", "inventory", "shipping", "pricing", "notifications",
            "auth", "profile", "reviews", "fraud", "tax", "wallet", "ledger", "email"
    );

    /**
     * The services the demo has real evidence for, which is what the picker offers.
     *
     * Belonging to a fixture family is not enough — the evidence has to name the service too.
     * `recommendations` was offered and mapped to the search family, but it appears nowhere in
     * the service map or the staleness runbook, so an incident declared on it showed four
     * metrics named search and indexer, a change log reading "No search service deployment",
     * and its own logs at status=200 throughout. Nothing on screen was about the service in
     * the dropdown. Offering a service is a promise there is evidence written for it.
     *
     * `catalog` was the milder version of the same thing: the corpus does describe it, so the
     * agent could scope it and page Discovery, but the telemetry is still search-prefixed and
     * its own logs are still all 200s. The search family speaks for `search`, so that is the
     * only name it is offered under.
     *
     * family() still maps both to the search family, so a direct API call gets the nearer of
     * the two fixture families rather than payment telemetry. They are just not on the menu.
     *
     * `cart` was the payment-family version: it fell through to the payment default, so picking
     * it filled the form with a checkout symptom over checkout telemetry and the checkout
     * runbook. The service map says payment timeouts almost never involve cart. It stays in the
     * deployment state, which is world, not a promise of evidence.
     */
    private static final List<String> INVESTIGABLE_SERVICES = List.of(
            "checkout", "payment-client", "payment-api", "search"
    );

    /**
     * What each fixture family is actually about, so the form can say so.
     *
     * The symptom text drives retrieval, so a form that pairs a search service with a checkout
     * symptom produces a blended answer that reads as the agent failing. Keeping the default
     * symptom next to the family it belongs to means the two can only disagree on purpose.
     */
    private static final Map<String, String> FAMILY_SYMPTOMS = Map.of(
            "search", "Stale results: index lag climbing, documents not committing",
            "payment", "Checkout failures: payment provider timeout after deployment"
    );

    /**
     * The payment-service release the March incident was recorded against.
     *
     * A fact about the past, so it is a constant. The seeded INCIDENT_RECORD must reference
     * THIS, never currentVersion() — a record that follows the live deployment can never look
     * stale, which silently disables the entire staleness demonstration.
     */
    public static final String INCIDENT_ERA_PAYMENT_VERSION = "4.7.0";

    /** Current deployed versions. Real implementations hit the deployment database. */
    private final Map<String, String> deployedVersions = new LinkedHashMap<>(Map.of(
            "payment-service", INCIDENT_ERA_PAYMENT_VERSION,
            "checkout", "9.2.1",
            "cart", "3.3.0",
            // Every service an incident can be recorded against needs a version here, or its
            // write-back records a blank deployVersion and no later recurrence can ever be
            // confirmed current — the gate narrows with it but never answers from it. A real
            // deployment database knows every service's version. Stable, matching the change log
            // ("No search service deployment in the previous 72 hours").
            "search", "3.8.2"
    ));

    /**
     * Telemetry for the incident actually being investigated.
     *
     * Used to return the checkout payment-timeout fixture for every request, which was fine
     * while the demo only ever ran one incident. It stops being fine the moment a scenario
     * asks the agent about something the corpus does NOT know: the gate correctly declines to
     * match, and then the screen shows checkout error rates for a search incident, which reads
     * as a bug and costs more credibility than the scenario buys.
     */
    public List<String> metrics(IncidentRequest request) {
        return switch (family(request)) {
            case "search" -> List.of(
                    "search.stale_document_ratio = 12.4% (baseline 0.1%)",
                    "search.index_lag = 41m (baseline under 60s)",
                    "search.query_latency_p95 = 180ms (baseline 165ms)",
                    "indexer.documents_committed = 0 in the last 38 minutes"
            );
            default -> List.of(
                    "checkout.error_rate = 18.2% (baseline 0.7%)",
                    "payment-api.p95_latency = 4.8s (baseline 420ms)",
                    "payment-api.timeout_rate = 14.9%",
                    "checkout.retry_rate = 31%"
            );
        };
    }

    /** Which fixture family an incident belongs to. */
    private String family(IncidentRequest request) {
        return family(request == null ? null : request.service());
    }

    private String family(String service) {
        return switch (service == null ? "" : service.toLowerCase(Locale.ROOT)) {
            case "search", "recommendations", "catalog" -> "search";
            default -> "payment";
        };
    }

    /** The symptom this service's fixtures were written to answer. */
    public String defaultSymptom(String service) {
        return FAMILY_SYMPTOMS.get(family(service));
    }

    /**
     * Everything. This is the expensive path.
     *
     * The volume matters: if the full sweep is small, the token delta is theatre. The default
     * 60 lines across 20 services is still conservative next to a real 2am incident, which is
     * the right direction to err in. It is lowerable only because some free tiers reject a
     * request that large outright — see SweepProperties, which also makes the demo say so.
     */
    public List<String> allLogs(IncidentRequest request) {
        return generateLogs(NOISY_SERVICES, sweep.linesPerService(), failing(request));
    }

    /** Only the services history says matter. This is the partial-reuse saving. */
    public List<String> scopedLogs(IncidentRequest request, List<String> services) {
        var scope = services == null || services.isEmpty()
                ? List.of(request.service())
                : services;
        return generateLogs(scope, sweep.scopedLinesPerService(), failing(request));
    }

    public List<String> recentChanges(IncidentRequest request) {
        if ("search".equals(family(request))) {
            return List.of(
                    "13:22Z search index rebuild started by a scheduled job",
                    "No search service deployment in the previous 72 hours",
                    "Analyzer configuration unchanged since August",
                    "No infrastructure changes in the previous 60 minutes"
            );
        }
        // NO LINE NAMING THE TIMEOUT CHANGE, deliberately. It used to read "4.7.0 changed payment
        // client timeout from 5s to 3s", which handed the cause to every run: the run without
        // memory never had to diagnose anything, so a claim that memory corrects the diagnosis
        // could never be distinctive. A real change log says what shipped, not what broke. The
        // evidence is still here — PaymentProviderTimeout after 3000ms in the logs against a
        // 4.8s p95 — but reading it as a configuration change is now the model's job, and the
        // March incident in memory is what makes it an easy one.
        return List.of(
                "14:01Z deployed payment-service " + deployedVersions.get("payment-service"),
                "Release notes: dependency upgrades and payment client configuration cleanup",
                "No database migrations in the deployment",
                "No infrastructure changes in the previous 60 minutes"
        );
    }

    /**
     * The deployable whose version a remediation for this service depends on.
     *
     * Payment-family incidents are about the payment-service release whichever service fired
     * the alert, which is what the fixtures and the seeded records say. Anything else depends
     * on its own deployment. Writing payment-service for every incident meant a search record
     * was checked for staleness against a payment release it has nothing to do with.
     */
    public String deployServiceFor(String service) {
        return "payment".equals(family(service)) ? "payment-service" : service;
    }

    /** Zero-token staleness input: is the version a stored remediation referenced still live? */
    public String currentVersion(String service) {
        return deployedVersions.get(service);
    }

    public boolean versionStillDeployed(String service, String version) {
        if (service == null || version == null || version.isBlank()) {
            return false;
        }
        return version.equals(deployedVersions.get(service));
    }

    public boolean serviceExists(String service) {
        return service != null && (deployedVersions.containsKey(service)
                || NOISY_SERVICES.contains(service.toLowerCase(Locale.ROOT)));
    }

    /** What the demo can be asked about and answer with evidence written for it. */
    public List<String> knownServices() {
        return INVESTIGABLE_SERVICES;
    }

    public Map<String, String> deploymentState() {
        return Map.copyOf(deployedVersions);
    }

    /** Demo hook: move the world on so a stored remediation becomes stale. */
    public void setDeployedVersion(String service, String version) {
        deployedVersions.put(service, version);
    }

    /**
     * The services that are actually failing in this incident's world.
     * <p>
     * ONLY THESE log their failure. Every service used to log its fixture failure in every
     * sweep, so a search incident's full sweep carried checkout PaymentProviderTimeout and
     * Stripe upstream timeouts — a payment outage that does not exist in that world. The run
     * without memory read the whole estate, saw it, and blamed Stripe for stale search results,
     * while the run with memory read a scoped slice that excluded it. That is a handicapped
     * baseline, not a comparison: the rest of the estate is noise, so it has to look like noise.
     */
    private Set<String> failing(IncidentRequest request) {
        return "search".equals(family(request))
                ? Set.of("search")
                : Set.of("checkout", "payment-client", "payment-api");
    }

    private List<String> generateLogs(List<String> services, int linesPerService, Set<String> failing) {
        var out = new ArrayList<String>();
        for (var service : services) {
            for (int i = 0; i < linesPerService; i++) {
                out.add(failing.contains(service) ? logLine(service, i) : healthyLine(service, i));
            }
        }
        return List.copyOf(out);
    }

    private static String timestamp(int i) {
        return "2026-09-06T14:%02d:%02dZ".formatted(2 + (i % 8), (i * 7) % 60);
    }

    private String healthyLine(String service, int i) {
        return timestamp(i) + " " + service + " INFO request handled status=200 duration=" + (12 + i * 3) + "ms";
    }

    private String logLine(String service, int i) {
        var ts = timestamp(i);
        return switch (service) {
            case "payment-client" -> ts + " payment-client WARN upstream timeout provider=stripe attempt=" + (i % 3 + 1);
            case "checkout" -> ts + " checkout ERROR PaymentProviderTimeout after 3000ms order=demo-" + (4800 + i);
            case "payment-api" -> ts + " payment-api WARN deadline exceeded downstream=stripe p95=4.8s";
            case "search" -> ts + " search WARN serving stale segment generation=" + (1180 + i)
                    + " committed=false age=41m";
            default -> healthyLine(service, i);
        };
    }
}
