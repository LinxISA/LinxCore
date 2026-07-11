# LSU Pipelines and Datapaths

This document covers load, store, L2C, and MMU pipelines and datapaths, including load result timing, store retirement, fills, snoops, and request paths.

> This Markdown document is the maintained specification. Sensitive metadata and internal reference labels have been removed while preserving architecture semantics.

### 6.1 Load Pipelines and Datapaths

This section specifies the pipelines for each phase of load execution Refer to Error Reference source not found for more details on load execution behavior The following diagram shows the load execution pipelines.

#### Figure 6 1 LSU Load Pipelines

Every load not flushed must eventually flow through the result pipe to select and format its result data and return it to IEXFSU At this point the load is considered resolved.
If the load encounters a resource hazard or data not ready it must be put to sleep in LIQ When the data becomes available the load is woken up in LIQ and repicked to execute The arbiters for DC TAG DC DATA and result pipe give highest priority to LIQ loads.

#### 6.1.1 Load Flow Control

Before describing the execution steps of the general load flow it is useful to give an overview of the flow control mechanisms for load instructions and load uops advancing through LSU data structures The following table summarizes load flow control for each combination of source structure and destination structure As can be seen it is guaranteed that an issued load can be handled and not bakcpressured by LSU.
| Source | Destination | Load Flow Control |
| --- | --- | --- |
| IEX LS issue queues | LIQ | Every Issued uOp is guaranteed to have vacant entry in LIQ |

The LIQ entries are managed LD Matrix ID flow control.
| IEX LS issue queues | LHQ | LHQ entries are managed by LID mod3l LHQ sliding window is matched to every load in issue pipe If uOp is not in the LHQ sliding window it is Cancelled and waits in LIQ for Repick |
| --- | --- | --- |

- The LHQ keeps track of the oldest load or store order for each thread that can still trigger a nuke flush.

All uOps once pgen valid and in LHQ sliding window write to LHQ Even if not resolved.

#### 6.1.2 General Load Flow

This section describes the execution steps of the general load flow Specific flows based on load address alignment and memory type are covered in later sections The diagram below shows the flowchart of execution steps.
The load starts from one of the 2 sources on the left IEX Issue LIQ Repick The load arbitrates for access to the Load Issue Pipe DC TAG Pipe DC DATA Pipe Result Pipe and REQ Pipe based on the resources it requires.
If the load obtains its result data it returns this result to IEXFSU and resolves If the load cannot return its result it is put to sleep in LIQ If the load was in the Result Pipe and did not return its result it sends cancel to IEXFSU When the condition that put the load to sleep is resolved the load is woken up in LIQ and ready to repick.

- Figure 6 2 General Load Flow.

#### 6.1.3 Load IEX Issue Pipeline

Load instructions are issued to LSU by the IEX LS issue queues At I2 stage an issued load may collide with a repackedreissued load from LIQ If there is no arbitration loss the issued load may encounter a resource hazard or data not ready condition that requires the load to be canceled at E3 stage In the following pipeline diagram the issued load has no arbitration loss at I2 stage and no cancel condition at E3 stage so it flows through the result pipe and returns data to IEXFSU.
When Load is pgen valid it writes to LHQ at E4.
| P1 | I1 | I2 | E1 | E2 | E3 | E4 | E5 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| IEX LS ISSQ picks load | IEX issues load to LSU | No repick collision | TLB VA to PA translation | Obtain PA attributes from TLB | write load to LHQ |  |  |

DC TAG Readout
TAG Match determine DC hitmiss hit way.
| Arbiter for DC DATA | DC DATA Readout | If DC hit send result data to result pipe | Bypass Result to OEX |
| --- | --- | --- | --- |

WB DATA
Send resolve to ROB.

##### 6.1.3.1 WB Aligned Load DC Hit

The table below shows a simplified view of the WB aligned load DC hit flow In the best case the load completes in a single pass with 4-cycle loaduse latency.
| Pass | Pipe | Stage | WB Aligned Load DC Hit |
| --- | --- | --- | --- |
| 1 | Issue Pipe | E1 | Load TLB translation |
| DC TAG Pipe | E1 | Load DC TAG hit + Start PA Match |  |
| DC DATA Pipe | E2 | Load DC DATA read |  |
| Result Pipe | E3E4 | Load select and format result return to IEXFSU |  |
| Result Pipe | E3E4 | Load nuke younger matching stale loads of same thread in LHQ |  |
| Result Pipe | E4 | Load allocated to LHQ |  |
| Result Pipe | E5 | Load send resolve to ROB |  |
| Async Event | Load becomes older than NextcommitRID deallocated from LHQ |  |  |

#### 6.1.4 LHQ Sliding Window

In the following pipeline diagram the issued load has no repick collision and data is ready but load is not in the LHQ sliding window This load is canclled and is captured and put to sleep in LIQ Wakeup in LIQ when load is in sliding window Therefore the issued load must be canceled at E3 stage and written to LIQ Issued load is kept with VA at LIQ and must re access the vSTQ If issued load outside LHQ sliding window is DC Miss generate L2 request.
| P1 | I1 | I2 | E1 | E2 | E3 | E4 | E5 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| IEX LS ISSQ picks load | IEX issues load to LSU | No repick collision | TLB VA to PA translation | Obtain PA attributes from TLB |  |  |  |
| Arbiter for DC TAG and DATA | DC TAG and DATA access determine DC hitmiss hit way | If DC hit and out of LHQ sliding window send cancel to IEX write load to LIQ VA |  |  |  |  |  |

##### 6.1.4.1 LHQ window cancel

When uop allocate LHQ entry at pipex E4if LID is out of LHQ sliding window uop need to sleep in LIQ and wait to wakeup by LID inside LHQ sliding window.
LHQ cancel may be lower priority for LIQ.

#### 6.1.5 L1 Miss or Bank Conflict

In the following pipeline diagram the issued load has no repick collision but it encounters a resource hazard or data not ready condition Therefore the issued load must be canceled at E3 stage and written to LIQ Issued Load is pgen valid and is written to LHQ at E4.
| P1 | I1 | I2 | E1 | E2 | E3 | E4 | E5 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| IEX LS ISSQ picks load | IEX issues load to LSU | No repick collision | TLB VA to PA translation | Obtain PA attributes from TLB | Write to LHQ |  |  |

DC TAG Readout
TAG Match determine DC hitmiss hit way.
| Arbiter for DC DATA | DC DATA Readout | If DC miss send cancel to IEX send miss request to L2C write load to LIQ |
| --- | --- | --- |

##### 6.1.5.1 WB DC Miss

The table below shows a simplified view of the WB aligned load DC miss flow In the best case the load completes in two passes The first pass detects the DC miss and sends the miss request to L2 and the second pass returns the load result data to IEXFSU.
| Pass | Pipe | Stage | WB Aligned Load DC Miss |
| --- | --- | --- | --- |
| 1 | Issue Pipe | E1 | Load TLB translation |
| DC TAG Pipe | E2 | Load DC TAG miss |  |
| Issue Pipe | E3 | Load allocated to LIQ put to sleep waiting for L2 FILL |  |
| Result Pipe | E3 | Load send cancel to IEXFSU if won result pipe arbitration |  |
| LHQ | E4 | Load pgen valid written to LHQ and makes itself snoopable |  |
| REQ Pipe | E3C1E3 | LFB send miss request to L2C |  |
| REQ Pipe | C4 | Load LIQ sleep FSM changed to waiting for fill LFBID |  |
| Async Event | L2C fill return CAMs fill LFBID against LIQ sleep tags and wakes up load |  |  |
| 2 | Result Pipe | P1 | Load LIQ repick |
| Result Pipe | I2 | Load send destination tag to IEXFSU to wakeup dependents |  |
| Result Pipe | E2 | Load fill pipe read |  |
| DC DATA Pipe | E2 | Load DC DATA read if data no longer available in fill pipe |  |
| Result Pipe | E3E4 | Load select and format result return to IEXFSU |  |
| Result Pipe | E3E4 | Load nuke younger matching stale loads of same thread in LHQ |  |
| Result Pipe | E3 | Load deallocated from LIQ |  |
| Result Pipe | E4 | Load in LHQ marked as resolved |  |
| Result Pipe | E5 | Load send resolve to ROB |  |
| Async Event | Load becomes older than NextCommitRID deallocated from LHQ |  |  |

#### 6.1.6 WB 64B Misaligned Load CrossCacheline SamePage

Cross 32B loads same cacheline are resolved like normal loads in 4 cycle.
Without SBMA the best case for the WB misaligned load crosscacheline samepage flow is when the load is a DC hit and completes in two passes.
The first pass detects cross64B misaligned and the second pass is an immediate LIQ repick with expansion of MA1 and MA2 components in two backtoback cycles.
| Pass | Pipe | Stage | WB Misaligned Load Cross16B SameCacheline DC Hit |
| --- | --- | --- | --- |
| 1 | Issue Pipe | E1 | Load TLB translation |
| Issue Pipe | E1 | Load misaligned detection as SL cross16B samecacheline |  |
| DC TAG Pipe | E1 | Load DC TAG hit |  |
| Issue Pipe | E3 | Load allocated to LIQ as immediately ready to repick |  |
| Result Pipe | E3 | Load send cancel to IEXFSU if won result pipe arbitration |  |
| 2 | Result Pipe | P1 | Load LIQ repick in two backtoback cycles for MA1 and MA2 |
| Result Pipe | MA2 I2 | Load send destination tag to IEXFSU to wakeup dependents |  |
| DC TAG Pipe | E1 | Load DC TAG hit |  |
| DC DATA Pipe | MA1 E2 | Load MA1 DC DATA read |  |
| DC DATA Pipe | MA2 E2 | Load MA2 DC DATA read |  |
| Result Pipe | MA2 E3 | Load merge MA1 E4 data with MA2 E3 data |  |
| Result Pipe | E3E4 | Load select and format result return to IEXFSU |  |
| Result Pipe | MA2 E3 | Load deallocated from LIQ |  |
| Result Pipe | MA1 E4 | Load MA1 allocated to LHQ[x] |  |
| Result Pipe | MA2 E4 | Load MA2 allocated to LHQ[y] |  |
| Result Pipe | MA2 E4 | Load send resolve to ROB |  |
| Async Event | Load becomes older than NextCommitRID for its thread deallocated from LHQ |  |  |
| 617 | WB 64B Mis Aligned LD with DC Miss no Skid MA |  |  |

When the load MA1 or MA2 is a DC miss the best case requires three passes The first pass detects the DC miss for MA1 but there is no opportunity to check DC TAG for MA2 Therefore the load must be allocated to LIQ and immediately repicked so it can check DC TAG for MA2 on the second pass Any MA1 miss request sent on the first pass is sendandforget The LIQ entry does not record the fill LFBID in the MA1 sleep FSM since the load must be set as immediately ready to repick.
The second pass LIQ repick expands the MA1 and MA2 components in two backtoback cycles The second pass checks DC TAG for both MA1 and MA2 sends miss requests to L2C for MA1 and MA2 as required and sets the LIQ sleep FSMs for MA1 and MA2 to wait for their fill LFBID The LIQ entry then waits for both MA1 and MA2 to wakeup on their fill RSBID match before considering the load as ready to repick.
Finally the third pass LIQ repick returns the load result data to IEXFSU The table below shows a simplified view of the WB misaligned load crosscacheline samepage flow for the case of DC miss on both MA1 and MA2.
| Pass | Pipe | Stage | WB Misaligned Load CrossCacheline SamePage DC Miss |
| --- | --- | --- | --- |
| 1 | Issue Pipe | E1 | Load TLB translation |
| Issue Pipe | E1 | Load misaligned detection as XL crosscacheline samepage |  |
| DC TAG Pipe | E2 | Load MA1 DC TAG miss |  |
| Issue Pipe | E3 | Load allocated to LIQ as immediately ready to repick |  |
| Result Pipe | E3 | Load send cancel to IEXFSU if won result pipe arbitration |  |
| REQ Pipe | E | Load send MA1 miss request to L2C sendandforget |  |
| 2 | Result Pipe | P1 | Load LIQ repick in two backtoback cycles for MA1 and MA2 |
| Result Pipe | MA2 I2 | Load send destination tag to IEXFSU to wakeup dependents |  |
| DC TAG Pipe | MA1 E2 | Load MA1 DC TAG miss |  |
| DC TAG Pipe | MA2 E2 | Load MA2 DC TAG miss |  |
| Result Pipe | MA1 E3 | Load MA1 put to sleep in LIQ waiting for L2 FILL |  |
| Result Pipe | MA2 E3 | Load MA2 put to sleep in LIQ waiting for L2 FILL |  |
| Result Pipe | E3 | Load send cancel to IEXFSU |  |
| REQ Pipe | MA1 C1E3 | Load MA1 send miss request to L2C |  |
| REQ Pipe | MA2 C1E3 | Load MA2 send miss request to L2C |  |
| REQ Pipe | MA1 C4 | Load LIQ MA1 sleep FSM changed to waiting for fill LFBID |  |
| REQ Pipe | MA2 C4 | Load LIQ MA2 sleep FSM changed to waiting for fill LFBID |  |
| Async Event | L2C MA1 fill return CAMs fill LFBID against LIQ sleep tags and wakes up load MA1 |  |  |
| Async Event | L2C MA2 fill return CAMs fill LFBID against LIQ sleep tags and wakes up load MA2 |  |  |
| 3 | Result Pipe | P1 | Load LIQ repick in two backtoback cycles for MA1 and MA2 |
| Result Pipe | MA2 I2 | Load send destination tag to IEXFSU to wakeup dependents |  |
| Result Pipe | MA1 E2 | Load MA1 fill pipe read |  |
| Result Pipe | MA2 E2 | Load MA2 fill pipe read |  |
| DC DATA Pipe | MA1 E2 | Load MA1 DC DATA read if data no longer available in fill pipe |  |
| DC DATA Pipe | MA2 E2 | Load MA2 DC DATA read if data no longer available in fill pipe |  |
| Result Pipe | MA2 E3 | Load merge MA1 E4 data with MA2 E3 data |  |
| Result Pipe | E3E4 | Load select and format result return to IEXFSU |  |
| Result Pipe | MA2 E3 | Load deallocated from LIQ |  |
| Result Pipe | MA1 E4 | Load MA1 allocated to LHQ[x] |  |
| Result Pipe | MA2 E4 | Load MA2 allocated to LHQ[x] |  |
| Result Pipe | MA2 E4 | Load send resolve to ROB |  |
| Async Event | Load becomes older than NextcommitRID for its thread deallocated from LHQ |  |  |

#### 6.1.8 TLB Miss

In the following pipeline diagram the issued load has no repick collision but it has a TLB miss or crosspage misaligned Therefore the issued load must be canceled at E3 stage and written to LIQ Reissue with VA The issued load is not written to LHQ as it is not pgen valid.
| P1 | I1 | I2 | E1 | E2 | E3 | E4 | E5 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| IEX LS ISSQ picks load | IEX issues load to LSU | No repick collision | TLB VA to PA translation | TLB miss | Write to LIQ | No write to LHQ |  |

DC TAG Readout VIPT.
Send Cancel to IEX.
Arbiter for DC DATA VIPTA.
Reissue LIQ if TLB L2 hit.
The LSU pipe supports 9 cycles DTLB miss L2 Hit operation Data provided to IEX at E9 E4 bypass to I2.
Write to LHQ when pgen valid.

#### 6.1.9 I2 Arbitration Loss Collide with LIQ RepickReissue and TAG WR

In the following pipeline diagram the issued load collides with eg repicked load from LIQ at I2 stage The LIQ load has higher priority to use the result pipe so the issued load must be written to LIQ at E3 stage.
If issued load lost Result pipe arbiter but was able to access the TLB the issued load is written to LIQ at E3 with PA and captured in LHQ with pgen valid at E4 If issued load did not access the TLB as described in flow below it is written in LIQ VA for reissue and not written to LHQ.
An issued load which lost I2 arbitration did not generate Wakeup to dependent uOps Does not require to send Cancel to IEX.
In 920 a Skid buffer I2 is added to design attempting to regain access of issued load to Execution pipe If successful it cancels above E3 stage where issued load is written to LIQ.
| P1 | I1 | I2 | E1 | E2 | E3 | E4 | E5 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| IEX LS ISSQ picks load | IEX issues load to LSU | Issued load collides with higher priority | TLB VA to PA translation | Obtain PA attributes from TLB | Write load to LIQ if entry free setup immediate ready for repick |  |  |
| Write to Skid buffer | Write to LHQ |  |  |  |  |  |  |
| I2 | E1 | E2 | E3 | E4 | E5 |  |  |
| Skid wins arbitration | Cancel Write to LIQ | Send Resolve |  |  |  |  |  |

DC TAG Readout VIPT.
| Arbiter for DC DATA VIPTA | DC DATA Readout |
| --- | --- |

##### 6.1.1.0 Skid Buffer I2

In the following pipeline diagram the issued load collides at I2 with a repicked Reissue stage and regains access to the execution pipe in the following cycle via Skid Buffer I2.
The LIQ RepickReissue has higher priority to use the result pipe so the issued load must be written to LIQ at E3 stage The skid buffer represents 23 stage opportunity to reaccess the execution pipe When succeeding to access pipe on time before written to LIQ the issued load is not written to LIQ at E3.
| P1 | I1 | I2 | E1I2 | E2E1 | E3E2 | E4E3 | E5 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| IEX LS ISSQ picks load | IEX issues load to LSU | Issued load collides with LIQ or higher | TLB VA to PA translation |  |  |  |  |
| If TLB access not blocked | Obtain PA attributes from TLB | Write load to LIQ setup immediate ready for repick |  |  |  |  |  |
| TAG access if not blocked | write load to LHQ if pgen valid |  |  |  |  |  |  |
| Write to Skid buffer | Skid Wins I2 Arbitration | E2E1 bypass for PA if already accessed TLB | Obtain PA attributes from TLB |  |  |  |  |

DC TAG Readout VIPT or E2E1 bypass.
TAG Match determine DC hitmiss hit way.
| Arbiter for DC DATA VIPTA | DC DATA Readout | If DC miss send cancel to IEX send miss request to L2C write load to LIQ |
| --- | --- | --- |

##### 6.1.1.1 Load LIQ Reissue and Repick Pipelines

The LIQ contains sleeping load uops that are waiting for a wakeup condition before they can reflow in the load result pipeline and return data to IEXFSU.
LIQ reissue and LIQ repick are two separate mechanisms sharing same control and age matrix.
LIQ reissue is used for cases when the load did not yet obtain its PA including load TLB miss and crosspage XP misaligned load.
LIQ repick is used for cases when the load already obtained its PA from TLB In repick cases the load is captured in LHQ with pgen valid.
Note Any uOp Repick which requires to access vSTQ is required to capture the VA Only Va[150] in LIQ after PGEN and PA in LIQ.
For Reissue a load with TLB miss or crosspage XP misaligned load is written to LIQ with only its VA The LIQ entry waits for the appropriate wakeup condition such as TLB fill return or TLB miss buffer entry free then sets the load as ready to reissue On LIQ reissue LSU sends the load VA to IEX at I1 stage and IEX muxes this VA into the sourceA data for LSU at I2 stage IEX locally cancels and reissues any I1 stage issued load that collides with an I1 stage LIQ reissue The Reissued load can then use the TLB for its VA to PA translation at E1 stage.
The following diagram shows the LIQ reissue pipeline for a load which is not TLB miss and is pgen valid.
| P1 | I1 | I2 | E1 | E2 | E3 | E4 | E5 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| LIQ reissues oldest ready | LIQ sends sidedoor request to IEX IEX block the new issue | IEX muxes VA into sourceA data |  |  |  |  |  |

Lsu put the va in the local mux.
The VA muxing is still done in I2 local bypass.
| TLB VA to PA translation | Obtain PA attributes from TLB | Write PA to LIQ if DC missCancel | Write PA to LHQ | Send resolve to ROB if DC hit |
| --- | --- | --- | --- | --- |
| LIQ sends wakeup destination tag to IEX | Return load result data to IEXFSU if DC hit resolved |  |  |  |

Note The local bypass after IEX get the I1 sidedoor request IEX masked the srcab to all zeros In LSU side I2 OR the new local VA on the srcab bus.
LIQ repick is used when the LIQ already contains the translated load PA and possibly WAYSEL so an issued load in the same cycle is free to use the TLB for its VA to PA translation And it includes TAG hit information TAG match can free tag read port to issued load.
When a load uop in LIQ receives its wakeup indication it is marked as ready to repick The following diagram shows the LIQ repick pipeline.
| P1 | I2 | E1 | E2 | E3 | E4 | E5 |
| --- | --- | --- | --- | --- | --- | --- |
| LIQ repicks oldest ready load with PA and in LHQ sliding window | LIQ sends wakeup destination tag to IEX | If DC miss cancel write load to LIQ same entry with new information | Note For repack uOp is already in LHQ | Send resolved to ROB if DC hit not canceled |  |  |

##### 6.1.1.2 Load Result Pipeline

The load result pipeline selects 5 sources and merges the data sources for each load uop aligns and signextends the result data as required and returns the result data to IEXFSU The load result pipeline also sends the load resolve indication to ROB.
The initial load result data is selected from one of the following sources.
DCDATA array
DC Fill Pipeline FDB.
BasicB
The second stage of merging Data includes Data from.
SCB SCD Array
STQ STD Array
When the load overlaps an older store in STQ or SCB the youngest of older store data from STQ and SCB can be merged onto the load result data.
The following diagram shows the load result pipeline.
| E1 | E2 | E3 | E4 | E5 |
| --- | --- | --- | --- | --- |
| Send read request to DCDATA bank arbiter if required | Read DCDATA bank if required |  |  |  |

Read BasicB if required.
| CAM FDB PA | Read FDB if PA overlap |  |
| --- | --- | --- |
| CAM SCB PA | Read SCB if overlap |  |
| CAM vSTQ VA | Read STQ if overlap and forwardable |  |
| Select baseline result data source DCDATA BasicB FDB merge STQ and SCB data onto result data align result data | Signextend result data if required return result data to IEXFSU | Send load resolve to ROB |

The following diagram shows the structure of the LSU result pipeline datapath.

##### 6.1.1.3 Load Wakeup Pipeline

This pipe describes when a uOp is picked wins Arbitration and accesses the E1 Load path.

##### 6.1.1.4 Load Cancel Pipeline

When a load uop flowing in the result pipeline sent wakeup to IEX and cannot return its result data to IEXFSU for some reason LSU must cancel the load LSU sends the result cancel to IEXFSU at E3 stage The IEXIssue queues are responsible for clearing the source ready bits for source registers dependent on the canceled load at E4 stage.
For dependent instructions that already issued the load result cancel propagates through the bypass network in parallel with the result data Dependent instructions receive the cancel attached to their source data and must cancel themselves.
| E1 | E2 | E3 | E4 | E5 |
| --- | --- | --- | --- | --- |

Issue queues wakeup source registers dependent on load in result pipe at E1 stage.
| Collect load cancel conditions detected at E1 stage | Collect load cancel conditions detected at E2 stage | Collect load cancel conditions detected at E3 stage |
| --- | --- | --- |
| Combine cancel conditions from E1E3 stages send to IEXFSU | Issue queues clear source ready bits for source registers dependent on canceled load destination registers |  |
| P1 | I2 | E1 |
| Fastest possible LIQ repick of canceled load | Send load destination ptag wakeup to issue queues | Fastest possible rewakeup of source registers dependent on canceled load |

##### 6.1.1.5 StoreLoad Nuke Pipeline

When a younger load issues and resolves ahead of an older store to same address any overlapping bytes the younger load must be nuke flushed This ordering violation is referred to as a storeload nuke or storeload interaction STLI.
| P1 | I1 | I2 | E1 | E2 | E3 | E4 | E5 | E6 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |

Older Store Flow
| IEX LS ISSQ picks store | IEX issues store to LSU | Store TLB VA to PA translation |
| --- | --- | --- |

The store PA CAMs LHQ For same thread matching loads setup LHQ nuked bit write enables.
Send store AGEN resolve to ROB.
Perthread Nuke Picker.
| Use thread ID and regional age matrix to find oldest nuked load within each region | Read out RID for oldest nuked load in each region find oldest nuked RID across regions | Send nuke flush thread ID and RID to ROB | Write SID delta and valid to MDB entry at hash PC of nuked load |
| --- | --- | --- | --- |

Following Nuke the load is recorded in the MDB with a pointer to the store that nuked it to help prevent the same load from being nuke flushed again in the future After the younger load is refetched and reexecuted it obtains the data that was stored by the older store The following diagram shows the pipeline for the storeload nuke case.

##### 6.1.1.6 LoadLoad Nuke Pipeline

When a Resolved load is snooped while still in Local LHQ it generates indication to LHQCTRL which generates a Nuke indication to OOO.
A non resolved load in LHQ which is snooped does not generate a Nuke indication It is cleared in pipe and LIQ for any TAGWAYSEL information Non resolved loads will Repick and then reaccess TAG and DC for new Data.
The following diagram shows the pipeline for the loadload nuke case.
| P1 | I1 | I2 | E1 | E2 | E3 | E4 | E5 |
| --- | --- | --- | --- | --- | --- | --- | --- |

Older Load Flow
| IEX LS ISSQ picks load | IEX issues load to LSU | Select load issued by IEX or repicked by LIQ | Issued load TLB VA to PA translation or repicked load PA bypass from LIQ |
| --- | --- | --- | --- |

Send load resolve to ROB.
Perthread Nuke Picker.
| Use thread ID and regional age matrix to find oldest nuked load within each region | Read out RID for oldest nuked load in each region find oldest nuked RID across regions | Send nuke flush thread ID and RID to ROB |
| --- | --- | --- |

In 920 the snoop also access the load pipe When snoop hits uOp on pipe the uOp is Cancelled and sent to LIQ for Repick.
Load Snoop cancel window snp C7 vs E2 C8 vs E2 C9 vs E2 C10 vs E2.
Load snoop Nuke Cases snp C7 vs E3 C7 vs E4.

##### 6.1.1.7 Cross Page Mis aligned Load

When a load has Cross Page mis align the flow make sure older store have been pgen before xpload resolved Mis aligned XP uOps are Cancelled at wait in LIQ The LIQ entry waits for the appropriate wakeup conditions the PA2 is written to XP cross page Buffer do not keep PA2 for each LHQ entry Instead use xpbuffers PA.

###### 61171 WB Misaligned Load CrossPage

This section covers the crosspage XP misaligned load flow when both MA1 and MA2 pages are WB for cases when one page is WB and the other page is NC or DEV.
The flow starts with the common CrossPage Load Translation Flow which obtains the TLB translations for both MA1 and MA2 and then wakes up the load in LIQ From this point the flow behavior is similar to the WB misaligned load crosscacheline samepage flow The table below shows a simplified view of the WB misaligned load crosspage flow for the best case of DC hit on both MA1 and MA2.
| Pass | Pipe | Stage | WB Misaligned Load CrossPage DC Hit |
| --- | --- | --- | --- |
| 4 | Result Pipe | P1 | Load LIQ repick in two backtoback cycles for MA1 and MA2 |
| Result Pipe | MA2 I2 | Load send destination tag to IEXFSU to wakeup dependents |  |
| DC TAG Pipe | MA1 E2 | Load MA1 DC TAG hit |  |
| DC TAG Pipe | MA2 E2 | Load MA2 DC TAG hit |  |
| DC DATA Pipe | MA1 E2 | Load MA1 DC DATA read |  |
| DC DATA Pipe | MA2 E2 | Load MA2 DC DATA read |  |
| Result Pipe | MA2 E3 | Load merge MA1 E4 data with MA2 E3 data |  |
| Result Pipe | E3E4 | Load select and format result return to IEXFSU |  |
| Result Pipe | MA2 E3 | Load deallocated from LIQ |  |
| Result Pipe | MA2 E5 | Load send resolve to LHQ |  |

##### 6.1.1.8 NC Load Flows

The following sections provide a simplified view of the NC load flows Each section shows the best case scenario in which the load completes with minimum latency fewest possible passes through the load pipes Refer to General Load Flow for all possible cases of hazards cancels sleepwakeuprepicks faults and flushes.

###### 61181 NC Aligned Load

The table below shows a simplified view of the NC aligned load flow In all cases the load requires three separate passes one pass through the issue pipe one pass through the REQ pipe and one pass through the result pipe On the first pass through the issue pipe it is possible that the load LID already matches the OLDESTUNORDEREDLID from LHQ In this case at the end of the first pass the LIQ sleep FSM can directly start at L2C request ready to begin the second pass.
| Pass | Pipe | Stage | NC Aligned Load |
| --- | --- | --- | --- |
| 1 | Issue Pipe | E1 | Load TLB translation as NC |
| Issue Pipe | E2 | Load LID younger than OLDESTUNORDEREDLID from LHQ |  |
| Issue Pipe | E3 | Load allocated to LIQ put to sleep waiting to match OLDESTUNORDEREDLID |  |
| Result Pipe | E3 | Load send cancel to IEXFSU if won result pipe arbitration |  |
| Async Event | Load LID matches OLDESTUNORDEREDLID LIQ sleep FSM changed to request ready |  |  |
| 2 | REQ Pipe | E1 | Load LIQ request pick |
| REQ Pipe | C0E2 | Load LIQ sleep FSM changed to waiting for LFB status |  |
| REQ Pipe | C1E3 | Load send miss request to L2C flagged for BasicB postallocation |  |

Note NCDEV load cannot send request at E3 NCDEV allocate LFB at first and LFB send request to L2.
| REQ Pipe | C4 | Load LIQ sleep FSM changed to waiting for fill LFBID |  |
| --- | --- | --- | --- |
| REQ Pipe | C4 | Load inform LHQ that request was RSB allocated |  |
| Async Event | After L2C has NC data ready and BasicB credits available fill return CAMs fill LFBID against LIQ sleep tags and wakes up load fill data allocated to BasicB instead of DC |  |  |
| 3 | Result Pipe | P1 | Load LIQ repick |
| Result Pipe | I2 | Load send destination tag to IEXFSU to wakeup dependents |  |
| Result Pipe | E2 | Load read NC data from BasicB |  |
| Result Pipe | E3E4 | Load select and format result return to IEXFSU |  |
| Result Pipe | E4 | Load deallocated from LIQ |  |

Load deallocated from LIQ after it resolve at E4.
| Result Pipe | E5 | Load send resolve to LHQ |
| --- | --- | --- |
| Result Pipe | E5 | Load return BasicB credit to L2C |

###### 61182 NC Misaligned Load Cross16B SamePage

The table below shows a simplified view of the NC misaligned load cross16B samepage flow The flow is similar to the NC aligned load flow except that the LIQ must generate separate requests to L2C for the MA1 and MA2 components and the LIQ repick to return the load result must expand the MA1 and MA2 components in two backtoback cycles.
Another difference between NC aligned loads and NC misaligned loads is the BasicB protocol with L2C.
Note In target implementation there is a new BasicB mechasim MA NCDEV will not consume bad credit Align NCDEV consume bab credit after L2 fill This mechasim details is open item Requires alignment with DV and L2 designer.
Open item a new NC merged load flow spec.

###### 61183 NC Misaligned Load CrossPage

This section covers the crosspage XP misaligned load flow when both MA1 and MA2 pages are NC Refer to Error Reference source not found for cases when one page is NC and the other page is WB or DEV.
The flow starts with the common CrossPage Load Translation Flow which obtains the TLB translations for both MA1 and MA2 From this point the flow is the same as the NC Misaligned Load Cross16B SamePage flow.

### 6.2 Store Pipelines and Datapaths

This section describes the pipelines for each phase of store execution The following diagram shows the store execution pipelines.

#### 6.2.1 Store Pipe

#### Figure 6 3 LSU Store Pipelines

Store Pipe Notes
Stores that are cancelled need to be reissued In 910 spec the SAGB was identified as component which does Reissue In practice in 920 the SAGB was implemented in the STQ Changing SAGB name to STQSAGB.
When FSU ST and INT Store write to STQ Priority Mux at the E1 selects which 2 ST access FSTD has higher priority than ISTD The prearb in I2 stage If the ISTD pipe meet the FSTD vld the ISTD pipe need to be cancelled Cancel is done by IEX.
STQ data attr and bm array write at E2 stage pa and status array write at E3 stage.

#### 6.2.2 STA IEX Issue Pipeline

This section shows the pipeline diagrams for a storeaddress STA instruction issued from IEX to LSU At I2 stage an issued STA may collide with a STA reissued by LSU from STQSAGB In following diagram there is no reissue collision and the issued STA flows through to STQ write.
| P1 | I1 | I2 | E1 | E2 | E3 | E4 | E5 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| IEX LS ISSQ picks STA | IEX issues STA to LSU | TLB VA to PA translation | Obtain PA attributes from TLB write bmattr to STQ | Write pastatus to STQ | Send AGEN resolve to ROB |  |  |

#### 6.2.3 Store I2 Arbitration Loss

The pipeline diagrams for a storeaddress STA instruction issued from IEX to LSU where at I2 stage the issued STA loss arbitration collide with a STA reissued by LSU from STQSAGB In following diagram the issued STA collides with a reissued STA so it cannot flow through to STQ write Instead the STA must be written to STQSAGB and marked as ready for immediate repick.
| P1 | I1 | I2 | E1 | E2 | E3 | E4 | E5 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| IEX LS ISSQ picks STA | IEX issues STA to LSU | Issued STA collides with LSU reissue STA | TLB VA to PA translation | Obtain PA attributes from TLB write bmattr to STQ | The tlb miss retry wait mrb tagwait mrb free immtry | The tlb miss retry wait mrb tagwait mrb free immtry |  |

STQSAGB write setting stq reissue ready.

#### 6.2.4 STA STQSAGB Reissue Pipeline

STQSAGB reissue is used for cases when the STA did not yet obtain its PA including STA TLB miss and crosspage XP misaligned STA.
The STA with TLB miss or crosspage XP misaligned STA is written to STQSAGB with its VA The STQSAGB entry waits for the appropriate wakeup condition such as TLB fill return or TLB miss buffer entry free then sets the STA as ready to reissue On STQSAGB reissue LSU sends the STA VA to IEX at I1 stage and IEX muxes this VA into the sourceA data for LSU at I2 stage IEX locally cancels and reissues any I1 stage issued STA that collides with an I1 stage STQSAGB reissue The STQSAGB reissue STA can then use the TLB for its VA to PA translation at E1 stage The following diagram shows the STQ reissue pipeline for a STA TLB miss.
| P1 | I1 | I2 | E1 | E2 | E3 | E4 | E5 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| STQSAGB reissues oldest ready STA TLB miss | Mux VA into sourceA data | TLB VA to PA translation | Obtain PA attributes from TLB | Send AGEN resolve to ROB |  |  |  |
| LIQ sends sidedoor request to IEX IEX block the new issue | write bmattr to STQ | Write pastatus to STQ |  |  |  |  |  |

#### 6.2.5 CSCTD IEX Issue Pipeline

This section shows the pipeline diagram for storedata STD uops issued from IEX ALU issue queues to LSU IEX can issue up to 2 STD uops per cycle and FSU can issue up to 2 STD uops per cycle However the STQ data array only has 2 write ports STD uops from IEX also have lower priority than STD uops from FSU at the STQ data array write ports.
When FSU ST and INT Store write to STQ Priority Mux at the E1 selects which 2 ST access FSTD has higher priority than ISTD The prearb in I2 stage If the ISTD pipe meet the FSTD vld the ISTD pipe need to be cancelled Cancel is done by IEX.
| P1 | I1 | I2 | E1 | E2 | E3 | E4 | E5 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| IEX ALU ISSQ picks STD uop | IEX issues STD to LSU | write STD to STQ data array |  |  |  |  |  |
| Pre arbitration Priority Mux | Priority Mux select 2 ST |  |  |  |  |  |  |

#### 6.2.6 STD FSU Issue Pipeline

This section shows the pipeline diagram for storedata STD uops issued from FSU to LSU STD uops from FSU have the highest priority to use the STQ data array write ports so they always directly write the STQ data array at E2 stage.
| P1 | I1 | I2 | E1 | E2 | E3 | E4 | E5 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| FSU ISSQ picks STD uop | FSU issues STD to LSU | Write STD to STQ data array |  |  |  |  |  |

#### 6.2.1 STQ Retire Pipeline

The STQ can retire up to two entries per cycle to SCB Each STQ entry consists of a STA uop and STD uop Each STD uop contains up to 32B of store data Therefore the STQ can retire up to 64B of data per cycle to SCB The STQ retire operations for STA uops and STD Data are in program order but decoupled A STD uop can retire in the same cycle as its STA uop or a later cycle but not before its STA uop A STQ entry cannot be deallocated until its STD uop has been retired to SCB.
The STQ maintains a copy of its two oldest STA uops in STA head flops and its two oldest STD uops in STD head flops These head flops are loaded at R3 stage At R4 stage the STQ checks whether each head flop uop satisfies its retire requirements If so the STQ writes the uop to SCB and reloads that head flop with the next oldest uop.
Note SCB may backpressure STQ and Stall retirement pipe.
The following diagrams show the STA and STD retire pipelines.
| R2 | R3 | R4 | R5 |
| --- | --- | --- | --- |
| reload afifo if related STQ entry has pgen and related afifo is empty | read two ready store from 4 afifos and check whether it satisfy retire conditons match noflush and older load ordered |  |  |

Check whether candidate retire store can merge any sca entry or current stage retire store and record merge information into sta head.
| stirage all ready store payload into stahead | maintain store merge information by tracking previous retire situation |
| --- | --- |

Check allocate or merge conditon stq readyscb ready decode sca allocmerge vector.
| update related sca entry statepayload according to allocmerge vector | Scb state machine ready wait tag status |
| --- | --- |

Release related STQ entry.
| R3 | R4 |
| --- | --- |

Reload STA head flop with next oldest STA uop from STQ.
| R2 | R3 | R4 | R5 |
| --- | --- | --- | --- |
| reload dfifo if related STQ entry has pgen and related dfifo is empty | read std from dfifos and check whether it satisfy retire conditons its sta has retired or has in sta head |  |  |
| storage all ready store payload into stdhead | check allocate or merge conditon link same scaptr stq readyscb ready decode sca allocmerge vector |  |  |
| update related scd entry statepayload according to allocmerge vector | Check if STD uop in STD head flop satisfies retire requirements write STD uop to SCB deallocate STQ entry |  |  |

Release related STQ entry.
| R3 | R4 |
| --- | --- |

Reload STD head flop with next oldest STD uop from STQ.

#### 6.2.2 Store DC TAG Pipeline

When STQ retire pipeline reachs R5 it will access the TAGC for TAG The R5 in STQ Retire Pipeline is aligned to I2.
When a cacheable STA uop is retired from STQ to SCB and allocates a new SCA entry it must first perform a DC TAG check in Store TAGC to determine the hitmiss and MESI state for the cacheline The following diagram shows the SCA DCTAG check pipeline.
| I2 | E1 | E2 | E3 |
| --- | --- | --- | --- |
| SCB SCA picks oldest ready DCTAG check request sends to DCTAG arbiter | DCTAG arbiter selects request from SCB SCA | Read DCTAG compare send hitmiss and MESI state to SCB SCA entry | SCB SCA entry starts tracking MESI state for this cacheline |

Note This pipe and pipe stage name match with the load pipe DC access stage It is easier for arbitration logic.

#### 6.2.3 SCB DC DATA Read Pipeline

For normal WB store the SCB do not supports ReadModifyWrite operation in LX930 But it still need to reserve SCB DC read flow to support atomic instruction.
The following diagram shows the SCD DCDATA read pipeline.
| I2 | E1 | E2 | E3 |
| --- | --- | --- | --- |
| SCD picks oldest ready DCDATA read request only atomic can set sends to DCDATA bank arbiter | DCDATA bank arbiter selects read request from SCD | Read DCDATA bank | Send data to AXE atomic excute engine |

If ECC ON LSU does E3 ECC correction.
Note The SCD WR pipe incl rdmodif wr and issue pipe are using same name to match with the load pipe DC access stage It is easier for arbitration logic.

#### 6.2.4 Store DC DATA Write Pipeline

When a cacheable SCD entry contains full of store data and the corresponding SCA entry MESI state is E or M the SCD entry is eligible to write the DCDATA array The following diagram shows the SCD DCDATA write pipeline.
| I2 | E1 | E2 | E3 |
| --- | --- | --- | --- |

Scddcdatareadyi2 and do pick arb.
Scbdcdatawr01reqi2.
If pend buffer empty set scbdcdatawr01reqaccepti2.
| write into pend buffer | wr req picked from pend buffer set wr01reqe1 |
| --- | --- |

Check wether related sdb is empty.
Set wr01gnte1 to release related pend buffer.
| write data into SDB | set wr01gnte2 to release related scd entry | SCD entry state changed from dirty to clean SCD entry eligible to be overwritten |
| --- | --- | --- |

Note The SCD WR pipe and issue pipe are using same name to match with the load pipe DC access stage It is easier for arbitration logic.
The basic SCB to DC interface is shown in bellow.

### 6.3 L2C Pipelines and Datapaths

This section describes the pipelines for each interface with L2C Further details on L2C interface behavior can be found in the chapter on Major Transaction Flows.

#### 6.3.1 REQ Pipeline

There are four sources inside LSU that generate requests to L2C.
LFB
SCB
TRBE
Load result pipeabc.
The L2 Request Arbiter is constructed of 4 Request Arbiters per RSB portsarrays that are split by PA[6] Priority is SCB>LFB for same thread and for different threads the SCB and LFB randomly prioritized.
LFB merges and prioritize between L2 Requests being generated by the 3 load pipes on their L2 interface It merges the requests for the loads to same cache line for normalWB operation.
The SCB follows same Request pipe line SCB also merges stores L2 requests to same cache line in the coalescing operation of the SCB for normalWB stores.
At E2 stage aligned to C0 the LFB for each of the three request interfaces pipeapipebpipec the request arbiter REQARB selects one of the sources to send its request to L2C.
At C1 which is aligned to E3 the LFB for WB load can send request to L2 for 10 cycle.
The LFB and SCB can send a L2 Request only when RSB is not Full When RSB is Full the LFB and SCB wait until resource is available Following pipe is without RSB Full.
The following diagram shows the common REQ pipeline for each request interface to L2C.
| C0E2 | C1E3 | C2 | C3 | C4 | C5 |
| --- | --- | --- | --- | --- | --- |
| REQARB selects request source | REQARB sends request to L2C |  |  |  |  |

Or request cancel
LSU send request cancel at C1 L2 flop it at C2.
L2C sends byprdy if request can be directly bypassed to L2C TAG check pipeline.
| C3 | C4 | C5 |
| --- | --- | --- |
| L2C sends L2C TAG check hitmiss result if request was bypassed to L2C TAG check pipeline | Fastest possible L2C fill valid if request was byprdy RSB accepted and L2C TAG hit |  |

#### Figure 31 L1 miss request and L2 receive

The basic request flow is shown in figure 31.
| 1 | C0E2 stage |
| --- | --- |

LSU request which including Load E2 request request LFB request TRBE request and SCB request ready at C0.
Load E2 request request.
Load can send request to L2 when no LFB request ready and it can allocate to LFB.
LFB request
Oldest LFB entry can send request to L2.
TRBE request
SCB request
SCB can send request to L2 by retire order.
| 2 | C1 stage |
| --- | --- |

LSU send request valid and corresponding payload to L2 at C1.
LSU send request cancel at C1.
L2 send receive request ready to LSU at C1.
L2 receive request ready classification.

- a | Pipe and bank ready.

| --- | --- | --- |

LSU send request to L2 without pipe conflict Pa[6] is different.
When L2 pipex and banky receive ready is not ready LSU will not send corresponding request to it Pa[76] is different.

- b | NTNCDEVSTAT req ready.

| --- | --- | --- |

Sometimes LSU send request to L2 with data such as NT store NC store DEV and STAT far.
L2 send NTNCDEVSTAT req ready to LSU and LSU will send corresponding request to L2.
When L2 request unready C1 and LSU request valid C1 at same cycle L2 will not accept this request and LSU will send this request again after corresponding reveive ready being set by L2.
| 3 | C2 stage |
| --- | --- |
| 4 | C3 stage |

L2 send hit vld at C3.
| 5 | C5 stage |
| --- | --- |

L2 send fill valid at C5.
L2 send hit enc way at C5.
| 6 | C7 stage |
| --- | --- |

L2 fill full cacheline data at C7.

#### 6.3.2 L1 Miss REQ Pipeline

If L1 TAG miss and the load is cacheable WB the load sends a miss request to L2C.
The following diagram shows the pipeline for the fastest case of a load DC TAG check sending a miss request at E3 stage.
| E1 | E2 | E3 |
| --- | --- | --- |
| DC TAG arbiter selects load uop | DC TAG check for load uop is miss |  |

LFB CAM Lookup
| C0 | C1 | Common REQ Pipeline |
| --- | --- | --- |
| REQARB selects DC TAG Miss pipe E2 stage | REQARB sends request to L2C |  |

#### 6.3.3 Repleace RSB ID with request ID in LSU

LSU send reqid[50] to L2 L2 assign rsb id for corresponding reqid.
And L2 fill LSU with reqid Refer to LFB DS chapter for specifications of 920 req id mechasim.

#### 6.3.4 LIQ REQ Pipeline

If LIQ uop need to send request to L2 it must allocate or merge to LFB And LFB send request to L2.
Each LIQ entry has a sleep FSM that includes a REQREADY state This state means the load is ready to send a request to L2C A load can reach the REQREADY state in the following 3 cases.
Cacheable WB load with DC TAG miss can send request to L2 after allocate or merge to L2.
Cacheable WB load with DC TAG miss cannot send miss request to L2C due to RSB Full of LFB Full indication and must allocated to LIQ in REQREADY state.
For NCDEV load it will sleep in LIQ until it satisfy the ordering requirement After that it wake up and allocate to LFB and send request by LFB.
LIQ has a request picker to select the oldest entry in REQREADY state and send its request on Pipe0pipe1 based on PA[6] The following diagram shows the pipeline for a LIQ request.
| E2 | C0E2 | C1E3 | Common REQ Pipeline |
| --- | --- | --- | --- |
| LFB request picker selects oldest ready load request using age matrix for NCDEV controlled by LHQ | REQARB selects LIQ request | REQARB sends LIQ request to L2C |  |

#### 6.3.5 SCB REQ Pipeline

The SCB request will not merge with LFB The SCB only merges store miss request Before SCB sending request to L2 the corresponding ready conditions must be set.
Similar to LIQ each SCB SCA entry has a request FSM that includes a REQREADY state This state indicates the store or special instruction in that entry is ready to send a request to L2C SCA has a request picker to select the oldest entry in REQREADY state and send it its request SCB can send request to L2 on pipe0 or pipe1 which is determined by Pa[6]
The following diagram shows the pipeline for an SCA request.
| E1R7 | C0E2 | C1E3 | Common REQ Pipeline |
| --- | --- | --- | --- |
| SCA request picker selects oldest ready request using age matrix | REQARB selects SCA request | REQARB sends SCA request to L2C |  |

Refer to 628 STQ Retire Pipeline for alignment of Retirement pipe and Issue pipe for SCB completion.

#### 6.3.6 Fill Pipeline

The L1 Data Cache DC fill pipeline is fixed latency and unstoppable once L2C sends the C5 stage fill valid Fills and snoops have the highest priority at the DC TAG and DC DATA bank arbiters The LIQ observes Fill pipe and prevent picking a load that would have DATA bank conflicts with fills This is referred in spec as conflictaware repick.
The diagram below shows the fill pipeline Fill pipeline in 920 is 64B.
| C4 | C5 | C6 | C7 | C8 | C9 | C10 | C11 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| L2C sends Req ID | L2C sends definite fill valid MESI state way PA | L2C sends fill data | L2C sends fill errors |  |  |  |  |

LSU wakes up LIQ entries waiting for fill REQ ID.
Prevent P1 stage repick if DC conflict.
Note For 1R1W tag ram its not necessary.
E2 loads check DC TAG conflict.
| LSU DC TAG arb selects fill | LSU DC TAG fill PA write |
| --- | --- |

Prevent P1 stage repick if DC DATA PA[6]0 conflict.
DC banking use PA[6] to do bank aware repick.
| LSU merges SCB store data into fill data generates ECC | E2 loads check DC DATA bank conflict PA[5]0 |
| --- | --- |
| LSU DC DATA arb selects fill | LSU DC DATA fill data write |

Prevent P1 stage repick if DC DATA PA[5]1 conflict.
Fill data in the C7 to C10 stages of the fill pipeline can be bypassed to loads before it is written to DC The following diagram shows the datapath for providing fill data to loads in each result pipe.
Diagram below should be updated After fill 64B this diagram could be simplified.

#### 6.3.7 10-cycle L1MissL2Hit Pipeline

The best case scenario for the WB load L1missL2hit case is a 10-cycle load to use latency To achieve this the following events must happen in sequence.
| 1 | At E3 stage of WB load with DC miss LFB sends C1E3 miss request to L2C |
| --- | --- |
| 2 | At C1E3 stage L2C sends bypass ready to LSU indicating the request will be bypassed into the L2 TAG check pipeline |
| 3 | At C3 stage L2C sends L2 hit valid indication to LSU |
| 4 | At C5 stage L2C sends definite fill valid indication to LSU |

In LSU LIQ repicks the load to catch the C7 stage fill data return at E3 stage If any of the events above are missing the fill data from L2C will not arrive and LSU must cancel the repicked load At this point the load must be put back to sleep in LIQ to wait for the C5 stage definite fill valid indication The following diagram shows the best case 10-cycle L1missL2hit pipeline.
| E1 | E2 | E3 |
| --- | --- | --- |

LSU Load DC Miss
| DC TAG miss | LSU puts load to sleep in LIQ |  |  |
| --- | --- | --- | --- |
| C1E3 | C2E4 | C3E5 | C4 |

LSU Load Miss Request to L2C.
LSU sends miss request to L2C.
| L2C sends bypass ready to LSU | L2C sends RSB accept with REQ ID |  |  |  |  |  |  |  |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| C1 | C2 | C3 | C4 | C5 | C6 | C7 | C8 | C9 |

L2C Bypass Pipeline.
| L2C sends L2 hit valid | L2C sends definite fill valid | L2C sends fill data |  |  |  |
| --- | --- | --- | --- | --- | --- |
| P1 | I2 | E1 | E2 | E3 | E4 |

LSU LIQ Load Repick.
| LIQ C3E5 load repick at lowest priority | LSU sends load dest tag wakeup to IEX | LSU bypasses C7 fill data to load result pipe | LSU sends load result to IEX |  |
| --- | --- | --- | --- | --- |
| P0 | P1 | I1 | I2 | E1 |

IEX Dependent Instruction Wakeup and Issue.
| IEX wakes up dependent instructions | IEX bypasses E4 load result to dependent instruction |
| --- | --- |

The shortest delay is only 10 cycle for LSU tag miss to send L2 request at C1 and receive L2 fill data at C7.

#### Figure 41 LSU tag miss and L2 hit 10 cycle data flow

L2 send speculative wakeup at C1.
LSU wakeup correspoding load at C2.
L2 send hit vld at C3.
L2 send fill vld at C5.
L2 send full cacheline data at C7.
LSU get data at C7.
LSU resolve ate C9.
From LSU tag miss C0 to LSU resolve C9 the delay is only 10 cycle.

#### 6.3.8 CHL Fill Pipeline

For the L1missL2miss case L2C may send the critical half line CHL to LSU before it receives the full cacheline LSU can provide this CHL data to waiting loads while it flows through the fill pipeline but then LSU drops the CHL data before it is written to DC Once L2C obtains the full cacheline it resends the full cacheline to LSU as a regular fill that is written to DC The following diagram shows the CHL fill pipeline.
C0
| C4 | C1 |  |  |  |  |  |  |  |  |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| C5 | C2 | C3 | C4 | C5 | C6 | C7 | C8 | C9 | C10 |
| L2C sends early CHL REQ ID | L2C sends early CHL wakeup PA[5] | L2C sends CHL fill valid | L2C sends CHL 32B data |  |  |  |  |  |  |
| LSU wakes up LIQ entries that are waiting for the CHL fill REQ ID | LSU drops CHL fill data at end of fill pipeline before writing to DC |  |  |  |  |  |  |  |  |

#### 6.3.9 Snoop Pipeline

The L1 Data Cache DC snoop pipeline is fixed latency and unstoppable once L2C sends the C5 stage snoop request valid Based on the snoop request type LSU takes the following actions.
| SNPI | CPBKSNPI | CPBKSNPS |  |
| --- | --- | --- | --- |
| DC STATE write | I state | I state | S state |
| Snooped setway was E state in DC STATE and SCB | NA | Send clean copyback indication to L2C |  |
| Snooped setway was M state in DC STATE or SCB | NA | Send dirty copyback indication to L2C |  |

Send dirty cacheline data to L2C.
Fills and snoops have the highest priority to DC and snoops LIQ applies conflictaware repack and avoids picking a load that will collide with the snoop.
The following diagram shows the snoop pipeline.
| C5 | C6 | C7 | C8 | C9 | C10 | C11 | C12 | C13 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| L2C sends snoop request valid type setway | LSU DC STATE write based on snoop type I or S | LSU sends clean E or dirty M indication to L2C for copyback snoop | E2 loads check DC DATA bank conflict with copyback snoop |  |  |  |  |  |

Note Snoop copyback read is 64B.
| LSU DC DATA arb selects copyback snoop | LDU DC DATA copyback snoop read | Detect and correct copyback data ECC errors | Read SCB store data merge with DC copyback data | LSU sends dirty copyback data to L2C |
| --- | --- | --- | --- | --- |

##### 6.3.1.0 NCDEVNTFAR Store Pipeline

LSU sends NCDEVNTFAR store request data to L2C on the copyback snoop data interface Before the SCA request picker can select an NCDEVNTFAR store request at E1R7 stage it must check that this request will not have a data interface conflict with a C12 or C13 stage copyback snoop at C3E5 stage The following diagram shows the NCDEVNTFAR store pipeline.
| E1R7 | C0E2 | C1E3 | Q1 | C3E5 | C4 |
| --- | --- | --- | --- | --- | --- |
| SCA request picker selects NCDEVNTFAR store request | REQARB selects SCA request | REQARB sends SCA request to L2C |  |  |  |
| Read SCB store bytemask | Read SCB store data |  |  |  |  |

LSU sends NTDEVNTFAR bytemask to L2C.
Note The NTNCDEV write data bus is 64B.
LSU sends NTDEVNTFAR data to L2C.

- Refer to 628 STQ Retire Pipeline for alignment of Retirement pipe and Issue pipe for SCB completion.

### 6.4 MMU Pipelines and Datapaths

#### 6.4.1 TLB Miss Request Pipeline

#### 6.4.2 TLB Fill Pipeline

#### Fill pipe

#### 6.4.3 L1TLB miss and L2TLB hit pipeline

The shortest load to use latency of the L1 TLB miss and L2 TLB hit is 11-cycle.
| 7 | LSU Control |
| --- | --- |

This chapter describes the LSU key Control procedures and also focuses on the execution flows for NC DEV Atomics streaming stores.
It collects the key cross LSU procedures Flush Commit and Retire.
Refer to Error Reference source not found for details on special instruction execution.
