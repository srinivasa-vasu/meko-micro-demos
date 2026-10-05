# Meko console

A debugging console for Meko's MCP surface. Call any tool, and see **exactly what was sent and
exactly what came back** — the arguments, the resolved tool name, the raw body, the text leaves
and the parsed rows, all on one screen.

That emphasis is deliberate. Building the sibling SRE demo, every hard bug was an argument
contract or a response shape, and none of them were visible from the tool schemas. A console
that shows only the parsed result cannot answer the question it exists for.

## Run it

```bash
export MEKO_API_KEY=...                 # required
export MEKO_MCP_URL=https://mcp.mekodata.ai/mcp   # optional, this is the default
mvn spring-boot:run
```

Then open <http://localhost:8090>. Port is `PORT`, agent id is `MEKO_AGENT_ID`.

## Operations

Pick a datapack in the sidebar first: every typed operation runs against it. The console then
fills in `datapack_id`, and also `conversation_id` and `agent_id` where the tool needs them, so
the form only asks for what you have to decide.

**Bold** marks a field you must fill in. *as agent* is the optional **Act as agent** field: blank
means the console's own agent, and whatever you enter is sent as `agent_id`. The *sent on the wire* column shows the tool's full
argument set, with ⚙ marking what the console adds. Open *Arguments sent* above any result to
see the exact JSON.

### Memory

| Operation | Tool | You fill in | Sent on the wire |
|---|---|---|---|
| Search memory | `memory_search` | **query**, limit (10) | `query`, `limit`, `datapack_id`⚙, `conversation_id`⚙, `agent_id`⚙ |
| List memories | `memory_get_all` | collection: private / promoted | `promoted`, `datapack_id`⚙, `conversation_id`⚙, `agent_id`⚙. There is no `page`; sending one throws. |
| Add memory | `memory_add` | **text**, write-as agent (default: the console) | `text`, `datapack_id`⚙, `conversation_id`⚙, `agent_id` |
| Update memory | `memory_update` | **memory id**, **replacement text**, as agent | `memory_id`, `text`, `agent_id` (must be the writer), `datapack_id`⚙, `conversation_id`⚙ |
| Delete memory | `memory_delete_by_id` | **memory id**, as agent | `memory_id`, `agent_id` (must be the writer), `datapack_id`⚙, `conversation_id`⚙ |
| Promote to shared | `memory_promote` | **memory ids**, one per line, as agent | `memory_ids` (array), `agent_id`, `datapack_id`⚙, `conversation_id`⚙. One way. Needs owner or maintainer rights. |
| Delete ALL memories | `memory_delete_all` | **the datapack id, retyped**, as agent | `agent_id`, `datapack_id`⚙, `conversation_id`⚙. Deletes only that agent's memories, not the whole datapack. If the retyped id doesn't match, the server refuses and nothing reaches Meko. |

### Knowledge

| Operation | Tool | You fill in | Sent on the wire |
|---|---|---|---|
| Search knowledge base | `knowledgebase_search` | **query**, limit (5), as agent | `query`, `limit`, `agent_id` (the schema says it scopes the search), `datapack_id`⚙, `conversation_id`⚙ |

### Conversations

Except for `conversation_list` (see below), `conversation_id` here is the conversation you are
acting on, not the console's own.

| Operation | Tool | You fill in | Sent on the wire |
|---|---|---|---|
| List conversations | `conversation_list` | limit (20), offset (0) | `limit`, `offset`, `datapack_id`⚙, `agent_id`⚙. `conversation_id` is left out on purpose. |
| Create conversation | `conversation_create` | title, session id, as agent | `agent_id` (becomes the owner), plus `datapack_id`⚙, `title`, `session_id` when set. Nothing is required. |
| Get conversation | `conversation_get` | **conversation id**, messages (include), message limit (50), as agent | `conversation_id`, `include_messages`, `limit`, `offset`, `agent_id`, `datapack_id`⚙ |
| Add message | `conversation_add_message` | **conversation id**, **user input**, **assistant output**, reasoning, plan, seed, as agent | `conversation_id`, `input`, `output`, `agent_id` (must be the creator), `datapack_id`⚙, plus `reasoning`, `plan` (array), `seed` when set |
| Update conversation | `conversation_update` | **conversation id**, new title, new metadata (JSON string), as agent | `conversation_id`, `agent_id`, `datapack_id`⚙, plus `title`, `metadata` when set. Fields you leave out are kept. |
| Delete conversation | `conversation_delete` | **conversation id**, **the id, retyped**, as agent | `conversation_id`, `agent_id`, `datapack_id`⚙. If the retyped id doesn't match, the server refuses. |

### Where `agent_id` filters

According to the tool schemas, `agent_id` decides **which rows a call can reach** on most tools,
not just how the call is attributed. Act as the wrong agent and the call answers "not found" or
does nothing, which looks the same as the row not existing.

| `agent_id` filters or must match | `agent_id` is trace attribution only |
|---|---|
| `memory_update`, `memory_delete_by_id` (must be the writer) | `memory_search` (searches every agent) |
| `memory_delete_all` (deletes only that agent's memories) | `memory_get_all` (lists every agent) |
| `memory_promote` | `conversation_list` |
| `memory_add` (picks the store the memory lands in) | |
| `knowledgebase_search` (the schema says it scopes the search) | |
| `conversation_create` (sets the owner) | |
| `conversation_get`, `_update`, `_delete` (ownership filter) | |
| `conversation_add_message` (must be the creator) | |

Every tool in the left column has an **Act as agent** field (**Write as agent** on Add memory).
To work on another agent's rows, copy the agent from the *Written by* column of a search or
listing.

### Datapack (sidebar)

| Operation | Tool | You fill in | Sent on the wire |
|---|---|---|---|
| Reload | `datapack_list` | nothing | `conversation_id`⚙ only. It rejects `agent_id`. |
| Describe | `datapack_describe` | the selected datapack | `datapack_id`, `include_status: true`, `conversation_id`⚙ |

### Raw

| Operation | Tool | You fill in | Sent on the wire |
|---|---|---|---|
| Raw tool call | any registered tool | **tool name**, arguments (JSON object) | Exactly what you typed. Nothing is added, not even `conversation_id`, so supply whatever the tool needs yourself. |

A tool name matches with or without Spring AI's connection prefix. An unknown name fails and
lists the registered tools.

### Use the width, and the keyboard

On screens 1500px and wider, the form and the result sit side by side, with the sidebar and form
pinned, so you can change an argument and read the response without scrolling. The icon at the
top right cycles the theme (auto → light → dark). The keyboard icon, or <kbd>?</kbd>, lists the
shortcuts:

| Key | Action |
|---|---|
| <kbd>⌘/Ctrl</kbd> + <kbd>Enter</kbd> | Run the selected operation |
| <kbd>j</kbd> / <kbd>k</kbd> | Next / previous operation |
| <kbd>/</kbd> | Focus the first field |
| <kbd>1</kbd> <kbd>2</kbd> <kbd>3</kbd> | Rows / text leaves / raw view |
| <kbd>h</kbd> | Step back through history, one call per press |
| <kbd>d</kbd> | Focus the datapack picker |
| <kbd>t</kbd> | Cycle theme |
| <kbd>?</kbd> | Show shortcuts |
| <kbd>Esc</kbd> | Leave a field, or close the shortcut list |

Single-key shortcuts are ignored while a field has focus, so typing a query never triggers them.

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
