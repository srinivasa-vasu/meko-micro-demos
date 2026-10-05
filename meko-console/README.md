# Meko console

A debugging console for Meko's MCP surface. Call any tool, and see **exactly what was sent and
exactly what came back** — the arguments, the resolved tool name, the raw body, the text leaves
and the parsed rows, all on one screen.

That emphasis is deliberate. Building the sibling SRE demo, every hard bug was an argument
contract or a response shape, and none of them were visible from the tool schemas. A console
that shows only the parsed result cannot answer the question it exists for.

## Run it

```bash
export MEKO_APIKEY=...                 # required
export MEKO_MCP_URL=https://mcp.mekodata.ai/mcp   # optional, this is the default
mvn spring-boot:run
```

Then open <http://localhost:8090>. Port is `PORT`, agent id is `MEKO_AGENT_ID`.

## Operations

| Operation | Tool | Notes |
|---|---|---|
| Search memory | `memory_search` | Hybrid ranking. Compare `score` against `raw_similarity_score`. |
| List memories | `memory_get_all` | Exact enumeration, private or promoted collection. |
| Add memory | `memory_add` | Stored verbatim. Can write as another agent. |
| Update memory | `memory_update` | Overwrite a row in place. |
| Delete memory | `memory_delete_by_id` | Permanent. |
| Delete ALL memories | `memory_delete_all` | No undo. Requires retyping the datapack id. |
| Promote to shared | `memory_promote` | One way, and permission-gated. |
| Search knowledge base | `knowledgebase_search` | Documents and promoted memories, chunked. |
| List conversations | `conversation_list` | Most recently updated first. |
| Create conversation | `conversation_create` | `session_id` is a honoured dedup key. |
| Get conversation | `conversation_get` | Metadata, or the full turn history. |
| Add message | `conversation_add_message` | One turn: input + output, with optional plan and reasoning. |
| Update conversation | `conversation_update` | Title and metadata; omitted fields are kept. |
| Delete conversation | `conversation_delete` | Hard delete of every trace under it. No undo. |
| Raw tool call | anything | Arguments sent verbatim. Nothing added. |

## What this encodes

Each of these was learned by watching a call fail in a way that looked like something else.
None is discoverable from the schemas.

- **`conversation_id` is required** by `memory_*`, `knowledgebase_search` **and** `datapack_list`,
  even where the schema gives it a default and lists nothing as required. Hence the bootstrap
  conversation: `datapack_id` is genuinely optional on `conversation_create`, which is the only
  reason `datapack_list` can be called before a datapack is known.
- **`datapack_list` rejects `agent_id`** with `unexpected_keyword_argument`. The `datapack_*`
  family takes `conversation_id` and `datapack_id` only.
- **`memory_get_all` accepts no `page`.** Sending one throws.
- **Unknown arguments are rejected, never ignored** — and the resulting exception, caught and
  turned into an empty list, is indistinguishable from an empty datapack.
- **The payload field differs by endpoint**: `memory` for `memory_search`, `data` for
  `memory_get_all`, `chunk_text` for `knowledgebase_search`. The extractor tries known names and
  then falls back to the longest non-metadata string, so a rename downstream degrades to "still
  works" rather than to "the datapack looks empty".
- **`agent_id` means three different things**, and the difference decides whether you get a
  row at all:
  - `memory_search` — trace attribution only; it does **not** filter results.
  - `conversation_get` / `_update` / `_delete` — an **ownership filter**. A conversation created
    by a different agent comes back as "not found", which is indistinguishable from it never
    having existed. The console shows which agent it is acting as for exactly this reason.
  - `datapack_list` — rejected outright.
- **`plan` is an array, `metadata` is a JSON string** on `conversation_add_message`. Easy to send
  in the other's shape, and neither is coerced.
- **`conversation_list` is the one tool where `conversation_id` is genuinely optional.** Omitting
  it gives the call its own ephemeral trace instead of attributing a listing to an unrelated
  conversation.
- **An error is valid JSON with zero rows.** Without an explicit check, a refused call and an
  empty result read identically — which sends you to debug the wrong thing.

## The raw call is the point

Typed forms are conveniences. When a tool behaves unlike its schema, the raw call is what settles
it: send an argument set, read the server's own words back. Two of the contracts above were found
that way after several wrong theories.

A useful first probe — does search retrieve on an exact token?

```json
{ "tool": "memory_search",
  "arguments": { "query": "INC-1842", "conversation_id": "<from a datapack call>",
                 "datapack_id": "<id>", "limit": 5 } }
```

If no returned row contains `INC-1842`, ranking is not doing exact-token matching for that query
shape. Try `"INCIDENT_RECORD INC-1842"` — a short, type-led query behaves very differently from
a distinctive token buried in a long sentence.

## Tests

`mvn test` — ten tests pinning response handling against payloads captured from a live datapack:
every collection shape, both transports, an unknown payload field, and an error body that must
not be mistaken for an empty result.
