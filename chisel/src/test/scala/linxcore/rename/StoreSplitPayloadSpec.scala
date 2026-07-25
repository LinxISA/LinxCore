package linxcore.rename

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import linxcore.common.{DestinationKind, InterfaceParams, OperandClass}
import linxcore.frontend.FrontendOpcodeDecodeTable
import linxcore.rob.ROBID
import org.scalatest.funsuite.AnyFunSuite

object StoreSplitPayloadReference {
  sealed trait StoreType
  case object All extends StoreType
  case object Addr extends StoreType
  case object Data extends StoreType

  final case class Uop(
      valid: Boolean,
      isStore: Boolean,
      storeSplitIntent: Boolean,
      isLoadStorePair: Boolean = false,
      isStorePcr: Boolean = false,
      cacheMaintainNoSplit: Boolean = false,
      opcode: Int = 0,
      bid: Int = 0,
      rid: Int = 0,
      lsid: Int = 0,
      tSeq: Int = 0,
      uSeq: Int = 0,
      tuDstValid: Boolean = false,
      tuDst: String = "None")

  final case class Payload(
      valid: Boolean,
      storeType: StoreType,
      dataSrcIndex: Int,
      staSrc0Zeroed: Boolean,
      bid: Int,
      rid: Int,
      lsid: Int,
      tSeq: Int,
      uSeq: Int,
      tuDstValid: Boolean,
      tuDst: String)

  final case class Decision(
      inReady: Boolean,
      fire: Boolean,
      storeActive: Boolean,
      split: Boolean,
      blockedBySta: Boolean,
      blockedByStd: Boolean,
      sta: Payload,
      std: Payload,
      unsplit: Payload)

  private def empty(storeType: StoreType): Payload =
    Payload(
      valid = false,
      storeType = storeType,
      dataSrcIndex = 0,
      staSrc0Zeroed = false,
      bid = 0,
      rid = 0,
      lsid = 0,
      tSeq = 0,
      uSeq = 0,
      tuDstValid = false,
      tuDst = "None")

  private def payload(uop: Uop, valid: Boolean, storeType: StoreType, dataSrcIndex: Int, staSrc0Zeroed: Boolean): Payload =
    Payload(
      valid = valid,
      storeType = storeType,
      dataSrcIndex = dataSrcIndex,
      staSrc0Zeroed = staSrc0Zeroed,
      bid = uop.bid,
      rid = uop.rid,
      lsid = uop.lsid,
      tSeq = if (valid) uop.tSeq else 0,
      uSeq = if (valid) uop.uSeq else 0,
      tuDstValid = valid && uop.tuDstValid,
      tuDst = if (valid && uop.tuDstValid) uop.tuDst else "None")

  def decide(uop: Uop, staReady: Boolean, stdReady: Boolean): Decision = {
    val storeActive = uop.valid && uop.isStore
    val split =
      storeActive && uop.storeSplitIntent && !uop.isLoadStorePair && !uop.cacheMaintainNoSplit
    val readyForStore = if (split) staReady && stdReady else staReady
    val fire = storeActive && readyForStore
    val dataSrcIndex = if (uop.isStorePcr) 1 else 0
    val preserveStaSrc0 = uop.isStorePcr || uop.opcode == FrontendOpcodeDecodeTable.OP_SC_W

    Decision(
      inReady = !storeActive || readyForStore,
      fire = fire,
      storeActive = storeActive,
      split = split,
      blockedBySta = storeActive && !staReady,
      blockedByStd = split && !stdReady,
      sta = payload(
        uop = uop,
        valid = fire && split,
        storeType = Addr,
        dataSrcIndex = dataSrcIndex,
        staSrc0Zeroed = !preserveStaSrc0),
      std = payload(
        uop = uop,
        valid = fire && split,
        storeType = Data,
        dataSrcIndex = dataSrcIndex,
        staSrc0Zeroed = false),
      unsplit = payload(
        uop = uop,
        valid = fire && !split,
        storeType = All,
        dataSrcIndex = dataSrcIndex,
        staSrc0Zeroed = false))
  }
}

class StoreSplitPayloadSpec extends AnyFunSuite with ChiselSim {
  import StoreSplitPayloadReference._

  private def pokeRobId(id: ROBID, value: Int, valid: Boolean = true, wrap: Boolean = false): Unit = {
    id.valid.poke(valid.B)
    id.wrap.poke(wrap.B)
    id.value.poke(value.U)
  }

  test("reference splits ordinary stores into atomic STA and STD payloads") {
    val uop = Uop(
      valid = true,
      isStore = true,
      storeSplitIntent = true,
      bid = 7,
      rid = 3,
      lsid = 11,
      tSeq = 5,
      uSeq = 6,
      tuDstValid = true,
      tuDst = "T")
    val decision = decide(uop, staReady = true, stdReady = true)

    assert(decision.inReady)
    assert(decision.fire)
    assert(decision.storeActive)
    assert(decision.split)
    assert(decision.sta.valid)
    assert(decision.std.valid)
    assert(!decision.unsplit.valid)
    assert(decision.sta.storeType == Addr)
    assert(decision.std.storeType == Data)
    assert(decision.sta.dataSrcIndex == 0)
    assert(decision.std.dataSrcIndex == 0)
    assert(decision.sta.staSrc0Zeroed)
    assert(decision.sta.bid == uop.bid)
    assert(decision.std.bid == uop.bid)
    assert(decision.sta.rid == uop.rid)
    assert(decision.std.rid == uop.rid)
    assert(decision.sta.lsid == uop.lsid)
    assert(decision.std.lsid == uop.lsid)
    assert(decision.sta.tSeq == uop.tSeq)
    assert(decision.std.tSeq == uop.tSeq)
    assert(decision.sta.uSeq == uop.uSeq)
    assert(decision.std.uSeq == uop.uSeq)
    assert(decision.sta.tuDstValid)
    assert(decision.std.tuDstValid)
    assert(decision.sta.tuDst == "T")
    assert(decision.std.tuDst == "T")
  }

  test("reference preserves PCR store source 0 and selects data source 1") {
    val uop = Uop(valid = true, isStore = true, storeSplitIntent = true, isStorePcr = true)
    val decision = decide(uop, staReady = true, stdReady = true)

    assert(decision.split)
    assert(decision.sta.valid)
    assert(!decision.sta.staSrc0Zeroed)
    assert(decision.sta.dataSrcIndex == 1)
    assert(decision.std.dataSrcIndex == 1)
  }

  test("reference preserves SC.W split STA source 0 while ordinary split stores still zero it") {
    val ordinary = decide(Uop(valid = true, isStore = true, storeSplitIntent = true), staReady = true, stdReady = true)
    val sc = decide(
      Uop(
        valid = true,
        isStore = true,
        storeSplitIntent = true,
        opcode = FrontendOpcodeDecodeTable.OP_SC_W),
      staReady = true,
      stdReady = true)

    assert(ordinary.split)
    assert(ordinary.sta.valid)
    assert(ordinary.sta.staSrc0Zeroed)
    assert(sc.split)
    assert(sc.sta.valid)
    assert(!sc.sta.staSrc0Zeroed)
    assert(sc.sta.dataSrcIndex == 0)
    assert(sc.std.dataSrcIndex == 0)
  }

  test("StoreSplitPayload preserves executable SC.W STA SrcL and still zeroes ordinary STA SrcL") {
    simulate(new StoreSplitPayload(InterfaceParams(robEntries = 8), mapQDepth = 8)) { dut =>
      dut.io.in.poke(0.U.asTypeOf(dut.io.in))
      dut.io.tSeq.poke(0.U.asTypeOf(dut.io.tSeq))
      dut.io.uSeq.poke(0.U.asTypeOf(dut.io.uSeq))
      dut.io.tuDstValid.poke(false.B)
      dut.io.tuDstKind.poke(DestinationKind.None)
      dut.io.staReady.poke(true.B)
      dut.io.stdReady.poke(true.B)

      dut.io.in.valid.poke(true.B)
      dut.io.in.isStore.poke(true.B)
      dut.io.in.storeSplitIntent.poke(true.B)
      dut.io.in.opcode.poke(FrontendOpcodeDecodeTable.OP_SC_W.U)
      dut.io.in.lsid.poke(13.U)
      pokeRobId(dut.io.in.bid, 1)
      pokeRobId(dut.io.in.gid, 2)
      pokeRobId(dut.io.in.rid, 3)
      dut.io.in.src(0).valid.poke(true.B)
      dut.io.in.src(0).operandClass.poke(OperandClass.P)
      dut.io.in.src(0).archTag.poke(5.U)
      dut.io.in.src(0).relTag.poke(6.U)
      dut.io.in.src(0).physTag.poke(7.U)
      dut.io.in.src(0).ready.poke(false.B)
      dut.io.in.src(0).literalValid.poke(false.B)
      dut.io.in.src(0).literal.poke(0x1234.U)

      dut.io.fire.expect(true.B)
      dut.io.sta.valid.expect(true.B)
      dut.io.sta.storeType.expect(StoreSplitStoreType.Addr)
      dut.io.sta.staSrc0Zeroed.expect(false.B)
      dut.io.sta.dataSrcIndex.expect(0.U)
      dut.io.sta.uop.src(0).valid.expect(true.B)
      dut.io.sta.uop.src(0).operandClass.expect(OperandClass.P)
      dut.io.sta.uop.src(0).archTag.expect(5.U)
      dut.io.sta.uop.src(0).relTag.expect(6.U)
      dut.io.sta.uop.src(0).physTag.expect(7.U)
      dut.io.sta.uop.src(0).ready.expect(false.B)
      dut.io.sta.uop.src(0).literalValid.expect(false.B)
      dut.io.sta.uop.src(0).literal.expect(0x1234.U)

      dut.io.in.opcode.poke(FrontendOpcodeDecodeTable.OP_SDI.U)
      dut.io.sta.staSrc0Zeroed.expect(true.B)
      dut.io.sta.uop.src(0).valid.expect(false.B)
      dut.io.sta.uop.src(0).operandClass.expect(OperandClass.Invalid)
    }
  }

  test("reference emits a single ST_ALL payload for pair and cache-maintain stores") {
    val pair = Uop(valid = true, isStore = true, storeSplitIntent = true, isLoadStorePair = true)
    val cacheMaintain =
      Uop(valid = true, isStore = true, storeSplitIntent = true, cacheMaintainNoSplit = true)

    val pairDecision = decide(pair, staReady = true, stdReady = false)
    val cacheDecision = decide(cacheMaintain, staReady = true, stdReady = false)

    assert(pairDecision.inReady)
    assert(pairDecision.fire)
    assert(!pairDecision.split)
    assert(pairDecision.unsplit.valid)
    assert(pairDecision.unsplit.storeType == All)
    assert(!pairDecision.sta.valid)
    assert(!pairDecision.std.valid)

    assert(cacheDecision.inReady)
    assert(cacheDecision.fire)
    assert(!cacheDecision.split)
    assert(cacheDecision.unsplit.valid)
    assert(cacheDecision.unsplit.storeType == All)
  }

  test("reference blocks split stores atomically when either dispatch queue is not ready") {
    val uop = Uop(valid = true, isStore = true, storeSplitIntent = true)
    val blockedByStd = decide(uop, staReady = true, stdReady = false)
    val blockedBySta = decide(uop, staReady = false, stdReady = true)

    assert(!blockedByStd.inReady)
    assert(!blockedByStd.fire)
    assert(blockedByStd.blockedByStd)
    assert(!blockedByStd.sta.valid)
    assert(!blockedByStd.std.valid)

    assert(!blockedBySta.inReady)
    assert(!blockedBySta.fire)
    assert(blockedBySta.blockedBySta)
    assert(!blockedBySta.sta.valid)
    assert(!blockedBySta.std.valid)
  }

  test("reference does not emit store payloads for non-store rows") {
    val decision = decide(Uop(valid = true, isStore = false, storeSplitIntent = true), staReady = false, stdReady = false)

    assert(decision.inReady)
    assert(!decision.fire)
    assert(!decision.storeActive)
    assert(!decision.sta.valid)
    assert(!decision.std.valid)
    assert(!decision.unsplit.valid)
  }

  test("StoreSplitPayload IO and enum values match model ST_* ordering") {
    val p = InterfaceParams()
    val io = new StoreSplitPayloadIO(p, mapQDepth = 32)

    assert(StoreSplitStoreType.All.asUInt.litValue == 0)
    assert(StoreSplitStoreType.Addr.asUInt.litValue == 1)
    assert(StoreSplitStoreType.Data.asUInt.litValue == 2)
    assert(io.in.lsid.getWidth == 32)
    assert(io.sta.uop.lsid.getWidth == 32)
    assert(io.std.uop.lsid.getWidth == 32)
    assert(io.unsplit.uop.lsid.getWidth == 32)
    assert(io.sta.dataSrcIndex.getWidth == 2)
    assert(io.std.dataSrcIndex.getWidth == 2)
    assert(io.tSeq.value.getWidth == 5)
    assert(io.uSeq.value.getWidth == 5)
    assert(io.tuDstKind.getWidth == DestinationKind.getWidth)
    assert(io.sta.tSeq.value.getWidth == 5)
    assert(io.std.uSeq.value.getWidth == 5)
    assert(io.unsplit.tuDstKind.getWidth == DestinationKind.getWidth)
  }

  test("StoreSplitPayload elaborates as a separate rename owner") {
    val sv = ChiselStage.emitSystemVerilog(new StoreSplitPayload(InterfaceParams()))

    assert(sv.contains("module StoreSplitPayload"))
    assert(sv.contains("io_sta_valid"))
    assert(sv.contains("io_std_valid"))
    assert(sv.contains("io_unsplit_valid"))
    assert(sv.contains("io_tSeq_value"))
    assert(sv.contains("io_sta_tSeq_value"))
    assert(sv.contains("io_std_uSeq_value"))
    assert(sv.contains("io_blockedByStd"))
  }
}
