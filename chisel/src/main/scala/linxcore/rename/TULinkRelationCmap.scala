package linxcore.rename

import chisel3._
import chisel3.util.log2Ceil

import linxcore.common._
import linxcore.rob.ROBID

class TULinkRelationCmapEntry(
    val p: InterfaceParams = InterfaceParams(),
    val mapQDepth: Int = 32)
    extends Bundle {
  val bid = new ROBID(p.robEntries)
  val gid = new ROBID(p.robEntries)
  val seq = new ROBID(mapQDepth)
}

class TULinkRelationCmapIO(
    val p: InterfaceParams = InterfaceParams(),
    val mapQDepth: Int = 32,
    val cmapDepth: Int = 8,
    val stidWidth: Int = 8)
    extends Bundle {
  private val countWidth = log2Ceil(cmapDepth + 1)

  val in = Input(new TULinkRetireSource(p, mapQDepth, stidWidth))
  val clear = Input(Bool())
  val commandReady = Input(Bool())

  val inReady = Output(Bool())
  val inAccepted = Output(Bool())
  val command = Output(new TULinkRetireCommand(mapQDepth))
  val commandFire = Output(Bool())

  val unsupportedDst = Output(Bool())
  val preReleaseT = Output(Bool())
  val preReleaseU = Output(Bool())
  val pressureReleaseT = Output(Bool())
  val pressureReleaseU = Output(Bool())
  val pendingMark = Output(Bool())
  val pendingPostReleaseT = Output(Bool())
  val pendingPostReleaseU = Output(Bool())
  val tCount = Output(UInt(countWidth.W))
  val uCount = Output(UInt(countWidth.W))
}

class TULinkRelationCmap(
    val p: InterfaceParams = InterfaceParams(),
    val mapQDepth: Int = 32,
    val cmapDepth: Int = 8,
    val releaseThreshold: Int = 4,
    val stidWidth: Int = 8)
    extends Module {
  require(mapQDepth > 1 && (mapQDepth & (mapQDepth - 1)) == 0, "T/U mapQ depth must be a power of two")
  require(cmapDepth > 1 && (cmapDepth & (cmapDepth - 1)) == 0, "relation cmap depth must be a power of two")
  require(releaseThreshold >= 0 && releaseThreshold < cmapDepth, "release threshold must fit relation cmap")

  private val ptrWidth = log2Ceil(cmapDepth)
  private val countWidth = log2Ceil(cmapDepth + 1)

  val io = IO(new TULinkRelationCmapIO(p, mapQDepth, cmapDepth, stidWidth))

  private def zeroEntry: TULinkRelationCmapEntry =
    0.U.asTypeOf(new TULinkRelationCmapEntry(p, mapQDepth))

  private def zeroCommand: TULinkRetireCommand =
    0.U.asTypeOf(new TULinkRetireCommand(mapQDepth))

  private def incPtr(ptr: UInt): UInt =
    Mux(ptr === (cmapDepth - 1).U, 0.U(ptrWidth.W), ptr + 1.U)(ptrWidth - 1, 0)

  private def decPtr(ptr: UInt): UInt =
    Mux(ptr === 0.U, (cmapDepth - 1).U(ptrWidth.W), ptr - 1.U)(ptrWidth - 1, 0)

  private def sameBidGid(entry: TULinkRelationCmapEntry): Bool =
    ROBID.equal(entry.bid, io.in.bid) && ROBID.equal(entry.gid, io.in.gid)

  val tEntries = RegInit(VecInit(Seq.fill(cmapDepth)(zeroEntry)))
  val uEntries = RegInit(VecInit(Seq.fill(cmapDepth)(zeroEntry)))
  val tHead = RegInit(0.U(ptrWidth.W))
  val uHead = RegInit(0.U(ptrWidth.W))
  val tTail = RegInit(0.U(ptrWidth.W))
  val uTail = RegInit(0.U(ptrWidth.W))
  val tCount = RegInit(0.U(countWidth.W))
  val uCount = RegInit(0.U(countWidth.W))

  val pendingMarkValid = RegInit(false.B)
  val pendingMarkKind = RegInit(DestinationKind.None)
  val pendingMarkSeq = RegInit(0.U.asTypeOf(new ROBID(mapQDepth)))
  val pendingPostReleaseT = RegInit(false.B)
  val pendingPostReleaseU = RegInit(false.B)

  val inDstT = io.in.valid && io.in.dstValid && (io.in.dstKind === DestinationKind.T)
  val inDstU = io.in.valid && io.in.dstValid && (io.in.dstKind === DestinationKind.U)
  val unsupportedDst = io.in.valid && io.in.dstValid && !inDstT && !inDstU

  val tBack = tEntries(decPtr(tTail))
  val uBack = uEntries(decPtr(uTail))
  val tNonEmpty = tCount =/= 0.U
  val uNonEmpty = uCount =/= 0.U
  val preReleaseT = io.in.valid && tNonEmpty && (io.in.isLast || !sameBidGid(tBack))
  val preReleaseU = io.in.valid && uNonEmpty && (io.in.isLast || !sameBidGid(uBack))
  val capacityReleaseT = inDstT && (tCount === cmapDepth.U)
  val capacityReleaseU = inDstU && (uCount === cmapDepth.U)
  val pressureReleaseT = inDstT && (tCount >= releaseThreshold.U)
  val pressureReleaseU = inDstU && (uCount >= releaseThreshold.U)
  val pendingAny = pendingMarkValid || pendingPostReleaseT || pendingPostReleaseU

  val command = Wire(new TULinkRetireCommand(mapQDepth))
  command := zeroCommand

  when(pendingMarkValid) {
    command.valid := true.B
    command.kind := pendingMarkKind
    command.seq := pendingMarkSeq
    command.dealloc := false.B
  }.elsewhen(pendingPostReleaseT || preReleaseT || capacityReleaseT) {
    command.valid := tNonEmpty
    command.kind := DestinationKind.T
    command.seq := tEntries(tHead).seq
    command.dealloc := true.B
  }.elsewhen(pendingPostReleaseU || preReleaseU || capacityReleaseU) {
    command.valid := uNonEmpty
    command.kind := DestinationKind.U
    command.seq := uEntries(uHead).seq
    command.dealloc := true.B
  }

  val commandFire = command.valid && io.commandReady
  val commandReleasesT =
    commandFire && !pendingMarkValid && (command.kind === DestinationKind.T) && command.dealloc
  val commandReleasesU =
    commandFire && !pendingMarkValid && (command.kind === DestinationKind.U) && command.dealloc

  val dstQueueFull = (inDstT && (tCount === cmapDepth.U) && !commandReleasesT) ||
    (inDstU && (uCount === cmapDepth.U) && !commandReleasesU)
  val inReady = !io.clear && !pendingAny && !preReleaseT && !preReleaseU &&
    !capacityReleaseT && !capacityReleaseU && !unsupportedDst && !dstQueueFull
  val inAccepted = io.in.valid && inReady

  when(io.clear) {
    tHead := 0.U
    uHead := 0.U
    tTail := 0.U
    uTail := 0.U
    tCount := 0.U
    uCount := 0.U
    pendingMarkValid := false.B
    pendingMarkKind := DestinationKind.None
    pendingMarkSeq := 0.U.asTypeOf(new ROBID(mapQDepth))
    pendingPostReleaseT := false.B
    pendingPostReleaseU := false.B
  }.otherwise {
    when(commandFire && pendingMarkValid) {
      pendingMarkValid := false.B
    }.elsewhen(commandReleasesT) {
      tHead := incPtr(tHead)
      tCount := tCount - 1.U
      when(pendingPostReleaseT) {
        pendingPostReleaseT := false.B
      }
    }.elsewhen(commandReleasesU) {
      uHead := incPtr(uHead)
      uCount := uCount - 1.U
      when(pendingPostReleaseU) {
        pendingPostReleaseU := false.B
      }
    }

    when(inAccepted && inDstT) {
      val entry = Wire(new TULinkRelationCmapEntry(p, mapQDepth))
      entry.bid := io.in.bid
      entry.gid := io.in.gid
      entry.seq := io.in.tSeq
      tEntries(tTail) := entry
      tTail := incPtr(tTail)
      tCount := tCount + 1.U
      pendingMarkValid := true.B
      pendingMarkKind := DestinationKind.T
      pendingMarkSeq := io.in.tSeq
      pendingPostReleaseT := io.in.isLast || pressureReleaseT
    }.elsewhen(inAccepted && inDstU) {
      val entry = Wire(new TULinkRelationCmapEntry(p, mapQDepth))
      entry.bid := io.in.bid
      entry.gid := io.in.gid
      entry.seq := io.in.uSeq
      uEntries(uTail) := entry
      uTail := incPtr(uTail)
      uCount := uCount + 1.U
      pendingMarkValid := true.B
      pendingMarkKind := DestinationKind.U
      pendingMarkSeq := io.in.uSeq
      pendingPostReleaseU := io.in.isLast || pressureReleaseU
    }
  }

  io.inReady := inReady
  io.inAccepted := inAccepted
  io.command := command
  io.commandFire := commandFire
  io.unsupportedDst := unsupportedDst
  io.preReleaseT := preReleaseT
  io.preReleaseU := preReleaseU
  io.pressureReleaseT := pressureReleaseT
  io.pressureReleaseU := pressureReleaseU
  io.pendingMark := pendingMarkValid
  io.pendingPostReleaseT := pendingPostReleaseT
  io.pendingPostReleaseU := pendingPostReleaseU
  io.tCount := tCount
  io.uCount := uCount
}
