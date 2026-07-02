package linxcore.lsu

import chisel3._

import linxcore.rob.ROBID

class ReducedLoadReplayCompletionDrainIO(
    val idEntries: Int,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val sizeWidth: Int = 7)
    extends Bundle {
  val candidateValid = Input(Bool())
  val candidate = Input(new ReducedLoadReplayCandidate(idEntries, addrWidth, pcWidth, sizeWidth))

  val completeValid = Input(Bool())
  val completeMemLoad = Input(Bool())
  val completePc = Input(UInt(pcWidth.W))
  val completeAddr = Input(UInt(addrWidth.W))
  val completeSize = Input(UInt(sizeWidth.W))
  val completeBid = Input(new ROBID(idEntries))
  val completeLsId = Input(new ROBID(idEntries))

  val consumeReady = Output(Bool())
  val matchValid = Output(Bool())
  val mismatch = Output(Bool())
  val pcMismatch = Output(Bool())
  val addrMismatch = Output(Bool())
  val sizeMismatch = Output(Bool())
  val bidMismatch = Output(Bool())
  val lsIdMismatch = Output(Bool())
}

class ReducedLoadReplayCompletionDrain(
    val idEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val sizeWidth: Int = 7)
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(sizeWidth >= 7, "sizeWidth must cover 64-byte scalar lines")

  val io = IO(new ReducedLoadReplayCompletionDrainIO(idEntries, addrWidth, pcWidth, sizeWidth))

  val completionEligible = io.completeValid && io.completeMemLoad
  val pcMismatch = io.candidate.pc =/= io.completePc
  val addrMismatch = io.candidate.addr =/= io.completeAddr
  val sizeMismatch = io.candidate.size =/= io.completeSize
  val bidMismatch = Seq(
    io.candidate.bid.valid =/= io.completeBid.valid,
    io.candidate.bid.wrap =/= io.completeBid.wrap,
    io.candidate.bid.value =/= io.completeBid.value
  ).reduce(_ || _)
  val lsIdMismatch = Seq(
    io.candidate.loadLsId.valid =/= io.completeLsId.valid,
    io.candidate.loadLsId.wrap =/= io.completeLsId.wrap,
    io.candidate.loadLsId.value =/= io.completeLsId.value
  ).reduce(_ || _)
  val anyMismatch = pcMismatch || addrMismatch || sizeMismatch || bidMismatch || lsIdMismatch
  val comparable = io.candidateValid && io.candidate.valid && completionEligible

  io.matchValid := comparable && !anyMismatch
  io.mismatch := comparable && anyMismatch
  io.consumeReady := io.matchValid
  io.pcMismatch := comparable && pcMismatch
  io.addrMismatch := comparable && addrMismatch
  io.sizeMismatch := comparable && sizeMismatch
  io.bidMismatch := comparable && bidMismatch
  io.lsIdMismatch := comparable && lsIdMismatch
}
