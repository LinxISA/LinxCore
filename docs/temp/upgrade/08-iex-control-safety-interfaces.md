# IEX Control, Safety, Interfaces, and MDB Support

This document covers SMT behavior, reset, implementation control registers, safe mode, parity protection, interfaces, physical-design notes, fast store-to-load forwarding, and IEX-side MDB support.

> This Markdown document is the maintained specification. Sensitive metadata and internal reference labels have been removed while preserving architecture semantics.

### 1.1 SMT Support in IEX

#### 1.1.1 Overview of SMT in IEX

Most of IEX resources are sharable in SMT mode Most uops info can be distinguished by tid in issue queues and pipelines Some structures are threadprivate There is no partitioned structure in the IEX The following table shows the sharedprivate structure in IEX.
| IQ S1WK | S1S2 Wakeup | Shared |
| --- | --- | --- |
| IQ ALU | Entry Stat | Shared |
| Entry Info | Shared |  |
| Entry Wakeup | Shared |  |
| AGE Matrix | Shared |  |
| IQ Read | Shared |  |
| IQ Write | Shared |  |
| IQ Free Entry Count | Shared |  |
| IQ Used Entry Count | Private |  |
| IQ SAFE | Private |  |
| IQ PMUDFX | Private |  |
| IQ BRU | Entry Stat | Shared |
| Entry Info | Shared |  |
| Entry Wakeup | Shared |  |
| AGE Matrix | Shared |  |
| IQ Read | Shared |  |
| IQ Write | Shared |  |
| IQ Free Entry Count | Shared |  |
| IQ Used Entry Count | Private |  |
| IQ SAFE | Private |  |
| IQ PMUDFX | Private |  |
| IQ AGU | Entry Stat | Shared |
| Entry Info | Shared |  |
| Entry Wakeup | Shared |  |
| AGE Matrix | Shared |  |
| IQ Read | Shared |  |
| IQ Write | Shared |  |
| IQ Free Entry Count | Shared |  |
| IQ Used Entry Count | Private |  |
| IQ SAFE | Private |  |
| IQ PMUDFX | Private |  |
| MDB LSFT | LFST read | Shared |
| LFST | Private |  |
| LFST write | Shared |  |
| IQ FLOP | STDYOESTSID | Private |
| Branch InOrder | Private |  |
| NextRetiredRID | Private |  |
| ThreadActive | Private |  |
| CPM Drop | Shared |  |
| DIDT | Shared |  |
| IQRTABRF | PTAG | Shared |
| PP ALU | PP ALU03 | Shared |
| PP ALU14 | Shared |  |
| PP ALU25 | Shared |  |
| PP ALU Share | SYS Far Cred | Private |
| SYS L3C | Private |  |
| PP BRU | PP BRU | Shared |
| PP AGU | PP PLDA | Shared |
| PP PSTA | Shared |  |
| DP | BN | Shared |
| BRU | Shared |  |
| DIV | Shared |  |
| ALU | Shared |  |
| SYS | Private |  |

Table 111 Overview IEX scheme in SMT mode.
Entry Used Safemode and PMU counters.
In SMT mode each thread maintains its own used entry count for each issue queue entry safemode counter and pmu counters Most of the resources are shared by SMT threads but the statstics for shared resources are private to each thread.
RF
Register file is shared by all threads by ptags allocated to each thread in OoO The shared RF mode is invisible in IEX.
MDB Last Fetched Store Table.
The MDB LFST table is private to each thread Each thread maintains its own storeload dependence color in each table.
SYS register
Threadprivate system registers maintain independent copy to each thread There are also corelevel system registers that need to be configured for all threads such configuration override bit registers and powerrelated system registers.
MSR GIC Credit Count.
The data path of GIC FAR MSR register access is independent for each thread Therefore MSR buffer is divided into two parts according to thread and credit is calculated independently.

#### 1.1.2 SMT mode switch and toggling

IEX receives MT Mode Multithread mode and ST Mode Singlethread mode signals from OoO IEX do not receive MTMode signal from OoO.
| mtpartition | Signal used for multithread partition |
| --- | --- |
| t0en | Thread0 enable it means thread 0 resource is enabled |
| t1en | Thread1 enable it means thread 1 resource is enabled |
| t0active | Thread0 active it means thread 0 is not in WFI mode |
| t1active | Thread1 active it means thread 1 is not in WFI mode |

In IEX the t0ent1en are used for clock gating for all threadprivate structures The thread active signals are used for thread toggle for thread arbitration in IEX The following case list all the thread toggle mechasim in IEX.
Oldest uop toggle in IQ per thread.
The IEX safemode tracks the oldest uop in each issue queue If the oldest uop is not picked in the IQ for a very long time it will trigger the safemode In order to track the oldest uop for each thread the oldest uop is selected based on RR policy from thread active scheduling.
Nextretired pointer toggle per thread.
In order to optimize timing the nextretired rid and nonflushrid of two thread are selected basing RR policy before sending to ISSQ entry And uop in entry compare the rid to generate block.

#### 1.1.3 Branch flush in SMT mode

In order to reuse branch flush path in whole CPU Branch flush buffer is designed to solve flush conflict in one cycle.

##### 1.1.3.1 Flush rid change

Because of ROB entry will be reduced in SMT mode so the generated flush RID will be changed as well.

#### Figure 112 flush rid change compare

The picture shows branch rid compares to mac rid range Similar cases of which is releated with the resource partition and mtpart is utilized to separate MT logic includes 1 OSW speculative pick AGU IQ in MT mode 2 the computation of SIDLID plus 1 BRU.

##### 1.1.3.2 SMT branch flush E1

#### Figure 113 Branch flush @E1 in SMT mode

BRU flush requests of different threads at E1 stage is selected according to roundrobin rule The payload of BRU which is not selected is written into the flush buffer.
BRU flush requests of the same thread at E1 stage is selected according to the rid and the BRU flush corresponding to the oldest rid is selected to send out flush at E1 stage flush at E2 stage.
If the flush carries out the resolve signals of the flushed BRU to OoO would be canceled resolve @E2 stage.

##### 1.1.3.3 SMT branch flush E2

#### Figure 114 Branch flush @E2 in SMT mode

The flushed thread and the thread which written into flush buffer are determined at E1 stage At E2 stage the flushed thread is sent out as shown in Figure 115 T0 flush is sent out Meanwhile the thread of the flush buffer T1 also send the flush or buffer resolve signal to OoO because the bru resolve signal is sent out to OoO at E2 stage OoO needs to obtain the flush thread information of the next stage to avoid the wrong commit.
The rid and thread of the flush buffer are compared with the flush requests of BRU012 at E1 stage It can be divided into 3 cases roughly.
Case I The thread of flush buffer is different with all the other flush threads 1 the flush of the flush buffer is sent out 2 the BRU flush corresponding to the oldest rid is selected among the other flush threads and written into the flush buffer.
Case II The thread of the flush buffer is same with all the other flush threads the oldest rid of all the flush request should be determined and the corresponding thread flush is sent out.
Case III The thread of the flush buffer is partly same with the other flush threads the flow can refer to 1532.

##### 1.1.3.4 SMT branch flush E3

#### Figure 115 branch flush E2 in SMT mode

At E3 stage the flush behavior is the same as single thread.
Through branch buffer the flush path can be reused but one thread flush signal may delay one cycle.
Specifically E2 flush I2 blue arrow means the flush in the BRU utilizes the flush signal at E2 stage.
FAT means the flush source as shown in Figure 113 E3 stage.

### 1.2 Clock and Reset

#### 1.2.1 Overview

In order to implement Clock MESH each box like IEX is required to implement no more than 2 levels of clock gating In this case the common WFX controlled clock down RCG is moved out of IEX So inside IEX a flops clock may have up to 2 level of clock gates.
1 the regional clock gates known as RCG are manually instanciated ICG linxstdicg cell which normally control a large number of flops as a whole such as the whole queue or a bank of a big structure etc.

2. the synthesis generated clock gates by instanciating such as DFFE DFFRE DFFSE cells form the second level of clock gates normally controlling a certain number of no less than two flops CP pin The 2nd level clock gate may also possibly be manually instanciated linxstdicg cell for example for the case where the tool cannot auto identify clock gating.

A flop may have only 1 level of clock gate connected either manually instanciated or synthesis tool auto inserted Very few flops have no clock gate at all eg some pipe vld signals.

2. level icg

#### 1.2.2 Reset

With the CORE scale bigger and bigger the Reset path for STA is harder to cover In this case the reset signal is bypassed and set to multi-cycle path with macro define.
All reset used in IEX comes from uiexrst module In this module the reset signal is connected to coreresetn The reset module would output corerstdone one cycle after coreresetn is deassert.

### 1.3 configuration override bits Related to IEX Function

#### 1.3.1 External Registers

##### 1.3.1.1 Processor RCG enable cpuactlrel1[61]

The register from OOO side at cpuactlrel1 forces the RCG registers enable to high.

##### 1.3.1.2 In order branch oooctlrel1[20]

The register from OOO side at oooctlrel1 force the branch executes in order.

##### 1.3.1.3 Speculative l3 sys reg access oooctlrel1[57]

The register from OOO side do not have OOOIEX interface.

##### 1.3.1.4 Max power mitigation

LSU adds configuration override bit to enable the max power mitigation and didt feature.
Lsumxpmctlr0el1[0] [43]

#### 1.3.2 Local Registers

##### 1.3.2.1 PAC AUT noflush iexctlrel1[0]

The issue of strip uop split from the basic aut instruction waits for nonflush rid.

##### 1.3.2.2 SYS SRC FORCE RDY iexctlrel1[1]

For a SYS uop in the issue queue fastQ when a nextretire rid is pointing to it it will be force ready when this configuration override bit set to 1.

##### 1.3.2.3 Reserved configuration override bit

8 bits Reserved flops for configuration override bits when required in case ECO.

##### 1.3.2.4 ISO configuration override bit iexctlrel1[6352]

##### 1.3.2.5 Safe mode Registers iexsafemodeel1[630]

The detail information for IEX safe mode please refer to chapter 14.

#### 1.3.3 Architecutre System Registers

##### 1.3.3.1 TBIT0SZT1SZZCR

##### 1.3.3.2 PAC Key

### 1.4 Safe mode

#### 1.4.1 Overview

Now in IEX there are too many kinds of block or cancel in issq and pipe In order to avoid some special cases that make IEX step into livelock Safe Mode is desigened to deal with the situations Following is overview graph.

#### Figure 171 safe mode overview graph

#### 1.4.2 Next Retire Keep Detect

This part is used to gating FlopLogic toggling in normal working status If IEX need Safe Mode to solve livedead lock and starvation problems the nextretirerid must keep unchanged several cycles Now the detect counter is 16 cycle.

#### 1.4.3 IQ Safe Mode

Following is the detail of IQ Safe Mode.

#### Figure 172 IQ Safe Mode

##### 1.4.3.1 Oldest Raw Ready Select

This part is to find out the oldest entry in issq and it may need step into Safe Mode when it is ready for many cycles but not picked.
Oldest Select In ALU ISSQ.
For normal ALU uops the oldest ones rid must equal to nextretirerid So there may be more than one entry can be selected We cannot use alu age matrix to find the oldest one.
For SPNZCV switch insert uops the rid may be not correct So it is recorded as the oldest one directly.
Raw Ready In ALU ISSQ.
| 1 | SRC must be ready |
| --- | --- |
| 2 | Not dummy uop |

## 3 No entryl2cmsrblkp0can be configured by configuration override bit

## 4 No entryl3csysblkp0can be configured by configuration override bit

Oldest Select In AGUSTD ISSQ.
Use age matrix to select out the oldest entry in AGUSTD issq.
Raw Ready In AGUSTD ISSQ.
| 1 | SRC must by ready |
| --- | --- |
| 2 | For STASTD uop no entryoswblkp0 |

##### 1.4.3.2 Safe Mode Block

T01 safemode detect.
1 Safe Mode detect condition the entryrawrdy of the oldest entry has no change reach the detect threshold.

2. Once safe mode detect Safe Mode exit until the oldest raw ready is zero or livelock is detected.
3. T0T1 Safemode detect by its own thread logic.

Safe Mode block

1. The thread which is detected firstly start block.
2. If two threads all enter safemode and the first thread cannot exit safemode when block reach block threshold then the block will toggle to the other thread.
3. If two thread all enter Safe Mode and the first thread exit Safe Mode before block reach block threshold then the block will toggle to the other thread.
4. If only one thread enter Safe Mode block is cancel until the oldest raw ready is zero or livelock is detected.
5. When oldest raw ready is zero or Live Lock is detected the block will cancel after 2 cycle.

6 Safe Mode Block will block all entry excep oldest entrys and S1 stage in corresponding ISSQ.

7. For AGUSTD ISSQ Safe Mode oldest uops are picked in age order this may be different from inst order.
8. When ALUSTD ISSQ step into Safe Mode IEX will give FSU the block signal until Safe Mode exit.

##### 1.4.3.3 Live Lock Request

Live Lock detect
1 Every 16 cycles configuration override bit configured Safe Mode Block will give Live Lock counter one pulse.

2. Live Lock detect condition the Live Lock counter reach Live Lock threshold.
3. Live Lock counter enable condition.

When block threshold is detected Live Lock counter increase.
When Live Lock is detected Live Lock count became zero next cycle and give out the Live Lock Request.
When safemode exit Live Lock count became zero next cycle.

5. If Safe Mode exit by itself 2 cycle before timeout timeout and Live Lock Request remain to generate.
6. When Live Lock is detected Safe Mode timeout and exit Safe Mode will start detecting again.
7. Any ISSQ can send out the live lock request.

When OOO receives IEX livelock OOO will.

#### 1.4.4 PP Safe Mode

Because IQ Safe Mode only solve the problems in its own ISSQ but now in IEX there are still some other blockcancel between ISSQ like I2F cancel and read conflict cancel And the two type cancels both have the highest priority between two pipe arbitrations So Pipe Safe Mode is designed to deal with cross ISSQ livedead lock or starvation.

#### Figure 172 PP Safe Mode

##### 1.4.4.1 Pipe Safe Mode Block

For ALU012 pipe 0 there are I2F cancels If I2F uop is picked at P1 and VFP Load is executing at E1 stage at the same time the I2F uop will be canceled And if too many VFP Load uops always cancel the I2F uop the I2F uop in the ALU ISSQ needs step into Safe Mode then block the corresponding AGU ISSQ to cut off the VFP LD uops executing at E1 stage.
For LDASTASTD pipe because of RF read port sharing if LDASTASTD uops need read RF and ALU uops also need read RF at the same time ALU uops have the highest priority and LDASTASTD need to be canceled If LDASTASTD uops are canceled too many times they need to step into Safe Mode and block the corresponding ALU ISSQ to cut off the ALU ISSQ picking ALU uops The read port sharing stratege can be found in 63.
Pipe Safe Mode Working Flow.
1 Pipe Safe Mode detect condition the I2FRead Conflict cancel happened every two cycles reaching the detect threshold 48 cycles which can be configured by configuration override bit.

2. Once pipe safe mode detect pipe will send out the block signal to its own ISSQ.

3 If ISSQ step into Safe Mode as well ISSQ will send out the cross module block to the corresponding ISSQ according to thread selecting logic can be configured by configuration override bit.
4 Safe Mode exit until the uop can be deallocated from ISSQ.

5. T0T1 Safemode detect by thread every cycle.

AnexampleofIQALU0blockIQAGU0withI2Fsafemodeblock.
IftheIQALU0I2Fsafemodeblockisdetectedont0threadtheblocksignalissenttoIQAGU0Bydefaultconfigurationthist0I2FsafemodeblockwillblockIQAGU0oneblockwindow1631cyclesconfiguratableandthentoggletot1blockwindowIfnot1I2FsafemodeblockfromIQALU0toIQAGU0IQAGU0willhaveawindow1631cyclestoissueBysettingchickenbittocrossthreadblocksysiexalupipesafeisovld0thenIQAGU0willbeblcokedallthetimeinbotht0blockwindowandt1blockwindow.

##### 1.4.4.2 Cross Module Block Relationship

The cross module block relationship is shown at Figure171 and can be summarized as folloing.
ALU0 ISSQ block by LDA0 read conflict cancel LDA1 read conflict cancel.
ALU1 ISSQ block by LDA2 read conflict cancel STA0 read conflict cancel STD1 read conflict cancel.
ALU2 ISSQ block by STA1 read conlinct cancel STD0 read conflict cancel.
AGU0 ISSQ block by ALU0 I2F cancel.
AGU1 ISSQ block by ALU1 I2F cancel.
AGU2 ISSQ block by ALU2 I2F cancel STD1 read conflict cancel.

#### 1.4.5 Safe Mode Not Solved Problems

Safe Mode not handle the situations that the core have function bugs causing IEX control signals not right like LD always miss L3 not return data and etc.

##### 1.4.5.1 S1 Speculative pick and LD cancel

At S1 stage uops do not establish the dependency relationship so if LD miss happened the S1 pick chain may be not stop And in this situation safe mode may be not detected but the chain can be cut by rob stall.

#### Figure 173 S2 Pick Chain

### 1.5 Register Parity Check

In order to find out the silicon defect which causes CPU work abnormal quickly many boxes including IEX add register parity check function.

#### 1.5.1 Error Record and report

IEX box support parity check.
When a box detects parity error it will send signal to LSU a RAS Node to record error information.
LSU will record the Error in RAS registers Error status is updated according to the ALU parity Error SERR syndrome Record the Error information in the errmisc1.
Its Uncontainable UC error and has the highest priority in the error recording The overwriteclear rule is following the Arm RAS Spec.

#### 1.5.2 DisableEnable Control

The protection logic will No Doubt introduce additional power consumption It is not sure if this is acceptable in all productline so hardware introduces a control bit to disable this function by default The control bit should be stable before the reset removal.
The function is controlled by SoC system register signal name is advraseni The requirement is just like the RVBAR Its asynchronous with the CPU clock The synchronization circuit should be placed at L2C And then go to the IEX It needs to add some pipe stage according to the floorplan for example LSM in 1 stage LSM out 1 stage and 1 stage for the IEX.

#### 1.5.3 IEX Protection Mechanism

##### 1.5.3.1 MicroArch Description

The parity calculation logic is implemented both in dispatched payload information in ISSQ at S1 stage and the same payload information in pipeline at I1 stage.
Protection structure In ISSQ the entryinfo part will be protected by parity check Refer to 1534 for parity fields to be protected.
Perform the XOR operation on all bits that need to be checked during S1S2 Considering the number of bits is too large perform partial XOR processing at S1 stage And perform the rest XOR operation and write parity result to each ISSQ entry at S2 stage.
Add onebit parity to each entry Write the XOR result to the parity bit during S2 stage.
For timing consideration the RAS check is reported only when the instruction is not STD instruction not I2FF2II2PP2I instruction not SYS instruction not literal load instruction and not bru fusion instruction.
All protected bit will calculate the parity bit again on pipeline I1I2 stage again by using the value read from ISSQ entry.
For timing consideration only partial XOR is performed at I1 stage on the pipeline The rest XOR is perform at I2 The I2 result are compared with parity bit read from ISSQ which is calculated at S2 stage and then reported if the the comparison results are not match at E1 stage to LSU.
For ALU DP it uses a special algorithm named MOD3 to protect register parity.
The IFU also has parity check logic and IFU sends the parity check result to the IEX first IEX need to combine the parity result from IFU and IEX together and then to LSU IFU Error has higher priority than IEX so if both IEX and IFU have parity error only IFU error results are reported to LSU.

##### 1.5.3.2 Error report priority

When multiple error happens IEX only report one of them according to error priority IFU has higher priority than all IEX error In IEX module mod3 has higher priority than ISSQ In ISSQ ALU has the highest priority while BRU has the lowest priority.
Ifu > iex
| iex | mod3 > issq |
| --- | --- |
| issq | alu > agu > bru |
| alu | alu 0 > alu 1 > alu 2 > alu 3 > alu 4 > alu 5 |
| agu | lda 0 > lda 1 > lda 2 |
| bru | bru 0 > bru 1 |

##### 1.5.3.3 Error Information

ISSQ error information.
| Bit | Domain |
| --- | --- |
| [15] | valid |
| [1412] | 3b010 IEX |
| [118] | 4b0000 ALU0 |

4b0001 ALU1
4b0010 ALU2
4b0011 ALU3
4b0100 ALU4
4b0101 ALU5
4b0110 AGU0
4b0111 AGU1
4b1000 AGU2
4b1001 BRU0
4b1010 BRU1
| [70] | ISSQ entry index |
| --- | --- |

Mod3 error information.
| Bit | Domain |
| --- | --- |
| [15] | valid |
| [1412] | 3b010 IEX |
| [118] | 4b0000 ALU DP 0 |

4b0001 ALU DP1
4b0010 ALU DP 2
4b0011 ALU DP 3
4b0100 ALU DP 4
4b0101 ALU DP 5
| [7] | 1b1 |
| --- | --- |

[60]

##### 1.5.3.4 Parity Fields

###### 15341 ALU ISSQ

ISSQ ALU 03
ISSQ ALU 14
ISSQ ALU 25

###### 15342 AGU ISSQ

###### 15343 BRU ISSQ

### 1.6 Interface

Refer to target core Interfacexlsx.
[internal reference removed]

### 1.7 Physical Design Information

#### 1.7.1 Block Level Floor Plan

The bounding constraints for floorplan.
| 1 | The iq alu order is 142503 |
| --- | --- |
| 2 | The agu wake and bru wake should surround the alu wake |
| 3 | Alu dp surround the bn |
| 4 | Bn near lsu port |

Example
Boundings in floorplan with RP regfile.
Floorplan of IOE

#### 1.7.2 List of compiler memory

None

#### 1.7.3 Register count for major modules inside the block

### 1.8 Fast STLF Support in IEX

#### 1.8.1 Overview

Fast Store to Load Forward FSTLF is a performance feature added since earlier-generation core to accelerate storetoload forwarding in IEX Instead of performing storetoload forward in LSU most of the operations are carried in IEX A fake load is created inside IEX to perform forwarding store data to the original load consumers.
A typical FSTLF operation takes place at this particular scenario when A>Store [B]>Load [B]>C patterns occurs the store data can directly forward to all load consumers directly.
Compared to conventional storetoload forwarding FSTLF inserts a mov fake load instruction that forwards STD results to all load consumers and void the original load instruction.
In most cases a typical FSTLF process can save four cycles compared to conventional storetoload forwarding The main cycle saved is mainly from match and forward in the LIQ and store queue.

#### 1.8.2 Interfaces

A FSTLF involves four major steps.
IEX calculates the issue queue index of STD that pairs the STA uop.
LSU detection of STA and LDA dynamic address matches and send load result ptag back to corresponding STD issue queue.
IEX picks the STD and pretend to be a fake load to wakeup its consumers.
The FSTLF data is forwarded to load consumers and both STD and Load are resolved.
The principle of performing FSTLF in IEX is to make a matching STD uop to become a fake load in the pipeline.

##### 1.8.2.1 IEX to LSU STA interface

For the store address pipeline STA the IEX needs to send LSU the corresponding STD Issue queue index that pairs the STA.
During S2 stage IEX calculates the IQ index of the STD and updates in the STA IQ entry This IQ index is treated as STA payload index and is sent to LSU store queue in I2 stage If the IEX is not able to calculate the IQ index for STD then the store instruction is disabled for FSTLF.
Due to implemention complexity when the store address and data are dispatched in adjacent cycles IEX could not calculate the STD index easily Therefore the current implementation disable FSTLF when the STA and STD are in different cycle.

##### 1.8.2.2 LSU to IEX load interface

When LSU detects a load and store address matches it sends all the payload that associates the load uop back to IEX.

##### 1.8.2.3 IEX to LSU STD interface

When LSU decides to send FSTLF request to IEX the STD payload is updated by LSU.
Then the STD is picked to pipeline and it is sent to LSU at i2 stage Besides typical STD operations the LSU uses fstlfvld information to deallocate or reactivate the matching load uop If it is a successful FSTLF it means IEX successfully performs the fake load then the LSU can safely deallocate the original load instruction in LIQ If it is a failed FSTLF it means IEX successfully performs the fake load then the LSU can safely deallocate the original load instruction in LIQ.

##### 1.8.2.4 IEX to OoO and FSU interface

When the STD is labeled as a fake load it also need to perform similar load cancel and resolve However the IEX do not resolve load uops directly LSU take the resolve Therefore additional resolve port is added in the IEXOOO interface.
Specifially for store>load>I2F>Floating point chain the fake load in IEX might also wakes up the uops in FSU Therefore the load cancel in IEX fake load should also be sent to FSU The FSU needs to select the real load cancel and fake load cancel from IEX.

#### 1.8.3 STASTD Index calculation

UOP index calculation involves two steps.

1. S1 stage STA IQ write calculates STD slot index.

The STD slot index is defined as alu0slot01 alu0slot12 alu0slot13 alu3slot04 alu3slot15 alu3slot16 and this slot index is flopped to select the allocated STD index in S2 stage.
In S2 stage the STD IQ index is available from the IQ free logic The free index is selected based on the slot index in STA pipeline The STD index is then written in STA IQ payload.

#### 1.8.4 LDASTA match and relabel

The following conditions are used to determine whether a FSTLF uop can perform in LSU.
| Matching condition | Note |
| --- | --- |
| Load type allowed | Only the following load types are allowed for FSTLF |

LDRLDRAALDRABLDRBLDRHLDRSBLDRSHLDRSWLDTRLDTRBLDTRHLDTRSBLDTRSHLDTRSW
| Store type allowed | Only the following store types are allowed for FSTLF |
| --- | --- |

STTRBSTRBSTURBSTRHSTURHSTRSTTRSTUR
| Store address ready but data not ready | A strict window can perform FSTLF |
| --- | --- |

STA ready first then LDA ready but STD not ready.
If STD is ready then no FSTLF is performed.
| Load address Store Address | Load address and store address should match strictly |
| --- | --- |
| Load size < Store size | Load size should be within store data range |
| Load type Store type | Load store should always be INT and no pairs |
| FSTLF disable | FSTLF is disabled at IEX and configuration override bits |

When a LDA uop arrives in LIQ in E2 if the matching store is E4 and E4 later it also misses the window of load/store forwarding.
After all the conditions met for FSTLF the LSU sends the payload back to STD issue queue and writes to the corresponding STD IQ entry This will make the STD to become a FSTLF uop The STD uop is updated with the original load destination ptag so that it can use to wakeup load consumers.
When the LSU updates IEX STD IQ entry it needs to make sure that the STD entry is from the original store instruction that has the same address with the load The STD uop can be picked and deallocated at any time The LSU needs to make sure the FSTLF ptag update is within a strict window The window is shown as follows.
The boundary of the STD payload update window is given by.
Latest update opportunity FSTLF load ptag update at E4 and the STD uop is at E1 stage and the next uop after STD is at S2 stage the next uop has not yet written in the same entry.
Missed update FSTLF load ptag update at E4 and the STD uop is after P1 stage so that the payload is already picked to pipeline The update to STD entry has no impact on the STD uop that has already picked However if the STD uop is cancelled in the pipe the repicked STD uop can carry the FSTLF payload again.

#### 1.8.5 IEX FSTLF Pipeline

When STD uop is updated with FSTLF payload it becomes a fake load in the IEX so that it can wakeup all its consumers and bypass STD data to consumers In order to become a fake load the FSTLF uop needs to squash the load uop at I2 stage at corresponding load pipe.
The full FSTLF pipeline in target core is shown in the following figure.

1. Load pipe I2 arbitration Each load pipe needs to select four sources at I2 stage two STD FSTLF result ptags repicked load ptags and issue load ptags The merged ptags are used to wakeup consumers that E1 stage The FSTLF uop has higher priority over repacked and issued load uops.
2. FSTLFSTD arbitration Two STD FSTLF uop might squash the same load pipe at the same time Only one FSTLF uop is allowed to success The other FSTLF uop is cancelled for FSTLF but its STD uop is allowed to continue A roundrobin arbiter is used to select two STDFSTLF uop is allowed to pass The RR schedule is flipped if one of the FSTLF uop is cancelled.

FSTLF Bypass The FSTLF uop needs to multiplex two result data from STD pipeline in E1 stage and perform signsize extension operation on behalf of the load it is represents The resulting data is then bypassed to all load consumers in their I2 stage.
Forbid FSTLFLoad W1 Bypass Due to timing constraints the merged STD data in the LSU could not bypass to load I2 directly in W1 stage Therefore we do not allow backtoback FSTLFLoad pattern If a load uop in I1 stage is immediately following a FSTLF in its W1 stage then this load is cancelled.

#### 1.8.6 FSTLF Cancel

##### 1.8.6.1 FSTLF Cancel types

The FSTLF is a special status for STD status There are three scenarios for the STD cancel.

1. STDFSTLF is cancelled by.

Load cancel
Regfile read conflict cancel.
SID evenodd arbitration cancel.
The STD uop is cancelled and repicked after four cycles and it can perform FSTLF again.

2. STDFSTLF is cancelled by FSTD cancel DVM cancel The STD is cancelled and loses the FSTLF flagpayload After the repick the STD could not perform FSTLF operations any more.
3. FSTLF is cancelled by FSTLF load pipe arbitration If two FSTLF wants to occupy the same load pipe one of the FSTLF uop is cancelled However both STD uop can continue but only one could not perform FSTLF operation.

##### 1.8.6.2 FSTLF Repick window

If a STD uop is tagged with FSTLF flag it is similar to a fake load uop Therefore it needs to comply to the 4-cycle repick window similarly to a load This is because a load vector in the consumers should be cleared after a load cancel.
Therefore if a STDFSTLF uop is cancelled it should not be immediately repicked A special wait counter is added for each STD issue entry If a FSTLFSTD uop is picked the counter is activated After the counter is set it needs to wait for the counter to reset in order to be picked.
The STD uop might also be blocked by the SID evenodd arbitration when picking to the store queue Therefore for some cases the STD might wait for five cycles in order to be repicked.

#### 1.8.7 FSTLF Flush

The FSTLFuop carries two ROBIDs one is load ROBID and one is STD ROBID It needs to consider both cases for the load and store when flushed.

##### 1.8.7.1 FSTLF Flush in IQ Entry

For all entries in the STD issue queue it needs to consider three senarios.

1. For ROB flush all entries are cleared in the IEX for the given thread.
2. For BRU flush STD RID older than Flush RID older than Load RID the STD entry loses the FSTLF flags.
3. For BRU flush Flush RID older than STD RID older than Load RID the STD entry is cleared in the IEX for the given thread.
4. For BRU flush STD RID older than Load RID older than Flush RID no entries are affected.

##### 1.8.7.2 FSTLF Flush in pipeline

After the STDFSTLF uop is picked both load ROBID and STD ROBID are carried Both ROBIDs are used for checking the flush signals in I1 and I2 stage.
The fstlfvld is a valid signal that qualifies the flushes in R3R4R5 The fstlfspecvld does not look at flush signals LSU can tell whether the FSTLF is successful if two valids are different Based on the information the LSU can safely deallocate LIQ if the FSTLF is successful.

### 1.9 MDB Support In IEX

#### 1.9.1 Overview

Conventional load store dependencies are enforced in the load store unit A buffer records the past load/store violations triggered by previous nuke flushes This buffer is called memory disabugation buffer.
Since target core the MDB is moved to IFU and IEX to reduce complexities The load store dependencies are performed similarly to instructiondependencies in IEX.
If a load uop is issued before a sta uop the load uop may read a wrong value from L1 instead of forwarding from the std uop Then the load uop reads the wrong value and it should be flushed to maintain correctness of the execution When a load retires to LHQ when STA comes and finds that a Load younger than itself has been executed the LSU will generate a nuke flush to when it becomes the oldest uop Frequent nuke flush would harm the performance significantly.
In target core the load/store dependence predication is handled in IFU Whenever a nuke flush occurs the group of load PC and store PC is marked with a flag which is called color This color information is carried to IEX to prevent a load uop issued too early before the depending sta uop The IEX records the last fetched store table to record whether the store uop with same color has been issued or not.
Whenever the load uop with the same color finds the store uop has not been issued this load uop is blocked in the AGU issue queue until the store uop is issued This ensures the load uop is issued after the store uop so that it can forward values from the store queue without generating nuke flushes.
Therefore there are four scenarios shown in following figure In this case the color mapping is performed according to the following figure This part involves the implementation of the IFU.

#### 1.9.2 MDB LFST Table

The MDB Last Fetched Store Table LFST is used to record the status of last fetched STA uop It is similarly to a scoreboard that tracks whether the last fetched store has been issued or not.
The LFST table is threadprivate.
Each LFST table per thread contains 15 entries which record the last fetched store RID indexed by colour ID Each entry contains two fields.

1. Entryvld indicates whether the color contains a valid STA before its E1 stage.
2. STARID indicates the ROB ID of the last fetched STA uop.

The LFST table is written twice when STA uops is at S1 and E1 stage For S1 stage the STA uop sets the entry vld to 1 and write its RID to entry For E1 stage the STA uop clears the entry vld to zero.
For a STA uop with a color ID it firstly sets the entry vld at entry colorID This indicates the LFST table contains a store with a color but its store address has not been calculated Then later load uops with same color ID will read from this color entry If the entry is valid then the load uop is blocked by MDB A STA uop might be cancelled before E1 stage its store address might be wrong therefore STA uop can only clear the LFST table when it is not speculative at E1 stage.

#### 1.9.3 MDB Mega Status

One extra flag is added for each load uop called mega If a load uop is mega then the LSU will make sure that this load needs to wait all previous stores to write into SCB before wakeup in LIQ The mega load uop is default as color zero IFUIEX.

#### 1.9.4 MDB Flow

The following figures shows the MDB implementation in the IEX.
For each STA uop at S1 stage.
STA uop sets the LFST entry vld and entry RID based on its ColorID.
Multiple STA uops might set to the same entry in LFST only the youngest store with the same color from eight uops writes to the LFST entry.
For each load uop at S1 stage.
Load uop reads LFST based on its ColorID.
If the LFST entry is valid it means the load uop needs to wait for a store uop with its RID.
For uop0uop7 from dispatch.
For all eight uops from dispatch there are multiple load uops and store uops with same colorID For the same cycle store uops in the same bundle should not update the LFST A 2dimentional inline vector is used to select the actual store uop RID and writes to the LFST entry.

#### 1.9.5 MDB Pipeline

The following diagram shows a basic MDB load store pipeline In IEX the sta uops need to wakeup depending load uops similarly to conventional uops STA uops wakeup load uops through RID but normal uop wakeup its consumers using ptags.
After a load uop reads the LFST table it needs to tell whether it needs to wait for a store uop with a RID If the LFST entry is vld for the load color the load uop carries the store uop RID and written to AGU issue queue The load uop is marked MDB not ready The load uop needs to be wakedup by the store uop by the RID.
Whenever a store uop is issued to pipeline it needs to broadcast its RID at E1 stage to all entries in AGU issue queue and entries LFST table Load uops that waits the store RID are marked MDB ready and they can be picked if their sources are ready.
The MDB wakeup does not support speculative pick STA uops might be cancelled at P1I1I2 stage Therefore STA uops can only wakeup at E1 stage when it is not speculative.
The MDB might also wakeup load uops at its S2 stage However due to timing constraints load uops in speculative pick S2P1S3P1 are default mdb not ready Therefore load uops with valid color are disabled for speculative pick if their color is valid.

#### 1.9.6 AGU Issue Queue Modification

One extra bit is added for each IQ AGU entry to represent whether a load instruction needs to wait for a store or not If it needs to wait for a store the load is blocked by MDBblock signal It needs to wait until the corresponding store to wakeup during the E1 stage If MDB color is not ready then the load instruction needs to wait for the store to wakeup.

#### 1.9.7 MDB configuration override bit

There is potentially a risk that a load uop might be blocked by MDB in unseen reasons Therefore we add configuration override bits to make sure that the load is not blocked by MDB indefinitely There are four configuration override bits added to protect MDB related mechanisms.
CKB1 MDB LFST Enable.
When the value is 0 the MDB function is disabled and the MDB ready value for all load instructions is forced set to 1 When the MDB function is disabled there will be many nuke flush generated This configuration override bit is enabled by default.
CKB2 MDB LFST Unblock Counter Enable.
To prevent a load is waiting for a store that do not exist we introduced a counter to unblock the load blocked by MDB This counter can help the execution to continue without deadlock.
This counter is starts to count when.

1. The oldest load uop in each AGU Issue queue is only blocked by MDB.
2. The values of NextRetire and NextRetireKeep of the ROB remain unchanged The ROB does not retire for a long time.

When the MDB unblock counter reaches the threshold an unblock signal is generated and the MDB block of the earliest load entry is set to 0 Then the load is unblocked and picked to pipeline.
CKB3 MDB LFST Unblock To Mega Enable.
This configuration override bit depends on CKB2.
When the MDB unblock counter reaches the threshold an unblock signal is generated and the MDB block of the earliest load entry is set to 0.
In addition the MEGA flag of the load uop corresponding to the unblock is set and sent to the LSU.
CKB4 MDB Unblock Counter Threshold.
This configuration override bits sets the threshold for the MDB unblock counter The current threshold for MDB unblock counter is set to 64 cycles.
