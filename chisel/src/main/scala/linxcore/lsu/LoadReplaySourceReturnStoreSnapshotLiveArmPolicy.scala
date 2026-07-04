package linxcore.lsu

import chisel3._

class LoadReplaySourceReturnStoreSnapshotLiveArmPolicyIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val policyEnable = Input(Bool())
  val rowMutationLiveEnable = Input(Bool())
  val launchValid = Input(Bool())
  val requestQueueCanAccept = Input(Bool())
  val acceptedTokenCanAccept = Input(Bool())
  val requestHeadValid = Input(Bool())
  val rawSinkAvailable = Input(Bool())
  val responseQueueFull = Input(Bool())
  val rawResponseValid = Input(Bool())

  val active = Output(Bool())
  val requestCandidate = Output(Bool())
  val requestEnable = Output(Bool())
  val sinkCandidate = Output(Bool())
  val sinkReady = Output(Bool())
  val responsePortBlocked = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByPolicyDisabled = Output(Bool())
  val requestBlockedByNoLaunch = Output(Bool())
  val requestBlockedByRowMutationDisabled = Output(Bool())
  val requestBlockedByRequestQueue = Output(Bool())
  val requestBlockedByAcceptedToken = Output(Bool())
  val sinkBlockedByNoRequest = Output(Bool())
  val sinkBlockedByRowMutationDisabled = Output(Bool())
  val sinkBlockedByRawSink = Output(Bool())
  val responseBlockedByQueueFull = Output(Bool())
  val responseBlockedByRawResponse = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotLiveArmPolicy extends Module {
  val io = IO(new LoadReplaySourceReturnStoreSnapshotLiveArmPolicyIO)

  val active = io.enable && !io.flush
  val policyActive = active && io.policyEnable
  val requestCandidate = policyActive && io.launchValid
  val requestStorageReady = io.requestQueueCanAccept && io.acceptedTokenCanAccept
  val requestEnable = requestCandidate && io.rowMutationLiveEnable && requestStorageReady
  val sinkHasRequest = io.requestHeadValid || requestEnable
  val sinkCandidate = policyActive && sinkHasRequest
  val sinkReady = sinkCandidate && io.rowMutationLiveEnable && io.rawSinkAvailable
  val responsePortBlocked = sinkCandidate && (io.responseQueueFull || io.rawResponseValid)
  val rawIntent =
    io.policyEnable ||
      io.rowMutationLiveEnable ||
      io.launchValid ||
      io.requestHeadValid ||
      io.rawSinkAvailable ||
      io.responseQueueFull ||
      io.rawResponseValid

  io.active := active
  io.requestCandidate := requestCandidate
  io.requestEnable := requestEnable
  io.sinkCandidate := sinkCandidate
  io.sinkReady := sinkReady
  io.responsePortBlocked := responsePortBlocked
  io.blockedByDisabled := !io.enable && rawIntent
  io.blockedByFlush := io.enable && io.flush && rawIntent
  io.blockedByPolicyDisabled := active && !io.policyEnable && rawIntent
  io.requestBlockedByNoLaunch := policyActive && !io.launchValid
  io.requestBlockedByRowMutationDisabled := requestCandidate && !io.rowMutationLiveEnable
  io.requestBlockedByRequestQueue := requestCandidate && io.rowMutationLiveEnable && !io.requestQueueCanAccept
  io.requestBlockedByAcceptedToken :=
    requestCandidate && io.rowMutationLiveEnable && io.requestQueueCanAccept && !io.acceptedTokenCanAccept
  io.sinkBlockedByNoRequest := policyActive && !sinkHasRequest
  io.sinkBlockedByRowMutationDisabled := sinkCandidate && !io.rowMutationLiveEnable
  io.sinkBlockedByRawSink := sinkCandidate && io.rowMutationLiveEnable && !io.rawSinkAvailable
  io.responseBlockedByQueueFull := sinkCandidate && io.responseQueueFull
  io.responseBlockedByRawResponse := sinkCandidate && io.rawResponseValid
}
