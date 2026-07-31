# CTU conformance

## Ordinary and template stream conformance {#VER-CTU-001}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=CTU-001,CTU-002,CTU-003 -->

`CTUSpec` shall check width-wide ordinary transfer, exact parent and
prediction preservation, FENTRY and FEXIT row recipes, expansion across more
than one D1 packet, cross-parent ordering, output stability under
backpressure, and W2/W4/W6/W8 packet shapes. `InstructionBufferSpec` shall
check retained FIFO order, pre-cycle-full backpressure, simultaneous
independent enqueue/dequeue, and stable output.

## Recovery conformance {#VER-CTU-002}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=CTU-004 -->

`CTUSpec` shall check that prepare echoes the exact transaction without
mutation and that apply removes only the target STID. `InstructionBufferSpec`
shall check that recovery suppresses transfer on the apply cycle, removes all
matching slots, and preserves unrelated operations in their original order.

## CTU ownership conformance {#VER-CTU-003}
<!-- ndf: kind=verif level=must layer=L3 status=stable since=0.1 verifies=CTU-005 -->

`CTUSpec` shall inspect the elaborated CTU hierarchy and require that it
contains only the CTU decode, child-description, retained-buffer, and trace
owners. The hierarchy shall contain no ROB, BROB, rename, issue-queue, or LSU
state owner.
