package linxcore.ooo

import chisel3._
import linxcore.frontend.IfuInnerFlush

/** Stateless equality helper retained after deletion of the displaced
  * frontend recovery wrapper.
  */
object OooFrontendRecoveryContract {
  def sameRedirectProposal(left: IfuInnerFlush, right: IfuInnerFlush): Bool =
    left.valid === right.valid &&
      left.peId === right.peId &&
      left.threadId === right.threadId &&
      left.transactionId === right.transactionId &&
      left.fetchSeq === right.fetchSeq &&
      left.oldEpoch === right.oldEpoch &&
      left.restartPc === right.restartPc &&
      left.checkpointId === right.checkpointId &&
      left.reason === right.reason &&
      left.scope === right.scope &&
      left.terminalSteer === right.terminalSteer &&
      left.terminalTaken === right.terminalTaken &&
      left.boundaryPc === right.boundaryPc &&
      left.boundaryFallthroughPc === right.boundaryFallthroughPc &&
      left.historyKeyValid === right.historyKeyValid &&
      left.predictionTag === right.predictionTag &&
      left.fetchPacketUid === right.fetchPacketUid &&
      left.ghrAction === right.ghrAction &&
      left.ghrAppendValid === right.ghrAppendValid &&
      left.ghrAppendTaken === right.ghrAppendTaken &&
      left.rasAction === right.rasAction &&
      left.rasUpdate === right.rasUpdate &&
      left.rasPushAddress === right.rasPushAddress
}
