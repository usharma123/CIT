import { PermissionNext } from "@/permission/next"
import { Log } from "@/util/log"
import type { Protocol } from "./protocol"

export namespace ApprovalGate {
  const log = Log.create({ service: "harness-approval-gate" })

  export async function handleRespond(params: Protocol.ApprovalRespondParams): Promise<{ ok: boolean }> {
    log.info("approval respond", { requestId: params.requestId, decision: params.decision })
    await PermissionNext.reply({
      requestID: params.requestId,
      reply: params.decision,
    })
    return { ok: true }
  }
}
