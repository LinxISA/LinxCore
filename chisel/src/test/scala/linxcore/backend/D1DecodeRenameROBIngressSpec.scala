package linxcore.backend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.commit.CommitTraceParams
import linxcore.common.InterfaceParams
import linxcore.frontend.FrontendOpcodeDecodeTable
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer

class D1DecodeRenameROBIngressSpec extends AnyFunSuite with ChiselSim {
  private val p = InterfaceParams(robEntries = 8, commitWidth = 2)
  private val trace = CommitTraceParams(
    commitWidth = p.commitWidth,
    robValueWidth = p.robIndexWidth,
    blockBidWidth = p.blockBidWidth,
    pcWidth = p.pcWidth,
    insnWidth = p.insnWidth,
    lenWidth = p.lenWidth)

  private def clear(dut: D1DecodeRenameROBIngress): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.ifuFlush.poke(0.U.asTypeOf(dut.io.ifuFlush))
    dut.io.renamedOutReady.poke(true.B)
    dut.io.completeValid.poke(false.B)
    dut.io.completeRobValue.poke(0.U)
    dut.io.deallocReady.poke(true.B)
    dut.io.blockBranchTakenValid.poke(false.B)
    dut.io.blockBranchTaken.poke(false.B)
  }

  private def presentFour(dut: D1DecodeRenameROBIngress): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.validMask.poke("b1111".U)
    for (lane <- 0 until p.decodeWidth) {
      val row = dut.io.in.bits.entries(lane)
      row.valid.poke(true.B)
      row.peId.poke(0.U)
      row.threadId.poke(0.U)
      row.pc.poke((0x4000 + lane * 4).U)
      row.opcode.poke(FrontendOpcodeDecodeTable.OP_ADDI.U)
      row.insnRaw.poke((0x100 + lane).U)
      row.insnLen.poke(4.U)
      row.uid.uid.poke((0x80 + lane).U)
      row.uid.fetchSlot.poke(lane.U)
      row.prediction.valid.poke(true.B)
      row.prediction.predictionTag.poke((0x900 + lane).U)
      row.prediction.transactionId.poke((0x500 + lane).U)
      row.prediction.fetchPacketUid.poke(0x40.U)
      row.prediction.fetchSeq.poke((20 + lane).U)
      row.prediction.requestPc.poke(0x4000.U)
      row.prediction.target.poke((0x5000 + lane * 8).U)
      row.prediction.checkpointId.poke((3 + lane).U)
      row.prediction.epoch.poke(2.U)
      dut.io.in.bits.meta(lane).valid.poke(true.B)
      dut.io.in.bits.meta(lane).opcode.poke(FrontendOpcodeDecodeTable.OP_ADDI.U)
    }
  }

  test("moves one four-wide fixed-width D1 group through rename and ROB in order") {
    simulate(new D1DecodeRenameROBIngress(
      p,
      trace,
      laneQueueDepth = 8,
      decRenQueueDepth = 4,
      mapQDepth = 8,
      gprMapQDepth = 16)) { dut =>
      clear(dut)
      presentFour(dut)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      val tags = ArrayBuffer.empty[BigInt]
      val pcs = ArrayBuffer.empty[BigInt]
      val rids = ArrayBuffer.empty[Int]
      var laneDequeues = 0
      var cycles = 0
      while (tags.size < 4 && cycles < 32) {
        if (dut.io.laneDequeued.peek().litToBoolean) {
          laneDequeues += 1
        }
        if (dut.io.renamedAccepted.peek().litToBoolean) {
          tags += dut.io.renamedOut.prediction.predictionTag.peek().litValue
          pcs += dut.io.renamedOut.pc.peek().litValue
          rids += dut.io.renamedOut.rid.value.peek().litValue.toInt
        }
        dut.clock.step()
        cycles += 1
      }

      assert(laneDequeues == 4)
      assert(tags.toSeq == Seq(0x900, 0x901, 0x902, 0x903).map(BigInt(_)))
      assert(pcs.toSeq == Seq(0x4000, 0x4004, 0x4008, 0x400c).map(BigInt(_)))
      assert(rids.distinct.size == 4)
      dut.io.robSize.expect(4.U)

      for (rid <- rids) {
        dut.io.completeValid.poke(true.B)
        dut.io.completeRobValue.poke(rid.U)
        dut.io.completeAccepted.expect(true.B)
        dut.clock.step()
      }
      dut.io.completeValid.poke(false.B)

      var drained = false
      for (_ <- 0 until 20) {
        if (dut.io.robEmpty.peek().litToBoolean) {
          drained = true
        }
        dut.clock.step()
      }
      assert(drained, "completed rows must retire through the real ROB owner")
    }
  }

  test("holds decoded prediction identity while rename is backpressured") {
    simulate(new D1DecodeRenameROBIngress(
      p,
      trace,
      laneQueueDepth = 8,
      decRenQueueDepth = 4,
      mapQDepth = 8,
      gprMapQDepth = 16)) { dut =>
      clear(dut)
      dut.io.renamedOutReady.poke(false.B)
      presentFour(dut)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      for (_ <- 0 until 8) {
        dut.io.renamedAccepted.expect(false.B)
        dut.clock.step()
      }

      dut.io.renamedOutReady.poke(true.B)
      val tags = ArrayBuffer.empty[BigInt]
      for (_ <- 0 until 20) {
        if (dut.io.renamedAccepted.peek().litToBoolean) {
          tags += dut.io.renamedOut.prediction.predictionTag.peek().litValue
        }
        dut.clock.step()
      }
      assert(tags.toSeq == Seq(0x900, 0x901, 0x902, 0x903).map(BigInt(_)))
    }
  }

  test("retains exact same-group ACRC and C.BSTOP adjacency after fixed-width decode") {
    simulate(new D1DecodeRenameROBIngress(
      p,
      trace,
      laneQueueDepth = 8,
      decRenQueueDepth = 4,
      mapQDepth = 8,
      gprMapQDepth = 16)) { dut =>
      clear(dut)
      presentFour(dut)
      dut.io.in.bits.validMask.poke("b0011".U)
      dut.io.in.bits.entries(0).opcode.poke(FrontendOpcodeDecodeTable.OP_ACRC.U)
      dut.io.in.bits.meta(0).opcode.poke(FrontendOpcodeDecodeTable.OP_ACRC.U)
      dut.io.in.bits.entries(1).opcode.poke(FrontendOpcodeDecodeTable.OP_C_BSTOP.U)
      dut.io.in.bits.meta(1).opcode.poke(FrontendOpcodeDecodeTable.OP_C_BSTOP.U)
      dut.io.in.bits.blockStopMask.poke("b0010".U)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      var sawAdjacent = false
      for (_ <- 0 until 20) {
        if (dut.io.serviceAdjacentStop.valid.peek().litToBoolean) {
          dut.io.serviceAdjacentStop.pc.expect(0x4004.U)
          dut.io.serviceAdjacentStop.insn.expect(0x101.U)
          dut.io.serviceAdjacentStop.len.expect(4.U)
          sawAdjacent = true
        }
        dut.clock.step()
      }
      assert(sawAdjacent, "ACRC must retain its exact same-group C.BSTOP successor")
    }
  }
}
