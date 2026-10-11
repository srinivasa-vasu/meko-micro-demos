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
        long millis,
        /* Rows per source, for tools that answer in labelled buckets (context_search). Empty
           otherwise. Kept apart from `rows` so one source can never pass for all of them. */
        Map<String, List<MekoRow>> buckets) {

    public McpCall(String tool, String resolvedTool, Map<String, Object> arguments, boolean ok,
                   String error, String raw, String text, List<MekoRow> rows, long millis) {
        this(tool, resolvedTool, arguments, ok, error, raw, text, rows, millis, Map.of());
    }

    public static McpCall failed(String tool, Map<String, Object> arguments, String error) {
        return new McpCall(tool, null, arguments, false, error, null, null, List.of(), 0);
    }

    /** The same call with its response split per source; `rows` becomes every bucket's rows. */
    public McpCall withBuckets(Map<String, List<MekoRow>> buckets) {
        var all = buckets.values().stream().flatMap(List::stream).toList();
        return new McpCall(tool, resolvedTool, arguments, ok, error, raw, text, all, millis,
                buckets);
    }

    /** The same call with these rows, for responses that are one bare object, not a list. */
    public McpCall withRows(List<MekoRow> rows) {
        return new McpCall(tool, resolvedTool, arguments, ok, error, raw, text, rows, millis,
                buckets);
    }
}
