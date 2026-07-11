# IEX Overview and Structure

This document introduces the integer execution unit, feature scope, issue queues, execution pipes, register files, implementation-specific variants, and high-level structure.

> This Markdown document is the maintained specification. Sensitive metadata and internal reference labels have been removed while preserving architecture semantics.

## 1 Terminology

| Name | Description |
| --- | --- |
| Dispatch | Instructions are moved from OOO to IEX issue queues |
| Issue | An instruction is picked up from the issue queue and sent to the corresponding execution pipe |
| Pick | Interchangeable with Issue |
| Picked | Instruction is picked however its entry may still be occupied |
| Block a pick | A uop with all its sources ready but not being picked because of structural hazard or lack of resources available This uop is NOT picked so the oldest of the ready uops younger than it can be picked |
| Cancel | A uop is picked speculatively on to a pipe but for some reasons such as the source depending on a load is missed or regfile read port conflict with another pipe etc it need to be killed on the pipe pipe cancel and repick later Some cancels such as ldmiss cancel need to kill the source ready source cancel and wakup again |
| System instruction | Instructions that directly read or write system registers MRSMSR |
| AGU issue queue | The issue queue that holds load store address calculation uops |
| BRU issue queue | BRU issue queue holds branch uops |
| ALU issue queue | ALU issue queue holds alu system std and communication with floating point unit uops |

## 2 Overview

IEX Integer Execution Unit mainly handle schedule issue and execute all A64 integer data processing instructions such as addition subtraction multiplication division miscellaneous logic operations and so on branch instructions PAC Pointer Authentication Code instructions and a small part of SVE instructions MRSMSR instructions are scheduled and issued by IEX to miscllenious modules who has access system register entities All the load and store including integer FPSIMD and SVE instructions are scheduled and issued by IEX to LSU IEX and FSU worked together to handle the integer to floating point convertion and floating point to integer instructions.
After OOO decoded and break down the instructions to uops microoperation and dispatched to IEX the uops are buffered and scheduled by issue queues and issued to corresponding execution pipelines including integer data processing pipes like ALUs MACs DIVs etc and LSU pipes for LDST uops The source operands for data processing or LDST uops are reading from the integer register file andor CC register file or forwarded from bypass network The result of the execution pipes are written back to integer andor CC register file and resolve information are sent back to OOO rob for retiring the uops After executing the branch instructions IEX sends hitmiss target address and miscellenous predict informations to IFU and broadcast branch flush in case branch misprediction detected.
For detailed feature list of IEX please refer to Chapter3 more information Here we list the major highlights in the version of target core.
Support 8-wide dispatch from OOO to IEX.
Reorganized ISSQs and larger issue window 6 ALU ISSQs with 16 entries each 3 AGU for LD and ST address uops ISSQs with 14 entries each and 2 BRU ISSQs with 24 entires each.
Wider machine structures maximum issue width can be 6x 1-cycle ALU + 3x LD + 2x STA + 2x BRU13 in total uops in one cycle support 2x branch uops 2x MUL 1xDIV in one cycle.
ALU pipeline virtualization to support symmetric ALU ISSQs to ease the dispatch.
CAM-based ISSQ wakeup mechanism.
Larger integer regfile with write banking 2 banks with one bank per 3 ALU IQ Each bank has 6 write ports and 13 read ports Each bank has 136 entries with 272 entries in total.
This specification is organized as following Chapter2 is a brief of IEX main functions and features target core and the main changes compared to previous version such as previous-generation core In Chapter3 a detailed feature list is provided which is served as the design requests DR to IEX In Chapter4 the IEX main constructs and IEX pipeline stages are briefly illustrated respectively Detailed structural and functional descriptions of IEX main components such as Issue Queues Register files Execution pipes etc and main data structures issue queue packets dependence tracking vector etc are specified in Chapter5 In the following Chapters from Chapter6 onwards specific topics are addressed in detail in standalone chapters including special instruction handling mechanism such as IntegerFloatingPoint interactions Chapter6 system MRSMSR instructions Chapter7 fused instructions Chapter8 SVE instructions Chapter9 PAC instruction Chapter10 SMTChapter 11 Clock and reset processing is specified in Chapter 2 and so on implementation control bits related to IEX to config some optional behaviours are specified in Chapter 13 The safe mode mechanism which handle the livelock deadlock and starvation scenarios is described in Chapter14 Register Parity Check in Chapter 15 IEX interface with other modules in CPU core are briefed in Chapter Chapter16 while for the details of interface signals please referred to [3] Some physical design information are provided in Chapter7 finally.
For the IEX execution unit datapath arithmetic design so called IEX DP such as ALU BRU MAC DIV CRC and PAC etc are specific topics and addressed in another separated document please refer to [4]

## 3 IEX Feature List

This feature list is served as microarchitecture design requests DR to IEX.

### 3.1 IEX IQ

| IEX Feature ID | Feature Description | DRUT Mapping |
| --- | --- | --- |
| IQDISPATCH | There are 11 issue queues in IEX |  |

- 6 ALU issue queues IQALU0123456.
- 3 AGU issue queues IQAGU012.
- 2 BRU issue queues IQBRU01.
- Up to 8 uops can be dispatched from OOO to IEX ALU issue queues per cycle 3 uops per each ALU ISSQ.
- Up to 8 uops max 8LD max 6ST can be dispatched from OOO to IEX AGU issue queues per cycle 3 uops per each AGU ISSQ.

| Up to 6 uops can be dispatched from OOO to IEX BRU issue queues per cycle 3 uops per each BRU ISSQ | 1 |
| --- | --- |
| IQALU | IEX has 6 ALU IQs each of which has 18 entries Each one has 3 write ports and one picker |

- IQ ALU03 are symmetric.
- IQ ALU14 are symmetric.

| IQ ALU25 are symmetric | 345 |
| --- | --- |
| IQAGU | IEX has 3 AGU issue queues each of which has 14 Entries AGU01 has 2 pipes each LDA0STA0 and LDA1STA1 while AGU2 only has one pipe LDA2 The following uops can be issued |

1. LDA of all integer FPSIMD SVE LD and PRFM instructions.
2. STA of all integer FPSIMD and SVE ST instructions.

| 3 STA of special instructions AT DC IC TLBI CLREX CFP CPP DGH DSB DMB ESB SSBB PSSBB and RAMIDX | 345 |  |
| --- | --- | --- |
| IQBRU | IEX has 2 BRU issue queues each of which has 24 entries The two BRU IQs are symmetric and used to issue branch uops | 345 |
| IQS1breakuop | If the uop is broken up into two uops by OOO at dispatch S1 stage the two uops has dependence |  |
| load/store register offset breaks up into two uops one ALU uop and one LS uop at dispatch time However different with that of previous-generation core IEX will NOT switch alu uops dst ptag to LS uops src1 ptag 2nd src with flag given by OOO In target implementation instead OOO puts the right src1 ptag to LS uop and NO need for IEX to switch these ptags | 1345 |  |
| IQALUpicker | Each ALU ISSQ of IQALU0123456 has 1 picker |  |

- IQ ALU03 executed uops STD1C ALU without SRCCDSTCI2FF2I.
- IQ ALU14 executed uops 1C ALU with SRCCDSTCI2FF2I.

| IQ ALU25 executed uops 1C ALU with DSTC2cycle ALU including SVE data processing 2 or 3-cycle MUL68 cycle DIV DIV4cycle PAC uops1cycle MSR 47variable cycle MRS | 8 |
| --- | --- |
| IQAGUpicker | Each AGU ISSQ of IQAGU01 has 2 pickers plda picks LDA uops and psta picks STA uops IQAGU2 has 1 picker only plda picks LDA uops |

At most 3 lda uops + 2 sta uops can be picked in one cycle.
| At most 3 ld pair 3128-bit + 2 std pair 2128-bit can be issues in one cycle | 8 |  |
| --- | --- | --- |
| IQBRUpicker | Each BRU ISSQ of IQBRU01 has 1 picker to pick branch uops | 8 |
| IQALUdealloc | IQ ALU entry deallocation under two situations |  |

- If the uop depends or does not depend on Load it will be deallocated at I2 stage.
- If the uop does not depend or does not depend on Load it will be deallocated at I1 stage.

| The recycling of an entry S2toS2 is 3 cycles S2I1S2 or 4 cycle S2I1I2S2 | 9 |
| --- | --- |
| IQAGUdealloc | IQ AGU entry deallocation under two situations |

- If the uop depends or does not depend on Load it will be deallocated at I2 stage.
- If the uop does not depend or does not depend on Load it will be deallocated at I1 stage.

| The recycling of an entry S2toS2 is 3 cycles S2I1S2 or 4 cycle S2I1I2S2 | 9 |
| --- | --- |
| IQSTDdealloc | IQ BRU entry deallocation under two situations |

- If the uop depends or does not depend on Load it will be deallocated at I2 stage.
- If the uop does not depend or does not depend on Load it will be deallocated at I1 stage.

| The recycling of an entry S2toS2 is 3 cycles S2I1S2 or 4 cycle S2I1I2S2 | 9 |
| --- | --- |
| IQDPDE | DPDE Dependence Extended structure is used to identify the source operand is depending on which pipes result for the purpose of data bypass The DPDE is generated by OOO |

4b0000 ALU0
4b0001 ALU1
4b0010 ALU2
4b0011 ALU3
4b0100 ALU4
4b0101 ALU5
4b1000 LSU0X
4b1001 LSU0Y
4b1010 LSU1X
4b1011 LSU1Y
4b1100 LSU2X
4b1101 LSU2Y
| 4b1111 MOVI | 345 |  |
| --- | --- | --- |
| IQRDRF | It needs to know at P1 stage whether a source operand need to read from RF or get from bypass network so the exact wkup time of a src is needed Matrix-based wkup scheme has the wkup granularity at uop level instead of src ptag level It is very expensive to fully detect each source exactly waked up by which pipes producer at wkup time Upon the src wkup a 3-bit counter is used to count the bypass window 100W1> 010W2> 001W3> 000RF Only 000 is useful to decide whether read RF or not at P1 stage This RDRF signal could be more aggressive than it is really needed but if it is real needed to read RF it must be asserted | 345 |
| IQTK | At I2 stage the bypass window of a uop is required to be accurately identified This is done by exhaustively compare the source ptag at Wake up stage with all pipes W0 W1 and W2 stages so as to get the tk vector |  |

3b100 W1 forwarded to I2.
3b010 W2 forwarded to I2.
3b001 W3 forwarded to I2.
| 3b000 RF read out to I2 | 345 |  |
| --- | --- | --- |
| IQLID | Load ID is allocated by OOO during dispatch for each load uop in program order Each LID is corresponding to a LHQ entry in LSU When issueing a load uop IEX will send its LID to LSU IEX does not check whether LID is in LHQ sliding window In LSU the LIQ checks and controls the load to LHQ whether its LID is out of LHQ window or not | 8 |
| IQSID | Store ID is allocated by OOO during dispatch for each store uop in program order Each SID is corresponding to a STQ entry in LSU Before issueing a store uop IEX check whether its SID is within the STQ sliding window or not A store both STA and STD uop is issued only if it is within the STQ window | 8 |
| IQOSW | Sliding window mechanism is used to control which ST both STA and STD uops can be issued to LSU if a ST is too young age out of the sliding window OSW it cannot be saved in queues of LSU There are 3 OSW protocol options between IEX IQ and LSU early block early cancel and late reject In target implementation we use early block and early cancel schemes |  |

PS

- Early Block if the uop is OSW IQ will mark the uop NOT ready.
- Early Cancel if the uop is OSW IQPP will cancel the uop and repick later.

| Late Reject if the uop is OSW IQ will issue and LSU reject and wk later then IEX will sleep and repick upon wk from LSU NOT used in target implementation release | 7 |  |
| --- | --- | --- |
| IQFSTDblk | STQ write ports are shared between fSTD with iSTD and fSTD has strict priority If fSTD uop send out FSU will send the corresponding pipe cancel to IEX and IEX iSTD uop will be cancelled and repicked later | 7 |
| IQSDOOR | For a load or store uop in LSU did not get PA successfully LSU will hijack the IEXLSU issue pipe to reissue from LIQ this load/store uop for TLB thanslation again This is so called sidedoor interface between LSU and IEX When a normal LS op issue at I1 from IEX meets LSUs sidedoor I1 vld IEX will kill this issue on pipe suppress the src I2 data to all 0 and revert the state to unpicked in IQ And LSU will locally mux in the sidedoor VA to replace IEX src The fastest IEX repick of this killed op will be the next cycle after this sidedoor kill p1> i1sidedoor killed >p1 The fastest repick of the reissued op via sidedoor will also be the next cycle after sidedoor i1sidedoor>i2repick Lda012 and sta01 can all support sidedoor | 7 |
| IQLDRepick | For a load uop in LSU got PA successfully but failed in return data results LSU will repick from LIQ to IEX E1E4 LD pipe If at the same cycle a LD op is picked by IEX on the same pipe the repick will win the pipe to wake up consumers and if the repick LD is hit write back RF The op issued by IEX will NOT go on LSU pipe but it will be written in LSU LIQ to repick later So from IEX view this LD op also issued normally and the entry will be deallocated as usual The fastest repick after LD miss is repick E1 at E5 E3miss E4 repick E1 | 345 |
| IQLATblk | Instructions executed on pipy of ALU25 can write data back to register file at E1 E2 E3 or E4reflowE4 stage Because one result bus and register file write port is shared by differentlatency pipes an instruction ready to be issued by pipy may need to be blocked if an earlier multi-cycle instruction reserved the result bus at the same cycle and result in WR sharing conflict The pipelatencyblock vector 3bits is used in issq to block a uop from being picked |  |

[0] 1-cycle uop P0 is blocked by 234-cycle uop P1I1I2.
[1] 2-cycle uop P0 is blocked by 34-cycle uop P1I1.
[2] 3-cycle uop P0 is blocked by 4-cycle uop P1.

- A 4-cycle uop at P1 blocks a 3-cycle P0 at I1 blocks 2-cycle P0 at I2 blocks 1-cycle P0.
- A 3-cycle uop at P1 blocks a 2-cycle P0 at I1 blocks a 1-cycle P1.

| A 2-cycle uop at P1 blocks a 1-cycle P0 | 7 |
| --- | --- |
| IQReflowblk | DIV32bit 6-cycle 64-bit 8-cycle FMRS Far MRS 7-cycle need a second flow reflow on pipe to avoid too long pipe staging |

- DIV has the highest reflow priority and block FMRS L3MRSMSR reflows.
- L3C MRSMSR variable latency must drop the first flow and reflow on pipe only when ack from L3C with unpredictable latency is received L3MRSL3MSR first flow does not block any other uops but only block a new L3MRSL3MSR uop in oneoutstanding mode For multioutstanding mode L3C MRSMSR is blocked when the FIFO is full.
- A FMRS ie L2C MRS reflows as a 4-cycle uop At its first flows E2 it goes back on pipe as reflow I2 A FMRS reflow will win priority if conflict with a L3C MRSMSR reflow request but will yield priority to DIV reflow requests For the same pipe aluy At most two concecutive cycles for reflow FMRS can happen because FMRS reflow blocks all uops including new FMRS itself Three alu pipes alu012y shares one MRS execution pipe so the FMRS reflow from three pipes can possibly fully occupy the MRS pipe for a long time.

| A L3MRS or L3MSR relfows as a 4-cycle uop It tries to go to pipe as reflow P0 stage once L3 ack flop 1-cycle before use asserted But it can be blocked by FMRS and DIV reflow in a reflowpending state till all FMRS and DIV reflow blocks are released | 7 |
| --- | --- |
| IQPWRblk | To fast response dynamic IRdrop in order to resolve power integrity issues CPU will slow down execution immediately to reduce current drain once the power supply voltage dropping below a threshold is detected by an onchip monitor CPM critical path monitor For IEX this slow down means blocking any uop from issuing in the following issue queues |
| All uops in IQALU IQAGU and IQBRU are blocked from issuing at DROPFLAG@N+5 | 7 |
| IQRTABI | The irdy table stores each int ptags ready state 1 means the ptag is nonspeculative ready 0 means the ptag is NOT nonspec ready yet |

- It is an 136entry1bit 2bank structure For each bank it has 4 clear port from OOO and 10 write ports from nonspec wkup ALU6+ AGU dstx+dsty 3pipe W1 stage + 1 movi at S1 stage And 16 8uop2src read ports from OOO at S1 stage per bank.

| Clear ready bits has higher priority over set ready bits | 345 |
| --- | --- |
| IQRTABC | The crdy table stores each CC ptags ready state 1 means the ptag is nonspeculative ready 0 means the ptag is NOT nonspec ready yet |

- It is comprised of 2 banks 72entry1bit realreg cc rc table and one 72-entry 1-bit tempcc tc table structure The rc table has 4 clear port from OOO 4 write ports from nonspec wkup ALU1245at W1 stage and 8 8uop1src read ports from OOO at S1 stage The tc table has 4 clear port from OOO 2 write ports from nonspec wkup ALU25 at W1 stage and 8 8uop1src read ports from OOO at S1 stage.

| Clear ready bits has higher priority over set ready bits | 345 |
| --- | --- |
| IQRTABP | The prdy table stores each for SVE predicate ptags ready state 1 means the ptag is nonspeculative ready 0 means the ptag is NOT nonspec ready yet |

- It is a 96entry1bit table 6 clear ports from OOO 4 write ports from nonspec wkup ALU25 at W1 stage + 2P2I at W0 stage and 8 8uop1src read ports from OOO at S1 stage.

| Clear ready bits has higher priority over set ready bits | 345 |
| --- | --- |
| IQSAFE | The safemode is the final protect mechanism to solve the starvation and livelockdeadlock problems in IEX mainly caused by arbitration schemes in both ST and MT modes This mechanism includes mainly two processes safemode detect and safemode block |

The safemode detect is to identify the abonormal conditions that result in a uop in IQ cannot be issued to pipe Normally the oldest uop in an IQ was waked up and some issue conditions satisfied so called in rawready state but for a long time reach a configured threshold it cannot be issued In target implementation we further consider the condition the oldest uop in an IQ or globally even not ready but for an expected long time cannot be issued for example the force ready mechanism for MRSMSR.
The safemode block is to give the detected uop opportunities to be issued by blocking other uops pick ready.
| target implementation nextretire IQ aluagu what is oldest | 17 |
| --- | --- |
| IQXFM | XFM stands for for Xform indicating a 64-bit result uop |

- src XFM is an attribute of a source operand which depends on a 64-bit result XFM producer while dst XFM is an attribute of a dst of which the uop has a result of 64-bit.

| Both src and dst XFM flags are used for power saving purpose The src XFM is used when reading a source opperand whether the high 32-bit data are required to flop or qualify to 0 The dst XFM is used when writing a result in BN or RF whether the high 32-bit data are required to flop When XFM 1 64-bit high 32-bit flop enable is on when XFM 0 high 32-bit flow enable is off and data qualified to zero for source operands | 8 |
| --- | --- |

### 3.2 IEX Pipes

| IEX Feature ID | Feature Description | DRUT Mapping |
| --- | --- | --- |
| PIPES1 | IEX Pipeline S1 stage |  |
| ISSQ packet field reuseoverload is composed S1 wkup is done on uop packet at wm2wm1w0w1 stage | 1 |  |
| PIPES2 | IEX Pipeline S2 stage |  |
| In this stage Micro Operations uops from OOO are written into IEX issue queues | 345 |  |
| PIPEP1 | IEX Pipeline P1 stage |  |
| The oldest from the order of writing into ISSQ uop with all its operands ready is picked from the issue queue and sent to corresponding execution pipes The earliest pick can happen at S2 stage | 101112 |  |
| PIPEI1 | IEX Pipeline I1 stage |  |
| The source operands of the picked uops are being read from the register file Source ptags are sharing read ports at I1 | 101112 |  |
| PIPEI2 | IEX Pipeline I2 stage |  |
| The source operands of the picked uops are selected from the bypass network either from the register file or forwarded from pipe results | 101112 |  |
| PIPEEX | IEX Pipeline E1E4E6 E8 Exvariable execution stage |  |
| Uops are issued to multiple pipes executed in parallel The latency of the pipes varies from 1 4 7 and variable cycles For 7-cycle FarMRS 6-cycle 32-bit DIV 8-cycle 64-bit DIV and variablecycle L3C MRSMSR latency uops it will reflow as a 4-cycle uop For ST uops sta and std uops IEX will not write back regfile but they will send addrdata to LSU at I2E1 stage respectively | [redacted numeric sequence] |  |
| PIPE6ALU | IEX has 6x 1-cycle ALU pips + 2x 2-cycle ALU pipes |  |

Support up to 6x 1-cycle ALU or 4x 1-cycle + 2x 2-cycle ALU per cycle.
ALU012345 1-cycle basic incl flatset ALU.
ALU14ADRADRP Splited ALU uops from BLRBL and ALU With SRCCDSTCC.
| ALU25y 1-cycle all ALU except ADRADRP and ALU with SRCC 2-cycle ALU MUL DIV SYS PAC | 102829 |  |
| --- | --- | --- |
| PIPEVirtualization | The ALU25 IQs are completely symmetric that is they can accept exactly same kinds of uops from OOO So each IQ ALU sees same number of execution resources such as DIV PAC and SYS logically although they are physically shared by the IQ pipes | 1028 |
| PIPEPartialALU1C | To optimize PPA the shift extr CCMNP CSEL CLZS and rev instructions are reduced to 3 1-cycle ALU ie pipey of ALU012 pipes instead of 6 | 10 |
| PIPEALULatency | The multi-cycle pipes ALU25 generate the result at various stages |  |

- 2-cycle ALU instructions result at E2.
- 2-cycle CRC instructions result at E2.
- 2-cycle MUL32 instructions result at E2.
- 3-cycle MUL64 instructions result at E3.
- 4-cycle MRS instructions result at E4.
- 5-cycle PAC instructions result at E4.
- 7-cycle MRS instructions result at E7 reflow E4.
- 6-cycle 32-bit DIV result at E6 reflow E4.
- 8-cycle 64-bit DIV result at E8 reflow E4.

| variable latency instructions L3C MRSMSR result at reflow E4 | 10 |  |
| --- | --- | --- |
| PIPECRC | CRC instructions are 2-cycle pipelined instructions and can be sent to ALU25 IQs Each IQ can issue CRC uop to its pipy and each pipe has 1 CRC execution unit CRC does not support fastforward | 1018 |
| PIPEMAC | 2x MAC units and one for each pipy of ALU25 IQs MAC instruction must be broken down to 2 uops 1 MUL + 1 ADD because of the IQ src ptag number limitation is 2 32-bit MUL uop is 2-cycle latency and 64-bit MUL is 3-cycle latency So the MAC32 including MADD32 MSUB32 SMADDL SMSUBL UMADDL UMSUBL is 3-cycle latency in total and MAC64 including MADD64 MSUB64 is 4-cycle latency in total The SMULH and UMULH uop are 3-cycle latency |  |
| MAC32 and MAC64 in fact support fastforward because of uop breakdown | 10 |  |
| PIPEDIV | Divide execution is latencyoptimized and pipelineoptimized The New DIV Algorithms support pipelined DIV uops | 1028 |
| PIPEDIVLatency | target core integer divider uses veryhigh radix DIV algorithm with fixed latency for both 32-bit and 64-bit DIV |  |

32-bit DIV latency6 throughput 1.
| 64-bit DIV latency8 throughput 1 | 2810 |  |
| --- | --- | --- |
| PIPESYS | 1 | MRSMSR uops are issued inorder This is implemented by tmpcc pseudo dependence |
| 2 | One MRSMSR pipe is shared by ALU25 IQs For single thread only 1 MRSMSR uop is ready for issue |  |
| 3 | target core MRSMSR can be issued backtoback | 81019 |
| PIPEMRS | OOO will tell IEX whether a MRS uop is speculative or nonspeculative Most of the MRS can be speculatively executed but a few like giccjar hivector saveop MRS RNDR and L3C registers by default can be configured speculative must wait all previous uops done |  |

1. Nonspeculative MRS IEX uses next retired rob ID sent from OOO to compare with the RID of MRS and the MRS uop can be issued if it is equal to next retired RID.

| 2 Speculative MRS IEX issues this kind of uop as normal ones ie will NOT look at next retire RID | 81019 |
| --- | --- |
| PIPEMSR | All the MSRs must be issued nonspeculatively and the issue is held until either the nonflush rid pointer or nextretired rid pointer pointed to it |

1. Nonflush MSR IEX uses nonflush rob ID sent from OOO to compare with MSRs and the MSR uop can be issued if it is not younger than nonflush RID oooiexnoflushridi2.

| 2 Nonspeculative MSR IEX uses next retired rob ID sent from OOO to compare with MSRs and the MSR uop can be issued if it is equal to next retired RID oooiexnextretiredridr1 | 10 |
| --- | --- |
| PIPEMRSLatency | General MRS uops execute 4 cycles but MRS to L2C and DTU unit will execute 7 cycles MRS to L3C unit will execute variable cycles |

- For L2C and DTU 1 The first time is from P1 to I2 and block issq one cycles at I2 stage to reserve a bubble 2 The second time is from reflow P1 to E4 at E1 stage it comes back to reflow P1 then reflow E1E4.

| For L3 1 The first time is from P1 to E1 2 The second time is from reflow P1 to E5 upon L3 ack it blocks issq one cycle to reserve a bubble as its reflow P0 stage then next cycle reflow P1 stage | 10 |
| --- | --- |
| PIPEMSRLatency | MSR uop is issued as a 1-cycle uop according to tmpcc wkup However it writes system register at E2 |

IEX write datavld are sent to system registers at E1 stage and flopped 1 cycle to E2 in each module to write system register Updated value available at system register Q side is E3.
MSR sysa X0 P1 I1 I2 E1 E2 E3.
| MRS X1 sysa P1 I1 I2 E1 E2 E3 E4 | 10 |
| --- | --- |
| PIPEReflow | Each pipe of an ALU25 IQ possibly have reflow requests from 7-cycle L2C or DTU MRS L3C MRSMSR or DIV |
| DIV has the highest priority in reflow and then MRS L2CDTU reflow and the L3C MRSMSR reflow has the lowest priority | 10 |
| PIPEResolve | Each pipe of ALU IQ has 1 resolve port to ROB |

- Resolve to OOO is normally at E2 stage But for basic AUT and ERETAAB instructions the resolve stage is E5 because fault information needs authentication result which is 4-cycle latency.
- When resolve port conflict on pipy between AUTERETAAB and other uops the former has higher priority and will block the ALU pick rdy or any reflow at P0.

| IEX will not send resolve signal of LDA STA and MSR uops LDASTA uops are resolved by LSU to OOO MSR uops are resolved by OOOs monitoring MSR bus | 10 |
| --- | --- |
| PIPESYSL2C | MRS L2CDTU reflow has higher priority than L3 reflow |

In case the L3 instructions ack back flopped to ackff meets MRS L2CDTU first flows I2 stage to punch a hole for P1 then L2CDTU wins and block a P1 bubble for it The L3 reflow will wait for one or two cycles to reserve a pipe bubble.
| L2C can be blocked by 32-bit 8-cycle or 64-bit DIV 11-cycle reflow | 10 |
| --- | --- |
| PIPESYSL3C | target core support L3C MRSMSR one outstanding For oneoutstanding configure |
| 1 IEX will NOT send more than one outstanding writeread request if no ack comes 2 L3C MSRMRS is nonpipeline uop It will block next L3C MSRMRS uops of all threads and nonL3C MSR uops in only same thread before L3C reflow enable | 10 |
| PIPESYSGIC | Some GIC registers write access MSR needs credit scheme with L2C |

1. IEX will NOT send more than 3 outstanding write requests if no l2ciexft2giccdirsgivldt0 pulse credit returns Each pulse of this signal will add 1 credit The credit will not exceed 3 The system registers need the credit mechnism are.

Iccdirel1 iccsgi0rel1 iccsgi1rel1 iccasgi1rel1 icceoir0el1 icceoir1el1.
| 2 For multiple thread each thread has a MSR credit counter to record the MSR issue | 1030 |
| --- | --- |
| PIPESYSSpecial | ISB WFI WFE WFIT WFET SEV and SEVL are handed as special MSR instructions The differences with normal MSR uops are |

- They except WFITWFET have no sources and no destination which means src and dst tags are ready when dispatched to ISSQ.
- For WFIWFEWFITWFETSEVSEVL uops IEX will hold the uop in ISSQ if their RIDs are younger than the nextretired RID from OOO They can be issued when their RIDs are equal the nextretired RID pointer They have a tmpcc src for dependence.
- For WFITWFET the MSR dstid is given as the timer dstid and broadcast to both timer L3C or L2C depending where the timer is and OOO From IEX it is just an MSR timer with nonspec attribute.

| For ISB uops IEX compares their RID with the nonflush RID pointer from OOO and issues the uops when they are equal or older than the nonflush RID pointer | 1030 |  |
| --- | --- | --- |
| PIPESIMDLS | OOO breaks down SIMD instructions to 1 or 2 LDASTA uops and multiple FSU uops IEX sends the optype 12bits to LSU for every LDASTA uop One extra thing for SIMD LD is that the sequencial number of each uop UID and indication of the last uop LASTUID of the SIMD instruction is encoded in the optype and sent to LSU This information is generated by OOO and write to ISSQ entries | 11 |
| PIPESTR | Integer store instruction is broken to STA and STD uops and dispatched to IQAGU and IQSTD respectively The STA and STD uop of a same store instruction has the same SID Both STA and STD are strictly required to be within LSU sliding window when they are issued to LSU and no reject scheme between LSU and IEX | 1112 |
| PIPELSSmallShift | For loadstoreregister offset instructions if the shift of its src1srcb is 0123 for A64 or 0123 for SVE OOO will send one uop to IQ AGUs and enable smallshift vld to inform IEX the small shift optimization For LS regoffset with extend uops small shift are not supported | 11 |
| PIPELSW1BYPASS | To resolve IEXLSUsrc timing some ALU instructions such as shf extr rev clzcls crc mac pac and div etc W1 bypass to ALL LDASTA I2 are removed This is realized by cancel the load/store uop waked up by producers P1 stage and picked at producers P2 stage The cancelled load/store will be at the fastest repicked one cycle after its cancelled stage It the load/store waked up after producers P1 stage or picked after producers P2 stage producers W2 bypass or later then the load/store can be issued without cancel |  |
| PIPEFPLD | For the floating point load instructions OOO will write the FP dst ptag and vld to IEX LDM buffer through normal dispatch IEX will issue the LD uop to LSU pipes and LSU merge I2 op wakeup and repick wakeup and send wake up signals to FSU LD matrix at E1 stage FSU will flop 1 cycle and wake up consumers at E2 stage FP loadtouse is 6-cycle which is wakeup at E2 and M1 bypass at LD E5 ie I2 bypass at LD E6 |  |
| IEX received FP LD wakeup signals LDM index from LSU too and read the FP dstptag and vld from LDM buffer and send to FSU at E3 stage The FP dstptagvld are only used for write back to FP Regfile by FSU | 11 |  |
| PIPEFPST | FP ST instructions are broken into two uops STA and FSTD The STA uop is executed in IEX while the FSTD is executed in FSU The STQ index also known as SID Store ID has be allocated at dispatch stage by OOO so IEX has the SID in its AGU ISSQs for the STA and FSU carry the corresponding SID in FSU ISSQs for the FSTD | 11 |
| PIPEF2I | An F2I uop dispatched to FSU IQ always has a dummy uop in IEX ALU IQ depending on it It wakes up the dummy uop at its W1 stage flopped from W0 The dummy uop will never be pick ready and never pick but it will be marked as picked on F2I W1 stage and use F2I vector wakes up its consumers on W1 stage F2I uop from F2I0 F2I1 F2I2 F2I3 pipes of FSU will block the issue of IEX pipx of ALU IQ012 to get the write ports of integer regfile and write at W4 stage flopped from W3 F2I0123 all need to hijack pipx of ALU0 RF bank0 ALU1 RF bank1 or ALU2 RF bank2 FSU has to avoid F2I0123 access the same bank of integer RF either by cancel or by pickblocking mechanism | 107824 |
| PIPEI2F | An I2F uop dispatched to ALU IQ may has a dummy uop in FSU IQ if the direct dependent uop is not a splitted uop of the I2F instruction IEX detects I2F depending of LD at I1 stage on pipe And I2F wakes up its dependent FP uop at I2 or E1 stage nonspeculatively There are 3x I2F pipes I2F012 each of which shares one FRF ldy write port I2F0>ldy0 I2F1>ldy1 I2F2>ldy2 IEX will detect whether there is writeback conflict between I2F012 vs ldy012 by cancelling I2F at I1 stage meeting LD pickrepick E2 stage | 107824 |
| PIPEF2CC | Similar with F2I scheme F2CC instructions such as FCMP FCCMP executed in FSU will update IEX CC register file and wake up dependent integer uops IEX dummy uop of F2CC ready at W1 flopped 1-cycle of I1E1 stage and wakes up its consumer uops nonspeculatively F2CC blocks ALU012 picks by FSU0 or FSU1 pipe at W0 to occupy the CC wirte port at FSUs W4 IEX will only need to handle the FSUs W0 stage meeting load miss E4 if so kill the wakeup and other cases are handled by FSU | 107824 |
| PIPECC2F | CC2F instructions such as FCCMP FCSEL need CC register value from IEX IEX execute the uop as an I2F mov uop in ALU012 and only use the [3128] bits to FSU for the mov data The wakeup and writeback scheme of CC2F is same with that of I2F | 107824 |
| PIPEFLUSH | IEX sees two kinds of flush events ROB flush from OOO and BRU flush from IEX branch execution unit The flush signal comes along with a RID indicating the younger instructions than that either in issue queues or on pipelines should be killed OOO flush has higher priority when it comes with the BRU flush at the same cycle and IEX ignores OOO flush RID and flush all the uops in IEX IQs or pipes To support SMT and only 1 flush at same cycle request a flush TID is provided to indicate the flush is for which thread A flushed uop should not impact states and results visible to programmers The crossBOX control signals need to clearly partition who does the flush actions refer to interface spec | 1011121 |
| PIPEEXC | Exception and ERET execpt ERETAAAB are handled by OOO and their execution is transparent to IEX The only thing IEX can feel is that OOO will write CC regfile using an injected MRS CC SPSRnzcv instruction | 10 |
| PIPEFFWD | target implementation does NOT support fast forward of CRC The main restrict is that it is difficult to identify a src ptag depending on a specific producer at exact P1 stage under matrixbased wkup scheme | 10 |
| PIPERESOLVE | IEX sends resolve signals vld and the uops rid to OOO after execution pipes finish the uop So that OOO can commit the instruction and release resources after collecting all resolve signals of the instructions uops |  |

- ALU012 resolve vld at E2 rid at E1 to OOO.
- BRU012 resolve vld at E2 rid at E2 to OOO.
- IEX does NOT send MSRs resolve to OOO and OOO resolve by itself.
- Reflow both DIV L2C MRS FarMRS and L3C sys reflow as a 5-cycle vld at reflow E2 rid at reflow E1 to OOO.

| Resolve with fault info the basic aut uop can only be resolved after aut final result out 5-cycle to know whether fault will be generated So resolve vld at E6 rid at E5 to OOO | 10 |
| --- | --- |
| PIPEBLR | A BL or BLR uop can be issued by IQ ALU012 to its pipx and sent to 1-cycle ALU pipe and BRU pipe at the same time |

For a BLR uop

- ALU pipe executes pcbase + pcoffset + 4 > X30.
- BRU pipe executes branch to <Xn>.

For a BL uop

- ALU pipe executes pcbase + pcoffset + 4 > X30.

| BRU pipe executes branch to pcbase + pcoffset + imm | 102122 |  |
| --- | --- | --- |
| PIPEMOVR | MOV register instructions as an alias of ORR shifted register can be executed ealier in OOO by manipulating ptags in SMAP without dispatching to IEX Besides the ptags handled by OOO the MOVR may change the register data value if Xform ALU instructions is followed by a Wform MOVR the MOVR will set the high 32-bit to allzero So for every source operand IEX need to be informed whether each sources producer of a uop is Xform or Wform through xfm bit for each srctag in issue queue If NOT src Xform the high 32-bit of the oprand will be masked to allzero during bypass otherwise high 32-bit is valid | 1018 |
| PIPEMOVI | MOV Immediate instructions such as MOVZ MOVN and ADRADRP instructions have only one immediate as its operand The result can be calculated earlier in OOO without dispatching to IEX and written to RegFile directly |  |

- Detect and flag these special uops at D2 stage If MOVreg instructions are detected at the same cycle MOVI optimization will be aborted and dispatch to IEX as usual Up to 3 MOVI instructions can be sent to IEX at the same cycle.
- Check IEX RegFile write port 2 movi0 shared with lsu0write port 3 movi1 shared with lsu1and write port 4 movi2 shared with lsu2 availability at D3 if IEX flags a stop enable is low the MOVI optimization is aborted If MOVI can go IEX enable is high OOO will send movi wakeup to iex rtab at S1 and prepare data result to be written to IEX RegFile at S1 stage.

| OOO writes the result to IEX RegFile at S1 through the write port 234 shared with lsu012 And send resolve to ROB at S1 | 1018 |
| --- | --- |
| PIPESPSwitch | Instead of renaming 4 SP architectural registers only 1 SP X31 is renamed so as to save 3 ptags To realize this SP EL or spsel switching OOO will insert pseudo uops into ROB instead of direct readwrite accessing to IEX RegFile |

- Four SP architectural registers according to each exception level SPEL0 SPEL1 SPEL2 SPEL3 are physically implemented in SRF of OOO.
- MSRMRS instructions can ONLY access SP registers LOWER than current EL while ALULDST instructions can ONLY access current SP SP of current EL So ALULDST and MSRMRS do not have dependencies through SP register.
- MSRMSR asscess SP registers in SRF following OOO spec seeing noflush pointer MSRMRS access rules ALULDST access SP register in RegFile through renamed ptag.
- When SP switching because EL change or spsel toggle an OOO selfsync flush is sent first Then OOO injects two uops to iex to switch from SPELx to SPELy.

MSR SPELx X31 read X31 from ptag M in RF save it to current architecutre stack pointer SPELx.
MRS X31 SPELy read from the detination architecture stack pointer SPELy and write to X31 in RF ptag M as the new SP.
| refer to lxooodecskbslcctrlv | 17 |
| --- | --- |
| PIPENZCVSwitch | OOO has no direct readwrite channels to IEX CC regfile OOO will inject uops to realize the save or restore NZCV operation |

- For NZCV save OOO will inject the following two uops after rob flush.

MOV X32 CC read CC value from CC RF ptag N in CC regfile and move to temp register X32 ptag M.
MSR SPSRELyNZCV X32 read CC value from int RF ptag M and write to SPSRELyNZCV fields.

- For NZCV restore OOO will inject the following two uops after rob flush.

MRS X32 SPSRELxNZCV read CC value from SPSRELxNZCV and write to temp register X32 ptag M.
| MOV CC X32 read CC value from int RF ptag M and write to CC RF ptag N | 17 |
| --- | --- |
| PIPERNG | OOO splits MRS RNG instruction to two uops one is L3C MRS and the other is a 1-cycle alu with dstc uop |

The L3C MRS uop OOO will sent different sysoffset to represent RNDRRNDRRS SRF The uop will nonspeculatively execute looking at nextreitre rid pointer and return the data and random number ready flag The reason of issuing L3C MRS uop upon nextretire rid is to make sure the current L3C MRS uop cannot overwrite the previous L3C MRS uop before the previous ALU uop read the RNDR data.
The alu uop is depend on the L3 MRS uop If the L3 MRS reflowed the alu uop will be woken up and isseued The CC result is basing on the random number ready flag.
The ready flag bit with be held unchanged in L3C available on L3CIEX interface until a new MRS RNDRRNDRRS access updating it.
| Each thread has its own ready flag bit private resources | 10 |
| --- | --- |
| PIPEBTI | To support V85 BTI instruction IEX needs to generate the correct branch type when flush occurs and sends it to OOO |

OOO will send the signal which represent the guarded any register other than X16 or X17 through aux packet predinfo[4]
IEX will generate the correct btype for every bru uop but the result is sent to OOO only when bru flush occurs.
| For bcond fusion the btype is always 2b00 so IEX does not need the predinfo[4] signal in this case | 10 |
| --- | --- |
| PIPEFLAGManipulation | Both 1-cycle ALU pipes IQALU012xy can support flag manipulaion instructions v84 and v85 except RMIF RMIF can only by issued from pipey of ALU012 |

- CFINV RMIF SETF8 SETF16.

| XAFlag AXFlag | 10 |
| --- | --- |

### 3.3 IEX RF

| IEX Feature ID | Feature Description | DRUT Mapping |
| --- | --- | --- |
| RFINT | Total size is 64-bit x 272 entry organized to 2 banks with each bank 136 entry 13 readports 6 writeports |  |

Source ZERO register XZR WZR is implemented as 9d510 and dst ZERO register is 9d511 When a uop has ZERO reg dst its dstvld will be set to 0 In target implementation although the srcvld is 1 if src reg is ZERO register NO regfile read is requested to reduce RF read conflicts.
| For F2I if the dst is ZERO reg FSU will pass through the dstvld and dstptag 9d511 to IEX The write to ZERO reg happens without effects Since no uops depending on an F2I uop with ZERO reg dst which is ensured by OOO rename no wkup happens for F2I uop producer with ZERO reg dst | 1819 |
| --- | --- |
| RFINTWR | 6 write ports per bank |

- WR port0 bank0 ALU0 F2I0123 to bank0.
- WR port0 bank1 ALU3F2I0123 to bank1.
- WR port1 bank0 ALU0Y.
- WR port1 bank1 ALU1Y.
- WR port1 bank2 ALU2Y.
- WR port2 bank0 ld0x ld0y movi0.
- WR port2 bank1 ld0x ld0y movi1.
- WR port2 bank2 ld0x ld0y movi2.
- WR port3 bank0 ld1x ld1y movi0.
- WR port3 bank1 ld1x ld1y movi1.
- WR port3 bank2 ld1x ld1y movi2.
- WR port4 bank0 ld2x ld2y movi0.
- WR port4 bank1 ld2x ld2y movi1.

| WR port4 bank2 ld2x ld2y movi2 | 1819 |
| --- | --- |
| RFINTRD | 13 read ports per bank |

RD port0 ALU0Y SRC0High LD0X SRC1Mid.
RD port1 ALU0Y SRC1High LD0X SRC0Mid.
RD port2 ALU0X SRC0High LD1X SRC1Mid.
RD port3 ALU0X SRC1High LD1X SRC0Mid.
RD port4 ALU1Y SRC0High STD1 SRC0Mid.
RD port5 ALU1Y SRC1High LD2X SRC0Mid.
RD port6 ALU1X SRC0High STA0 SRC1Mid.
RD port7 ALU1X SRC1High STA0 SRC0Mid.
RD port8 ALU2Y SRC0High STA1 SRC1Mid.
RD port9 ALU2Y SRC1High STA1 SRC0Mid.
RD port10 ALU2X SRC0High STD0 SRC0Mid.
RD port11 ALU2X SRC1High STD0 SRC1Mid.
| RD port12 LD2X SRC1High STD1 SRC1Mid | 1819 |  |
| --- | --- | --- |
| RFW3BYP | With the W3 bypass stage absorbed into register file the bypass network last stage W3 pipe flipflops are saved so as to save some bypass power | 1819 |
| RFCC | 4-bit 72-entry with 6 WR and 6 RD ports | 1819 |
| RFCCWR | WR port0 ALU0X F2CC0 F2CC1 |  |

WR port1 ALU0Y
WR port2 ALU1X F2CC0 F2CC1.
WR port3 ALU1Y
WR port4 ALU2X F2CC0 F2CC1.
| WR port5 ALU2Y | 1819 |
| --- | --- |
| RFCCRD | RD port0 ALU0X srcc read |

RD port1 ALU0Y srcc read.
RD port2 ALU1X srcc read.
RD port3 ALU1Y srcc read.
RD port4 ALU2X srcc read.
| RD port5 ALU2Y srcc read | 1819 |
| --- | --- |
| RFF2I | F2I uops issued from FSU ISSQs will send results through IEX bypass network and write integer regfile F2I0123 sharing WR port 0 with IEX and always has higher priority over corresponding ALU pipes It will block ALU012 from being issued to their pipex and occupy the WR0 port on that bank bank0 for ALU0 bank1 for ALU1 and bank2 for ALU2 |
| F2I sharing LDY ports are NOT supported in target implementation | 181924 |
| RFF2CC | F2CC FCMP FCCMP FJCVTZS uops update CC register file and are muxed into IEX CC bypass network Both F2CC0 and F2CC1 can share ALU012x CC write ports |
| RFBANKHiLo | Integer regfile is devided to 2 banks high 32-bit and low 32-bit on bitwidth direction This is to differenciate WW form and XX form instruction so that the read and write access of high32bit data for WW form instructions can be clockgated to save power |

This is also used for OOO MOVR WWXX optimization After OOO performed MOVR WW form optimization IEX needs to be informed that the uops src tag is using a movoptimized register the high32bit in register file may not be 0 but it should be 0 This will be handled at the bypass mux seletion control.
| Because of the highlow banked readwrite access scheme the result in register file may not be 64bitwise right instead only low 32bits are correct for a Wform instruction result and its high 32bits are random data eg unchanged last time value So the srcs xfm flag is always required to qualify the high 32-bit from regfile when it is used by a src operand And DV checker needs to take into accout this for comparason with the value in register file | 1819 |  |
| --- | --- | --- |
| RFBANK3b | Integer regfile is divided to 3 banks bank01 2 on depth direction with each bank 88entries Uops from ALU0 only write to bank0 ALU1 to bank1 and ALU2 to bank2 Uops from AGU012 and STD01 and write to any bank The 5WRport 3bank can be written in parallel at the same cycle and the 13RDport 3bank can also be read in parallel at the same cycle F2I0123 can access bank012s WR port0 If F2I0123 uops access to same bank FSU will select one of them and cancel the others | 1819 |
| RFINIT | Regfile both Int and CC initialization upon reset is NOT needed On reset OOO RENAME will map all ATAGS to ZERO ptag so initialization by writing every entry to 0 is not needed | 1819 |

### 3.4 IEX Misc

| IEX Feature ID | Feature Description | DRUT Mapping |
| --- | --- | --- |
| CHKNBR | configuration override bit from OOO oooiexinorderbr |  |
| When this signal is asserted BRU uops dispatched to ALU012 issue queue will be issued in order by waiting for the nextretire rid pointing to the BRU uop | 302122 |  |
| CHKNPAC | configuration override bit in iexctrlel1 |  |

Basic AUT will is executed as 1 uop with a configurable writeback and wakeup latency to provide flexibility on security or performance.
When IEXPACAUTNONFLUSH is set to.

1. AUT is in security mode The AUT is completely acted as a 5-cycle latency producer to writeback results and wake up its consumers after the PAC authentication is done with PASSED.
0. AUT is in performance mode The AUT is acted as a 1-cycle latency producer to writeback results and wake up its consumers when PAC authentication is ongoing.

| Note that for both modes the resolve to OOO is same at E6 stage and PACFail exception will be asserted if authentication failed | 30 |
| --- | --- |
| CHKNReserve | configuration override bit in iexctrlel1 |
| IEXCHICKENBITRESERVE for eco register | 30 |
| CHKNSAFE | configuration override bit iexctrlel1 and iexsafemodeel1 |
| The safe mode configuration including detection thresholds block window and modes etc Details to refer to target implementation implmentation defined registersxlsx iexsafemodeel1 | 30 |
| DFXDBG | Some critical signals will be sampled by DJTAG interface to debugging on silicon in the scenario of CPU core hang all the signals are stable without toggling to diagnose possible failure causes |

Each IQs oldest entrys info srcvld ready dpde rid T0T1.
Safe mode detectionentrance history or counters.
General pick blocking signals of each IQ.
| Notpicked oldest entrys blocking signals | 30 |  |
| --- | --- | --- |
| DFXREG | All the DFX debugging signals are organized into several 32-bit registers The details refer to target implementation V100DJTAG eventsxls | 30 |
| FUSGeneral | Fusion uops NO need to send two resolve valid to OOO in target implementation |  |
| Only backtoback instruction pairs can be identified by OOO as fusion uop pairs Support a fusion pair to cross cycle ie head in S1 and tail in D3 stage | 25 |  |
| FUSBCOND | Instruction Fusion 1CALUcc + Bcond |  |
| The pair of an alu instruction update NZCV and a bcond use NZCV to calculate taken or not taken This pair of instructions will execute in one cycle on two datapaths alu one cycle datapath and bru datapath Up to 4 fusions can be picked and executed per cycle The ALUcc supports 1-cycle lu instructions BICSANDS and au instructions ADDSSUBSimmshfext The bconds condition supports all pairs except AL EQNE CSCC MIPL VSVC HILS GELT GTLE | 25 |  |
| FUSMOVK | Instruction Fusion MOVZMOVN + MOVK |  |
| The pair is a MOV instruction moves an optionallyshifted 16-bit immediate into a register and a MOVK keeps the other bits unchanged besides the moving function The pair of instructions will execute in one cycle and fused into one uop No fusion if MOVs and MOVKs Rd is not the same The hw bits of the fusion pattern must meet specific constraints The tails dst ptag is merged in the heads ptag field by OOO so IEX can use directly | 25 |  |
| FUSCLZ | Instruction Fusion MVN + CLZ |  |
| The pair of an intruciton MVN and a CLZ instruction will execute in one cycle and fused into one uop The first source of the ORN instruction must be zero register and the sf bit of the two uops must be the same The tails dst ptag is merged in the heads ptag field by OOO so IEX can use directly | 25 |  |
| MXPMPUR | The purpose of MXPM maxpower mitigation is to limit average power over medium timescale to maximize efficiency over wide variety of workloads and MXPM is designed for electrical safety on 1us timescale aim to do not go overcurrent to avoid damage or brownout In IEX there are two features to support the topdown power requirement MPMM and CPU load event | 7 |
| MXPMMPMM | When LSU send mxpmlimtvld and mxpmlimitlevel[10] to IEX there is a timer to produce a block signal to every issue queues The block frequency is according to the limit level There are four limit levels |  |

2b00 reduce 375 power targeting to 625 of IEX max power 6 out of 16 cycles blocked.
2b01 reduce 25 power targetign to 75 of IEX max power 4 out of 16 cycles blocked.
2b10 reduce 188 power targeting to 812 of IEX max power 3 out of 16 cycles blocked.
2b11 reduce 125 power targeting to 875 of IEX max power 2 out of 16 cycles blocked.
| In order to save logic and resource the block cycles of different level for power reduction is estimated not exactly true in reality | 7 |
| --- | --- |
| MXPMEVENT | IEX can monitor the working status and send event signals to LSU The four event signals are |

1. mxpmhintevt0 1 iex issue uop number no less than 1.
2. mxpmhintevt1 1 iex issue uop number no less than 3.
3. mxpmhintevt2 1 iex issue uop number no less than 5.
4. mxpmhintevt3 1 iex issue uop number no less than 7.

| CPU load control module can decide whether to reduce power such as dropping clock frequency | 7 |  |
| --- | --- | --- |
| PMUMON | PMU is to monitor the performance of every module and find the performance critical path In IEX all PMU events can be seperated to Architecture level and Microarchitecture level | 31 |
| PMUARCH | Architecture level PMU events are required by Arm Spec In IEX they are designed per thread |  |

Event Num 0x0010 BRMISPRED Mispredicted or not predicted branch speculatively executed.
Event Num 0x0012 BRPRED Predictable branch speculatively executed.
| Event Num 0x8073 SVEPLOOPTERMSPEC SVE predicate loop termination speculatively executed | 31 |
| --- | --- |
| PMUUARCH | MicroArchitecture level PMU events are used to help test IEX performance They have topdown evnents and IEX individual enents Detailed definition refer to IEX PMU definition sheet [9]target implementation PMU Eventsxlsx PMUevnetsv8x |

31
| SPUGeneral | This SPU unit is responsible for Arm V83 Statistical Profiling feature implementation Arm Statistical Profiling extension is a mechanism for profiling software and hardware using randomized sampling In IEX all SPU events can be seperated to Architecture level and Microarchitecture level | 32 |
| --- | --- | --- |
| SPUARCH | Architecture level SPU events are required by Arm Spec In IEX they are designed per thread |  |

- Branch Target Address.
- Instruction Latency Information.
- Instruction type.

| Branch misprediction | 32 |
| --- | --- |
| SPUUARCH | MicroArchitecture level SPU info are used to |

Iexdtuft0spudsptoissueendt01 Indicate the tagged uop has been issued for execution successfully.
Iexdtuft0spuresolveendt01 Indicate the tagged uop has completed execution and no longer capable of stalling any instruction that consumes its output.
Iexdtuft0spuinsttypet01[30] The type of tagged uop branch or conditional or other.
Iexdtuft0spuevttypet01[10] The event type mispredicted or not taken.
| iexdtuft0spubrutargetaddrt01[550] branch target address | 32 |
| --- | --- |
| IEXPC | IEX only has 3 ALUBRU PCPredictPC read ports of PC buffer in target implementation after removing LDST PC read ports |
| There are 3 BRU pipes in target implementation IEX and each BRU PC read port has one index addr with two vld pcvld and predvld and two data out PC and predicted PC hence it is more than 1 port but less than fully 2 ports ADR and ADRP instructions are shared with the BRU PC read ports on same issue pipe pipx of ALU012 | 1021 |
| SVEDP | SVE Data Processing Instruction by IEX |

1. ADDVLADDPLRDVLINCBHWDDECBHWDscalarINCPDECPscalarCTERMEQCTERMNECNTBHWD implemented in 1 cycle.
2. USQINCBHWDWHILExUSQINCPUSQDECP3264bit scalar implemented in 2 cycle WHILEx instruction output pg[x] and pstateCC.

Target implementation support SVE2 WHILEx instructions.
| SVEGL | SVESVE2 Gather Load instructions |
| --- | --- |
| OOO will break down to each LDA uops with dummy uop for F2I to get the vector address 1 or 2 LD uops depend on the F2I data as the LD address The first breakdown LD uop LD0 should not be deallocated until LSU send glast vld at E7 while other LD uops can be deallocated at E4 if LD hits LD0 will be the last LD uop to be deallocated FSU only uses LD0 to wake up consumers of Gather Load instructions | 26 |
| SVESS | SVESVE2 Scatter Store instructions |
| OOO will break down to each STA uops with dummy uop for F2I to get the vector address 1 or 2 STA uops depend on the F2I data as the ST address | 26 |
| PAC | Pointer Authentication v83 and v86 is implemented as 5-cycle latency execution unit |

- ARMv83PAuth instructions with ARMv86 extention supported EnhancedPAC2 Inserting PAC using XORing instead of replacing scheme and FPAC generating PACFail synchronous exception when AUT failing are supported.
- target implementation has the IDAA64ISAR1EL1[74] APA 0100 IDAA64ISAR1EL1[118] API 0000 previous-generation core has the IDAA64ISAR1EL1[2724] GPA 0001 IDAA64ISAR1EL1[3128] GPI 0000.
- All the basic AUT instructions will generate PACFail exception if PAC authentication is failed.
- All the combined AUT instructions will NOT generate PACFail exception if PAC authentication is failed but only modify the pointer so as to generate translation fault when it is being used by branch or load instructions.
- The basic AUT instructions can be speculatively executed in so called performance mode that is the PACstripped pointer value is provided 1-cycle latency in parallel with the PAC checking and failure report In security mode by default basic AUT preform writeback wkup and resolve as a 5-cycle latency uop only on authentication success.

| ERETAA and ERETAB instructions are specially handled together with OOO ERETAAB need to wait for nextretiredrid to issue from IEX | 23 |
| --- | --- |
| SMTSYS | In SMT2 design each system register is duplicated so that each thread has its private system registers |

- For each MRS or MSR uop an additional tid signal is sent on system register access bus to indicate which threads register is selected to access.
- For each system register bits sent to IEX are duplicated for each thread with t0 and t1 suffix.

| For the system register implemented inside IEX such as PAC Key registers each thread has its private registers | 10 |
| --- | --- |
| SMTFlush | In SMT2 scenario the flush signals include the flush RID and flush TID Besides the comparison of RID flushed if a uops RID is younger or equal than the flushrid it is also required that the uops TID is match the flush TID |
| In BRU unit it is required to handle the case when branch flush of each thread come at the same cycle but only one threads branch flush can be sent out This require IEX to buffer one threads flush info and send next cycle with roundrobin scheme The associated branch resolve signal also need to be buffered so as to match with the branch flush | 914 |
| SMTQoS | Issue Queue support SMT QoS threshold scheme |

In MT mode multithread mode there must reserve special number of entries for each thread in each type of issue queues ALUAGUSTD IQs.
The reserved number can be configured in selfdefine system register for each thread and each type of issue queue.
| If current thread occupies too more entries in a specific type ALUAGUSTD of issue queues it will generate corresponding type ALUAGUSTD of stall for this thread | 29 |  |
| --- | --- | --- |
| PMUPEVNT | Power Events are implemented in RTL design to estimate CPU core dynamic power consumption on silicon with power model The power characterized events of IEX mainly includes Regfile RDWR 3264-bit events and uops of different types ALU LDST MUL DIV BRANCH etc dispathedexcutedcancelled events These events represent activities of the main power consumers in IEX such as IQ RF and Datapaths Before the power events are sent out from IEX integrators or lowpass filter are connected to multibit events to limit event ouput signal width in IEX 29 bits out of 128 bits totally in CPU core All BOX power events are concatenated to L2C and then sent to ITS a module outside core processing power calculation | 31 |

### 3.5 Implementation-Specific Features

A few features are required only for some deployment variants but not for other deployment variants For target implementation some features are only supported in Variant B or Variant A design variation which is also called Cdirection or Tdirection Here we list all the IEXrelated or IEXaware features which has Variant B and Variant A discrepancy.

#### 3.5.1 Variant A Only Features

| IEX Feature ID | Feature Description | DRUT Mapping |
| --- | --- | --- |
| SYSL3CMOST | MRSMSR L3C system register with multioutstanding |  |

There is a FIFO to save outstanding MRSMSR request req info from IEX to L3C and acknowledge ack info from L3C to IEX before the uop reflows on pipe For SingleThread ST mode the outstanding 8 for MultiThread MT mode each thread has outstanding 4.
This feature is enabled when macro LXCFGIEXL3SYSOTS is NOT defined.

#### 3.5.2 Variant B Only Features

| IEX Feature ID | Feature Description |
| --- | --- |
| SYSL3COOST | MRSMSR L3C system register with oneoutstanding |

Only ONE MRSMSR uops request req or acknowledge ack is buffered before the uop reflows on pipe for both SingleThread ST and MultiThread MT mode.
This feature is enabled by macro LXCFGIEXL3SYSOTS defined.
| RASPARITY | RAS parity check |
| --- | --- |

Payload of ALU Issue Queues IQALU012 has a parity bit for each entry which is set upon writing the entry 1 for odd number of 1s and 0 for even number of 1s When reading out the payload bits at I1 stage the parity is recalculated and compared against the parity bit If the parity results mismatch with the parity bits an error is signaled to LSM module for SEI exception and error information including IDs of IEX IQs and IQ entryies which are reported error Note that IQAGU and IQSTD are NOT paritycheck enabled with macro LXIEXLSURASEN NOT defined.
This feature is enabled by macro LXCFGREGPAREN defined from TOP level and LXIEXRASEN and LXIEXLSURASEN from IEX level To eanble IQ ALU LXIEXRASEN needs to define To enable AGUSTD both LXIEXRASEN and LXIEXLSURASEN need to define.
| MRSRNG | MRS RNDRRNDRRS True Random Number supported |
| --- | --- |

This is an ARMv85 feature FEATRNG Reads to RNDR and RNDRRS registers return a 64-bit random number IEX handle this instrution with 3 uops a pair of special MRS L3C system register uops MRSm + MRSd plus an ALU uop depending on MRSm The MRSm uop gets the 64-bit random number and specially its ready status from L3C where a truerandomnumber IP is integrated and the ALU uop puts the ready status to condition code CC for consumer instructions looking for it.
This is enabled by macro LXCFGSUPPORTRNGEN.
| SYSWFXT | Support WFET and WFIT instructions |
| --- | --- |

This is an ARMv87 feature FEATWFxT and FEATWFxT2 IEX handle these two instructions as an MSR uop to L2C where timer control logic is integrated and this MSR broadcasts a timeout value in Xd to L2C and other modules For FEATWFxT2 IEX will especially send the generalpurpose register number so called ATAG to OOO at the same time broadcasts timeout value to L2C.
This is enabled by macro LXCFGOOOWFXTEN.

## 4 IEX Structure Description

### 4.1 Overall Block Diagram

The IEX unit is responsible for integer instruction execution It is mainly composed of the following function parts Issue Queues Register files Bypass network Execution pipes including ALU Branch system instructions etc and other parts like PMU Performance Monitor Units DFX clock and reset.

3. 1 IEX block diagram.

### 4.2 Overall Structure Diagram

The IEX unit is responsible for integer instruction execution which is composed of three main function parts.
Issue Queue
The issue queue tracks the status of operands for each instruction Once all operands for that instruction are ready the issue queue picks up the instruction and sends it to the corresponding execution unit provide that no resource conflict is presented.
Register files
The register files contain both the architectural and active working register values.
Execution pipes
ALU1 ALU2 ALU3 and ALU4 pipes handle data processing instructions including Branch instructions that go through either ALU2 or ALU3.
BRU0 BRU1 pipes handle conditional branch instructions.
ALU0 ALU3 handle store datafload point to integer uops.
ALU1 ALU4 handle ADRADRP float point to integerinteger to fload point uops with srcc.
ALU2 ALU5 handle 2 cycle alumultiplydividepacsystem instructions.
AGU0 AGU1 pipes handle memory instructions load/store Adress calculation part.
AGU2 pipe handle only load address calculation.
NoticeLoadStore L1 Cache is in LSU side.
Bypass network
Bypass network is used to forwarding data from execution unit result to execution source data.
Execution Units
There are six 1-cycle ALU execution units ALU0 ALU1 ALU2 ALU3 ALU4 and ALU5.
There are two 2-cycle ALU execution units ALU2 and ALU5 including CRCWhile etc.
There are two MAC execution units ALU2 and ALU5.
There are one PAC execution units Shared by ALU2ALU5 pipe.
There are one DIV execution units Shared by ALU2ALU5 pipe.
There are two BRU execution units BRU0 and BRU1.

### 4.3 Overall PipeLine

#### 4.3.1 Dispatch Pipeline

| S1 | S2 | S3 |
| --- | --- | --- |

In order to aline OOO pipeline stage here we called Dipatch Pipeline in IEX.
S1
Uops are dispatched from OOO and written into Spec Issue Buffer which just flopp the uops info in each issq write port Also called ISSQ SLOT.
S2
ISSQ free entries are allocate to each write port and then uops information are written into it.
S3
The next stage of S2 entry will be valid and wait to be picked.

#### 4.3.2 Entry Pick and Execution PipeLine

| P0 | P1 | I1 | I2 | E1 | E2 | E3 | E4 |
| --- | --- | --- | --- | --- | --- | --- | --- |

If uops are picked and executed here we called Execution Pipeline in IEX.
P0
P1
I1
I2
E1
E2
E3
E4

#### 4.3.3 RF Write PipeLine

| WM2 | WM1 | W0 | W1 | W2 | W3 | W4 |
| --- | --- | --- | --- | --- | --- | --- |

The major operations in each stage are summarized below.

4. 2 IEX pipeline stages.

S1
Instructions from the dispatcher are written into the issue queues The status of operands for each instruction is updated based on the issue picture Some uncritical information is written at S2 stage.
P1
Instructions with all operands ready are picked and sent to the corresponding execution pipes If more than one instruction is ready for a pipe the oldest one is picked Meanwhile the register numbers of source operands are read out of the issue queues.
I1
The integer register file reads out data for each source operand.
I2
A bypass network picks up the latest data from the pipeline.
E1
Simple data processing instructions finish in the first cycle of execution The results are available for the next instructions I2 bypass.
E2
Instructions that finished in E1 write the integer register files The branch pipe asserts the redirect signal if mispredicted Two cycle alu is available for bypass.
E3
MDU continues execution Some mac is available for bypass Two cycle alu writes the integer register files.
E4
MDU continues execution Some mac writes the integer register files Load data is available for bypass.
E5
MDULoad writes the integer register files.
