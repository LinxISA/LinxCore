# IFU to CTU interface

## Fixed-container fetch transfer {#IFC-IFU-CTU-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=ARC-TOP-030,D-PREFIX-001,D-IDENTITY-001 -->

IFU shall transfer each accepted instruction to CTU in a 64-bit container with
an explicit 2, 4, 6, or 8 byte length. A packet shall contain one continuous
program-order prefix, and every entry shall carry its complete instruction and
prediction identity.

## Fetched packet Bundle {#MEC-IFU-CTU-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFC-IFU-CTU-001 -->

`FetchedPacket` uses `count + Vec(fetchWidth, FetchedInstruction)`.
`IFUIO.toCtu` and `CTUIO.fromIfu` use that one payload type with opposite
directions; neither endpoint defines a sender- or receiver-specific copy.
The payload carries architectural fetch and prediction identity, not a
provisional ROB or BROB allocation.
