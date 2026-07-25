package linxcore.frontend

import chisel3._
import chisel3.util.{Decoupled, Valid, log2Ceil}
import linxcore.common.InterfaceParams

class ISideL1IIO(
    val p: InterfaceParams = InterfaceParams(),
    val sets: Int = 64,
    val lineBytes: Int = 64)
    extends Bundle {
  val lookup = Flipped(Decoupled(new ISideFetchRequest(p, lineBytes)))
  val response = Decoupled(new ISideCacheCandidate(p, lineBytes))
  val refill = Flipped(Valid(new ISideL1IRefill(p, lineBytes)))
  val invalidate = Input(Bool())
  val innerFlush = Input(new IfuInnerFlush(p))
}

class ISideL1I(
    val p: InterfaceParams = InterfaceParams(),
    val sets: Int = 64,
    val lineBytes: Int = 64)
    extends Module {
  require(sets > 0 && (sets & (sets - 1)) == 0)
  require(lineBytes >= 8 && (lineBytes & (lineBytes - 1)) == 0)

  private val lineOffsetBits = log2Ceil(lineBytes)
  private val setWidth = math.max(1, log2Ceil(sets))
  private val lineTagWidth = p.pcWidth - lineOffsetBits

  val io = IO(new ISideL1IIO(p, sets, lineBytes))

  val valid = RegInit(VecInit(Seq.fill(sets)(false.B)))
  val physicalTags = RegInit(VecInit(Seq.fill(sets)(0.U(lineTagWidth.W))))
  val data = RegInit(VecInit(Seq.fill(sets)(0.U((lineBytes * 8).W))))

  val responseValid = RegInit(false.B)
  val response = RegInit(0.U.asTypeOf(new ISideCacheCandidate(p, lineBytes)))
  val killResponse =
    io.innerFlush.valid &&
      responseValid &&
      response.request.identity.threadId === io.innerFlush.threadId

  io.response.valid := responseValid && !killResponse
  io.response.bits := response
  io.lookup.ready := !responseValid || io.response.ready || killResponse

  val lookupLine = io.lookup.bits.lineVa(p.pcWidth - 1, lineOffsetBits)
  val lookupIndex =
    if (sets == 1) 0.U(setWidth.W) else lookupLine(setWidth - 1, 0)

  when(io.invalidate) {
    valid.foreach(_ := false.B)
  }.otherwise {
    when(io.refill.valid) {
      val refillLine = io.refill.bits.linePa(p.pcWidth - 1, lineOffsetBits)
      val refillIndex =
        if (sets == 1) 0.U(setWidth.W) else refillLine(setWidth - 1, 0)
      valid(refillIndex) := true.B
      physicalTags(refillIndex) := refillLine
      data(refillIndex) := io.refill.bits.lineData
    }
  }

  when(killResponse || io.response.fire) {
    responseValid := false.B
  }
  when(io.lookup.fire) {
    responseValid := true.B
    response := 0.U.asTypeOf(response)
    response.request := io.lookup.bits
    response.candidateValid := valid(lookupIndex)
    response.physicalTag := physicalTags(lookupIndex)
    response.lineData := data(lookupIndex)
  }
}
