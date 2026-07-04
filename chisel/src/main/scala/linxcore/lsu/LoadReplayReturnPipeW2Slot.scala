package linxcore.lsu

import chisel3._
import chisel3.util.log2Ceil

import linxcore.commit.{CommitOperandTrace, CommitTraceParams}
import linxcore.rob.ROBID

class LoadReplayReturnPipeW2SlotIO(
    val idEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Bundle {
  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))
  private val sourceTraceParams =
    CommitTraceParams(regWidth = math.max(8, archRegWidth), dataWidth = dataWidth)

  val enable = Input(Bool())
  val flush = Input(Bool())
  val clear = Input(Bool())
  val replaceOnClear = Input(Bool())
  val writeValid = Input(Bool())
  val writeTargetIsAgu = Input(Bool())
  val writeTargetIsLda = Input(Bool())
  val writePipeIndex = Input(UInt(returnPipeIndexWidth.W))
  val writeLoadToUsePipeIndex = Input(UInt(returnPipeIndexWidth.W))
  val writeBid = Input(new ROBID(idEntries))
  val writeGid = Input(new ROBID(idEntries))
  val writeRid = Input(new ROBID(idEntries))
  val writeLoadLsId = Input(new ROBID(idEntries))
  val writePc = Input(UInt(pcWidth.W))
  val writeAddr = Input(UInt(addrWidth.W))
  val writeSize = Input(UInt(sizeWidth.W))
  val writeDst = Input(new LoadReplayDestination(archRegWidth, physRegWidth))
  val writeSourceTraceValid = Input(Bool())
  val writeSource0 = Input(new CommitOperandTrace(sourceTraceParams))
  val writeSource1 = Input(new CommitOperandTrace(sourceTraceParams))
  val writeData = Input(UInt(dataWidth.W))
  val writeWakeupRequired = Input(Bool())

  val accepted = Output(Bool())
  val occupied = Output(Bool())
  val entryValid = Output(Bool())
  val entryTargetIsAgu = Output(Bool())
  val entryTargetIsLda = Output(Bool())
  val entryPipeIndex = Output(UInt(returnPipeIndexWidth.W))
  val entryLoadToUsePipeIndex = Output(UInt(returnPipeIndexWidth.W))
  val entryBid = Output(new ROBID(idEntries))
  val entryGid = Output(new ROBID(idEntries))
  val entryRid = Output(new ROBID(idEntries))
  val entryLoadLsId = Output(new ROBID(idEntries))
  val entryPc = Output(UInt(pcWidth.W))
  val entryAddr = Output(UInt(addrWidth.W))
  val entrySize = Output(UInt(sizeWidth.W))
  val entryDst = Output(new LoadReplayDestination(archRegWidth, physRegWidth))
  val entrySourceTraceValid = Output(Bool())
  val entrySource0 = Output(new CommitOperandTrace(sourceTraceParams))
  val entrySource1 = Output(new CommitOperandTrace(sourceTraceParams))
  val entryData = Output(UInt(dataWidth.W))
  val entryWakeupRequired = Output(Bool())
  val blockedByDisabled = Output(Bool())
  val blockedByFlush = Output(Bool())
  val blockedByClear = Output(Bool())
  val blockedByNoWrite = Output(Bool())
  val blockedByInvalidTarget = Output(Bool())
  val blockedByOccupied = Output(Bool())
  val acceptedEmpty = Output(Bool())
  val replacedOnClear = Output(Bool())
  val blockedByReplaceDisabled = Output(Bool())
}

class LoadReplayReturnPipeW2Slot(
    val idEntries: Int = 16,
    val addrWidth: Int = 64,
    val pcWidth: Int = 64,
    val dataWidth: Int = 64,
    val sizeWidth: Int = 7,
    val returnPipeCount: Int = 1,
    val archRegWidth: Int = 6,
    val physRegWidth: Int = 6)
    extends Module {
  require(idEntries > 1, "idEntries must be greater than one")
  require((idEntries & (idEntries - 1)) == 0, "idEntries must be a power of two")
  require(addrWidth > 0, "addrWidth must be positive")
  require(pcWidth > 0, "pcWidth must be positive")
  require(dataWidth > 0, "dataWidth must be positive")
  require(sizeWidth > 0, "sizeWidth must be positive")
  require(returnPipeCount > 0, "returnPipeCount must be positive")
  require(archRegWidth > 0, "archRegWidth must be positive")
  require(physRegWidth > 0, "physRegWidth must be positive")

  private val returnPipeIndexWidth = math.max(1, log2Ceil(returnPipeCount))
  private val sourceTraceParams =
    CommitTraceParams(regWidth = math.max(8, archRegWidth), dataWidth = dataWidth)

  val io = IO(new LoadReplayReturnPipeW2SlotIO(
    idEntries,
    addrWidth,
    pcWidth,
    dataWidth,
    sizeWidth,
    returnPipeCount,
    archRegWidth,
    physRegWidth
  ))

  val occupiedReg = RegInit(false.B)
  val targetIsAguReg = RegInit(false.B)
  val targetIsLdaReg = RegInit(false.B)
  val pipeIndexReg = RegInit(0.U(returnPipeIndexWidth.W))
  val loadToUsePipeIndexReg = RegInit(0.U(returnPipeIndexWidth.W))
  val bidReg = RegInit(ROBID.disabled(idEntries))
  val gidReg = RegInit(ROBID.disabled(idEntries))
  val ridReg = RegInit(ROBID.disabled(idEntries))
  val loadLsIdReg = RegInit(ROBID.disabled(idEntries))
  val pcReg = RegInit(0.U(pcWidth.W))
  val addrReg = RegInit(0.U(addrWidth.W))
  val sizeReg = RegInit(0.U(sizeWidth.W))
  val dstReg = RegInit(LoadReplayDestination.none(archRegWidth, physRegWidth))
  val sourceTraceValidReg = RegInit(false.B)
  val source0Reg = RegInit(0.U.asTypeOf(new CommitOperandTrace(sourceTraceParams)))
  val source1Reg = RegInit(0.U.asTypeOf(new CommitOperandTrace(sourceTraceParams)))
  val dataReg = RegInit(0.U(dataWidth.W))
  val wakeupRequiredReg = RegInit(false.B)

  val active = io.enable && !io.flush
  val targetValid = io.writeTargetIsAgu ^ io.writeTargetIsLda
  val writeCandidate = active && io.writeValid && targetValid
  val acceptedEmpty = writeCandidate && !io.clear && !occupiedReg
  val replacedOnClear = writeCandidate && io.clear && io.replaceOnClear && occupiedReg
  val accepted = acceptedEmpty || replacedOnClear

  when(io.flush) {
    occupiedReg := false.B
    targetIsAguReg := false.B
    targetIsLdaReg := false.B
    pipeIndexReg := 0.U
    loadToUsePipeIndexReg := 0.U
    bidReg := ROBID.disabled(idEntries)
    gidReg := ROBID.disabled(idEntries)
    ridReg := ROBID.disabled(idEntries)
    loadLsIdReg := ROBID.disabled(idEntries)
    pcReg := 0.U
    addrReg := 0.U
    sizeReg := 0.U
    dstReg := LoadReplayDestination.none(archRegWidth, physRegWidth)
    sourceTraceValidReg := false.B
    source0Reg := 0.U.asTypeOf(source0Reg)
    source1Reg := 0.U.asTypeOf(source1Reg)
    dataReg := 0.U
    wakeupRequiredReg := false.B
  }.elsewhen(accepted) {
    occupiedReg := true.B
    targetIsAguReg := io.writeTargetIsAgu
    targetIsLdaReg := io.writeTargetIsLda
    pipeIndexReg := io.writePipeIndex
    loadToUsePipeIndexReg := io.writeLoadToUsePipeIndex
    bidReg := io.writeBid
    gidReg := io.writeGid
    ridReg := io.writeRid
    loadLsIdReg := io.writeLoadLsId
    pcReg := io.writePc
    addrReg := io.writeAddr
    sizeReg := io.writeSize
    dstReg := io.writeDst
    sourceTraceValidReg := io.writeSourceTraceValid
    source0Reg := io.writeSource0
    source1Reg := io.writeSource1
    dataReg := io.writeData
    wakeupRequiredReg := io.writeWakeupRequired
  }.elsewhen(io.clear) {
    occupiedReg := false.B
    targetIsAguReg := false.B
    targetIsLdaReg := false.B
    pipeIndexReg := 0.U
    loadToUsePipeIndexReg := 0.U
    bidReg := ROBID.disabled(idEntries)
    gidReg := ROBID.disabled(idEntries)
    ridReg := ROBID.disabled(idEntries)
    loadLsIdReg := ROBID.disabled(idEntries)
    pcReg := 0.U
    addrReg := 0.U
    sizeReg := 0.U
    dstReg := LoadReplayDestination.none(archRegWidth, physRegWidth)
    sourceTraceValidReg := false.B
    source0Reg := 0.U.asTypeOf(source0Reg)
    source1Reg := 0.U.asTypeOf(source1Reg)
    dataReg := 0.U
    wakeupRequiredReg := false.B
  }

  io.accepted := accepted
  io.occupied := occupiedReg
  io.entryValid := occupiedReg
  io.entryTargetIsAgu := targetIsAguReg
  io.entryTargetIsLda := targetIsLdaReg
  io.entryPipeIndex := pipeIndexReg
  io.entryLoadToUsePipeIndex := loadToUsePipeIndexReg
  io.entryBid := bidReg
  io.entryGid := gidReg
  io.entryRid := ridReg
  io.entryLoadLsId := loadLsIdReg
  io.entryPc := pcReg
  io.entryAddr := addrReg
  io.entrySize := sizeReg
  io.entryDst := dstReg
  io.entrySourceTraceValid := sourceTraceValidReg
  io.entrySource0 := source0Reg
  io.entrySource1 := source1Reg
  io.entryData := dataReg
  io.entryWakeupRequired := wakeupRequiredReg
  io.blockedByDisabled := !io.enable && io.writeValid
  io.blockedByFlush := io.enable && io.flush && io.writeValid
  io.blockedByClear := active && io.clear && io.writeValid && !replacedOnClear
  io.blockedByNoWrite := active && !io.clear && !io.writeValid
  io.blockedByInvalidTarget := active && !io.clear && io.writeValid && !targetValid
  io.blockedByOccupied := active && !io.clear && io.writeValid && targetValid && occupiedReg
  io.acceptedEmpty := acceptedEmpty
  io.replacedOnClear := replacedOnClear
  io.blockedByReplaceDisabled :=
    active && io.clear && io.writeValid && targetValid && occupiedReg && !io.replaceOnClear
}
