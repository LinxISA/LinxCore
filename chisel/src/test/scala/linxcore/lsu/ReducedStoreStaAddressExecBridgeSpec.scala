package linxcore.lsu

import circt.stage.ChiselStage
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import linxcore.common.InterfaceParams
import linxcore.common.OperandClass
import linxcore.frontend.FrontendOpcodeDecodeTable
import linxcore.rename.StoreSplitStoreType
import org.scalatest.funsuite.AnyFunSuite

object ReducedStoreStaAddressExecBridgeReference {
  final case class Source(valid: Boolean, ready: Boolean, data: BigInt)
  final case class QueueHead(
      opcode: Int,
      pc: BigInt = 0,
      imm: BigInt = 0,
      valid: Boolean = true,
      queueValid: Boolean = true,
      addrHalf: Boolean = true,
      allStore: Boolean = false,
      sources: Vector[Source] = Vector.fill(3)(Source(valid = false, ready = true, data = 0)))
  final case class Result(
      candidate: Boolean,
      supported: Boolean,
      sourceMask: Int,
      sourceReady: Boolean,
      execValid: Boolean,
      addr: BigInt,
      data: BigInt,
      size: Int)

  private def scaled(imm: BigInt, shift: Int): BigInt =
    imm << shift

  private def pcrSize(opcode: Int): Int =
    opcode match {
      case FrontendOpcodeDecodeTable.OP_HL_SB_PCR => 1
      case FrontendOpcodeDecodeTable.OP_HL_SH_PCR => 2
      case FrontendOpcodeDecodeTable.OP_HL_SW_PCR => 4
      case _ => 8
    }

  def decide(head: QueueHead, enable: Boolean = true): Result = {
    val candidate = enable && head.queueValid && head.valid && (head.addrHalf || head.allStore)
    val (supported, mask, addr, size) =
      head.opcode match {
        case FrontendOpcodeDecodeTable.OP_HL_SB_PCR |
            FrontendOpcodeDecodeTable.OP_HL_SD_PCR |
            FrontendOpcodeDecodeTable.OP_HL_SH_PCR |
            FrontendOpcodeDecodeTable.OP_HL_SW_PCR =>
          (true, 0, head.pc + head.imm, pcrSize(head.opcode))
        case FrontendOpcodeDecodeTable.OP_SDI =>
          (true, 0x2, head.sources(1).data + scaled(head.imm, 3), 8)
        case FrontendOpcodeDecodeTable.OP_SWI =>
          (true, 0x2, head.sources(1).data + scaled(head.imm, 2), 4)
        case FrontendOpcodeDecodeTable.OP_SHI =>
          (true, 0x2, head.sources(1).data + scaled(head.imm, 1), 2)
        case FrontendOpcodeDecodeTable.OP_SBI =>
          (true, 0x2, head.sources(1).data + head.imm, 1)
        case FrontendOpcodeDecodeTable.OP_SD =>
          (true, 0x6, head.sources(1).data + scaled(head.sources(2).data, 3), 8)
        case FrontendOpcodeDecodeTable.OP_SC_W =>
          (true, 0x3, head.sources(1).data, 4)
        case FrontendOpcodeDecodeTable.OP_C_SDI =>
          (true, 0x1, head.sources(0).data + scaled(head.imm, 3), 8)
        case FrontendOpcodeDecodeTable.OP_C_SWI =>
          (true, 0x1, head.sources(0).data + scaled(head.imm, 2), 4)
        case _ =>
          (false, 0, BigInt(0), 0)
      }
    val sourceReady = (0 until 3).forall { idx =>
      ((mask & (1 << idx)) == 0) || (head.sources(idx).valid && head.sources(idx).ready)
    }
    Result(
      candidate = candidate,
      supported = supported,
      sourceMask = mask,
      sourceReady = sourceReady,
      execValid = candidate && supported && sourceReady,
      addr = addr,
      data = if (head.opcode == FrontendOpcodeDecodeTable.OP_SC_W) head.sources(0).data else 0,
      size = size)
  }
}

class ReducedStoreStaAddressExecBridgeSpec extends AnyFunSuite with ChiselSim {
  import ReducedStoreStaAddressExecBridgeReference._

  private def source(data: BigInt, valid: Boolean = true, ready: Boolean = true): Source =
    Source(valid = valid, ready = ready, data = data)

  test("reference emits an early OP_SDI STA result from the ready base source only") {
    val result = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_SDI,
      imm = 3,
      sources = Vector(source(0xdead), source(0x1000), source(0))))

    assert(result.candidate)
    assert(result.supported)
    assert(result.sourceMask == 0x2)
    assert(result.sourceReady)
    assert(result.execValid)
    assert(result.addr == 0x1018)
    assert(result.size == 8)
  }

  test("reference blocks OP_SDI STA when the address base source is not ready") {
    val result = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_SDI,
      imm = 3,
      sources = Vector(source(0xdead), source(0x1000, ready = false), source(0))))

    assert(result.candidate)
    assert(result.supported)
    assert(result.sourceMask == 0x2)
    assert(!result.sourceReady)
    assert(!result.execValid)
  }

  test("reference emits OP_SHI STA addresses from sign-extended SIMM12 scaled by two") {
    val positive = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_SHI,
      imm = 7,
      sources = Vector(source(0xdead), source(0x1000), source(0xbeef, ready = false))))
    val zero = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_SHI,
      imm = 0,
      sources = Vector(source(0, ready = false), source(0x2200), source(0, valid = false))))
    val negative = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_SHI,
      imm = -4,
      sources = Vector(source(0), source(0x2000), source(0, valid = false, ready = false))))

    assert(positive.supported)
    assert(positive.sourceMask == 0x2)
    assert(positive.sourceReady)
    assert(positive.execValid)
    assert(positive.addr == 0x100e)
    assert(positive.size == 2)

    assert(zero.sourceMask == 0x2)
    assert(zero.sourceReady)
    assert(zero.execValid)
    assert(zero.addr == 0x2200)
    assert(zero.size == 2)

    assert(negative.sourceMask == 0x2)
    assert(negative.sourceReady)
    assert(negative.execValid)
    assert(negative.addr == 0x1ff8)
    assert(negative.size == 2)
  }

  test("reference blocks OP_SHI only on the base source and leaves store data independent") {
    val baseNotReady = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_SHI,
      imm = 5,
      sources = Vector(source(0xaaaa), source(0x1000, ready = false), source(0xbbbb))))
    val dataNotReady = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_SHI,
      imm = 5,
      sources = Vector(source(0xaaaa, ready = false), source(0x1000), source(0xbbbb, valid = false, ready = false))))

    assert(baseNotReady.candidate)
    assert(baseNotReady.supported)
    assert(baseNotReady.sourceMask == 0x2)
    assert(!baseNotReady.sourceReady)
    assert(!baseNotReady.execValid)

    assert(dataNotReady.candidate)
    assert(dataNotReady.supported)
    assert(dataNotReady.sourceMask == 0x2)
    assert(dataNotReady.sourceReady)
    assert(dataNotReady.execValid)
    assert(dataNotReady.addr == 0x100a)
    assert(dataNotReady.size == 2)
  }

  test("reference gates OP_SHI candidates exactly and does not support OP_SHI_U") {
    val notQueueValid = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_SHI,
      imm = 1,
      queueValid = false,
      sources = Vector(source(0), source(0x1000), source(0))))
    val notPayloadValid = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_SHI,
      imm = 1,
      valid = false,
      sources = Vector(source(0), source(0x1000), source(0))))
    val notAddrHalf = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_SHI,
      imm = 1,
      addrHalf = false,
      sources = Vector(source(0), source(0x1000), source(0))))
    val disabled = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_SHI,
      imm = 1,
      sources = Vector(source(0), source(0x1000), source(0))),
      enable = false)
    val unsignedSibling = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_SHI_U,
      imm = 1,
      sources = Vector(source(0), source(0x1000), source(0))))

    assert(!notQueueValid.candidate)
    assert(notQueueValid.supported)
    assert(!notQueueValid.execValid)
    assert(!notPayloadValid.candidate)
    assert(notPayloadValid.supported)
    assert(!notPayloadValid.execValid)
    assert(!notAddrHalf.candidate)
    assert(notAddrHalf.supported)
    assert(!notAddrHalf.execValid)
    assert(!disabled.candidate)
    assert(disabled.supported)
    assert(!disabled.execValid)
    assert(unsignedSibling.candidate)
    assert(!unsignedSibling.supported)
    assert(unsignedSibling.sourceMask == 0)
    assert(!unsignedSibling.execValid)
  }

  test("reference keeps adjacent byte and word immediate-store STA behavior unchanged") {
    val byteStore = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_SBI,
      imm = 7,
      sources = Vector(source(0), source(0x3000), source(0, valid = false))))
    val wordStore = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_SWI,
      imm = 7,
      sources = Vector(source(0), source(0x3000), source(0, valid = false))))

    assert(byteStore.supported)
    assert(byteStore.sourceMask == 0x2)
    assert(byteStore.execValid)
    assert(byteStore.addr == 0x3007)
    assert(byteStore.size == 1)

    assert(wordStore.supported)
    assert(wordStore.sourceMask == 0x2)
    assert(wordStore.execValid)
    assert(wordStore.addr == 0x301c)
    assert(wordStore.size == 4)
  }

  test("reference emits OP_SC_W address from SrcR, data from SrcL, and size four") {
    val result = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_SC_W,
      sources = Vector(source(0x55667788), source(0x4400), source(0xdead, valid = false, ready = false))))

    assert(result.supported)
    assert(result.sourceMask == 0x3)
    assert(result.sourceReady)
    assert(result.execValid)
    assert(result.addr == 0x4400)
    assert(result.data == 0x55667788)
    assert(result.size == 4)
  }

  test("reference blocks OP_SC_W on SrcL or SrcR but not SrcP") {
    val srcLBlocked = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_SC_W,
      sources = Vector(source(0x55, ready = false), source(0x1000), source(0x2000))))
    val srcRBlocked = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_SC_W,
      sources = Vector(source(0x55), source(0x1000, ready = false), source(0x2000))))
    val srcPIgnored = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_SC_W,
      sources = Vector(source(0x55), source(0x1000), source(0x2000, valid = false, ready = false))))

    assert(!srcLBlocked.sourceReady)
    assert(!srcLBlocked.execValid)
    assert(!srcRBlocked.sourceReady)
    assert(!srcRBlocked.execValid)
    assert(srcPIgnored.sourceReady)
    assert(srcPIgnored.execValid)
  }

  test("bridge executes OP_SC_W All payload from SrcL data and SrcR address without SrcP") {
    simulate(new ReducedStoreStaAddressExecBridge(
      p = InterfaceParams(),
      mapQDepth = 32,
      peIdWidth = InterfaceParams().peIdWidth,
      stidWidth = InterfaceParams().threadIdWidth,
      tidWidth = InterfaceParams().threadIdWidth,
      sizeWidth = InterfaceParams().memSizeWidth
    )) { dut =>
      dut.io.enable.poke(true.B)
      dut.io.queueValid.poke(true.B)
      dut.io.queue.poke(0.U.asTypeOf(dut.io.queue))
      dut.io.queue.valid.poke(true.B)
      dut.io.queue.storeType.poke(StoreSplitStoreType.All)
      dut.io.queue.uop.valid.poke(true.B)
      dut.io.queue.uop.opcode.poke(FrontendOpcodeDecodeTable.OP_SC_W.U)
      dut.io.queue.uop.isStore.poke(true.B)
      dut.io.queue.uop.threadId.poke(3.U)
      dut.io.queue.uop.src(0).valid.poke(true.B)
      dut.io.queue.uop.src(0).operandClass.poke(OperandClass.P)
      dut.io.queue.uop.src(1).valid.poke(true.B)
      dut.io.queue.uop.src(1).operandClass.poke(OperandClass.P)
      dut.io.queue.uop.src(2).valid.poke(false.B)
      dut.io.queue.uop.src(2).operandClass.poke(OperandClass.P)
      dut.io.srcReadReady(0).poke(true.B)
      dut.io.srcReadReady(1).poke(true.B)
      dut.io.srcReadReady(2).poke(false.B)
      dut.io.srcReadData(0).poke(0x55667788L.U)
      dut.io.srcReadData(1).poke(0x4400.U)
      dut.io.srcReadData(2).poke(0xdead.U)

      dut.io.candidate.expect(true.B)
      dut.io.supportedOpcode.expect(true.B)
      dut.io.addrSourceMask.expect("b011".U)
      dut.io.addrSourceReady.expect(true.B)
      dut.io.exec.valid.expect(true.B)
      dut.io.exec.addr.expect(0x4400.U)
      dut.io.exec.data.expect(0x55667788L.U)
      dut.io.exec.size.expect(4.U)
    }
  }

  test("bridge executes split OP_SC_W Addr payload only when SrcL data and SrcR address are both ready") {
    simulate(new ReducedStoreStaAddressExecBridge(
      p = InterfaceParams(),
      mapQDepth = 32,
      peIdWidth = InterfaceParams().peIdWidth,
      stidWidth = InterfaceParams().threadIdWidth,
      tidWidth = InterfaceParams().threadIdWidth,
      sizeWidth = InterfaceParams().memSizeWidth
    )) { dut =>
      dut.io.enable.poke(true.B)
      dut.io.queueValid.poke(true.B)
      dut.io.queue.poke(0.U.asTypeOf(dut.io.queue))
      dut.io.queue.valid.poke(true.B)
      dut.io.queue.storeType.poke(StoreSplitStoreType.Addr)
      dut.io.queue.uop.valid.poke(true.B)
      dut.io.queue.uop.opcode.poke(FrontendOpcodeDecodeTable.OP_SC_W.U)
      dut.io.queue.uop.isStore.poke(true.B)
      dut.io.queue.uop.src(0).valid.poke(true.B)
      dut.io.queue.uop.src(0).operandClass.poke(OperandClass.P)
      dut.io.queue.uop.src(1).valid.poke(true.B)
      dut.io.queue.uop.src(1).operandClass.poke(OperandClass.P)
      dut.io.queue.uop.src(2).valid.poke(false.B)
      dut.io.srcReadReady(0).poke(true.B)
      dut.io.srcReadReady(1).poke(false.B)
      dut.io.srcReadReady(2).poke(false.B)
      dut.io.srcReadData(0).poke(0x11223344L.U)
      dut.io.srcReadData(1).poke(0x8800.U)
      dut.io.srcReadData(2).poke(0.U)

      dut.io.candidate.expect(true.B)
      dut.io.addrSourceMask.expect("b011".U)
      dut.io.addrSourceReady.expect(false.B)
      dut.io.exec.valid.expect(false.B)

      dut.io.srcReadReady(1).poke(true.B)
      dut.io.addrSourceReady.expect(true.B)
      dut.io.exec.valid.expect(true.B)
      dut.io.exec.addr.expect(0x8800.U)
      dut.io.exec.data.expect(0x11223344L.U)
      dut.io.exec.size.expect(4.U)
    }
  }

  test("reference lets PCR-store STA compute address without RF source readiness") {
    val result = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_HL_SW_PCR,
      pc = 0x4000,
      imm = 0x24,
      sources = Vector(source(0xaaaa, ready = false), source(0, valid = false), source(0, valid = false))))

    assert(result.supported)
    assert(result.sourceMask == 0)
    assert(result.sourceReady)
    assert(result.execValid)
    assert(result.addr == 0x4024)
    assert(result.size == 4)
  }

  test("reference exposes compressed-store STA source loss after payload source-zeroing") {
    val result = decide(QueueHead(
      opcode = FrontendOpcodeDecodeTable.OP_C_SDI,
      imm = 2,
      sources = Vector(source(0x2000, valid = false), source(24), source(0, valid = false))))

    assert(result.supported)
    assert(result.sourceMask == 0x1)
    assert(!result.sourceReady)
    assert(!result.execValid)
  }

  test("bridge elaborates with the reduced top interface parameters") {
    val p = InterfaceParams()
    val sv = ChiselStage.emitSystemVerilog(new ReducedStoreStaAddressExecBridge(
      p = p,
      mapQDepth = 32,
      peIdWidth = p.peIdWidth,
      stidWidth = p.threadIdWidth,
      tidWidth = p.threadIdWidth
    ))
    assert(sv.contains("module ReducedStoreStaAddressExecBridge"))
    assert(sv.contains("12'h193"))
    assert(sv.contains("io_srcReadData_1 + {io_queue_uop_imm[62:0], 1'h0}"))
    assert(sv.contains("4'h2"))
  }
}
