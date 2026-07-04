package linxcore.lsu

import chisel3._

class LoadReplaySourceReturnStoreSnapshotResponseMatchIO extends Bundle {
  val enable = Input(Bool())
  val flush = Input(Bool())
  val queryIssued = Input(Bool())
  val responseValidIn = Input(Bool())
  val responseMatchesSelected = Input(Bool())
  val scbReturned = Input(Bool())
  val waitStoreIn = Input(Bool())
  val dataValidIn = Input(Bool())

  val active = Output(Bool())
  val responseCandidate = Output(Bool())
  val responseMatched = Output(Bool())
  val responseOrdered = Output(Bool())
  val responseValid = Output(Bool())
  val waitStore = Output(Bool())
  val dataValid = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByNoQuery = Output(Bool())
  val blockedByNoMatch = Output(Bool())
  val blockedByScbOrder = Output(Bool())
  val invalidResponseWithoutQuery = Output(Bool())
  val invalidDataWithWaitStore = Output(Bool())
}

class LoadReplaySourceReturnStoreSnapshotResponseMatch extends Module {
  val io = IO(new LoadReplaySourceReturnStoreSnapshotResponseMatchIO)

  val active = io.enable && !io.flush
  val rawResponse =
    io.responseValidIn || io.waitStoreIn || io.dataValidIn
  val responseCandidate = active && io.responseValidIn
  val responseHasQuery = responseCandidate && io.queryIssued
  val responseMatched = responseHasQuery && io.responseMatchesSelected
  val responseOrdered = responseMatched && io.scbReturned

  io.active := active
  io.responseCandidate := responseCandidate
  io.responseMatched := responseMatched
  io.responseOrdered := responseOrdered
  io.responseValid := responseOrdered
  io.waitStore := responseOrdered && io.waitStoreIn
  io.dataValid := responseOrdered && io.dataValidIn
  io.blockedByDisabled := !io.enable && rawResponse
  io.blockedByFlush := io.enable && io.flush && rawResponse
  io.blockedByNoQuery := responseCandidate && !io.queryIssued
  io.blockedByNoMatch := responseHasQuery && !io.responseMatchesSelected
  io.blockedByScbOrder := responseMatched && !io.scbReturned
  io.invalidResponseWithoutQuery := active && io.responseValidIn && !io.queryIssued
  io.invalidDataWithWaitStore := responseOrdered && io.waitStoreIn && io.dataValidIn
}
