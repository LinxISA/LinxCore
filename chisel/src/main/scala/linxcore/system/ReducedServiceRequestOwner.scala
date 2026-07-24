package linxcore.system

import chisel3._
import chisel3.util._

import linxcore.commit.{CommitTraceParams, CommitTraceRow}
import linxcore.common.InterfaceParams
import linxcore.rob.ROBID

object ReducedServiceRequestOwner {
  val RawAcrcRequestType: Int = 1
  val TrapIllegalServiceRequest: BigInt = 0x0000d001L
  val TrapIllegalServiceSequence: BigInt = 0x0000d002L
  val RawCompressedBstop: BigInt = 0x0000
  val RawBstop: BigInt = 0x00000001L
}

class ReducedServiceRequestIdentity(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val stid = UInt(p.threadIdWidth.W)
  val bid = new ROBID(p.robEntries)
  val gid = new ROBID(p.robEntries)
  val rid = new ROBID(p.robEntries)
}

class ReducedServiceRequestPayload(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val requestType = UInt(8.W)
  val pc = UInt(p.pcWidth.W)
  val insnLen = UInt(p.lenWidth.W)
  val insnRaw = UInt(p.insnWidth.W)
  val nextInsnLen = UInt(p.lenWidth.W)
  val nextHalfword = UInt(16.W)
  val nextInsnRaw = UInt(p.insnWidth.W)
  val identity = new ReducedServiceRequestIdentity(p)
  val a0 = UInt(p.immWidth.W)
  val a1 = UInt(p.immWidth.W)
  val a2 = UInt(p.immWidth.W)
  val a3 = UInt(p.immWidth.W)
  val a4 = UInt(p.immWidth.W)
  val a5 = UInt(p.immWidth.W)
  val a7 = UInt(p.immWidth.W)
  val a0PhysTag = UInt(p.physRegWidth.W)
}

class ReducedServiceRequestResponse(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val requestType = UInt(8.W)
  val identity = new ReducedServiceRequestIdentity(p)
  val a0 = UInt(p.immWidth.W)
}

class ReducedServiceWriteback(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val stid = UInt(p.threadIdWidth.W)
  val bid = new ROBID(p.robEntries)
  val gid = new ROBID(p.robEntries)
  val rid = new ROBID(p.robEntries)
  val pc = UInt(p.pcWidth.W)
  val physTag = UInt(p.physRegWidth.W)
  val data = UInt(p.immWidth.W)
}

class ReducedServiceRequestOwnerIO(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Bundle {
  val request = Flipped(Decoupled(new ReducedServiceRequestPayload(p)))
  val serviceRequest = Decoupled(new ReducedServiceRequestPayload(p))
  val serviceResponse = Flipped(Decoupled(new ReducedServiceRequestResponse(p)))
  val flush = Input(Bool())

  val completeReady = Input(Bool())
  val releaseReady = Input(Bool())
  val writebackReady = Input(Bool())
  val completeValid = Output(Bool())
  val completeRobValue = Output(UInt(p.robIndexWidth.W))
  val completeRow = Output(new CommitTraceRow(traceParams))
  val releaseValid = Output(Bool())
  val releaseRid = Output(new ROBID(p.robEntries))
  val writebackValid = Output(Bool())
  val writeback = Output(new ReducedServiceWriteback(p))

  val busy = Output(Bool())
  val controlFence = Output(Bool())
  val trappedInvalidRequestType = Output(Bool())
  val trappedIllegalSequence = Output(Bool())
  val responseIdentityMismatch = Output(Bool())
  val lateResponseSuppressed = Output(Bool())
}

class ReducedServiceRequestOwner(
    val p: InterfaceParams = InterfaceParams(),
    val traceParams: CommitTraceParams = CommitTraceParams())
    extends Module {
  require(traceParams.pcWidth == p.pcWidth, "trace PC width must match interface PC width")
  require(traceParams.insnWidth == p.insnWidth, "trace instruction width must match interface instruction width")
  require(traceParams.lenWidth == p.lenWidth, "trace length width must match interface length width")
  require(traceParams.dataWidth == p.immWidth, "trace data width must match interface data width")
  require(traceParams.robValueWidth == p.robIndexWidth, "trace ROB value width must match interface ROB index width")

  import ReducedServiceRequestOwner._

  val io = IO(new ReducedServiceRequestOwnerIO(p, traceParams))

  // Autonomous reduced semihost/service proxy for benchmark bring-up only.
  // This is not the full architectural ACRC trap/system owner and must not be
  // counted as cross-stack ACRC ISA coverage.  The normal completion/writeback
  // path is a proxy waiver until the real trap/privilege owner is integrated.
  val busy = RegInit(false.B)
  val cancelDrain = RegInit(false.B)
  val pending = Reg(new ReducedServiceRequestPayload(p))

  val zeroRow = WireDefault(0.U.asTypeOf(new CommitTraceRow(traceParams)))
  val zeroWriteback = WireDefault(0.U.asTypeOf(new ReducedServiceWriteback(p)))

  val legalRequestType = io.request.bits.requestType === RawAcrcRequestType.U
  val nextCompressedStop =
    (io.request.bits.nextInsnLen === 2.U) &&
      (io.request.bits.nextHalfword === RawCompressedBstop.U)
  // The current QEMU oracle exposes the adjacent stop as raw halfword 0x0000
  // C.BSTOP.  4-byte BSTOP adjacency remains cross-stack unresolved and is
  // rejected by this reduced proxy rather than silently broadening coverage.
  val legalStopAfter = nextCompressedStop
  val acceptingRequests = !io.flush && !busy && !cancelDrain
  val requestTrap = io.request.valid && acceptingRequests && (!legalRequestType || !legalStopAfter)
  val requestLegal = io.request.valid && acceptingRequests && legalRequestType && legalStopAfter

  val responseIdentityMatches =
    busy &&
      (io.serviceResponse.bits.requestType === pending.requestType) &&
      (io.serviceResponse.bits.identity.stid === pending.identity.stid) &&
      ROBID.equal(io.serviceResponse.bits.identity.bid, pending.identity.bid) &&
      ROBID.equal(io.serviceResponse.bits.identity.gid, pending.identity.gid) &&
      ROBID.equal(io.serviceResponse.bits.identity.rid, pending.identity.rid)

  val normalSideEffectsReady = io.completeReady && io.releaseReady && io.writebackReady
  val responseFire = !io.flush && io.serviceResponse.valid && responseIdentityMatches && normalSideEffectsReady
  val canceledResponseDrain = !io.flush && cancelDrain && io.serviceResponse.valid
  val trapFire = !io.flush && requestTrap && io.completeReady

  io.request.ready := acceptingRequests && Mux(requestTrap, io.completeReady, io.serviceRequest.ready)
  io.serviceRequest.valid := requestLegal
  io.serviceRequest.bits := io.request.bits
  io.serviceResponse.ready := !io.flush && (cancelDrain || (!busy) || (responseIdentityMatches && normalSideEffectsReady))

  io.completeValid := trapFire || responseFire
  io.completeRobValue := Mux(trapFire, io.request.bits.identity.rid.value, pending.identity.rid.value)
  io.completeRow := zeroRow
  io.releaseValid := responseFire
  io.releaseRid := ROBID.disabled(p.robEntries)
  io.writebackValid := responseFire
  io.writeback := zeroWriteback

  when(trapFire) {
    io.completeRow.valid := true.B
    io.completeRow.identity.bid := io.request.bits.identity.bid.value
    io.completeRow.identity.gid := io.request.bits.identity.gid.value
    io.completeRow.identity.rid := io.request.bits.identity.rid.value
    io.completeRow.rob.valid := io.request.bits.identity.rid.valid
    io.completeRow.rob.wrap := io.request.bits.identity.rid.wrap
    io.completeRow.rob.value := io.request.bits.identity.rid.value
    io.completeRow.pc := io.request.bits.pc
    io.completeRow.insn := io.request.bits.insnRaw
    io.completeRow.len := io.request.bits.insnLen
    io.completeRow.trap.valid := true.B
    io.completeRow.trap.cause := Mux(
      legalRequestType,
      TrapIllegalServiceSequence.U(traceParams.causeWidth.W),
      TrapIllegalServiceRequest.U(traceParams.causeWidth.W))
    io.completeRow.trap.arg0 := io.request.bits.pc
    io.completeRow.nextPc := io.request.bits.pc
  }.elsewhen(responseFire) {
    io.completeRow.valid := true.B
    io.completeRow.identity.bid := pending.identity.bid.value
    io.completeRow.identity.gid := pending.identity.gid.value
    io.completeRow.identity.rid := pending.identity.rid.value
    io.completeRow.rob.valid := pending.identity.rid.valid
    io.completeRow.rob.wrap := pending.identity.rid.wrap
    io.completeRow.rob.value := pending.identity.rid.value
    io.completeRow.pc := pending.pc
    io.completeRow.insn := pending.insnRaw
    io.completeRow.len := pending.insnLen
    io.completeRow.wb.valid := true.B
    io.completeRow.wb.reg := 2.U
    io.completeRow.wb.data := io.serviceResponse.bits.a0
    io.completeRow.dst.valid := true.B
    io.completeRow.dst.reg := 2.U
    io.completeRow.dst.data := io.serviceResponse.bits.a0
    io.completeRow.nextPc := pending.pc + pending.insnLen
    io.releaseRid := pending.identity.rid
    io.writeback.stid := pending.identity.stid
    io.writeback.bid := pending.identity.bid
    io.writeback.gid := pending.identity.gid
    io.writeback.rid := pending.identity.rid
    io.writeback.pc := pending.pc
    io.writeback.physTag := pending.a0PhysTag
    io.writeback.data := io.serviceResponse.bits.a0
  }

  io.busy := busy
  io.controlFence := busy || cancelDrain
  io.trappedInvalidRequestType := trapFire && !legalRequestType
  io.trappedIllegalSequence := trapFire && legalRequestType && !legalStopAfter
  io.responseIdentityMismatch := io.serviceResponse.valid && busy && !responseIdentityMatches
  io.lateResponseSuppressed := io.serviceResponse.valid && !io.flush && (cancelDrain || !busy)

  when(io.flush) {
    busy := false.B
    cancelDrain := busy || cancelDrain
  }.elsewhen(canceledResponseDrain) {
    cancelDrain := false.B
  }.elsewhen(io.serviceRequest.fire) {
    pending := io.request.bits
    busy := true.B
  }.elsewhen(responseFire) {
    busy := false.B
  }
}
