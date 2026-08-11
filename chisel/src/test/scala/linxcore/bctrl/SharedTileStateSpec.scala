package linxcore.bctrl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class SharedTileStateSpec extends AnyFunSuite with ChiselSim {
  private def clear(dut: SharedTileState): Unit = {
    dut.io.write.valid.poke(false.B)
    dut.io.write.bits.poke(0.U.asTypeOf(dut.io.write.bits))
    dut.io.readSharedId.poke(0.U)
    dut.io.readPeId.poke(0.U)
  }

  private def descriptor(
      lane: SharedTileDescriptor,
      dtype: Int = 4,
      rows: Int = 8,
      cols: Int = 8,
      validRows: Int = 8,
      validCols: Int = 8): Unit = {
    lane.dtype.poke(dtype.U)
    lane.rows.poke(rows.U)
    lane.cols.poke(cols.U)
    lane.validRows.poke(validRows.U)
    lane.validCols.poke(validCols.U)
  }

  private def write(
      dut: SharedTileState,
      sharedId: Int,
      peMask: Int,
      sizeCode: Int,
      readyMask: Int = 0xf,
      bytes: Int = 256,
      dtype: Int = 4): Unit = {
    dut.io.write.bits.sharedId.poke(sharedId.U)
    dut.io.write.bits.peMask.poke(peMask.U)
    dut.io.write.bits.sizeCode.poke(sizeCode.U)
    dut.io.write.bits.payloadReadyMask.poke(readyMask.U)
    for (pe <- 0 until 4) {
      descriptor(dut.io.write.bits.descriptors(pe), dtype = dtype)
      dut.io.write.bits.payloadBytes(pe).poke(bytes.U)
    }
    dut.io.write.valid.poke(true.B)
  }

  test("first write fixes allocation and commits every selected lane atomically") {
    simulate(new SharedTileState) { dut =>
      clear(dut)
      write(dut, sharedId = 0, peMask = 0xa, sizeCode = 2)
      dut.io.write.ready.expect(true.B)
      dut.io.status.expect(SharedTileWriteStatus.Applied)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.peMask.expect(0xa.U)
      dut.io.commit.bits.perPeCapacity.expect(256.U)
      dut.io.commit.bits.allocatedBytes.expect(512.U)
      dut.clock.step()

      dut.io.write.valid.poke(false.B)
      dut.io.readSharedId.poke(0.U)
      dut.io.version.allocationMask.expect(0xa.U)
      dut.io.version.initializedMask.expect(0xa.U)
      dut.io.version.perPeCapacity.expect(256.U)
      dut.io.version.allocatedBytes.expect(512.U)

      // PE_MASK bit 3 names PE0 and bit 1 names PE2.
      dut.io.readPeId.poke(0.U)
      dut.io.readInitialized.expect(true.B)
      dut.io.readPeId.poke(1.U)
      dut.io.readInitialized.expect(false.B)
      dut.io.readPeId.poke(2.U)
      dut.io.readInitialized.expect(true.B)
    }
  }

  test("payload backpressure prevents partial descriptor or initialization effects") {
    simulate(new SharedTileState) { dut =>
      clear(dut)
      write(dut, sharedId = 255, peMask = 0xc, sizeCode = 1, readyMask = 0x8, bytes = 128)
      dut.io.write.ready.expect(false.B)
      dut.io.status.expect(SharedTileWriteStatus.WaitingForPayload)
      dut.io.commit.valid.expect(false.B)
      dut.clock.step(2)

      dut.io.write.valid.poke(false.B)
      dut.io.readSharedId.poke(255.U)
      dut.io.version.allocationMask.expect(0.U)
      dut.io.version.initializedMask.expect(0.U)

      write(dut, sharedId = 255, peMask = 0xc, sizeCode = 1, readyMask = 0xc, bytes = 128)
      dut.io.commit.valid.expect(true.B)
      dut.clock.step()
      dut.io.write.valid.poke(false.B)
      dut.io.version.allocationMask.expect(0xc.U)
      dut.io.version.initializedMask.expect(0xc.U)
    }
  }

  test("zero mask is a strict no-op for every size code") {
    simulate(new SharedTileState) { dut =>
      clear(dut)
      for (size <- 0 to 7) {
        write(dut, sharedId = 0, peMask = 0, sizeCode = size, readyMask = 0, bytes = 0)
        dut.io.write.ready.expect(true.B)
        dut.io.status.expect(SharedTileWriteStatus.Noop)
        dut.io.commit.valid.expect(false.B)
        dut.clock.step()
      }
      dut.io.write.valid.poke(false.B)
      dut.io.version.allocationMask.expect(0.U)
      dut.io.version.initializedMask.expect(0.U)
    }
  }

  test("all size codes charge popcount times per-PE capacity") {
    for (size <- 1 to 7) {
      simulate(new SharedTileState) { dut =>
        clear(dut)
        val capacity = 128 << (size - 1)
        write(dut, sharedId = size, peMask = 0xe, sizeCode = size, bytes = capacity)
        dut.io.status.expect(SharedTileWriteStatus.Applied)
        dut.io.commit.bits.perPeCapacity.expect(capacity.U)
        dut.io.commit.bits.allocatedBytes.expect((capacity * 3).U)
      }
    }
  }

  test("canonical PE masks preserve architectural lane ordering") {
    for (mask <- Seq(0x1, 0x3, 0x7, 0xf)) {
      simulate(new SharedTileState) { dut =>
        clear(dut)
        write(dut, sharedId = mask, peMask = mask, sizeCode = 1, bytes = 128)
        dut.io.status.expect(SharedTileWriteStatus.Applied)
        dut.io.commit.bits.allocatedBytes.expect((128 * Integer.bitCount(mask)).U)
        dut.clock.step()

        dut.io.write.valid.poke(false.B)
        dut.io.readSharedId.poke(mask.U)
        for (pe <- 0 until 4) {
          dut.io.readPeId.poke(pe.U)
          dut.io.readInitialized.expect(((mask & (1 << (3 - pe))) != 0).B)
        }
      }
    }
  }

  test("subset updates preserve allocation while expansion and descriptor changes reject") {
    simulate(new SharedTileState) { dut =>
      clear(dut)
      write(dut, sharedId = 8, peMask = 0xa, sizeCode = 2)
      dut.clock.step()

      write(dut, sharedId = 8, peMask = 0x8, sizeCode = 2)
      dut.io.status.expect(SharedTileWriteStatus.Applied)
      dut.clock.step()

      write(dut, sharedId = 8, peMask = 0xb, sizeCode = 2)
      dut.io.status.expect(SharedTileWriteStatus.AllocationExpansion)
      dut.io.commit.valid.expect(false.B)
      dut.clock.step()

      write(dut, sharedId = 8, peMask = 0x8, sizeCode = 3, bytes = 512)
      dut.io.status.expect(SharedTileWriteStatus.DescriptorMismatch)
      dut.io.commit.valid.expect(false.B)
      dut.clock.step()

      write(dut, sharedId = 8, peMask = 0x8, sizeCode = 2)
      descriptor(dut.io.write.bits.descriptors(0), rows = 4, cols = 16, validRows = 4)
      dut.io.status.expect(SharedTileWriteStatus.DescriptorMismatch)
      dut.io.commit.valid.expect(false.B)
      dut.clock.step()

      dut.io.write.valid.poke(false.B)
      dut.io.readSharedId.poke(8.U)
      dut.io.version.allocationMask.expect(0xa.U)
      dut.io.version.initializedMask.expect(0xa.U)
      dut.io.version.perPeCapacity.expect(256.U)
    }
  }

  test("invalid descriptor and payload size reject before state changes") {
    simulate(new SharedTileState) { dut =>
      clear(dut)
      write(dut, sharedId = 1, peMask = 0xf, sizeCode = 0, bytes = 128)
      dut.io.status.expect(SharedTileWriteStatus.InvalidSize)
      dut.io.write.ready.expect(true.B)
      dut.clock.step()

      write(dut, sharedId = 1, peMask = 0xf, sizeCode = 1, bytes = 64)
      dut.io.status.expect(SharedTileWriteStatus.PayloadSizeMismatch)
      dut.clock.step()

      write(dut, sharedId = 1, peMask = 0xf, sizeCode = 1, bytes = 128)
      descriptor(dut.io.write.bits.descriptors(0), rows = 0)
      dut.io.status.expect(SharedTileWriteStatus.InvalidDescriptor)
      dut.clock.step()

      write(dut, sharedId = 1, peMask = 0xf, sizeCode = 1, bytes = 128)
      descriptor(dut.io.write.bits.descriptors(0), rows = 3)
      dut.io.status.expect(SharedTileWriteStatus.InvalidDescriptor)
      dut.clock.step()

      write(dut, sharedId = 1, peMask = 0xf, sizeCode = 1, bytes = 128)
      descriptor(dut.io.write.bits.descriptors(3), dtype = 5)
      dut.io.status.expect(SharedTileWriteStatus.DescriptorMismatch)
      dut.clock.step()

      dut.io.write.valid.poke(false.B)
      dut.io.readSharedId.poke(1.U)
      dut.io.version.allocationMask.expect(0.U)
      dut.io.version.initializedMask.expect(0.U)
    }
  }

  test("S0 and S255 are independent and reset clears undefined reads") {
    simulate(new SharedTileState) { dut =>
      clear(dut)
      write(dut, sharedId = 0, peMask = 0x1, sizeCode = 1, bytes = 128)
      dut.clock.step()
      write(dut, sharedId = 255, peMask = 0x8, sizeCode = 1, bytes = 128)
      dut.clock.step()
      dut.io.write.valid.poke(false.B)

      dut.io.readSharedId.poke(0.U)
      dut.io.readPeId.poke(3.U)
      dut.io.readInitialized.expect(true.B)
      dut.io.readPeId.poke(0.U)
      dut.io.readInitialized.expect(false.B)
      dut.io.readSharedId.poke(255.U)
      dut.io.readPeId.poke(0.U)
      dut.io.readInitialized.expect(true.B)

      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.readInitialized.expect(false.B)
      dut.io.version.allocationMask.expect(0.U)
    }
  }

  test("binding and mixed-operation legality stays fail closed") {
    simulate(new TileOperandLegality) { dut =>
      dut.io.bindingKind.poke(TileBindingKind.Bior)
      dut.io.space.poke(TileOperandSpace.ScalarAddress)
      dut.io.bindingLegal.expect(true.B)
      dut.io.space.poke(TileOperandSpace.Shared)
      dut.io.bindingLegal.expect(false.B)

      dut.io.bindingKind.poke(TileBindingKind.Biot)
      dut.io.space.poke(TileOperandSpace.Local)
      dut.io.bindingLegal.expect(true.B)
      dut.io.bindingKind.poke(TileBindingKind.Bios)
      dut.io.space.poke(TileOperandSpace.Shared)
      dut.io.bindingLegal.expect(true.B)

      dut.io.operation.poke(TileOperationKind.Tmov)
      dut.io.sharedUse.poke(TileSharedUse.Source)
      dut.io.sharedMask.poke(0xa.U)
      dut.io.localMask.poke(0xa.U)
      dut.io.operationLegal.expect(true.B)
      dut.io.localMask.poke(0x8.U)
      dut.io.operationLegal.expect(false.B)

      dut.io.operation.poke(TileOperationKind.Cube)
      dut.io.sharedUse.poke(TileSharedUse.Source)
      dut.io.sharedMask.poke(0xf.U)
      dut.io.operationLegal.expect(true.B)
      dut.io.sharedUse.poke(TileSharedUse.Destination)
      dut.io.operationLegal.expect(false.B)

      dut.io.operation.poke(TileOperationKind.Tgemv)
      dut.io.sharedUse.poke(TileSharedUse.Source)
      dut.io.operationLegal.expect(false.B)
      dut.io.sharedUse.poke(TileSharedUse.None)
      dut.io.sharedMask.poke(0.U)
      dut.io.operationLegal.expect(true.B)
    }
  }
}
