package meko.console.mcp;

import java.util.List;
import java.util.Map;

/**
 * One tool invocation, with everything needed to debug it.
 * <p>
 * The raw response is kept alongside the parsed one on purpose. Every hard bug in the sibling
 * SRE demo came down to "what exactly did the server send back", and a console that hides that
 * is a console that cannot answer the question it exists for.
 */
public record McpCall(
        String tool,
        /* The resolved tool name as registered, which is namespaced by the connection. */
        String resolvedTool,
        Map<String, Object> arguments,
        boolean ok,
        /* Server-side error text, when the response is an error payload rather than a result. */
        String error,
        String raw,
        /* Text leaves of the response, one per line — usually what you actually want to read. */
        String text,
        /* Rows, when the response carried a recognisable collection. */
        List<MekoRow> rows,
        long millis) {

    public static McpCall failed(String tool, Map<String, Object> arguments, String error) {
        return new McpCall(tool, null, arguments, false, error, null, null, List.of(), 0);
    }
}
