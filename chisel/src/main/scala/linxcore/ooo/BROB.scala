package linxcore.ooo

import chisel3._
import chisel3.util._
import linxcore.params.CoreParams
import linxcore.top.interface._

class BROBIO(val p: CoreParams) extends Bundle {
  val prepare = Flipped(Decoupled(new D3RenameGroup(p)))
  val prepared = Output(new BROBPrepared(p))
  val publishFire = Input(Bool())
  val release = Flipped(Valid(new BROBReleaseTxn(p)))
  val releaseAccepted = Valid(new RobIdentity(p))
  val releaseRejected = Valid(new RobIdentity(p))
  val recoveryPrepare = Flipped(Valid(new RecoveryPlan(p)))
  val recoveryApply = Flipped(Valid(new RecoveryPlan(p)))
}

class BROB(val p: CoreParams) extends Module {
  val io = IO(new BROBIO(p))

  private val stidWidth = InterfaceWidth.index(p.ooo.stidCount)
  private def safeStid(stid: UInt): UInt =
    if (p.ooo.stidCount == 1) 0.U(stidWidth.W) else stid

  val used = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(PrefixPacketContract.countWidth(p.ooo.brobEntriesPerStid).W))))
  val tail = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(p.nativeBidWidth.W))))
  val generation = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.brobGenerationWidth.W))))
  val currentValid = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(false.B)))
  val currentBid = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.nativeBidWidth.W))))
  val currentGeneration = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.brobGenerationWidth.W))))
  val tableValid = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    VecInit(Seq.fill(p.ooo.brobEntriesPerStid)(false.B)))))
  val tableGeneration = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    VecInit(Seq.fill(p.ooo.brobEntriesPerStid)(
      0.U(p.brobGenerationWidth.W))))))
  val head = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(0.U(p.nativeBidWidth.W))))
  val headGeneration = RegInit(VecInit(Seq.fill(p.ooo.stidCount)(
    0.U(p.brobGenerationWidth.W))))

  val stid = safeStid(io.prepare.bits.entries(0).uop.decoded.rob.stid)
  val prepared = Wire(new BROBPrepared(p))
  prepared := 0.U.asTypeOf(prepared)
  prepared.count := io.prepare.bits.count
  val scanTail = Wire(Vec(p.ooo.d3PrefixWidth + 1, UInt(p.nativeBidWidth.W)))
  val scanGen = Wire(Vec(p.ooo.d3PrefixWidth + 1, UInt(p.brobGenerationWidth.W)))
  val scanCurrentValid = Wire(Vec(p.ooo.d3PrefixWidth + 1, Bool()))
  val scanCurrentBid = Wire(Vec(p.ooo.d3PrefixWidth + 1, UInt(p.nativeBidWidth.W)))
  val scanCurrentGen = Wire(Vec(p.ooo.d3PrefixWidth + 1, UInt(p.brobGenerationWidth.W)))
  val allocCount = Wire(Vec(p.ooo.d3PrefixWidth + 1,
    UInt(PrefixPacketContract.countWidth(p.ooo.d3PrefixWidth).W)))
  scanTail(0) := tail(stid)
  scanGen(0) := generation(stid)
  scanCurrentValid(0) := currentValid(stid)
  scanCurrentBid(0) := currentBid(stid)
  scanCurrentGen(0) := currentGeneration(stid)
  allocCount(0) := 0.U
  for (lane <- 0 until p.ooo.d3PrefixWidth) {
    val active = lane.U < io.prepare.bits.count
    val startsBlock = active && io.prepare.bits.entries(lane).uop.decoded.immediateValid
    val stopsBlock = active && io.prepare.bits.entries(lane).uop.decoded.immediate(0)
    val bid = Mux(startsBlock, scanTail(lane), scanCurrentBid(lane))
    val gen = Mux(startsBlock, scanGen(lane), scanCurrentGen(lane))
    prepared.entries(lane).valid := active
    prepared.entries(lane).stid := stid
    prepared.entries(lane).bid := bid
    prepared.entries(lane).brobGeneration := gen
    prepared.entries(lane).allocated := startsBlock
    val nextTailWide = scanTail(lane) +& startsBlock.asUInt
    val wrap = nextTailWide >= p.ooo.brobEntriesPerStid.U
    scanTail(lane + 1) := nextTailWide(p.nativeBidWidth - 1, 0)
    scanGen(lane + 1) := scanGen(lane) + wrap.asUInt
    scanCurrentValid(lane + 1) := Mux(active, !stopsBlock, scanCurrentValid(lane))
    scanCurrentBid(lane + 1) := Mux(startsBlock, bid, scanCurrentBid(lane))
    scanCurrentGen(lane + 1) := Mux(startsBlock, gen, scanCurrentGen(lane))
    allocCount(lane + 1) := allocCount(lane) + startsBlock.asUInt
  }
  io.prepared := prepared
  io.prepare.ready := io.prepare.bits.count <= p.ooo.d3PrefixWidth.U &&
    used(stid) + allocCount(p.ooo.d3PrefixWidth) <= p.ooo.brobEntriesPerStid.U

  when(io.prepare.valid && io.publishFire && io.prepare.ready) {
    tail(stid) := scanTail(p.ooo.d3PrefixWidth)
    generation(stid) := scanGen(p.ooo.d3PrefixWidth)
    currentValid(stid) := scanCurrentValid(p.ooo.d3PrefixWidth)
    currentBid(stid) := scanCurrentBid(p.ooo.d3PrefixWidth)
    currentGeneration(stid) := scanCurrentGen(p.ooo.d3PrefixWidth)
    used(stid) := used(stid) + allocCount(p.ooo.d3PrefixWidth)
    for (lane <- 0 until p.ooo.d3PrefixWidth) {
      when(prepared.entries(lane).valid && prepared.entries(lane).allocated) {
        tableValid(stid)(prepared.entries(lane).bid) := true.B
        tableGeneration(stid)(prepared.entries(lane).bid) :=
          prepared.entries(lane).brobGeneration
      }
    }
  }

  val rel = io.release.bits.entries(0)
  val relStid = safeStid(rel.stid)
  val releaseExact = io.release.valid && io.release.bits.count =/= 0.U &&
    rel.stid < p.ooo.stidCount.U &&
    rel.bid === head(relStid) &&
    rel.brobGeneration === headGeneration(relStid) &&
    tableValid(relStid)(rel.bid) &&
    tableGeneration(relStid)(rel.bid) === rel.brobGeneration
  val relRob = Wire(new RobIdentity(p))
  relRob := rel
  io.releaseAccepted.valid := releaseExact
  io.releaseAccepted.bits := relRob
  io.releaseRejected.valid := io.release.valid && io.release.bits.count =/= 0.U &&
    !releaseExact
  io.releaseRejected.bits := relRob
  when(io.release.valid && io.release.bits.count =/= 0.U) {
    when(releaseExact) {
      tableValid(relStid)(rel.bid) := false.B
      used(relStid) := used(relStid) - 1.U
      val nextHead = head(relStid) +& 1.U
      val wrap = nextHead >= p.ooo.brobEntriesPerStid.U
      head(relStid) := nextHead(p.nativeBidWidth - 1, 0)
      headGeneration(relStid) := headGeneration(relStid) + wrap.asUInt
    }
  }

  when(io.recoveryApply.valid) {
    val recStid = safeStid(io.recoveryApply.bits.trigger.stid)
    tail(recStid) := io.recoveryApply.bits.firstKilled.bid
    generation(recStid) := io.recoveryApply.bits.firstKilled.brobGeneration
    used(recStid) := used(recStid) - io.recoveryApply.bits.killedGroupCount
  }
}
