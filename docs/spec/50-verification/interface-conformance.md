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
store-data paths, and identity-qualified completions and load results.

## Recovery and commit conformance {#VER-IFC-CONTROL-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=IFC-RECOVERY-001,IFC-COMMIT-001 -->

`TopInterfaceSpec` shall check separate prepare and apply endpoints, complete
cutoff identities, an OOO-owned continuous commit prefix, and stable retained
payloads while a consumer applies backpressure.

## DTU and memory conformance {#VER-IFC-EDGE-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=IFC-DTU-001,IFC-MEMORY-001 -->

`TopInterfaceSpec` shall check observational trace connectivity and
generation-qualified instruction/data memory traffic without a DTU-owned
architectural-control state.

## Generated interface projection {#VER-IFC-MANIFEST-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 depends-on=MEC-IFU-CTU-001,MEC-CTU-OOO-001,MEC-OOO-IEX-001,MEC-IEX-LSU-001,MEC-RECOVERY-001,MEC-COMMIT-001,MEC-DTU-001,MEC-MEMORY-001 -->

`InterfaceManifestSpec` and
`tools/chisel/render_top_interface_manifest.py --check` shall compare the
checked-in JSON and Markdown projections with the endpoint and leaf shapes
derived from the elaborated canonical Bundles. Generated projections are
evidence and shall not define fields independently of the Scala types.
