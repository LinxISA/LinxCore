package linxcore.lsu

import circt.stage.ChiselStage
import linxcore.common.InterfaceParams
import linxcore.rename.StoreSplitStoreType
import org.scalatest.funsuite.AnyFunSuite

object StoreDispatchQueuesReference {
  sealed trait StorePart
  case object Sta extends StorePart
  case object Std extends StorePart
  case object Unsplit extends StorePart

  final case class Payload(part: StorePart, id: Int)

  final case class Decision(
      staReady: Boolean,
      stdReady: Boolean,
      protocolError: Boolean,
      splitInput: Boolean,
      unsplitInput: Boolean,
      staEnqueueFire: Boolean,
      stdEnqueueFire: Boolean,
      staDequeueFire: Boolean,
      stdDequeueFire: Boolean,
      staCount: Int,
      stdCount: Int)

  final class Model(depth: Int) {
    require(depth > 0 && (depth & (depth - 1)) == 0)

    private var sta = Vector.empty[Payload]
    private var std = Vector.empty[Payload]

    def staCount: Int = sta.size
    def stdCount: Int = std.size
    def staHead: Option[Payload] = sta.headOption
    def stdHead: Option[Payload] = std.headOption

    def step(
        staIn: Option[Payload] = None,
        stdIn: Option[Payload] = None,
        unsplitIn: Option[Payload] = None,
        flush: Boolean = false,
        staDequeueReady: Boolean = false,
        stdDequeueReady: Boolean = false): Decision = {
      val splitInput = staIn.nonEmpty && stdIn.nonEmpty && unsplitIn.isEmpty
      val unsplitInput = unsplitIn.nonEmpty && staIn.isEmpty && stdIn.isEmpty
      val mixedUnsplit = unsplitIn.nonEmpty && (staIn.nonEmpty || stdIn.nonEmpty)
      val loneSplitHalf = (staIn.nonEmpty != stdIn.nonEmpty) && unsplitIn.isEmpty
      val protocolError = mixedUnsplit || loneSplitHalf

      val staDequeueFire = !flush && sta.nonEmpty && staDequeueReady
      val stdDequeueFire = !flush && std.nonEmpty && stdDequeueReady
      val staCanEnqueue = sta.size != depth || staDequeueFire
      val stdCanEnqueue = std.size != depth || stdDequeueFire
      val splitEnqueueFire = !flush && splitInput && !protocolError && staCanEnqueue && stdCanEnqueue
      val unsplitEnqueueFire = !flush && unsplitInput && !protocolError && staCanEnqueue
      val staEnqueueFire = splitEnqueueFire || unsplitEnqueueFire
      val stdEnqueueFire = splitEnqueueFire

      if (flush) {
        sta = Vector.empty
        std = Vector.empty
      } else {
        if (staDequeueFire) {
          sta = sta.tail
        }
        if (stdDequeueFire) {
          std = std.tail
        }
        if (staEnqueueFire) {
          sta = sta :+ unsplitIn.getOrElse(staIn.get)
        }
        if (stdEnqueueFire) {
          std = std :+ stdIn.get
        }
      }

      Decision(
        staReady = !flush && staCanEnqueue,
        stdReady = !flush && stdCanEnqueue,
        protocolError = protocolError,
        splitInput = splitInput,
        unsplitInput = unsplitInput,
        staEnqueueFire = staEnqueueFire,
        stdEnqueueFire = stdEnqueueFire,
        staDequeueFire = staDequeueFire,
        stdDequeueFire = stdDequeueFire,
        staCount = sta.size,
        stdCount = std.size
      )
    }
  }
}

class StoreDispatchQueuesSpec extends AnyFunSuite {
  import StoreDispatchQueuesReference._

  private def sta(id: Int): Payload = Payload(Sta, id)
  private def std(id: Int): Payload = Payload(Std, id)
  private def unsplit(id: Int): Payload = Payload(Unsplit, id)

  test("reference enqueues split stores atomically into STA and STD queues") {
    val model = new Model(depth = 4)
    val enqueue = model.step(staIn = Some(sta(0)), stdIn = Some(std(0)))

    assert(enqueue.splitInput)
    assert(!enqueue.protocolError)
    assert(enqueue.staReady)
    assert(enqueue.stdReady)
    assert(enqueue.staEnqueueFire)
    assert(enqueue.stdEnqueueFire)
    assert(enqueue.staCount == 1)
    assert(enqueue.stdCount == 1)
    assert(model.staHead.contains(sta(0)))
    assert(model.stdHead.contains(std(0)))

    val dequeue = model.step(staDequeueReady = true, stdDequeueReady = true)
    assert(dequeue.staDequeueFire)
    assert(dequeue.stdDequeueFire)
    assert(dequeue.staCount == 0)
    assert(dequeue.stdCount == 0)
  }

  test("reference routes unsplit stores to the STA queue only") {
    val model = new Model(depth = 4)
    val enqueue = model.step(unsplitIn = Some(unsplit(7)))

    assert(enqueue.unsplitInput)
    assert(enqueue.staEnqueueFire)
    assert(!enqueue.stdEnqueueFire)
    assert(enqueue.staCount == 1)
    assert(enqueue.stdCount == 0)
    assert(model.staHead.contains(unsplit(7)))
    assert(model.stdHead.isEmpty)
  }

  test("reference blocks split stores when either dispatch queue lacks space") {
    val model = new Model(depth = 2)

    assert(model.step(staIn = Some(sta(0)), stdIn = Some(std(0))).staEnqueueFire)
    assert(model.step(staIn = Some(sta(1)), stdIn = Some(std(1))).stdEnqueueFire)
    val freeStaOnly = model.step(staDequeueReady = true)
    assert(freeStaOnly.staCount == 1)
    assert(freeStaOnly.stdCount == 2)

    val blocked = model.step(staIn = Some(sta(2)), stdIn = Some(std(2)))
    assert(blocked.staReady)
    assert(!blocked.stdReady)
    assert(!blocked.staEnqueueFire)
    assert(!blocked.stdEnqueueFire)
    assert(blocked.staCount == 1)
    assert(blocked.stdCount == 2)
  }

  test("reference allows enqueue into a full queue when dequeue frees space in the same cycle") {
    val model = new Model(depth = 2)

    assert(model.step(unsplitIn = Some(unsplit(0))).staEnqueueFire)
    assert(model.step(unsplitIn = Some(unsplit(1))).staEnqueueFire)
    assert(model.staCount == 2)

    val recycle = model.step(unsplitIn = Some(unsplit(2)), staDequeueReady = true)
    assert(recycle.staReady)
    assert(recycle.staDequeueFire)
    assert(recycle.staEnqueueFire)
    assert(recycle.staCount == 2)
    assert(model.staHead.contains(unsplit(1)))
  }

  test("reference treats lone split halves and mixed unsplit inputs as protocol errors") {
    val model = new Model(depth = 4)

    val loneSta = model.step(staIn = Some(sta(0)))
    assert(loneSta.protocolError)
    assert(loneSta.staReady)
    assert(loneSta.stdReady)
    assert(!loneSta.staEnqueueFire)
    assert(loneSta.staCount == 0)

    val mixed = model.step(stdIn = Some(std(1)), unsplitIn = Some(unsplit(1)))
    assert(mixed.protocolError)
    assert(!mixed.staEnqueueFire)
    assert(!mixed.stdEnqueueFire)
    assert(mixed.staCount == 0)
    assert(mixed.stdCount == 0)
  }

  test("reference flush clears both dispatch queues and suppresses same-cycle handshakes") {
    val model = new Model(depth = 4)
    assert(model.step(staIn = Some(sta(0)), stdIn = Some(std(0))).staEnqueueFire)

    val flushed = model.step(unsplitIn = Some(unsplit(1)), flush = true, staDequeueReady = true, stdDequeueReady = true)
    assert(!flushed.staReady)
    assert(!flushed.stdReady)
    assert(!flushed.staEnqueueFire)
    assert(!flushed.stdEnqueueFire)
    assert(!flushed.staDequeueFire)
    assert(!flushed.stdDequeueFire)
    assert(flushed.staCount == 0)
    assert(flushed.stdCount == 0)
    assert(model.staHead.isEmpty)
    assert(model.stdHead.isEmpty)
  }

  test("StoreDispatchQueues IO preserves store split payload and model store type width") {
    val p = InterfaceParams()
    val io = new StoreDispatchQueuesIO(p, depth = 4)

    assert(StoreSplitStoreType.All.asUInt.litValue == 0)
    assert(StoreSplitStoreType.Addr.asUInt.litValue == 1)
    assert(StoreSplitStoreType.Data.asUInt.litValue == 2)
    assert(io.staReady.getWidth == 1)
    assert(io.stdReady.getWidth == 1)
    assert(io.inputProtocolError.getWidth == 1)
    assert(io.staCount.getWidth == 3)
    assert(io.stdCount.getWidth == 3)
    assert(io.staOut.uop.lsid.getWidth == 32)
    assert(io.stdOut.uop.lsid.getWidth == 32)
    assert(io.staOut.dataSrcIndex.getWidth == 2)
    assert(io.stdOut.dataSrcIndex.getWidth == 2)
  }

  test("StoreDispatchQueues elaborates as a separate queue-backed dispatch boundary") {
    val sv = ChiselStage.emitSystemVerilog(new StoreDispatchQueues(InterfaceParams(), depth = 4))

    assert(sv.contains("module StoreDispatchQueues"))
    assert(sv.contains("io_staEnqueueFire"))
    assert(sv.contains("io_stdEnqueueFire"))
    assert(sv.contains("io_staOutValid"))
    assert(sv.contains("io_stdOutValid"))
    assert(sv.contains("io_inputProtocolError"))
  }
}
