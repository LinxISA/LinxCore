package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.common.{DestinationKind, InterfaceParams}
import linxcore.rename.{StoreSplitIssuePayload, StoreSplitStoreType}
import linxcore.rob.ROBID

class StoreDispatchExecResult(
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8)
    extends Bundle {
  val valid = Bool()
  val addr = UInt(addrWidth.W)
  val data = UInt(dataWidth.W)
  val size = UInt(sizeWidth.W)
  val peId = UInt(peIdWidth.W)
  val stid = UInt(stidWidth.W)
  val tid = UInt(tidWidth.W)
  val stackValid = Bool()
  val scalarIex = Bool()
  val simtLane = UInt(simtLaneWidth.W)
}

class StoreDispatchToSTQIO(
    val p: InterfaceParams = InterfaceParams(),
    val entries: Int = 16,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32)
    extends Bundle {
  val flushValid = Input(Bool())
  val staValid = Input(Bool())
  val stdValid = Input(Bool())
  val sta = Input(new StoreSplitIssuePayload(p, mapQDepth))
  val std = Input(new StoreSplitIssuePayload(p, mapQDepth))
  val staExec = Input(new StoreDispatchExecResult(addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))
  val stdExec = Input(new StoreDispatchExecResult(addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))
  val staInsertReady = Input(Bool())
  val stdInsertReady = Input(Bool())

  val staDequeueReady = Output(Bool())
  val stdDequeueReady = Output(Bool())
  val insertValid = Output(Bool())
  val insert = Output(new STQStoreRequest(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth, 64, p.lsidWidth))
  val staRequest = Output(new STQStoreRequest(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth, 64, p.lsidWidth))
  val stdRequest = Output(new STQStoreRequest(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth, 64, p.lsidWidth))
  val staCandidate = Output(Bool())
  val stdCandidate = Output(Bool())
  val selectedSta = Output(Bool())
  val selectedStd = Output(Bool())
  val blockedByStaExec = Output(Bool())
  val blockedByStdExec = Output(Bool())
  val blockedByStaInsert = Output(Bool())
  val blockedByStdInsert = Output(Bool())
  val stdBypassStaBlocked = Output(Bool())
}

class StoreDispatchToSTQ(
    val p: InterfaceParams = InterfaceParams(),
    val entries: Int = 16,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8,
    val mapQDepth: Int = 32)
    extends Module {
  require(entries > 1, "STQ identity entries must be greater than one")
  require((entries & (entries - 1)) == 0, "STQ identity entries must be a power of two")

  private val idWidth = log2Ceil(entries)

  val io = IO(new StoreDispatchToSTQIO(p, entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth))

  private def resizeUInt(value: UInt, width: Int): UInt =
    if (width <= value.getWidth) value(width - 1, 0) else value.pad(width)

  private def resizeId(id: ROBID): ROBID = {
    val out = Wire(new ROBID(entries))
    out.valid := id.valid
    out.wrap := id.wrap
    out.value := resizeUInt(id.value, idWidth)
    out
  }

  private def lsidToId(lsid: UInt): ROBID = {
    val out = Wire(new ROBID(entries))
    val wrapBit = if (p.lsidWidth > idWidth) lsid(idWidth) else false.B
    out.valid := true.B
    out.wrap := wrapBit
    out.value := resizeUInt(lsid, idWidth)
    out
  }

  private def mapStoreType(payloadType: StoreSplitStoreType.Type): STQStoreType.Type = {
    val out = Wire(STQStoreType())
    out := STQStoreType.All
    when(payloadType === StoreSplitStoreType.Addr) {
      out := STQStoreType.Addr
    }.elsewhen(payloadType === StoreSplitStoreType.Data) {
      out := STQStoreType.Data
    }
    out
  }

  private def zeroRequest: STQStoreRequest = {
    val req = Wire(new STQStoreRequest(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth, 64, p.lsidWidth))
    req := 0.U.asTypeOf(req)
    req.storeType := STQStoreType.All
    req.bid := ROBID.disabled(entries)
    req.gid := ROBID.disabled(entries)
    req.rid := ROBID.disabled(entries)
    req.lsId := ROBID.disabled(entries)
    req.lsIdFull := 0.U
    req.tSeq := ROBID.disabled(mapQDepth)
    req.uSeq := ROBID.disabled(mapQDepth)
    req.tuDstValid := false.B
    req.tuDstKind := DestinationKind.None
    req
  }

  private def buildRequest(payload: StoreSplitIssuePayload, exec: StoreDispatchExecResult): STQStoreRequest = {
    val req = Wire(new STQStoreRequest(entries, addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth, mapQDepth, 64, p.lsidWidth))
    req := 0.U.asTypeOf(req)
    req.storeType := mapStoreType(payload.storeType)
    req.peId := exec.peId
    req.stid := exec.stid
    req.tid := exec.tid
    req.bid := resizeId(payload.uop.bid)
    req.gid := resizeId(payload.uop.gid)
    req.rid := resizeId(payload.uop.rid)
    req.lsId := lsidToId(payload.uop.lsid)
    req.lsIdFull := payload.uop.lsid
    req.tSeq := payload.tSeq
    req.uSeq := payload.uSeq
    req.tuDstValid := payload.tuDstValid
    req.tuDstKind := Mux(payload.tuDstValid, payload.tuDstKind, DestinationKind.None)
    req.pc := payload.uop.pc
    req.addr := exec.addr
    req.data := exec.data
    req.size := exec.size
    req.stackValid := exec.stackValid
    req.scalarIex := exec.scalarIex
    req.simtLane := exec.simtLane
    req
  }

  val staPayloadAvailable = io.staValid && io.sta.valid
  val stdPayloadAvailable = io.stdValid && io.std.valid
  val staCandidate = !io.flushValid && staPayloadAvailable && io.staExec.valid
  val stdCandidate = !io.flushValid && stdPayloadAvailable && io.stdExec.valid
  val selectedSta = staCandidate && io.staInsertReady
  val selectedStd = !selectedSta && stdCandidate && io.stdInsertReady

  val staRequest = buildRequest(io.sta, io.staExec)
  val stdRequest = buildRequest(io.std, io.stdExec)

  io.staRequest := Mux(staPayloadAvailable, staRequest, zeroRequest)
  io.stdRequest := Mux(stdPayloadAvailable, stdRequest, zeroRequest)
  io.insert := Mux(selectedSta, staRequest, Mux(selectedStd, stdRequest, zeroRequest))
  io.insertValid := selectedSta || selectedStd
  io.staDequeueReady := selectedSta
  io.stdDequeueReady := selectedStd
  io.staCandidate := staCandidate
  io.stdCandidate := stdCandidate
  io.selectedSta := selectedSta
  io.selectedStd := selectedStd
  io.blockedByStaExec := !io.flushValid && staPayloadAvailable && !io.staExec.valid
  io.blockedByStdExec := !io.flushValid && stdPayloadAvailable && !io.stdExec.valid
  io.blockedByStaInsert := staCandidate && !io.staInsertReady
  io.blockedByStdInsert := stdCandidate && !io.stdInsertReady && !selectedSta
  io.stdBypassStaBlocked := selectedStd && staCandidate && !io.staInsertReady
}
