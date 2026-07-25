package linxcore.frontend

import chisel3._
import chisel3.util.{Decoupled, Valid, log2Ceil}
import linxcore.common.InterfaceParams

class ISideITLBIO(
    val p: InterfaceParams = InterfaceParams(),
    val entries: Int = 16,
    val lineBytes: Int = 64,
    val pageBytes: Int = 4096)
    extends Bundle {
  val lookup = Flipped(Decoupled(new ISideFetchRequest(p, lineBytes)))
  val response = Decoupled(new ISideTranslationResult(p, lineBytes, pageBytes))
  val refill = Flipped(Valid(new ISideItlbRefill(p, pageBytes)))
  val invalidate = Input(Bool())
  val innerFlush = Input(new IfuInnerFlush(p))
}

class ISideITLB(
    val p: InterfaceParams = InterfaceParams(),
    val entries: Int = 16,
    val lineBytes: Int = 64,
    val pageBytes: Int = 4096)
    extends Module {
  require(entries > 0 && (entries & (entries - 1)) == 0)
  require(pageBytes > lineBytes && (pageBytes & (pageBytes - 1)) == 0)

  private val indexWidth = math.max(1, log2Ceil(entries))
  private val pageOffsetBits = log2Ceil(pageBytes)
  private val vpnWidth = p.pcWidth - pageOffsetBits

  val io = IO(new ISideITLBIO(p, entries, lineBytes, pageBytes))

  val valid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val vpns = RegInit(VecInit(Seq.fill(entries)(0.U(vpnWidth.W))))
  val ppns = RegInit(VecInit(Seq.fill(entries)(0.U(vpnWidth.W))))
  val executable = RegInit(VecInit(Seq.fill(entries)(false.B)))

  val responseValid = RegInit(false.B)
  val response = RegInit(0.U.asTypeOf(new ISideTranslationResult(p, lineBytes, pageBytes)))
  val killResponse =
    responseValid &&
      IfuFlushContract.kills(response.request.identity, response.request.transactionId, io.innerFlush)

  io.response.valid := responseValid && !killResponse
  io.response.bits := response
  io.lookup.ready := !responseValid || io.response.ready || killResponse

  val lookupVpn = io.lookup.bits.lineVa(p.pcWidth - 1, pageOffsetBits)
  val lookupIndex =
    if (entries == 1) 0.U(indexWidth.W) else lookupVpn(indexWidth - 1, 0)
  val lookupHit = valid(lookupIndex) && vpns(lookupIndex) === lookupVpn

  when(io.invalidate) {
    valid.foreach(_ := false.B)
  }.otherwise {
    when(io.refill.valid) {
      val refillIndex =
        if (entries == 1) 0.U(indexWidth.W) else io.refill.bits.vpn(indexWidth - 1, 0)
      valid(refillIndex) := true.B
      vpns(refillIndex) := io.refill.bits.vpn
      ppns(refillIndex) := io.refill.bits.ppn
      executable(refillIndex) := io.refill.bits.executable
    }
  }

  when(killResponse || io.response.fire) {
    responseValid := false.B
  }
  when(io.lookup.fire) {
    responseValid := true.B
    response := 0.U.asTypeOf(response)
    response.request := io.lookup.bits
    response.hit := lookupHit
    response.accessFault := lookupHit && !executable(lookupIndex)
    response.ppn := Mux(lookupHit, ppns(lookupIndex), 0.U)
  }
}
