# Juujarvis

AI family assistant running on a Mac Mini, powered by Claude.

The name honors the Juujarvi heritage -- a lake in Kemijärvi, Finnish Lapland, along the Kemijoki River. When the family emigrated to the US about a hundred years ago, they shortened the name to Jarv. Juujarvis reunites both halves while nodding to a certain famous AI butler.

## What it does

- Responds to iMessages (individual and group chats) using Claude
- Streams responses to a web UI via WebSocket
- Remembers conversations and learns about family members over time
- Searches the family Confluence wiki
- Manages family members dynamically through conversation
- Summarizes each day's conversations at 4am with follow-up tracking

## Tech stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin |
| Framework | Spring Boot 3.3 |
| AI | Claude via anthropic-java 2.18.0 |
| Database | SQLite (juujarvis.db) |
| iMessage | chat.db polling + AppleScript |
| Wiki | Confluence Cloud (Atlassian REST API) |
| Build | Gradle 9.x (Kotlin DSL), JDK 21 |

## Setup

1. Clone the repo
2. Copy config and fill in secrets:
   ```
   cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
   ```
3. Required secrets in `application-local.yml`:
   - `juujarvis.anthropic.api-key` -- Anthropic API key
   - `juujarvis.confluence.*` -- Atlassian credentials (optional)
4. Grant Terminal/IDE **Full Disk Access** in System Settings (needed to read `chat.db`)
5. Run:
   ```
   ./gradlew bootRun
   ```
6. Open http://localhost:8080

## Configuration

Key settings in `application.yml`:

| Property | Default | Description |
|----------|---------|-------------|
| `juujarvis.anthropic.api-key` | (env) | Anthropic API key |
| `juujarvis.anthropic.model` | claude-sonnet-4-20250514 | Claude model ID |
| `juujarvis.imessage.polling-enabled` | true | Set to false when using an external iMessage bridge |
| `juujarvis.confluence.base-url` | (env) | Atlassian instance URL |
| `juujarvis.db-path` | juujarvis.db | SQLite database path |

## Inbound message API

External bridges (iMessage, Signal, email) can POST messages to `POST /api/inbound`. See the [OpenAPI spec](src/main/resources/static/openapi.yml) for the full contract.

```bash
curl -X POST http://localhost:8080/api/inbound \
  -H "Content-Type: application/json" \
  -d '{"channel":"IMESSAGE","sender":"roose@iki.fi","text":"Hello Juujarvis"}'
```

## Tools available to Claude

| Tool | Description |
|------|-------------|
| `send_message` | Send a message to a family member |
| `read_messages` | Fetch recent iMessage history |
| `manage_calendar` | Calendar operations (stub -- Google Calendar planned) |
| `search_confluence` | Search/read Confluence wiki pages |
| `manage_user` | Add, update, remove family members |
| `update_person_profile` | Save observations about a person |

## Project structure

```
src/main/kotlin/com/juujarvis/
  controller/        REST endpoints
  messaging/         Channel providers (iMessage)
  model/             Data models
  service/           Core services (Assistant, ConversationStore, etc.)
  tool/              Claude tool implementations
```
