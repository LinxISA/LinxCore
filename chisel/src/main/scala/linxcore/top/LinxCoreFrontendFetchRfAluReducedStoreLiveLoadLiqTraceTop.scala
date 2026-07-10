package linxcore.top

import linxcore.common.CoreParams

/**
  * Harness top that routes every ordinary scalar E1 load through LIQ admission.
  *
  * The inherited replay-LIQ path remains enabled for any later store-wakeup
  * work, while `useReducedLiveLoadLiq` removes direct execute completion for
  * first-pass loads.
  */
class LinxCoreFrontendFetchRfAluReducedStoreLiveLoadLiqTraceTop(
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
    useReducedStoreStaAddressExecBridge: Boolean = false)
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
      useReducedLiveLoadLiq = true)

object EmitLinxCoreFrontendFetchRfAluReducedStoreLiveLoadLiqTraceTop extends App {
  private val useEarlyStaAddressExec =
    sys.env.get("LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS").exists(value => value.nonEmpty && value != "0")

  circt.stage.ChiselStage.emitSystemVerilogFile(
    new LinxCoreFrontendFetchRfAluReducedStoreLiveLoadLiqTraceTop(
      CoreParams(robEntries = 8, commitWidth = 2),
      mapQDepth = 32,
      gprMapQDepth = 256,
      physRegs = 128,
      useReducedStoreStaAddressExecBridge = useEarlyStaAddressExec),
    args = Array("--target-dir", "../generated/chisel-verilog/frontend-fetch-rf-alu-reduced-store-live-load-liq-trace-top"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
