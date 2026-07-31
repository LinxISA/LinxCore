package linxcore.ifu

import linxcore.common.InterfaceParams
import linxcore.frontend.{IfuBackendFeedbackBridge, IfuRedirectArbiter}

/** Canonical IFU redirect arbiter.
  *
  * Backend typed recovery enters through the backend redirect input and
  * supersedes speculative prediction correction. It is not a prediction
  * provider and it does not expose an IEX control port.
  */
class IFURecovery(
    p: InterfaceParams = InterfaceParams(),
    threadCount: Int = 1)
    extends IfuRedirectArbiter(p, threadCount)

/** OOO-authored backend feedback boundary for IFU training and recovery. */
class IFUBackendFeedback(
    p: InterfaceParams = InterfaceParams(),
    entries: Int = 2)
    extends IfuBackendFeedbackBridge(p, entries)
