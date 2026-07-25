package linxcore.bctrl

import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import linxcore.frontend.FrontendOpcodeDecodeTable
import org.scalatest.funsuite.AnyFunSuite

object BlockControlTemplateSequencerReference {
  import TemplateRenameSidecarReference.Key

  sealed trait ChildKind
  case object Store extends ChildKind
  case object Restore extends ChildKind

  final case class Child(
      index: Int,
      archReg: Int,
      physTag: Int,
      addr: BigInt,
      data: BigInt,
      last: Boolean)
  final case class Plan(
      key: Key,
      opcode: Int,
      pc: BigInt,
      oldSp: BigInt,
      imm: BigInt,
      newSp: BigInt,
      nextPc: BigInt,
      redirectValid: Boolean,
      childKind: ChildKind,
      children: Vector[Child])

  def buildPlan(
      key: Key,
      opcode: Int,
      pc: BigInt,
      oldSp: BigInt,
      imm: BigInt,
      m: Int,
      n: Int,
      smap: Vector[Int],
      registerValues: Vector[BigInt],
      loadValues: Vector[BigInt],
      srcData0: BigInt): Plan = {
    val ring = BlockControlTemplateSequencer.legalRing(m, n).toVector
    require(smap.size >= 24 && registerValues.size >= 24)
    require(loadValues.size == ring.size)
    val newSp = BlockControlTemplateSequencer.nextSp(opcode, oldSp, imm)
    val addresses =
      BlockControlTemplateSequencer.childAddresses(opcode, oldSp, imm, ring.size).toVector
    val isEntry = opcode == FrontendOpcodeDecodeTable.OP_FENTRY
    val children = ring.indices.map { idx =>
      Child(
        index = idx,
        archReg = ring(idx),
        physTag = smap(ring(idx)),
        addr = addresses(idx),
        data = if (isEntry) registerValues(ring(idx)) else loadValues(idx),
        last = idx == ring.size - 1
      )
    }.toVector
    val isFretRa = opcode == FrontendOpcodeDecodeTable.OP_FRET_RA
    val isFretStk = opcode == FrontendOpcodeDecodeTable.OP_FRET_STK
    val nextPc =
      if (isFretRa) srcData0
      else if (isFretStk) loadValues.head
      else pc + 4
    Plan(
      key = key,
      opcode = opcode,
      pc = pc,
      oldSp = oldSp,
      imm = imm,
      newSp = newSp,
      nextPc = nextPc,
      redirectValid = isFretRa || isFretStk,
      childKind = if (isEntry) Store else Restore,
      children = children
    )
  }

  final class Lifecycle(val plan: Plan) {
    var completionSelected = false
    var committed = false
    var cancelled = false
    var illegalDiscardAttempts = 0
    var selfRestarts = 0
    var drainIndex = 0
    var decodeFence = true
    var issueFence = true
    var memoryFence = true
    var tailZero = false

    def terminal: Boolean = completionSelected && !cancelled
    def retained: Int = if (cancelled || tailZero) 0 else plan.children.size - drainIndex
    def currentChild: Option[Child] =
      Option.when(committed && !cancelled && !tailZero && drainIndex < plan.children.size)(
        plan.children(drainIndex)
      )

    def selectCompletion(): Unit = {
      require(!cancelled)
      completionSelected = true
    }

    def commit(key: Key): Boolean = {
      val accepted = completionSelected && !cancelled && key == plan.key
      if (accepted) committed = true
      accepted
    }

    def recover(key: Key, killsActive: Boolean): Unit = {
      if (key == plan.key) {
        selfRestarts += 1
      } else if (killsActive && !completionSelected) {
        cancelled = true
        decodeFence = false
        issueFence = false
        memoryFence = false
      } else if (killsActive) {
        illegalDiscardAttempts += 1
      }
    }

    def acceptChild(ready: Boolean): Option[Child] = {
      val held = currentChild
      if (ready) {
        held.foreach { child =>
          drainIndex += 1
          if (child.last) {
            decodeFence = false
            issueFence = false
            memoryFence = false
            tailZero = true
          }
        }
      }
      held
    }
  }

  final class ResponseTracker(expectedKey: Key, expectedIndex: Int) {
    var staleDrops = 0
    var acceptedData = Option.empty[BigInt]

    def response(key: Key, index: Int, data: BigInt): Boolean = {
      val matches = key == expectedKey && index == expectedIndex && acceptedData.isEmpty
      if (matches) acceptedData = Some(data) else staleDrops += 1
      matches
    }
  }
}

class BlockControlTemplateSequencerSpec extends AnyFunSuite {
  import BlockControlTemplateSequencerReference._
  import TemplateRenameSidecarReference.{Key, RobId}

  private val key = Key(
    stid = 0,
    bid = RobId(valid = true, wrap = false, value = 25),
    gid = RobId(valid = true, wrap = true, value = 29),
    rid = RobId(valid = true, wrap = false, value = 29),
    robSlot = 29,
    generation = 7
  )
  private val smap = Vector.tabulate(32)(_ + 40)
  private val registerValues = Vector.tabulate(32)(idx => BigInt(idx) * 1000 + 7)

  private def plan(
      opcode: Int,
      m: Int = 10,
      n: Int = 19,
      oldSp: BigInt = 231408,
      imm: BigInt = 160,
      loads: Vector[BigInt] = Vector.tabulate(10)(idx => BigInt(70000 + idx)),
      srcData0: BigInt = 73440): Plan =
    buildPlan(
      key,
      opcode,
      pc = 70512,
      oldSp,
      imm,
      m,
      n,
      smap,
      registerValues,
      loads,
      srcData0
    )

  test("legal register order covers normal and wrapped CTGen ranges") {
    assert(BlockControlTemplateSequencer.legalRing(10, 19) == (10 to 19))
    assert(BlockControlTemplateSequencer.legalRing(22, 3) == Seq(22, 23, 2, 3))
    assert(BlockControlTemplateSequencer.legalRing(2, 23) == (2 to 23))
    assertThrows[IllegalArgumentException](BlockControlTemplateSequencer.legalRing(1, 3))
    assertThrows[IllegalArgumentException](BlockControlTemplateSequencer.legalRing(2, 24))
  }

  test("all four opcodes use immediate-only low and high frame arithmetic") {
    val low = Seq(
      FrontendOpcodeDecodeTable.OP_FENTRY -> (BigInt(0x10000) - 0x234),
      FrontendOpcodeDecodeTable.OP_FEXIT -> (BigInt(0x10000) + 0x234),
      FrontendOpcodeDecodeTable.OP_FRET_RA -> (BigInt(0x10000) + 0x234),
      FrontendOpcodeDecodeTable.OP_FRET_STK -> (BigInt(0x10000) + 0x234)
    )
    low.foreach { case (opcode, expected) =>
      assert(BlockControlTemplateSequencer.nextSp(opcode, 0x10000, 0x234) == expected)
    }

    val fullImmediate = BigInt(0x3123)
    assert(BlockControlTemplateSequencer.nextSp(
      FrontendOpcodeDecodeTable.OP_FENTRY,
      0x20000,
      fullImmediate
    ) == BigInt(0x20000) - fullImmediate)
    assert(BlockControlTemplateSequencer.nextSp(
      FrontendOpcodeDecodeTable.OP_FEXIT,
      0x20000,
      fullImmediate
    ) == BigInt(0x20000) + fullImmediate)
  }

  test("Dhrystone FENTRY buffers the complete ordered save set before commit") {
    val values = registerValues.updated(10, BigInt(73440)).updated(11, BigInt(85906))
    val entry = buildPlan(
      key,
      FrontendOpcodeDecodeTable.OP_FENTRY,
      pc = 70512,
      oldSp = 231408,
      imm = 160,
      m = 10,
      n = 19,
      smap,
      values,
      Vector.fill(10)(BigInt(0)),
      srcData0 = 0
    )
    assert(entry.newSp == 231248)
    assert(entry.children.map(_.addr) == (231400 to 231328 by -8).map(BigInt(_)))
    assert(entry.children.head.data == 73440)
    assert(entry.children(1).data == 85906)
    assert(entry.children.map(_.archReg) == (10 to 19))
    assert(entry.children.last.last)
  }

  test("wrapped FENTRY preserves 22 23 2 3 ordering and stable physical tags") {
    val entry = plan(
      FrontendOpcodeDecodeTable.OP_FENTRY,
      m = 22,
      n = 3,
      imm = 64,
      loads = Vector.fill(4)(BigInt(0))
    )
    assert(entry.children.map(_.archReg) == Seq(22, 23, 2, 3))
    assert(entry.children.map(_.physTag) == Seq(62, 63, 42, 43))
    assert(entry.children.map(_.addr) == Seq(231400, 231392, 231384, 231376).map(BigInt(_)))
  }

  test("FEXIT and FRET restores follow new-SP minus eight order") {
    val loads = Vector.tabulate(10)(idx => BigInt(9000 + idx))
    Seq(
      FrontendOpcodeDecodeTable.OP_FEXIT,
      FrontendOpcodeDecodeTable.OP_FRET_RA,
      FrontendOpcodeDecodeTable.OP_FRET_STK
    ).foreach { opcode =>
      val restore = plan(opcode, loads = loads)
      assert(restore.newSp == 231568)
      assert(restore.children.map(_.addr) == (231560 to 231488 by -8).map(BigInt(_)))
      assert(restore.children.map(_.data) == loads)
      assert(restore.childKind == Restore)
    }
  }

  test("FRET targets are causal: RA source for FRET.RA and first lookup for FRET.STK") {
    val fretRa = plan(FrontendOpcodeDecodeTable.OP_FRET_RA, srcData0 = 73440)
    assert(fretRa.redirectValid)
    assert(fretRa.nextPc == 73440)

    val loads = Vector(BigInt(73440)) ++ Vector.fill(9)(BigInt(0))
    val fretStk = plan(FrontendOpcodeDecodeTable.OP_FRET_STK, loads = loads)
    assert(fretStk.redirectValid)
    assert(fretStk.nextPc == fretStk.children.head.data)
    assert(fretStk.nextPc == 73440)
  }

  test("completion selection, matching commit, held children, and eventual tail-zero are ordered") {
    val lifecycle = new Lifecycle(plan(FrontendOpcodeDecodeTable.OP_FENTRY))
    assert(!lifecycle.terminal)
    assert(!lifecycle.commit(key))
    lifecycle.selectCompletion()
    assert(lifecycle.terminal)
    assert(lifecycle.retained == 10)
    assert(!lifecycle.commit(key.copy(generation = 8)))
    assert(lifecycle.commit(key))

    val held = lifecycle.acceptChild(ready = false)
    assert(held.contains(lifecycle.plan.children.head))
    assert(lifecycle.acceptChild(ready = false) == held)
    assert(lifecycle.drainIndex == 0)

    lifecycle.plan.children.indices.foreach { idx =>
      val accepted = lifecycle.acceptChild(ready = true)
      assert(accepted.exists(_.index == idx))
    }
    assert(lifecycle.tailZero)
    assert(lifecycle.retained == 0)
    assert(!lifecycle.decodeFence && !lifecycle.issueFence && !lifecycle.memoryFence)
  }

  test("self restart retains all phases while external cancellation is phase-qualified") {
    val beforeCompletion = new Lifecycle(plan(FrontendOpcodeDecodeTable.OP_FENTRY))
    beforeCompletion.recover(key, killsActive = true)
    assert(beforeCompletion.selfRestarts == 1)
    assert(!beforeCompletion.cancelled)

    beforeCompletion.recover(key.copy(rid = key.rid.copy(value = 28)), killsActive = true)
    assert(beforeCompletion.cancelled)
    assert(!beforeCompletion.decodeFence && !beforeCompletion.issueFence && !beforeCompletion.memoryFence)

    val afterCompletion = new Lifecycle(plan(FrontendOpcodeDecodeTable.OP_FRET_STK))
    afterCompletion.selectCompletion()
    afterCompletion.recover(key.copy(generation = 9), killsActive = true)
    assert(!afterCompletion.cancelled)
    assert(afterCompletion.illegalDiscardAttempts == 1)
    assert(afterCompletion.commit(key))
    afterCompletion.recover(key, killsActive = true)
    assert(afterCompletion.selfRestarts == 1)
    assert(afterCompletion.retained == 10)
  }

  test("stale and duplicate child responses do not mutate the expected child") {
    val tracker = new ResponseTracker(key, expectedIndex = 3)
    assert(!tracker.response(key.copy(generation = 6), index = 3, data = 1))
    assert(!tracker.response(key, index = 2, data = 2))
    assert(tracker.response(key, index = 3, data = 3))
    assert(!tracker.response(key, index = 3, data = 4))
    assert(tracker.acceptedData.contains(3))
    assert(tracker.staleDrops == 3)
  }

  test("BlockControlTemplateSequencer elaborates retained maps and all explicit acceptance ports") {
    val p = InterfaceParams(robEntries = 16, physRegWidth = 7, lsidWidth = 40)
    val io = new BlockControlTemplateSequencerIO(
      p,
      archRegs = 32,
      physTagWidth = 7,
      stqEntries = 16,
      lsidWidth = 40,
      maxChildren = 22
    )
    assert(io.parentRequest.bits.elements.keySet ==
      Set("sidecar", "oldSp", "srcData0", "srcData1", "srcData2"))
    assert(io.activeMap.smap.length == 32)
    assert(io.storeRequest.bits.lsId.getWidth == 40)
    assert(io.storeRequest.bits.stqIndex.getWidth == 4)

    val sv = ChiselStage.emitSystemVerilog(
      new BlockControlTemplateSequencer(
        p,
        archRegs = 32,
        physTagWidth = 7,
        stqEntries = 16,
        lsidWidth = 40,
        maxChildren = 22
      )
    )
    assert(sv.contains("module BlockControlTemplateSequencer"))
    assert(sv.contains("io_parentRequest_valid"))
    assert(sv.contains("io_completion_valid"))
    assert(sv.contains("io_storeRequest_valid"))
    assert(sv.contains("io_rfWriteRequest_valid"))
    assert(sv.contains("io_selfRestartObserved"))
    assert(sv.contains("io_illegalDiscardAttempt"))
    assert(sv.contains("io_tailZero"))
  }
}
