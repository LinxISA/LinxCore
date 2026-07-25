package linxcore.lsu

import circt.stage.ChiselStage
import chisel3._
import linxcore.common.{DispatchTarget, InterfaceParams, OperandClass}
import linxcore.rename.StoreSplitStoreType

class ReducedStoreStaAddressExecHlSdiPrProbeIO extends Bundle {
  val enable = Input(Bool())
  val queueValid = Input(Bool())
  val payloadValid = Input(Bool())
  val opcode = Input(UInt(12.W))
  val pc = Input(UInt(64.W))
  val insnRaw = Input(UInt(64.W))
  val imm = Input(UInt(64.W))
  val storeType = Input(UInt(2.W))
  val peId = Input(UInt(8.W))
  val threadId = Input(UInt(8.W))
  val lsid = Input(UInt(32.W))
  val bidValid = Input(Bool())
  val bidWrap = Input(Bool())
  val bidValue = Input(UInt(6.W))
  val gidValid = Input(Bool())
  val gidWrap = Input(Bool())
  val gidValue = Input(UInt(6.W))
  val ridValid = Input(Bool())
  val ridWrap = Input(Bool())
  val ridValue = Input(UInt(6.W))
  val srcValid = Input(Vec(3, Bool()))
  val srcReady = Input(Vec(3, Bool()))
  val srcData = Input(Vec(3, UInt(64.W)))
  val srcArchTag = Input(Vec(3, UInt(6.W)))
  val srcPhysTag = Input(Vec(3, UInt(6.W)))

  val candidate = Output(Bool())
  val supportedOpcode = Output(Bool())
  val addrSourceMask = Output(UInt(3.W))
  val addrSourceReady = Output(Bool())
  val blockedBySource = Output(Bool())
  val blockedByUnsupported = Output(Bool())
  val execValid = Output(Bool())
  val execAddr = Output(UInt(64.W))
  val execData = Output(UInt(64.W))
  val execSize = Output(UInt(4.W))
  val execPeId = Output(UInt(8.W))
  val execStid = Output(UInt(8.W))
  val execTid = Output(UInt(8.W))
  val execStackValid = Output(Bool())
  val execScalarIex = Output(Bool())
  val execSimtLane = Output(UInt(8.W))
  val queueOpcode = Output(UInt(12.W))
  val queueInsnRaw = Output(UInt(64.W))
  val queueImm = Output(UInt(64.W))
  val queueStoreType = Output(UInt(2.W))
  val queueSrcValid = Output(Vec(3, Bool()))
  val queueSrcReady = Output(Vec(3, Bool()))
  val queueSrcData = Output(Vec(3, UInt(64.W)))
  val queueSrcArchTag = Output(Vec(3, UInt(6.W)))
  val queueSrcPhysTag = Output(Vec(3, UInt(6.W)))
}

class ReducedStoreStaAddressExecHlSdiPrProbe extends Module {
  private val p = InterfaceParams()

  val io = IO(new ReducedStoreStaAddressExecHlSdiPrProbeIO)
  val bridge = Module(new ReducedStoreStaAddressExecBridge(
    p = p,
    mapQDepth = 32,
    peIdWidth = p.peIdWidth,
    stidWidth = p.threadIdWidth,
    tidWidth = p.threadIdWidth,
    sizeWidth = p.memSizeWidth
  ))

  bridge.io.enable := io.enable
  bridge.io.queueValid := io.queueValid
  bridge.io.queue := 0.U.asTypeOf(bridge.io.queue)
  bridge.io.queue.valid := io.payloadValid
  bridge.io.queue.storeType := Mux(
    io.storeType === 1.U,
    StoreSplitStoreType.Addr,
    Mux(io.storeType === 2.U, StoreSplitStoreType.Data, StoreSplitStoreType.All))
  bridge.io.queue.uop.valid := io.payloadValid
  bridge.io.queue.uop.peId := io.peId
  bridge.io.queue.uop.threadId := io.threadId
  bridge.io.queue.uop.pc := io.pc
  bridge.io.queue.uop.opcode := io.opcode
  bridge.io.queue.uop.dispatchTarget := DispatchTarget.Lsu
  bridge.io.queue.uop.imm := io.imm
  bridge.io.queue.uop.immValid := true.B
  bridge.io.queue.uop.rid.valid := io.ridValid
  bridge.io.queue.uop.rid.wrap := io.ridWrap
  bridge.io.queue.uop.rid.value := io.ridValue
  bridge.io.queue.uop.bid.valid := io.bidValid
  bridge.io.queue.uop.bid.wrap := io.bidWrap
  bridge.io.queue.uop.bid.value := io.bidValue
  bridge.io.queue.uop.gid.valid := io.gidValid
  bridge.io.queue.uop.gid.wrap := io.gidWrap
  bridge.io.queue.uop.gid.value := io.gidValue
  bridge.io.queue.uop.lsid := io.lsid
  bridge.io.queue.uop.isLoad := false.B
  bridge.io.queue.uop.isStore := true.B
  bridge.io.queue.uop.storeSplitIntent := true.B
  bridge.io.queue.uop.insnLen := 6.U
  bridge.io.queue.uop.insnRaw := io.insnRaw

  for (idx <- 0 until 3) {
    bridge.io.queue.uop.src(idx).valid := io.srcValid(idx)
    bridge.io.queue.uop.src(idx).operandClass := OperandClass.P
    bridge.io.queue.uop.src(idx).archTag := io.srcArchTag(idx)
    bridge.io.queue.uop.src(idx).physTag := io.srcPhysTag(idx)
    bridge.io.queue.uop.src(idx).ready := io.srcReady(idx)
    bridge.io.srcReadReady(idx) := io.srcReady(idx)
    bridge.io.srcReadData(idx) := io.srcData(idx)
  }

  io.candidate := bridge.io.candidate
  io.supportedOpcode := bridge.io.supportedOpcode
  io.addrSourceMask := bridge.io.addrSourceMask
  io.addrSourceReady := bridge.io.addrSourceReady
  io.blockedBySource := bridge.io.blockedBySource
  io.blockedByUnsupported := bridge.io.blockedByUnsupported
  io.execValid := bridge.io.exec.valid
  io.execAddr := bridge.io.exec.addr
  io.execData := bridge.io.exec.data
  io.execSize := bridge.io.exec.size
  io.execPeId := bridge.io.exec.peId
  io.execStid := bridge.io.exec.stid
  io.execTid := bridge.io.exec.tid
  io.execStackValid := bridge.io.exec.stackValid
  io.execScalarIex := bridge.io.exec.scalarIex
  io.execSimtLane := bridge.io.exec.simtLane
  io.queueOpcode := bridge.io.queue.uop.opcode
  io.queueInsnRaw := bridge.io.queue.uop.insnRaw
  io.queueImm := bridge.io.queue.uop.imm
  io.queueStoreType := io.storeType
  io.queueSrcValid := io.srcValid
  io.queueSrcReady := io.srcReady
  io.queueSrcData := io.srcData
  io.queueSrcArchTag := io.srcArchTag
  io.queueSrcPhysTag := io.srcPhysTag
}

object EmitReducedStoreStaAddressExecHlSdiPrProbe extends App {
  val targetDir = args.sliding(2, 1).collectFirst {
    case Array("--target-dir", dir) => dir
  }.getOrElse("generated/chisel-verilog/lsu-reduced-store-sta-address-exec-hl-sdi-pr-probe-r857")

  ChiselStage.emitSystemVerilogFile(
    new ReducedStoreStaAddressExecHlSdiPrProbe,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"),
    args = Array("--target-dir", targetDir))
}
