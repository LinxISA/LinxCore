package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, Valid, log2Ceil}
import linxcore.common.InterfaceParams
import linxcore.frontend.{D1InstructionGroup, IfuInnerFlush}

class OooIfuD1IngressIO(
    val ifuP: InterfaceParams = InterfaceParams(),
    val oooP: OooParams = OooParams(),
    val depthPerStid: Int = 16)
    extends Bundle {
  private val countWidth = log2Ceil(depthPerStid + 1)

  val ifuThreadId = Output(UInt(ifuP.threadIdWidth.W))
  val ifuD1 = Flipped(Decoupled(new D1InstructionGroup(ifuP)))
  val selectStid = Input(UInt(oooP.stidWidth.W))
  val flush = Input(new IfuInnerFlush(ifuP))
  /** Stops new target-STID movement without clearing retained frontend state. */
  val fence = Input(Vec(oooP.stidCount, Bool()))
  val cancel = Input(Vec(oooP.stidCount, Bool()))
  val retiringBargBpcnValid = Input(Vec(oooP.stidCount, Bool()))
  val retiringBargBpcn = Input(Vec(oooP.stidCount, UInt(oooP.pcWidth.W)))

  val ctuParentClaim = Decoupled(new OooCtuParentClaim(oooP))
  val ctuExpansionPlan = Flipped(Decoupled(new OooCtuExpansionPlan(oooP)))
  val ctuChild = Flipped(Decoupled(new OooCtuCanonicalChild(oooP)))
  val ctuPlanRejected = Valid(new OooCtuPlanReject(oooP))
  val ctuChildRejected = Valid(new OooCtuChildReject(oooP))
  val ctuRecoveryPrepare = Flipped(Decoupled(
    new OooGlobalRecoveryRequest(oooP)))
  val ctuRecoveryPrepared = Valid(new OooCtuRecoveryPrepared(oooP))
  val ctuRecoveryRejected = Valid(new OooCtuRecoveryReject(oooP))
  val ctuRecoveryApply = Input(Bool())
  val ctuRecoveryAbort = Input(Bool())

  val out = Decoupled(new OooD1DecodedPacket(oooP))

  val rawCounts = Output(Vec(oooP.stidCount, UInt(countWidth.W)))
  val rawEligibleMask = Output(UInt(oooP.stidCount.W))
  val fusionHeld = Output(Vec(oooP.stidCount, Bool()))
  val ctuOccupied = Output(Vec(oooP.stidCount, Bool()))
  val ctuActive = Output(Vec(oooP.stidCount, Bool()))
  val complexBlocked = Output(Vec(oooP.stidCount, Bool()))
  val malformedInput = Output(Bool())
}

/** Canonical seam from fixed-four-wide IFU D1 to OOO D1.
  *
  * CTU and complex parents leave this seam as exact diverted-parent sidebands;
  * they are never reconstructed from a mask or allowed to mutate ROB/RF/LSU.
  */
class OooIfuD1Ingress(
    val ifuP: InterfaceParams = InterfaceParams(),
    val oooP: OooParams = OooParams(),
    val depthPerStid: Int = 16)
    extends Module {
  val io = IO(new OooIfuD1IngressIO(ifuP, oooP, depthPerStid))

  val raw = Module(new OooIfuRawIngress(ifuP, oooP, depthPerStid))
  val d1 = Module(new OooD1FusionDecode(oooP))
  val ctu = Module(new OooCtuIngressBridge(oooP))

  raw.io.ifuD1 <> io.ifuD1
  raw.io.selectStid := io.selectStid
  raw.io.fence := io.fence
  raw.io.retiringBargBpcnValid := io.retiringBargBpcnValid
  raw.io.retiringBargBpcn := io.retiringBargBpcn
  raw.io.flush := io.flush
  d1.io.in <> raw.io.out

  for (stid <- 0 until oooP.stidCount) {
    d1.io.cancel(stid) :=
      io.cancel(stid) || (io.flush.valid && io.flush.threadId === stid.U)
    ctu.io.cancel(stid) := d1.io.cancel(stid)
  }
  ctu.io.fence := io.fence
  ctu.io.in <> d1.io.out
  io.out <> ctu.io.out

  io.ctuParentClaim <> ctu.io.parentClaim
  ctu.io.expansionPlan <> io.ctuExpansionPlan
  ctu.io.child <> io.ctuChild
  io.ctuPlanRejected := ctu.io.planRejected
  io.ctuChildRejected := ctu.io.childRejected
  ctu.io.recoveryPrepare <> io.ctuRecoveryPrepare
  io.ctuRecoveryPrepared := ctu.io.recoveryPrepared
  io.ctuRecoveryRejected := ctu.io.recoveryRejected
  ctu.io.recoveryApply := io.ctuRecoveryApply
  ctu.io.recoveryAbort := io.ctuRecoveryAbort

  io.ifuThreadId := raw.io.ifuThreadId
  io.rawCounts := raw.io.counts
  io.rawEligibleMask := raw.io.eligibleMask &
    ~ctu.io.occupied.asUInt & ~ctu.io.active.asUInt
  io.fusionHeld := d1.io.held
  io.ctuOccupied := ctu.io.occupied
  io.ctuActive := ctu.io.active
  io.complexBlocked := ctu.io.blockedByComplex
  io.malformedInput := raw.io.malformedInput
}
