package linxcore.top

import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder, Queue, log2Ceil}
import linxcore.common.InterfaceParams
import linxcore.frontend.{ISideLineReadRequest, ISideLineResponse}

class IfuLineMemoryReadRequest(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Bundle {
  val tag = UInt(p.uopUidWidth.W)
  val linePa = UInt(p.pcWidth.W)
}

class IfuLineMemoryReadResponse(
    val p: InterfaceParams = InterfaceParams(),
    val lineBytes: Int = 64)
    extends Bundle {
  val tag = UInt(p.uopUidWidth.W)
  val linePa = UInt(p.pcWidth.W)
  val lineData = UInt((lineBytes * 8).W)
}

class IfuLineMemoryBridgeIO(
    val p: InterfaceParams = InterfaceParams(),
    val entries: Int = 4,
    val lineBytes: Int = 64)
    extends Bundle {
  private val countWidth = log2Ceil(entries + 1)

  val ifuRequest = Flipped(Decoupled(new ISideLineReadRequest(p, lineBytes)))
  val memoryRequest = Decoupled(new IfuLineMemoryReadRequest(p, lineBytes))
  val memoryResponse = Flipped(Decoupled(new IfuLineMemoryReadResponse(p, lineBytes)))
  val ifuRefill = Decoupled(new ISideLineResponse(p, lineBytes))

  val outstandingMask = Output(UInt(entries.W))
  val issuedMask = Output(UInt(entries.W))
  val responsePendingMask = Output(UInt(entries.W))
  val outstandingCount = Output(UInt(countWidth.W))
  val requestQueueCount = Output(UInt(countWidth.W))
  val staleResponse = Output(Bool())
}

/** Tagged 64-byte memory adapter for the production IFU.
  *
  * Each accepted IFU miss receives an independent monotonic memory tag. A
  * bounded table retains the complete `ISideLineReadRequest` until the tagged
  * response is accepted by the IFU, so no response identity is reconstructed
  * from PC, physical line address, or another request ID. Issued requests are
  * deliberately not cancelled by speculative recovery: the IFU miss table
  * keeps such requests as physical-state orphans until their response drains.
  */
class IfuLineMemoryBridge(
    val p: InterfaceParams = InterfaceParams(),
    val entries: Int = 4,
    val lineBytes: Int = 64)
    extends Module {
  require(entries > 0)
  require(lineBytes >= 8 && (lineBytes & (lineBytes - 1)) == 0)

  val io = IO(new IfuLineMemoryBridgeIO(p, entries, lineBytes))

  val valid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val issued = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val responsePending = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val responseData = RegInit(VecInit(Seq.fill(entries)(0.U((lineBytes * 8).W))))
  val memoryTags = RegInit(VecInit(Seq.fill(entries)(0.U(p.uopUidWidth.W))))
  val requests = RegInit(
    VecInit(Seq.fill(entries)(0.U.asTypeOf(new ISideLineReadRequest(p, lineBytes)))))
  val nextMemoryTag = RegInit(0.U(p.uopUidWidth.W))

  val freeMask = VecInit(valid.map(v => !v)).asUInt
  val freeValid = freeMask.orR
  val freeIndex = PriorityEncoder(freeMask)

  val requestQueue = Module(new Queue(new IfuLineMemoryReadRequest(p, lineBytes), entries))
  requestQueue.io.enq.valid := io.ifuRequest.valid && freeValid
  requestQueue.io.enq.bits.tag := nextMemoryTag
  requestQueue.io.enq.bits.linePa := io.ifuRequest.bits.linePa
  io.ifuRequest.ready := freeValid && requestQueue.io.enq.ready

  io.memoryRequest.valid := requestQueue.io.deq.valid
  io.memoryRequest.bits := requestQueue.io.deq.bits
  requestQueue.io.deq.ready := io.memoryRequest.ready
  val issueFire = io.memoryRequest.fire

  val issueMatch = Wire(Vec(entries, Bool()))
  val responseMatch = Wire(Vec(entries, Bool()))
  for (entry <- 0 until entries) {
    issueMatch(entry) :=
      valid(entry) &&
        !issued(entry) &&
        memoryTags(entry) === io.memoryRequest.bits.tag
    responseMatch(entry) :=
      valid(entry) &&
        (issued(entry) || (issueFire && issueMatch(entry))) &&
        !responsePending(entry) &&
        memoryTags(entry) === io.memoryResponse.bits.tag &&
        requests(entry).linePa === io.memoryResponse.bits.linePa
  }

  val issueMatchMask = issueMatch.asUInt
  val responseMatchMask = responseMatch.asUInt
  val responseMatchValid = responseMatchMask.orR
  val responseIndex = PriorityEncoder(responseMatchMask)
  val pendingMask = responsePending.asUInt
  val pendingValid = pendingMask.orR
  val pendingIndex = PriorityEncoder(pendingMask)
  val pendingRequest = requests(pendingIndex)

  io.ifuRefill.valid := pendingValid
  io.ifuRefill.bits := 0.U.asTypeOf(io.ifuRefill.bits)
  io.ifuRefill.bits.peId := pendingRequest.request.identity.peId
  io.ifuRefill.bits.transactionId := pendingRequest.request.transactionId
  io.ifuRefill.bits.threadId := pendingRequest.request.identity.threadId
  io.ifuRefill.bits.fetchPacketUid := pendingRequest.request.identity.fetchPacketUid
  io.ifuRefill.bits.fetchSeq := pendingRequest.request.identity.fetchSeq
  io.ifuRefill.bits.checkpointId := pendingRequest.request.identity.checkpointId
  io.ifuRefill.bits.epoch := pendingRequest.request.identity.epoch
  io.ifuRefill.bits.lineVa := pendingRequest.request.lineVa
  io.ifuRefill.bits.linePa := pendingRequest.linePa
  io.ifuRefill.bits.lineData := responseData(pendingIndex)

  io.memoryResponse.ready := true.B
  io.staleResponse := io.memoryResponse.valid && !responseMatchValid

  val allocateFire = io.ifuRequest.fire
  val responseCapture = io.memoryResponse.fire && responseMatchValid
  val refillFire = io.ifuRefill.fire

  when(allocateFire) {
    val liveTagCollision = VecInit(
      (0 until entries).map(entry => valid(entry) && memoryTags(entry) === nextMemoryTag)).asUInt.orR
    assert(!liveTagCollision, "IFU memory tag must not alias a live request")
    assert(requestQueue.io.enq.fire, "IFU line-table allocation and memory enqueue must be atomic")
    valid(freeIndex) := true.B
    issued(freeIndex) := false.B
    responsePending(freeIndex) := false.B
    memoryTags(freeIndex) := nextMemoryTag
    requests(freeIndex) := io.ifuRequest.bits
    nextMemoryTag := nextMemoryTag + 1.U
  }

  when(issueFire) {
    assert(PopCount(issueMatchMask) === 1.U, "memory request must name one retained IFU row")
    for (entry <- 0 until entries) {
      when(issueMatch(entry)) {
        issued(entry) := true.B
      }
    }
  }

  when(responseCapture) {
    assert(PopCount(responseMatchMask) === 1.U, "memory response must name one issued IFU row")
    responsePending(responseIndex) := true.B
    responseData(responseIndex) := io.memoryResponse.bits.lineData
  }

  when(refillFire) {
    valid(pendingIndex) := false.B
    issued(pendingIndex) := false.B
    responsePending(pendingIndex) := false.B
  }

  io.outstandingMask := valid.asUInt
  io.issuedMask := issued.asUInt
  io.responsePendingMask := responsePending.asUInt
  io.outstandingCount := PopCount(valid)
  io.requestQueueCount := requestQueue.io.count
}
