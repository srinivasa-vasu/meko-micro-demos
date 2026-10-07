package meko.agentic.goap.story.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.common.PlannerType;
import meko.agentic.goap.story.PaymentDisputes;
import meko.agentic.goap.story.StoryLedger;
import meko.agentic.goap.story.store.ContextStore;

import java.util.List;

/**
 * Story 1, phase 1: a vendor's frontline chatbot (a separate product with its own store) talks to the customer, then
 * escalates. What crosses to your payments specialist is only the CRM ticket note. Everything else the specialist
 * knows has to come from a context store both sides can reach.
 */
@Agent(planner = PlannerType.GOAP, description = "Vendor frontline chatbot that captures the customer's problem and escalates it")
public class SupportFrontlineAgent {
    public static final String AGENT_ID = "vendor:helpbot";

    public record SupportChat(String caseId, ContextStore.Kind store) {}

    public record ChatCaptured(String caseId, ContextStore.Kind store, String transaction, String amount, String merchant) {}

    public record TicketNote(String caseId, ContextStore.Kind store, String note) {}

    /** The customer conversation. The facts in it are what the specialist will need. */
    static List<String[]> conversation(PaymentDisputes.Dispute dispute) {
        return List.of(
                new String[]{"My card was charged but the checkout said the payment failed.",
                        "Sorry about that. Which transaction is it?"},
                new String[]{"Transaction " + dispute.transaction() + ", " + dispute.amount() + " at " + dispute.merchant() + ", yesterday evening.",
                        "Thanks. Have you tried anything already?"},
                new String[]{"I retried once and restarted the app. It failed again but the first debit stayed.",
                        "Understood. Is the card still with you?"},
                new String[]{"Yes, the card is with me. It isn't lost.",
                        "Thank you. I'm passing this to our payments specialist."});
    }

    /** The event name of the chatbot's handoff record in the transaction's conversation. */
    public static final String HANDOFF = "handoff to payments specialist";

    private final StorySupport support;

    public SupportFrontlineAgent(StorySupport support) {
        this.support = support;
    }

    @Action(description = "Talk to the customer and capture the problem", post = {"chatCaptured"}, cost = 0.10, canRerun = false)
    public ChatCaptured converse(SupportChat chat) {
        StoryLedger ledger = support.ledger(chat.caseId());
        ContextStore store = support.store(chat.store());
        // The simulated customer: the bot learns the transaction from what they say, and keys the conversation by it.
        PaymentDisputes.Dispute dispute = support.disputes().forCase(chat.caseId());
        for (String[] turn : conversation(dispute)) {
            ledger.say("Customer", turn[0]);
            ledger.say("Vendor chatbot", turn[1]);
            support.record(store, chat.caseId(), AGENT_ID, dispute.transaction(), turn[0], turn[1]);
        }
        ledger.step("Vendor chatbot", "converse", "Captured transaction, amount, merchant, troubleshooting and card status from the customer.", support.effort("support-frontline", "converse"), StoryLedger.Kind.WORK);
        return new ChatCaptured(chat.caseId(), chat.store(), dispute.transaction(), dispute.amount(), dispute.merchant());
    }

    @AchievesGoal(description = "The case is escalated to a payments specialist")
    @Action(description = "Record the established facts and escalate with a ticket note",
            pre = {"chatCaptured"}, post = {"escalated"}, cost = 0.05, canRerun = false)
    public TicketNote escalate(ChatCaptured chat) {
        StoryLedger ledger = support.ledger(chat.caseId());
        ContextStore store = support.store(chat.store());
        String facts = "Support case " + chat.caseId() + " handoff facts: customer confirmed transaction " + chat.transaction() + " for " + chat.amount() + " at " + chat.merchant() + ". "
                + "Card was debited but checkout showed the payment failed. Customer already retried once and restarted the app. "
                + "Card is in the customer's possession, not lost. Duplicate charge not yet ruled out.";
        boolean saved = store.remember(chat.caseId(), AGENT_ID, facts).isPresent();
        support.recordEvent(store, chat.caseId(), AGENT_ID, chat.transaction(), HANDOFF, null,
                "Escalate case " + chat.caseId() + " to a payments specialist", facts);
        ledger.step("Vendor chatbot", "escalate",
                support.writeOutcome(chat.caseId(), AGENT_ID, chat.store(), saved, "the established facts") + "; escalated with a ticket note.",
                support.effort("support-frontline", "escalate"), StoryLedger.Kind.CONTEXT);
        return new TicketNote(chat.caseId(), chat.store(), "Customer says card payment failed but they were charged. Please investigate.");
    }

    @Condition(name = "chatCaptured")
    public boolean chatCaptured(ChatCaptured chat) {
        return chat != null;
    }

    @Condition(name = "escalated")
    public boolean escalated(TicketNote note) {
        return note != null;
    }
}
