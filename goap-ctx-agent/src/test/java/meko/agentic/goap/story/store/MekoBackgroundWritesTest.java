package meko.agentic.goap.story.store;

import meko.agentic.goap.context.MekoMcpClient;
import meko.agentic.goap.context.MekoProperties;
import meko.agentic.goap.story.RunEvents;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Background writes against a Meko that answers slowly, as the live one does (a conversation turn takes about half a second). */
class MekoBackgroundWritesTest {
    static final long CALL_MILLIS = 200;

    /** A fake Meko: every call takes CALL_MILLIS; conversations keep the turns they were sent, in arrival order. */
    static class SlowMeko extends MekoMcpClient {
        final Map<String, List<Map<String, Object>>> conversations = new ConcurrentHashMap<>();
        final List<String> memories = java.util.Collections.synchronizedList(new ArrayList<>());
        private final ObjectMapper json = new ObjectMapper();

        SlowMeko() { super(new MekoProperties()); }

        @Override public String conversation(String key, String title, Map<String, Object> metadata) { return "conv-" + key; }
        @Override public String datapack() { return "pack"; }

        @Override
        public JsonNode callTool(String tool, Map<String, Object> args) throws Exception {
            Thread.sleep(CALL_MILLIS);
            String conversation = String.valueOf(args.get("conversation_id"));
            return switch (tool) {
                case "conversation_add_message" -> {
                    conversations.computeIfAbsent(conversation, k -> java.util.Collections.synchronizedList(new ArrayList<>()))
                            .add(Map.of("input", args.get("input"), "output", args.get("output")));
                    yield json.readTree("{}");
                }
                case "conversation_get" -> json.valueToTree(Map.of("messages", conversations.getOrDefault(conversation, List.of())));
                case "memory_add" -> {
                    memories.add(String.valueOf(args.get("text")));
                    yield json.readTree("{\"results\":[{\"id\":\"m-" + memories.size() + "\"}]}");
                }
                default -> json.readTree("{}");
            };
        }
    }

    @Test
    void conversationTurnsAreWrittenInTheBackgroundInOrderAndReadAfterTheyLand() {
        SlowMeko meko = new SlowMeko();
        ReceiptLog receipts = new ReceiptLog(new RunEvents());
        MekoContextStore store = new MekoContextStore(meko, receipts, new OutageSwitch());

        long start = System.nanoTime();
        for (int i = 1; i <= 5; i++) store.addTurn("CASE-1", "agent:a", "TX-1", "input " + i, "output " + i);
        long queuedMillis = (System.nanoTime() - start) / 1_000_000;
        // Five turns at 200 ms each would block for a second; queued, the agent carries on at once.
        assertThat(queuedMillis).isLessThan(CALL_MILLIS);

        // A read of the conversation waits for its pending turns, and sees them in the order they were written.
        List<ContextStore.Turn> turns = store.transcript("CASE-1", "agent:b", "TX-1");
        assertThat(turns).extracting(ContextStore.Turn::input).containsExactly("input 1", "input 2", "input 3", "input 4", "input 5");
    }

    @Test
    void resultsWrittenBackLaterAreCompleteWhenTheRunFlushes() {
        SlowMeko meko = new SlowMeko();
        ReceiptLog receipts = new ReceiptLog(new RunEvents());
        MekoContextStore store = new MekoContextStore(meko, receipts, new OutageSwitch());

        long start = System.nanoTime();
        store.rememberLater("CASE-2", "agent:a", "Case CASE-2 resolved");
        store.addTurn("CASE-2", "agent:a", "TX-2", "in", "out");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(CALL_MILLIS);

        store.flush("CASE-2");
        assertThat(meko.memories).containsExactly("Case CASE-2 resolved");
        // Every background write has its receipt once the run flushes, so the run's counts are complete.
        assertThat(receipts.forCase("CASE-2")).extracting(ReceiptLog.Receipt::tool).containsExactlyInAnyOrder("memory_add", "conversation_add_message");
        assertThat(receipts.forCase("CASE-2")).allMatch(ReceiptLog.Receipt::ok);
    }
}
