package linxcore.execute

import chisel3._
import chisel3.util.{PopCount, log2Ceil}

import linxcore.common.{InterfaceParams, RenamedUop}

/** Two-entry registered issue-ingress skid.
  *
  * `drainPresent`/`drainPreview` are presentation-only: they may expose a
  * younger dual-eligible row even when that bank cannot consume it yet.
  * `drainCanConsume` is the downstream bank's consume credit, and only
  * `drainFire` is the accepted dequeue event that may mutate downstream state.
  */
class ScalarIssueIngressSkid2IO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val inValid = Input(Bool())
  val inReady = Output(Bool())
  val in = Input(new RenamedUop(p))
  val inBank = Input(UInt(1.W))
  val flushValid = Input(Bool())

  val drainCanConsume = Input(Vec(2, Bool()))
  val drainPresent = Output(Vec(2, Bool()))
  val drainPreview = Output(Vec(2, new RenamedUop(p)))
  val drainFire = Output(Vec(2, Bool()))

  val empty = Output(Bool())
  val full = Output(Bool())
  val count = Output(UInt(log2Ceil(3).W))
  val bankOccupancy = Output(Vec(2, UInt(log2Ceil(3).W)))
  val dualDrainEligible = Output(Bool())
  val dualDrainFire = Output(Bool())
  val olderOnlyFire = Output(Bool())
}

class ScalarIssueIngressSkid2(val p: InterfaceParams = InterfaceParams()) extends Module {
  val io = IO(new ScalarIssueIngressSkid2IO(p))

  private val depth = 2
  private val countWidth = log2Ceil(depth + 1)

  val entries = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new RenamedUop(p)))))
  val banks = RegInit(VecInit(Seq.fill(depth)(0.U(1.W))))
  val count = RegInit(0.U(countWidth.W))

  val active = !io.flushValid
  val enqueueFire = io.inValid && io.inReady
  val candidateCount = count + Mux(enqueueFire, 1.U(countWidth.W), 0.U(countWidth.W))
  val hasOlder = active && candidateCount =/= 0.U
  val hasYounger = active && candidateCount === 2.U
  val older = Mux(count === 0.U, io.in, entries(0))
  val younger = Mux(count === 0.U, 0.U.asTypeOf(new RenamedUop(p)), Mux(count === 1.U, io.in, entries(1)))
  val olderBank = Mux(count === 0.U, io.inBank, banks(0))
  val youngerBank = Mux(count === 1.U, io.inBank, banks(1))
  val olderSafe = ScalarPipeSafety.fixedScalarAlu(older)
  val youngerSafe = ScalarPipeSafety.fixedScalarAlu(younger)
  val dualEligible =
    hasYounger && olderSafe && youngerSafe && (olderBank =/= youngerBank)
  val dualFire =
    dualEligible && io.drainCanConsume(olderBank) && io.drainCanConsume(youngerBank)
  val olderOnlyFire =
    hasOlder && !dualFire && io.drainCanConsume(olderBank)

  for (bank <- 0 until 2) {
    val olderToBank = hasOlder && olderBank === bank.U
    val youngerToBank = hasYounger && youngerBank === bank.U
    io.drainPresent(bank) :=
      Mux(dualEligible, olderToBank || youngerToBank, olderToBank)
    io.drainPreview(bank) := Mux(
      dualEligible && youngerToBank,
      younger,
      Mux(olderToBank, older, 0.U.asTypeOf(new RenamedUop(p)))
    )
    io.drainFire(bank) := Mux(dualFire, olderToBank || youngerToBank, olderOnlyFire && olderToBank)
  }

  io.inReady := active && count < depth.U
  io.empty := count === 0.U
  io.full := count === depth.U
  io.count := count
  for (bank <- 0 until 2) {
    io.bankOccupancy(bank) := PopCount((0 until depth).map { idx =>
      count > idx.U && banks(idx) === bank.U
    })
  }
  io.dualDrainEligible := dualEligible
  io.dualDrainFire := dualFire
  io.olderOnlyFire := olderOnlyFire

  when(io.flushValid) {
    count := 0.U
    entries := VecInit(Seq.fill(depth)(0.U.asTypeOf(new RenamedUop(p))))
    banks := VecInit(Seq.fill(depth)(0.U(1.W)))
  }.otherwise {
    val nextEntries = Wire(Vec(depth, new RenamedUop(p)))
    val nextBanks = Wire(Vec(depth, UInt(1.W)))
    val nextCount = Wire(UInt(countWidth.W))

    nextEntries := entries
    nextBanks := banks
    nextCount := count

    when(count === 0.U) {
      when(enqueueFire) {
        when(olderOnlyFire) {
          nextEntries(0) := 0.U.asTypeOf(new RenamedUop(p))
          nextBanks(0) := 0.U
          nextCount := 0.U
        }.otherwise {
          nextEntries(0) := io.in
          nextBanks(0) := io.inBank
          nextCount := 1.U
        }
        nextEntries(1) := 0.U.asTypeOf(new RenamedUop(p))
        nextBanks(1) := 0.U
      }
    }.elsewhen(count === 1.U) {
      when(enqueueFire) {
        when(dualFire) {
          nextEntries(0) := 0.U.asTypeOf(new RenamedUop(p))
          nextEntries(1) := 0.U.asTypeOf(new RenamedUop(p))
          nextBanks(0) := 0.U
          nextBanks(1) := 0.U
          nextCount := 0.U
        }.elsewhen(olderOnlyFire) {
          nextEntries(0) := io.in
          nextEntries(1) := 0.U.asTypeOf(new RenamedUop(p))
          nextBanks(0) := io.inBank
          nextBanks(1) := 0.U
          nextCount := 1.U
        }.otherwise {
          nextEntries(0) := entries(0)
          nextEntries(1) := io.in
          nextBanks(0) := banks(0)
          nextBanks(1) := io.inBank
          nextCount := 2.U
        }
      }.elsewhen(olderOnlyFire) {
        nextEntries(0) := 0.U.asTypeOf(new RenamedUop(p))
        nextEntries(1) := 0.U.asTypeOf(new RenamedUop(p))
        nextBanks(0) := 0.U
        nextBanks(1) := 0.U
        nextCount := 0.U
      }
    }.otherwise {
      when(dualFire) {
        nextEntries(0) := 0.U.asTypeOf(new RenamedUop(p))
        nextEntries(1) := 0.U.asTypeOf(new RenamedUop(p))
        nextBanks(0) := 0.U
        nextBanks(1) := 0.U
        nextCount := 0.U
      }.elsewhen(olderOnlyFire) {
        nextEntries(0) := entries(1)
        nextEntries(1) := 0.U.asTypeOf(new RenamedUop(p))
        nextBanks(0) := banks(1)
        nextBanks(1) := 0.U
        nextCount := 1.U
      }
    }

    entries := nextEntries
    banks := nextBanks
    count := nextCount
  }
}
