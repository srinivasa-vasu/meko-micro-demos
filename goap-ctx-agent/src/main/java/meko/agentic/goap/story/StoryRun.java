package meko.agentic.goap.story;

import meko.agentic.goap.model.ModelCallLog;
import meko.agentic.goap.story.store.ReceiptLog;

import java.util.List;
import java.util.Map;

/** Everything one story run did, measured from the executed agent processes. */
public record StoryRun(
        String storyId,
        String title,
        boolean meko,
        /** WITHOUT, DATABASE or MEKO. */
        String lane,
        String store,
        String caseId,
        String outcome,
        List<StoryRunner.Phase> phases,
        List<StoryLedger.Step> steps,
        List<StoryLedger.Line> transcript,
        List<StoryLedger.Found> found,
        /** Retrieved but deliberately not used, with the reason: what makes it the right context. */
        List<StoryLedger.Passed> passedOver,
        List<StoryLedger.Decision> decisions,
        List<ReceiptLog.Receipt> receipts,
        List<ModelCallLog.ModelCall> modelCalls,
        Map<String, Object> metrics,
        /** The measured metrics to lead with, as {key, label, betterWhen}. */
        List<List<String>> headline,
        /** A follow-up's audit trail, entry by entry; empty for a story's own run. */
        List<StoryLedger.AuditEntry> auditTrail,
        /** What the run wrote to the context store, in full and in order: records, dialogue, memories, promotions. */
        List<meko.agentic.goap.story.store.ReceiptLog.Written> written) {
}
