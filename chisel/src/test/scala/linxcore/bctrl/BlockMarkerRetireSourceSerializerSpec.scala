package linxcore.bctrl

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object BlockMarkerRetireSourceSerializerReference {
  final case class Source(id: Int, stop: Boolean = false)
  final case class StepResult(
      ready: Boolean,
      validMask: Int,
      enqueueCount: Int,
      count: Int,
      out: Option[Source],
      dequeued: Boolean)

  final class State(sourceWidth: Int, depth: Int) {
    private var queue = Vector.empty[Source]

    private def mask(sources: Seq[Option[Source]]): Int =
      sources.zipWithIndex.foldLeft(0) { case (acc, (source, idx)) =>
        if (source.nonEmpty) acc | (1 << idx) else acc
      }

    def step(
        sources: Seq[Option[Source]] = Seq.empty,
        outReady: Boolean = false,
        clear: Boolean = false): StepResult = {
      val paddedSources = sources.padTo(sourceWidth, None).take(sourceWidth)
      val ready = !clear && queue.size <= depth - sourceWidth
      val acceptedSources = if (ready) paddedSources.flatten else Seq.empty
      val out = if (!clear) queue.headOption else None
      val dequeued = outReady && out.nonEmpty && !clear
      val result = StepResult(
        ready = ready,
        validMask = mask(paddedSources),
        enqueueCount = acceptedSources.size,
        count = queue.size,
        out = out,
        dequeued = dequeued)

      if (clear) {
        queue = Vector.empty
      } else {
        if (dequeued) {
          queue = queue.tail
        }
        queue = queue ++ acceptedSources
      }
      result
    }
  }
}

class BlockMarkerRetireSourceSerializerSpec extends AnyFunSuite {
  import BlockMarkerRetireSourceSerializerReference._

  test("reference compacts a retire window and drains marker sources in slot order") {
    val state = new State(sourceWidth = 4, depth = 8)

    val enqueue = state.step(Seq(Some(Source(0)), None, Some(Source(2)), Some(Source(3, stop = true))))
    assert(enqueue.ready)
    assert(enqueue.validMask == 0xd)
    assert(enqueue.enqueueCount == 3)
    assert(enqueue.out.isEmpty)

    val first = state.step(outReady = false)
    assert(first.out.contains(Source(0)))
    assert(!first.dequeued)

    val drain0 = state.step(outReady = true)
    val drain1 = state.step(outReady = true)
    val drain2 = state.step(outReady = true)
    assert(drain0.out.contains(Source(0)))
    assert(drain1.out.contains(Source(2)))
    assert(drain2.out.contains(Source(3, stop = true)))
    assert(state.step().out.isEmpty)
  }

  test("reference requires room for a full retire window before enqueue") {
    val state = new State(sourceWidth = 2, depth = 4)

    state.step(Seq(Some(Source(0)), Some(Source(1))))
    state.step(Seq(Some(Source(2)), None))

    val blocked = state.step(Seq(Some(Source(3)), None))
    assert(!blocked.ready)
    assert(blocked.enqueueCount == 0)

    val blockedWhileDraining = state.step(Seq(Some(Source(3)), None), outReady = true)
    assert(!blockedWhileDraining.ready)
    assert(blockedWhileDraining.out.contains(Source(0)))

    val accepted = state.step(Seq(Some(Source(3)), None))
    assert(accepted.ready)
    assert(accepted.enqueueCount == 1)
  }

  test("reference clear drops queued marker sources and blocks same-cycle enqueue") {
    val state = new State(sourceWidth = 2, depth = 4)

    state.step(Seq(Some(Source(0)), Some(Source(1))))
    val cleared = state.step(Seq(Some(Source(2)), None), outReady = true, clear = true)
    assert(!cleared.ready)
    assert(cleared.out.isEmpty)
    assert(!cleared.dequeued)
    assert(cleared.enqueueCount == 0)
    assert(state.step().out.isEmpty)
  }

  test("BlockMarkerRetireSourceSerializer elaborates marker queue ports") {
    val sv = ChiselStage.emitSystemVerilog(
      new BlockMarkerRetireSourceSerializer(
        sourceWidth = 4,
        sourceQueueDepth = 8
      ))

    assert(sv.contains("module BlockMarkerRetireSourceSerializer"))
    assert(sv.contains("io_sourceWindowReady"))
    assert(sv.contains("io_sourceValidMask"))
    assert(sv.contains("io_sourceEnqueueCount"))
    assert(sv.contains("io_sourceDequeued"))
    assert(sv.contains("io_out_boundaryTarget"))
  }
}
