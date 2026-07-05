package linxcore.top

import linxcore.common.CoreParams

class LinxCoreFrontendFetchRfAluReducedStoreReplayLiqTraceTop(
    coreParams: CoreParams = CoreParams(),
    decRenQueueDepth: Int = 4,
    issueQueueDepth: Int = 4,
    denseSlotQueueDepth: Int = 8,
    storeDispatchQueueDepth: Int = 4,
    storeExecBufferEntries: Int = 4,
    mapQDepth: Int = 32,
    gprMapQDepth: Int = 32,
    archRegs: Int = 24,
    physRegs: Int = 64,
    useReducedStoreStaAddressExecBridge: Boolean = false,
    reducedStoreStdExecDelayCycles: Int = 0)
    extends LinxCoreFrontendFetchRfAluTraceTop(
      coreParams = coreParams,
      decRenQueueDepth = decRenQueueDepth,
      issueQueueDepth = issueQueueDepth,
      denseSlotQueueDepth = denseSlotQueueDepth,
      storeDispatchQueueDepth = storeDispatchQueueDepth,
      storeExecBufferEntries = storeExecBufferEntries,
      mapQDepth = mapQDepth,
      gprMapQDepth = gprMapQDepth,
      archRegs = archRegs,
      physRegs = physRegs,
      useReducedStoreDispatchStq = true,
      useReducedStoreStaAddressExecBridge = useReducedStoreStaAddressExecBridge,
      useReducedLoadReplayLiqAlloc = true,
      reducedStoreStdExecDelayCycles = reducedStoreStdExecDelayCycles)

object EmitLinxCoreFrontendFetchRfAluReducedStoreReplayLiqTraceTop extends App {
  private def envInt(name: String): Int =
    sys.env.get(name).filter(_.nonEmpty).map { value =>
      value.toIntOption.getOrElse {
        throw new IllegalArgumentException(s"$name must be a nonnegative integer, got '$value'")
      }
    }.getOrElse(0)

  private val stdDelayCycles = envInt("LINXCORE_REPLAY_LIQ_STD_DELAY_CYCLES")
  require(stdDelayCycles >= 0, "LINXCORE_REPLAY_LIQ_STD_DELAY_CYCLES must be nonnegative")
  private val useEarlyStaAddressExec =
    sys.env.get("LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS").exists(value => value.nonEmpty && value != "0")

  circt.stage.ChiselStage.emitSystemVerilogFile(
    new LinxCoreFrontendFetchRfAluReducedStoreReplayLiqTraceTop(
      CoreParams(robEntries = 8, commitWidth = 2),
      mapQDepth = 32,
      gprMapQDepth = 256,
      physRegs = 128,
      useReducedStoreStaAddressExecBridge = useEarlyStaAddressExec,
      reducedStoreStdExecDelayCycles = stdDelayCycles),
    args = Array("--target-dir", "../generated/chisel-verilog/frontend-fetch-rf-alu-reduced-store-replay-liq-trace-top"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
