# CTU to OOO interface

## Canonical D1 prefix transfer {#IFC-CTU-OOO-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=D-PREFIX-001,D-IDENTITY-001 -->

CTU shall present OOO with a continuous program-order prefix of canonical
front-end operations. An entry shall be either one encoded 64-bit instruction
container or one non-recursive template uop and shall retain its architectural
parent identity.

## D1 packet Bundle {#MEC-CTU-OOO-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFC-CTU-OOO-001 -->

`D1Packet` uses `count + Vec(decodeWidth, FrontEndOp)`.
`FrontEndOp.kind` distinguishes encoded instructions from template uops;
`CTUIO.toOoo` and `OOOIO.fromCtu` share the same payload definition.
