package linxcore.execute

import chisel3._
import chisel3.util.{Cat, PriorityEncoder, Reverse}

import linxcore.frontend.FrontendOpcodeDecodeTable

class ReducedScalarFpExecuteIO extends Bundle {
  val inValid = Input(Bool())
  val inReady = Output(Bool())
  val opcode = Input(UInt(10.W))
  val insnRaw = Input(UInt(32.W))
  val srcL = Input(UInt(64.W))
  val srcR = Input(UInt(64.W))
  val flush = Input(Bool())

  val outReady = Input(Bool())
  val outValid = Output(Bool())
  val outData = Output(UInt(64.W))
  val unsupported = Output(Bool())
}

class ReducedScalarFpExecute extends Module {
  val io = IO(new ReducedScalarFpExecuteIO)

  private def opcode(value: Int): UInt =
    value.U(io.opcode.getWidth.W)

  private val dstType = io.insnRaw(31, 27)
  private val srcType = io.insnRaw(26, 25)
  private val isFeq = io.opcode === opcode(FrontendOpcodeDecodeTable.OP_FEQ)
  private val isFcvtFsToFd =
    io.opcode === opcode(FrontendOpcodeDecodeTable.OP_FCVT) && srcType === 1.U && dstType === 0.U
  private val isUcvtfUdToFs =
    io.opcode === opcode(FrontendOpcodeDecodeTable.OP_UCVTF) && srcType === 0.U && dstType === 1.U
  private val isFeqFd = isFeq && srcType === 0.U
  private val isFeqFs = isFeq && srcType === 1.U
  private val supported = isFeqFd || isFeqFs || isFcvtFsToFd || isUcvtfUdToFs

  private def fp32IsNaN(x: UInt): Bool =
    x(30, 23) === 0xff.U && x(22, 0) =/= 0.U

  private def fp64IsNaN(x: UInt): Bool =
    x(62, 52) === 0x7ff.U && x(51, 0) =/= 0.U

  private def fp32IsZero(x: UInt): Bool =
    x(30, 0) === 0.U

  private def fp64IsZero(x: UInt): Bool =
    x(62, 0) === 0.U

  private def fp32Eq(a: UInt, b: UInt): Bool =
    !fp32IsNaN(a) && !fp32IsNaN(b) && (a === b || (fp32IsZero(a) && fp32IsZero(b)))

  private def fp64Eq(a: UInt, b: UInt): Bool =
    !fp64IsNaN(a) && !fp64IsNaN(b) && (a === b || (fp64IsZero(a) && fp64IsZero(b)))

  private def fp32ToFp64(a: UInt): UInt = {
    val sign = a(31)
    val exp = a(30, 23)
    val frac = a(22, 0)
    val out = Wire(UInt(64.W))
    out := 0.U

    when(exp === 0xff.U) {
      val isNaN = frac =/= 0.U
      val frac64 = Cat(isNaN, frac, 0.U(28.W))
      out := Cat(sign, 0x7ff.U(11.W), frac64)
    }.elsewhen(exp === 0.U) {
      when(frac === 0.U) {
        out := Cat(sign, 0.U(63.W))
      }.otherwise {
        val leadingZeros = PriorityEncoder(Reverse(frac))
        val msbIndex = 22.U(5.W) - leadingZeros
        val normalizedFrac = (frac << (23.U - msbIndex))(22, 0)
        val exp64 = 1023.U(11.W) - 149.U(11.W) + msbIndex
        out := Cat(sign, exp64, normalizedFrac, 0.U(29.W))
      }
    }.otherwise {
      val exp64 = exp.zext.asSInt + (1023 - 127).S
      out := Cat(sign, exp64.asUInt(10, 0), frac, 0.U(29.W))
    }
    out
  }

  private def uint64ToFp32(a: UInt): UInt = {
    val out = Wire(UInt(32.W))
    out := 0.U
    when(a =/= 0.U) {
      val leadingZeros = PriorityEncoder(Reverse(a))
      val msbIndex = 63.U(6.W) - leadingZeros
      val unrounded = Wire(UInt(24.W))
      unrounded := 0.U
      val shift = Mux(msbIndex > 23.U, msbIndex - 23.U, 0.U)
      when(msbIndex <= 23.U) {
        unrounded := (a << (23.U - msbIndex))(23, 0)
      }.otherwise {
        unrounded := (a >> shift)(23, 0)
      }

      val guardShift = shift - 1.U
      val guard = shift =/= 0.U && ((a >> guardShift)(0) === 1.U)
      val stickyMask = (1.U(64.W) << guardShift) - 1.U
      val sticky = shift > 1.U && ((a & stickyMask) =/= 0.U)
      val roundUp = guard && (sticky || unrounded(0))
      val rounded = Cat(0.U(1.W), unrounded) + roundUp
      val carry = rounded(24)
      val exp = 127.U(8.W) + msbIndex + carry
      val frac = Mux(carry, rounded(23, 1), rounded(22, 0))
      out := Cat(0.U(1.W), exp, frac)
    }
    out
  }

  private val feqResult = Mux(isFeqFs, fp32Eq(io.srcL(31, 0), io.srcR(31, 0)), fp64Eq(io.srcL, io.srcR))
  private val result = Wire(UInt(64.W))
  result := 0.U
  when(isFeqFd || isFeqFs) {
    result := Mux(feqResult, 1.U(64.W), 0.U(64.W))
  }.elsewhen(isFcvtFsToFd) {
    result := fp32ToFp64(io.srcL(31, 0))
  }.elsewhen(isUcvtfUdToFs) {
    result := uint64ToFp32(io.srcL).pad(64)
  }

  io.inReady := io.outReady
  io.outValid := io.inValid && !io.flush
  io.outData := result
  io.unsupported := io.inValid && !supported
}

object ReducedScalarFpExecute {
  val SrcTypeFd = 0
  val SrcTypeFs = 1
  val DstTypeFd = 0
  val DstTypeFs = 1

  private val Mask64 = (BigInt(1) << 64) - 1
  private val Mask32 = (BigInt(1) << 32) - 1

  def scalarFpInsn(dstType: Int, srcType: Int): BigInt =
    (BigInt(dstType & 0x1f) << 27) | (BigInt(srcType & 0x3) << 25)

  def referenceResult(opcode: Int, insnRaw: BigInt, srcL: BigInt, srcR: BigInt): Option[BigInt] = {
    val srcType = ((insnRaw >> 25) & 0x3).toInt
    val dstType = ((insnRaw >> 27) & 0x1f).toInt
    opcode match {
      case FrontendOpcodeDecodeTable.OP_FEQ if srcType == SrcTypeFd =>
        Some(if (fp64Eq(srcL, srcR)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_FEQ if srcType == SrcTypeFs =>
        Some(if (fp32Eq(srcL, srcR)) 1 else 0)
      case FrontendOpcodeDecodeTable.OP_FCVT if srcType == SrcTypeFs && dstType == DstTypeFd =>
        Some(fp32ToFp64(srcL))
      case FrontendOpcodeDecodeTable.OP_UCVTF if srcType == SrcTypeFd && dstType == DstTypeFs =>
        Some(uint64ToFp32(srcL))
      case _ => None
    }
  }

  private def fp32Eq(a: BigInt, b: BigInt): Boolean = {
    val aa = a & Mask32
    val bb = b & Mask32
    !fp32IsNaN(aa) && !fp32IsNaN(bb) && (aa == bb || (fp32IsZero(aa) && fp32IsZero(bb)))
  }

  private def fp64Eq(a: BigInt, b: BigInt): Boolean = {
    val aa = a & Mask64
    val bb = b & Mask64
    !fp64IsNaN(aa) && !fp64IsNaN(bb) && (aa == bb || (fp64IsZero(aa) && fp64IsZero(bb)))
  }

  private def fp32IsNaN(a: BigInt): Boolean =
    ((a >> 23) & 0xff) == 0xff && (a & 0x7fffff) != 0

  private def fp64IsNaN(a: BigInt): Boolean =
    ((a >> 52) & 0x7ff) == 0x7ff && (a & ((BigInt(1) << 52) - 1)) != 0

  private def fp32IsZero(a: BigInt): Boolean =
    (a & 0x7fffffffL) == 0

  private def fp64IsZero(a: BigInt): Boolean =
    (a & BigInt("7fffffffffffffff", 16)) == 0

  private def fp32ToFp64(a: BigInt): BigInt = {
    val bits = a & Mask32
    val sign = (bits >> 31) & 1
    val exp = (bits >> 23) & 0xff
    val frac = bits & 0x7fffff
    if (exp == 0xff) {
      val frac64 = if (frac == 0) BigInt(0) else (BigInt(1) << 51) | (frac << 28)
      (sign << 63) | (BigInt(0x7ff) << 52) | frac64
    } else if (exp == 0) {
      if (frac == 0) {
        sign << 63
      } else {
        val msbIndex = frac.bitLength - 1
        val exp64 = 1023 - 149 + msbIndex
        val normalizedFrac = (frac << (23 - msbIndex)) & 0x7fffff
        (sign << 63) | (BigInt(exp64) << 52) | (normalizedFrac << 29)
      }
    } else {
      (sign << 63) | ((exp + (1023 - 127)) << 52) | (frac << 29)
    }
  }

  private def uint64ToFp32(a: BigInt): BigInt = {
    val value = a & Mask64
    if (value == 0) {
      0
    } else {
      val msbIndex = value.bitLength - 1
      val (unrounded, roundUp) =
        if (msbIndex <= 23) {
          ((value << (23 - msbIndex)) & 0xffffff, false)
        } else {
          val shift = msbIndex - 23
          val kept = (value >> shift) & 0xffffff
          val guard = ((value >> (shift - 1)) & 1) != 0
          val sticky = (value & ((BigInt(1) << (shift - 1)) - 1)) != 0
          (kept, guard && (sticky || (kept & 1) != 0))
        }
      val rounded = unrounded + (if (roundUp) 1 else 0)
      val carry = (rounded >> 24) != 0
      val exp = 127 + msbIndex + (if (carry) 1 else 0)
      val frac = if (carry) (rounded >> 1) & 0x7fffff else rounded & 0x7fffff
      (BigInt(exp) << 23) | frac
    }
  }
}
