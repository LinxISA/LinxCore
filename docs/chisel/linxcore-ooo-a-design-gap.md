# LinxCore OOO/LSU Compared With `Documents/a.txt`

## Scope

`Documents/a.txt` is used as a microarchitecture mechanism reference, not as an
ISA contract. ARM-specific exception levels, condition flags, exclusive
monitors, barrier encodings and acquire/release policy are intentionally not
imported into Linx.

## Main differences

| Area | `a.txt` design | Current LinxCore Chisel | Production gap |
|---|---|---|---|
| Identity | Fixed TID/RID/UID/LID/SID fields; SID preallocates STQ | STID-qualified native BID plus BROB/RID/resident generations, full LSID/store ID and generation-qualified physical lease | Linx is stronger against wrap/reuse; preserve this contract during physical replication |
| Decode/OOO width | Concrete wide backend topology | D1 raw width, expanded-uop width, rename, dispatch, retire, ROB banks and IQ ports are independent parameters; 2/4/6 decode elaborates | Default-width timing and sustained pressure proof remain |
| Store execution | Two STA and multiple integer/FP STD paths with I2 pre-arbitration/cancel | Two retained STA and two retained integer STD lanes, exact reservation before execute | FSU/vector STD and priority/cancel owner are absent; sustained dual STA is not closed |
| STQ organization | 40 entries shared by two threads; PA, byte-mask, data, attribute and status arrays; local vSTQ tags replicated per load pipe | Parameterized canonical STQ status/identity plus a two-bank, two-write `STQDataBank` | PA/attribute/status physical partition, replicated vSTQ tags and production-depth floorplan proof remain |
| STD timing | STQ tag at E2, data write at E3 | Exact STD accept, mask phase, data phase, then lease completion | Stage naming must be aligned with final LSU E1-E3 floorplan; ECC/parity remains |
| Store-to-load forward | Per-load-pipe E1 full-VA CAM, E3 STQ data read, multi-hit byte composition | Byte-granular youngest-older `LoadStoreForwarding` and `LoadForwardPipeline` exist; physical data snapshot now exists | The O3 canonical STQ snapshot is not yet wired into the live load E1/E3 path; replicated tags and multi-hit timing are open |
| Unknown older store | Load blocks on unresolved older STA/STD and later repicks | LIQ wait-store, MDB and replay helpers exist | One closed admission/block/wakeup path from canonical STQ is still required |
| Late STA violation | Older STA CAMs younger resolved LHQ and nukes/replays violations | LHQ, ResolveQ and typed recovery pieces exist | Late-STA overlap detector, exact youngest violating load selection and recovery publication are not composed |
| Commit/SCB | Two STQ retire entries per cycle, up to 32 bytes total, then SCA/SCD coalescing | Exact logical pair CommitQ, retained split drain, SCB coalescing and terminal last-fragment free | Wider payload, production cache/device transport and timing proof remain |
| Translation/protection | Separate load/store TLBs, replay, memory attributes and device paths | Scalar L1D, miss/refill, PMA/classification sidecars and queue owners exist in pieces | DTLB/PMP/PMA faults and cacheable/device/coherent transports are not one production composition |
| Recovery | Conventional thread/age recovery around backend structures | One prepared exact recovery plan spans ROB, rename, IQ/IEX and canonical STQ; stale generations fail closed | This is a Linx advantage and must not be weakened to narrow SID/BID magnitude comparisons |

## Upgrade order

1. **Physical STQ data owner** — complete in this packet: two banked STD ports,
   mask-before-data timing, exact completion and joined commit/forward view.
2. **Forwarding snapshot** — replicate only minimal stable STQ tag metadata per
   load pipe; retain data in one bank; implement E1 CAM request and E3 data
   return with byte-granular youngest-older merge.
3. **Blocking and violation recovery** — connect unknown older STA/STD blocking,
   STD/STA wakeup, LHQ late-STA overlap detection and typed replay/nuke.
4. **Translation and attributes** — compose store/load DTLB, PMP/PMA,
   cacheable/non-cacheable/device classification and precise faults.
5. **Memory-system closure** — connect L1D/SCB/miss/refill/coherence owners,
   vector/FSU store data, ECC/parity and physical timing.
6. **Promotion** — run default and unequal-capacity UT/IT, generated RTL,
   synthesis/timing, then common-ELF Dhrystone and CoreMark comparison.

## Spec decisions still needed

- Production STQ depth and STID partition policy: fixed partition, dynamic
  sharing, or quota plus borrow.
- Number of load pipes and vSTQ tag replicas; whether the E3 data read is
  register-replicated or macro-banked.
- Maximum scalar/vector store payload per STQ beat and FSU-versus-integer STD
  arbitration.
- Forwarding behavior for data from multiple STQ rows and a partially unknown
  nearest older store.
- Precise recovery owner and priority when late STA violation, load miss,
  branch correction and exception coincide.
- DTLB/PMP/PMA latency, replay queue ownership and device-ordering contract.
