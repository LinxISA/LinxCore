package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, RRArbiter, Valid}

/** Elaboration-time projection of one picker/execution lane. */
final case class OooIexIssueDomainConfig(
    classBankEnables: Seq[BigInt],
    releasePort: Int = 0,
    name: String = "",
    capabilities: BigInt = OooIexDomainCapability.ValidMask)

object OooIexIssueDomainConfig {
  def singleClass(
      p: OooParams,
      uopClass: Int,
      bankEnable: BigInt,
      releasePort: Int = 0,
      name: String = ""): OooIexIssueDomainConfig = {
    require(uopClass >= 0 && uopClass < p.iqClassCount,
      "single-class IEX domain names an invalid IQ class")
    OooIexIssueDomainConfig(
      Seq.tabulate(p.iqClassCount)(index =>
        if (index == uopClass) bankEnable else BigInt(0)),
      releasePort, name, OooIexDomainCapability.ValidMask)
  }

  def validate(p: OooParams, domains: Seq[OooIexIssueDomainConfig]): Unit = {
    require(domains.length == p.iexIssueDomainCount,
      "static IEX topology must define every picker function")
    domains.zipWithIndex.foreach { case (domain, index) =>
      require(domain.classBankEnables.length == p.iqClassCount,
        s"IEX domain $index must define every physical IQ class")
      require(domain.classBankEnables.exists(_ != 0) &&
        domain.classBankEnables.forall(mask => mask >= 0 &&
          mask < (BigInt(1) << p.iqBankCount)),
        s"IEX domain $index needs nonempty in-range class/bank masks")
      require(domain.releasePort >= 0 &&
        domain.releasePort < p.iexReleaseWidth,
        s"IEX domain $index names an invalid exact release port")
      require(domain.capabilities != 0 &&
        (domain.capabilities & ~OooIexDomainCapability.ValidMask) == 0,
        s"IEX domain $index needs a nonempty declared capability mask")
    }
    for (port <- 0 until p.iexReleaseWidth) {
      require(domains.exists(_.releasePort == port),
        s"IEX release port $port has no statically owned picker")
    }
    for (left <- domains.indices; right <- left + 1 until domains.length) {
      val overlaps = domains(left).classBankEnables
        .zip(domains(right).classBankEnables)
        .exists { case (leftMask, rightMask) =>
          (leftMask & rightMask) != 0
        }
      val capabilityOverlap =
        (domains(left).capabilities & domains(right).capabilities) != 0
      require(!overlaps || !capabilityOverlap,
        s"IEX pickers $left and $right overlap one class/bank/capability")
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

  val pickBankEnables = Output(Vec(p.iexIssueDomainCount,
    Vec(p.iqClassCount, UInt(p.iqBankCount.W))))
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

/** Static class/bank/capability topology around retained picker/E1 slots.
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
    Module(new OooIexE1TransferSlot(p, domain.classBankEnables, lane,
      domain.capabilities))
  }

  for (((slot, domain), lane) <- slots.zip(domains).zipWithIndex) {
    slot.io.i2 <> io.i2(lane)
    io.e1(lane) <> slot.io.e1
    slot.io.recoveryApply := io.recoveryApply
    slot.io.loadCancel := io.loadCancel

    io.pickBankEnables(lane) := VecInit(
      domain.classBankEnables.map(_.U(p.iqBankCount.W)))
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
