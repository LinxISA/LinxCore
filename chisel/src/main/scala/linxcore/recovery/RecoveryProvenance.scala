package linxcore.recovery

import chisel3._
import chisel3.util.{UIntToOH, log2Ceil}

/** Implementation-only recovery ownership metadata.
  *
  * `causeMask` tracks every source report represented by an action, while
  * `payloadSource` names the source whose exact request fields survived.
  */
class RecoveryProvenance(val sourceCount: Int) extends Bundle {
  require(sourceCount > 0, "recovery provenance must expose at least one source")

  val causeMask = UInt(sourceCount.W)
  val payloadSourceValid = Bool()
  val payloadSource = UInt(math.max(1, log2Ceil(sourceCount)).W)
}

object RecoveryProvenance {
  def oneHot(source: UInt, sourceCount: Int): UInt =
    if (sourceCount == 1) 1.U(1.W) else UIntToOH(source, sourceCount)

  def single(source: UInt, valid: Bool, sourceCount: Int): RecoveryProvenance = {
    val out = Wire(new RecoveryProvenance(sourceCount))
    out := 0.U.asTypeOf(out)
    out.causeMask := Mux(valid, oneHot(source, sourceCount), 0.U)
    out.payloadSourceValid := valid
    out.payloadSource := source
    out
  }

  /** Model merge copies the destination payload and changes only its type. */
  def merged(src: RecoveryProvenance, dst: RecoveryProvenance): RecoveryProvenance = {
    require(src.sourceCount == dst.sourceCount, "merged provenance widths must match")
    val out = Wire(new RecoveryProvenance(src.sourceCount))
    out.causeMask := src.causeMask | dst.causeMask
    out.payloadSourceValid := dst.payloadSourceValid
    out.payloadSource := dst.payloadSource
    out
  }
}
