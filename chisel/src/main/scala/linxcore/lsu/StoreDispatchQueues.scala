package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.common.InterfaceParams
import linxcore.rename.StoreSplitIssuePayload

class StoreDispatchQueuesIO(
    val p: InterfaceParams = InterfaceParams(),
    val depth: Int = 4,
    val mapQDepth: Int = 32)
    extends Bundle {
  private val countWidth = log2Ceil(depth + 1)

  val staIn = Input(new StoreSplitIssuePayload(p, mapQDepth))
  val stdIn = Input(new StoreSplitIssuePayload(p, mapQDepth))
  val unsplitIn = Input(new StoreSplitIssuePayload(p, mapQDepth))
  val flushValid = Input(Bool())
  val staDequeueReady = Input(Bool())
  val stdDequeueReady = Input(Bool())

  val staReady = Output(Bool())
  val stdReady = Output(Bool())
  val inputProtocolError = Output(Bool())
  val splitInput = Output(Bool())
  val unsplitInput = Output(Bool())
  val staEnqueueFire = Output(Bool())
  val stdEnqueueFire = Output(Bool())
  val staDequeueFire = Output(Bool())
  val stdDequeueFire = Output(Bool())
  val staOutValid = Output(Bool())
  val stdOutValid = Output(Bool())
  val staOut = Output(new StoreSplitIssuePayload(p, mapQDepth))
  val stdOut = Output(new StoreSplitIssuePayload(p, mapQDepth))
  val staCount = Output(UInt(countWidth.W))
  val stdCount = Output(UInt(countWidth.W))
  val staEmpty = Output(Bool())
  val stdEmpty = Output(Bool())
  val staFull = Output(Bool())
  val stdFull = Output(Bool())
}

class StoreDispatchQueues(
    val p: InterfaceParams = InterfaceParams(),
    val depth: Int = 4,
    val mapQDepth: Int = 32)
    extends Module {
  require(depth > 0 && (depth & (depth - 1)) == 0, "store dispatch queue depth must be a power of two")

  private val ptrWidth = math.max(1, log2Ceil(depth))
  private val countWidth = log2Ceil(depth + 1)

  val io = IO(new StoreDispatchQueuesIO(p, depth, mapQDepth))

  private def zeroPayload: StoreSplitIssuePayload = {
    val payload = Wire(new StoreSplitIssuePayload(p, mapQDepth))
    payload := 0.U.asTypeOf(payload)
    payload
  }

  private def nextPtr(ptr: UInt): UInt =
    Mux(ptr === (depth - 1).U, 0.U, ptr + 1.U)

  val staMem = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new StoreSplitIssuePayload(p, mapQDepth)))))
  val stdMem = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new StoreSplitIssuePayload(p, mapQDepth)))))
  val staHead = RegInit(0.U(ptrWidth.W))
  val staTail = RegInit(0.U(ptrWidth.W))
  val stdHead = RegInit(0.U(ptrWidth.W))
  val stdTail = RegInit(0.U(ptrWidth.W))
  val staCount = RegInit(0.U(countWidth.W))
  val stdCount = RegInit(0.U(countWidth.W))

  val splitInput = io.staIn.valid && io.stdIn.valid && !io.unsplitIn.valid
  val unsplitInput = io.unsplitIn.valid && !io.staIn.valid && !io.stdIn.valid
  val mixedUnsplit = io.unsplitIn.valid && (io.staIn.valid || io.stdIn.valid)
  val loneSplitHalf = (io.staIn.valid =/= io.stdIn.valid) && !io.unsplitIn.valid
  val inputProtocolError = mixedUnsplit || loneSplitHalf

  val staOutValid = staCount =/= 0.U
  val stdOutValid = stdCount =/= 0.U
  val staDequeueFire = !io.flushValid && staOutValid && io.staDequeueReady
  val stdDequeueFire = !io.flushValid && stdOutValid && io.stdDequeueReady
  val staCanEnqueue = (staCount =/= depth.U) || staDequeueFire
  val stdCanEnqueue = (stdCount =/= depth.U) || stdDequeueFire
  val splitEnqueueFire = !io.flushValid && splitInput && !inputProtocolError && staCanEnqueue && stdCanEnqueue
  val unsplitEnqueueFire = !io.flushValid && unsplitInput && !inputProtocolError && staCanEnqueue
  val staEnqueueFire = splitEnqueueFire || unsplitEnqueueFire
  val stdEnqueueFire = splitEnqueueFire

  io.staReady := !io.flushValid && staCanEnqueue
  io.stdReady := !io.flushValid && stdCanEnqueue
  io.inputProtocolError := inputProtocolError
  io.splitInput := splitInput
  io.unsplitInput := unsplitInput
  io.staEnqueueFire := staEnqueueFire
  io.stdEnqueueFire := stdEnqueueFire
  io.staDequeueFire := staDequeueFire
  io.stdDequeueFire := stdDequeueFire
  io.staOutValid := staOutValid
  io.stdOutValid := stdOutValid
  io.staOut := Mux(staOutValid, staMem(staHead), zeroPayload)
  io.stdOut := Mux(stdOutValid, stdMem(stdHead), zeroPayload)
  io.staCount := staCount
  io.stdCount := stdCount
  io.staEmpty := staCount === 0.U
  io.stdEmpty := stdCount === 0.U
  io.staFull := staCount === depth.U
  io.stdFull := stdCount === depth.U

  when(io.flushValid) {
    staHead := 0.U
    staTail := 0.U
    stdHead := 0.U
    stdTail := 0.U
    staCount := 0.U
    stdCount := 0.U
  }.otherwise {
    when(staEnqueueFire) {
      staMem(staTail) := Mux(unsplitEnqueueFire, io.unsplitIn, io.staIn)
      staTail := nextPtr(staTail)
    }
    when(stdEnqueueFire) {
      stdMem(stdTail) := io.stdIn
      stdTail := nextPtr(stdTail)
    }
    when(staDequeueFire) {
      staHead := nextPtr(staHead)
    }
    when(stdDequeueFire) {
      stdHead := nextPtr(stdHead)
    }

    staCount := staCount + staEnqueueFire.asUInt - staDequeueFire.asUInt
    stdCount := stdCount + stdEnqueueFire.asUInt - stdDequeueFire.asUInt
  }
}
