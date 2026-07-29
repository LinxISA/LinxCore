package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, RRArbiter, Valid}

/** Elaboration-time ownership of one issue domain. */
final case class OooIexIssueDomainConfig(
    uopClass: Int,
    bankEnable: BigInt,
    releasePort: Int = 0)

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
      require(domain.releasePort >= 0 &&
        domain.releasePort < p.iexReleaseWidth,
        s"IEX domain $index names an invalid exact release port")
    }
    for (port <- 0 until p.iexReleaseWidth) {
      require(domains.exists(_.releasePort == port),
        s"IEX release port $port has no statically owned issue domain")
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
  val issueReleases = Vec(p.iexReleaseWidth,
    Decoupled(new OooIexIssueRelease(p)))
  def issueRelease = issueReleases(0)
  val e1 = Vec(p.iexIssueDomainCount,
    Decoupled(new OooIexExecuteTransaction(p)))

  val recoveryApply = Flipped(Valid(new OooResidencyRecoveryPlan(p)))
  val loadCancel = Input(Vec(p.iexLoadCancelPorts,
    Valid(new OooIexLoadCancel(p))))

  val pickClasses = Output(Vec(p.iexIssueDomainCount, OooUopClass()))
  val pickBankEnables = Output(Vec(p.iexIssueDomainCount,
    UInt(p.iqBankCount.W)))
  val releaseDomains = Vec(p.iexReleaseWidth,
    Valid(UInt(p.iexIssueDomainWidth.W)))
  def releaseDomain = releaseDomains(0)
  val rejected = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexE1TransferReject(p))))
  val killed = Output(Vec(p.iexIssueDomainCount,
    Valid(new OooIexExecuteTransaction(p))))
  val occupied = Output(Vec(p.iexIssueDomainCount, Bool()))
  val empty = Output(Bool())
}

/** Static class/bank topology around class-specific retained E1 slots.
  *
  * One fair arbiter serves each statically owned exact release port. Domains
  * sharing a port serialize and retain denied I2 owners; domains on distinct
  * ports may transfer together. Every winner's accept and release remain one
  * fire.
  */
class OooIexE1TransferFabric(
    val p: OooParams,
    val domains: Seq[OooIexIssueDomainConfig]) extends Module {
  OooIexIssueDomainConfig.validate(p, domains)

  val io = IO(new OooIexE1TransferFabricIO(p))
  val slots = domains.zipWithIndex.map { case (domain, lane) =>
    Module(new OooIexE1TransferSlot(p, domain.uopClass, lane))
  }

  for (((slot, domain), lane) <- slots.zip(domains).zipWithIndex) {
    slot.io.i2 <> io.i2(lane)
    io.e1(lane) <> slot.io.e1
    slot.io.recoveryApply := io.recoveryApply
    slot.io.loadCancel := io.loadCancel

    io.pickClasses(lane) := OooUopClass.all(domain.uopClass)
    io.pickBankEnables(lane) := domain.bankEnable.U
    io.rejected(lane) := slot.io.rejected
    io.killed(lane) := slot.io.killed
    io.occupied(lane) := slot.io.occupied
  }

  for (port <- 0 until p.iexReleaseWidth) {
    val lanes = domains.indices.filter(domains(_).releasePort == port)
    val releaseArbiter = Module(new RRArbiter(
      new OooIexIssueRelease(p), lanes.length))
    for ((lane, localIndex) <- lanes.zipWithIndex) {
      releaseArbiter.io.in(localIndex) <> slots(lane).io.issueRelease
    }
    io.issueReleases(port) <> releaseArbiter.io.out
    io.releaseDomains(port).valid := releaseArbiter.io.out.valid
    io.releaseDomains(port).bits := VecInit(lanes.map(_.U))(
      releaseArbiter.io.chosen)
  }
  io.empty := !io.occupied.asUInt.orR
}
