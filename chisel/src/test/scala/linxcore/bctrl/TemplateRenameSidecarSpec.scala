package linxcore.bctrl

import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object TemplateRenameSidecarReference {
  final case class RobId(valid: Boolean, wrap: Boolean, value: Int)
  final case class Key(
      stid: Int,
      bid: RobId,
      gid: RobId,
      rid: RobId,
      robSlot: Int,
      generation: Int)
  final case class Payload(key: Key, smap: Vector[Int], cmap: Vector[Int])

  sealed trait IssueResult
  final case class Held(payload: Payload) extends IssueResult
  final case class Transferred(payload: Payload) extends IssueResult
  case object RejectedMismatch extends IssueResult
  case object RejectedStaleGeneration extends IssueResult

  final class State(stidCount: Int, archRegs: Int) {
    require(stidCount > 0)
    private val sidecars = Array.fill(stidCount)(Option.empty[Payload])
    private val decodeFences = Array.fill(stidCount)(false)
    private val issueFences = Array.fill(stidCount)(false)
    private val memoryFences = Array.fill(stidCount)(false)
    var enqueueCount = 0
    var transferCount = 0
    var mismatchDropCount = 0
    var staleGenerationDropCount = 0

    private def lane(stid: Int): Option[Int] =
      Option.when(stid >= 0 && stid < stidCount)(stid)

    def occupied(stid: Int): Boolean =
      lane(stid).exists(sidecars(_).nonEmpty)

    def fences(stid: Int): (Boolean, Boolean, Boolean) =
      lane(stid)
        .map(idx => (decodeFences(idx), issueFences(idx), memoryFences(idx)))
        .getOrElse((false, false, false))

    def enqueue(payload: Payload): Boolean = lane(payload.key.stid).exists { idx =>
      require(payload.smap.size == archRegs && payload.cmap.size == archRegs)
      val free =
        sidecars(idx).isEmpty && !decodeFences(idx) && !issueFences(idx) && !memoryFences(idx)
      if (free) {
        sidecars(idx) = Some(payload)
        decodeFences(idx) = true
        issueFences(idx) = true
        memoryFences(idx) = true
        enqueueCount += 1
      }
      free
    }

    def issue(key: Key, downstreamReady: Boolean): IssueResult =
      lane(key.stid).flatMap(idx => sidecars(idx)) match {
      case Some(payload) if payload.key == key && !downstreamReady =>
        Held(payload)
      case Some(payload) if payload.key == key =>
        sidecars(key.stid) = None
        transferCount += 1
        Transferred(payload)
      case Some(payload) if payload.key.copy(generation = key.generation) == key =>
        mismatchDropCount += 1
        staleGenerationDropCount += 1
        RejectedStaleGeneration
      case _ =>
        mismatchDropCount += 1
        RejectedMismatch
    }

    def releaseDecode(key: Key): Unit =
      lane(key.stid).foreach(idx => if (sidecarKey(idx).contains(key)) decodeFences(idx) = false)

    def releaseIssue(key: Key): Unit =
      lane(key.stid).foreach(idx => if (sidecarKey(idx).contains(key)) issueFences(idx) = false)

    def releaseMemory(key: Key): Unit =
      lane(key.stid).foreach(idx => if (sidecarKey(idx).contains(key)) memoryFences(idx) = false)

    def cancel(key: Key): Unit = lane(key.stid).foreach { idx =>
      if (sidecarKey(idx).contains(key)) {
        sidecars(idx) = None
        decodeFences(idx) = false
        issueFences(idx) = false
        memoryFences(idx) = false
      }
    }

    private def sidecarKey(idx: Int): Option[Key] =
      sidecars(idx).map(_.key).orElse {
        // The hardware retains entry bytes after ownership transfer so fence
        // release remains full-keyed even though occupancy is clear.
        retainedKeys(idx)
      }

    private val retainedKeys = Array.fill(stidCount)(Option.empty[Key])

    def issueAndRetainKey(key: Key, downstreamReady: Boolean): IssueResult = {
      val result = issue(key, downstreamReady)
      result match {
        case Transferred(payload) => retainedKeys(key.stid) = Some(payload.key)
        case _ =>
      }
      result
    }
  }
}

class TemplateRenameSidecarSpec extends AnyFunSuite {
  import TemplateRenameSidecarReference._

  private val baseKey = Key(
    stid = 0,
    bid = RobId(valid = true, wrap = false, value = 5),
    gid = RobId(valid = true, wrap = true, value = 6),
    rid = RobId(valid = true, wrap = false, value = 7),
    robSlot = 7,
    generation = 11
  )
  private val payload = Payload(baseKey, Vector.tabulate(32)(identity), Vector.tabulate(32)(_ + 32))

  test("TemplateParentRequest is a pure payload with no nested valid field") {
    val p = InterfaceParams()
    val request = new TemplateParentRequest(p, archRegs = 32, physTagWidth = 7)
    assert(!request.elements.contains("valid"))
    assert(request.elements.keySet == Set("sidecar", "oldSp", "srcData0", "srcData1", "srcData2"))
    assert(request.sidecar.map.smap.length == 32)
    assert(request.sidecar.map.cmap.length == 32)
    assert(request.sidecar.map.smap.head.getWidth == 7)
  }

  test("downstream backpressure holds the exact sidecar and transfers once") {
    val state = new State(stidCount = 2, archRegs = 32)
    assert(state.enqueue(payload))
    val held = state.issueAndRetainKey(baseKey, downstreamReady = false)
    assert(held == Held(payload))
    assert(state.occupied(0))
    assert(state.fences(0) == ((true, true, true)))

    assert(state.issueAndRetainKey(baseKey, downstreamReady = true) == Transferred(payload))
    assert(!state.occupied(0))
    assert(state.transferCount == 1)
    assert(state.issueAndRetainKey(baseKey, downstreamReady = true) == RejectedMismatch)
    assert(state.transferCount == 1)
  }

  test("every full ROB identity component participates in matching") {
    val mutations = Seq[Key => Key](
      key => key.copy(stid = 1),
      key => key.copy(bid = key.bid.copy(valid = false)),
      key => key.copy(bid = key.bid.copy(wrap = true)),
      key => key.copy(bid = key.bid.copy(value = 4)),
      key => key.copy(gid = key.gid.copy(valid = false)),
      key => key.copy(gid = key.gid.copy(wrap = false)),
      key => key.copy(gid = key.gid.copy(value = 9)),
      key => key.copy(rid = key.rid.copy(valid = false)),
      key => key.copy(rid = key.rid.copy(wrap = true)),
      key => key.copy(rid = key.rid.copy(value = 8)),
      key => key.copy(robSlot = 6)
    )
    mutations.foreach { mutate =>
      val state = new State(stidCount = 2, archRegs = 32)
      assert(state.enqueue(payload))
      assert(state.issueAndRetainKey(mutate(baseKey), downstreamReady = true) == RejectedMismatch)
      assert(state.occupied(0))
      assert(state.transferCount == 0)
    }
  }

  test("stale generation is rejected and counted separately") {
    val state = new State(stidCount = 1, archRegs = 32)
    assert(state.enqueue(payload))
    assert(state.issueAndRetainKey(baseKey.copy(generation = 10), downstreamReady = true) ==
      RejectedStaleGeneration)
    assert(state.staleGenerationDropCount == 1)
    assert(state.mismatchDropCount == 1)
    assert(state.occupied(0))
  }

  test("fences outlive transfer and release independently by full key") {
    val state = new State(stidCount = 1, archRegs = 32)
    assert(state.enqueue(payload))
    assert(state.issueAndRetainKey(baseKey, downstreamReady = true) == Transferred(payload))
    assert(state.fences(0) == ((true, true, true)))

    state.releaseIssue(baseKey)
    assert(state.fences(0) == ((true, false, true)))
    state.releaseDecode(baseKey.copy(generation = 99))
    assert(state.fences(0) == ((true, false, true)))
    state.releaseDecode(baseKey)
    assert(state.fences(0) == ((false, false, true)))
    state.releaseMemory(baseKey)
    assert(state.fences(0) == ((false, false, false)))
    assert(state.enqueue(payload))
  }

  test("eligible pre-transfer cancellation clears ownership and all fences") {
    val state = new State(stidCount = 1, archRegs = 32)
    assert(state.enqueue(payload))
    state.cancel(baseKey.copy(robSlot = 3))
    assert(state.occupied(0))
    state.cancel(baseKey)
    assert(!state.occupied(0))
    assert(state.fences(0) == ((false, false, false)))
  }

  test("STID lanes are independent and cannot overwrite fenced ownership") {
    val state = new State(stidCount = 2, archRegs = 32)
    val second = payload.copy(key = baseKey.copy(stid = 1, robSlot = 9))
    assert(state.enqueue(payload))
    assert(state.enqueue(second))
    assert(!state.enqueue(payload))
    assert(state.issueAndRetainKey(second.key, downstreamReady = true) == Transferred(second))
    assert(state.occupied(0))
    assert(!state.occupied(1))
  }

  test("TemplateRenameSidecarTable elaborates full-key ownership and independent fence state") {
    val p = InterfaceParams(robEntries = 16, threadIdWidth = 3, physRegWidth = 7)
    val io = new TemplateRenameSidecarTableIO(p, archRegs = 32, physTagWidth = 7, stidCount = 4)
    assert(io.occupiedMask.getWidth == 4)
    assert(io.enqueue.bits.map.smap.length == 32)
    assert(io.parentRequest.bits.elements.keySet ==
      Set("sidecar", "oldSp", "srcData0", "srcData1", "srcData2"))

    val sv = ChiselStage.emitSystemVerilog(
      new TemplateRenameSidecarTable(p, archRegs = 32, physTagWidth = 7, stidCount = 4)
    )
    assert(sv.contains("module TemplateRenameSidecarTable"))
    assert(sv.contains("io_parentRequest_valid"))
    assert(sv.contains("io_parentRequest_ready"))
    assert(sv.contains("io_decodeFenceMask"))
    assert(sv.contains("io_issueFenceMask"))
    assert(sv.contains("io_memoryFenceMask"))
    assert(sv.contains("io_staleGenerationDropCount"))
  }
}
