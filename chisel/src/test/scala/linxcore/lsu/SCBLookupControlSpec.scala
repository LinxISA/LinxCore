package linxcore.lsu

import circt.stage.ChiselStage
import org.scalatest.funsuite.AnyFunSuite

object SCBLookupControlReference {
  final case class Request(valid: Boolean, entryIndex: Int, lineAddr: BigInt, byteMask: BigInt, data: BigInt)
  final case class Result(
      lookupReady: Boolean,
      lookupFire: Boolean,
      lookupStall: Boolean,
      acceptedMask: BigInt,
      missMask: BigInt,
      freeMask: BigInt,
      dcacheUpdateValid: Boolean,
      broadcastUpgrade: Boolean,
      l2RequestValid: Boolean,
      l2Write: Boolean,
      l2Upgrade: Boolean,
      txnTid: Int)

  def step(
      req: Request,
      entries: Int,
      dcacheReady: Boolean,
      dcacheWriteHit: Boolean,
      dcacheTagHit: Boolean,
      l2RequestReady: Boolean): Result = {
    val needsL2 = !dcacheWriteHit
    val lookupReady = dcacheReady && (!needsL2 || l2RequestReady)
    val lookupFire = req.valid && lookupReady
    val selectedMask = if (lookupFire) BigInt(1) << req.entryIndex else BigInt(0)
    val hitPath = lookupFire && dcacheWriteHit
    val missPath = lookupFire && !dcacheWriteHit
    val hasStoreBytes = req.byteMask != 0

    require(req.entryIndex >= 0 && req.entryIndex < entries)
    Result(
      lookupReady = lookupReady,
      lookupFire = lookupFire,
      lookupStall = req.valid && !lookupReady,
      acceptedMask = selectedMask,
      missMask = if (missPath) selectedMask else BigInt(0),
      freeMask = if (hitPath) selectedMask else BigInt(0),
      dcacheUpdateValid = hitPath && hasStoreBytes,
      broadcastUpgrade = hitPath && hasStoreBytes,
      l2RequestValid = missPath,
      l2Write = missPath && !dcacheTagHit,
      l2Upgrade = missPath && dcacheTagHit,
      txnTid = (req.entryIndex << 2) | 2)
  }
}

class SCBLookupControlSpec extends AnyFunSuite {
  import SCBLookupControlReference._

  test("writable DCache hit emits byte update, upgrade broadcast, and free mask") {
    val result = step(
      Request(valid = true, entryIndex = 3, lineAddr = 0x1000, byteMask = BigInt("ff", 16), data = 0x11223344),
      entries = 8,
      dcacheReady = true,
      dcacheWriteHit = true,
      dcacheTagHit = true,
      l2RequestReady = false)

    assert(result.lookupFire)
    assert(result.acceptedMask == (BigInt(1) << 3))
    assert(result.freeMask == (BigInt(1) << 3))
    assert(result.missMask == BigInt(0))
    assert(result.dcacheUpdateValid)
    assert(result.broadcastUpgrade)
    assert(!result.l2RequestValid)
  }

  test("tag hit without write permission emits an upgrade ownership request") {
    val result = step(
      Request(valid = true, entryIndex = 5, lineAddr = 0x2000, byteMask = BigInt(1), data = 0xaa),
      entries = 8,
      dcacheReady = true,
      dcacheWriteHit = false,
      dcacheTagHit = true,
      l2RequestReady = true)

    assert(result.lookupFire)
    assert(result.missMask == (BigInt(1) << 5))
    assert(result.freeMask == BigInt(0))
    assert(result.l2RequestValid)
    assert(result.l2Upgrade)
    assert(!result.l2Write)
    assert(result.txnTid == ((5 << 2) | 2))
  }

  test("tag miss emits a write ownership request") {
    val result = step(
      Request(valid = true, entryIndex = 2, lineAddr = 0x3000, byteMask = BigInt(3), data = 0xbbcc),
      entries = 8,
      dcacheReady = true,
      dcacheWriteHit = false,
      dcacheTagHit = false,
      l2RequestReady = true)

    assert(result.lookupFire)
    assert(result.l2RequestValid)
    assert(result.l2Write)
    assert(!result.l2Upgrade)
    assert(result.missMask == (BigInt(1) << 2))
  }

  test("miss path stalls when L2 ownership request queue is not ready") {
    val result = step(
      Request(valid = true, entryIndex = 1, lineAddr = 0x4000, byteMask = BigInt(1), data = 0xdd),
      entries = 8,
      dcacheReady = true,
      dcacheWriteHit = false,
      dcacheTagHit = false,
      l2RequestReady = false)

    assert(!result.lookupReady)
    assert(!result.lookupFire)
    assert(result.lookupStall)
    assert(result.acceptedMask == BigInt(0))
    assert(!result.l2RequestValid)
  }

  test("all lookup work stalls when DCache is unavailable") {
    val result = step(
      Request(valid = true, entryIndex = 0, lineAddr = 0x5000, byteMask = BigInt(1), data = 0xee),
      entries = 8,
      dcacheReady = false,
      dcacheWriteHit = true,
      dcacheTagHit = true,
      l2RequestReady = true)

    assert(!result.lookupReady)
    assert(!result.lookupFire)
    assert(result.lookupStall)
    assert(result.freeMask == BigInt(0))
  }

  test("empty-byte hit still frees the SCB row but emits no upgrade broadcast") {
    val result = step(
      Request(valid = true, entryIndex = 4, lineAddr = 0x6000, byteMask = BigInt(0), data = 0),
      entries = 8,
      dcacheReady = true,
      dcacheWriteHit = true,
      dcacheTagHit = true,
      l2RequestReady = false)

    assert(result.lookupFire)
    assert(result.freeMask == (BigInt(1) << 4))
    assert(!result.dcacheUpdateValid)
    assert(!result.broadcastUpgrade)
  }

  test("Chisel SCBLookupControl elaborates with DCache update and L2 request descriptors") {
    val sv = ChiselStage.emitSystemVerilog(new SCBLookupControl(scbEntries = 8))

    assert(sv.contains("module SCBLookupControl"))
    assert(sv.contains("io_dcacheUpdate_byteMask"))
    assert(sv.contains("io_dcacheUpdate_broadcastUpgrade"))
    assert(sv.contains("io_l2Request_txnTid"))
    assert(sv.contains("io_l2Request_upgrade"))
  }
}
