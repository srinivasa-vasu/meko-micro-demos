package meko.console.api;

import meko.console.mcp.McpCall;
import meko.console.mcp.MekoClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Every endpoint returns the full McpCall, not just the useful part.
 * <p>
 * The arguments sent, the resolved tool name, the raw body and the parsed rows all travel to the
 * browser together. That is the whole reason this app exists: a console that shows you only the
 * parsed result cannot tell you why the parse was empty.
 */
@RestController
@RequestMapping("/api/meko")
public class MekoController {

    private final MekoClient meko;

    public MekoController(MekoClient meko) {
        this.meko = meko;
    }

    @GetMapping("/status")
    public Status status() {
        var tools = meko.connected() ? meko.tools() : List.<String>of();
        return new Status(meko.connected(), tools.size(), tools, meko.agentId());
    }

    public record Status(boolean connected, int toolCount, List<String> tools, String agentId) {}

    @GetMapping("/datapacks")
    public McpCall datapacks() {
        return meko.listDatapacks();
    }

    @GetMapping("/datapack")
    public McpCall describe(@RequestParam String datapackId) {
        return meko.describeDatapack(datapackId);
    }

    @PostMapping("/memory/search")
    public McpCall searchMemory(@RequestBody Query body) {
        return meko.searchMemory(body.datapackId(), body.query(), body.limitOrDefault());
    }

    @GetMapping("/memory/all")
    public McpCall allMemories(@RequestParam String datapackId,
                               @RequestParam(defaultValue = "false") boolean promoted) {
        return meko.allMemories(datapackId, promoted);
    }

    @PostMapping("/memory/add")
    public McpCall addMemory(@RequestBody Write body) {
        return meko.addMemory(body.datapackId(), body.text(), body.asAgent());
    }

    @PostMapping("/memory/update")
    public McpCall updateMemory(@RequestBody Write body) {
        return meko.updateMemory(body.datapackId(), body.memoryId(), body.text(), body.asAgent());
    }

    @PostMapping("/memory/delete")
    public McpCall deleteMemory(@RequestBody Write body) {
        return meko.deleteMemory(body.datapackId(), body.memoryId(), body.asAgent());
    }

    /**
     * Wipe the datapack's memories.
     * <p>
     * Guarded by a typed confirmation rather than a boolean flag: a stray `true` in a JSON body
     * is far too easy, and this is the one call with no undo. The caller must echo the datapack
     * id back, which cannot happen by accident.
     */
    @PostMapping("/memory/delete-all")
    public McpCall deleteAll(@RequestBody DeleteAll body) {
        if (body.confirmDatapackId() == null
                || !body.confirmDatapackId().equals(body.datapackId())) {
            return McpCall.failed("memory_delete_all", Map.of("datapack_id", body.datapackId()),
                    "Not confirmed: confirmDatapackId must equal datapackId. Nothing was deleted.");
        }
        return meko.deleteAllMemories(body.datapackId(), body.asAgent());
    }

    public record DeleteAll(String datapackId, String confirmDatapackId, String asAgent) {}

    /** One way, and permission-gated. The UI asks before calling this. */
    @PostMapping("/memory/promote")
    public McpCall promote(@RequestBody Promote body) {
        return meko.promoteMemories(body.datapackId(), body.memoryIds(), body.asAgent());
    }

    @PostMapping("/knowledge/search")
    public McpCall searchKnowledge(@RequestBody Query body) {
        return meko.searchKnowledge(body.datapackId(), body.query(), body.limitOrDefault(),
                body.asAgent());
    }

    // ------------------------------------------------------------------
    // Conversations
    // ------------------------------------------------------------------

    @GetMapping("/conversations")
    public McpCall listConversations(@RequestParam String datapackId,
                                     @RequestParam(defaultValue = "20") int limit,
                                     @RequestParam(defaultValue = "0") int offset) {
        return meko.listConversations(datapackId, limit, offset);
    }

    @PostMapping("/conversation/create")
    public McpCall createConversation(@RequestBody Conversation body) {
        return meko.createConversation(body.datapackId(), body.title(), body.sessionId(),
                body.asAgent());
    }

    @GetMapping("/conversation")
    public McpCall getConversation(@RequestParam String datapackId,
                                   @RequestParam String conversationId,
                                   @RequestParam(defaultValue = "true") boolean includeMessages,
                                   @RequestParam(defaultValue = "50") int limit,
                                   @RequestParam(defaultValue = "0") int offset,
                                   @RequestParam(required = false) String asAgent) {
        return meko.getConversation(datapackId, conversationId, includeMessages, limit, offset,
                asAgent);
    }

    @PostMapping("/conversation/message")
    public McpCall addMessage(@RequestBody Message body) {
        return meko.addMessage(body.datapackId(), body.conversationId(), body.input(),
                body.output(), body.reasoning(), body.planLines(), body.metadata(), body.seed(),
                body.asAgent());
    }

    @PostMapping("/conversation/update")
    public McpCall updateConversation(@RequestBody Conversation body) {
        return meko.updateConversation(body.datapackId(), body.conversationId(),
                body.title(), body.metadata(), body.asAgent());
    }

    /** Hard delete, no undo. Confirmed the same way memory_delete_all is. */
    @PostMapping("/conversation/delete")
    public McpCall deleteConversation(@RequestBody Conversation body) {
        if (body.confirmConversationId() == null
                || !body.confirmConversationId().equals(body.conversationId())) {
            return McpCall.failed("conversation_delete",
                    Map.of("conversation_id", String.valueOf(body.conversationId())),
                    "Not confirmed: confirmConversationId must equal conversationId. "
                            + "Nothing was deleted.");
        }
        return meko.deleteConversation(body.datapackId(), body.conversationId(), body.asAgent());
    }

    /** The conversation this console attaches to every other call. */
    @GetMapping("/conversation/active")
    public Map<String, String> activeConversation(@RequestParam String datapackId) {
        return Map.of("datapackId", datapackId,
                "conversationId", meko.activeConversation(datapackId),
                "agentId", meko.agentId());
    }

    public record Conversation(String datapackId, String conversationId, String title,
                               String sessionId, String metadata, String confirmConversationId,
                               String asAgent) {}

    public record Message(String datapackId, String conversationId, String input, String output,
                          String reasoning, String plan, String metadata, String seed,
                          String asAgent) {
        /**
         * plan is a real ARRAY of strings on the wire, while metadata is a JSON STRING.
         * Easy to get backwards, and either shape in the other's place is rejected — so the
         * form takes one line per action and the conversion happens here.
         */
        List<String> planLines() {
            if (plan == null || plan.isBlank()) {
                return List.of();
            }
            return plan.lines().map(String::trim).filter(l -> !l.isEmpty()).toList();
        }
    }

    /**
     * Call any registered tool with arbitrary arguments, sent verbatim.
     * <p>
     * The most useful endpoint here. Tool schemas have been wrong about what is required and
     * what is accepted often enough that being able to try an argument set and see the server's
     * own words back is worth more than any typed wrapper.
     */
    @PostMapping("/raw")
    public McpCall raw(@RequestBody RawCall body) {
        return meko.callRaw(body.tool(), body.arguments() == null ? Map.of() : body.arguments());
    }

    /** asAgent is only honoured by knowledgebase_search; memory_search ignores agent_id. */
    public record Query(String datapackId, String query, Integer limit, String asAgent) {
        int limitOrDefault() {
            return limit == null || limit < 1 ? 10 : limit;
        }
    }

    public record Write(String datapackId, String memoryId, String text, String asAgent) {}

    public record Promote(String datapackId, List<String> memoryIds, String asAgent) {}

    public record RawCall(String tool, Map<String, Object> arguments) {}
}
