# superscalarNPU interface projection

## Typed interface organization reference {#REF-SNPU-001}
<!-- ndf: kind=info level=may layer=L0 status=stable since=0.1 -->
<!-- ndf: origin-kind=git repository=https://github.com/hengliao1972/superscalarNPU.git revision=3ae82dbc2bd68346255bfb6d8175495490ae2d3a checkout=/Users/zhoubot/Documents/superscalarNPU origin-status=interpretation -->

The pinned checkout informs interface organization: group payloads by
transaction, keep box IO declarations near the top boundary, and make
connectivity reviewable independently of internal implementation.

Its fixed lane shapes, NPU-specific payloads, and any direct recovery shortcut
are not LinxCore contracts. LinxCore interfaces derive widths from the central
profile and route recovery through the OOO-owned plan.
