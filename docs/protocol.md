# Harness Protocol Specification

JSON-RPC 2.0 over stdio protocol for managing Bootstrap agent threads, turns, and approvals.

## Transport

- **Encoding**: JSON-RPC 2.0
- **Channel**: stdin/stdout (newline-delimited JSON)
- **Logging**: All logs go to stderr or log files. stdout is exclusively for JSON-RPC.

## Methods

### `initialize`

Returns server capabilities and version.

**Params**: `{ clientInfo?: { name, version? } }` (optional)

**Result**:
```json
{
  "version": "1.0.0",
  "capabilities": {
    "threads": true,
    "turns": true,
    "approvals": true,
    "streaming": true,
    "persistence": true
  }
}
```

### `thread.create`

Create a new thread (maps to a Session).

**Params**: `{ title?: string, directory?: string }`

**Result**: `{ thread: Thread }`

### `thread.list`

List all threads.

**Params**: `{}` (optional)

**Result**: `{ threads: Thread[] }`

### `thread.get`

Get a thread with its full event history.

**Params**: `{ threadId: string }`

**Result**: `{ thread: Thread, events: Event[] }`

### `turn.start`

Start a new turn (prompt the agent). Returns immediately with a turnId; streaming results arrive as notifications.

**Params**:
```json
{
  "threadId": "ses_...",
  "input": [{ "type": "text", "text": "Run the tests" }],
  "model": { "providerID": "anthropic", "modelID": "claude-sonnet-4-20250514" },
  "agent": "code"
}
```

**Result**: `{ turnId: string }`

### `approval.respond`

Respond to an approval request.

**Params**: `{ requestId: string, decision: "once" | "always" | "reject" }`

**Result**: `{ ok: true }`

### `turn.cancel`

Cancel the active turn on a thread.

**Params**: `{ threadId: string }`

**Result**: `{ ok: true }`

## Notifications (Server to Client)

| Notification | Description |
|---|---|
| `thread.created` | A new thread was created |
| `turn.started` | A turn began executing |
| `turn.completed` | A turn finished successfully |
| `turn.error` | A turn ended with an error |
| `item.started` | A new item was created (message, tool call, approval) |
| `item.delta` | Streaming content for an item (text tokens, tool output) |
| `item.completed` | An item finished |
| `approval.requested` | The agent needs approval to proceed |

## Item Types

| Type | Description |
|---|---|
| `user_message` | User input text/files |
| `assistant_message` | Model-generated text or reasoning |
| `tool_exec` | Tool invocation (bash, read, edit, etc.) |
| `tool_log` | Streaming tool output |
| `approval` | Permission request/response |
| `artifact` | Generated artifact |

## Types

### Thread
```typescript
{
  threadId: string     // Same as Session ID
  title: string
  directory: string
  time: { created: number, updated: number }
}
```

### Turn
```typescript
{
  turnId: string
  threadId: string
  status: "running" | "completed" | "cancelled" | "error"
  time: { started: number, completed?: number }
}
```

### Item
```typescript
{
  itemId: string
  threadId: string
  turnId: string
  type: ItemType
  data?: Record<string, any>
}
```

## Persistence

Events are persisted to `.harness/threads/{threadId}/events.jsonl` as append-only JSONL. Thread metadata is stored in `.harness/threads/{threadId}/meta.json`.

Use `thread.get` to replay the full event history after a restart.

## Error Codes

| Code | Name | Description |
|---|---|---|
| -32700 | ParseError | Invalid JSON |
| -32600 | InvalidRequest | Invalid JSON-RPC request |
| -32601 | MethodNotFound | Unknown method |
| -32602 | InvalidParams | Invalid method parameters |
| -32603 | InternalError | Internal server error |
| -32001 | ThreadNotFound | Thread ID not found |
| -32002 | TurnBusy | Thread already has an active turn |
| -32003 | TurnNotFound | Turn ID not found |
| -32004 | ApprovalNotFound | Approval request ID not found |

## CLI Usage

```bash
# Start the harness server
bootstrap harness --cwd /path/to/repo

# Pipe a single request
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | bootstrap harness

# Use the demo client
bun src/harness/client.ts --cwd /path/to/repo "Run the tests"
```
