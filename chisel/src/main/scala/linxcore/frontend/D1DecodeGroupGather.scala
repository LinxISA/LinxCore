package linxcore.frontend

import chisel3._
import chisel3.util.Decoupled
import linxcore.common.InterfaceParams

class D1DecodeGroupGatherIO(val p: InterfaceParams = InterfaceParams()) extends Bundle {
  val in = Flipped(Decoupled(new D1InstructionGroup(p)))
  val out = Decoupled(new D1InstructionGroup(p))
  val flush = Input(new IfuInnerFlush(p))
}

class D1DecodeGroupGather(val p: InterfaceParams = InterfaceParams()) extends Module {
  require(p.decodeWidth == 4, "D1DecodeGroupGather preserves one four-wide D1 group")
  require(p.insnWidth == 64, "D1DecodeGroupGather carries fixed-width 64-bit instructions")

  val io = IO(new D1DecodeGroupGatherIO(p))

  val occupied = RegInit(false.B)
  val group = RegInit(0.U.asTypeOf(new D1InstructionGroup(p)))

  val storedIdentity = group.entries(0).identity
  val killStored =
    occupied &&
      IfuFlushContract.kills(storedIdentity, storedIdentity.fetchPacketUid, io.flush)

  io.out.valid := occupied && !killStored
  io.out.bits := group

  val incomingIdentity = io.in.bits.entries(0).identity
  val killIncoming =
    IfuFlushContract.kills(incomingIdentity, incomingIdentity.fetchPacketUid, io.flush)
  io.in.ready := !killIncoming && (!occupied || io.out.ready || killStored)

  val outFire = io.out.valid && io.out.ready
  val inFire = io.in.valid && io.in.ready

  when(killStored || outFire) {
    occupied := false.B
  }
  when(inFire) {
    group := io.in.bits
    occupied := true.B
  }
}
