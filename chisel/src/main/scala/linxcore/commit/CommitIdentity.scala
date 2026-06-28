package linxcore.commit

import chisel3._

class CommitIdentity extends Bundle {
  val bid = UInt(32.W)
  val gid = UInt(32.W)
  val rid = UInt(32.W)
}
