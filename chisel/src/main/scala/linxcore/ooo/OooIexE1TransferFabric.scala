package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, RRArbiter, Valid}

/** Elaboration-time ownership of one issue domain. */
final case class OooIexIssueDomainConfig(
    uopClass: Int,
    bankEnable: BigInt)

object OooIexIssueDomainConfig {
  def validate(p: OooParams, domains: Seq[OooIexIssueDomainConfig]): Unit = {
    require(domains.length == p.iexIssueDomainCount,
      "static IEX topology must define every physical issue domain")
    domains.zipWithIndex.foreach { case (domain, index) =>
      require(domain.uopClass >= 0 && domain.uopClass < p.iqClassCount,
        s"IEX domain $index names an invalid physical IQ class")
      require(domain.bankEnable > 0 &&
        domain.bankEnable < (BigInt(1) << p.iqBankCount),
        s"IEX domain $index needs a nonempty in-range bank mask")
    }
    for (left <- domains.indices; right <- left + 1 until domains.length) {
      require(domains(left).uopClass != domains(right).uopClass ||
        (domains(left).bankEnable & domains(right).bankEnable) == 0,
        s"IEX domains $left and $right overlap one class/bank owner")
    }
  }
}

class OooIexE1TransferFabricIO(val p: OooParams = OooParams())
    extends Bundle {
  val i2 = Flipped(Vec(p.iexIssueDomainCount,
    Decoupled(new OooIexI2Transaction(p))))
  val issueRelease = Decoupled(new OooIexIssueRelease(p))
  val e1 = Vec(p.iexIssueDomainCount,
    Decoupled(new OooIexExecuteTransaction(p)))

  val recoveryApply = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val loadCancel = Input(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))

  val pickClasses = Output(Vec(p.iexIssueDomainCount, OooUopClass()))
  val pickBankEnables = Output(Vec(p.iexIssueDomainCount,
    UInt(p.iqBankCount.W)))
  val releaseDomain = Valid(UInt(p.iexIssueDomainWidth.W))
  val rejected = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexE1TransferReject(p))))
  val killed = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexExecuteTransaction(p))))
  val occupied = Output(Vec(p.iexIssueDomainCount, Bool()))
  val empty = Output(Bool())
}

/** Static class/bank topology around class-specific retained E1 slots.
  *
  * The existing IQ has one exact terminal-release port, so simultaneous I2
  * candidates arbitrate before ownership transfer. A denied domain retains
  * I2; the winner's accept and release remain one fire. This fabric therefore
  * closes identity and recovery composition but does not claim multi-release
  * throughput.
  */
class OooIexE1TransferFabric(
    val p: OooParams,
    val domains: Seq[OooIexIssueDomainConfig]) extends Module {
  OooIexIssueDomainConfig.validate(p, domains)

  val io = IO(new OooIexE1TransferFabricIO(p))
  val slots = domains.zipWithIndex.map { case (domain, lane) =>
    Module(new OooIexE1TransferSlot(p, domain.uopClass, lane))
  }
  val releaseArbiter = Module(new RRArbiter(
    new OooIexIssueRelease(p), p.iexIssueDomainCount))

  for (((slot, domain), lane) <- slots.zip(domains).zipWithIndex) {
    slot.io.i2 <> io.i2(lane)
    releaseArbiter.io.in(lane) <> slot.io.issueRelease
    io.e1(lane) <> slot.io.e1
    slot.io.recoveryApply := io.recoveryApply
    slot.io.loadCancel := io.loadCancel

    io.pickClasses(lane) := OooUopClass.all(domain.uopClass)
    io.pickBankEnables(lane) := domain.bankEnable.U
    io.rejected(lane) := slot.io.rejected
    io.killed(lane) := slot.io.killed
    io.occupied(lane) := slot.io.occupied
  }

  io.issueRelease <> releaseArbiter.io.out
  io.releaseDomain.valid := releaseArbiter.io.out.valid
  io.releaseDomain.bits := releaseArbiter.io.chosen
  io.empty := !io.occupied.asUInt.orR
}
