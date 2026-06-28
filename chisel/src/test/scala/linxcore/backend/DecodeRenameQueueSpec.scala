package linxcore.backend

import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import org.scalatest.funsuite.AnyFunSuite

object DecodeRenameQueueReference {
  final case class Step(
      pushReady: Boolean,
      pushFire: Boolean,
      popFire: Boolean,
      nextCount: Int)

  def step(count: Int, depth: Int, pushValid: Boolean, popReady: Boolean, outValid: Boolean, flush: Boolean = false): Step = {
    require(depth > 0 && (depth & (depth - 1)) == 0)
    require(count >= 0 && count <= depth)

    val popFire = !flush && outValid && popReady
    val pushReady = !flush && (count < depth || popFire)
    val pushFire = pushValid && pushReady
    val nextCount =
      if (flush) 0
      else count + (if (pushFire) 1 else 0) - (if (popFire) 1 else 0)

    Step(pushReady, pushFire, popFire, nextCount)
  }
}

class DecodeRenameQueueSpec extends AnyFunSuite {
  import DecodeRenameQueueReference._

  test("reference preserves a registered decode-to-rename boundary") {
    val emptyPush = step(count = 0, depth = 4, pushValid = true, popReady = true, outValid = false)
    assert(emptyPush.pushReady)
    assert(emptyPush.pushFire)
    assert(!emptyPush.popFire)
    assert(emptyPush.nextCount == 1)

    val visiblePop = step(count = 1, depth = 4, pushValid = false, popReady = true, outValid = true)
    assert(visiblePop.popFire)
    assert(visiblePop.nextCount == 0)
  }

  test("reference allows simultaneous full-queue pop and push") {
    val fullBlocked = step(count = 4, depth = 4, pushValid = true, popReady = false, outValid = true)
    assert(!fullBlocked.pushReady)
    assert(!fullBlocked.pushFire)
    assert(fullBlocked.nextCount == 4)

    val fullExchange = step(count = 4, depth = 4, pushValid = true, popReady = true, outValid = true)
    assert(fullExchange.pushReady)
    assert(fullExchange.pushFire)
    assert(fullExchange.popFire)
    assert(fullExchange.nextCount == 4)
  }

  test("reference flush clears state and blocks traffic") {
    val flushed = step(count = 3, depth = 4, pushValid = true, popReady = true, outValid = true, flush = true)
    assert(!flushed.pushReady)
    assert(!flushed.pushFire)
    assert(!flushed.popFire)
    assert(flushed.nextCount == 0)
  }

  test("IO exposes queue state with depth-derived widths") {
    val io = new DecodeRenameQueueIO(InterfaceParams(), depth = 4)

    assert(io.pushReady.getWidth == 1)
    assert(io.popFire.getWidth == 1)
    assert(io.head.getWidth == 2)
    assert(io.tail.getWidth == 2)
    assert(io.count.getWidth == 3)
    assert(io.out.valid.getWidth == 1)
  }

  test("DecodeRenameQueue elaborates as a separate queue owner") {
    val sv = ChiselStage.emitSystemVerilog(
      new DecodeRenameQueue(InterfaceParams(), depth = 4)
    )

    assert(sv.contains("module DecodeRenameQueue"))
    assert(sv.contains("io_pushReady"))
    assert(sv.contains("io_popFire"))
    assert(sv.contains("io_count"))
  }
}
