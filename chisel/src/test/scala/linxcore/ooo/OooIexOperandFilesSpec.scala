package linxcore.ooo

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.ValidIO
import linxcore.common.OperandClass
import org.scalatest.funsuite.AnyFunSuite

class OooIexOperandFilesSpec extends AnyFunSuite with ChiselSim {
  private val p = OooParams(
    stidCount = 2,
    pPhysRegs = 128,
    tPhysRegs = 4,
    uPhysRegs = 4,
    iexPReadPorts = 2,
    iexTReadPorts = 2,
    iexUReadPorts = 2,
    iexPWritePorts = 2,
    iexTWritePorts = 2,
    iexUWritePorts = 2,
    pcBufferEntries = 32,
    pcBankCount = 4)

  private def clear(dut: OooIexOperandFiles): Unit = {
    dut.io.pReadRequests.foreach(
      _.poke(0.U.asTypeOf(dut.io.pReadRequests.head)))
    dut.io.tReadRequests.foreach(
      _.poke(0.U.asTypeOf(dut.io.tReadRequests.head)))
    dut.io.uReadRequests.foreach(
      _.poke(0.U.asTypeOf(dut.io.uReadRequests.head)))
    dut.io.pInit.poke(0.U.asTypeOf(dut.io.pInit))
    dut.io.pClear.foreach(_.poke(0.U.asTypeOf(dut.io.pClear.head)))
    dut.io.pWrite.foreach(_.poke(0.U.asTypeOf(dut.io.pWrite.head)))
    dut.io.tClear.foreach(_.poke(0.U.asTypeOf(dut.io.tClear.head)))
    dut.io.uClear.foreach(_.poke(0.U.asTypeOf(dut.io.uClear.head)))
    dut.io.tWrite.foreach(_.poke(0.U.asTypeOf(dut.io.tWrite.head)))
    dut.io.uWrite.foreach(_.poke(0.U.asTypeOf(dut.io.uWrite.head)))
  }

  private def pokeLocalKey(
      key: OooIexLocalFileKey,
      stid: Int,
      tag: Int,
      index: Int,
      generation: Int,
      epoch: Int = 7): Unit = {
    key.stid.poke(stid.U)
    key.epoch.poke(epoch.U)
    key.tag.poke(tag.U)
    key.sequence.valid.poke(true.B)
    key.sequence.index.poke(index.U)
    key.sequence.generation.poke(generation.U)
  }

  private def pokePKey(
      key: OooIexPFileKey,
      stid: Int,
      ptag: Int,
      generation: Int,
      epoch: Int = 7): Unit = {
    key.stid.poke(stid.U)
    key.epoch.poke(epoch.U)
    key.ptag.poke(ptag.U)
    key.generation.poke(generation.U)
  }

  private def pokeRead(
      request: ValidIO[OooIexOperandReadPortRequest],
      operandClass: OperandClass.Type,
      stid: Int,
      epoch: Int,
      ptag: Int = 0,
      pGeneration: Int = 0,
      localTag: Int = 0,
      index: Int = 0,
      generation: Int = 0): Unit = {
    request.poke(0.U.asTypeOf(request))
    request.valid.poke(true.B)
    request.bits.stid.poke(stid.U)
    request.bits.epoch.poke(epoch.U)
    request.bits.source.valid.poke(true.B)
    request.bits.source.operandClass.poke(operandClass)
    request.bits.source.ptag.poke(ptag.U)
    request.bits.source.ptagGeneration.poke(pGeneration.U)
    request.bits.source.localTag.poke(localTag.U)
    request.bits.source.localSequence.valid.poke(true.B)
    request.bits.source.localSequence.index.poke(index.U)
    request.bits.source.localSequence.generation.poke(generation.U)
  }

  test("owns P data and exact STID-local T/U sequence data") {
    simulate(new OooIexOperandFiles(p)) { dut =>
      clear(dut)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      // P data/ready is the existing canonical ScalarGPRFile state.
      dut.io.pInit.valid.poke(true.B)
      pokePKey(dut.io.pInit.bits.key,
        stid = 0, ptag = 40, generation = 3)
      dut.io.pInit.bits.data.poke(11.U)
      dut.clock.step()
      dut.io.pInit.valid.poke(false.B)
      pokeRead(dut.io.pReadRequests(0), OperandClass.P,
        stid = 0, epoch = 7, ptag = 40, pGeneration = 3)
      dut.io.pReadResponses(0).valid.expect(true.B)
      dut.io.pReadResponses(0).bits.expect(11.U)

      dut.io.pClear(0).valid.poke(true.B)
      pokePKey(dut.io.pClear(0).bits,
        stid = 0, ptag = 40, generation = 4)
      dut.clock.step()
      dut.io.pClear(0).valid.poke(false.B)
      // The previous generation becomes unreadable before data returns.
      dut.io.pReadResponses(0).valid.expect(false.B)
      dut.io.pReadRequests(0).bits.source.ptagGeneration.poke(4.U)
      dut.io.pWrite(0).valid.poke(true.B)
      pokePKey(dut.io.pWrite(0).bits.key,
        stid = 0, ptag = 40, generation = 4)
      dut.io.pWrite(0).bits.data.poke(22.U)
      dut.io.pWrite(0).bits.commit.poke(false.B)
      dut.io.pWriteReady(0).expect(true.B)
      dut.io.pWriteFire(0).expect(false.B)
      dut.clock.step()
      dut.io.pReadResponses(0).valid.expect(false.B)
      dut.io.pWrite(0).bits.commit.poke(true.B)
      dut.io.pWriteFire(0).expect(true.B)
      dut.clock.step()
      dut.io.pWrite(0).valid.poke(false.B)
      dut.io.pReadResponses(0).valid.expect(true.B)
      dut.io.pReadResponses(0).bits.expect(22.U)

      // T allocation clear installs a new exact sequence but no readable data.
      dut.io.tClear(0).valid.poke(true.B)
      pokeLocalKey(dut.io.tClear(0).bits,
        stid = 1, tag = 2, index = 3, generation = 4)
      dut.clock.step()
      dut.io.tClear(0).valid.poke(false.B)
      pokeRead(dut.io.tReadRequests(0), OperandClass.T,
        stid = 1, epoch = 7, localTag = 2, index = 3, generation = 4)
      dut.io.tReadResponses(0).valid.expect(false.B)
      dut.io.tWrite(0).valid.poke(true.B)
      dut.io.tWrite(0).bits.commit.poke(false.B)
      pokeLocalKey(dut.io.tWrite(0).bits.key,
        stid = 1, tag = 2, index = 3, generation = 4)
      dut.io.tWrite(0).bits.data.poke(33.U)
      dut.io.tWriteReady(0).expect(true.B)
      dut.io.tWriteFire(0).expect(false.B)
      dut.clock.step()
      dut.io.tReadResponses(0).valid.expect(false.B)
      dut.io.tWrite(0).bits.commit.poke(true.B)
      dut.clock.step()
      dut.io.tWrite(0).valid.poke(false.B)
      dut.io.tReadResponses(0).valid.expect(true.B)
      dut.io.tReadResponses(0).bits.expect(33.U)
      dut.io.tAllocatedCount(1).expect(1.U)
      dut.io.tReadyCount(1).expect(1.U)

      // A stale sequence on the same local tag is not readable.
      dut.io.tReadRequests(0).bits.source.localSequence.generation.poke(5.U)
      dut.io.tReadResponses(0).valid.expect(false.B)

      // U is a distinct namespace and can carry another exact local owner.
      dut.io.uClear(0).valid.poke(true.B)
      pokeLocalKey(dut.io.uClear(0).bits,
        stid = 0, tag = 1, index = 2, generation = 6)
      dut.clock.step()
      dut.io.uClear(0).valid.poke(false.B)
      dut.io.uWrite(0).valid.poke(true.B)
      dut.io.uWrite(0).bits.commit.poke(true.B)
      pokeLocalKey(dut.io.uWrite(0).bits.key,
        stid = 0, tag = 1, index = 2, generation = 6)
      dut.io.uWrite(0).bits.data.poke(44.U)
      dut.clock.step()
      dut.io.uWrite(0).valid.poke(false.B)
      pokeRead(dut.io.uReadRequests(0), OperandClass.U,
        stid = 0, epoch = 7, localTag = 1, index = 2, generation = 6)
      dut.io.uReadResponses(0).valid.expect(true.B)
      dut.io.uReadResponses(0).bits.expect(44.U)
      dut.io.uReadyCount(0).expect(1.U)
    }
  }

  test("rejects a stale local write without mutating the current owner") {
    intercept[Exception] {
      simulate(new OooIexOperandFiles(p)) { dut =>
        clear(dut)
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.tClear(0).valid.poke(true.B)
        pokeLocalKey(dut.io.tClear(0).bits,
          stid = 1, tag = 2, index = 3, generation = 4)
        dut.clock.step()
        dut.io.tClear(0).valid.poke(false.B)
        dut.io.tWrite(0).valid.poke(true.B)
        dut.io.tWrite(0).bits.commit.poke(true.B)
        pokeLocalKey(dut.io.tWrite(0).bits.key,
          stid = 1, tag = 2, index = 3, generation = 5)
        dut.io.tWrite(0).bits.data.poke(99.U)
        dut.io.localProtocolError.expect(true.B)
        dut.clock.step()
      }
    }
  }

  test("rejects a stale P generation write") {
    intercept[Exception] {
      simulate(new OooIexOperandFiles(p)) { dut =>
        clear(dut)
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.pClear(0).valid.poke(true.B)
        pokePKey(dut.io.pClear(0).bits,
          stid = 1, ptag = 40, generation = 4)
        dut.clock.step()
        dut.io.pClear(0).valid.poke(false.B)
        dut.io.pWrite(0).valid.poke(true.B)
        dut.io.pWrite(0).bits.commit.poke(true.B)
        pokePKey(dut.io.pWrite(0).bits.key,
          stid = 1, ptag = 40, generation = 3)
        dut.io.pWrite(0).bits.data.poke(99.U)
        dut.io.pProtocolError.expect(true.B)
        dut.clock.step()
      }
    }
  }

  test("rejects duplicate exact P writes without granting a mutation") {
    intercept[Exception] {
      simulate(new OooIexOperandFiles(p)) { dut =>
        clear(dut)
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.pClear(0).valid.poke(true.B)
        pokePKey(dut.io.pClear(0).bits,
          stid = 1, ptag = 40, generation = 4)
        dut.clock.step()
        dut.io.pClear(0).valid.poke(false.B)
        for (port <- 0 until 2) {
          dut.io.pWrite(port).valid.poke(true.B)
          dut.io.pWrite(port).bits.commit.poke(true.B)
          pokePKey(dut.io.pWrite(port).bits.key,
            stid = 1, ptag = 40, generation = 4)
          dut.io.pWrite(port).bits.data.poke((50 + port).U)
          dut.io.pWriteReady(port).expect(true.B)
          dut.io.pWriteFire(port).expect(false.B)
        }
        dut.io.pProtocolError.expect(true.B)
        dut.clock.step()
      }
    }
  }

  test("rejects duplicate exact local writes without granting a mutation") {
    intercept[Exception] {
      simulate(new OooIexOperandFiles(p)) { dut =>
        clear(dut)
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.tClear(0).valid.poke(true.B)
        pokeLocalKey(dut.io.tClear(0).bits,
          stid = 1, tag = 2, index = 3, generation = 4)
        dut.clock.step()
        dut.io.tClear(0).valid.poke(false.B)
        for (port <- 0 until 2) {
          dut.io.tWrite(port).valid.poke(true.B)
          dut.io.tWrite(port).bits.commit.poke(true.B)
          pokeLocalKey(dut.io.tWrite(port).bits.key,
            stid = 1, tag = 2, index = 3, generation = 4)
          dut.io.tWrite(port).bits.data.poke((60 + port).U)
          dut.io.tWriteReady(port).expect(true.B)
          dut.io.tWriteFire(port).expect(false.B)
        }
        dut.io.localProtocolError.expect(true.B)
        dut.clock.step()
      }
    }
  }
}
