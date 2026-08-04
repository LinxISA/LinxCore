# Interface conformance

## Front-end packet conformance {#VER-IFC-FRONTEND-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=IFC-IFU-CTU-001,IFC-CTU-OOO-001 -->

`TopInterfaceSpec` shall elaborate W2, W4, W6, and W8 fetch and D1 packets,
check 64-bit instruction containers and continuous-prefix counts, and prove
that the IFU/CTU and CTU/OOO endpoints reuse the canonical payload types.

## Backend dispatch and LSU conformance {#VER-IFC-BACKEND-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=IFC-OOO-IEX-001,IFC-IEX-LSU-001 -->

`TopInterfaceSpec` shall check class-specific OOO/IEX channels, an independent
CMD channel, two default load-address paths, two store-address paths, two
store-data paths, and identity-qualified resolves and load results. It shall
distinguish initial `LoadIssueTxn`, LIQ `LoadReissueTxn`, translated
`LoadRepickTxn`, speculative-dependent `LoadCancelTxn`, and terminal
`LoadResultTxn`; no queue row or lane may substitute for `MemoryIdentity`.
The same tests shall prove one `RobResolveTxn` per ROB member and independent
resident system/multicycle and CMD paths under backpressure.

## Recovery and commit conformance {#VER-IFC-CONTROL-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=IFC-RECOVERY-001,IFC-COMMIT-001 -->

`TopInterfaceSpec` shall check separate prepare and apply endpoints, complete
cutoff identities, an OOO-owned continuous commit prefix, and stable retained
payloads while a consumer applies backpressure. System and CMD tests shall
reject a noflush authorization for a non-head, locally faulted, or
recovery-targeted member; prove Recovery Prepare priority and withdrawal of an
unused killed authorization; and prove that authorization consumption,
side-effect acceptance, and the matching no-value `RobResolveTxn` fire
atomically and exactly once.

## TOP external CMD conformance {#VER-IFC-TOP-EXT-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=IFC-TOP-EXT-001,IFC-TOP-EXT-002 -->

`TopInterfaceSpec` shall elaborate the `CmdIssueTxn` TOP endpoint in W2, W4,
W6, and W8, hold its complete identity and payload stable under backpressure,
and prove that an absent sink causes no CMD fire, resolve, or side effect.
`InterfaceManifestSpec` shall reject an always-ready sink or a second CMD
completion endpoint.

## DTU and memory conformance {#VER-IFC-EDGE-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=IFC-DTU-001,IFC-MEMORY-001 -->

`TopInterfaceSpec` shall check observational trace connectivity and
generation-qualified instruction/data memory traffic without a DTU-owned
architectural-control state.

The same tests MUST prove that external `MemorySize.Bytes64` elaborates as the
three-bit value six, every W4 LSU request/response lane is projected, and no
four-bit byte-count field can truncate a cache-line request.

## Generated interface projection {#VER-IFC-MANIFEST-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 depends-on=MEC-IFU-CTU-001,MEC-CTU-OOO-001,MEC-OOO-IEX-001,MEC-IEX-LSU-001,MEC-RECOVERY-001,MEC-COMMIT-001,MEC-DTU-001,MEC-MEMORY-001,MEC-TOP-EXT-001 -->

`InterfaceManifestSpec` and
`tools/chisel/render_top_interface_manifest.py --check` shall compare the
checked-in JSON and Markdown projections with the endpoint and leaf shapes
derived from the elaborated canonical Bundles, including the external
`CmdIssueTxn` endpoint. Generated projections are
evidence and shall not define fields independently of the Scala types.
