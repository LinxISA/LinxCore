package linxcore.top

import chisel3._
import chisel3.util.Cat

import linxcore.commit.{CommitTraceParams, CommitTraceRow}
import linxcore.common.{InterfaceParams, OperandClass, RenamedUop}
import linxcore.lsu.{STQCommitDrainRequest, StoreDispatchScIdentity}
import linxcore.rob.ROBID

class ScalarScTopHandshakeIO(
    val p: InterfaceParams,
    val traceParams: CommitTraceParams,
    val scbRequestCount: Int,
    val stqEntries: Int)
    extends Bundle {
  val flush = Input(Bool())

  val issueFire = Input(Bool())
  val issueUop = Input(new RenamedUop(p))
  val issueAddr = Input(UInt(p.immWidth.W))
  val issueData = Input(UInt(p.immWidth.W))

  val ownerScReqReady = Input(Bool())
  val ownerScReqAccepted = Input(Bool())
  val ownerScSuccess = Input(Bool())
  val ownerScStatus = Input(UInt(p.immWidth.W))
  val ownerScStoreData = Input(UInt(p.immWidth.W))
  val ownerScCompleteFire = Input(Bool())

  val storeScSelectedSuccess = Input(Bool())
  val storeScSelectedMissDiscard = Input(Bool())
  val storeStqInsertAccepted = Input(Bool())
  val scbAcceptedMask = Input(UInt(scbRequestCount.W))
  val scbAcceptedReqs = Input(Vec(
    scbRequestCount,
    new STQCommitDrainRequest(stqEntries, p.immWidth, p.immWidth, 4, p.robEntries, p.lsidWidth)))
  val serviceCompleteValid = Input(Bool())

  val ownerScReqValid = Output(Bool())
  val ownerScReqStid = Output(UInt(p.threadIdWidth.W))
  val ownerScReqLineAddr = Output(UInt(p.immWidth.W))
  val ownerScReqData = Output(UInt(p.immWidth.W))
  val ownerScReqBid = Output(new ROBID(p.robEntries))
  val ownerScReqGid = Output(new ROBID(p.robEntries))
  val ownerScReqRid = Output(new ROBID(p.robEntries))
  val ownerScReqLsId = Output(UInt(p.lsidWidth.W))
  val ownerScCommitReady = Output(Bool())

  val storeScResultValid = Output(Bool())
  val storeScResultSuccess = Output(Bool())
  val storeScResultIdentity = Output(new StoreDispatchScIdentity(p.robEntries, p.threadIdWidth, p.lsidWidth))
  val storeScStoreData = Output(UInt(p.immWidth.W))

  val completeValid = Output(Bool())
  val completeRobValue = Output(UInt(p.robIndexWidth.W))
  val completeRow = Output(new CommitTraceRow(traceParams))
  val releaseValid = Output(Bool())
  val releaseBid = Output(new ROBID(p.robEntries))
  val releaseRid = Output(new ROBID(p.robEntries))
  val releaseStid = Output(UInt(p.threadIdWidth.W))
  val writebackValid = Output(Bool())
  val writebackTag = Output(UInt(p.physRegWidth.W))
  val writebackData = Output(UInt(p.immWidth.W))
  val active = Output(Bool())
  val inserted = Output(Bool())
  val scbAcceptedDiagnostic = Output(Bool())
}

class ScalarScTopHandshake(
    val p: InterfaceParams,
    val traceParams: CommitTraceParams,
    val scbRequestCount: Int,
    val stqEntries: Int)
    extends Module {
  val io = IO(new ScalarScTopHandshakeIO(p, traceParams, scbRequestCount, stqEntries))

  private def lineAddr(addr: UInt): UInt =
    Cat(addr(p.immWidth - 1, 6), 0.U(6.W))

  private def robIdToTraceValue(id: ROBID): UInt =
    Cat(0.U((32 - p.robIndexWidth - 1).W), id.wrap, id.value)

  val activeReg = RegInit(false.B)
  val insertedReg = RegInit(false.B)
  val uopReg = Reg(chiselTypeOf(io.issueUop))
  val addrReg = RegInit(0.U(p.immWidth.W))
  val dataReg = RegInit(0.U(p.immWidth.W))
  val stidReg = RegInit(0.U(p.threadIdWidth.W))
  val bidReg = RegInit(ROBID.disabled(p.robEntries))
  val gidReg = RegInit(ROBID.disabled(p.robEntries))
  val ridReg = RegInit(ROBID.disabled(p.robEntries))
  val lsIdReg = RegInit(0.U(p.lsidWidth.W))
  val terminalPending = RegInit(false.B)
  val terminalUop = Reg(chiselTypeOf(io.issueUop))
  val terminalAddr = RegInit(0.U(p.immWidth.W))
  val terminalData = RegInit(0.U(p.immWidth.W))
  val terminalStid = RegInit(0.U(p.threadIdWidth.W))
  val terminalBid = RegInit(ROBID.disabled(p.robEntries))
  val terminalGid = RegInit(ROBID.disabled(p.robEntries))
  val terminalRid = RegInit(ROBID.disabled(p.robEntries))
  val terminalLsId = RegInit(0.U(p.lsidWidth.W))
  val terminalSuccess = RegInit(false.B)
  val terminalStatus = RegInit(0.U(p.immWidth.W))

  val scbAcceptedDiagnostic = VecInit((0 until scbRequestCount).map { idx =>
    val req = io.scbAcceptedReqs(idx)
    io.scbAcceptedMask(idx) && req.valid &&
      req.stid === stidReg &&
      ROBID.equal(req.bid, bidReg) &&
      ROBID.equal(req.gid, gidReg) &&
      ROBID.equal(req.rid, ridReg) &&
      req.lsId === lsIdReg
  }).asUInt.orR
  val insertedFire = activeReg && io.storeScSelectedSuccess && io.storeStqInsertAccepted
  val missDiscardFire = activeReg && io.storeScSelectedMissDiscard
  val commitReady = missDiscardFire || insertedFire || (activeReg && insertedReg)
  val terminalFire = terminalPending && !io.serviceCompleteValid && !io.flush
  val terminalCaptureFire = io.ownerScCompleteFire && commitReady && !terminalPending && !io.flush

  io.ownerScReqValid := io.issueFire && !activeReg && !terminalPending
  io.ownerScReqStid := io.issueUop.threadId
  io.ownerScReqLineAddr := lineAddr(io.issueAddr)
  io.ownerScReqData := io.issueData
  io.ownerScReqBid := io.issueUop.bid
  io.ownerScReqGid := io.issueUop.gid
  io.ownerScReqRid := io.issueUop.rid
  io.ownerScReqLsId := io.issueUop.lsid
  io.ownerScCommitReady := commitReady && !terminalPending && !io.serviceCompleteValid

  io.storeScResultValid := activeReg && !terminalPending
  io.storeScResultSuccess := io.ownerScSuccess
  io.storeScResultIdentity.stid := stidReg
  io.storeScResultIdentity.bid := bidReg
  io.storeScResultIdentity.gid := gidReg
  io.storeScResultIdentity.rid := ridReg
  io.storeScResultIdentity.lsIdFull := lsIdReg
  io.storeScStoreData := io.ownerScStoreData

  io.completeValid := terminalFire
  io.completeRobValue := terminalRid.value
  io.completeRow := 0.U.asTypeOf(io.completeRow)
  io.completeRow.valid := io.completeValid
  io.completeRow.identity.bid := robIdToTraceValue(terminalBid)
  io.completeRow.identity.gid := robIdToTraceValue(terminalGid)
  io.completeRow.identity.rid := robIdToTraceValue(terminalRid)
  io.completeRow.rob.valid := terminalRid.valid
  io.completeRow.rob.wrap := terminalRid.wrap
  io.completeRow.rob.value := terminalRid.value
  io.completeRow.blockBidValid := terminalUop.blockBidValid
  io.completeRow.blockBid := terminalUop.blockBid
  io.completeRow.pc := terminalUop.pc
  io.completeRow.insn := terminalUop.insnRaw
  io.completeRow.len := terminalUop.insnLen
  io.completeRow.nextPc := terminalUop.pc + terminalUop.insnLen
  io.completeRow.src0.valid :=
    io.completeValid && terminalUop.src(0).valid && terminalUop.src(0).operandClass === OperandClass.P
  io.completeRow.src0.reg := terminalUop.src(0).archTag
  io.completeRow.src0.data := terminalData
  io.completeRow.src1.valid :=
    io.completeValid && terminalUop.src(1).valid && terminalUop.src(1).operandClass === OperandClass.P
  io.completeRow.src1.reg := terminalUop.src(1).archTag
  io.completeRow.src1.data := terminalAddr
  io.completeRow.dst.valid := io.completeValid && terminalUop.dst(0).valid
  io.completeRow.dst.reg := terminalUop.dst(0).archTag
  io.completeRow.dst.data := terminalStatus
  io.completeRow.wb.valid := io.completeValid && terminalUop.dst(0).valid
  io.completeRow.wb.reg := terminalUop.dst(0).archTag
  io.completeRow.wb.data := terminalStatus
  io.completeRow.mem.valid := io.completeValid && terminalSuccess
  io.completeRow.mem.isStore := true.B
  io.completeRow.mem.addr := terminalAddr
  io.completeRow.mem.wdata := terminalData
  io.completeRow.mem.size := 4.U

  io.releaseValid := io.completeValid
  io.releaseBid := terminalBid
  io.releaseRid := terminalRid
  io.releaseStid := terminalStid
  io.writebackValid := io.completeValid && terminalUop.dst(0).valid
  io.writebackTag := terminalUop.dst(0).physTag
  io.writebackData := terminalStatus
  io.active := activeReg || terminalPending
  io.inserted := insertedReg
  io.scbAcceptedDiagnostic := scbAcceptedDiagnostic

  when(io.flush) {
    activeReg := false.B
    insertedReg := false.B
    terminalPending := false.B
    bidReg := ROBID.disabled(p.robEntries)
    gidReg := ROBID.disabled(p.robEntries)
    ridReg := ROBID.disabled(p.robEntries)
    lsIdReg := 0.U
  }.otherwise {
    when(terminalFire) {
      terminalPending := false.B
      terminalBid := ROBID.disabled(p.robEntries)
      terminalGid := ROBID.disabled(p.robEntries)
      terminalRid := ROBID.disabled(p.robEntries)
      terminalLsId := 0.U
      terminalSuccess := false.B
      terminalStatus := 0.U
    }
    when(terminalCaptureFire) {
      terminalPending := true.B
      terminalUop := uopReg
      terminalAddr := addrReg
      terminalData := dataReg
      terminalStid := stidReg
      terminalBid := bidReg
      terminalGid := gidReg
      terminalRid := ridReg
      terminalLsId := lsIdReg
      terminalSuccess := io.ownerScSuccess
      terminalStatus := Mux(io.ownerScSuccess, 0.U, io.ownerScStatus)
      activeReg := false.B
      insertedReg := false.B
      bidReg := ROBID.disabled(p.robEntries)
      gidReg := ROBID.disabled(p.robEntries)
      ridReg := ROBID.disabled(p.robEntries)
      lsIdReg := 0.U
    }
    when(insertedFire) {
      insertedReg := true.B
    }
    when(io.issueFire && io.ownerScReqAccepted && !activeReg && !terminalPending) {
      activeReg := true.B
      insertedReg := false.B
      uopReg := io.issueUop
      addrReg := io.issueAddr
      dataReg := io.issueData
      stidReg := io.issueUop.threadId
      bidReg := io.issueUop.bid
      gidReg := io.issueUop.gid
      ridReg := io.issueUop.rid
      lsIdReg := io.issueUop.lsid
    }
  }
}
