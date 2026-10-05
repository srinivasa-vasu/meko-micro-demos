package meko.agentic.sre.api;

import meko.agentic.sre.meko.MekoDatapack;
import meko.agentic.sre.meko.MekoToolBridge;
import meko.agentic.sre.service.SeedService;
import meko.agentic.sre.service.IncidentDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Manual demo controls.
 * <p>
 * These are the escape hatches, not the main path. Driving the demo one button at a time means
 * remembering the order — seed, set the deployment state, fill the form to match — and that
 * order is what gets forgotten in front of an audience. Prefer /api/demo/scenarios, which arms
 * a whole world state in one call and states up front what should happen.
 * <p>
 * Seeding itself lives in {@link SeedService} so the scenario endpoints can reuse it.
 */
@RestController
@RequestMapping("/api/v1/meko")
public class MekoController {

    private final SeedService seedService;
    private final IncidentDataService incidentData;
    private final MekoDatapack datapack;
    private final MekoToolBridge meko;

    public MekoController(
            SeedService seedService,
            IncidentDataService incidentData,
            MekoDatapack datapack,
            MekoToolBridge meko) {
        this.seedService = seedService;
        this.incidentData = incidentData;
        this.datapack = datapack;
        this.meko = meko;
    }

    /**
     * Why Meko is or is not working, in one request.
     * <p>
     * "No datapack resolved" has at least four distinct causes — the MCP client never
     * connected, the API key is wrong, the tool set does not include datapack_list, or the name
     * genuinely does not exist — and they are indistinguishable from the error message alone.
     * This endpoint separates them.
     */
    @GetMapping("/status")
    public MekoStatus status() {
        var tools = meko.available() ? meko.registeredTools() : List.<String>of();
        return new MekoStatus(
                meko.available(),
                tools.size(),
                tools,
                tools.stream().anyMatch(t -> t.endsWith("datapack_list")),
                datapack.configuredName(),
                datapack.id(),
                datapack.configured(),
                datapack.diagnosis());
    }

    public record MekoStatus(
            boolean mcpConnected,
            int toolCount,
            List<String> registeredTools,
            boolean datapackListAvailable,
            String configuredDatapackName,
            String resolvedDatapackId,
            boolean usable,
            String diagnosis) {}

    @PostMapping("/seed")
    public SeedService.SeedResult seed() {
        return seedService.seed();
    }

    /*
      Moves the world on so a stored remediation becomes stale. This drives the scenario that
      matters most on stage: an incident that superficially matches history but is not the
      same incident, where reuse must be rejected rather than confidently applied.
     */
    /**
     * Delete duplicate rows left by earlier non-idempotent seeding.
     * <p>
     * Destructive, so it is never part of arming — a presenter should decide to run this, not
     * discover it happened. Promoted rows are left alone unless asked for, because promotion is
     * one-way and those rows are shared with every agent on the datapack.
     */
    @PostMapping("/prune")
    public SeedService.PruneResult prune(
            @RequestParam(defaultValue = "false") boolean includePromoted) {
        return seedService.prune(includePromoted);
    }

    @PostMapping("/advance-deployment")
    public DeploymentChange advanceDeployment(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String version) {
        // The service the seeded history is pinned to: shipping anything else changes no result.
        service = service == null || service.isBlank() ? SeedService.RECORDED_DEPLOY_SERVICE : service;

        var previous = incidentData.currentVersion(service);
        // No version given means "ship whatever comes next", which is what the button says.
        // Defaulting to a fixed 5.0.0 made the second press a no-op that still reported success.
        var target = version == null || version.isBlank() ? nextVersionAfter(previous) : version;

        if (target.equals(previous)) {
            return new DeploymentChange(service, previous, previous, false,
                    "%s is already on %s. Nothing changed, so nothing became stale."
                            .formatted(service, previous));
        }

        incidentData.setDeployedVersion(service, target);
        return new DeploymentChange(service, previous, target, true,
                // The stale ones are those pinned to what WAS deployed. Worth stating in the
                // direction the reuse gate actually checks, because the inverse reads as
                // plausible and is wrong.
                "%s moved from %s to %s. Stored remediations that assume %s are now stale and the "
                        .formatted(service, previous, target, previous)
                        + "gate will refuse to serve them.");
    }

    /**
     * The next release after this one. Bumps the last numeric segment, so repeated presses keep
     * producing genuinely new versions rather than silently doing nothing.
     */
    private String nextVersionAfter(String version) {
        if (version == null || version.isBlank()) {
            return "1.0.0";
        }
        int cut = version.lastIndexOf('.');
        if (cut < 0) {
            return version + ".1";
        }
        try {
            return version.substring(0, cut + 1) + (Integer.parseInt(version.substring(cut + 1)) + 1);
        } catch (NumberFormatException e) {
            return version + ".1";   // not semver; append rather than guess
        }
    }

    public record DeploymentChange(
            String service, String previousVersion, String currentVersion,
            boolean changed, String effect) {}

    @PostMapping("/reset-deployment")
    public DeploymentChange resetDeployment() {
        var service = SeedService.RECORDED_DEPLOY_SERVICE;
        var back = SeedService.RECORDED_DEPLOY_VERSION;
        var previous = incidentData.currentVersion(service);
        incidentData.setDeployedVersion(service, back);
        return new DeploymentChange(service, previous, back, !back.equals(previous),
                back.equals(previous)
                        ? "%s is already on %s. Nothing changed.".formatted(service, back)
                        : "%s rolled back from %s to %s, which is the version the "
                                .formatted(service, previous, back)
                                + "stored remediation assumes. Reuse is possible again.");
    }
}
