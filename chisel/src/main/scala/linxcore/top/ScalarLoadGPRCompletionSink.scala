package linxcore.top

import chisel3._
import chisel3.util.log2Ceil

import linxcore.common.{DestinationKind, ScalarBackendParams, ScalarLsuParams}
import linxcore.execute.ScalarGPRFile
import linxcore.lsu.LoadReplayDestination

class ScalarLoadGPRCompletionSinkIO(
    val backend: ScalarBackendParams,
    val lsu: ScalarLsuParams)
    extends Bundle {
  private val tagWidth = log2Ceil(backend.gprPhysRegs)

  val initValid = Input(Bool())
  val initTag = Input(UInt(tagWidth.W))
  val initData = Input(UInt(lsu.dataWidth.W))
  val clearValid = Input(Bool())
  val clearTag = Input(UInt(tagWidth.W))

  val externalWriteValid = Input(Bool())
  val externalWriteTag = Input(UInt(tagWidth.W))
  val externalWriteData = Input(UInt(lsu.dataWidth.W))
  val externalWriteReady = Output(Bool())
  val externalWriteFire = Output(Bool())

  val loadCandidateValid = Input(Bool())
  val loadDst = Input(new LoadReplayDestination(lsu.archRegWidth, lsu.physRegWidth))
  val loadData = Input(UInt(lsu.dataWidth.W))
  val loadSpecWakeup = Input(Bool())
  val loadStackValid = Input(Bool())
  val loadResolveFire = Input(Bool())
  val loadWritebackFire = Input(Bool())
  val loadWakeupFire = Input(Bool())
  val loadWritebackReady = Output(Bool())
  val loadWakeupReady = Output(Bool())

  val readTag = Input(UInt(tagWidth.W))
  val readData = Output(UInt(lsu.dataWidth.W))
  val readReady = Output(Bool())
  val readyMask = Output(UInt(backend.gprPhysRegs.W))

  val loadWriteRequired = Output(Bool())
  val loadWakeupRequired = Output(Bool())
  val loadWritebackSelected = Output(Bool())
  val loadWakeupPublished = Output(Bool())
  val loadBlockedByExternalWrite = Output(Bool())
  val loadBlockedByUnsupportedDestination = Output(Bool())
  val protocolError = Output(Bool())
}

class ScalarLoadGPRCompletionSink(
    val backend: ScalarBackendParams = ScalarBackendParams(),
    val lsu: ScalarLsuParams = ScalarLsuParams())
    extends Module {
  require(log2Ceil(backend.gprPhysRegs) == lsu.physRegWidth,
    "scalar LSU physical tags must address the canonical GPR file")

  private val writePorts = backend.gprWritePorts
  val io = IO(new ScalarLoadGPRCompletionSinkIO(backend, lsu))
  val gpr = Module(new ScalarGPRFile(
    archRegs = backend.gprArchRegs,
    physRegs = backend.gprPhysRegs,
    dataWidth = lsu.dataWidth,
    readPorts = 1,
    writePorts = writePorts
  ))

  val hasDestination = io.loadDst.valid && (io.loadDst.kind =/= DestinationKind.None)
  val gprDestination = hasDestination && (io.loadDst.kind === DestinationKind.Gpr)
  val localDestination = hasDestination &&
    ((io.loadDst.kind === DestinationKind.T) || (io.loadDst.kind === DestinationKind.U))
  val loadWriteRequest = io.loadCandidateValid && gprDestination
  val loadWakeupRequest = io.loadCandidateValid && !io.loadSpecWakeup &&
    !io.loadStackValid && hasDestination
  val externalSameTag = io.externalWriteValid && loadWriteRequest &&
    (io.externalWriteTag === io.loadDst.physTag)
  val canUseSecondPort = writePorts > 1
  val loadPort = if (canUseSecondPort) 1 else 0
  val loadHasPort = !io.externalWriteValid || (canUseSecondPort.B && !externalSameTag)

  for (port <- 0 until writePorts) {
    gpr.io.write(port).requestValid := false.B
    gpr.io.write(port).commit := false.B
    gpr.io.write(port).tag := 0.U
    gpr.io.write(port).data := 0.U
  }
  gpr.io.write(0).requestValid := io.externalWriteValid
  gpr.io.write(0).commit := io.externalWriteValid
  gpr.io.write(0).tag := io.externalWriteTag
  gpr.io.write(0).data := io.externalWriteData

  if (canUseSecondPort) {
    gpr.io.write(loadPort).requestValid := loadWriteRequest && io.externalWriteValid && !externalSameTag
    gpr.io.write(loadPort).commit := io.loadWritebackFire && io.externalWriteValid && !externalSameTag
    gpr.io.write(loadPort).tag := io.loadDst.physTag
    gpr.io.write(loadPort).data := io.loadData
  }
  when(loadWriteRequest && !io.externalWriteValid) {
    gpr.io.write(0).requestValid := true.B
    gpr.io.write(0).commit := io.loadWritebackFire
    gpr.io.write(0).tag := io.loadDst.physTag
    gpr.io.write(0).data := io.loadData
  }

  val selectedPortReady = Mux(
    io.externalWriteValid,
    if (canUseSecondPort) gpr.io.write(loadPort).ready else false.B,
    gpr.io.write(0).ready
  )
  val loadGprReady = loadHasPort && selectedPortReady
  val unsupportedWakeup = loadWakeupRequest && localDestination
  val writebackReady = !loadWriteRequest || loadGprReady
  val wakeupReady = !loadWakeupRequest || (gprDestination && loadGprReady)

  gpr.io.initValid := io.initValid
  gpr.io.initTag := io.initTag
  gpr.io.initData := io.initData
  gpr.io.clearValid := io.clearValid
  gpr.io.clearTag := io.clearTag
  gpr.io.readValid(0) := true.B
  gpr.io.readTag(0) := io.readTag

  val expectedWritebackFire = io.loadResolveFire && loadWriteRequest
  val expectedWakeupFire = io.loadResolveFire && loadWakeupRequest
  val loadWritebackSelected = io.loadWritebackFire && loadGprReady
  val loadWakeupPublished = io.loadWakeupFire && gprDestination && loadGprReady
  val protocolError =
    (io.loadWritebackFire =/= expectedWritebackFire) ||
      (io.loadWakeupFire =/= expectedWakeupFire) ||
      (io.loadResolveFire && !(writebackReady && wakeupReady)) ||
      (io.loadWritebackFire && !loadWritebackSelected) ||
      (io.loadWakeupFire && !loadWakeupPublished) ||
      gpr.io.protocolError

  io.externalWriteReady := gpr.io.write(0).ready
  io.externalWriteFire := gpr.io.write(0).fire && io.externalWriteValid
  io.loadWritebackReady := writebackReady
  io.loadWakeupReady := wakeupReady
  io.readData := gpr.io.readData(0)
  io.readReady := gpr.io.readReady(0)
  io.readyMask := gpr.io.readyMask
  io.loadWriteRequired := loadWriteRequest
  io.loadWakeupRequired := loadWakeupRequest
  io.loadWritebackSelected := loadWritebackSelected
  io.loadWakeupPublished := loadWakeupPublished
  io.loadBlockedByExternalWrite := loadWriteRequest && !loadHasPort
  io.loadBlockedByUnsupportedDestination := unsupportedWakeup
  io.protocolError := protocolError || unsupportedWakeup

  assert(!protocolError,
    "scalar load GPR writeback and wakeup must commit with exact W2 resolve")
}
