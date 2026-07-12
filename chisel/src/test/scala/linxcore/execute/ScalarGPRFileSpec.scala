package linxcore.execute

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object ScalarGPRFileReference {
  final case class Write(request: Boolean, commit: Boolean, tag: Int, data: Long)
  final case class Result(fire: Vector[Boolean], ready: Vector[Boolean])

  def arbitrate(writes: Vector[Write]): Result = {
    val ready = writes.indices.map { port =>
      writes(port).request && !writes.indices.take(port).exists { higher =>
        writes(higher).request && writes(higher).tag == writes(port).tag
      }
    }.toVector
    Result(
      fire = writes.indices.map(port => ready(port) && writes(port).commit).toVector,
      ready = ready
    )
  }
}

class ScalarGPRFileSpec extends AnyFunSuite {
  import ScalarGPRFileReference._

  test("request reserves a write port but only commit mutates state") {
    val held = arbitrate(Vector(Write(request = true, commit = false, tag = 40, data = 1)))
    assert(held.ready == Vector(true))
    assert(held.fire == Vector(false))

    val committed = arbitrate(Vector(Write(request = true, commit = true, tag = 40, data = 1)))
    assert(committed.fire == Vector(true))
  }

  test("higher port priority blocks only a duplicate tag") {
    val duplicate = arbitrate(Vector(
      Write(request = true, commit = true, tag = 40, data = 1),
      Write(request = true, commit = true, tag = 40, data = 2)))
    assert(duplicate.ready == Vector(true, false))
    assert(duplicate.fire == Vector(true, false))

    val independent = arbitrate(Vector(
      Write(request = true, commit = true, tag = 40, data = 1),
      Write(request = true, commit = true, tag = 41, data = 2)))
    assert(independent.fire == Vector(true, true))
  }

  test("canonical scalar GPR file elaborates parameterized ports and ready state") {
    val sv = ChiselStage.emitSystemVerilog(new ScalarGPRFile(
      archRegs = 24,
      physRegs = 128,
      dataWidth = 64,
      readPorts = 2,
      writePorts = 2
    ))
    assert(sv.contains("module ScalarGPRFile"))
    assert(sv.contains("io_write_0_requestValid"))
    assert(sv.contains("io_write_1_blockedByHigherSameTag"))
    assert(sv.contains("io_readyMask"))
    assert(sv.contains("io_protocolError"))
  }
}
