package linxcore.testutil

final case class SimulationProgressEvent(kind: String, identity: String)

/** Tracks typed architectural progress in simulated cycles.
  *
  * This is intentionally independent from build-process liveness: a busy
  * simulator can still be architecturally deadlocked.
  */
final class SimulationProgress(
    maxIdleCycles: Long,
    occupancy: () => String) {
  require(maxIdleCycles > 0, "maxIdleCycles must be positive")

  private var lastObservedCycle = 0L
  private var lastProgressCycle = 0L
  private var lastProgress = Option.empty[SimulationProgressEvent]

  def observe(
      cycle: Long,
      events: Iterable[SimulationProgressEvent]): Unit = {
    require(cycle >= lastObservedCycle,
      "cycle must not move backwards")
    lastObservedCycle = cycle

    events.lastOption.foreach { event =>
      lastProgressCycle = cycle
      lastProgress = Some(event)
    }
  }

  def requireAlive(cycle: Long): Unit = {
    require(cycle >= lastObservedCycle,
      "cycle must not move backwards")
    lastObservedCycle = cycle

    if (cycle - lastProgressCycle > maxIdleCycles) {
      val event = lastProgress
        .map(value => s"${value.kind}:${value.identity}")
        .getOrElse("none")
      throw new IllegalStateException(
        s"simulation made no architectural progress: cycle=$cycle " +
          s"lastCycle=$lastProgressCycle lastEvent=$event " +
          s"occupancy=${occupancy()}")
    }
  }
}
