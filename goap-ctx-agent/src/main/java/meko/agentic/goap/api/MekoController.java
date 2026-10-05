package meko.agentic.goap.api;

import meko.agentic.goap.context.MekoMcpClient;
import meko.agentic.goap.story.StorySeeder;
import meko.agentic.goap.story.store.OutageSwitch;
import meko.agentic.goap.story.store.ReceiptLog;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/meko")
public class MekoController {
    private final MekoMcpClient meko;
    private final StorySeeder seeder;
    private final ReceiptLog receipts;
    private final OutageSwitch outage;

    public MekoController(MekoMcpClient meko, StorySeeder seeder, ReceiptLog receipts, OutageSwitch outage) {
        this.meko = meko;
        this.seeder = seeder;
        this.receipts = receipts;
        this.outage = outage;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new java.util.LinkedHashMap<>(meko.status());
        status.put("simulatedOutage", outage.on());
        return status;
    }

    /** Trust scenario: make every context-store call fail, as if Meko were down. */
    @PostMapping("/outage")
    public Map<String, Object> outage(@RequestParam boolean on) {
        outage.set(on);
        return Map.of("simulatedOutage", outage.on());
    }

    /** Seeds the live datapack with the stories' synthetic organizational context. Safe to repeat. */
    @PostMapping("/seed")
    public ResponseEntity<?> seed() {
        if (!meko.configured()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Meko is not configured", "status", meko.status()));
        }
        var outcome = seeder.seedLive();
        boolean failed = receipts.forCase("seed").stream().anyMatch(r -> !r.ok());
        return ResponseEntity.status(failed ? 502 : 200).body(Map.of(
                "datapack", meko.datapackName(),
                "seeded", outcome,
                "errors", receipts.forCase("seed").stream().filter(r -> !r.ok()).map(ReceiptLog.Receipt::detail).toList()));
    }
}
