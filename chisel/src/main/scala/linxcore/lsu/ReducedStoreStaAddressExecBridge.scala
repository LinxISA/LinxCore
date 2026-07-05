package linxcore.lsu

import chisel3._

import linxcore.common.InterfaceParams
import linxcore.frontend.FrontendOpcodeDecodeTable
import linxcore.rename.{StoreSplitIssuePayload, StoreSplitStoreType}

class ReducedStoreStaAddressExecBridgeIO(
    val p: InterfaceParams = InterfaceParams(),
    val mapQDepth: Int = 32,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8)
    extends Bundle {
  val enable = Input(Bool())
  val queueValid = Input(Bool())
  val queue = Input(new StoreSplitIssuePayload(p, mapQDepth))
  val srcReadReady = Input(Vec(3, Bool()))
  val srcReadData = Input(Vec(3, UInt(dataWidth.W)))

  val exec = Output(new StoreDispatchExecResult(addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))
  val candidate = Output(Bool())
  val supportedOpcode = Output(Bool())
  val addrSourceMask = Output(UInt(3.W))
  val addrSourceReady = Output(Bool())
  val blockedBySource = Output(Bool())
  val blockedByUnsupported = Output(Bool())
}

class ReducedStoreStaAddressExecBridge(
    val p: InterfaceParams = InterfaceParams(),
    val mapQDepth: Int = 32,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64,
    val peIdWidth: Int = 8,
    val stidWidth: Int = 8,
    val tidWidth: Int = 8,
    val sizeWidth: Int = 4,
    val simtLaneWidth: Int = 8)
    extends Module {
  require(addrWidth == dataWidth, "reduced STA bridge expects address and data operands to share a width")
  require(p.immWidth <= addrWidth, "reduced STA bridge expects immediates to fit in the address width")

  val io = IO(new ReducedStoreStaAddressExecBridgeIO(
    p,
    mapQDepth,
    addrWidth,
    dataWidth,
    peIdWidth,
    stidWidth,
    tidWidth,
    sizeWidth,
    simtLaneWidth
  ))

  private def opcode(value: Int): UInt =
    value.U(p.opcodeWidth.W)

  private def resize(value: UInt, width: Int): UInt =
    if (width <= value.getWidth) value(width - 1, 0) else value.pad(width)

  private def scaledImm(shift: Int): UInt =
    resize(io.queue.uop.imm << shift, addrWidth)

  private def pcrStoreSize(op: UInt): UInt =
    Mux(
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SB_PCR),
      1.U(sizeWidth.W),
      Mux(
        op === opcode(FrontendOpcodeDecodeTable.OP_HL_SH_PCR),
        2.U(sizeWidth.W),
        Mux(op === opcode(FrontendOpcodeDecodeTable.OP_HL_SW_PCR), 4.U(sizeWidth.W), 8.U(sizeWidth.W))))

  private def zeroExec: StoreDispatchExecResult = {
    val exec = Wire(new StoreDispatchExecResult(addrWidth, dataWidth, peIdWidth, stidWidth, tidWidth, sizeWidth, simtLaneWidth))
    exec := 0.U.asTypeOf(exec)
    exec
  }

  val op = io.queue.uop.opcode
  val src0 = resize(io.srcReadData(0), addrWidth)
  val src1 = resize(io.srcReadData(1), addrWidth)
  val src2 = resize(io.srcReadData(2), addrWidth)

  val supportedOpcode = WireDefault(false.B)
  val addrSourceMask = WireDefault(0.U(3.W))
  val addr = WireDefault(0.U(addrWidth.W))
  val size = WireDefault(0.U(sizeWidth.W))

  when(
    op === opcode(FrontendOpcodeDecodeTable.OP_HL_SB_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SD_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SH_PCR) ||
      op === opcode(FrontendOpcodeDecodeTable.OP_HL_SW_PCR)) {
    supportedOpcode := true.B
    addr := resize(io.queue.uop.pc + io.queue.uop.imm, addrWidth)
    size := pcrStoreSize(op)
  }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SDI)) {
    supportedOpcode := true.B
    addrSourceMask := "b010".U
    addr := src1 + scaledImm(3)
    size := 8.U
  }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SWI)) {
    supportedOpcode := true.B
    addrSourceMask := "b010".U
    addr := src1 + scaledImm(2)
    size := 4.U
  }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SBI)) {
    supportedOpcode := true.B
    addrSourceMask := "b010".U
    addr := src1 + resize(io.queue.uop.imm, addrWidth)
    size := 1.U
  }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_SD)) {
    supportedOpcode := true.B
    addrSourceMask := "b110".U
    addr := src1 + resize(src2 << 3, addrWidth)
    size := 8.U
  }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SDI)) {
    supportedOpcode := true.B
    addrSourceMask := "b001".U
    addr := src0 + scaledImm(3)
    size := 8.U
  }.elsewhen(op === opcode(FrontendOpcodeDecodeTable.OP_C_SWI)) {
    supportedOpcode := true.B
    addrSourceMask := "b001".U
    addr := src0 + scaledImm(2)
    size := 4.U
  }

  val candidate =
    io.enable &&
      io.queueValid &&
      io.queue.valid &&
      (io.queue.storeType === StoreSplitStoreType.Addr)
  val srcReadyVec = VecInit((0 until 3).map { idx =>
    !addrSourceMask(idx) || (io.queue.uop.src(idx).valid && io.srcReadReady(idx))
  })
  val addrSourceReady = srcReadyVec.reduce(_ && _)

  val exec = zeroExec
  exec.valid := candidate && supportedOpcode && addrSourceReady
  exec.addr := addr
  exec.data := 0.U
  exec.size := size
  exec.peId := resize(io.queue.uop.peId, peIdWidth)
  exec.stid := resize(io.queue.uop.threadId, stidWidth)
  exec.tid := resize(io.queue.uop.threadId, tidWidth)
  exec.stackValid := false.B
  exec.scalarIex := true.B
  exec.simtLane := 0.U

  io.exec := exec
  io.candidate := candidate
  io.supportedOpcode := supportedOpcode
  io.addrSourceMask := addrSourceMask
  io.addrSourceReady := addrSourceReady
  io.blockedBySource := candidate && supportedOpcode && !addrSourceReady
  io.blockedByUnsupported := candidate && !supportedOpcode
}
