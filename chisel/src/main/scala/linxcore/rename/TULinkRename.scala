package linxcore.rename

import chisel3._
import chisel3.util.log2Ceil

import linxcore.bctrl.BID
import linxcore.common._
import linxcore.rob.ROBID

class TULinkRenameMapQueueEntry(
    val robEntries: Int,
    val mapQDepth: Int,
    val physTagWidth: Int)
    extends Bundle {
  val valid = Bool()
  val retired = Bool()
  val bid = new ROBID(robEntries)
  val rid = new ROBID(robEntries)
  val gid = new ROBID(robEntries)
  val seq = new ROBID(mapQDepth)
  val physTag = UInt(physTagWidth.W)
}

class TULinkResolvedOperand(
    val p: InterfaceParams = InterfaceParams(),
    val mapQDepth: Int = 32)
    extends Bundle {
  val valid = Bool()
  val operandClass = OperandClass()
  val relTag = UInt(p.archRegWidth.W)
  val seq = new ROBID(mapQDepth)
  val physTag = UInt(p.physRegWidth.W)
  val hit = Bool()
  val underflow = Bool()
}

class TULinkResolvedDestination(
    val p: InterfaceParams = InterfaceParams(),
    val mapQDepth: Int = 32)
    extends Bundle {
  val valid = Bool()
  val kind = DestinationKind()
  val relTag = UInt(p.archRegWidth.W)
  val seq = new ROBID(mapQDepth)
  val physTag = UInt(p.physRegWidth.W)
  val allocated = Bool()
  val blocked = Bool()
}

class TULinkRenameIO(
    val p: InterfaceParams = InterfaceParams(),
    val localRegsT: Int = 32,
    val localRegsU: Int = 32,
    val mapQDepth: Int = 32,
    val bidWidth: Int = BID.DefaultWidth)
    extends Bundle {
  private val countWidth = log2Ceil(Seq(localRegsT, localRegsU, mapQDepth).max + 1)

  val in = Input(new DecodedUop(p))
  val renameValid = Input(Bool())

  val ready = Output(Bool())
  val accepted = Output(Bool())

  val src = Output(Vec(3, new TULinkResolvedOperand(p, mapQDepth)))
  val dst = Output(new TULinkResolvedDestination(p, mapQDepth))

  val tSeq = Output(new ROBID(mapQDepth))
  val uSeq = Output(new ROBID(mapQDepth))
  val needsTAlloc = Output(Bool())
  val needsUAlloc = Output(Bool())
  val blockedByTAlloc = Output(Bool())
  val blockedByUAlloc = Output(Bool())
  val sourceUnderflowMask = Output(UInt(3.W))

  val tAllocPhysTag = Output(UInt(p.physRegWidth.W))
  val uAllocPhysTag = Output(UInt(p.physRegWidth.W))
  val tMapQValidMask = Output(UInt(mapQDepth.W))
  val uMapQValidMask = Output(UInt(mapQDepth.W))
  val tUsedEntries = Output(UInt(countWidth.W))
  val uUsedEntries = Output(UInt(countWidth.W))
  val tUsedPhys = Output(UInt(countWidth.W))
  val uUsedPhys = Output(UInt(countWidth.W))
}

class TULinkRename(
    val p: InterfaceParams = InterfaceParams(),
    val localRegsT: Int = 32,
    val localRegsU: Int = 32,
    val mapQDepth: Int = 32,
    val bidWidth: Int = BID.DefaultWidth)
    extends Module {
  require(mapQDepth > 1 && (mapQDepth & (mapQDepth - 1)) == 0, "T/U mapQ depth must be a power of two")
  require(localRegsT > 0 && localRegsT <= (1 << p.physRegWidth), "T local register count must fit physRegWidth")
  require(localRegsU > 0 && localRegsU <= (1 << p.physRegWidth), "U local register count must fit physRegWidth")

  private val countWidth = log2Ceil(Seq(localRegsT, localRegsU, mapQDepth).max + 1)
  private val offsetWidth = log2Ceil(mapQDepth + 1)

  val io = IO(new TULinkRenameIO(p, localRegsT, localRegsU, mapQDepth, bidWidth))

  private def disabledSeq: ROBID =
    ROBID.disabled(mapQDepth)

  private def incPhys(ptr: UInt, regs: Int): UInt =
    Mux(ptr === (regs - 1).U, 0.U(p.physRegWidth.W), ptr + 1.U)

  private def allocBlocked(usedEntries: UInt, usedPhys: UInt, localRegs: Int): Bool =
    ((usedEntries + 1.U) > mapQDepth.U) || ((usedPhys + 1.U) > localRegs.U)

  private def zeroEntry: TULinkRenameMapQueueEntry =
    0.U.asTypeOf(new TULinkRenameMapQueueEntry(p.robEntries, mapQDepth, p.physRegWidth))

  private def resolveSource(
      src: DecodedOperand,
      cls: OperandClass.Type,
      allocSeq: ROBID,
      usedEntries: UInt,
      mapQ: Vec[TULinkRenameMapQueueEntry]): TULinkResolvedOperand = {
    val out = Wire(new TULinkResolvedOperand(p, mapQDepth))
    out := 0.U.asTypeOf(out)
    out.operandClass := src.operandClass
    out.relTag := src.relTag
    out.seq := disabledSeq

    val isLink = io.in.valid && src.valid && (src.operandClass === cls)
    val rawOffsetPlusOne = src.relTag +& 1.U
    val offsetFitsMap = rawOffsetPlusOne <= mapQDepth.U
    val safeOffset = Wire(UInt(offsetWidth.W))
    safeOffset := Mux(offsetFitsMap, rawOffsetPlusOne(offsetWidth - 1, 0), 1.U(offsetWidth.W))
    val seq = ROBID.sub(allocSeq, safeOffset)
    val hasDepth = rawOffsetPlusOne <= usedEntries
    val hit = isLink && offsetFitsMap && hasDepth && mapQ(seq.value).valid

    out.valid := isLink
    out.seq := Mux(isLink, seq, disabledSeq)
    out.physTag := Mux(hit, mapQ(seq.value).physTag, 0.U)
    out.hit := hit
    out.underflow := isLink && !hit
    out
  }

  val tMapQ = RegInit(VecInit(Seq.fill(mapQDepth)(zeroEntry)))
  val uMapQ = RegInit(VecInit(Seq.fill(mapQDepth)(zeroEntry)))
  val tAllocSeq = RegInit(ROBID.zero(mapQDepth))
  val uAllocSeq = RegInit(ROBID.zero(mapQDepth))
  val tAllocPhys = RegInit(0.U(p.physRegWidth.W))
  val uAllocPhys = RegInit(0.U(p.physRegWidth.W))
  val tUsedEntries = RegInit(0.U(countWidth.W))
  val uUsedEntries = RegInit(0.U(countWidth.W))
  val tUsedPhys = RegInit(0.U(countWidth.W))
  val uUsedPhys = RegInit(0.U(countWidth.W))

  val srcResolved = Wire(Vec(3, new TULinkResolvedOperand(p, mapQDepth)))
  for (idx <- 0 until 3) {
    val tResolved = resolveSource(io.in.src(idx), OperandClass.T, tAllocSeq, tUsedEntries, tMapQ)
    val uResolved = resolveSource(io.in.src(idx), OperandClass.U, uAllocSeq, uUsedEntries, uMapQ)
    srcResolved(idx) := Mux(tResolved.valid, tResolved, uResolved)
  }

  val dstIsT = io.in.valid && io.in.dst(0).valid && (io.in.dst(0).kind === DestinationKind.T)
  val dstIsU = io.in.valid && io.in.dst(0).valid && (io.in.dst(0).kind === DestinationKind.U)
  val tBlocked = dstIsT && allocBlocked(tUsedEntries, tUsedPhys, localRegsT)
  val uBlocked = dstIsU && allocBlocked(uUsedEntries, uUsedPhys, localRegsU)
  val sourceUnderflowMask = VecInit(srcResolved.map(_.underflow)).asUInt
  val canAccept = !sourceUnderflowMask.orR && !tBlocked && !uBlocked
  val accepted = io.renameValid && io.in.valid && canAccept

  when(accepted && dstIsT) {
    tMapQ(tAllocSeq.value).valid := true.B
    tMapQ(tAllocSeq.value).retired := false.B
    tMapQ(tAllocSeq.value).bid := io.in.bid
    tMapQ(tAllocSeq.value).rid := io.in.rid
    tMapQ(tAllocSeq.value).gid := io.in.gid
    tMapQ(tAllocSeq.value).seq := tAllocSeq
    tMapQ(tAllocSeq.value).physTag := tAllocPhys
    tAllocSeq := ROBID.inc(tAllocSeq)
    tAllocPhys := incPhys(tAllocPhys, localRegsT)
    tUsedEntries := tUsedEntries + 1.U
    tUsedPhys := tUsedPhys + 1.U
  }

  when(accepted && dstIsU) {
    uMapQ(uAllocSeq.value).valid := true.B
    uMapQ(uAllocSeq.value).retired := false.B
    uMapQ(uAllocSeq.value).bid := io.in.bid
    uMapQ(uAllocSeq.value).rid := io.in.rid
    uMapQ(uAllocSeq.value).gid := io.in.gid
    uMapQ(uAllocSeq.value).seq := uAllocSeq
    uMapQ(uAllocSeq.value).physTag := uAllocPhys
    uAllocSeq := ROBID.inc(uAllocSeq)
    uAllocPhys := incPhys(uAllocPhys, localRegsU)
    uUsedEntries := uUsedEntries + 1.U
    uUsedPhys := uUsedPhys + 1.U
  }

  io.ready := canAccept
  io.accepted := accepted
  io.src := srcResolved
  io.tSeq := tAllocSeq
  io.uSeq := uAllocSeq
  io.needsTAlloc := dstIsT
  io.needsUAlloc := dstIsU
  io.blockedByTAlloc := tBlocked
  io.blockedByUAlloc := uBlocked
  io.sourceUnderflowMask := sourceUnderflowMask
  io.tAllocPhysTag := tAllocPhys
  io.uAllocPhysTag := uAllocPhys
  io.tMapQValidMask := VecInit(tMapQ.map(_.valid)).asUInt
  io.uMapQValidMask := VecInit(uMapQ.map(_.valid)).asUInt
  io.tUsedEntries := tUsedEntries
  io.uUsedEntries := uUsedEntries
  io.tUsedPhys := tUsedPhys
  io.uUsedPhys := uUsedPhys

  val dst = Wire(new TULinkResolvedDestination(p, mapQDepth))
  dst := 0.U.asTypeOf(dst)
  dst.valid := dstIsT || dstIsU
  dst.kind := io.in.dst(0).kind
  dst.relTag := io.in.dst(0).relTag
  dst.seq := Mux(dstIsT, tAllocSeq, Mux(dstIsU, uAllocSeq, disabledSeq))
  dst.physTag := Mux(dstIsT, tAllocPhys, Mux(dstIsU, uAllocPhys, 0.U))
  dst.allocated := accepted && (dstIsT || dstIsU)
  dst.blocked := tBlocked || uBlocked
  io.dst := dst
}
