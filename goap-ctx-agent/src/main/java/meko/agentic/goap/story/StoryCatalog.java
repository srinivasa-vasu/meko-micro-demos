package meko.agentic.goap.story;

import meko.agentic.goap.story.agents.CatchUpAgent;
import meko.agentic.goap.story.agents.DisputeAuditorAgent;
import meko.agentic.goap.story.agents.KycReviewAgent;
import meko.agentic.goap.story.agents.KycReviewCheckAgent;
import meko.agentic.goap.story.agents.PaymentFailureAgent;
import meko.agentic.goap.story.agents.PaymentsSpecialistAgent;
import meko.agentic.goap.story.agents.SreInvestigatorAgent;
import meko.agentic.goap.story.agents.SreTakeoverAgent;
import meko.agentic.goap.story.agents.SupportFrontlineAgent;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StoryCatalog {

    /**
     * @param headline   measured metrics to lead with, as {key, label, betterWhen} where betterWhen is "lower" or "higher"
     * @param mekoTools  the Meko capabilities this story actually exercises
     */
    /**
     * @param boundary   the system boundary the context has to cross, stated on screen so the setup is explicit
     * @param ladder     how the story exercises each rung of memory → knowledge → right context, as {rung, how};
     *                   a blank "how" means the rung is not what this story is about
     */
    /**
     * @param followUp what happens to the case later, run by another agent given only the case's id; null if none
     */
    public record Story(String id, String title, String domain, String problem, String hook,
                        String withoutMeko, String withMeko, String goap, String punchline,
                        List<String> agents, List<String> mekoTools, List<List<String>> headline, String presenterTip,
                        String boundary, List<List<String>> ladder, FollowUp followUp, String reset, List<CastMember> cast) {
        Story(String id, String title, String domain, String problem, String hook, String withoutMeko, String withMeko,
              String goap, String punchline, List<String> agents, List<String> mekoTools, List<List<String>> headline,
              String presenterTip, String boundary, List<List<String>> ladder) {
            this(id, title, domain, problem, hook, withoutMeko, withMeko, goap, punchline, agents, mekoTools, headline,
                    presenterTip, boundary, ladder, null, null, List.of());
        }

        Story withFollowUp(FollowUp followUp) {
            return new Story(id, title, domain, problem, hook, withoutMeko, withMeko, goap, punchline, agents, mekoTools,
                    headline, presenterTip, boundary, ladder, followUp, reset, cast);
        }

        /** The agents in this case, in the order they act. */
        Story withCast(CastMember... cast) {
            return new Story(id, title, domain, problem, hook, withoutMeko, withMeko, goap, punchline, agents, mekoTools,
                    headline, presenterTip, boundary, ladder, followUp, reset, List.of(cast));
        }

        /** @param reset the button that starts the story over (see {@link StoryService#reset}) */
        Story withReset(String reset) {
            return new Story(id, title, domain, problem, hook, withoutMeko, withMeko, goap, punchline, agents, mekoTools,
                    headline, presenterTip, boundary, ladder, followUp, reset, cast);
        }
    }

    /**
     * A later look at a finished case by someone accountable, given only the case's id: it reads the decision traces.
     *
     * @param label     the button that runs it
     * @param who       who looks at the case
     * @param agent     the Embabel agent that runs it
     * @param caseKind  what its own case ids are named after (see {@link StoryService#newCaseId})
     * @param subject   what it is given, e.g. "transaction"
     * @param running   the status shown while it runs
     * @param headline  measured metrics to lead with, as for a story
     */
    public record FollowUp(String label, String who, String agent, String caseKind, String subject, String running,
                           List<List<String>> headline) {}

    /**
     * One agent in a case: its Meko agent id (the same constant the agent writes with), the system it lives in, what it
     * does, when it acts relative to this run ("earlier": its writes are already in context; "now"; "later": a
     * follow-up), and what it hands to the next agent across the boundary, if anything.
     */
    public record CastMember(String id, String name, String system, String role, String when, String handsOver) {}

    static final FollowUp AUDIT = new FollowUp("Audit this transaction", "Compliance auditor", "DisputeAuditorAgent", "audit", "transaction",
            "Weeks later: a compliance auditor reviews each lane's transaction, given only its id…",
            List.of(List.of("decisionTraces", "Decision traces on record", "higher"), List.of("recordsRequested", "Records requested from other teams", "lower"),
                    List.of("minutes", "Audit minutes", "lower")));

    static final FollowUp REVIEW_CHECK = new FollowUp("Check this review", "Model-risk reviewer", "KycReviewCheckAgent", "review-check", "review",
            "Later: a model-risk reviewer checks each lane's KYC review against the standard, given only its id…",
            List.of(List.of("decisionTraces", "Decision traces on record", "higher"), List.of("recordsRequested", "Records requested from other teams", "lower"),
                    List.of("minutes", "Review-check minutes", "lower")));

    private final List<Story> stories = List.of(
            new Story("dont-make-me-explain", "Don't Make Me Explain It Again", "SUPPORT",
                    "A customer explains their problem to a vendor's support chatbot, gets escalated to your payments specialist, and is asked everything again.",
                    "Every customer has lived this. Repeating yourself is the moment trust breaks.",
                    "Your specialist gets the CRM ticket note and nothing else, so it has to ask the customer again for anything missing.",
                    "The vendor chatbot and your specialist both connect to Meko over MCP. The chatbot writes the conversation and handoff facts; your specialist, in a different system, recalls the facts by case and finds the dispute policy in shared knowledge. Both write to the transaction's conversation, which an auditor later reads in one call.",
                    "Both runs start from the same optimistic plan. After the case file is opened the planner replans: each fact that is still unknown adds an \"ask the customer\" action.",
                    "The customer changed agents. The context didn't.",
                    List.of("Vendor chatbot", "Payments specialist", "Compliance auditor"),
                    List.of("conversation_add_message (keyed by transaction id)", "memory_add", "memory_search (case-scoped)", "knowledgebase_search", "conversation_get (auditor, by transaction id)"),
                    List.of(List.of("customerQuestions", "Customer re-asked", "lower"), List.of("minutes", "Handling minutes", "lower")),
                    "Read the specialist's lines in the transcript aloud: that is the customer experience. Then press Audit this transaction: weeks later an auditor, given only the transaction id, gets the whole record back from one conversation_get.",
                    "Frontline: a vendor's chatbot, a separate product with its own store. Specialist: your payments agent. Only the CRM ticket crosses between them.",
                    List.of(List.of("memory", "The vendor bot's handoff facts, recalled by case id; the conversation, keyed by the transaction id"), List.of("knowledge", "The dispute policy, an uploaded document in shared knowledge"), List.of("right context", "CARD-DISPUTE-07, not the look-alike chargeback policy CARD-DISPUTE-03"), List.of("decision traces", "Each decision written to the transaction's conversation with who made it; the auditor reads it back by transaction id"))).withFollowUp(AUDIT).withCast(
                    new CastMember(SupportFrontlineAgent.AGENT_ID, "Vendor chatbot", "The vendor's product, with its own store", "Talks to the customer, escalates", "now", "CRM ticket note"),
                    new CastMember(PaymentsSpecialistAgent.AGENT_ID, "Payments specialist", "Your application", "Resolves the dispute, replies", "now", "Weeks later: the transaction id"),
                    new CastMember(DisputeAuditorAgent.AGENT_ID, "Compliance auditor", "Your compliance desk", "Reviews how the dispute was handled", "later", null)),
            new Story("agent-died-2am", "The Agent Died at 2 AM", "SRE / SDLC",
                    "The payments team's on-call agent is halfway through a production incident when its pod is OOMKilled, and your platform SRE team's agent has to take over.",
                    "Everyone has inherited an incident where half the investigation lived in someone's head.",
                    "Your platform agent starts from the alert and repeats the deploy correlation and the database check.",
                    "The payments team's agent checkpointed its findings to Meko (artifact + memory). Your platform agent, a different team's application given only the incident id, restores the checkpoint and resumes at the unfinished step.",
                    "The investigator process is genuinely terminated mid-plan. The takeover agent's planner schedules re-work only for steps it could not recover.",
                    "The agent failed. The investigation didn't.",
                    List.of("Payments team's on-call agent", "Platform SRE agent"),
                    List.of("artifact_put", "artifact_get", "memory_add", "memory_search (case-scoped)"),
                    List.of(List.of("minutes", "Minutes to mitigation", "lower"), List.of("repeatedSteps", "Steps repeated", "lower")),
                    "Use Start incident, restart the server, then Take over: with Meko the takeover still resumes, because nothing it needs lived in the process.",
                    "First investigator: the payments team's agent, in the payments team's application. Takeover: your platform SRE team's agent. Only the incident id crosses between them.",
                    List.of(List.of("memory", "The dead agent's checkpoint and findings, restored from just the incident id"), List.of("knowledge", ""), List.of("right context", ""), List.of("decision traces", "The takeover's judgement of how far the dead agent got, written to the incident's conversation with who made it"))).withCast(
                    new CastMember(SreInvestigatorAgent.AGENT_ID, "On-call agent", "The payments team's application", "Investigates, checkpoints, then dies", "now", "The incident id"),
                    new CastMember(SreTakeoverAgent.AGENT_ID, "Platform SRE agent", "Your platform team", "Takes over, mitigates", "now", null)),
            new Story("what-changed", "What Changed Since Last Review?", "BFSI / KYC",
                    "A periodic KYC re-review for a business customer. Last quarter's review was done by another analyst.",
                    "Analysts don't want another 40-page re-read when only one thing changed.",
                    "With no prior review to compare against, the only path to sign-off is re-verifying every lane.",
                    "The agent recovers last quarter's review, written to Meko by another analyst agent, diffs it against today's profile and requests evidence only for the material change.",
                    "The planner picks between a targeted path and a full review depending on whether the prior review was recovered and what the diff found.",
                    "The question is not \"what do I know?\" It is \"what changed?\"",
                    List.of("KYC analyst agent"),
                    List.of("memory_search (written by kyc-analyst:2026-q2)", "artifact_put"),
                    List.of(List.of("evidenceRequests", "Evidence requests", "lower"), List.of("minutes", "Analyst minutes", "lower")),
                    "Point at who wrote the prior review: a different agent, a quarter ago.",
                    "Last quarter's review: written by the analyst desk's agent, in the analyst desk's system.",
                    List.of(List.of("memory", "Last quarter's review, written by another desk's agent"), List.of("knowledge", "The KYC re-review standard: what counts as a material change"), List.of("right context", "The 2026 review of CUST-7788, not the 2025 one and not CUST-7789"), List.of("decision traces", "The analyst's decisions, the review it used and what it passed over, in the review's conversation; a model-risk reviewer checks them by review id"))).withFollowUp(REVIEW_CHECK).withReset("Start a new review cycle").withCast(
                    new CastMember(StorySeeder.ANALYST_DESK, "Analyst desk's agent", "The analyst desk's system", "Reviewed the customer last quarter", "earlier", "Nothing: only its record in shared memory"),
                    new CastMember(KycReviewAgent.AGENT_ID, "Your KYC agent", "Your review desk", "Re-reviews, requests evidence, writes the result back", "now", "Later: the review id"),
                    new CastMember(KycReviewCheckAgent.AGENT_ID, "Model-risk reviewer", "Your model-risk team", "Checks the review against the standard", "later", null)),
            new Story("vacation-catch-up", "I Was on Vacation. Catch Me Up.", "SDLC",
                    "A developer returns after two weeks to a payment-retry pull request.",
                    "The code is current. The decisions, review requests and incident lessons around it are what you missed.",
                    "The developer reconstructs two weeks from PR threads, chat and the wiki. They find the ADR but miss the reviewer's request that lived in chat, and the PR bounces.",
                    "The catch-up agent reads what other tools wrote to the shared datapack while the developer was away: a Claude Code pairing session, the PR review bot, the incident bot, plus ADR-194.",
                    "Recovered context posts the conditions that make the idempotency-test action necessary. Without it, the planner has no reason to schedule it.",
                    "Your coding assistant remembers the project, not just the prompt.",
                    List.of("Catch-up agent"),
                    List.of("memory_search (cross-agent: claude_code, pr-review-bot, incident-bot)", "knowledgebase_search"),
                    List.of(List.of("minutes", "Minutes to approval", "lower"), List.of("missedRequirements", "Missed requirements", "lower")),
                    "The memories were written by other tools. Any MCP client on the same datapack, such as Claude Code or Cursor, can write them.",
                    "Context written by other tools: a Claude Code session, the PR review bot, the incident bot, and the architecture wiki.",
                    List.of(List.of("memory", "Decisions and requests written by Claude Code, the PR bot and the incident bot"), List.of("knowledge", "ADR-194, the architecture decision behind the retry contract"), List.of("right context", "The open request on PR-4821; the superseded draft and PR-4812 are passed over"), List.of("decision traces", "The catch-up agent's reading of the project context, written to the PR case's conversation with who made it"))).withReset("Reopen the review request").withCast(
                    new CastMember(StorySeeder.CODING_ASSISTANT, "Claude Code session", "A developer's coding assistant", "Recorded the retry decision", "earlier", null),
                    new CastMember(StorySeeder.PR_BOT, "PR review bot", "The code-review tool", "Recorded the open review request", "earlier", null),
                    new CastMember(StorySeeder.INCIDENT_BOT, "Incident bot", "The incident tool", "Recorded the postmortem lesson", "earlier", "Nothing: only what they wrote"),
                    new CastMember(CatchUpAgent.AGENT_ID, "Catch-up agent", "Your coding assistant", "Briefs you, finishes the PR, writes back what it addressed", "now", null)),
            new Story("organization-learned", "The Organization Learned", "BFSI / SUPPORT",
                    "The same payment failure keeps arriving from different customers, and the first one was solved by another team.",
                    "Solving case one is not the value. Not rediscovering the answer on case one hundred is.",
                    "Every case tests every hypothesis again. What one investigation learned leaves with it.",
                    "The EU payments-ops team's agent solves case 1 and promotes the root cause into Meko's shared knowledge base. Your support app's agents, a different application, find it and validate it instead of investigating. With your own database, the EU team's lesson stays in the EU team's database.",
                    "A learned pattern in shared knowledge makes the cheap validation path available; without it, the planner's only route is testing every hypothesis.",
                    "Every solved case lowers the cost of the next one.",
                    List.of("Payments investigator", "Knowledge curator"),
                    List.of("knowledgebase_search", "memory_add", "memory_promote"),
                    List.of(List.of("minutes", "Minutes per case", "lower"), List.of("hypothesesTested", "Hypotheses tested", "lower")),
                    "Run the series: case 1 is solved by another team. Only with Meko does your app's case 2 get cheaper; your own database never saw that team's lesson.",
                    "Case 1: the EU payments-ops team's agent, in their application. Cases 2 and 3: your support app.",
                    List.of(List.of("memory", "The EU team's root cause, written as it closes case 1"), List.of("knowledge", "Promoted from memory into shared knowledge, so other applications find it"), List.of("right context", "Checked against each case before reuse; a case that only looks like it is rejected"), List.of("decision traces", "Each judgement of whether a learned pattern fits, traced and cited in the resolution note"))).withReset("Reset learning").withCast(
                    new CastMember(PaymentFailureAgent.AGENT_ID, "Payments investigator", "Case 1: the EU payments-ops team's application; cases 2 and 3: your support app", "Diagnoses each failed payment", "now", null),
                    new CastMember(PaymentFailureAgent.CURATOR_ID, "Knowledge curator", "The EU payments-ops team's application", "Promotes the root cause to shared knowledge", "now", null)));

    public List<Story> all() {
        return stories;
    }

    public Story get(String id) {
        return stories.stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown story: " + id));
    }
}
