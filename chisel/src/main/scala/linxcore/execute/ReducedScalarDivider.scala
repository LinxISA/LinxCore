package linxcore.execute

import chisel3._
import chisel3.util.{Cat, Fill}

class ReducedScalarDividerIO extends Bundle {
  val reqValid = Input(Bool())
  val reqReady = Output(Bool())
  val lhs = Input(UInt(64.W))
  val rhs = Input(UInt(64.W))
  val signed = Input(Bool())
  val word = Input(Bool())
  val remainder = Input(Bool())

  val respValid = Output(Bool())
  val respReady = Input(Bool())
  val result = Output(UInt(64.W))
  val flush = Input(Bool())
}

/** Area-oriented radix-2 divider for the reduced scalar execution path.
  *
  * One quotient bit is produced per cycle. Word operations run for 32 cycles
  * and full-width operations run for 64 cycles. The request records all sign
  * information up front, so the iterative datapath itself is unsigned and has
  * only one 65-bit compare/subtract path.
  */
class ReducedScalarDivider extends Module {
  val io = IO(new ReducedScalarDividerIO)

  val busy = RegInit(false.B)
  val resultValid = RegInit(false.B)
  val divisor = Reg(UInt(64.W))
  val quotient = Reg(UInt(64.W))
  val partialRemainder = Reg(UInt(65.W))
  val iterationsLeft = Reg(UInt(7.W))
  val resultIsWord = Reg(Bool())
  val resultIsRemainder = Reg(Bool())
  val quotientNegative = Reg(Bool())
  val remainderNegative = Reg(Bool())
  val result = Reg(UInt(64.W))

  io.reqReady := !io.flush && !busy && !resultValid
  io.respValid := !io.flush && resultValid
  io.result := result

  val requestAccepted = io.reqValid && io.reqReady
  val lhsWord = io.lhs(31, 0)
  val rhsWord = io.rhs(31, 0)
  val lhsNegative = io.signed && Mux(io.word, lhsWord(31), io.lhs(63))
  val rhsNegative = io.signed && Mux(io.word, rhsWord(31), io.rhs(63))
  val lhsDomain = Mux(io.word, lhsWord.pad(64), io.lhs)
  val rhsDomain = Mux(io.word, rhsWord.pad(64), io.rhs)
  val lhsWordMagnitude = Mux(lhsNegative, (~lhsWord + 1.U)(31, 0), lhsWord)
  val rhsWordMagnitude = Mux(rhsNegative, (~rhsWord + 1.U)(31, 0), rhsWord)
  val lhsMagnitude = Mux(io.word, lhsWordMagnitude.pad(64), Mux(lhsNegative, (~io.lhs + 1.U)(63, 0), io.lhs))
  val rhsMagnitude = Mux(io.word, rhsWordMagnitude.pad(64), Mux(rhsNegative, (~io.rhs + 1.U)(63, 0), io.rhs))
  val divisorIsZero = rhsDomain === 0.U

  val shiftedRemainder64 = Cat(partialRemainder(63, 0), quotient(63))
  val shiftedRemainder32 = Cat(partialRemainder(63, 0), quotient(31))
  val shiftedRemainder = Mux(resultIsWord, shiftedRemainder32, shiftedRemainder64)
  val subtract = shiftedRemainder >= divisor.pad(65)
  val nextRemainder = Mux(subtract, shiftedRemainder - divisor.pad(65), shiftedRemainder)
  val shiftedQuotient64 = Cat(quotient(62, 0), 0.U(1.W))
  val shiftedQuotient32 = Cat(0.U(32.W), quotient(30, 0), 0.U(1.W))
  val shiftedQuotient = Mux(resultIsWord, shiftedQuotient32, shiftedQuotient64)
  val nextQuotient = shiftedQuotient | subtract.asUInt
  val magnitudeResult = Mux(resultIsRemainder, nextRemainder(63, 0), nextQuotient)
  val resultNegative = Mux(resultIsRemainder, remainderNegative, quotientNegative)
  val signedResult = Mux(resultNegative, (~magnitudeResult + 1.U)(63, 0), magnitudeResult)
  val formattedResult = Mux(resultIsWord, Cat(Fill(32, signedResult(31)), signedResult(31, 0)), signedResult)

  when(io.flush) {
    busy := false.B
    resultValid := false.B
  }.otherwise {
    when(resultValid && io.respReady) {
      resultValid := false.B
    }

    when(requestAccepted) {
      resultIsWord := io.word
      resultIsRemainder := io.remainder
      quotientNegative := lhsNegative ^ rhsNegative
      remainderNegative := lhsNegative

      when(divisorIsZero) {
        val zeroRemainder = Mux(io.word, Cat(Fill(32, lhsWord(31)), lhsWord), io.lhs)
        result := Mux(io.remainder, zeroRemainder, 0.U)
        resultValid := true.B
        busy := false.B
      }.otherwise {
        divisor := rhsMagnitude
        quotient := lhsMagnitude
        partialRemainder := 0.U
        iterationsLeft := Mux(io.word, 32.U, 64.U)
        busy := true.B
      }
    }.elsewhen(busy) {
      quotient := nextQuotient
      partialRemainder := nextRemainder
      iterationsLeft := iterationsLeft - 1.U
      when(iterationsLeft === 1.U) {
        result := formattedResult
        resultValid := true.B
        busy := false.B
      }
    }
  }
}
