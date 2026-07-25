package linxcore.bctrl

import chisel3._
import chisel3.util._
import linxcore.common.InterfaceParams
import linxcore.rob.ROBID

class TemplateParentIdentity(val p: InterfaceParams) extends Bundle {
  val generation = UInt(16.W)
  val stid = UInt(p.threadIdWidth.W)
  val peId = UInt(p.peIdWidth.W)
  val pc = UInt(p.pcWidth.W)
  val raw = UInt(p.insnWidth.W)
  val opcode = UInt(p.opcodeWidth.W)
  val bid = new ROBID(p.robEntries)
  val gid = new ROBID(p.robEntries)
  val rid = new ROBID(p.robEntries)
  val robSlot = UInt(p.robIndexWidth.W)
  val blockBidValid = Bool()
  val blockBid = UInt(p.blockBidWidth.W)
  val commitIdentityBid = UInt(32.W)
  val commitIdentityGid = UInt(32.W)
  val commitIdentityRid = UInt(32.W)
}

class TemplateMapSnapshot(val archRegs: Int, val physTagWidth: Int) extends Bundle {
  require(archRegs >= 24, "template register ring requires architectural registers 2 through 23")
  require(physTagWidth > 0, "physical tag width must be positive")

  val smap = Vec(archRegs, UInt(physTagWidth.W))
  val cmap = Vec(archRegs, UInt(physTagWidth.W))
}

class TemplateRenameSidecar(
    val p: InterfaceParams,
    val archRegs: Int,
    val physTagWidth: Int)
    extends Bundle {
  val identity = new TemplateParentIdentity(p)
  val src0Imm = UInt(p.immWidth.W)
  val rangeM = UInt(p.archRegWidth.W)
  val rangeN = UInt(p.archRegWidth.W)
  val map = new TemplateMapSnapshot(archRegs, physTagWidth)
}

class TemplateIssueRequest(val p: InterfaceParams) extends Bundle {
  val identity = new TemplateParentIdentity(p)
  val oldSp = UInt(p.immWidth.W)
  val srcData0 = UInt(p.immWidth.W)
  val srcData1 = UInt(p.immWidth.W)
  val srcData2 = UInt(p.immWidth.W)
}

class TemplateParentRequest(
    val p: InterfaceParams,
    val archRegs: Int,
    val physTagWidth: Int)
    extends Bundle {
  val sidecar = new TemplateRenameSidecar(p, archRegs, physTagWidth)
  val oldSp = UInt(p.immWidth.W)
  val srcData0 = UInt(p.immWidth.W)
  val srcData1 = UInt(p.immWidth.W)
  val srcData2 = UInt(p.immWidth.W)
}

object TemplateParentIdentity {
  private def sameRobId(lhs: ROBID, rhs: ROBID): Bool =
    lhs.valid === rhs.valid && lhs.wrap === rhs.wrap && lhs.value === rhs.value

  def sameKey(lhs: TemplateParentIdentity, rhs: TemplateParentIdentity): Bool =
    lhs.stid === rhs.stid &&
      sameRobId(lhs.bid, rhs.bid) &&
      sameRobId(lhs.gid, rhs.gid) &&
      sameRobId(lhs.rid, rhs.rid) &&
      lhs.robSlot === rhs.robSlot &&
      lhs.generation === rhs.generation

  def sameKeyExceptGeneration(lhs: TemplateParentIdentity, rhs: TemplateParentIdentity): Bool =
    lhs.stid === rhs.stid &&
      sameRobId(lhs.bid, rhs.bid) &&
      sameRobId(lhs.gid, rhs.gid) &&
      sameRobId(lhs.rid, rhs.rid) &&
      lhs.robSlot === rhs.robSlot
}

class TemplateRenameSidecarTableIO(
    val p: InterfaceParams,
    val archRegs: Int,
    val physTagWidth: Int,
    val stidCount: Int)
    extends Bundle {
  val enqueue = Flipped(Decoupled(new TemplateRenameSidecar(p, archRegs, physTagWidth)))
  val issue = Flipped(Decoupled(new TemplateIssueRequest(p)))
  val parentRequest = Decoupled(new TemplateParentRequest(p, archRegs, physTagWidth))

  val cancel = Flipped(Valid(new TemplateParentIdentity(p)))
  val releaseDecodeFence = Flipped(Valid(new TemplateParentIdentity(p)))
  val releaseIssueFence = Flipped(Valid(new TemplateParentIdentity(p)))
  val releaseMemoryFence = Flipped(Valid(new TemplateParentIdentity(p)))
  val globalClear = Input(Bool())

  val occupiedMask = Output(UInt(stidCount.W))
  val decodeFenceMask = Output(UInt(stidCount.W))
  val issueFenceMask = Output(UInt(stidCount.W))
  val memoryFenceMask = Output(UInt(stidCount.W))
  val enqueueCount = Output(UInt(32.W))
  val transferCount = Output(UInt(32.W))
  val mismatchDropCount = Output(UInt(32.W))
  val staleGenerationDropCount = Output(UInt(32.W))
}

class TemplateRenameSidecarTable(
    val p: InterfaceParams = InterfaceParams(),
    val archRegs: Int = 32,
    val physTagWidth: Int = 6,
    val stidCount: Int = 1)
    extends Module {
  require(stidCount > 0, "sidecar table must track at least one STID")
  require(BigInt(stidCount) <= (BigInt(1) << p.threadIdWidth), "STID count must fit the interface")

  val io = IO(new TemplateRenameSidecarTableIO(p, archRegs, physTagWidth, stidCount))

  val occupied = RegInit(VecInit(Seq.fill(stidCount)(false.B)))
  val entries = Reg(Vec(stidCount, new TemplateRenameSidecar(p, archRegs, physTagWidth)))
  val decodeFence = RegInit(VecInit(Seq.fill(stidCount)(false.B)))
  val issueFence = RegInit(VecInit(Seq.fill(stidCount)(false.B)))
  val memoryFence = RegInit(VecInit(Seq.fill(stidCount)(false.B)))

  val enqueueCount = RegInit(0.U(32.W))
  val transferCount = RegInit(0.U(32.W))
  val mismatchDropCount = RegInit(0.U(32.W))
  val staleGenerationDropCount = RegInit(0.U(32.W))

  private def laneMatches(stid: UInt): Vec[Bool] =
    VecInit((0 until stidCount).map(idx => stid === idx.U(p.threadIdWidth.W)))

  private def selectSidecar(matches: Vec[Bool]): TemplateRenameSidecar = {
    val selected = WireDefault(0.U.asTypeOf(new TemplateRenameSidecar(p, archRegs, physTagWidth)))
    when(matches.asUInt.orR) {
      selected := Mux1H(matches, entries)
    }
    selected
  }

  private def commandMatches(
      command: ValidIO[TemplateParentIdentity],
      idx: Int): Bool =
    command.valid && TemplateParentIdentity.sameKey(command.bits, entries(idx).identity)

  val enqueueLanes = laneMatches(io.enqueue.bits.identity.stid)
  val enqueueInRange = enqueueLanes.asUInt.orR
  val enqueueLaneAvailable = Mux1H(
    enqueueLanes,
    VecInit((0 until stidCount).map(idx =>
      !occupied(idx) && !decodeFence(idx) && !issueFence(idx) && !memoryFence(idx)))
  )
  io.enqueue.ready := enqueueInRange && enqueueLaneAvailable && !io.globalClear

  val issueLanes = laneMatches(io.issue.bits.identity.stid)
  val issueInRange = issueLanes.asUInt.orR
  val selectedOccupied = Mux1H(issueLanes, occupied)
  val selectedEntry = selectSidecar(issueLanes)
  val issueExactMatch =
    issueInRange && selectedOccupied &&
      TemplateParentIdentity.sameKey(io.issue.bits.identity, selectedEntry.identity)
  val issueStaleGeneration =
    issueInRange && selectedOccupied &&
      TemplateParentIdentity.sameKeyExceptGeneration(io.issue.bits.identity, selectedEntry.identity) &&
      io.issue.bits.identity.generation =/= selectedEntry.identity.generation
  val selectedEntryCancelled =
    io.cancel.valid && issueInRange && selectedOccupied &&
      TemplateParentIdentity.sameKey(io.cancel.bits, selectedEntry.identity)
  val issueDominated = io.globalClear || selectedEntryCancelled

  io.parentRequest.valid := io.issue.valid && issueExactMatch && !issueDominated
  io.parentRequest.bits.sidecar := selectedEntry
  io.parentRequest.bits.oldSp := io.issue.bits.oldSp
  io.parentRequest.bits.srcData0 := io.issue.bits.srcData0
  io.parentRequest.bits.srcData1 := io.issue.bits.srcData1
  io.parentRequest.bits.srcData2 := io.issue.bits.srcData2

  // Exact matches transfer only with the downstream Decoupled fire. Rejected
  // mismatches are consumed locally and cannot create a parent request.
  io.issue.ready := !issueDominated && Mux(issueExactMatch, io.parentRequest.ready, true.B)
  val issueReject = io.issue.fire && !issueExactMatch

  when(io.globalClear) {
    for (idx <- 0 until stidCount) {
      occupied(idx) := false.B
      decodeFence(idx) := false.B
      issueFence(idx) := false.B
      memoryFence(idx) := false.B
    }
  }.otherwise {
    for (idx <- 0 until stidCount) {
      when(commandMatches(io.cancel, idx)) {
        occupied(idx) := false.B
        decodeFence(idx) := false.B
        issueFence(idx) := false.B
        memoryFence(idx) := false.B
      }.otherwise {
        when(commandMatches(io.releaseDecodeFence, idx)) {
          decodeFence(idx) := false.B
        }
        when(commandMatches(io.releaseIssueFence, idx)) {
          issueFence(idx) := false.B
        }
        when(commandMatches(io.releaseMemoryFence, idx)) {
          memoryFence(idx) := false.B
        }
      }

      when(io.enqueue.fire && enqueueLanes(idx)) {
        entries(idx) := io.enqueue.bits
        occupied(idx) := true.B
        decodeFence(idx) := true.B
        issueFence(idx) := true.B
        memoryFence(idx) := true.B
      }

      when(io.parentRequest.fire && issueLanes(idx)) {
        occupied(idx) := false.B
      }
    }
  }

  when(io.enqueue.fire) {
    enqueueCount := enqueueCount + 1.U
  }
  when(io.parentRequest.fire) {
    transferCount := transferCount + 1.U
  }
  when(issueReject) {
    mismatchDropCount := mismatchDropCount + 1.U
    when(issueStaleGeneration) {
      staleGenerationDropCount := staleGenerationDropCount + 1.U
    }
  }

  assert(!io.parentRequest.fire || io.issue.fire)
  assert(!io.parentRequest.fire || issueExactMatch)
  assert(!(issueDominated && (io.issue.fire || io.parentRequest.fire)))
  assert(!(selectedEntryCancelled && io.parentRequest.valid))
  assert(!(io.enqueue.fire && io.parentRequest.fire &&
    io.enqueue.bits.identity.stid === io.issue.bits.identity.stid))

  io.occupiedMask := occupied.asUInt
  io.decodeFenceMask := decodeFence.asUInt
  io.issueFenceMask := issueFence.asUInt
  io.memoryFenceMask := memoryFence.asUInt
  io.enqueueCount := enqueueCount
  io.transferCount := transferCount
  io.mismatchDropCount := mismatchDropCount
  io.staleGenerationDropCount := staleGenerationDropCount
}
