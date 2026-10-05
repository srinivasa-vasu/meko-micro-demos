package meko.console.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The conversation arguments whose SHAPE is easy to get backwards.
 *
 * On conversation_add_message, `plan` is a real array of strings while `metadata` is a JSON
 * string. Either sent in the other's shape is rejected, not coerced — so the form takes a plain
 * line-per-action textarea and the conversion happens server-side, rather than asking whoever is
 * using the console to remember which is which.
 */
class ConversationArgumentTest {

    private MekoController.Message message(String plan) {
        return new MekoController.Message("dp", "conv", "in", "out", null, plan, null, null);
    }

    @Test
    void planBecomesAnArrayOfTrimmedActions() {
        var lines = message("  Check the indexer\nRestart it if hung\n").planLines();

        assertEquals(List.of("Check the indexer", "Restart it if hung"), lines);
    }

    @Test
    void blankLinesAreDroppedRatherThanSentAsEmptyActions() {
        assertEquals(List.of("One", "Two"), message("One\n\n   \nTwo\n").planLines());
    }

    @Test
    void noPlanIsAnEmptyListNotNull() {
        // An empty list is omitted from the request entirely; null would serialise as
        // "plan": null, which is a different thing to send.
        assertTrue(message(null).planLines().isEmpty());
        assertTrue(message("   ").planLines().isEmpty());
    }

    @Test
    void deletingWithoutMatchingConfirmationIsRefusedBeforeItLeaves() {
        var controller = new MekoController(null);
        var body = new MekoController.Conversation("dp", "conv-1", null, null, null, "conv-2");

        var call = controller.deleteConversation(body);

        assertFalse(call.ok());
        assertTrue(call.error().contains("Nothing was deleted"), call.error());
        // It never reached the client: a null MekoClient would have thrown otherwise.
    }

    @Test
    void deletingWithMatchingConfirmationIsAttempted() {
        var controller = new MekoController(null);
        var body = new MekoController.Conversation("dp", "conv-1", null, null, null, "conv-1");

        try {
            controller.deleteConversation(body);
        } catch (NullPointerException reachedTheClient) {
            return;
        }
        throw new AssertionError("a matching confirmation should let the call through");
    }
}
