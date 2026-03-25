import { Bus } from "@/bus"
import { Identifier } from "@/id/id"
import { Log } from "@/util/log"
import { SessionPrompt } from "@/session/prompt"
import { Provider } from "@/provider/provider"
import { Agent } from "@/agent/agent"
import { Protocol } from "./protocol"
import { JsonRpc } from "./jsonrpc"
import { ThreadManager } from "./thread-manager"
import { EventLog } from "./event-log"

export namespace TurnRunner {
  const log = Log.create({ service: "harness-turn-runner" })

  export async function start(input: {
    turnId: string
    threadId: string
    sessionID: string
    directory: string
    parts: Protocol.TurnStartParams["input"]
    model?: { providerID: string; modelID: string }
    agent?: string
    transport: JsonRpc.Transport
  }) {
    const { turnId, threadId, sessionID, directory, parts, transport } = input

    const turn = ThreadManager.startTurn(turnId, threadId)

    // Emit turn.started
    const turnStartedEvent = {
      notification: Protocol.Notifications.TURN_STARTED,
      turnId,
      threadId,
      time: turn.timeStarted,
    }
    transport.send(JsonRpc.notification(Protocol.Notifications.TURN_STARTED, turnStartedEvent))
    await EventLog.append(directory, threadId, turnStartedEvent)

    // Create user_message item
    const userItemId = Identifier.ascending("item")
    const userItem: Protocol.Item = {
      itemId: userItemId,
      threadId,
      turnId,
      type: "user_message",
      data: { parts },
    }
    turn.items.set(userItemId, userItem)

    const userStartedEvent = {
      notification: Protocol.Notifications.ITEM_STARTED,
      item: userItem,
    }
    transport.send(JsonRpc.notification(Protocol.Notifications.ITEM_STARTED, userStartedEvent))
    await EventLog.append(directory, threadId, userStartedEvent)

    const userCompletedEvent = {
      notification: Protocol.Notifications.ITEM_COMPLETED,
      itemId: userItemId,
      threadId,
      turnId,
    }
    transport.send(JsonRpc.notification(Protocol.Notifications.ITEM_COMPLETED, userCompletedEvent))
    await EventLog.append(directory, threadId, userCompletedEvent)

    // Subscribe to bus events, filtering by sessionID
    const activeItems = new Map<string, string>() // partID/callID → itemId

    const unsubscribe = Bus.subscribeAll((event) => {
      handleBusEvent(event, {
        turnId,
        threadId,
        sessionID,
        directory,
        transport,
        turn,
        activeItems,
      }).catch((e) => log.error("failed to handle bus event", { error: e }))
    })
    turn.unsubscribe = unsubscribe

    // Resolve model
    const model = input.model ?? (await resolveModel())
    const agent = input.agent ?? (await Agent.defaultAgent())

    // Convert input parts to SessionPrompt format
    const promptParts: SessionPrompt.PromptInput["parts"] = parts.map((p) => {
      if (p.type === "text") {
        return { type: "text" as const, text: p.text }
      }
      return { type: "file" as const, url: p.url, filename: p.filename, mime: p.mime }
    })

    // Call the existing agent loop
    try {
      await SessionPrompt.prompt({
        sessionID,
        model,
        agent,
        parts: promptParts,
      })

      ThreadManager.completeTurn(turnId, "completed")
      const completedEvent = {
        notification: Protocol.Notifications.TURN_COMPLETED,
        turnId,
        threadId,
        status: "completed",
        time: Date.now(),
      }
      transport.send(JsonRpc.notification(Protocol.Notifications.TURN_COMPLETED, completedEvent))
      await EventLog.append(directory, threadId, completedEvent)
    } catch (e: any) {
      log.error("turn error", { error: e, turnId })
      const status = e?.name === "MessageAbortedError" ? "cancelled" : "error"
      ThreadManager.completeTurn(turnId, status)
      const errorEvent = {
        notification: Protocol.Notifications.TURN_ERROR,
        turnId,
        threadId,
        status,
        error: e?.message ?? String(e),
        time: Date.now(),
      }
      transport.send(JsonRpc.notification(Protocol.Notifications.TURN_ERROR, errorEvent))
      await EventLog.append(directory, threadId, errorEvent)
    }
  }

  async function handleBusEvent(
    event: { type: string; properties: any },
    ctx: {
      turnId: string
      threadId: string
      sessionID: string
      directory: string
      transport: JsonRpc.Transport
      turn: ThreadManager.TurnState
      activeItems: Map<string, string>
    },
  ) {
    const { turnId, threadId, sessionID, directory, transport, turn, activeItems } = ctx

    switch (event.type) {
      case "message.part.updated": {
        const props = event.properties
        const part = props.part
        if (!part || part.sessionID !== sessionID) return

        if (part.type === "text" && props.delta && part.synthetic !== true) {
          // Text delta → assistant_message item.delta
          let itemId = activeItems.get(part.id)
          if (!itemId) {
            itemId = Identifier.ascending("item")
            activeItems.set(part.id, itemId)
            const item: Protocol.Item = {
              itemId,
              threadId,
              turnId,
              type: "assistant_message",
              data: { partId: part.id },
            }
            turn.items.set(itemId, item)
            const startedEvent = {
              notification: Protocol.Notifications.ITEM_STARTED,
              item,
            }
            transport.send(JsonRpc.notification(Protocol.Notifications.ITEM_STARTED, startedEvent))
            await EventLog.append(directory, threadId, startedEvent)
          }
          const deltaEvent = {
            notification: Protocol.Notifications.ITEM_DELTA,
            itemId,
            threadId,
            turnId,
            type: "assistant_message",
            delta: props.delta,
          }
          transport.send(JsonRpc.notification(Protocol.Notifications.ITEM_DELTA, deltaEvent))
          // Don't log every delta to event log (too noisy) — only log final text on completion
        } else if (part.type === "reasoning" && props.delta) {
          // Reasoning delta
          let itemId = activeItems.get(part.id)
          if (!itemId) {
            itemId = Identifier.ascending("item")
            activeItems.set(part.id, itemId)
            const item: Protocol.Item = {
              itemId,
              threadId,
              turnId,
              type: "assistant_message",
              data: { partId: part.id, reasoning: true },
            }
            turn.items.set(itemId, item)
            const startedEvent = {
              notification: Protocol.Notifications.ITEM_STARTED,
              item,
            }
            transport.send(JsonRpc.notification(Protocol.Notifications.ITEM_STARTED, startedEvent))
            await EventLog.append(directory, threadId, startedEvent)
          }
          const deltaEvent = {
            notification: Protocol.Notifications.ITEM_DELTA,
            itemId,
            threadId,
            turnId,
            type: "assistant_message",
            delta: props.delta,
            reasoning: true,
          }
          transport.send(JsonRpc.notification(Protocol.Notifications.ITEM_DELTA, deltaEvent))
        } else if (part.type === "tool") {
          handleToolEvent(part, ctx)
        }
        break
      }

      case "permission.asked": {
        const req = event.properties
        if (req.sessionID !== sessionID) return

        const itemId = Identifier.ascending("item")
        const item: Protocol.Item = {
          itemId,
          threadId,
          turnId,
          type: "approval",
          data: {
            requestId: req.id,
            permission: req.permission,
            patterns: req.patterns,
            metadata: req.metadata,
          },
        }
        turn.items.set(itemId, item)
        activeItems.set(req.id, itemId)

        const startedEvent = {
          notification: Protocol.Notifications.ITEM_STARTED,
          item,
        }
        transport.send(JsonRpc.notification(Protocol.Notifications.ITEM_STARTED, startedEvent))
        await EventLog.append(directory, threadId, startedEvent)

        const approvalEvent = {
          notification: Protocol.Notifications.APPROVAL_REQUESTED,
          requestId: req.id,
          threadId,
          turnId,
          itemId,
          permission: req.permission,
          patterns: req.patterns,
          metadata: req.metadata,
          tool: req.tool,
        }
        transport.send(JsonRpc.notification(Protocol.Notifications.APPROVAL_REQUESTED, approvalEvent))
        await EventLog.append(directory, threadId, approvalEvent)
        break
      }

      case "permission.replied": {
        const req = event.properties
        if (req.sessionID !== sessionID) return

        const itemId = activeItems.get(req.requestID)
        if (itemId) {
          const completedEvent = {
            notification: Protocol.Notifications.ITEM_COMPLETED,
            itemId,
            threadId,
            turnId,
            type: "approval",
            data: { reply: req.reply },
          }
          transport.send(JsonRpc.notification(Protocol.Notifications.ITEM_COMPLETED, completedEvent))
          await EventLog.append(directory, threadId, completedEvent)
        }
        break
      }
    }
  }

  function handleToolEvent(
    part: any,
    ctx: {
      turnId: string
      threadId: string
      sessionID: string
      directory: string
      transport: JsonRpc.Transport
      turn: ThreadManager.TurnState
      activeItems: Map<string, string>
    },
  ) {
    const { turnId, threadId, directory, transport, turn, activeItems } = ctx

    switch (part.state?.status) {
      case "pending": {
        const itemId = Identifier.ascending("item")
        activeItems.set(part.callID, itemId)
        const item: Protocol.Item = {
          itemId,
          threadId,
          turnId,
          type: "tool_exec",
          data: {
            callId: part.callID,
            tool: part.tool,
            status: "pending",
          },
        }
        turn.items.set(itemId, item)
        const startedEvent = {
          notification: Protocol.Notifications.ITEM_STARTED,
          item,
        }
        transport.send(JsonRpc.notification(Protocol.Notifications.ITEM_STARTED, startedEvent))
        EventLog.append(directory, threadId, startedEvent)
        break
      }
      case "running": {
        const itemId = activeItems.get(part.callID)
        if (!itemId) return
        const deltaEvent = {
          notification: Protocol.Notifications.ITEM_DELTA,
          itemId,
          threadId,
          turnId,
          type: "tool_log",
          data: {
            callId: part.callID,
            tool: part.tool,
            input: part.state.input,
          },
        }
        transport.send(JsonRpc.notification(Protocol.Notifications.ITEM_DELTA, deltaEvent))
        break
      }
      case "completed": {
        const itemId = activeItems.get(part.callID)
        if (!itemId) return
        const completedEvent = {
          notification: Protocol.Notifications.ITEM_COMPLETED,
          itemId,
          threadId,
          turnId,
          type: "tool_exec",
          data: {
            callId: part.callID,
            tool: part.tool,
            output: part.state.output,
            title: part.state.title,
          },
        }
        transport.send(JsonRpc.notification(Protocol.Notifications.ITEM_COMPLETED, completedEvent))
        EventLog.append(directory, threadId, completedEvent)
        break
      }
      case "error": {
        const itemId = activeItems.get(part.callID)
        if (!itemId) return
        const completedEvent = {
          notification: Protocol.Notifications.ITEM_COMPLETED,
          itemId,
          threadId,
          turnId,
          type: "tool_exec",
          data: {
            callId: part.callID,
            tool: part.tool,
            error: part.state.error,
          },
        }
        transport.send(JsonRpc.notification(Protocol.Notifications.ITEM_COMPLETED, completedEvent))
        EventLog.append(directory, threadId, completedEvent)
        break
      }
    }
  }

  async function resolveModel() {
    try {
      const model = await Provider.defaultModel()
      return {
        providerID: model.providerID,
        modelID: model.modelID,
      }
    } catch {
      return { providerID: "openrouter", modelID: "anthropic/claude-sonnet-4-20250514" }
    }
  }
}
