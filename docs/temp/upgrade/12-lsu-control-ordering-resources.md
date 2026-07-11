# LSU Control, Ordering, Resources, and Coherence

This document covers flush and no-flush procedures, ROB commit behavior, load/store ordering, LHQ ordering, cross-page and device flows, non-cacheable store flows, resources, coherence, and SVE store handling.

> This Markdown document is the maintained specification. Sensitive metadata and internal reference labels have been removed while preserving architecture semantics.

### 7.1 Flush Procedure LSU Control

The LSU receives two flushes the IEX branch flush at E2 stage and the OOO ROB flush at R2 stage Each flush carries the Thread ID TID and oldest ROB ID RID that must be flushed The ROB flush is always older in program order and has priority over a branch flush of the same thread in the same cycle.
The LSU merges the two flushes then samples the merged flushes to R3 stage and R4 stage An instruction whose RID is younger than or equal to the merged flush RID of the same thread must be invalidated.
For Loads at each stage of the load issue pipes and load result pipes carries its TID and RID On an R3 stage merged flush LSU compares the flush TIDRID against each pipeline stage TIDRID If the pipeline RID is younger than or equal to the flush RID of the same thread the load at that stage is invalidated.
Similarly each entry in the LIQ and LHQ has TID and RID fields On an R4 stage merged flush LSU compares the flush TIDRID against each entry TIDRID If the entry RID is younger than or equal to the flush RID of the same thread the entry is invalidated.
For Stores at each stage of the STASTD issue pipes carries its TID and RID On an R3 stage merged flush LSU compares the flush TIDRID against each pipeline stage TIDRID If the pipeline RID is younger than or equal to the flush RID of the same thread the STASTD at that stage is invalidated.
Similarly each entry in the STQ has TID and RID fields On an R4 stage merged flush LSU compares the flush TIDRID against each entry TIDRID If the entry RID is younger than or equal to the flush RID of the same thread the entry is invalidated.
For both load and store pipe Pipe Valid are flushed from I1 to E3.
Store Flush window.
Pipe can process flush at issue pipe from I2 to E4 issue I1 flush need to be coverd by IEXFSU.

### 7.2 Instruction Noflush

The ROB maintains a window of oldest instructions that can no longer trigger a flush This is called the noflush window Instructions that AGEN resolved at TLB translation for store or DEV load or fully resolved at load data return are eligible to enter the noflush window once all older instructions have AGEN resolved or fully resolved The boundary of the noflush window is the NOFLUSHRID The NOFLUSHRID is part of the interface from OOO to LSU The instruction at the NOFLUSHRID is not yet resolved This instruction cannot be flushed by ROB due to an asynchronous trigger such as interrupt but it can still resolve itself with a fault causing itself to be flushed.
The following diagram shows the noflush window and NOFLUSHRID in ROB.

#### Figure 6 4 ROB Noflush Window

For Loads the LIQ uses the NOFLUSHRID to determine when it can send a DEV load request to L2C When the NOFLUSHRID is younger than or equal to the DEV load RID and the DEV load RID matches the OLDESTUNORDEREDRID from LHQ the DEV load is eligible to send its request to L2C.
For stores the STQ uses the NOFLUSHRID to determine when it can retire a store instruction to SCB When the NOFLUSHRID is younger than or equal to the store RID and each of the store uops has obtained its PA with no fault detected the store is considered architecturally committed and can STA retire from STQ to SCB Additionally when each of the store uops has obtained its store data it can STD retire from STQ to SCB.

### 7.3 ROB Commit

The Instructions are Deallocated from ROB in program order as they fully resolve without fault This ROB dequeue operation is called ROB commit.
A load is considered ROB committed when ROB deallocates the load RID Load ROB commit does not trigger any LSU activity.

- For stores cnce a store instruction is ROB committed it can no longer be flushed The store RID becomes invalid and can be recycled by a younger instruction Each STQ entry keeps track of when its store is ROB committed so it can disable the flush RID comparison logic.

### 7.4 load/store Ordering

Before describing the separate load/store execution flows it is necessary to explain the ordering requirements that LSU must enforce across load and store instructions The following table provides a summary of load/store ordering requirements based on the program order and memory attributes of each instruction.
| Older Instruction A | Younger Instruction B | Summary of Ordering Requirements |
| --- | --- | --- |
| WB Load | WB Load | If Load B resolves first with older data and becomes stale snoop invalidated and Load A resolves later with newer data with PA match byte overlap then Load B must be nuke flushed |
| NC Load | No ordering requirements |  |
| DEV Load | Load A must resolve return data before Load B can send its request to L2C in case Load A has a data error that requires it to be flushed |  |
| WB Store | Load A must resolve return data before Store B can become globally ordered |  |
| NC Store | Load A must resolve return data before Store B can send its request to L2C |  |
| DEV Store | Load A must resolve return data before Store B can send its request to L2C |  |
| NC Load | WB Load | No ordering requirements |
| NC Load | Load A request must be RSB allocated before Load B can send its request to L2C |  |
| DEV Load | Load A request must be RSB allocated before Load B can send its request to L2C |  |
| WB Store | Load A must resolve return data before Store B can become globally ordered |  |
| NC Store | Load A must resolve return data before Store B can send its request to L2C |  |
| DEV Store | Load A must resolve return data before Store B can send its request to L2C |  |
| DEV Load | WB Load | No ordering requirements |
| NC Load | Load A request must be RSB allocated before Load B can send its request to L2C |  |
| DEV Load | Load A request must be RSB allocated before Load B can send its request to L2C |  |
| WB Store | Load A must be RSB allocated before Store B can become globally ordered |  |
| NC Store | Load A must be RSB allocated before Store B can send its request to L2C |  |
| DEV Store | Load A must be RSB allocated before Store B can send its request to L2C |  |
| WB Store | WB Load | Two cases when younger WB load B has PA match byte overlap with older WB store A |
| 3 | If store A reaches STQ PA array before load B CAMs STQ PA array load B detects STQ hit and STQ forwards store A data to load B |  |
| 4 | If load B CAMs STQ PA array before store A reaches STQ PA array load B detects STQ miss and may resolve return data Later when store A writes STQ PA array it CAMs LHQ detects LHQ hit on younger resolved load B and nuke flushes load B |  |
| NC Load | No ordering requirements |  |
| DEV Load | Store A must AGEN resolve detect faults before Load B can send its request to L2C |  |
| WB Store | Store A must AGEN resolve detect faults before Store B can become globally ordered |  |
| NC Store | Store A must AGEN resolve detect faults before Store B can send its request to L2C |  |
| DEV Store | Store A must AGEN resolve detect faults before Store B can send its request to L2C |  |
| NC Store | WB Load | No ordering requirements |
| NC Load | Store A must be RSB allocated before Load B can send its request to L2C |  |
| DEV Load | Store A must be RSB allocated before Load B can send its request to L2C |  |
| WB Store | Store A must AGEN resolve detect faults before Store B can become globally ordered |  |
| NC Store | Store A must send its request to L2C before Store B can send its request to L2C |  |
| DEV Store | Store A must send its request to L2C before Store B can send its request to L2C |  |
| DEV Store | WB Load | No ordering requirements |
| NC Load | Store A must be RSB allocated before Load B can send its request to L2C |  |
| DEV Load | Store A must be RSB allocated before Load B can send its request to L2C |  |
| WB Store | Store A must AGEN resolve detect faults before Store B can become globally ordered |  |
| NC Store | Store A must send its request to L2C before store B can send its request to L2C |  |
| DEV Store | Store A must send its request to L2C before store B can send its request to L2C |  |

Need to update for ldar and stlr missing in above.

### 7.5 LHQ Ordering

LSU uses its LHQ to help enforce ordering requirements across load/store instructions The LHQ has full observability of all loads and stores It serves as an extension of the ROB with effectively one entry per RID per thread Each LHQ entry tracks the status of an instruction from ROB allocate through ROB commit ROB deallocate.
Additional details on Resolve Pgen Ldxr Ordered Ncdev refer to previous-generation core LSU MicroArchitecturePipe Liu Wei.

##### 1.1.1.1 Load Ordering

The following table summarizes these oldest RID cases The sections below provide more details on how each oldest RID is generated.
| Oldest RIDLID Generated by LHQ | Description | Comment |
| --- | --- | --- |
| Oldest LIQ LID | LID of oldest load that still needs to use LIQ oldest unresolved load instruction | Total |
| Oldest Unordered LD LID | LID of oldest unresolved WB load or nonLFB allocated NCDEV load | Total |
| Oldest LDXR LID | RIDLID of oldest load exclusive instruction | Total |
| ldxOldest LIQ LID | Local LID of oldest load that still needs to use LIQ oldest unresolved load instruction | Local |

##### 1.1.1.2 Oldest LIQ LID

The LHQ generates OLDESTLIQLID by searching from oldest to youngest LID and finding the first entry with NEEDLIQ1 unresolved load.
NEEDLIQ LD resolved.

##### 1.1.1.3 Oldest Unordered Load LID

The LOAD generates OLDESTUNORDEREDLOADLID by searching from oldest to youngest RID and finding the first entry with UNORDEREDLOAD 1 UNORDEREDLOAD includes the following instructions.
Unresolved WB loads.
NCDEV loadsstores whose request was not yet RSB allocated in L2C.
LHQ sends OLDESTUNORDEREDRID to LIQ to enable NCDEV loads to send their requests to L2C Refer to the sections on NCDEV Load Flows for more details.
UNORDEREDLOAD LD WB resolved | NCDEV lfballocate.

##### 1.1.1.4 Oldest Load Exclusive LID

The LHQ generates OLDESTLDXRRID by searching from oldest to youngest RID and finding the first entry with LDXR1 One of the requirements for a load exclusive instruction to execute is that it must be the OLDESTLDXRLID Refer to the section on Load Exclusive Flows for more details.
LDXRLDXR | INV

### 7.6 CrossPage Load Translation Flow

This section describes the crosspage XP load translation flow This flow serves as the common starting point for all XP load cases including WB NC DEV and mixedattribute The two pages of a XP load are referred to as XP1 and XP2 The XP load flow must first obtain the TLB translation for the XP1 page then obtain the TLB translation for the XP2 page then finally the load can proceed with execution If a fault is detected on XP1 or XP2 the flow exits early and the load resolves with fault.
The following diagram summarizes the XP load flow.

#### Figure 5 4 XP Load Translation Flow

The XP load flow begins with E2 stage crosspage detection on IEX issued loads A load instruction is classified as crosspage if its VA plus total size crosses a 4KB boundary Base on uop to check cross page not instruction.
For multiuop load instruction each uop detect XP independently and based on checking if uops starting VA plus uop size crosses a 4KB boundary The cross page fault between uops is not checked.
If a fault is detected on the IEX issued load the XP detection is discarded and the load is resolved with fault This resolve can be sent on the first issue if the load won the resultpipe otherwise the load will allocate LIQ as needed to immediately repick and resolve with fault.
Once a load instruction is classified as crosspage it must check if its RID is the OLDESTLIQRID If not the load must discard its TLB hit translation if obtained and go to sleep in LIQ with its VA until it becomes the OLDESTLIQRID The reason for this is that each LIQ entry only has one PA[4812] field This field is sufficient for aligned loads and samepage misaligned loads but a crosspage load requires additional storage for its XP2 PA[4812] To save area LIQ only has a 1-entry LIQXP2PA flop To avoid contention a XP load can only use this LIQXP2PA when it is the OLDESTLIQRID oldest unresolved load.
On the IEX issue or LIQ reissue when the XP load is OLDESTLIQRID it will attempt the TLB translation for its XP1 VA LIQ reissued loads attempting XP1 translation will not arbitrate for resultpipe wakeup dependents since they cannot return data yet The XP load will go through the regular TLB miss handling flow of LIQ sleep and reissue as needed until either obtaining the XP1 PA from TLB hit or detecting a fault.
On a XP1 fault detected the XP load allocates LIQ as immediately ready to repick with the fault bit set Since the XP load is the OLDESTLIQRID there is a guaranteed reserved LIQ entry available to allocate At the same time the XP load LIQ entry is deallocated and the LIQ credit is returned to IEX On the XP load LIQ repick the load resolves with fault.
On a XP1 TLB hit the XP1 PAattributes are written to the LIQ entry for the XP load and the XP1 VA DEV and STAGE2DEV attributes are written to a 1-entry LIQXP1VA flop The LIQ entry for the XP load is set as ready to reissue to obtain the XP2 PA.
LIQ reissued loads attempting XP2 translation will not arbitrate for the resultpipe wakeup dependents since they cannot return data yet At the TLB sidedoor muxes the LIQ reissue for XP2 translation will select the XP1 VA from the 1-entry LIQXP1VA flop as srca and select 64h1000 as srcb Once again the XP load will go through the regular TLB miss handling flow of LIQ sleep and reissue as needed until either obtaining the XP2 PA from TLB hit or detecting a fault LIQ will provide TLB with the XP1 DEV and STAGE2DEV attributes on each LIQ reissue for XP2 translation so that TLB can detect additional faults on XP2 for mixedattribute cases Refer to the TLB appendix for the details of mixedattribute fault detection.
Similar to a XP1 fault on a XP2 fault detected the XP load allocates LIQ as immediately ready to repick with the fault bit set At the same time the XP load LIQ entry is deallocated and the LIQ credit is returned to IEX On the XP load LIQ repick the load resolves with fault.
On a XP2 TLB hit the XP2 PAattributes are written to the 1-entry LIQXP2PA flop The LIQ entry for the XP load is set as ready to repick One liq entry for one uop.
LIQ repick uses the misaligned and crosspage attributes to select the appropriate PAattributes for each uop Once uops have return data without cancel the XP load LIQ entry is deallocated and the LIQ credit is returned to IEX.

### 7.7 DEV Load Flows

### 7.8 NC Store Flows

#### 7.8.1 NC Aligned Store

#### 7.8.2 NC Misaligned Store Cross16B SamePage

#### 7.8.3 NC Misaligned Store CrossPage

### 7.9 DEV Store Flows

#### 7.9.1 DEV Aligned Store

DEV Aligned 64B Store.
DEV Misaligned Store Cross16B SamePage.
DEV Misaligned Store CrossPage.

#### 7.9.2 MixedAttribute Store Flows

#### 7.1.0 SVE Store Flows

#### 7.1.1 Atomic Flows

The general flow of atomic is as follows.

#### 7.1.2 Streaming Stores

#### 7.1.3 DCZVA Optimization

We propose DCZVA can lookup DC tag and not always send to L2 as NT mode in target implementation That make ldst after DCZVA can have better performance.
DCZVA performs as normal st can merge to other normal st and DCZVA with same PA Other st can merge to DCZVA at same And DCZVA need 2 std retire because it has 64bytes data.
After DCZVA allocate a SCA entry it lookup DC tag at first and determained to go as NT or rdm by the tag hit result.
If tag miss the DCZVA will execute as NT and send datless request to L2 with ZVA hint But if other st or ld hit the DCZVA before NT lock the DCZVA will transform to a normal st with 64 bytes 0 data and write to L1DC.
If tag hitwhether hits or hitm the DCZVA will transform to a normal st with 64 bytes 0 data directly And write L1Dc sent rdm to L2 firstly if hits.
DCZVA NT FSM
DCZVA SCA FSM
DCZVA SCD FSM

#### 7.1.4 Mem Copy

| 8 | LSU Interfaces |
| --- | --- |

Link to excel in lsuspeceach model is an excel svn.
The most updated excel is in HQ side different link.
[internal reference removed]
| 9 | SMT |
| --- | --- |

### 9.1 Resource

| allocate | dealloc | picker | prevent deadlock | STMT switch |
| --- | --- | --- | --- | --- |
| liq | in ST mode each thread could use the whole resources |  |  |  |

And in MT mode its shareable by Tx and Ty.
Both STMT the allocation is controlled by load matrix distinguishing Tx and Ty.
| No reserved entry load matrix make sure the oldest load could sent to LSU and has empty entries | 1 t0t1 reslove |
| --- | --- |

2. both 2 thread s flush is cleared any entry younger and equal to the flush rid.

| Released after flush | 1 t0 and t1 take turns to repick |
| --- | --- |

I2 picker

1. t0 repick vld and no t1 repick vld t0 repick.
2. t1 repick vld and no t0 repick vld t1 repick.
3. t0 repick vld and t1 repick vld and previous repick t1 t0 repick.
4. t0 repick vld and t1 repick vld and previous repick t0 t1 repick.

MA 2 blockage always has the highest pri.
Safe mode t0 and t1 safe mode t0 priority is higher than safe mode t1.
| No Reserved entry per thread load matrix mange the entries | ooo send the rob flush and cleared the lmq and start the need thread with RID0 |  |  |
| --- | --- | --- | --- |
| lfb | STMT shared by Tx and Ty |  |  |
| No reserved schema Use safe mode to block the younger load use LFB in the same Tx or the loads from Ty | L2 fill comesWB prfm L2L3 accept by L2 | Use the allocation order as age matrix | controlled by LIQ and use safemode |
| No reserved entry per thread | ooobru flush is not clear the lfb Wait the previous requests fill return |  |  |
| lhq | in ST mode each thread could use the whole resources |  |  |

And in MT mode its 5050 partition by Tx and Ty.
| use tx and ty lids sliding window to control | use t0t1 nextcommit rid to dealloc each lhq entry |
| --- | --- |

Committed ld number and lhq released the committed loads in one cycle.
| both 2 thread s flush is cleared any entry younger and equal to the flush lid Cleared in one cycle no credit return | NA | parition policy Use sliding window | ooo send the rob flush and cleared the lhq and start the need thread with RID0 |  |  |
| --- | --- | --- | --- | --- | --- |
| mdb | in ST mode each thread could use the whole resources |  |  |  |  |
| In MT mode its shareable by Tx and Ty | commit inst number periodical clear all |  |  |  |  |
| use modified lru stategy Only one thread is allowed to dealloc in one clock cycle | NA | NA | the exclusive entry will be replaced gradually |  |  |
| basicb misalign | Tx and Ty has separate1 entry static | resolveflush | each threads oldest MA or oldest WB load could use this entry | static entry relax the oldest use bab contraint in 910 | rob flush released the resources |
| basicb align | 4 entry NCDev |  |  |  |  |

In ST mode each thread could use the whole resources.
And in MT mode its shareable by Tx and Ty.
| Total credit is calculated without distinguishing Tx and Ty | The same idx LIQ t0t1 reslove or forward from fill pipe consider the merge their might multi LIQ use the same bab need to wait all of them is resolved |  |  |
| --- | --- | --- | --- |
| all the linked LIQs t0t1 are flushed and then the basicb is deallocated | NA | in function side it move to the misalign bab entries Oldest wb load use the ma bab entry | ooo send the rob flush and cleared the linked idx liq and then the basicb is deallocated |
| stq | In ST mode each thread could use the whole resources |  |  |

And in MT mode its 5050 partition.
| Each thread maintains a seperate sliding window of SIDs | Each thread retires in order of its own SIDs |
| --- | --- |

Each cycle can retire 2 entries of the same thread or each entry per thread.
Each thread s flush can clear all of its own entries which is younger or equal to the flush ridsid.
| And the flushed entry is cleared to idle | STQ retire picker |
| --- | --- |

There are 2 in order pickers for each thread.
Commonly sta0 is for T0 and sta1 is for T1.
If there is only one thread can retire it can use 2 pickers to retire Same cachelinewithin or between 2 threads will hazard in scb.
I2 pickerreissue
| has one picker per bank each thread select an oldest request and between 2 thread use RR | Maintains a TO mechanism among the 2 threads retire |
| --- | --- |

If a thread cannot retire for a configurable interval because of SCB resourcesthen disable the other threads STASTD retires for several cycles.
If the committed store STASTD cannot retire enter the stq safe mode block both threads ld.
| STASTD seperate | Need to wait for all of the pending entries have retired to SCB before mode switch |
| --- | --- |
| scb | In ST mode each thread could use the whole resources |

And in MT mode its fully sharedeach thread could use the whole resources if needed.

2. sta retire and 2 std retire.

| follow the stq retire order | Its SCASCD FSMs go to IDLECLEAN states | SCA scb tagreq |
| --- | --- | --- |

L1 miss request picker.
When the store reitre build up an global age matrix by retire order.
The L1 miss request is following the global age matrix.
But the hazard is decided in its own thread.
SCD scbrdscbwr0scbwr1.
The age is setup in the retire time.
L1 D readwrite picker.
| Use 2 X8 RR to pick 2 req from 16 scd The 1st RR pick req from [redacted numeric sequence] another RR pick req from [redacted numeric sequence] | The age matrix setup a cross thread order the SCB stravation is splitted by thread after the oldest one is blocked a long time it enter the st safe mode And block both threads ld | retired store is not affected by flush |
| --- | --- | --- |

SCB didnt have STMT switching.
| tlbmrb | fully shared by 2 thread |
| --- | --- |
| Use the single threads oldest reserved | dealloc when mmu fill or some 1-cycle delayed mrb flush due to timingfault |

Each thread s flush can clear all of its own entries which is younger or equal to the flush rid.
| And the flushed entry is cleared to idle | 1 Mrb > pipe |
| --- | --- |

2. pick oldest riduid one to send out tlb miss request.

| 3between thread it use RR | light weight safemode |  |  |
| --- | --- | --- | --- |
| If mrb full and the oldestnextcommit rid has tlb miss and rejected by a certain numbers Then block the younger load/store allocate MRB | before switching it has flush and cleared all the entries |  |  |
| result pipe | NA | NA | I2 picked |

Repick > issue
Add the D data access arbiter rule.
| LFSR random control between pipes still need repick > issuestill under perf eval | NA | NA |  |  |  |
| --- | --- | --- | --- | --- | --- |
| load MGW | Static FSM for Tx and Ty | resolve |  |  |  |
| could be flushed by nocmt flush | oldest Dev use MGW | NA | NA |  |  |
| store MGW | shared by now RR use | MGW GO | NA | mgw store only wait for scbdownstream ready then could use MGW engine If it still depends on noflushscatter xp bufferother stq ready not allowed to use mgw engine | NA |
| load XP buffer | One static entry for Tx and Ty | NA | NA | For gather load use by uid number for 07 in order | NA |
| store XP buffer | One static entry for Tx and Ty | NA | NA | except scatter only 1 XP per SIMDSVE | NA |
| Scatter XP buffer | shared by 2 thread RR use | NA | NA | need to wait for dgen when scatter store go to afifohead | NA |

### 9.2 Coherence

#### 9.2.1 Introduction

SMT coherent mainly need to handle the RAR order between threads and maintain the exclusiveatomic status between threads.

#### 9.2.2 Protocol

Remove TU set and XTS L1V snp changed to XTS PA snp.
Load XT hit store STQ SCB set XTS bit.
XTS store GO scb write D successfully inject L2V snp PA based to LHQ snp nuke XT load.
For timing XTS store only block XT load in Finite cycles and the blocked load will repick immediately.
Coherence stage
Stage1 XT younger load get old data.
Stage2 XT store GO WR D or Fmerge block XT load resolveload cancel.
Stage3 XT store inject L2V snp to LHQ nuke XT load.

- XT load get new data.

#### 9.2.3 XTS SET

Load hit stq and set XTS at STD r4.
Load hit stq from sta pipe E2 to sta retire R4 and XTS Write SCB at sta retire R5.
When load hit sta retire R5 the load is cancelled and repicked immediately.
L1 WR NT L2 send L2V SNP to lsu.
Sta cam LHQ set XTS.
Sta cam load result pipe E4 and LHQ entry and set XTS at sta issue pipe E4 or E5 for timing.
STQXTS for ma
There are two bits information for STQXTSMA1 and STQXTSMA2.
For cross cacheline stores STQXTSMA1 and STQXTSMA2 are written into different SCA when they retired If MA12 in same cacheline STQXTSs are written into one SCA entry.
For XP stores STQXTSMA1 and STQXTSMA2 are written into different SCA when they retired For XP2 if xt load va[116] 6b0 XTS set.
Load hit scb and set XTS.
Load hit SCB set XTS before Idlehitm I2.

#### 9.2.4 Nuke SET

Scb write D and inject L2v Snp to LHQ.
Store GO at E2 and inject L2v snp C6 to LHQ.
If store wr D E1 conflict with L2V SNP C5 L2V SNP C5 1 XTS store wr cancel and rewrite immediately.
Store wr D conflict with XTS fmerge XTS store wr is done and fmerge is cancelled.
Scb fill merge and inject L2v snp to LHQ The injected XTS snoop has lower priority than L2V snoop It need to find an empty timing slot to insert.
When XTS store fill merge it will inject L2v snp C6 to LHQ at fill C8 when fill merge C7 not conflict with L2V SNP C5 L2V SNP C5 0.
When fill merge C7 vs STA xts retire merge R5 fmerge done and send xts nuke.
Scb fill merge and cannot inject L2v snp to LHQ many timesXTS nuke XT oldest unresolved loads.
If fill merge C7 conflict with L2V SNP C5 L2V SNP C5 1 XTS store cancel fill merge and write D again.
The l2v snp vld c4 is almost accurate and the signal would not be stuck at 1b1 long times do not need a safe mode to make sure the xts snp could be asserted.
XTS L2v snp clear SCB XTS bit of the matched cache line XTS DMB Nuke snp clear all xts bits of the write data include in scd and pend buffers of the same thread.

#### 9.2.5 Load XT cancel

XTS local tag r5 cancel load.
XT load cancel and imm repick.
XTS SCA write D cancel XT load.
Load E2 xts hit sca Idlehitm I2 and xts bit is unset load cancel and imm retey.
Load E2 xts hit sca wr gnt E1 load cancel and the load imm retry.
Load E2 xts hit sca wr E2 load cancel and the load imm retry.
XTS SCA fill merge cancel XT load.
Load E2 xts hit sca fill merge C6 C7 C8 C9 load cancel and imm repick get new data.
| 10 | SVE |
| --- | --- |

Not Ready yet

#### 1.0.1 SVE Load

##### 1.0.1.1 SVE contiguousstructure Load

###### 10111 SVE nop uop flow

Newly add a Nop flow similar to tlb fault flow.

###### 10112 SVE normal flow

###### 10113 SVE fault nuke flow

##### 1.0.1.2 SVE Gather flow

SVE gather instruction last flag generate.

##### 1.0.1.3 SVE FFNF flow

###### 10131 SVE contiguous FFNF

SVE Contiguous FFNF.
When FFR fault happened FFR bitmask masked elements result data is zero elements which not masked by FFR bitmask is right value from mem.
| SVE | MA | XP | SP fault | TLB fault1 | TLB fault2 | Fault | FFR fault | FFR index |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NF | 01 | 0 | 1 | X | X | 1 | 0 | X |
| NF | 01 | 0 | 0 | 1 | X | 0 | 1 | First active element |
| NF | 1 | 1 | 1 | X | X | 1 | 0 | X |
| NF | 1 | 1 | 0 | 1 | X | 0 | 1 | XP1 First active element |
| NF | 1 | 1 | 0 | 0 | 1 | 0 | 1 | If ma1nulXP2 First active element |
| NF | 1 | 1 | 0 | 0 | 1 | 0 | 1 | If ma1vld and msize cross xpmaXP1 last active element |
| FF | 01 | 0 | 1 | X | X | 1 | 0 | X |
| FF | 01 | 0 | 0 | 1 | X | 1 | 0 | X |
| FF | 1 | 1 | 1 | X | X | 1 | 0 | X |
| FF | 1 | 1 | 0 | 1 | X | 1 | 0 | If XP1 has First active element |
| FF | 1 | 1 | 0 | 0 | 1 | 0 | 1 | If XP1 has First active elementreport XP2 First active element |
| FF | 1 | 1 | 0 | 0 | 1 | 1 | 0 | If XP2 has First active element |
| FF | 1 | 1 | 0 | 1 | X | 1 | 0 | If First active element cross XP |
| FF | 1 | 1 | 0 | 0 | 1 | 1 | 0 | If First active element cross XP |

###### 10132 SVE gather FF

SVE Gather FF
When FFR fault happened the element has FFR fault result data is zero the element which normal complete result is right value.
The difference is due to gather loads each result data resolved in separate time and do not know other elements status.
SVE Firstfault vector load instructions can access Device memory only for the First active element.
| first active element | 2nd active element | 3rd active element | other active element | LSU uArch behavior |  |
| --- | --- | --- | --- | --- | --- |
| First fault load | Device No fault | Device No fault | Device No fault | Device No fault | 1 send out first active element address access |

2send out the bytes that are in a 32Baligned window of first active element address.

3. if the 2nd3rdother active element address is not in the 64Baligned window of first active element address.

- 31 it will not send out.
- 32 report in FFR from 2nd element.

| First fault load | Device or Normal fault | Device No fault | Device No fault | Device No fault | 1 take exception |
| --- | --- | --- | --- | --- | --- |

2. No device access be sent out.

| First fault load | Device or Normal fault | Normal No fault | Normal No fault | Normal No fault | 1 take exception |
| --- | --- | --- | --- | --- | --- |

2. Normal memory access will be sent out.
3. Normal memory will fill right data.

| First fault load | Device No fault | Device or Normal fault | Device No fault | Device No fault | 1 first active element device access will send out |
| --- | --- | --- | --- | --- | --- |

2. 2nd3rdother active element will not send device access.
3. report in FFR from 2nd element.

| First fault load | Normal No fault | Device No fault | Device No fault | Device No fault | 1 first active element normal access will send out |
| --- | --- | --- | --- | --- | --- |

2. 2nd3rdother active element will not send device access.
3. report in FFR from 2nd element.

| First fault load | Normal No fault | Normal No fault | Device No fault | Device No fault | 1 first2nd active element normal access will send out |
| --- | --- | --- | --- | --- | --- |

2. 3rdother active element will not send device access.
3. report in FFR from 3rd element.

| Non Fault Load | Device No fault or Fault | Device No fault or Fault | Device No fault or Fault | Device No fault or Fault | 1 device access will not be sent out |
| --- | --- | --- | --- | --- | --- |

2. report in FFR from first element.

| Non Fault Load | Device No fault or Fault | Normal No fault | Normal No fault | Normal No fault | 1 device access will not be sent out |
| --- | --- | --- | --- | --- | --- |

2. 2nd3rdother active element normal access maybe send out.
3. report in FFR from first element.

| Non Fault Load | Normal No fault | Device No fault or Fault | Any | Any | 1 first active element will send out normal access |
| --- | --- | --- | --- | --- | --- |

2. 2nd active element device access will not be sent out.
3. report in FFR from 2nd element.

#### 1.0.2 SVE Store

##### 1.0.2.1 SVE store pipeline

In target implementation LSU store SVE flow is same as nomal store no uop split no VAB vectore address buffer read path The most difference between SVE store and normal store is that the SVE store need to do scrp data decode in store issue pipe and the nopgen SVE store need to reuse stq bytemask array to storage scrp data In addition all NCDEV SVE store is treat as MGW.

##### 1.0.2.2 XP buffer

###### 10221 XP buffer structure

Non scatter sve store have no difference with nomarl store in xp buffer occupy and release due to there is at most one XP for consecutive address However scater sve have up to 8 uops and each uops address is discontinuous so 8 XP buffers distributed in two STQ banks are prepare for scatter sve.

###### 10222 XP scatter pipeline

| a | XP scatter first issue |
| --- | --- |
| b | First XP uop reissue without conflict |
| 1 | First XP uop issue |
| 2 | Other XP uop issue |
| c | First XP uop reissue with conflict |
| d | First XP uop reissue match xp lock |
| e | How to blcok other scatter XP |
| f | How to clear XP buffer |
| 11 | RAS |

#### 1.1.1 Load ras flow

##### 1.1.1.1 Load ras general flow

##### 1.1.1.2 Load ras detailed flow

###### 11121 Align load only has data CE

###### 11122 Misalign load only has data CE

| 11123 | Align load only has tag CE win pipe and win data arb |
| --- | --- |

###### 11124 Align load only has tag CE with lose pipe

| 11125 | Align load only has tag CE with win pipe but lose data arb |
| --- | --- |
| 11126 | Misalign load only ma1 has tag CE both ma1ma2 win data arb |
| 11127 | Misalign load only ma1 has tag CE ma1 win data arb but ma2 lose data arb |
| 11128 | Misalign load only ma1 has tag CE ma1 lose data arb |

###### 11129 Load fwd sbe from FDB

###### 111210 Load only has data UE

###### 111211 Load fwd data UE from FDB

###### 111212 Load fwd data UE from BAB

###### 111213 Load fwd data UE from SCB

###### 111214 Liq match dataabort from fill pipe and pick it

###### 111215 Load CE to UE

#### 1.1.2 Store ras flow

##### 1.1.2.1 Store match data CE

##### 1.1.2.2 Store match tag CE

##### 1.1.2.3 Store match fill error

##### 1.1.2.4 Near atomic match data error

##### 1.1.2.5 Far to near atomic match data error

##### 1.1.2.6 Far atomic match fill err

#### 1.1.3 Ras report

Load CE register update.
RCE monitor release.
SEA taken by LSU
SEI taken by LSU
| 12 | DS of Structures |
| --- | --- |

#### 1.2.1 UOP TABLE

Please refer to the LSU UOP TABLE file.
[internal reference removed]
Different from previous version the UOP expansion for FPSIMDSVE is moved to OEX This uop table shows how FPSIMDSVE load and store is splitted And their uop type decode.

#### 1.2.2 Ordering

The Ordering handling for LDLD STLD ordering for WBNCDev or Barriers Pelase refer to the LSU Ordering Notes.
[internal reference removed]

##### 1.2.2.1 Mixed Attribute Load Ordering

###### 12211 Cross page with Normal and Dev Memory

In target implementation the uop expansion is moved to OEX LSU do not have the whole instructures picuture in the issue time Thus the cross page misalign fault is report by the uop level If the cross page boundary is between uops its no fault in target implementation.
Multi Uop Dev Ordering.
Arm spec requirement Device memory cannot be access speculative so cannot be access multitime But for multiuop instruction as SVESIMD the DEV memory access might occur multiple times.
So in target implementation Load multiuops are independent of each other It do not wait for the completion status of other UOPs DEV memory access follow oldest unorder LID from LHQ and dot not send speculative request to L2C but when load instruction have exceptionnuke or RAS CE it maybe multiple times multiple times.

#### 1.2.3 PIPELINE

##### 1.2.3.1 Overview

###### 12311 Features

LSU provides the following features.
| Feature | Description |
| --- | --- |
| 3 Load pipes | LSU support 3 load pipe and bandwidth 32Bytes per pipe |
| Skid buffer I2 blocking | Load pipe use skid buffer to reduce Loadtouse |
| Cross 32B align | Cross 32B boundary is treated as align load no penality |
| Cross 64B +1 latency by SkidMA | Use skid buffer cross 64B load 5 cycle load to use |

Table 1 High Level Features.

##### 1.2.3.2 Structure

###### 12321 Load pipe

Main Functions
Module clock gate
Pipe Valid are flushed from I1 to E3.
Pipe cancel from IEX qualify at I2.
Pipe drain mark
Pipe MGW flag
Pipe hash pc calculate.
Pipe SP check
Pipe issuereissue mux.
Pipe misalign and cross page check.
Pipe optype and opsize decode.
Pipe bytemaskwordsel calculate.
Pipe SVE srcpnull calculate.
Pipe SVE nfff processLoad pipe exclusive.
Pipe TLBITLBPF flow supportsame as store pipe.
A new module for Load pipe to support Skid buffer I2 blocking.
Load Misalign
| Name | Ca |
| --- | --- |
| Cross32B0 | size24 va30>8 |
| Cross32B1 | size32 va300 |
| Cross64B | size+va >64 |

Bytemask 32-bit and for ma1ma2.
| 1 | Ldax bytemask do not send to ldst forward |
| --- | --- |
| 2 | Reuse va+size to generate bytemask1 and bytemask2 to result piperemove endva at i2 |
| 3 | SVE bytemask with srcpdata need other lib and to calculate null indication |
| 4 | Consider Cross32B with 0 penalty case to select bytemask VAbit40 |
| 5 | Dev mgw do not change |

Aligned
If VA[54]2b01 set va41b0 >shifted bytemask1.
Else >orignal bytemask1.
Misaligned MA1 bytemask1.
Misaligned MA2 bytemask2.
Dwordsel 8-bit for D read.
Ldax is only valid when aligned.
Based on VA+Size
Based on endva
TLB srcasrcb MUX
For TLBITLBPF need to add tlbipfva in ldax sidedoor part.
SVE support
Remove srcp tag
For watchpoint if use parentxp flow still need predicate for bytemask.

###### 12322 Store pipe

Main Functions
Module clock gate
Pipe Valid are flushed from I1 to E3.
Pipe cancel from IEX qualify.
Pipe drain marked
Pipe DSBMGW flag
Pipe hash pc calculate.
Pipe SP check
Pipe issuereissue mux.
Pipe misalign and cross page check.
Pipe optype and opsize decode.
Pipe bytemask calculate.
Pipe SVE srcpnull calculate.
Sveallact
Xp1nulle1
Xp2nulle1
Ma1null
Ma2null
Ma
Bytemask at issue vld e2.
Store pipe exclusiveagen resolve module.
Pipe dcspec flag
Pipe agenresolve to OOO.

- utput to LHQSCB.

Pipe AT flow
There is new module for special LSU store pipe function.
Flush window
Pipe can process flush at issue pipe from I2 to E4 issue I1 flush need to be coverd by IEXFSU.

###### 12323 Result pipe

Result cancel
There are two types of cancel internal for pipe and interface for IEXFSU.
New cancel
SBI2 blocking
LHQ sliding window check.
LIQ cancel lfb full.
New LIQ Spec wakeup.
Snoop cancel
Remove cancel
Ldat
Oldest release
Lmqlhq full
Result resolve
Resolve only uop resolvefault do not required by instruction.
Fault rasgather ffr.
Prfm
Result ctrl
Main Functions

1. repickwakeup with ldmidx.
3. ldaxliqxldodbldstx arbit.
4. ICGflush
5. dvm sync
6. ldaxlost arb
7. LIQ spec cancel.
8. bytemask for sel stqscbfillstage1dc.
9. SVE srcpshufflegather.

#### 1.2.4 Skid buffer and Misalign handling

##### 1.2.4.1 Overview

Backgroud
Latency of Repick
In previous-generation core Each cancel LD which is repicked from LAGB leads to a best case of 9 LDtoUse.
As depicted Data can fastly be provided at E9 in figure below To reduce FailedCancelled LDs LDtoUse we need to avoidbypass the LAGB repick scheme.

#### Figure 1 L1 Miss Pipe + Wakeup dependent uOp

Dependent instruction Wakeup is sent in M1E1 from LSU to IEX.
Dependent instruction Wakeup following Repick is sent at M1E6.
Dependent uOp is Cancelled at M3E3 and E4.
Issue pipe is E1E10 Dependent uOp is also in reference to Issue Pipe.

##### 1.2.4.2 Skid Buffer feature

Skid Buffer Definition.
Skid buffers are used to allow a blocked uOp due to resource conflict to quickly Retry again to access resources Retry is a RepickReissue operation.
The Skid buffer is a 3 stage systolic bus attempting to access the Load pipe if pipe is free Invalid.
If Skid buffer wins Load pipe then uop does not require to go through LDQ Repick It immediately arbitrate on I2 to gain access to pipe.
If Skid buffer fails to win uOp is written to LDQ Repicked at E4 and can generate Data at E9.
We identify 3 types of skid bufferEach with its own characteristics They all attempt to reaccess pipe at I2.
I2 Blocking SB noted SBI2.
Mis align SB noted as SBMAdrop.

#### Figure 2 L1 TAG access arbiter

##### 1.2.4.3 Skid buffer for I2 blocking

###### 12431 SBI2 Definition

The SBI2 purpose is to avoid repick via LDQ It eliminates the writing of a uop to LAGB with TAG Arbitration Loss or Result Pipe Loss at I2 in the case where SBI2 won in either of the next 3 cycles M1 M2 M3.
With SBI2 an arbitration loss uOp can have opportunity to 567 LDtouse.
The I2 blocking skid buffer is defined as follows.
I2 blocking detect condition.
Tag arbitration lost by conflict with TAG fill or LDQ repack.
Result pipe lost by conflict with LDQ repack.
SBI2 to win reaccess to M1 is Priority repickreissuex>issue>SBI2 And when SkidI2 confliction with IEX issue and not repickreissue the Skid I2 do not pick from the SkidI2.
Issue won the TAG arbiter.
Issue won result pipe.
IEX issue is invalid.
The SBI2 is a M1 to I2 bypass Where.
I2 Arbitration loss is detected in issue I2 uOp is captured in SBI2 stage 0.
At M1 where issued LD is located no Wakeup is generated to IEX.
At M1 the uOp attempts to regain ownership over I2 of next cycle Win over pipe is identified at I2.
The SBI2 has 3 opportunities to win pipe before writing to LDQ M1 M2 M3 of issued load.
If SBI2 wins pipe it generates Wakeup on M1 and cancels the M3 Cancelissue load and the M3 write to LDQ.
Noted as M1I2 Skid buffer although M2I2 and M3I2 bypass are supported with Stage 0 1 2 of the Skid buffer.
Design Notes
Prioritize SB2 SB1 SB0 when more than 1 is attempting access in the SBI2.
SBI2 can contain pgen if TLB was read.
Aslo need to consider TLB missfault and mairWB or NCDEV.

#### Figure 3 SBI2 pipeline for SB0 SB1 SB2

###### 12432 Pipeline and Datapath

M1I2 Skid buffer
Case1
Detect I2 blocking condition.
SBI2 win reaccess to M1.
Wakeup to IEX send at reissue M1.
But issue have tlb miss or ld ncdev SBI2 M1I2 fails and sent cancel to IEX at reissue E3.
Issue Uop write LDQ at E3 as normal.
Case1
Detect I2 blocking condition.
SBI2 M1I2 win reaccess to M1.
Wakeup to IEX send at reissue M1.
And issue have tlb hit and ld WB SBI2 M1I2 success.
If reissue tag miss also need to cancel and write LDQ.
Issue Uop do not write LDQ at E3.
M2I2 Skid buffer
Case0
Detect I2 blocking condition.
SBI2 M1I2 do not win reaccess to M1eg TAG still lost.
SBI2 M2I2 win reaccess to M1.
Wakeup to IEX send at reissue M1.
But issue have tlb miss or ld ncdev SBI2 M2I2 fails and sent cancel to IEX at reissue E3.
Issue Uop write LDQ at E3.
Case1
Detect I2 blocking condition.
SBI2 M1I2 do not win reaccess to M1eg TAG still lost.
SBI2 M2I2 win reaccess to M1.
Wakeup to IEX send at reissue M1.
And issue have tlb hit and ld WB SBI2 M2I2 success.
If reissue tag miss also need to cancel and write LDQ.
Issue Uop do not write LDQ at E3.
M3I2 Skid buffer
Case0
Detect I2 blocking condition.
SBI2 M1I2 M2I2 do not win reaccess to M1eg TAG still lost.
SBI2 M3I2 win reaccess to M1.
But issue have tlb miss or ld ncdev SBI2 M2I2 fails and No wakeup to IEX send at reissue M1.
Issue Uop write LDQ at E3.
Case1
Detect I2 blocking condition.
SBI2 M1I2 M2I2 do not win reaccess to M1eg TAG still lost.
SBI2 M3I2 win reaccess to M1.
Wakeup to IEX send at reissue M1.
And issue have tlb hit and ld WB SBI2 M3I2 success.
If reissue tag miss also need to cancel and write LDQ.
Issue Uop do not write LDQ at E3.
SBI2 M1M2M3 Priority.

##### 1.2.4.4 Load misalign

Load Misalign new define for 920WBNC.
| AlignMA | Check conditionva+size | Remark |
| --- | --- | --- |
| Align | do not cross 32B |  |
| cross 32Bsize<16B do not care VA | With 0 penalty |  |
| cross 32Bsize24B VA[30] <8 | With 0 penalty |  |
| cross 32Bsize32B VA 16byte align | With 0 penalty |  |
| MA | cross 64Bcross cacheline |  |
| cross 32Bsize24B VA[30] >8 | simplify STRFWD |  |
| cross 32Bsize32B VA non16byte align | Limited by DC POR and simplify STRFWD |  |

Cross page
NOTE For Dev the cross 32B boundary is still treated as MGW.

###### 12441 32B Crossing with 0 penalty

Requires MA Merge in M3M4 and add requirement to DC design.
Compared with misalign saved by 1 cycle and do not need to repick.
No cancel signal is generated for 32B crossing.
No Misalign handling for NC and Device request All NCDEV are blocked any way and sent to LDQ.

- Requirement for DC design.

Allow to read a maximum of four consecutive 8 bytes from DC cacheline at one time.
32B Crossing with 0 penalty pipeline as below.

##### 1.2.4.5 LSUIEX interface

Wakeup
Requirement 1 Support E1 wakeup.
LSUIEX wakeup move form I2 to E1 for both repickreissue and issue Do not need the IEX self wakeup for issue.

#### 1.2.5 LIQ liuewei

##### 1.2.5.1 Structure

LIQ
| Att array | Fields Name | Width | Description |
| --- | --- | --- | --- |
| type | 12 |  |  |
| Oppc | 16 |  |  |
| sampled | 1 |  |  |
| tid | 1 |  |  |
| rid | 9 |  |  |
| lid | 8 | LIQ index |  |
| ldmatrixidx | 8 |  |  |
| srcpvld | 1 |  |  |
| srcptag | 1 |  |  |
| scale | 2 |  |  |

PA array
| pbha | 2 |  |
| --- | --- | --- |
| size | 3 |  |
| samecacheline | 1 |  |
| papage | 37 |  |
| mair | 5 |  |
| shareable | 2 |  |
| pagesize | 3 |  |
| vaidx1 | 14 |  |
| vaidx2 | 14 |  |
| vapage | [6312] | full va for stld fwd |

Status array
| dev1 | 1 |
| --- | --- |
| ncdev1 | 1 |
| hit1vld | 1 |
| Hit1way | 2 |
| Hit2vld | 1 |
| Hit2way | 2 |
| Pgen1 | 1 |
| Pgen2 | 1 |
| Fault1vld | 1 |
| Fault2vld | 1 |
| dataabort1 | 1 |
| dataabort2 | 1 |
| dataaborttype | 2 |
| dcspec1 | 1 |
| dcspec2 | 1 |
| Bigendian | 1 |
| yostptr | 10 |
| Va1idx13to12 | 2 |
| Va2idx13to12 | 2 |
| usebab1 | 1 |
| usebab2 | 1 |
| ldxr | 1 |
| Rce | 1 |
| xp | 1 |
| ma | 2 |
| Liqstatusfsm1 | 7 |
| Liqstatusfsm2 | 7 |
| mustdrainvld | 1 |
| ma1null | 1 |
| ma2null | 1 |

##### 1.2.5.2 LIQ FSM

FSM status
| LIQSTATUSFSM | [60] |  |
| --- | --- | --- |
| LIQSTATUSWIDTH | 7 |  |
| LIQSTATUSREADY | 6 |  |
| LIQSTATUSREADYUSEDC | 5 |  |
| LIQSTATUSIDLE | 7b0000000 |  |
| LIQSTATUSWAITDSBDONE | 7b0000010 |  |
| LIQSTATUSWAITTLBNSE | 7b0000011 |  |
| LIQSTATUSWAITMRBDEALLOC | 7b0000100 |  |
| LIQSTATUSWAITMMUFILL | 7b0000101 |  |
| LIQSTATUSWAITLIQOLDEST | 7b0000110 | XP |
| LIQSTATUSWAITOLDESTWBLDXR | 7b0001000 | ldxr WB |
| LIQSTATUSWAITOLDESTNCLDXR | 7b0001001 | ldxr NC |
| LIQSTATUSWAITOLDESTDEVLDXR | 7b0001010 | ldxr DEV |
| LIQSTATUSWAITOLDESTNCUNORDER | 7b0001011 | NC |
| LIQSTATUSWAITOLDESTDEVUNORDER | 7b0001100 | DEV |
| LIQSTATUSWAITLFBCREDIT | 7b0001101 |  |
| LIQSTATUSWAITLFBHAZARD | 7b0001110 |  |
| LIQSTATUSWAITL2FILL | 7b0001111 |  |
| LIQSTATUSWAITLHQCREDIT | 7b0010000 |  |
| LIQSTATUSWAITSTQPGEN | 7b0011000 |  |
| LIQSTATUSWAITSTQDEALLOC | 7b0011001 |  |
| LIQSTATUSWAITSTQDGEN | 7b0011010 |  |
| LIQSTATUSWAITSCBDEALLOC | 7b0011011 |  |
| LIQSTATUSWAITSCBFILL | 7b0011100 |  |
| LIQSTATUSREADYREISSUE | 7b1000000 |  |
| LIQSTATUSREADYUSEFDBE2C6 | 7b1000001 | 10 cycle |
| LIQSTATUSREADYUSEFDBE2C7 | 7b1000010 |  |
| LIQSTATUSREADYUSEFDBE2C8 | 7b1000011 |  |
| LIQSTATUSREADYUSEFDBE2C9 | 7b1000100 |  |
| LIQSTATUSREADYUSEFDBE2Ca | 7b1000101 |  |
| LIQSTATUSREADYUSEBAB | 7b1001000 |  |
| LIQSTATUSREADYUSEDC | 7b1100000 |  |

FSM transition condition.
Sleep Conditon
| PGEN | Priority | CASE | Status | Description |  |
| --- | --- | --- | --- | --- | --- |
| 0 | 1 | SLEEP | older dsb undone | LIQSTATUSWAITDSBDONE | oldest dsb done |
| 2 | load will not execute until its rid match with nextcommit rid | LIQSTATUSWAITTLBNSE | load rid match with nextcomit rid |  |  |
| 3 | Issued load TLB miss Miss request buffer MRB full | LIQSTATUSWAITMRBDEALLOC | TLB miss buffer entry becomes available |  |  |
| 4 | Issued load TLB miss Allocated miss request buffer MRB entry and sent request to MMU | LIQSTATUSWAITMMUFILL | MMU fill return for appropriate miss buffer ID |  |  |
| 5 | issue load Pa cross page WB+WBWB+NC NC+NC DEV+DEV | LIQSTATUSWAITLIQOLDEST | load lid match global oldest liq lid |  |  |
| 1 | 1 | SLEEP | issue wb ldxr will not execute until its lid match with oldest excl id in LSU | LIQSTATUSWAITOLDESTWBLDXR | ldxr lid match with oldest exclusive lid |
| 1 | issue nc ldxr will not execute until its lid match with oldest excl id in LSU | LIQSTATUSWAITOLDESTNCLDXR | ldxr lid match with oldest exclusive lid no older ncdev store in stq or scb no older store without PGEN |  |  |
| 1 | issue dev ldxr will not execute until its lid match with oldest excl id in LSU | LIQSTATUSWAITOLDESTDEVLDXR | ldxr lid match with oldest exclusive lid oldest unordered lid and noflushed rid no older ncdev store in stq or scb no older store without PGEN |  |  |
| 2 | issue nc load ldxr will not execute until its lid match with oldest excl id in LSU | LIQSTATUSWAITOLDESTNCUNORDER | ldxr lid match with oldest unordered lid no older ncdev store in stq or scb no older store without PGEN |  |  |
| 2 | issue dev load ldxr will not execute until its lid match with oldest excl id in LSU | LIQSTATUSWAITOLDESTDEVUNORDER | ldxr lid match with oldest unordered lid and noflushed rid no older ncdev store in stq or scb no older store without PGEN |  |  |
| 3 | issue or repick load cannot allocate LFB because LFB full | LIQSTATUSWAITLFBCREDIT | LFB credit visiable |  |  |
| issue or repick load cannot merge LFB because RDM cannot merge with RDS open item | LIQSTATUSWAITLFBHAZARD | corresponding LFB deallocate |  |  |  |
| issue or repick load allocate LFB | LIQSTATUSWAITL2FILL | L2 Fill |  |  |  |
| issue or repick load cannot allocate LHQ because LHQ full | LIQSTATUSWAITLHQCREDIT | LHQ credit visiable |  |  |  |
| 1 | 3 | SLEEP | issue or repick load win result pipe tag miss and hit scb scb request has sent to L2 open item | LIQSTATUSWAITSCBFILL | after L2 fill SCB and wake up load at C6 |
| 4 | issue or repick load win result pipe mdb fail and older store not PGEN | LIQSTATUSWAITSTQPGEN | correspoding STQ PGEN |  |  |
| 5 | issue or repick load win result pipe special instruction block load excute or load multi hit older store | LIQSTATUSWAITSTQDEALLOC | correspoding STQ deallocate |  |  |
| 6 | issue or repick load win result pipe load hit older store which is not DGEN data not issue to lsu | LIQSTATUSWAITSTQDGEN | correspoding STQ receive data |  |  |
| 7 | issue or repick load win result pipe special instruction block load excute or load multi hit older store | LIQSTATUSWAITSCBDEALLOC | correspoding SCB deallocate |  |  |

Wakeup Conditon
| PGEN | CASE | Status | Description |
| --- | --- | --- | --- |
| 0 | WAKEUP | After all PGEN 0 sleep be wakeuped FSM will be at ready reissue state | LIQSTATUSREADYREISSUE |
| 1 | 10 cycle wakeup FSM first state | LIQSTATUSREADYUSEFDBE2C6 |  |
| 10 cycle wakeup FSM second state | LIQSTATUSREADYUSEFDBE2C7 |  |  |
| 10 cycle wakeup FSM third state | LIQSTATUSREADYUSEFDBE2C8 |  |  |
| 10 cycle wakeup FSM fourth state OR C5 L2 fill wakeup FSM first state | LIQSTATUSREADYUSEFDBE2C9 |  |  |
| 10 cycle wakeup FSM fifth state OR C5 L2 fill wakeup FSM second state | LIQSTATUSREADYUSEFDBE2Ca |  |  |
| NCDEV wake up | LIQSTATUSREADYUSEBAB |  |  |
| 10 cycle wakeup FSM sixth state OR C5 L2 fill wakeup FSM third state OR other wakeup | LIQSTATUSREADYUSEDC | other wakue up contains kinds of immediately repick like tag or data read conflict |  |

FSM tranisiton diagram.

##### 1.2.5.3 Handling Order

##### 1.2.5.4 RepickReissue pipe confliction handling

#### 1.2.6 LHQ linfei

##### 1.2.6.1 Overview

This is top diagram with OOOIEXLSU for LHQ preallocation scheme.
LHQ preallocation
LSU has a flopbased Load Hit Queue LHQ shared by 2 threads which tracks resolved loads that may need to be flushed due to ordering violations and tracks all Loads from Issue to Retire including NCDEV.
When an older store matches a younger resolved load to same address in LHQ the younger load must be flushed This is referred to as a stld nuke flush.
When a snoopinvalidate matches a resolved load in LHQ it is marked stale If an older unresolved load with same PA exists in LHQ when snoop arrives the younger load with stale must be flushed This is referred to as a ldsnoop nuke flush.
Main impact of new LHQ.
Need to handle single entry per uOp eg Mis AlignCrosspage.
LHQ repartition to three localLHQs.
For SMT handling in Local LHQ LHQ resources are split in half.
Change LDSnoopNuke scheme.
Support load ordering.
The following tables show the structure and entry format of the LHQ.
| LHQ | Comment |  |
| --- | --- | --- |
| Entries | 340 | Divide to three localLHQ array and 42 entries per array |
| Entry width | 86 bits |  |
| Ports | 4R 4W 3C | For localLHQ |

WR E4 pipexvld ma1ma2 pgenresolve.
CAME4 sta0
CAME4 sta1
CAMC7 snoop invalidate.
WR deallocated per cycle.
RD E5 T0 nuke picker for STLDDMB.
RD E5 T1 nuke picker for STLDDMB.
| TOTAL SIZE | 5504 bits |
| --- | --- |

DSE size
The following tables show the LHQ module structures.
| Module1 | Module2 | Module3 | Comment |
| --- | --- | --- | --- |

Lhq
| lhqarray | Entrywren |
| --- | --- |

Snoop check
Sta01 pipe access
| lhqentry | Lhq load attribute |
| --- | --- |

Load excute statuspgenresovled.
Load nuke statussnoopednuked.
| lhqorderctrl | Load ordering |
| --- | --- |

LHQ sliding window.
| lhqnukectrl | Ooo nuke generate |  |
| --- | --- | --- |
| MacroParameter | RangeValue | Comment |
| LSULHQDEPTH | 324048 | Local and more smaller |
| LSULDUNUM | 23 | Load pipe |
| LSUPAWIDTH | 404852 |  |
| LSUVAWIDTH | 404852 |  |

##### 1.2.6.2 Entry Attribute

| Entry Field | Width | RD | WR | CAM | Comment |
| --- | --- | --- | --- | --- | --- |
| vld | 1 | E4 pipexldax valid load |  |  |  |
| Clear on LHQ deallocate | Entry Valid |  |  |  |  |

Set when entry allocate.
Clear when commit or flushed or nuke picked.
| tid | 1 | E4 T0 nuke picker |
| --- | --- | --- |

E4 T1 nuke picker
| dealloc picker | E4 pipexldax alloc load | dealloc | Thread ID for compare with nextcommit RID for deallocating LHQ entry load ordering send nuke flush |
| --- | --- | --- | --- |
| rid | 9 | E4 T0 nuke picked |  |
| E4 T1 nuke picked | E4 pipexldax alloc load | dealloc | ROB ID for compare with nextcommit RID for deallocating LHQ entry load ordering send nuke flush |
| type | 3 | Entry alloc | Subtype for special instruction |

Ldarprfmldxr
| mustdrain | 1 | E4 pipex pgen load |
| --- | --- | --- |
| Set on dvmsync when entry is valid | This entry must drain from LHQ before asserting dvmsyncdone |  |

Set when entry or pipex is mustdrain and pgen.
Clear when entry deallocate.
| papage | 16 | E4 pipexldax pgen load |
| --- | --- | --- |

C7 snoop
E2 sta0 store
| E2 sta1 store | PA[48] [2612] for stld and ldsnoop nuke comparison |  |
| --- | --- | --- |
| vaidx | 14 | E4 pipexldax pgen load |

E2 sta0 store
E2 sta1 store
| C7 snoop | VA[130] for stld and ldsnoop nuke comparison and snoop comparison |  |  |
| --- | --- | --- | --- |
| size | 4 | E4 pipex resolved load | E2 sta0 store |
| E2 sta1 store | Load word mask used to generate wordmask for stld and ldsnoop nuke comparison |  |  |
| resovled | 1 | E4 pipex resolved load | FSM RSB allocated |
| nuked | 1 | E4 T0 nuke picker |  |
| E4 T1 nuke picker | E2 sta0 stld nuke |  |  |

E2 sta1 stld nuke
E3 dmb resolve
| Clear on nuke picked | This entry is nuked and must be picked for nuke flush |  |  |  |  |
| --- | --- | --- | --- | --- | --- |
| Nuke done | 1 | Entry has been nuked |  |  |  |
| yostdelta | 10 | E4 T0 nuke picked |  |  |  |
| E4 T1 nuke picked | E4 pipex resolved load | Load YOST pointer overwritten with delta for MDB update at stld nuke |  |  |  |
| stale | 1 | Set on snoop match when load is resolved | This entry is stale and must be nuked on older DMB match DMBld hazard |  |  |
| stli | 1 | E4 T0 nuke picked |  |  |  |
| E4 T1 nuke picked | E2 sta0 stld nuke |  |  |  |  |
| E2 sta1 stld nuke | This entry was nuked by store and requires MDB update |  |  |  |  |
| TOTAL | 55 |  |  |  |  |
| Entry Field | Width | RD | WR | CAM | Comment |
| ncdev | 1 | E4 pipexldax pgen load | Memory attribute |  |  |
| pgen | 1 | E4 pipexldax pgen load |  |  |  |
| fault | 1 | E4 pipexldax pgen load |  |  |  |
| ma1null | 1 | E4 pipexldax pgen load | SVE uop is nop Multiplexed to ncdev |  |  |
| ma2null | 1 | E4 pipexldax pgen load | SVE uop is nopMultiplexed to ncdev |  |  |
| ma | 1 | E4 pipexldax pgen load | Misaligned |  |  |

Cross32B with special.

- r cross64B

| xp | 1 | E4 pipexldax pgen load | Uop is crosspage |
| --- | --- | --- | --- |
| pgen2 | 1 | E4 pipexldax pgen load |  |
| ncdev2 | 1 | E4 pipexldax pgen load |  |
| fault2 | 1 | E4 pipexldax pgen load |  |
| TOTAL SIZE | 40 |  |  |

Deallocated> idle> entryvld> pgen> resovled> nuked> nukepickeddellocated.
Deallocated> idle> entryvld> pgen> resovled> nuked> flush.
Deallocated> idle> entryvld> pgen> resovled> commit deallocated.
FSM[10] define
IdlepgenresolveWBresolveNCDEV.

##### 1.2.6.3 Load Ordering

The following table summarizes these oldest RID cases The sections below provide more details on how each oldest RID is generated.
| Oldest RIDLID Generated by LHQ | Description | Comment |
| --- | --- | --- |
| Oldest LIQ LID | LID of oldest load that still needs to use LIQ oldest unresolved load instruction | total |
| Oldest Unordered LD LID | LID of oldest unresolved WB load or nonLFB allocated NCDEV load | total |
| Oldest LDXR LID | RIDLID of oldest load exclusive instruction | total |
| ldxOldest LIQ LID | Local LID of oldest load that still needs to use LIQ oldest unresolved load instruction | local |

The following table summarizes entry status output from LHQ entry to LHQCTRL.
| Entry status | condition | Comment |
| --- | --- | --- |
| Entryneedliq | entryvld resolved || |  |

Entryvld
| Entryunorderedld | entryvld resolved wb || |
| --- | --- |

Entryvld lfballocate ncdev ||.
Entryvld
| Entryldxr | entryvld ldxr || |
| --- | --- |

Entryvld
| Entrynuked | Stldnukeset|| |
| --- | --- |

Dmbnukeset||
Snpnukesetnext commit.
| Entryvld | nextCommit or flush deallocate |
| --- | --- |

Issue e4 allocate or D4 allocate.
Order structure
There is one lib to select oldest RIDLID It depend on logic level LHQ is ordere structure use tcode01 to search for the oldest.
Stq28mux1 for yngst is 6 logic level.

##### 1.2.6.4 SMT partition

LID define
LID[10] is determined by LDU index range is 02.
LID[72] is for local LHQ index And range is 039 or 31 or 47.
LID[98] is free for OOO.
SMT partition
Local LHQ entry index LID[72] for ST mode.
Local LHQ entry index LID[62]TID for MT mode.
T0 use local LHQ even entry.
T1 use local LHQ odd entry.
T0T1 have independent LHQ sliding window.

##### 1.2.6.5 Loadsnoop Nuke

The following table summarizes entry status for Loadsnoop match.
| Entry status | Condition | Comment |
| --- | --- | --- |
| Entrynotresolved | entryvld resolved WB pgen pamatch || |  |

Entryvld resolved WB nopgen ||.
Entryvld resolved ldar ||.
| entryvld | Have older unresolved WB load when snoop valid |  |
| --- | --- | --- |
| Entrysnoopresolved | entryvld resolved WB snoopmatchhit | Resolve load is snooped |

Need pipe e3e4c7 bypass.
For LDAR with WBNCDEV make older entry to be unresolved and set snoop nuke.
Loadsnoop Nuke who scheme.
When snoop arrives use LHQ entry status to find the oldest LID entry which need to be nuked.
Loadsnoop nuke pipeline may be 34cycles.
Loadsnoop nuke and storeload nuke may be asynchronous event And they have different pipeline with age compare mux at interface OOOnuke.
Snoop match schemebased on PASnoop.
| Stage | Match condition | Comment |  |
| --- | --- | --- | --- |
| Result pipe at | E1E2 | PA or Hash PA match | Load cancel and LIQ repick |
| E3E4 | PA or Hash PA match | Snoop match bypass |  |
| LHQ entry | Invalid | Entry valid not pgen || |  |
| entryvld | Older not resolved is valid |  |  |
| Pgen | PA or Hash PA match | Older not resolved is valid |  |
| Resolved | PA or Hash PA match | Entry snoopedneed to be nuked |  |

PA Snoop is another snoop port from L2C It does not include the L1V ash PA generate.
21-bit pa26to5
XPload snoop flow
Do not keep PA2 for each LHQ entry Instead use xpbuffers PA.
Stld nuke do not need to cam PA1 and PA2 Flow make sure older store have been pgen before xpload resolved.
Xp2 flow local match oldest lid Xp2 match VA11to6 or global oldest liq lid.
OOONuke interface
Storeload nuke set nuked field in LHQ entry And nuked picker can select oldest nuked LHQ entry to send nuke to OOO.
Nuked picker use LHQ sliding window to find oldest nuked LHQ not age matrix Or maybe two ooonuke ports.

##### 1.2.6.6 Pipeline and Datapath
