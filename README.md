# Meko micro demos

Small, self-contained Spring Boot apps that show what [Meko](https://mekodata.ai/) adds to AI agents: memory,
knowledge and decision traces that persist across runs and are shared across agents and system boundaries. Three of the
demos plan with [Embabel](https://github.com/embabel/embabel-agent) GOAP, one of them on [YugabyteDB AMP](https://www.yugabyte.com/amp/)
branches; the fourth is a console for Meko's MCP tools.

Each demo is its own Maven project with its own README. Start there for the details.

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/srinivasa-vasu/meko-micro-demos?quickstart=1) Try any of them in the cloud: nothing to install (see [In the cloud](#in-the-cloud)).

| Demo | What it shows | Port |
|---|---|---|
| [`goap-ctx-agent`](goap-ctx-agent/README.md) | Five agent workflows across system boundaries, run side by side without Meko, with your own database, and with Meko. The same agents and the same GOAP planner; only the context changes. | 8080 |
| [`support-ctx-agent`](support-ctx-agent/README.md) | An AI SRE incident commander. Memory is consulted first and decides how much telemetry is read, which model tier is used, and whether a model is needed at all. Every run reports its cost against the no-memory baseline. | 8081 |
| [`release-ctx-agent`](release-ctx-agent/README.md) | Meko × YugabyteDB AMP workshop, two use cases. Rehearse before you release: an agent branches `main` on AMP (no ticket, zero-copy) and rehearses a pull request's migrations using the standard and other teams' incidents and critical queries from Meko. Undo a bad batch job: an agent branches `main` as of the scheduler's recorded position and repairs only the rows the job changed. Every decision is on a record in Meko. | 8082 |
| [`meko-console`](meko-console/README.md) | A debugging console for Meko's MCP surface. Call any tool and see exactly what was sent and what came back. | 8090 |

Each demo has its own port, so all of them can run at once. All of them read the Meko key from `MEKO_API_KEY`.

## Requirements

- Java 21+ and Maven
- Spring Boot 4.1.1; Embabel Agent 1.5.1 (`goap-ctx-agent`, `support-ctx-agent`)
- A Meko API key and datapack, for live Meko. `goap-ctx-agent` also runs offline with an in-memory stand-in.
- A model provider key for `support-ctx-agent` (OpenRouter by default, or Groq or Gemini)

## Quick start

Every demo reaches Meko through one shared project, `meko-client`; `goap-ctx-agent` and `release-ctx-agent` also share
`workshop-kit` (the workshop pages' layout and navigation). Install them once from the repository root, and again after
pulling changes to them:

```bash
mvn -q install -DskipTests -pl meko-client,workshop-kit
```

```bash
# Runs offline; "With Meko" uses an in-memory stand-in
cd goap-ctx-agent
mvn spring-boot:run
open http://localhost:8080
```

```bash
cd support-ctx-agent
export MEKO_API_KEY=mko_tkn_...
export MEKO_DATAPACK_ID=...            # or MEKO_DATAPACK_NAME
export OPENROUTER_API_KEY=...
mvn spring-boot:run
open http://localhost:8081
```

```bash
cd meko-console
export MEKO_API_KEY=...
mvn spring-boot:run
open http://localhost:8090
```

Live Meko setup for each demo (knowledge base documents to upload, seeding the datapack, model profiles) is in that
demo's README.

## In the cloud

[Open the repository in GitHub Codespaces](https://codespaces.new/srinivasa-vasu/meko-micro-demos?quickstart=1). The
codespace comes with Java 21 and Maven, and installs the shared `meko-client` and `workshop-kit` as it starts. While it
is created, GitHub offers optional secrets: `MEKO_API_KEY` (live Meko, and AMP for `release-ctx-agent`), `AMP_PASSWORD`,
and a model key for the provider you run with: `GROQ_API_KEY` (the default for `goap-ctx-agent`'s model mode),
`OPENROUTER_API_KEY` (the default for `support-ctx-agent`) or `GOOGLE_API_KEY` (Gemini). Add them later under *Settings → Codespaces → Secrets*
on GitHub, then rebuild the codespace.

In the codespace's terminal, start a demo as in the quick start, without the install step:

```bash
cd goap-ctx-agent && mvn spring-boot:run      # runs offline; no key needed
```

Codespaces forwards the port and offers to open the page in the browser. The ports are private to your GitHub account
unless you choose to share them. Each demo's README has its own options.

For `release-ctx-agent`, AMP's endpoints admit the codespace's own public address, which the page detects: the app, in
the codespace, is what connects to AMP. A codespace that stops and starts again may come back with another address;
endpoints created before then still admit only the old one, so create the cluster again, or give it the new address.

## Repository layout

```
.devcontainer/       The GitHub Codespaces (dev container) setup: Java 21, Maven, the four ports
pom.xml              Builds the projects that share code together (mvn -pl goap-ctx-agent -am spring-boot:run)
meko-client/         The Meko MCP client every demo shares: connection, key, timeouts, tool calls
workshop-kit/        The workshop pages' shell they share: top bar, steps sidebar, navigation, theme
goap-ctx-agent/      Embabel GOAP stories, with and without Meko
release-ctx-agent/   Meko × AMP: rehearse a release, undo a bad batch job
support-ctx-agent/   Incident commander: memory-first planning and cost reporting
meko-console/        Meko MCP debugging console
```

In the two agent demos, `src/main/resources/kb/` holds the fictional runbooks, policies and standards the demos upload to a
Meko knowledge base.
