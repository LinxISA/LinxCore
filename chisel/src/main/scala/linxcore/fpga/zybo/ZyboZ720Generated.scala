// GENERATED FILE - DO NOT EDIT.
package linxcore.fpga.zybo

object ZyboZ720Generated {
  final val BoardName = "zybo_z7_20"
  final val Part = "xc7z020clg400-1"
  final val SafeClockHz = 50000000L
  final val BalancedClockHz = 75000000L
  final val StretchClockHz = 100000000L
  final val AxiControlBase = BigInt("43c00000", 16)
  final val AxiControlSize = BigInt("00010000", 16)
  final val AxiDataWidth = 64
  final val LineBytes = 64
  final val MaxOutstanding = 1
  final val LinxMemoryBase = BigInt("00000000", 16)
  final val LinxMemorySize = BigInt("10000000", 16)
  final val UartData = BigInt("10000000", 16)
  final val UartStatusLinuxExit = BigInt("10000004", 16)
  final val TestFinisher = BigInt("10009000", 16)
  final val VirtioBase = BigInt("30001000", 16)
  final val SmokePc = BigInt("00010000", 16)
  final val SmokeSp = BigInt("0003ff00", 16)
  final val LinuxPc = BigInt("00010000", 16)
  final val LinuxSp = BigInt("0ffef000", 16)
  final val LinuxA0 = BigInt("00000000", 16)
  final val LinuxA1 = BigInt("0f000000", 16)
  final val LinuxInitramfs = BigInt("08000000", 16)
  final val KernelArtifactBase = BigInt("00010000", 16)
  final val KernelArtifactSize = BigInt("01000000", 16)
  final val InitramfsArtifactBase = BigInt("08000000", 16)
  final val InitramfsArtifactSize = BigInt("04000000", 16)
  final val DtbArtifactBase = BigInt("0f000000", 16)
  final val DtbArtifactSize = BigInt("00010000", 16)
  final val ResourceBudgetLut = 40000
  final val ResourceBudgetFf = 80000
  final val ResourceBudgetBram36 = 100
  final val ResourceBudgetDsp48 = 64
}
