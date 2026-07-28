package linxcore.ooo

import chisel3._
import chisel3.util.{Decoupled, log2Ceil}
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
  val out = Decoupled(new OooD1DecodedPacket(oooP))

  val rawCounts = Output(Vec(oooP.stidCount, UInt(countWidth.W)))
  val rawEligibleMask = Output(UInt(oooP.stidCount.W))
  val fusionHeld = Output(Vec(oooP.stidCount, Bool()))
  val malformedInput = Output(Bool())
}

/** Production seam from fixed-four-wide IFU D1 to canonical OOO D1.
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
  val d1 = Module(new OooD1ProductionDecode(oooP))

  raw.io.ifuD1 <> io.ifuD1
  raw.io.selectStid := io.selectStid
  raw.io.fence := io.fence
  raw.io.flush := io.flush
  d1.io.in <> raw.io.out

  for (stid <- 0 until oooP.stidCount) {
    d1.io.cancel(stid) :=
      io.cancel(stid) || (io.flush.valid && io.flush.threadId === stid.U)
  }
  io.out <> d1.io.out

  io.ifuThreadId := raw.io.ifuThreadId
  io.rawCounts := raw.io.counts
  io.rawEligibleMask := raw.io.eligibleMask
  io.fusionHeld := d1.io.held
  io.malformedInput := raw.io.malformedInput
}
