package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

class SCBResponseBufferEntry(
    val scbEntries: Int)
    extends Bundle {
  private val entryIndexWidth = math.max(1, log2Ceil(scbEntries))
  private val txnIdWidth = entryIndexWidth + 2

  val txnId = UInt(txnIdWidth.W)
  val writeResp = Bool()
  val upgradeResp = Bool()
}

class SCBResponseBufferIO(
    val scbEntries: Int,
    val depth: Int)
    extends Bundle {
  private val entryIndexWidth = math.max(1, log2Ceil(scbEntries))
  private val txnIdWidth = entryIndexWidth + 2
  private val countWidth = log2Ceil(depth + 1)

  val rawValid = Input(Bool())
  val rawTxnId = Input(UInt(txnIdWidth.W))
  val rawWriteResp = Input(Bool())
  val rawUpgradeResp = Input(Bool())
  val rawReady = Output(Bool())
  val rawAccepted = Output(Bool())

  val headReady = Input(Bool())
  val headValid = Output(Bool())
  val headTxnId = Output(UInt(txnIdWidth.W))
  val headWriteResp = Output(Bool())
  val headUpgradeResp = Output(Bool())
  val headConsumed = Output(Bool())

  val empty = Output(Bool())
  val full = Output(Bool())
  val count = Output(UInt(countWidth.W))
}

class SCBResponseBuffer(
    val scbEntries: Int = 16,
    val depth: Int = 4)
    extends Module {
  require(scbEntries > 0, "SCB response buffer entries must be nonzero")
  require(depth > 0, "SCB response buffer depth must be nonzero")

  private val ptrWidth = math.max(1, log2Ceil(depth))
  private val countWidth = log2Ceil(depth + 1)

  val io = IO(new SCBResponseBufferIO(scbEntries, depth))

  private def inc(ptr: UInt): UInt = {
    if (depth == 1) {
      0.U(ptrWidth.W)
    } else {
      Mux(ptr === (depth - 1).U, 0.U, ptr + 1.U)
    }
  }

  val entries = Reg(Vec(depth, new SCBResponseBufferEntry(scbEntries)))
  val headPtr = RegInit(0.U(ptrWidth.W))
  val tailPtr = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(countWidth.W))

  val empty = count === 0.U
  val full = count === depth.U
  val headValid = !empty
  val headFire = headValid && io.headReady
  val rawReady = !full || headFire
  val rawFire = io.rawValid && rawReady

  when(rawFire) {
    entries(tailPtr).txnId := io.rawTxnId
    entries(tailPtr).writeResp := io.rawWriteResp
    entries(tailPtr).upgradeResp := io.rawUpgradeResp
  }

  when(headFire) {
    headPtr := inc(headPtr)
  }

  when(rawFire) {
    tailPtr := inc(tailPtr)
  }

  when(rawFire && !headFire) {
    count := count + 1.U
  }.elsewhen(!rawFire && headFire) {
    count := count - 1.U
  }

  val head = entries(headPtr)
  io.rawReady := rawReady
  io.rawAccepted := rawFire
  io.headValid := headValid
  io.headTxnId := head.txnId
  io.headWriteResp := head.writeResp
  io.headUpgradeResp := head.upgradeResp
  io.headConsumed := headFire
  io.empty := empty
  io.full := full
  io.count := count
}
