# LSU MDB, Exclusive, and Atomic Flows

This document covers memory-disambiguation buffer behavior, exclusive access flows, atomic implementation, interfaces, always-near policy, and terminology annex content.

> This Markdown document is the maintained specification. Sensitive metadata and internal reference labels have been removed while preserving architecture semantics.

| 1 | Allocate Pipe |
| --- | --- |

There are two ports which can access LHQ entry to allocate entry and update uOp attributes.
Entry allocate once and update attributes when entry valid and needed.
Load issue pipe at E4.
LHQ entry allocate at MA1aligned E4.
And update PANCDEVfault when TLB hit.
Result pipex at E4.
Load set resolved at E4 when no load cancel.
Set resolved load to stale.
| 2 | Cancel Pipe |
| --- | --- |

LHQ window cancel
When uop allocate LHQ entry at pipex E4if LID is out of LHQ sliding window uop need to sleep in LIQ and wait to wakeup by LID inside LHQ sliding window.
LHQ cancel may be lower priority for LIQ.
Load snoop cancel when snoop match at pipex.
Load Snoop cancel window snp C7 vs E2 C8 vs E2 C9 vs E2 C10 vs E2.
Load snoop bypass window snp C7 vs E3 C7 vs E4.
| 3 | Nuke Pipeline |
| --- | --- |

StoreLoad nukeSTLI.
When a younger load issues and resolves ahead of an older store to same address any overlapping bytes the younger load must be nuke flushed This ordering violation is referred to as a storeload nuke or storeload interaction STLI The load is recorded in the MDB with a pointer to the store that nuked it to help prevent the same load from being nuke flushed again in the future After the younger load is refetched and reexecuted it obtains the data that was stored by the older store The following diagram shows the pipeline for the storeload nuke casestld nuke check stage delay 1-cycle at st pgen e4 stage in 930.
Bypass window
LHQ CTRL pick oldest nuked entry.
LoadSnoop nuke
When a resolved load is snoop invalidated while still in LHQ it is marked as stale If an older load with same address any overlapping halfwords resolves after the younger stale load the younger stale load must be nuke flushed The reason is that the younger load returned old data while the older load returned new data This ordering violation is referred to as a loadload nuke After the younger load is refetched and reexecuted it obtains the new data The following diagram shows the pipeline for the loadload nuke case.
Snoop C7 match resolved load to send OOO nuke flush.
Snoop C7 valid with Fill interaction.
DMB nuke
The flow is same as Linx910 When snoop match set younger load to stale When DMB resolve arrive set nuked to pick as STLI flow.
Other special issue.
LDAR it make older entry to be unresolved and set snoop nuke.
XTS snoop same as L2 snoop Also need to read PA.
Storeload order STLRATOMARSTXRDSBESB nuke.
AcquireDMBDSB use the nuke flush flush all the younger staled resovled load.
| 4 | Flush |
| --- | --- |

For OOO flush deallocated all LHQ entry from LHQ oldest pointer Nuke flush also need LID.
For BRU flush need a new iexlsubruflushlide2 from IEX to LSU and can be a vector flush for LHQ.
| 5 | Next commit |
| --- | --- |

LHQ sliding window.
Generate lhqoldestptr and locallhqoldestptr based LID.
LHQ sliding windowLID lhq youngest ptrlhq oldestptr + 464.
All LLHQs are unified to a LHQ sliding window And size LLHQsize3.
When commit rid update sliding window advanced forward.
Every commit starts from the head of LLHQs and slide one by one in sequence.
Lhqoldestptrt0t1 is reset by lsu reset or smt switch.
If 340 6-bit+2bitwhen LID[10] arrive to 2then return to 0.
When LID[72] arrive to 39then return to 0.
Since based on LID multiuops is one rid together with many LIDs.
LHQ oldest ptr update.
Firstly use locallhqoldestptrvec to select local oldest RID and compare with nextcommit RID per cycle.
If local LHQ RID is olderlocal LHQ entry can be deallocated and send commitplus1 to LHQ CTRL.
Secondly LHQ CTRL advance lhqoldestptr based on numbers of local commit Also advance locallhqoldestptr from which local LHQ have valid deallocated entry.
Max three LHQ entries deallocated per cycle based on locallhqoldestptr For each time next commit is updatedmay be many cycles to deallocate LHQ entry.

#### 1.2.7 LFBlinfei

##### 1.2.7.1 Introduction

LSU redundant request to L2 study.
Hardware Prefetch HWPF off.
AVG 70 of all LSU L2 requests are redundant when HWPFs are OFF 34M47M For Demanded 14M L2 accesses 47M L2 requests are generated where 34M are redundant 70 RSB merges 32M L2 request out of 34MOut of Total 47M L2 Requests 43M are coming from issue The LMQ contributes 008M requests and SCB 028M requests.
LSU Prefetch HWPF off.
Number of Total requests drops 32 from 47M to 32M These 15M requests without PF are now L1 Hit Number of Redundant requests drops by 66 35M>13M This is explained as every useful pre fetch which is brought to L1 can remove multiple redundant LSU requests LSU sent 2M L2 requests out of which 13M are redundant 65 Out of Total 13M LSU L2 Requests 11M was 43M are coming from issue LMQ contributes 006M 008M requests and SCB 011M 028M requests L1 HWPF generates 003M redundant requests Out of the Redundant L2 requests generated the LSU 3 sources is accounting for AVG 97 of all redundant But that is with HWPF already requesting it.
Line Fill buffer
Line fill buffer LFB is to merge LSU load tag miss requests which can effectively reduce redundant requests to L2 and improve performance.
Modeling of LFB

#### Figure 11 LSU Miss Request System Flow

Load will cam LFB and SCB at E2 If tag miss and not hit SCB load will allocate or merge LFB If tag miss and hit SCB load will not allocate LFB and wait fill that corresponding SCB request LFB and SCB can send different bank request to L2 at same cycle LSU will not send requests to L2 until its corresponding request ready is valid for LSU After receiving LSU request L2 will fill LSU with corresponding request ID which will wake up LIQ or SCB.
LFB CAM is partition for three load pipe and it is full PA cam structure like LHQ in previous-generation core CAM payload contain PA[486] Mematt[40] Tid and Tagcheck.
LFB CTRL is shared for all load pipe Type[30] Mod[50] and other payload are stored in CTRL.

#### Figure 12 LFB structure

##### 1.2.7.2 Load request merge

All loads will cam PA[486] with LFB LFB allocating PA E3 issue PA E2 STQ and SCB at stage E2 as shown in figure 21 Based on PA cam results there are four stages including allocating or merging with LFB Wait LFB credit and wait SCB fill Detail relationship between PA cam and results is shown in figure 22.

#### Figure 21 load cam LFB and Store

#### Figure 22 load cam result

| 1 | Allocating LFB |
| --- | --- |

Load tag miss and PA cam does not hit any LFB and SCBwhen LFB is not full.
| 2 | Merging with LFB |
| --- | --- |

Load tag miss and PA cam hit any LFB and not hit SCB.
| 3 | Wait LFB credit |
| --- | --- |

Load tag miss and PA cam does not hit any LFB and SCBwhen LFB is full.
| 4 | Wait SCB fill |
| --- | --- |

Load tag miss and PA cam hit SCB it will record corresponding SCB request ID and wait its fill.
Load allocating or merging with LFB.
| 1 | WB load can merge with the same cacheline and memory attributes load WB load can merge |
| --- | --- |

With different thread load.
| 2 | NC load can merge with the same cacheline and memory attributes load |
| --- | --- |

- Cross thread NC load merge is forbidden by TID compare The NC load merge feature is discussed in a separate chapter.
- After L2 fill C5 LFB will be at data home state and cannot be merged.

| 3 | Device load will not merge with any LFB entry |
| --- | --- |
| 4 | Misalign load cross 64B and cross page will allocate two LFB entry |
| 5 | Prfm L1 will allocate or merge with LFB entry without distinguish the request type RDS or RDM |
| 6 | Ldxr will allocate or merge with LFB entry when snoop delay unset Otherwise Ldxr request typ |

Type must be RDM and cannot merge with any LFB entry.
R1 when snoop delay set ldxr allocate a new LFB and the request with snpdelay label.

- L2 fake merge or misp snp +fill.

R2 if the snp mechanism between LSU and L2 change to request and ackldxr can merge with any LFB.
| 7 | Ldat will not allocate or merge with any LFB entryAtomic is moved to store pipe |
| --- | --- |

A. Load hit stq E2 and the corresponding LFB request type change RDS to RDM.

- b Load hit stq E2 without any process.

Merge window
| a | WB merge window |
| --- | --- |

#### Figure 23 WB load merge window

After WB LFB send request to L2 same pa load will merge to LFB until corresponding C5 fill valid After that load can hit fill pipe or hit tag.
| b | NC merge window |
| --- | --- |

#### Figure 24 NC Load merge window

After NC LFB send request to L2 same pa NC load will merge to LFB until corresponding C5 fill valid.
E3 VS C5 NC load merge to LFB and repick immediately use the LFB deallocate to wakeup.
LFB sends request to L2.
First allocate first request All request ready LFB will send request to L2 in allocating order.
WB normal load can send request to L2 at E3for 10 cyclewhen no LFB request ready and LFB is not full.
For NCDEV the shadow reject window is as follow.

#### Figure 25 NCDEV shadow reject window

Flush LFB
LFB will not be flushed and it will be deallocated after L2 fill.
Alias
LFB merge with PA[486] cam alias is merge to the same LFB entry.
LIQ record va13to12 and hitway.
After L2 fill the corresponding LFB linked LIQ will update the va13to12 and hit way by the fill encway[30]
Cross thread XT merge.
Only WB LFB can be cross thread merged.
When load allocate the LFB the NCDEV and Oldest will set usebab in LIQ.
When safemode set only the same tid load can allocate or merge LFB and the other thread load will wait LFB credit in LIQ.
Deallocate

#### Figure 26 WB load linked LFB deallocate

| 1 | After WB fill C5 ID match with LFB it will deallocate |
| --- | --- |

After WB fill load will get data from fill pipe or data cache as shown in figure 25.

##### 1.2.7.3 Interface Protocol with L2

Merge request and fill pipe in LSU and L2.

#### Figure 31 L1 miss request and L2 receive

The smoothy L2 wakeup is implemented in LFB The L1 miss requests are merged in LFB Afer L2 send a early wakeup signal LFB record the wakeup status 1 boardcast the wakeup signal to the linked LIQ entries 2 any loads merged to LFB after the L2 wakeup are use the LFB wakeup status to do imm repick The LFB wakeup status is start from L2 early wakeup flag and end at C5 if fill vld arrived at C5 its a correct wakeup if not its false wakeup and LFB need to go back to wakeup L2C status.
The basic request flow is shown in figure 31.
| 1 | C0 stage |
| --- | --- |

LSU request which including Load E2 request request LFB request and SCB request ready at C0.
Load E2 request request.
Load can send request to L2 when no LFB request ready and it can allocate to LFB.
LFB request
Oldest LFB entry can send request to L2.
SCB request
SCB can send request to L2 by retire order SCB request is lookup LFB but not allocate or merged in LFB.
| 2 | C1 stage |
| --- | --- |

LSU send request valid and corresponding payload to L2 at C1.
LSU send request cancel at C1.
L2 send receive request ready to LSU at C1.
L2 receive request ready classification.
| a | Pipe and bank ready |
| --- | --- |

LSU send request to L2 without pipe conflict Pa[6] is different.
When L2 pipex and banky receive ready is not ready LSU will not send corresponding request to it Pa[76] is different.
| b | NTNCDEVSTAT req ready |
| --- | --- |

Sometimes LSU send request to L2 with data such as NT store NC store DEV and STAT far.
L2 send NTNCDEVSTAT req ready to LSU and LSU will send corresponding request to L2.

- When L2 request unready C1 and LSU request valid C1 at same cycle L2 will not accept this request and LSU will send this request again after corresponding reveive ready being set by L2.

| 3 | C2 stage |
| --- | --- |

L2 send speculative wakeup at C1 or C2TBD At C2 stage the speculative wakeup can more precise.
| 4 | C3 stage |
| --- | --- |

L2 send hit vld at C3.
| 5 | C5 stage |
| --- | --- |

L2 send fill valid at C5.
L2 send hit enc way at C5.
| 6 | C7 stage |
| --- | --- |

L2 fill full cacheline data at C7.
Repleace RSB ID with request ID in LSU.
LSU send reqid[60] to L2 L2 assign rsb id for corresponding reqid.
And L2 fill LSU with reqid.
LFB req ID[65] 00 Store SCB[65] 2b01.
Request interface
The L1L2 request interface could check the target implementation Interface document.
| 1274 | 10 Cycles and CHL protocol |
| --- | --- |

10 cycle
This is performance feature When LSU tag miss it send request to L2 at C1 and L2 will fill data at C7 The shortest delay is only 10 cycle.

#### Figure 41 LSU tag miss and L2 hit 10 cycle data flow

L2 send speculative wakeup at C1 or C2 At C2 stage the speculative wakeup can more precise.
LSU wakeup correspoding load at C2.
L2 send hit vld at C3.
L2 send fill vld at C5.
L2 send full cacheline data at C7.
LSU get data at C7.
LSU resolve ate C9.
From LSU tag miss C0 to LSU resolve C9 the delay is only 10 cycle.

#### Figure 42 performance optimization based on 10 cycle option1

Performance optimization based 10 cycle.
Option1
L2 send speculative wakeup C1C2 to LSU.
E2 VS C2 LFB merge LIQ immediately repick.
Otherwise wait L2 respond.
L2 send hit valid C3 to LSU.
E2 VS C3 E2 VS C4 LFB merge LIQ immediately repick.
Otherwise wait L2 respond.
L2 send fill valid C5 to LSU.
E2 VS C5 LFB merge LIQ immediately repick.
Otherwise wait L2 respond.
Option2
L2 send speculative wakeup C1C2 to LSU.
E2 VS C4 C5 LIQ immediately repick.
E2 VS C3 LIQ wait L2 fill.

#### Figure 43 performance optimization based on 10 cycle option2

CHL
This is a performacne feature CHL critical word bypass bypass PENDFLIT or RX data directly to LSU targets on reducing L2 miss load to use latency.
Two cases of CHL for L3 behaviors.

1. L3 hit case L3 gives PENDFLIT 512B data two cycles before RX data L2 gives early wake up and critical word data fill to LSU.
2. L3 miss case no early PENDFLIT L2 bypass critical word data from RX data.

#### Figure 44 Load get data from CHL fill

Preallocate LHQ repick load may get data from C7 to Cb.
E2 VS C9 get CHL data the timing must be considered.
E2 VS Ca and E2 Vs Cb get getting CHL data based on when the load allocate LHQ only resolve or including E4 Wr after allocating LIQ.
Issue load may get data when E2 VS C6 C7 C8 C9 When E2 VS C9 get data timing must be considered.

##### 1.2.7.5 SCB Request

SCB request will not merge with LFB.
SCB will merge store miss request.
Before SCB sending request to L2 the corresponding ready must be set.

##### 1.2.7.6 LFB FSM STATE

#### Figure 81 LFB FSM STATE

##### 1.2.7.7 LIQ FSM STATE

#### Figure 91 LIQ FSM

| 128 | STQ |
| --- | --- |

##### 1.2.8.1 Overview

- Figure 1 STQ Overview.

The document include the above colored module which is related to stq.
Features
STQ provides the following features.
| Feature | Description |
| --- | --- |
| 162024 entry stq odd bank array | Include the store info which used to reissue and retired to scb |
| 162024 entry stq even bank array | Include the store info which used to reissue and retired to scb |
| 324048 entry vstq array | Include the store info which used to forward data to load |
| STLD forwarding | Forward data from stq entries which match with load |

Table 1 High Level Features.

##### 1.2.8.2 Structure

###### 12821 Basic Diagram

###### 12822 Main Storage Description

Stq even att array.
| Field | Width | RD | WR | CAM | Comment |
| --- | --- | --- | --- | --- | --- |

Attribute Array
| hashpc | 11 | R3 STA retire0 | E2 IEX sta0 issue | Hash of PC[482] for prefetcher |
| --- | --- | --- | --- | --- |
| type | 12 | R3 STA retire0 |  |  |
| R3 STD retire0 | E2 IEX sta0 issue | Store uop type |  |  |
| size | 4 | R3 STA retire0 | E2 IEX sta0 issue | Store uop size |
| sampled | 1 | R3 STA retire0 | E2 IEX sta0 issue | Store sampled by SPU |
| mgw | 1 | R3 STA retire0 | E2 IEX sta0 issue | uop is MGW |
| tid | 1 | R2 STA retire0 |  |  |
| R2 STD retire0 | E2 IEX sta0 issue | R4 T0 flush |  |  |
| R4 T1 flush | Thread ID |  |  |  |
| rid | 8 | R2 STA retire0 |  |  |
| R2 STD retire0 | E2 IEX sta0 issue | ROB ID |  |  |

Stq odd att array
| Field | Width | RD | WR | CAM | Comment |
| --- | --- | --- | --- | --- | --- |

Attribute Array
| hashpc | 11 | R3 STA retire1 | E2 IEX sta1 issue | Hash of PC[482] for prefetcher |
| --- | --- | --- | --- | --- |
| type | 12 | R3 STA retire1 |  |  |
| R3 STD retire1 | E2 IEX sta1 issue | Store uop type |  |  |
| size | 4 | R3 STA retire1 | E2 IEX sta1 issue | Store uop size |
| sampled | 1 | R3 STA retire1 | E2 IEX sta1 issue | Store sampled by SPU |
| mgw | 1 | R3 STA retire1 | E2 IEX sta1 issue | uop is MGW |
| tid | 1 | R2 STA retire1 |  |  |
| R2 STD retire1 | E2 IEX sta1 issue | R4 T0 flush |  |  |
| R4 T1 flush | Thread ID |  |  |  |
| rid | 8 | R2 STA retire1 |  |  |
| R2 STD retire1 | E2 IEX sta1 issue | R4 T0 flush |  |  |
| R4 T1 flush | ROB ID |  |  |  |

Stq even pa array
| Field | Width | RD | WR | CAM | Comment |
| --- | --- | --- | --- | --- | --- |

PA Array
| papage | 37 | R4 STA retire0 |
| --- | --- | --- |
| I1 reissue | E3 IEX sta0 issue | PA[4812] or va[4812] or srca |
| mair | 5 | R4 STA retire0 |
| I1 reissue | E3 IEX sta0 issue | mair or va[5349] |
| pbha | 2 | R4 STA retire0 |
| I1 reissue | E3 IEX sta0 issue | Phba |
| shareable | 2 | R4 STA retire0 |
| I1 reissue | E3 IEX sta0 issue | Sharable or va[5554] |
| pagesize | 3 | R4 STA retire0 |
| I1 reissue | E3 IEX sta0 issue | Page size or va[5856] |
| ncdev | 1 | R4 STA retire0 |
| I1 reissue | E3 IEX sta0 issue |  |
| dev | 1 | R4 STA retire0 |
| I1 reissue | E3 IEX sta0 issue |  |
| vaidx1 | 10 | R4 STA retire0 |
| I1 reissue | E3 IEX sta0 issue | VA[156] for ssa ma1 component or |

6b0 va[6359]
| vaidx0 | 10 | R4 STA retire0 |
| --- | --- | --- |
| I1 reissue | E3 IEX sta0 issue | VA[156] for ssa ma2 component or |

6b0 va[6359]
| vaoffset | 6 | R4 STA retire0 |
| --- | --- | --- |
| I1 reissue | E3 IEX sta0 issue | VA[50] for ssa |

Stq odd pa array
| Field | Width | RD | WR | CAM | Comment |
| --- | --- | --- | --- | --- | --- |

PA Array
| papage | 37 | R4 STA retire1 |
| --- | --- | --- |
| I1 reissue | E3 IEX sta1 issue | PA[4812] or va[4812] or srca |
| mair | 5 | R4 STA retire1 |
| I1 reissue | E3 IEX sta1 issue | mair or va[5349] |
| pbha | 2 | R4 STA retire1 |
| I1 reissue | E3 IEX sta1 issue | Phba |
| shareable | 2 | R4 STA retire1 |
| I1 reissue | E3 IEX sta1 issue | Sharable or va[5554] |
| pagesize | 3 | R4 STA retire1 |
| I1 reissue | E3 IEX sta1 issue | Page size or va[5856] |
| ncdev | 1 | R4 STA retire1 |
| I1 reissue | E3 IEX sta1 issue |  |
| dev | 1 | R4 STA retire1 |
| I1 reissue | E3 IEX sta1 issue |  |
| vaidx1 | 10 | R4 STA retire1 |
| I1 reissue | E3 IEX sta1 issue | VA[156] for ssa ma1 component or |

6b0 va[6359]
| vaidx0 | 10 | R4 STA retire1 |
| --- | --- | --- |
| I1 reissue | E3 IEX sta1 issue | VA[156] for ssa ma2 component or |

6b0 va[6359]
| vaoffset | 6 | R4 STA retire1 |
| --- | --- | --- |
| I1 reissue | E3 IEX sta1 issue | VA[50] for ssa |

Stq even status array.
| Field | Width | RD | WR | CAM | Comment |
| --- | --- | --- | --- | --- | --- |

Status Array
| dgen | 1 | R2 STD retire | E2 IEXFSU std0 issue |
| --- | --- | --- | --- |
| pgen | 1 | R2 STA retire | E2 IEXFSU std0 issue |
| fault | 1 | R2 STA retire | E3 IEX sta0 issue |
| commited | 1 | R2 STA retire |  |
| R2 STD retire | R3 OoO commit |  |  |
| ma | 1 | R2 STA retire |  |
| R2 STD retire | E3 IEX sta0 issue |  |  |
| null | 1 | R3 STA retire |  |

R3 STD retire
| I1 reissue | E3 IEX sta0 issue |  |
| --- | --- | --- |
| xp | 1 | R2 STA retire |
| I1 reissue | E3 IEX sta0 issue |  |

Stq odd status array.
| Field | Width | RD | WR | CAM | Comment |
| --- | --- | --- | --- | --- | --- |

Status Array
| dgen | 1 | R2 STD retire | E2 IEXFSU std1 issue |
| --- | --- | --- | --- |
| pgen | 1 | R2 STA retire | E2 IEXFSU std1 issue |
| fault | 1 | R2 STA retire | E3 IEX sta1 issue |
| commited | 1 | R2 STA retire |  |
| R2 STD retire | R3 OoO commit |  |  |
| ma | 1 | R2 STA retire |  |
| R2 STD retire | E3 IEX sta1 issue |  |  |
| null | 1 | R3 STA retire |  |

R3 STD retire
| I1 reissue | E3 IEX sta1 issue |  |
| --- | --- | --- |
| xp | 1 | R2 STA retire |
| I1 reissue | E3 IEX sta1 issue |  |

Stq even bm array
| Field | Width | RD | WR | CAM | Comment |
| --- | --- | --- | --- | --- | --- |

Bytemask Array
| bytemask | 16 | E2 pipe0 load |
| --- | --- | --- |

E2 pipe1 load
E2 pipe2 load
R4 STD retire0
| R4 STD retire1 | E2 IEX sta0 issue |
| --- | --- |

Bytemask for store.
Stq odd bm array
| Field | Width | RD | WR | CAM | Comment |
| --- | --- | --- | --- | --- | --- |

Bytemask Array
| bytemask | 16 | E2 pipe0 load |
| --- | --- | --- |

E2 pipe1 load
E2 pipe2 load
R4 STD retire0
| R4 STD retire1 | E2 IEX sta1 issue |
| --- | --- |

Bytemask for store.
Stq even data array.
| Field | Width | RD | WR | CAM | Comment |
| --- | --- | --- | --- | --- | --- |

Data Array
| data | 128 | E2 pipe0 load |
| --- | --- | --- |

E2 pipe1 load
E2 pipe2 load
R3 STD retire0
| R3 STD retire1 | E2 std0 issue |
| --- | --- |

Stq odd data array.
| Field | Width | RD | WR | CAM | Comment |
| --- | --- | --- | --- | --- | --- |

Data Array
| data | 128 | E2 pipe0 load |
| --- | --- | --- |

E2 pipe1 load
E2 pipe2 load
R3 STD retire0
| R3 STD retire1 | E2 std1 issue |
| --- | --- |

Vstq array
| Field | Width | RD | WR | CAM | Comment |
| --- | --- | --- | --- | --- | --- |

Attribute Array
| va | 49 | E1 pipe0 ld |
| --- | --- | --- |

E1 pipe1 ld
| E1 pipe2 ld | Sta issue e2 | E1 pipe0 ld |
| --- | --- | --- |

E1 pipe1 ld
E1 pipe2 ld
| xp | 1 | E1 pipe0 ld |
| --- | --- | --- |

E1 pipe1 ld
| E1 pipe2 ld | Sta issue e2 |  |
| --- | --- | --- |
| tid | 1 | E2 pipe0 ld |

E2 pipe1 ld
| E2 pipe2 ld | Sta issue e2 |  |
| --- | --- | --- |
| stlr | 1 | E2 pipe0 ld |

E2 pipe1 ld
| E2 pipe2 ld | Sta issue e2 |  |
| --- | --- | --- |
| St fwd disable | 1 | E1 pipe0 ld |

E1 pipe1 ld
| E1 pipe2 ld | Sta issue e2 |  |
| --- | --- | --- |
| Size bytes num | 5 | E1 pipe0 ld |

E1 pipe1 ld
| E1 pipe2 ld | Sta issue e2 |  |
| --- | --- | --- |
| Pgen ma1 | 1 | E2 pipe0 ld |

E2 pipe1 ld
| E2 pipe2 ld | Sta e2 |  |
| --- | --- | --- |
| Pgen ma2 | 1 | E2 pipe0 ld |

E2 pipe1 ld
| E2 pipe2 ld | Sta e2 |  |
| --- | --- | --- |
| Pa page | 12 | E2 pipe0 ld |

E2 pipe1 ld
| E2 pipe2 ld | Sta pgen e2 |  |  |
| --- | --- | --- | --- |
| ncdev | 1 | Stq dealloc r5 | Sta pgen e2 |
| ma | 1 | E1 pipe0 ld |  |

E1 pipe1 ld
| E1 pipe2 ld | Sta issue e2 |  |
| --- | --- | --- |
| fault | 1 | E1 pipe0 ld |

E1 pipe1 ld
| E1 pipe2 ld | Sta e2 |  |
| --- | --- | --- |
| Esize endian | 3 | E1 pipe0 ld |

E1 pipe1 ld
| E1 pipe2 ld | Sta issue e2 |  |
| --- | --- | --- |
| dgen | 1 | E2 pipe0 ld |

E2 pipe1 ld
| E2 pipe2 ld | Std e2 or fake sta e2 |  |
| --- | --- | --- |
| dczva | 1 | E1 pipe0 ld |

E1 pipe1 ld
| E1 pipe2 ld | Sta issue e2 |  |
| --- | --- | --- |
| Cmofault | 1 | E2 pipe0 ld |

E2 pipe1 ld
| E2 pipe2 ld | Sta e2 |  |
| --- | --- | --- |
| Cmodczva | 1 | E1 pipe0 ld |

E1 pipe1 ld
| E1 pipe2 ld | Sta issue e2 |  |
| --- | --- | --- |
| Cannt fwd | 1 | E1 pipe0 ld |

E1 pipe1 ld
| E1 pipe2 ld | Rob flush r4 |  |
| --- | --- | --- |
| All active | 1 | E1 pipe0 ld |

E1 pipe1 ld
| E1 pipe2 ld | Sta issue e2 |  |
| --- | --- | --- |
| agen | 1 | E1 pipe0 ld |

E1 pipe1 ld
| E1 pipe2 ld | Sta e2 |
| --- | --- |

Local scb tag array.
| Field | Width | RD | WR | CAM | Comment |
| --- | --- | --- | --- | --- | --- |

Attribute Array
| sca pa page[4812] | 37 | E2 IEX ldx issue | R5 STA retire | Pa page for sca entry |  |
| --- | --- | --- | --- | --- | --- |
| sca idx[116] | 6 | E2 IEX ldx issue | R5 STA retire | Pa page for sca entry |  |
| sca tid | 1 | E2 IEX ldx issue | R5 STA retire | Pa page for sca entry |  |
| Field | Width | RD | WR | CAM | Comment |

Attribute Array
| scd pa5 | 1 | E2 IEX ldx issue | R5 STD retire | Pa5 page for scd entry |
| --- | --- | --- | --- | --- |
| scdsca ptr[30] | 4 | E2 IEX ldx issue | R5 STD retire | Sca entry number for scd entry |

###### 12823 STQ FSM

FSM1
FSM2

###### 12824 STQ Implementation Description

Basic implementation.
How to write
When multi thread
How to read
| 12825 | DAGB |
| --- | --- |

Scheme1
Scheme2
In target implementation it will extend the std forward window for load Basic On Latest Perf Study this gain is very limited The feature is dropped.
In 910 load can get the std data at E2 through reading from stq or bypass from E2 store on store std pipeline.
In target implementation load can get the std data at E2 through reading from stq or bypass from E2 store on store std pipeline or bypass from E1 store on store std pipeline when load arrive E3 it can continue to get the store data form E1 store on store std pipeline.
That to say in target implementation two more std cycle will be watched by the load to redue the SILF cancel.
Reg to reg

###### 12826 Forward

Scheme in 910
Scheme in 920
In target implementation it will do the va match between load and store in stqinclude store on store pipe.
It will be done in E1 and generate the youngest matched stq entry number With this youngest matched stq entry number it will do the stq entry reading at E2 and supply data for load.
When doing match logic at E1 three part will be used.
Part0 Load VA[4812] match with stq entry va[4812] may need custom cell.
Part1 load va[116] match with stq entry index0[116] may need custom cell.
Load va[116] match with stq entry index1[116] may need custom cell.
Part2 load rangeVA[50] + size match with stq entry range0 VA0[50] + size.

- load rangeVA[50] + size match with stq entry range1 VA1[50] + size.

When all of the three part is matched then the related stq entry can supply data for load and it needs to select the youngest one.
After matching the youngest matching stq entry will supply data for this load.
Range match is used to decide whether the range of load is match with the range of store Its range match instead of equal match so it will not be done by custom cell.
It will use the following scheme.
CAM Scheme
In 930 only repick load with mhit can divide into ldhldl when cam stq Issue load will cancel and record mhit in LIQ if hit more than 1 stq entry.
There is only 1 ldh cam port in 930 means only 1 repick load can divide and do mhit2 forward If more than 1 load want to do ldh cam only 1 can success random select 1 The others will cam stq again and can resolve if it is singlehit this time otherwise will cancel and immrepick to arb for ldh cam port again.
32B store pipe forward schema.
32B store scheme
Diffenent with 16BL Std 16BH high 16B of 32B store is not camthenread load need use known STQ IDX to read STD 16BH at E1 stage.
In 930 one load can only read one 16BH high 16B of 32B store through repicknf If load hit std 16BH load will cancel and record hit sid in LIQ then load will repick and read std 16BH at E1 stage.
Retire reading scheme.

###### 12827 Scb local tag

To release the r4 timing vstqs deallocation will be delayed one cyclefrom r4 to r5 So scb entrys content for load forward can also be delay for one cycle.
There is a local scb tag in each ld pipe it will be used to do part load match logic for load forwarding It will generate a match vector for scd entry and scd entry will use it to read bytemask and data Before reading bytemask scd need to qualify it with dirty or clean bit.
For the cancel logic local scb tag will generate the pa match for each sca entry a pa and tid match vector will be send to SCA And SCA entry need qualify it with fsm or other info to generate the final cancel valid and related cancel scb entry number to result pipe or LIQ.
| 129 | SCB |
| --- | --- |

##### 1.2.9.1 STLR Optimization

Instructions
Optimized instructions are normal and NCDEV STLR STXR exclusive and STAR atomic are not optimizedDiscussed in the Exclusvie and Atomic Optimization charpters.
Stqretirectrl scbstahead.
When these instructions retire from STQ to SCB they used to be blocked at retire head R4 until nextcommit reach In Lx930 they can retire when no flush rid reach them DSB and DMB instructions are not able to be merged into SCA entry and younger instructions are not to be merged on them too STLR can merge into SCA youngest entry when them attributes is match.
New status bit WaitWR and WaitWRPTR[40] are added for these instructions STLR DSB and DMB instruction set WaitWR bit and WaitWRPTR[4] when they are allocated into SCB and clear these bits when it is the oldest one in SCA They clear ALL mergeable bit of the same thread in SCA.
WB stores after WB STLR is not able to merge into STLR itself and stores before STLR All stores after STLR set WaitWR bit when retiring and they set WaitWRPTR[40] 1b0 STLRPTR Their WaitWR bit is cleared when STLR deallocate from SCB STLR stores use WaitGO bit instead of waitWRPTR It is cleared with STLR WaitWR bit when STLR preDMB acked.
NDCpend
NC DEV NT stores set NDCpend bit when their WaitWR bit is 0 STLR uop clear NDCpend bit by send out preDMB when it retires if there are no special instructions in SCB Otherwise STLR clear NDCpend bit when it is the oldest one in SCB.
Scbscdentry
When WaitGO is set SCD is not able to merge into fill or write into D Only STLR write is blocked and all other store can write into D as normal All special instructions including NC \ DEV \ SYS and etc cannot send request to L2 WB stores is able to send out readM or fill request If the cache line that STLR write into is snooped before STLR get oldest the SCA FSM will sent DC fill request again.
Special Uop serial.
NDC Pend + Normal ST STLR Normal ST.
STLR send preDMB using barfsm when retired from STQ @stage R4 if the barfsm is idle Otherwise it sends preDMB when it is the oldest STLR check NDCpend bit after it becomes oldest it write into DCache and deallocate as a normal store.
NCDEV ST STLR NCDEV ST.
STLR send preDMB uop when it becomes oldest Then it write into DCache Younger NCDEV stores WaitWR bit is set and WaitWRPTR is linked to STLR They set NDCpend bit when their WaitWR bit is cleared at which time that STLR is deallocated.
NCDEV ST NCDEV STLR NCDEV ST.
NCDEV STLR act as same as WB STLR before it deallocated and send out postDMB uop after send store request to L2 Younger NCDEV stores set NDCpend bit after STLR deallocated.
STLR normal LDAR STLR.
If older STLR is issued after younger LDAR the LDAR is cancelled and wait oldest resolve rid reach it After that LDAR is picked up to load pipe and search older STLR same PA or different PA in STQ It is cancelled into LMQ again if there are older STLR exist in STQ And the LMQ state is set to wait STQ deallocate When the older STLR retires LDAR is picked up to load pipe again and cancelled again The LMQ state changes to wait SCB deallocate Younger STLR should wait older loads ordered before it retires So the younger STLR cannot retire into SCB before LDAR finished.
Agen resolve
Normal STLR act as normal store NCDEV STLR act as NCDEV store.
SCB multihit cancel.
If load matches two or more WB stores in SCB it is cancelled and wait STLR deallocate DCZVA may also cause SCB multihit with DCZVA cancel DCZVA cancel get higher priority than multihit cancel DCZVA itself is disqualified when calculating multihit So the pure multihit cancel can only be caused by STLR.
STAR
Atomic stores with release property may also send out preDMB STAR blocks its data retiring to SCB until preDMB operation finished.
WB ST WB ST hazard.
A normal store may not be able to merge into older normal store if STLR between them So a normal store can set hazard to another normal store of same thread.

##### 1.2.9.2 SCD 32B

SCD entry from 16B to 32B expand the scd data and bm to 256bit32bit.
In SCD 16B 1 SCA could link up to 4 SCD After supporting SCD 32B the max link SCD is reduce to 2.
SCD entry is configurable by 12 or 16.

##### 1.2.9.3 SCBL2C interface update

Introduction
SCB used to send requests to L2 and L2 response acceptreject signal 2 cycles later Now the interface is simplified to reqack handshake interface The acknowledge signal is set at the same cycle on request send.
SCBL2C interface
In the handshake interface protocol SCB send request to L2C at C1 stage and get acknowledge response at the same cycle So the interface design is simplified Reject and shadow reject logic is removed All request will not be rejected If L2C RSB full the next request is waiting at request head @ C1 stage Oldest SCB request is picked at CM1 stage and other information is selected at C0 stage.
When LSU receives L2C acknowledge it send next request to C1 interface The timing of acknowledge signal is not good So there are two pend buffers at stage C0 This pend buffer can cover 1 clock latency and resolve timing issue.
Request Swap
If L2 send no ready and the request is bank hazarded SCB takes 23 cycles to swap request When requests in C0 buffer and C1 stage are swappable the hazarded request at C1 stage can be replaced by later requests pending in C0 buffer Requests in C0 buffer and C1 stage cannot swap with unsent requests in SCB because this action takes a long time It isnt worth to do this for just a hint.
Request Cancel
Normal Read upgrade RdUpg requests may change to Read M request by snoop and Exclusive RdUpg request is cancelled by snpisnpci.
SCB SCA fsm
SCA fsm used to count q0 to q3 for L2 RSB reject Now these states are turn to hitsmisswaitL2ack state The state machine transfer from idlehitsmiss state to hitsmisswaitL2ack when this request is stored in pend buffer And the state transfer to hitsmisswaitL2 when L2C sent acknowledge @ stage C2 The hitsmisswaitrsbfree and hitsmisswaitrsbdealloc states are obsoleted too.
New state transfer diagram is shown below.
Oldest Request
LSU do not need to mark the oldest request to L2C So the whole oldest monitor module is useless And SCB do not send request with oldest mark.
FillSnoop data
Data in upper half line and lower half line is filled and snooped at same time So SCD fill merges of different half line occur at the same time That will ease the design complicity of SCD entries.
| 1210 | TLB |
| --- | --- |

###### 12101 Overview

###### 121011 Features

Target implementation TLB provides the following features.
| Feature | Description |
| --- | --- |
| Separate LDST TLB array | Separate LDST TLB array for 5 issue pipes Totally three 64-entry LD DTLB with 3 CAM ports shared by 3 load issue pipes and a 40-entry ST DTLB with 2 CAM ports shared by 2 store issue pipes |
| LDST TLB ctrl | With repartition the LDST TLB ctrl is also separatedEvery pipe has its own tlbctrl |
| TLB update | LDST TLB store different translation for each issue pipe respectively As a result LDST TLB use separate LRU control modules and updated separatelyAnd store fill is onr cycle solwer than load pipe |
| Dirty Bit Modify DBM | Both of the LDST TLB need the Dirty Bit Modify flow DBM The atomic instruction is in load pipe |
| TLBI | TLBI need to go through all the copies of TLB |
| Fault report | Fault ctrl is per issue pipes and fbuf and fmon is shared |
| TLB prefetch | TLB prefetch needs to be separated with LDST type and different type go to different TLB and load pf go to lda0 store pf go to sta0 New requirement for HWP |
| TLB miss generate flow simplification | simplification the merge and age compare logic of MRB |
| AT path merge with TLB miss path | merge AT request path with miss |

Fault response can be merged but write PAR path not merge.
Mmu fill remove f0 under evaluation.
L2 early wakeup remove f2 under evaluation.
| move VA source add to E1 | Based on Timing |
| --- | --- |
| 16k merge 64k | add 16k merge 64k besides current 4k merge 16k |
| PANEPAN | Enhanced PAN is added for AArch64 controlled stage 1 translation regimes |

Table 1 High Level Features.
930 TLB provides the following features.
| Feature | Description | Status | Change from R1 |
| --- | --- | --- | --- |
| Many TLB entries | Support up to 256512 entry DTLBFA128 | Baseline | Yes |
| 2 Level TLB | 2 level TLBuTLB + mTLB to balance the power and area | Baseline | Yes |
| Support all page sizes | Support all kinds of page sizes 4K16K64K256K2M32M512M1G | Baseline | No |
| Support RME | Support V9 arch feature FEATRME | Baseline | Yes |
| Support XS | Support V9 arch feature FEATXS | Baseline | Yes |
| Relax the TLB force miss timing | Timing opt for pgen signal Decouple the TLB force miss flow with the pgen valid | Baseline | Yes |
| Use Base Address to lookup uTLB | Use base addresssrca or srcb to lookup uTLB to relax timing | Baseline | Yes |
| Enhanced XP flow | Speed up the XP flow | Baseline | Yes |
| Support TLB ramidx | Support TLB ramidx | Baseline | Yes |
| Relax Multiuop fault reporting | Relax the multiuop fault report ordering requirements Simplify the tlb fault control flow | Drop | Yes |

###### 121012 Interfaces

Figure 1 below provides an overview of the TLB interfaces with other modules Each row represents a group of interface signals for a particular function Refer to the 13102 chapter for more details on interface signal names and behaviors.

#### Figure 1 TLB interface

TLB has the following interfaces with MMU Refer to 511 for more details.
| MMU Interface Group | Description |
| --- | --- |
| TLBREQ | When the DTLB does not contain a translation for a particular address it sends a TLB miss request to MMU to obtain the translation |
| TLBFILL | Once MMU has retrieved the translation for a TLB fill request it sends this back to the DTLB |

Table 2 Interface with MMU.
Structure

###### 121013 Basic Diagram

RTL hierarchy
Block diagram

###### 121014 Main Storage Description

| L1 DTLB | Comment |
| --- | --- |

3 LD TLB
| Entries | 16 |  |
| --- | --- | --- |
| Entry width | 101 bits | 51b Adder CAM entry + 50b RAM entry |
| Ports | 3R 1W 3C | RD E1 IEX lda0 issueE1 IEX lda1 issueE1 IEX lda2 issue |

WR TLB fillmtlb update.
CAM E1 IEX lda0 issueE1 IEX lda1 issueE1 IEX lda2 issue.
2 ST TLB
| Entries | 16 |  |
| --- | --- | --- |
| Entry width | 101 bits | 51b Adder CAM entry + 50b RAM entry |
| Ports | 3R 1W 3C | RD E1 IEX sta0 issueE1 IEX sta1 issue |

WR TLB fill
CAM E1 IEX sta0 issueE1 IEX sta1 issue.
1 MTLB
| Entries | 128 |  |
| --- | --- | --- |
| Entry width | 101 bits | 51b Adder CAM entry + 50b RAM entry |
| Ports | 1R 1W 1C | RD ldst utlb miss |

WR TLB fill
CAM ldst utlb miss.
| TOTAL SIZE | 11128 bits |  |  |  |  |  |
| --- | --- | --- | --- | --- | --- | --- |
| Field | Field | Width | RD | WR | CAM | Comment |

TLB CAM
| tid | [49] | 1 | TLB fill | E1 IEX lda0 issue |
| --- | --- | --- | --- | --- |

E1 IEX lda1 issue
E1 IEX lda2 issue
E1 IEX sta0 issue
| E1 IEX sta1 issue | The stread ID of ins |  |  |
| --- | --- | --- | --- |
| cnp | [48] | 1 | Common not private |
| fillmerged | [47] | 1 | This dat is 16k merged |
| va[4812] | [4610] | 37 | VA page |
| pagemerge | [9] | 1 | When EL1 TLBI by VA all the pagemerge valid entry is invalid |
| asididx | [78] | 2 | ASIDindex |
| ng | [6] | 1 | Not Global bit |
| mmutype | [53] | 3 | 00 EL0EL1 with s1off |

01 EL0EL1
10 EL2
11 EL3
| pagesize | [20] | 3 | 000 4K |
| --- | --- | --- | --- |

001 >16K
010 >64K
011 >2M
100 >32M
101 >512M
110 >1G
111 not allowed
TLB RAM
| pa[4012] | [4820] | 37 | E1 IEX lda0 issue |
| --- | --- | --- | --- |

E1 IEX lda1 issue
E1 IEX lda2 issue
E1 IEX sta0 issue
| E1 IEX sta1 issue | TLB fill | PA page |  |
| --- | --- | --- | --- |
| pbha | [1918] | 2 | Pagebased hardware attributes |
| stage2fwb | [17] | 1 | Stage2 is force writeback |
| stage1aptable | [16] | 1 | Access permission table is set |
| stage2dbm | [15] | 1 | Dirty bit modify in stage2 |
| stage1dbm | [14] | 1 | Dirty bit modify in stage1 |
| shareability | [13] | 1 | 0 nonshareable |

1. shareable

| stage2permission | [1211] | 2 | NSEL01 |
| --- | --- | --- | --- |

### 0.0 no read access permission

| 01 | RW |  |  |  |  |  |
| --- | --- | --- | --- | --- | --- | --- |
| 10 | RO |  |  |  |  |  |
| 11 | RO |  |  |  |  |  |
| epan | 10 | 1 | epan |  |  |  |
| stage1permission | [98] | 2 | EL0 | EL1 | EL2 | EL3 |

### 0.0 none RW RW RW

### 0.1 RW RW RW RW

### 1.0 none RO RO RO

### 1.1 RO RO RO RO

| stage1ll | [76] | 2 | Stage 1 level for DFSC |
| --- | --- | --- | --- |

00 level 0
01 level 1
10 level 2
11 level 3
| Stage2ovrf1 | [5] | 1 |  |
| --- | --- | --- | --- |
| memattr | [40] | 5 | Memory attributes |
| TOTAL | 107 |  |  |

###### 121015 Implementation Description

| 1 | Separate LDST UTLB array for 5 issue pipes |
| --- | --- |
| a | 16 entry fully associative LD TLB array with 3 CAM ports is shared by 3 load issue pipe support 4 kinds of page sizes And its shared by the 2 threads According to the common not private |
| b | 16 entry fully associative ST TLB array with 2 CAM ports is shared by 2 store issue pipe support 4 kinds of page sizes And its shared by the 2 threads According to the common not private |
| c | 128 entry fully associative MTLB array with 1 CAM ports is shared by 2 store issue pipe support multiple page sizes And its shared by the 2 threads According to the common not private |
| d | Latch based TLB |
| 2 | Separate LDST TLB ctrl |
| a | With repartition the LDST UTLB ctrl is also separated Instance 3+2 tlbctrl module for each pipe line And use a tlbctrltop handle the tlbctrl of 5 issue pipes |
| b | Local signal are generated in tlbctrl such as system reg select based thread and signal flop etc |
| c | Some global signal processed in tlbctrltop such as system state combine in each thread |
| d | After separate the LDST issue pipe is not allowed to merge OR record both ldst update info in MRB |
| 3 | TLB update |
| a | LDST UTLB store different translation for each issue pipe respectively As a result LDST UTLB and MTLB use separate LRU control modules and updated separately |
| b | Unifiedly controlled by MRB |
| 4 | Dirty Bit Modify DBM |

Both load TLB and Store TLB has the DBM.
| 5 | TLBI |
| --- | --- |

Cleared the whole uTLB when TLBI sync vld arrivesTLBI not use to access uTLB.
The TLBI request is involved in the mTLB port arbitration and has the highest priority.
Win arbiterreadcompareclearuse the TLBI flow the same as R1.
| 6 | Fault report |
| --- | --- |
| a | Fault ctrl is per issue pipes Each pipe has its own fault ctrl logic to detect the faults |
| b | fbuf and fmon is shared by 5 issue pipes fbuf store the fault filled from mmu and fmon find the oldest fault of each thread to report |
| 7 | TLB prefetch |

TLB prefetch needs to be separated with LDST type and different type go to different UTLB IF UTLB miss access MTLB.
| 8 | TLB miss generate flow simplification |
| --- | --- |

4. entry 4 outstandingE3 send miss req to mmu.

One cycle just one miss req to allocate mrbIf an AT instruction is encountered at same cyclethe miss req consider that the mrb is full.
A normal miss req need to pass three levels check before allocating MRB.
Firstly miss req check mrb is full or not.
Secondly compare mrb entry whether need merge.
Thirdly make sure which mrb entry can allocate.
| 9 | AT path merge with TLB miss path |
| --- | --- |
| a | Merge AT request path with miss request path |
| b | Fault response path can be merged with fault report path But write PAR path not merge and do by MMU |

### 1.0 16k merge 64k

Add 16k merge 64k besides current 4k merge 16k Cam[48] is reused by 4k merge 16k and 16k merge 64k mhit does not need to be changed with the new feature.

### 1.1 EPAN

PAN not only decided by APbut also by the XN Bit at the same time.

###### 12102 Interface Requirement

###### 121021 Cross Box requirementto OEXL2CMMUFSU

Interface to MMU
| MMU Interface Group | Description |
| --- | --- |
| TLBREQ | When the LSU TLB does not contain a translation for a particular address it sends a TLB fill request to MMU to obtain the translation AT request port is merged with TLB miss port |
| TLBFILL | Once MMU has retrieved the translation for a TLB fill request it sends this back to the TLB |

##### 1.2.1.1 MDB target core move to OEX

###### 12111 Features

MDB provides the following featureschanges against 910.
| Feature | Description |
| --- | --- |
| 64 ent fullyassociative | 1way64set |
| 3 delta 1mega | Each entry record 3 delta after reach 3 delta use mega to let load waiting for all older stores |
| Shrink delta | Shrink the 10 bit full delta to 5 bit If the real delta exceed 5-bit set mega vld instand |
| Support SMT | Shared in SMT |
| Use hash PC | Hash PC Indexing PC[120] |
| Support MDB SnR schema | Special Optimization for Save and Restore functions prevent load bypass store in this cases |
| Add mdbstpc | Save store stpcooo when hit mdb compare mdbstpc with ooostpc only if mdbstpcooostpc then is the real hit |

Table 1 High Level Features.

###### 12112 Interfaces

###### 121121 MDB Interfaces

Interface with Load pipe.
| Load pipe | InOut | Description |
| --- | --- | --- |
| Pipe012ophashpce1[120] | In |  |
| Pipe012ldyostptre1[90] | In | Change to SetWay structure the logic level is reduced Do the SID calculation at E1 |
| Pipe012ldmdbrdene1 | In |  |
| Pipe012optide1 | In |  |
| Pipe012ldfwddisablee1 | In | prfm ldodb ldstx is not allowed ld st forward need to disable the MDB |
| Pipe012ldmdbsid1vlde2 | Out |  |
| Pipe012ldmdbsid1e2 | Out |  |
| Pipe012ldmdbsid2vlde2 | Out |  |
| Pipe012ldmdbsid2e2 | Out |  |
| Pipe012ldmdbmegavlde2 | Out |  |

Interface with LHQ.
| LHQ interface | InOut | Description |
| --- | --- | --- |
| Lhqmdbnukedstaselt01e5 | In |  |
| Lhqmdbnukedvldt01e5 | In | The candidate stld nuke send to OOO Write into nuke buffer first waiting for nukeflush comes Only the oldest ld pass st write into MDB |
| Lhqmdbnukedldridt01e5 | In |  |
| Lhqmdbnukedldyostptrt01e5 | In |  |

Interface with OOO.
| Load pipe | InOut | Description |
| --- | --- | --- |
| Ooolsumdbsttids2 | In |  |
| Ooolsumdbstvld0s2 | In |  |
| Ooolsumdbstvld1s2 | In |  |
| Ooolsumdbstvld2s2 | In |  |
| Ooolsumdbstvld3s2 | In |  |
| Ooolsumdbstvld4s2 | In |  |
| Ooolsumdbstvld5s2 | In |  |
| Ooolsumtpart | in |  |
| Ooolsunukeflushr4 | In |  |
| Ooolsupstatessbst0 | In |  |
| Ooolsupstatessbst1 | In |  |
| Ooolsusysclkent0 | In |  |
| Ooolsusysclkent1 | In |  |
| Ooolsut0act | In |  |
| Ooolsut0en | In |  |
| Ooolsut1act | In |  |
| Ooolsut1en | In |  |
| Ooolsumdbstpchasn0s2 | In |  |
| Ooolsumdbstpchasn1s2 | In |  |
| Ooolsumdbstpchasn2s2 | In |  |
| Ooolsumdbstpchasn3s2 | In |  |
| Ooolsumdbstpchasn4s2 | In |  |
| Ooolsumdbstpchasn5s2 | In |  |
| Lsuooomdbstpcclkent0 | out |  |
| Lsuooomdbstpcclkent1 | out |  |

Interface with SNR.
| Load pipe | InOut | Description |
| --- | --- | --- |
| Pipe012ldmdbsid1vlde2 | In |  |
| Pipe012ldmdbsid1e2 | In |  |
| Pipe012ldmdbsid2vlde2 | In |  |
| Pipe012ldmdbsid2e2 | In |  |
| Pipe012ldmdbmegavlde2 | In |  |

###### 121122 SNR interfaces

Interface with mdb ctrl.
| Mdb ctrl | InOut | Description |
| --- | --- | --- |
| Pipe012ldmdbsid1vlde2 | Out |  |
| Pipe012ldmdbsid1e2 | Out |  |
| Pipe012ldmdbsid2vlde2 | Out |  |
| Pipe012ldmdbsid2e2 | Out |  |
| Pipe012ldmdbmegavlde2 | Out |  |

Interface with stqltag.
| Stq ltag | InOut | Description |
| --- | --- | --- |
| Pipe012ldmdbdelta123faile2 | In |  |
| Pipe012ldmdbmegafaile2 | In |  |
| Pipe012ldmdbdelta123vlde2 | Out |  |
| Pipe012ldmdbdelta123e2 | Out |  |
| Pipe012ldmdbmega vlde2 | Out |  |
| Pipe012ldmdbsnrside2 | Out |  |

###### 12113 Structure

LSU has a flopbased Memory Disambiguation Buffer MDB shared by 2 threads which tracks loads that resolved returned data ahead of older stores to same address and had to be flushed Each entry corresponds to one load that had to be flushed and contains pointers to up to 2 older stores that caused the flush The MDB is Indexing by the load hash PC[40] and use hash PC[125] as tag Each load checks for valid store pointers in MDB and then waits to execute until those stores generate their addresses The following tables show the structure and entry format of the MDB.
| 121131 | MDB |
| --- | --- |

MDB is a shared structure not duplicated in each LDU.
| MDB | Comment |  |
| --- | --- | --- |
| Entries | 64 ent fullyassociative | 1way64set |
| Entry width | 48 bits | 1-bit delta1vld |

5-bit delta1
5-bit delta1stpc
1-bit delta2vld
5-bit delta2
5-bit delta2stpc
1-bit delta3vld
5-bit delta3
5-bit delta3stpc
1-bit megabitvld
13-bit entrypc
1-bit tid
| Ports | 4R 1W | RD E1 pipe0 load |
| --- | --- | --- |

RD E1 pipe1 load
RD E1 pipe2 load
RD R5 store load nuke.
WR R6 store load nuke.
| TOTAL SIZE | 3072bits |
| --- | --- |

###### 121132 MDBSTPC

Store PC is an identifier of the store uop Different stores would typically have different PCs.
General algorithm steps.
| 1 | When adding a new delta to MDB save store hashpc[40] |
| --- | --- |
| 2 | When looking up a load in the MDB a hit on a certain delta can happen only if the saved store hashPC[40] of a delta matches that of a currently active store at the delta location |

Since proposed mechanism is reducing the number of MDB waits and is expected to also not wait in cases it should because 5 bits of the PC is not enough and because store PC is not the only factor that should be considered when deciding whether to cancel a wait then number of nukes on account of a certain load is expected to increase For this reason it is desired to add another delta to the current number having a total of 3 deltas to avoid a lot of cases where mega indication is set.

- Algorithm implementation.

| 1 | To each MDB entry add 3 new fields storepc1 storepc2 storepc3 fields |
| --- | --- |
| a | Field has 5 bits that account for store hashpc[40] |
| 2 | @MDB update new delta addition |
| a | Save store hashpc[40] in appropriate storepc according to delta index If Delta already exists and storepc for that delta does not match need to allocate new delta |
| 3 | Add a new per thread MDB store PC buffer |
| a | Number of entries is STQsize + 2 storeissueqsize This number should prevent overflow conditions and account for all stores between issue and STQ retirement In previous-generation core this equals 48 + 2 16 80 |
| b | Each entry in the buffer has 5 bits that account for store hashpc[40] |
| c | 4 or 6 write ports S2 continuous |
| d | Buffer is never flushed and no need to recover on nuke branch misprediction |
| e | Location of buffer should be in LSU close to MDB so it can be read with good timing |
| 4 | @store dispatch |
| a | Save hashpc[40] in buffer entry SID MDBstorePCbuffersize since dispatch is done in order writes to the buffer are in order |
| b | Maximal writes to MDB store PC buffer should be maximal number of dispatched stores per cycle Inline writes should be allowed |
| 5 | @MDB load lookup |
| a | If entry has mega bit set no change to current MDB behavior load waits for YOST |
| b | If mega bit NOT set Generate SID from YOST and delta |
| c | Compare hashpc[40] from MDB entry to hashpc[40] from MDBstorePCbuffer read from entry SID MDBstorePCbuffersize |
| d | If match MDB delta hit |
| e | Else MDB delta miss |
| f | NOTE Since there are 3 load pipes and 3 deltas per load in MDB buffer total number of read ports from MDBstorePCbuffer is 3 3 9 This is hard to support timing wise since MDBstorePCbuffer can be read only after SID from delta is calculated This adds another read that eventually is responsible for possible load cancel generation |
| g | Because of this timing issue MDB cam will be moved to I2 pipe stage Following is the MDB pipe for loads |
| i | I2 cam MDB and calculate valid deltas |
| ii | E1 Read virtual STQ PC from MDB entry and store PC buffer |

E2 compare PC from MDB entry to PC from store PC buffer and generate cancel condition signal based on age comparison and PC comparison.
STPC entry num and structure.
Mdb entry num and structure is related to the stq entry number As mentioned above STPCSIZE STQsize + 2 storeissueqsize.
Every bank has 8 entrys.
When STQsize32the stpc will be like.
When STQsize40the stpc will be different between single thread and two threads.
When STQsize48the stpc will be different between single thread and two threads.

###### 121133 SNR Save and Restore Extension

#### Motivation

In Linx920 when switching to 3LDUs scheme Store load nukes Memory Disambiguation nukes become more frequent.
Examination of CA model runs of internal benchmark suite SPECINT benchmarks have identified a recurring SW pattern that is responsible for 50 of store load nukes observed for this benchmark that have shown to be removed once new algorithm has been modeled in CA model.
It is very beneficial to avoid these nukes for both performance gain and MDB size maintenance.

#### Algorithm general

New algorithm aims to identify store and load pairs based on a marking mechanism that would mark loads with the store ID of the store that it should not bypass before PGEN This is done in the decode pipe This marking is part of the uop payload and should accompany the uop while still not past MDB in the LSU pipe In the MDB such a marked load is treated as a load that hit the MDB and the store ID actually this is a delta like legacy MDB entry holds is used by MDB to cancel the load in case the store denoted by that ID is not PGEN yet.
New algorithm is composed of 3 components.
| 1 | Stores registration |
| --- | --- |
| a | Done @ decode dispatch pipe after store ID is valid |
| b | Identify suspicious stores and register store ID in a dedicated buffer entry |
| 2 | Loads marking |
| a | Done @ decode dispatch pipe after yost Youngest of Older Stores is valid |
| b | Lookup relevant entry in buffer if valid calculate snrdelta yost minus buffer entry store ID append this delta to uop payload and mark this delta as valid extra valid bit needed in payload |
| 3 | Loads MDB extension |
| a | Done @ LSU E2 stage in MDB lookup logic |
| b | If uop snrdelta is valid treat this load as MDB hit with delta snrdelta |

#### Algorithm implementation details

#### Stores registration

When a store to SP of a register in the range X19X30 ie suspicious store is in the decode pipe registration is done according to the following FSM.
Start in Idle state.
If instruction is bl blr go to Armed state.
Else stay in Idle state.
Armed state
If instruction is ret go to Idle state.
If instruction is str stp and stored register is in range [X30X19] and storing to SP go to Register state and register this store ID in entry number src X19 in SNR store buffer For example if store is saving X24 then it will write store ID to entry 24 19 5.
If instruction is bl blr stay in Armed state.
Register state
If instruction is ret go to Idle state and flush SNR store buffer.
If instruction is bl blr go to Armed state and flush SNR store buffer.
If instruction is ldr ldp and destination register is in range [X30X19] and loading from SP stay in Register state and write store ID to relevant buffer entry as done for stores in Armed state above.
Above FSM is illustrated in the following FSM flowchart.

#### FSM notes

| 1 | FSM applies only to bl blr ret str stp instructions |
| --- | --- |
| 2 | Stp instruction would register up to 2 SNR store buffer entries according to stored X registers in the range of X19X30 |

#### SNR store buffer

Duplicated per thread.
Has 12 entries for indexing via X19X30.
Entry contains 10 bits of store ID and 1 valid bit.
Buffer is flushed on.

- Nuke from ROB
- Branch misprediction flush if branch is older than bl blr instruction.

#### Loads marking

When a load from SP to a register in the range of X19X30 is in the decode stage it would read the relevant SNR store buffer entry entry index calculated the same as for store if the entry is valid this means that an older store has written it and this load should be marked with the store ID written in this entry.
NOTE OOO send the marked SID to LSU at the dispatch phase use the LIQ preallocate schema directly send to LSU and in the real issue LSU read out the SID from the preallocate LIQ entry and do the load/store checking The detail interface is open item need more discussion with OOO.
NOTE Ldp Load Pair instruction that has 2 sources in the range of X19X30 will only read entry that is corresponding to source 1 This is acceptable because in the vast majority of the cases ldp is correspondent with stp of the same registers thus it is OK to check only one entry and mark this load for waiting only for a single store Feature does not support load marking on 2 store IDs.
As described above if relevant SNR store buffer entry is valid.
| 1 | Calculate delta according to yost youngest of older stores saved as part of load payload minus stid taken from SNR store buffer |
| --- | --- |
| 2 | Append calculated delta to lda uop and set snrdeltavalid bit for the |

#### Loads MDB extension

As described in Algorithm General section if the load is marked snrdelta is valid this load is treated as an MDB hit with delta nsrdelta If this load does not have a valid MDB entry MDB miss then load would be canceled only if snrdelta store is not pgen yet If the load hits the MDB then snrdelta is treated as an additional delta in the MDB entry treated as third delta.
If snrdelta marked load does not have a valid MDB entry it SHOULD NOT be inserted to the MDB Snrdelta marked loads can interact with valid MDB entry but will never generate a new entry for this load.

###### 12114 Pipelines

Load look up mdb
Update mdb

##### 1.2.1.2 Exclusive

###### 12121 Overview

LSU supports the LoadExclusive LDXR and StoreExclusive STXR instructions The general behavior is that a program executes LDXR to arm a hardware exclusive monitor with a particular cacheline PA for which the thread or core if singlethreaded has exclusive access If the thread then loses exclusive access to that cacheline PA the monitor transitions from exclusive state to open state After executing some other instructions the program executes STXR to check whether the exclusive monitor is still in exclusive state for the STXR cacheline PA If yes the store component of STXR is executed and STXR returns the passing status value 0 to the STXR destination register If not the store component of STXR is dropped and STXR returns the failing status value 1 to the STXR destination register.
| 12122 | LDXR |
| --- | --- |

LDXR flow and SMONCMON set.
| 12123 | STXR |
| --- | --- |

STXRWB flow
STLXRWB flow
STXRNCDEV flow
LDSTX flow

##### 1.2.1.3 Atomic

###### 12131 Overview

###### 121311 Scope

The scope of this document is to specify atomic instruction implementation details This document includes LSU internal structurepipeline changes main features involved and also defines the interface and interaction with OOO IEX L2L3.

###### 121312 Background

The atomic design for 910 is atomic instructions issue from load pipeline use MOB to keep atomics program ordering and tagged one outstanding atomic monitor to keep the life cycles of atomics.
In the target implementation design the MOB module will be removed need to reconsider the atomics order handling.
Three targets of the target implementation atomic design.
Atomics behave as stores keep ordering from store retirement is more natural.
Reduce atomics execution latency.
Support multiple outstanding atomics.
Note plan to support multiple outstanding atomics with two phases for the complexity phase 1 supports Stlike atomic multiple outstanding phase 2 supports Ldlike atomic multiple outstanding.

#### Figure 1 Atomics in 910 design

#### Figure 2 Atomics in target implementation design

###### 121313 Terminology

Table 1 Atomic Implementation Terminology.
| Terminology | Explanations |
| --- | --- |
| Ldlike atomic | LD<op> CASCASP SWP old data returns for destination |
| Stlike atomic | ST<op> no old data returns for destination |
| LDAT | Load ops generated from atomic inside LSU to get the old memory data |
| STAT | Store ops generated from atomic inside LSU to store new data for near L1 D write |
| FAR | Store ops generated from atomic inside LSU to form all data and op info from LSU to L2 |
| LDODB | The old data buffer to record the Loadlike atomic returned old data |
| AXE | Atomic execution unit for data processing |

###### 12132 Structure Description

###### 121321 Structure updating List

The main structures updating as following.
Remove LDAT

- Shorten the atomics latency.
- No LDAT writes STQ atomic yoest sid calculation and LMQ sid comparison.
- No STQ LDAT sidedoor to nuke younger loads.
- No younger loads wait for Atomic Monitor.

Remove Atomic Monitor.

- No separate flush.
- No extra snoop and fill acke windows handling.
- No cross thread interaction.
- Merge handing inside SCA and SCD to hook with AXE.

SCB

- Multiple outstanding atomics handling with hazards ordering.
- AXE with pull phase and push phase to reduce latency.
- AXE shared for 2threads.
- AXE new write data covers 32-bit write granule reduce one more Read from SCB.
- Ldlike atomics spec wakeup and late wakeup for ldodb.

###### 121322 Structure of AXE

The AXE module interacts with SCA SCD locally for controls and data moving The main functionality of AXE is data alignment shift endianess data calculations.

#### Figure 3 Data flows of AXE

###### 12133 Pipelines

Three main flows of atomics as following.
Near for L1 D hit
Far to near for L1 D miss then L2 decides near and fill with ownership.
Far for L1 D miss and L2 decides far handing from L3SoC.

###### 121331 Near Atomics

#### Figure 4 Near Atomics LDAT in 910 design

#### Figure 5 Near Atomics STP in target implementation design

###### 121332 Far to Near Atomics

#### Figure 6 Far to Near Atomics LDAT in 910 design

#### Figure 7 Far to Near Atomics STP in target implementation design

###### 121333 Far Atomics

#### Figure 8 Far Atomics LDAT in 910 design

#### Figure 9 Far Atomics STP in target implementation design

###### 12134 Main Features

###### 121341 SMT Handling

- Atomic SMT is same as normal store before retire its characters after retire as follows.

For near only 1 atomic around 2 thred can be excuted in AXE.
For far LSU can sent multireq to L2C but only 1 far atomic can be fill to LSU at the same time.

###### 121342 RAS Extension

All ras error of atomic is reported SEI.
Far atomic will match fill error in fdb and report SEI in RCE module.
Far to near atomic will hold fill error in each sca entry and report SEI in pull phase.
DC data ecc error will be detected in pull phase and report SEI.

###### 12135 Interface and Protocol

###### 121351 LSU<>OEX Side

Uop type updating refer to new Uop table.
Issue and resolve of atomics as following.
Issue as load > Issue as store.
Resolve as load > Agen resolve as store.
[Note] For phase 2 to support multiple outstanding Ldlike atomic need to refine the agen resolve and resolve interfaces basically Ldlike atomic includes agen resolve for pgenfault and resolve for ldodb data returns to release the retirement blocking.
| IEX>LSU Issue Interface Group | Signal Width | Description |
| --- | --- | --- |
| iexlsusta01ldmidxi2 | [50] | Atomic destination ldmatrixid |

###### 121352 LSU<>L2 Side

- The main updating is to support multiple outstanding of far to near fill.

| LSU>L2C Request Interface Group | Signal Width | Description |
| --- | --- | --- |
| lsul2catomicdonex0 | [00] | Atomic far to near done indication |
| lsul2catomicdoneidx0 | [60] | Atomic far to near done id for L2 entry releases |

The constraints and protocols with L2 as following.
L2 decides far or near with encoding exclok 2b10 for near excl ok 2b11 for far.
L2 responses with excl ok with grnt valid with grnt id same cycle.
L2 need to block same index and way snoop till LSU sends atomic done for respective id.
[Note] For Ldlike atomic multiple outstanding support may introduce more blocking mechanism for far fill or more arbitration protocols between L2.

###### 121353 LSU Far Atomic Data Format

###### 12136 Other Considerations

###### 121361 SWP with compare drop

###### 121362 AlwaysNear hint

The key points for AlwaysNear as following.
To support AlwaysNear need to add a group of interface to send RDMatomic request to L2C need to discuss with L2.
| LSU>L2C Request Interface Group | Signal Width | Description |
| --- | --- | --- |
| lsul2cp01reqatrdmc1 | [00] | Atomic always near indication only for RDM request |

When LSU get hits or miss LSU will trigger AlwaysNear process send RDMatomic.
In norm phase L2 must fill data with Estate to LSU and LSU start near process.
Need to add some configuration override bit to control weather open always near.
Sysdisatomicalwaysnear.
Sysdisatomicswpcasalwaysnear.
To avoid additional latency lost in special instruction SWAP and CAS LSU condition check failed an AlwaysNearfailcounter will be built in LSU LSU will close this feature when fail number is overflow In addition LSU also has some configuration override bits to control always near.
Sysdisatomicalwaysnear.
This configuration override bit is to disable atomic always near feature 1 means lsu do not send always near to L2C.
Sysdisatomicswpcashitsalwaysnear.
This configuration override bit is to disable swpcas atomic with hit S state send always near.
Sysdisatomicuncondmissalwaysnear.
This configuration override bit is to disable unconditional atomic with I state send always near.
Sysdisatomicalwaysnearthreshold.
The group of configuration override bits are to set always near disable threshold when always near counter is overflow in alwaysnearopen state lsu will disable always near feature.
Sysrecatomicalwaysnearthreshold.
The group of configuration override bits are to set always near recovery threshold when always near counter is overflow in alwaysneardisable state lsu will recovery always near feature.
Sysdisatomicspecsendrs.
Tis configuration override bit is to disable atomic ins send RDS prefetch from STQ.

#### Figure 10 AlwaysNear close and open condition

###### 12137 CAS SelfRu

###### 12138 Ldlike Multioutstanding

The phase2 is to support Ldlike atomic multiple outstanding some microarchitecture and resource need to be reconsider.
To earlier get commit information ldlike atomic also need to send agenresolve in stissuepipe so currently agenresolve code in store pipe for ldlike atomic need to be removed.
To avoid ldlike atomic blocked by retire r3 nst atomic do not consider check commit condition in retire r3.
In previous scheme ldtype atomic only has two group of resource for each thread this is a limitation for multioutstanding.
Far and near conflict L2 just fill 1 far to LSU so near ldodb has high priority than far.

###### 12139 Implementation Note

###### 121391 Implement Details

| 13 | Annex Terminology |
| --- | --- |

Abbreviations short list.
| a | DEV Loads Device |
| --- | --- |
| b | NC Non Cacheble |
| c | Ldat It is the atomics load part Its not sending request to L2 in 910 |
| d | Ldxr It is the load exclusive |
| e | Ldar It is the load acquire In 910 it will repick after the oldest release rid point to it |
| f | Prfm It is the SW prefetch instruction whose request type may be RDS or RDM |
| g | RDS It is the read share request type just request the S status |
| h | RDM It is the read modify request type It required the E status |
| i | RR It is the RoundRobin |
| j | MGW It is the misalign device |

MGW is the total size misalign device since the element size misalign device is treated as fault.
| k | Align DEV size and address align in the same cacheline |
| --- | --- |

- Misalign DEV size and address unalign in the same cacheline.
- cross cacheline.
- corss page

| l | Unnop Nop is empty instruction |
| --- | --- |

Unnop is the instruction except nop.
| m | Unnull Null is the one kind of SVE inst its predicate is all invalid It should treat as nop |
| --- | --- |

Unnull is the instruction except null.
