# OOO Decode

This document rewrites the decode flow, uop break rules, grouping, branch-ID handling, PC management, fusion, early exception detection, and special decode behavior.

> This Markdown document is the maintained specification. Sensitive metadata and internal reference labels have been removed while preserving architecture semantics.

## 6 Function Description

### 6.1 Decode

- IFU will provide up to 8 instructions at the OOOs input In IFU predecode stage each instruction will have number of its ops decoded in addition to other predecoded attributes.
- For all instructions that will have more than 5 ops in total will be decoded at complex decoder only meaning that if its not at starting slot for decode itll break decode window and will be decoded at complex decoder on the next cycle.
- Decoders for integer and VFP will be shared ie each decoder will be able to decode both integer and VFP ops After decode stage all ops will be separated into INT and VFP ops INT ops will have 8 dispatch slots so the integer op separation can be done by simply clearing valid indication for ops that are VFP.
- In OOO all instructions will go through replicate and shift down mux that will duplicate multiop instructions according to the number of its ops while preserving original instruction order by shifting down younger instruction.

#### 6.1.1 Block Diagram

#### 6.1.2 Predecode in IFU

- At the last IFU pipestage each instruction will pass through simple predecode logic where it will be assigned total number of ops op number will be decoded as 3-bit ranging between 1 and 5and 7 and the number 7 is used to indicate that instruction can be decoded at complex decoder onlyThe number of destinations will no longer be required by OOO.
- Maximum number of ops per instruction decoded in regular noncomplex decoder.

4. VFP store addressloads.
1. INT store data can be STP with 2 SRCs.
2. VFP store data can be STP or STMN with 2 SRCs.
2. INT loads
2. ALU ops MADD

#### 6.1.3 InstQ read restrictions in IFU

No more than 8 instructions in total.
No more than 8 ops in total.
No more than 8 INT ops.
No more than 6 VFP ops.
No more than 6 load+store operation in total VFP + INT.
No more than 4 store operation in total VFP + INT.
No more than 4 CC writers.
No more than 2 predtkn branch.
No more than one complex instruction.

#### 6.1.4 D1 stage

##### 6.1.4.1 Uop break and mapping

To improve frontend bandwidth efficiency try to break uop in latter stage In current microarchitecture not only decode can break uop but also dispatch can break uop.
At D1 stage decode will break uop according to uop number of each instruction which is from uop table The uop break is the process of mapping down each uop will occupy one lane.
The basic format of uop is.
INT src1 src2 srcc dst1.
FP src1 src2 src3 srcc pg dst1vfp dst1int.
One instruction can break at least one uop and at most 8 uops one cycle If uop number of an instruction is greater than 5 it will be treated as complex instruction and will be sent to decode singlely and decode will decode this complex instruction in 1 or 2 cycle.
The instruction are input from bank of IFU so a onehot signalinststart is used to sign which instruction of bank slot is the first instruction.
All pattern of uop mapping are listed in following figure Decode does this mapping at d1 stage and get uops mapping information for subsequent decoding like.

#### Notes

1complex instmore than 5 uopneed 1 cycle or 2 cycle.
2noncomplex instno more than 5 uop.

##### 6.1.4.2 Dst number

In current microarchitecture each uop format can have 1vfp dst at most and 1 int dst at most but rename only can rename 6 vfp dst ifu will send vfp instructions whose total number less or equal 6 Decode need do vfp dst mapping before send to rename so after decode the uop lane and vfp dst is decoupled that is the dst in one lane may not belong to the uop in the same lane.
The vfp dst lane mapping is done at D2 stage.

##### 6.1.4.3 Decode for ROB Grouping

Instead of one instruction using 1 rob entry a group of instructions can share one rob entry with one rob id For a group to commit all instructions within the group have to be completed successfully Therefore any instruction type that presents as a possible noncomplete point needs to be broken into next group or be a group by its self.

###### 61431 Group Starts

Instructions will start a group as following condition occurs.
| 1 | D1 stage will detect whether to start a group by type of each instruction it will pipe to D2 to calculate rid Please check uop table for the detail |
| --- | --- |
| 2 | D1 stage will get exception information for each instruction it will pipe to D2 to calculate rid |
| 3 | When group is full of instruction may be 12 max by system setting next instruction will start a group |
| 4 | Fuse head instruction will start a group |
| 5 | Bid increases instruction will start a group |
| 6 | New instruction follows empty cycle or another thread cycle at D2 will start a group |

For the detail rules of start a group please see the chapter of ROB.

###### 61432 Group Ends

Instructions will end a group as following condition occurs.
| 1 | D1 stage will detect whether to end a group by type of each instruction it will pipe to D2 to calculate rid Please check uop table for the detail |
| --- | --- |
| 2 | The instruction with predict hint will end a group |
| 3 | The instruction having overflow pc offset will end a group |

For the detail rules of end a group please see the chapter of ROB.

###### 61433 Special instruction grouping

For some special rule of special instructions for group start and end please see the chapter of ROB.

###### 61434 Preparation for BID calculation

IFU generates fetch blocks and assign each fetch block a BID BranchQ ID IFU uses BID to keep track of BranchQ in the case of flush or instruction retire The most common cases for BID changes is IFU encounters taken Branches or cacheline breaks The life of the BIDs within the OOO can be summarized below.
IFU send BID inc with instructions to OOO.
When Flush BIDs will be sent to IFU for the instruction that trigger the Flush.
If no flush the youngest BID among the retired ROB groups will be sent back to IFU from OOO.
IFU uses BID to find out which BranchQ entry to retrieve corresponding information for flush recovery GHR etc or retirement branch training and entry deallocation The current POR is for IFU to statically partition BranchQ for SMT Therefore each threads will use separate BID space.
The handshaking interface between IFU and OOO for the flush and commit BID is shown below.
| Signal name | Width | Description |
| --- | --- | --- |
| ifuoooinstvld07d1 | 1 | Indicate instruction in Slot 07 is valid |
| ifuooobidinc07d1 | 7 | The BID corresponding to the instruction in Slot 07 This is used to calculate bid in d3 |
| ifuooobidvldd1 | 1 | Assert at mt part changerob flush r5bru flush e4 ifu side |
| ifuooobidd1 | 7 | 1 cannot be larger than or equal to BRQ depth the bid from ifu to OOO must be in following scope |

- when DEPTH 80 ST 063 MT 039.
- when DEPTH 64 ST 047 MT 031.

2. when mtpart change in dbgstate ifuooo send size1 bid base after exit dbg state ifuooo send size1 bid base.
3. rob flushbru flush ifuooobidt0t1 equal ooo flushbidiex bruflush bid.

| oooifuflushbidr2 | 7 | BranchQ ID corresponding the flush instruction |
| --- | --- | --- |
| oooifurobcommitbidr2 | 7 | BranchQ ID corresponding the youngest nonflush commit group |

In order to send BID back to IFU an instruction can carry its BID through the pipeline If the instruction causes flush its BID will be sent back to IFU as flush occurs If not the youngest BID among the retired instructions will be sent back during commit However carrying BIDs around would have cost a lot of flops The scheme described below will greatly reduce the resource required for BID storage.
Without communicating with IFU OOO will keep a local copy of the BID Base and coverts all the BIDs received from IFU into encoded offset The offsets will be stored in the ROB with the instructions If no flush occurs oooifurobcommitbidr2[60] will be calculated with the BID base and the BID offset found in the youngest ROB groups The BID Base will be updated accordingly In the case of the flush the BID Base will be updated by the BID associated with the instruction that causes flush The same BID will be sent back to IFU as well.
As for BR mispredict BRU will send iexifubrubidi2[60] The information is from OOO The BID is pipelined in the OOO and send to the IssueQ via dispatch packet The BID is overloading the CC destination tag field since the BR will not use a destination.
The BID offset can be encoded one bit bidinc because the instruction with bid increment will start a new group and the details is as described below.
Bidinc whether bid changes for this 1st instruction of this ROB group compared to last ROB group.

##### 6.1.4.4 Decode for PC management

Decode handle PC offset generation for every instruction.
In D1 stage the following can be identified.
New PC base needed for predicted taken branch.
Sequential PC not a predicted taken branch.
Uop All uops from the same instruction will use the same PC.
The logic is based on the information obtained from opcode and IFU interface The information will be stored in the D2 stage flop for each instructions The D2 stage will use the information for PC Buffer entry request and PC offset calculation.

##### 6.1.4.5 Instruction Fusion

To improve performance some two different instructions will be fused to one to execute The benefit for this is occupy less issq entry and can reduce total execution latency There are different fusion instruction type and only support two back-to-back instruction to fuse cross cycle back-to-back fusion also supported.

###### 61451 Instruction Fusion scheme

@D1 decode inst type for possible fusion head and tailCheck fuse enable pair rdrnrm match or not.
@D2 check inst vld for each head and tail and whether in one rid or not u0 need to check with last u5 Head fuse valid is set speculative but tail fuse valid set must meet all fusion condition.
@D3 headtail dispatch normally tailhead will kill self Meanwhile headtail will check tail fuse vld to know fuse good or not and record this info to s1 to change fuse related info.
@S1 Head will use fuse good info got from D3 to change packet info For iex will set fuse vld and fuse type For fp will change the instruction code.
At D1 stage decoder will detect the possible head and tail according to instruction type supported for fusion D1 also do the fusion pattern check including fuse enable of each type whether has a pair of head and tail RdRnRm satisfy fusion condition or not To support cross cycle fusion also need check the u0 with last u5 Each type of fusion is detected in parallel Each instruction has a fuse valid and a fuse type of headtail head fuse valid is set speculatively provided that it satisfy fuse head instruction type tail fuse valid set must satisfy all fusion condition including head fuse valid In following pipeline head will decide fusion good or not by check tail fuse valid the reason of this kind of design is due to cross cycle case the head is one cycle ahead of tail and difficult to know tail status.
At D2 stage decoder will check fusion condition further including whether has exception whether in one rob group for some fusion pattern Overall D2 need finish all fusion condition check because for cross cycle case when head has been at S1 tailhead is at D3 which cannot rename in time so D2 need stop tailhead rename by killing dst with head rename instead once decide do fusion That is to say the fusion cannot be cancel once D2 generated fuse vld for pair and stopped tailhead rename To support cross cycle fusion D2 also need check the i0 with last u5.
At D3 stage the headtail will dispatch normally and record its fuse valid and fuse headtail type to S1 the tail with fuse valid set after all condition check will kill self not to dispatch.
At S1 stage each head check corresponding tail fuse valid to see whether fuse good include cross cycle case which u5 need to check u0 status in D3 If fuse good then the head need add the fuse information into the packets for issq But if its movprfx fusion tail would check fuse good and dispatch in issq For iex need tell the fuse good fuse type and support corresponding tail packets the tail of u5 will get from D3 For fsu need change the instruction code.
All fusion type dispatchkill headtail and support cross-cycle fuse or not are listing below.
| Fusion type | Dispatch | Kill | Support cross-cycle |
| --- | --- | --- | --- |
| ALUcc + BCond | Head | Tail | Support when cb is on |
| AES fusion | Head | Tail | Support when cb is on |
| Movprfx fusion | Tail | Head | Not support |
| ORR + EOR | Head | Tail | Not support |

###### 61452 Instruction Fusion cases

The earlier-generation core processor will support the following instruction fusion cases.
| 1 | ALUcc + BCond |
| --- | --- |

To detect the fusion decode need to distinguish the alu instructions which update NZCV and only execute in one cycle To remove the fusion buffer some alu instructions will be detected not to do fusion for some reason.

#### Fusion head

| AU | execution data path | comment |
| --- | --- | --- |
| ADDS immediate | alu one cycle |  |
| CMN immediate | alu one cycle | an alias of ADDS immediate |
| ADDS extended register | alu one cycle | when imm3 3b0 sf 1b1 option 3bx11 imm3 3b0 sf 1b0 option 3bx1x |
| CMN extended register | alu one cycle | an alias of ADDS when imm3 3b0 sf 1b1 option 3bx11 imm3 3b0 sf 1b0 option 3bx1x |
| ADDS shifted register | alu one cycle | when imm6 is 000000 |
| CMN shifted register | alu one cycle | an alias of ADDS when imm6 is 000000 |
| SUBS immediate | alu one cycle |  |
| CMP immediate | alu one cycle | an alias of SUBS |
| SUBS extended register | alu one cycle | when imm3 3b0 sf 1b1 option 3bx11 imm3 3b0 sf 1b0 option 3bx1x |
| CMP extended register | alu one cycle | an alias of SUBS when imm3 3b0 sf 1b1 option 3bx11 imm3 3b0 sf 1b0 option 3bx1x |
| SUBS shifted register | alu one cycle | when imm6 is 000000 |
| CMP shifted register | alu one cycle | an alias of SUBS when imm6 is 000000 |
| NEGS | alu one cycle | an alias of SUBS when imm6 is 000000 |

LU
| ANDS immediate | alu one cycle |  |
| --- | --- | --- |
| ANDS shifted register | alu one cycle | when imm6 is 000000 |
| BICS shifted register | alu one cycle | when imm6 is 000000 |

#### Fusion tail

B <cond> <label> condition is EQNE CSCC MIPL VSVC HILS GELT GTLEunsupport ALand support others.
If the aluupdate nzcv is dispatched but the bcond is stalled cancel branch fusion dispatch two separate uops.

#### Fused

| 2 | AES fusion |
| --- | --- |

In order to improve the performance of floatingpoint benchmark some instructions in pair can be merged as 1 uop in OOO and executed it in FSU This can save the pipeline band with both in OOO and FSU and reduce the latency when executing in FSU.
AESEa64 Fusion
Head AESE Vd16B Vn16B.
Tail AESMC Vd16B Vd16B.
Fused AESEMC Vd16B Vn16B.
AESDa64 Fusion head.
Head AESD Vd16B Vn16B.
Tail AESIMC Vd16B Vd16B.
Fused AESDIMC Vd16B Vn16B.
AESEsve2 Fusion
Head AESE Vd16B Vn16B.
Tail AESMC Vd16B Vd16B.
Fused AESEMC Vd16B Vn16B.
AESDsve2 Fusion head.
Head AESD Vd16B Vn16B.
Tail AESIMC Vd16B Vd16B.
Fused AESDIMC Vd16B Vn16B.
The fusion pattern constraints.
| 1 | The sf must be the same |
| --- | --- |
| 2 | The Rd must be the same |
| 3 | For tail instruction RdRn |

After fusion the two combination type need two new instructions encode This will use the unallocated encode in Cryptographic AES group.
AESEMC > opcode[40]00000.
AESDIMC > opcode[40]00001.
| 3 | Movprfx fusion |
| --- | --- |

The MOVPRFX predicated instruction is a predicated vector move that can be combined with a predicated destructive instruction that immediately follows it in program order It can create a single constructive operation or convert an instruction with merging predication to use zeroing predication.
The MOVPRFX unpredicated instruction is an unpredicated vector move that can be combined with a predicated and unpredicated destructive instruction that immediately follows it in program order It can create a single constructive operation.

#### Fusion head

Predicated instruction.
MOVPRFX predicated.
Unpredicated instruction.
MOVPRFX unpredicated.

#### Fusion tail

Predicated instruction.
Reference to uop tableSVEUPUPP.
Unpredicated instruction.
Reference to uop tableSVEUU.
The fusion pattern constraints.
| 1 | prefixed inst is destructive format |
| --- | --- |
| 2 | prefixed insts dst MOVPRFX dst |
| 3 | prefixed insts other src MOVPRFX dst |
| 4 | prefixed insts Pgesize MOVPRFX Pgesize if it is predicated inst |
| 5 | MOVPRFX unpredicate can fuse with unpredciatepredicate SVE |
| 6 | MVOPRFX predicate can fuse with predicate SVE exclude MVOPRFXM+ prefixedM |

Movprfx fuse vld would replace the src atag for tail instruction with src atag of movprfx instruction Then fsu taking tail instruction to process is enough.
| 4 | ORR + EOR fusion |
| --- | --- |

ORRvector register and EORvector can be merged as 1 uop in OOO and executed it in FSU This can save the pipeline band with both in OOO and FSU and reduce the latency when executing in FSU.
ORR + EOR Fusion
Head ORR V1 V1 V2
Tail EOR V1 V1 V3
Fused ORR V1 V1 V2 V3.
The fusion pattern constraints.
| 1 | ORRdst EORsrc1 |
| --- | --- |
| 2 | ORRdst EORdst |
| 3 | ORRsrc1 ORRsrc2 EORsrc1 EORsrc2 |
| 4 | ORRQ EORQ 1 |

The src3 of Fusioned ORR is equal to EORs src2 the dst vld and fp vld would be killed by decoder.

###### 61453 Instrucion Special cases

| 1 | Branch fusion |
| --- | --- |

For cross cycle case if the uop update cc has been at S1 the bcond is stalled at D3 then need cancel the fusion Because the branch has not been written into rob if fusion is executed then will resolve to a unallocated rob entry bcond can be in different group with update cc uop or false commit bcond in same group with update cc uop Even the fusion generate a flush with unallocated rid.
| 2 | Movprfx fusion |
| --- | --- |

Movprfx fuse is to stop head rename and kill head uop and dispatch tail uop in issq This is different from the above behavior Because the main work is done by tail uop.

##### 6.1.4.6 Early Exception Identification

In the D1 stage the early exception will be identified and reported to the exception block The exceptions that can be detected in the D1 stage is listed below with descending priority.
Reset catch match
Exception catch match.
Software Step exceptions.
Exception from IFU.
LinxCore Defined Trap.
Illegal Return Events.
BTI exception
Trap IMPLEMENTATION DEFINED TIDCP functionality.
Undefined instructions.
Exception Generation Instructions and Instruction implicitly caused exception.
Multiple exceptions can be detected at the D1 stage but only the exception that has the highest priority will be reported The exception type will be captured by the OOO Exception block and a bit in the ROB will indicate the exception will occur The instruction that has the early exception will occupy a ROB entry by itself The Exception block can easily identify the instruction that has exception at the commit stage and starts the exception handling.

###### 61461 SoftwareHalt Step exceptions

According to the Arm v8 Spec we just take the software step exception as an example and the halt Step exception is similar with it.
The Debugger implemented in DTU enables software step by setting MDSCREL1SS to 1 Then executes ERET to go the instructions to be singlestepped PSTATESS will be 1b1.
The pipeline executes the instruction to be singlestepped and takes a Software Step exception on the next instruction and returning control to the debugger.
The Software Step exceptions can be taken only if debug exceptions are enabled.
Essentially DTU provided the debug state enables the OOO pipeline will enter the Software Stepping Activenotpending state after the ERET is executed If no other exception to take the pipeline out of the debug state the execution of the next instruction will enter the Activepending State and take the Software Step exception when the instruction commits.
If the OOO pipeline is in Activenotpending state the D1 stage will capture the next instruction comes in from IFU and report the exception This exception will only apply to Instruction 0 After the exception the Software Stepping will return to the inactive state until the next ERET to activate the stepping or debug software can abort by reset the MDSCREL1SS.

###### 61462 Exceptions from IFU

IFU provided the following interface to OOO to identify which instructions has exceptions.
| Signal name | Width | Description |
| --- | --- | --- |
| ifuoootidd1 | 1 | Indicate which thread the instruction belongs to |
| ifuooobkpt07d1 | 1 | This signal indicates there is a breakpoint match at the instruction slot 01234567 |
| ifuooopabort07d1 | 1 | Abort signal for the instruction in Slot 01234567 which considering Fill Buffer abort ITLB abort and TLB page walk abort cases |

The attributes associated with these exceptions will be captured and send to the exception handling logic The IFU exception has the highest priority than the exceptions detected in the downstream pipeline.
All exception from ifu include.
| 1 | Pc misalign |
| --- | --- |
| 2 | Iabort |
| 3 | EL3 PA fault |
| 4 | Breakpoint |

###### 61463 LinxCore Defined Trap

LinxCore family processors has defined certain unsupported instructions or exception that is unique to the pipeline The D1 stage will identify the cases and report the exception This has higher priority than other instruction specific exceptions.

###### 61464 Illegal return events

The ARMv8 architecture defines the Return as.
Execution of an ERET instruction.
Execution of a DRPS instruction in Debug state.
Exit from Debug state.
There are cases described in the Arm Architecture Spec that can cause illegal return It also define a generic mechanism for handling returns to a mode or state that is illegal At the decode stage the handling is to detect PSTATEIL 1b1 After that any instruction pass through D1 stage results in an Illegal Execution state exception.

###### 61465 BTI exception

Please looking at 6162 BTI for details.

###### 61466 HCRTrap IMPLEMENTATION DEFINED functionality

The exception is to implement the HCREL2TIDCP function The Hypervisor Configuration Register HCREL2 provides configuration controls for virtualization including defining whether various Nonsecure operations are trapped to EL2 When the bit is set EL0 or EL1 accesses to or execution of the specified encodings reserved for IMPLEMENTATION DEFINED functionality are trapped to EL2.
The D1 logic will identify the system instructions encoding.

### 3.1 [redacted numeric sequence]

| 1 | 1 | 0 | 1 | 0 | 1 | 0 | 1 | 0 | 0 | L | op0 | op1 | CRn | CRm | op2 | Rt |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

And the condition below.
IMPLEMENTATION DEFINED system instructions which are accessed using SYS and SYSL with CRn 11 15.
IMPLEMENTATION DEFINED System registers which are accessed using MRS and MSR with the S3<op1><Cn><Cm><op2> register name.
Then report to the exception.

###### 61467 UNDEFINED instruction

In D1 stage the UNDEFINED instruction will be identified via UNALLOCATED opcode bit detection as well as instruction has UNDEFINED behavior under certain processor states The D1 decoder will extract these UNALLOCATED opcodes and report to the Exception block.
Another class of instructions that can generated UNDEFINED instruction exceptions are the HiVector instrucitons In certain corner cases HiVector compute instruction will not see opcode in the SOPQ For these cases OOO will report the UNDEFINED instruction exception too Although the actual logic is in the D2 stage the exceptions are with the same priority as other UNDEFINED instructions Please refer to HiVector compute Handling chapter for more details.
Yet another class of UNDEFINED instructions are the instructions appear in the wrong Exception Level or the wrong process State The instructions class will be decoded and checked against the processor state register and flag as an exception.
The System Register Access to unallocated encoding or executed in the wrong Exception level or violates the RW permission write to RO register will be reported as UNDEFINED instructions as well.
Besides according to Arm spec the SBO ShouldBeOne and SBZ ShouldBeZero instructions are choosen to be implemented as undefined.

###### 61468 Exception Generation instructions and Instruction implicitly caused exception

The instructions under Exception Generation class will trigger exception and some of these instructions will change the Execution Level as well The detection of these logic is done in D1 stage and the exception will be reported to the Exception block The encoding format is shown below.

### 3.1 [redacted numeric sequence]

| 1 | 1 | 0 | 1 | 0 | 1 | 0 | 0 | opc | imm16 | op2 | LL |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| opc | op2 | LL |  |  |  |  |  |  |  |  |  |
| 000 | 000 | 01 | SVC |  |  |  |  |  |  |  |  |
| 000 | 000 | 10 | HVC |  |  |  |  |  |  |  |  |
| 000 | 000 | 11 | SMC |  |  |  |  |  |  |  |  |
| 001 | 000 | 00 | BRK |  |  |  |  |  |  |  |  |
| 010 | 000 | 00 | HLT |  |  |  |  |  |  |  |  |
| 101 | 000 | 01 | DCPS1 |  |  |  |  |  |  |  |  |
| 101 | 000 | 10 | DCPS2 |  |  |  |  |  |  |  |  |
| 101 | 000 | 11 | DCPS3 |  |  |  |  |  |  |  |  |

Another exception with the same priority is some instructions take trap under certain condition Exception level Processor State etc Take Data Cache Zero by VA instruction DC ZVA as an example According to the Arm ISA spec the instruction will cause exception when.
If SCTLREL1DZE0 execution of this instruction at EL0 is trapped to EL1.

- When EL2 is implemented and is using AArch64 and SCREL3NS 1 HCREL2E2H 0.

If HCREL2TDZ1 Nonsecure execution of this instruction at EL0 and EL1 is trapped to EL2.

- When EL2 is implemented and is using AArch64 and SCREL3NS 1 HCREL2E2H 1 HCREL2TGE 0.

If HCREL2TDZ1 Nonsecure execution of this instruction at EL0 and EL1 is trapped to EL2.
To decode this instruction the D1 decode logic will use the System instruction encode space.

### 3.1 [redacted numeric sequence]

| 1 | 1 | 0 | 1 | 0 | 1 | 0 | 1 | 0 | 0 | 0 | 0 | 1 | op1 | CRn | CRm | op2 | Rt |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

With DC ZVA encode.
| op1 | CRn | CRm | op2 | <dcop> |
| --- | --- | --- | --- | --- |

#### 0.1.1 [redacted numeric sequence] ZVA

And combined the system register value to detect the exception Please refer to the Arm ISA Spec for the full list of exceptions.

##### 6.1.4.7 128 Hint

When prefetch is disabled or no continuous pattern is detected when prefetch is enabled to trigger HWP For improving the efficiency of memory copy operation the core need send a hint to the L3 indicating that 128 bytes of continuous access may occur.
| 61471 | FLOW |
| --- | --- |

Ifu detected continuous LD14Q instruction If they have the same base addr Rn ifu would generate a 128hint on the continuous instructions Then the hint send to decode with instruction Every instruction has a 128hint Decode will send the 128hint to dsp iex lsu l2 then final to l3.

###### 61472 PATTERN

The IFU identifies adjacent LD14Q with the same base address at F4 and marks the two instructions with 128hint including the cross-cycle instructions.

##### 6.1.4.8 Yield instTBD

- When yield inst in ST stall d1 64 cycle When yield inst in MT stall d1 64 cycle and other thread can go.

#### 6.1.5 D2 stage

##### 6.1.5.1 D2 decoder block

##### 6.1.5.2 Decode for rename

Rename need get source and destination atag information to do rename mapping The corresponding source and destination info will be extracted from the opcode based on the instruction encoding in the D2 stage.
Dst vldatagsize and src vld and src atag are decoded For atag ZERO is be d63 SP is d31 TMP0 is d32 TMP1 is d33 TMP2 is d34 TMP3 is d35 ZERO dst atag will kill its vld cause it will do nothing.
For these details of every instruction please see uoptablexlsx.

##### 6.1.5.3 Decode for dispatch

Dispatch need uop type information to decide dispatch to which issq and need to know instruction code and other payload sent to execution unit Uidtype and resolve count are decoded The hint for going to which issq is also obtained base on uidutype.
For these details of every instruction please see uoptablexlsx.

##### 6.1.5.4 Destination lane mapping

INTVFP uop have 1vfp dst at most and 1 int dst at mostso they neednt to be mapped.
CC dst is different There are 4 cc dst lanes and map as follow.

##### 6.1.5.5 VFP BUBBLE Squeeze controls

- Squeezing 8 decoded ops into 6 is VFP slots is done in a following manner at D2 pipestage the predecode operation will produce vector of VFP ops locations per instruction that will be used to calculate controls for shifting up VFP ops into slots that contained nonVFP ops.

Mapping as follow

##### 6.1.5.6 ROB id calculation

For ROB ID calculation the information will be combined with the ROB ID sum stored as mux select in the D1 stage flop The exact ROB ID needed will be determined by the logic in the D2 stage as shown below.
The precomputation request of the ROB ID is done in D2 to alleviate the timing constraint of ROB access Also the ROB ID calculated here is the virtual robid The physical robid is based on the earlier-generation core ROB allocation scheme Please refer to the ROB uArch section.
The ROB full can also be predetermined in the D2 stage Since the ROB entries can be released in the commit stage while new entries being allocated the ROB full detection has to take both allocation and releasing into account The ROB full will be considered as a stall condition The left capacity of rob entry is a part of threadselect generation logic.
The robid for an instruction is calculated based how many group has been accumulated before older instructions the instruction AND whether the instruction will startend a group At D1 stage the instructions will be identified as one of the categories above Combined with the position age information of the instruction D2 logic will create robid plus X cases.
Since all instructions from D1 stage are from the same thread there is no need to take Thread ID into the logic The ROB control will handle the entry allocation for both threads.
For a set of instructions from D1 stage taking the 4th uops u3 ROB ID calculation for example its robid can be plus 01234 based on the last rid in last cycle.
Plus 0 if the 4 uops are same instruction AND DOES NOT start a group AND last cycle not group full but one rid can have 2 instruction at most there are at least one start in u0u4.
Plus 1 u02 are same instruction and starts a group AND u3 itself DOES NOT start a group.
Plus 2 u0 starts a group AND u12 are same instruction and does not starts a group AND u3 itself DOES NOT start a group.
Plus 3 u0 starts a group AND u12 are same instruction and starts a group AND u3 itself starts a group.
Plus 4 u03 are four instructions AND every of them starts a group.
An example for plus 01234.

##### 6.1.5.7 PC management in D2

In D2 stage the main logic is to calculate pc offset value and check over flow or not.
PC offset management logic in D2 need to handle following cases.

###### 61571 Sequential PC

The PC offset register is used to keep track of the PC progression passed D2 after the current PC buffer entry is allocated The PC offset will be incremented by instruction and plus 1 means plus 4 bytes The PC information including the previous PC Buffer write pointer PB Buffer index and the PC offset will send down to the pipeline.
The PC offset will keep incrementing until a new PC buffer entry is allocated The offset count will be reset for the new PC buffer entry and start counting up for the subsequent instruction which uses sequential PC.
If the PC offset has accumulated to 63 then need start a new PC buffer entry and PC offset reset to 0.

###### 61572 Predicted Taken Branches

At D2 stage the number of Predicted Taken Branches will be identified There can be instructions using Sequential PC after the Predicted Taken Branches Their PC will be represented by the new PC buffer entry index plus the new PC offset starting from 4 right after the pc of Predicted Taken Branches.

###### 61573 Branch Flush

When Branch Flush is observed BRU will send the PC Buffer index corresponding to the mispredicted BR The younger PC buffer entries will be flushed The PC offset will be reset as well.
The next fetched instruction will be right after the BR and its PC will establish a new PC buffer entry The write pointer will be the mispredicted BR index plus 1.

###### 61574 Exception Flush

If a ROB flush is observed the write pointer and the PC offset will be reset to 0 The PC buffer will be emptied The restart address will be the new PC base at the entry 0.

###### 61575 PC Buffer Full

If an instruction in the D2 stage that requires new PC Buffer entry and it will make the PC buffer full or exceed number of pc writing port The younger instructions that require a new PC buffer entry and all the subsequent instructions will stall.
Similar to ROB full condition the PC buffer full logic has to consider the newly add entries as well as the releasing entries.

##### 6.1.5.8 MovrMovifmovrsxtw instfmov xzr opt in D2

The instructions which can perform move optimization are detected at D1 stage At D2 stage decode check further wether the instruction can do move optimization actually and limit the num of instruction for move optimizationThen rename and dispatch would work on that if they see the move hint from decode.
For movi or movr include sxtw optimization instruction type there are 3 instructions optimization can be supported each type in one cycle.
For fmove instruction type only one instruction optimization can be supported in one cycle.
For fmov xzr instruction type Rn is zero register and all instructions can be supported in one cycle.
Movi instruction
| Instruction | comment |
| --- | --- |
| MOVN 32-bit | Rd is not ZR |
| MOVZ 32-bit | Rd is not ZR |
| MOVN 64-bit | Rd is not ZR |
| MOVZ 64-bit | Rd is not ZR |
| ADR | Rd is not ZR |
| ADRP | Rd is not ZR |

Movr instruction
| Instruction | comment |
| --- | --- |
| ORR shifted register 32-bit | shift 00 imm6 000000 Rn 11111 |
| ORR shifted register 64-bit | shift 00 imm6 000000 Rn 11111 |
| ADD immediate 32-bit | sh 0 imm12 [redacted numeric sequence] |
| ADD immediate 64-bit | sh 0 imm12 [redacted numeric sequence] |

Fmovr instruction
| Instruction | comment |
| --- | --- |
| FMOV register singleprecision | NA |
| FMOV register doubleprecision | NA |
| ORR vectors unpredicated | NA |
| ORR vector register | NA |

Sxtw instruction
| Instruction | comment |
| --- | --- |
| SXTW Alias of SBFM | NA |

Fmov xzr instruction.
| Instruction | comment |
| --- | --- |
| FMOV general 32-bit to singleprecision | Rn 11111 |
| FMOV general 32-bit to haftprecision | Rn 11111 |
| FMOV general 64-bit to doubleprecision | Rn 11111 |
| FMOV general 64-bit to halfprecision | Rn 11111 |

At D2 stage

##### 6.1.5.9 Saveop

###### 61591 User defined field

Arm reserves an encoding space for implementation defined instructions as following The following are used to decode whether an 32-bit instruction is implemented defined or not.
| Bit | 31 | 30 | 29 | 28 | 27 | 26 | 25 | 24 | 23 | 22 | 20 | 19 | 15 | 13 | 12 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| value | 1 | 1 | 0 | 1 | 0 | 1 | 0 | 1 | 0 | 0 | 0 | 1 | 1 | 1 | 1 |

The following are user defined field.
| bit | 18 | 17 | 16 | 21 | 14 | 11 | 10 | 9 | 8 | 7 | 6 | 5 | 4 | 3 | 2 | 1 | 0 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

The above 17bits as free encoding space is used to define hivect instructions Due to the limits of encoding space all 3 or more operands functions are built with two 32-bit instructions which is called double instruction format.

###### 61592 SAVEOPEL0

The 1st instruction that carries the opcode is called the SaveOp instruction and should be scheduled before the 2nd instruction that carries the register indexes It writes a system control register SAVEOPEL0.
| 1 | Store the double instruction operation code When contents switch store this register to the memory stack |
| --- | --- |
| 2 | No RW constraints No trap behavior There are totally 19 saveop are supported |
| 3 | SAVEOPEL0 is a 32-bit register [3110] bits are reserved zero |
| 4 | Accessing the SAVEOPEL0 the 1st instruction of double instruction will update it MRSMSR can update it as well with golden value or garbage value |

MRS Xt SAVEOPEL0
MSR SAVEOPEL0 Xt
Register access is encoded as follows.
| Op0 | Op1 | Crn | Crm | Op2 |
| --- | --- | --- | --- | --- |
| 3 | 3 | C15 | C3 | 0 |

Suggest not to configure it by MSR.
| 5 | If the program writes the SAVEOP by MSR instruction with garbage value the following firstsecond instruction of dual instruction packet will generate undefined instruction exception and only legal MSR SAVEOPEL0 instruction after illegal MSR will clear illegal detection SAVEOPEL0 format is shown below |  |
| --- | --- | --- |
| Bits | Description |  |
| [3110] | RES0 |  |
| [9] | Bit [16] of SAVEOP instruction | SAVEOP H Bit |
| [80] | Bit [80] of SAVEOP instruction | SAVEOP[80] Bit |

The 1st instruction is not dispatched to issue queue It update the latest saveop value in decode And others it execute just like NOP When the 2nd instruction arrives in OOO it looks up the latest SAVEOP and carries the opcode with it and goes down the execution pipeline The 2nd instruction gets dispatched into the issue queue gets picked executed and retired as usual.

###### 61593 Saveop queue

The dual hivector instruction includes a saveop instruction and a 2nd hivector instruction The saveop instruction updates the saveop register in saveop queue in decode and the other behavior is the same as the nop instruction The 2nd hivector instruction provides operands src and dst atag Decode would dectected the 2nd hivector at D1 stage and then break its uops at D2 stage based on the latest saveop Decode will also replace the opcode with the latest saveop information for the 2nd hivector instruction Then the 2nd hivector uops will be dispatched to fsu.
The latest saveop for hivect instruction is got from three source.
| 1 | If there is a saveop instruction coming in same cycle and it is above hivect instruction The saveop value from the coming saveop instruction will bypass to hivect instruction directly |
| --- | --- |
| 2 | If no saveop instruction in same cycle the latest saveop in queue will give to hivect instruction |
| 3 | If no saveop instruction in same cycle or no saveop in queue the committed saveop will give to hivect instruction |

The latest speculative saveop info is stored in saveop queue in decode Both saveop instruction and msr saveop can update the value The info includes pld packet saveop opcode[16][80]bits and the rid of corresponding uop which update the info.
The latest committed saveop info is stored in one flop When the saveop instruction or msr saveop instruction is commited its saveop info in saveop queue will get in the flop and is also cleared in queue The info just includes pld packet It can offer the latest saveop info for the 2nd hivector instruction if there is no saveop info in queue It can offer the lastest committed data to srf for mrs saveop instruction want the pld data through iexsrf.
Decode has a 16entries queue to store the speculative saveop info by speculative saveop instruction and msr saveop instruction It contains 4 banks there are 4 entries in one bank Because only two saveop instructions in one cycle are permitted to launch decode has two writing ports and writes two banks one cycle at most The queue support SMT for one thread can have 8 entries to store saveop info.
There is bank pointer and entry pointer to indicate next entry will be written The queue entry can be set flushed commited set ovfw hint and get stall.
| 1 | Set by saveop instruction |
| --- | --- |

Saveop instruction write saveop into queue when it get into D3 from D2 by bankentry pointer As below figure indicat B0E3 will be written by next saveop instruction If there are two saveop instruction coming B0E3 will be written by first one B1E3 will be written by secode one.
| 2 | Set by msr saveop instruction |
| --- | --- |

Msr saveop instruction will update saveop in queue through iexsrf msr path It will clear all saveop in queue and write the value into B0E0 at ST mode and B0E0B0E2 t0t1 at MT mode When msr instruction is committed The value will be recorded in committed flop.
| 3 | Flushed |
| --- | --- |

- When ooo flush or bru flush happen the younger saveop will be flushed the vld will be cleared All saveop entry compare with flush rid in one cycle at flush r3 or e3 stage.

| 4 | Committed |
| --- | --- |

All saveop entry compare with next commit rid in one cycle at commit r2 stage When saveop entry is older than the next commit rid it will be committed vld will be cleared Then saveop value will be recorded in committed flop.
| 5 | Overflow hint |
| --- | --- |

When saveop will be updated by saveop instruction if saveop instruction is older than the next committed should set an overflow hint in the entry Cause the corresponding uop will be stalled at D3.
| 6 | Saveop stall |
| --- | --- |

When left saveop entry num is less than 2 saveop stalled will be generated at D3 next cycle It stall all D3 uop and wait the left saveop entry num gets more by committing older saveop in queue When the left saveop entry num is greater than 1 the saveop stall will be removed.

###### 61510 Inject instruction

The specific instructions can be injected at decode directly when the operations need to done by request of specific scenario There are three inject types instructions can be injected Each type has two instructions and can only be injected at corresponding uop lane.
The request for injecting the instructions of corresponding type is shown below.
When eret or exiting out of debug state is taken cc restore type will be injected.
When going to another EL or changing sp select bit of PSTATE sp switch type will be injected.
When going into exception is taken cc save type will be injected.
The uop info details of each injected instruction is shown below.
Cc restore type will restore cc from SPSR to PSTATE field.
Sp switch type will change stack point value between current EL and target EL.
Cc save type will save cc from PSTATE field into SPSR.
The whole injecting flow is shown below.
When the above exception is taken and the corresponding request is sampled by flush the request coming from excsrf is send to dec to stall d1 Ren get the request too and pend it until commit done comes When ren commit is done ren send the goning reqest to dec and rob Dec inject the corresponding uop in D3 by request type The uop will get out to dsp and ren as the normal uop from D3 Rob wait for the resolve of injecting uop When it get all resolve it will tell ren to commit When ren commit is done it will tell dec to remove stall.

#### 6.1.6 V8x

| 6161 | PAC |
| --- | --- |

ARMv83PAuth adds functionality that supports address authentication of the contents of a register before that register is used as the target of an indirect branch or as a load This feature is mandatory in Armv83 implementations ARMv83PAuth2 adds enhanced pointer authentication functionality that changes the mechanism by which a PAC is added to the pointer.
We support ARMv83PAuth all and ARMv83PAuth2 as following ID says.

###### 61611 Instruction

All PAC instructions include singlepac instruction and combinedpac instruction.

###### 61612 New system reg bits

Some new system reg and some bits are added by this pac feature.
About KEY
There are five keys used in pac instruction Every key has 128 bits and consists of Hi part and Lo part The corresponding register are put in IEX.
| Instruction | IA | APIAKeyLoEL1 | 64bits |
| --- | --- | --- | --- |
| APIAKeyHiEL1 | 64bits |  |  |
| IB | APIAKeyLoEL1 | 64bits |  |
| APIAKeyHiEL1 | 64bits |  |  |
| Data | DA | APIAKeyLoEL1 | 64bits |
| APIAKeyHiEL1 | 64bits |  |  |
| DB | APIAKeyLoEL1 | 64bits |  |
| APIAKeyHiEL1 | 64bits |  |  |
| Generic | GA | APIAKeyLoEL1 | 64bits |
| APIAKeyHiEL1 | 64bits |  |  |

About EN
ENIAENIBENDAENDB bits are added in SCTLREL123 these setting bits are send to DECODE and predecode in IFU for decoding dstuop info When EN is not valid the pac instruction which use the corresponding key.
For singlepac instruction execute as nop exclude PACGA and XPAC instruction.
For combinedpac execute as noAUT Instruction.
About TRAP
Add HCREL2APKSCREL3APK to control trap behavior of accessing KEY registers Add HCREL2APKSCREL3APK to control trap behavior of pac Instructions.
About TBI
Add the following TBI bits in TCREL.
| TCREL1 | TBID0TBID1 |
| --- | --- |
| TCREL2 | TBID0TBID1 |
| TCREL2 | TBID |
| TCREL3 | TBID |

TBI control bits can come from different TCR system register at different EL and setting Srf handles the different condition and just give four signals TBI0TBI1TBID0TBID1 to IEX The following show the different conditions.
| EL1 | EL0 and TGE0 | EL2 and e2h1 and tge0 | EL3 and e2h1 and tge1 | EL2 and e2h0 | EL3 |  |  |  |  |  |  |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TBI0 | TCREL1 | TBI0 | TCREL2 | TBI0 | TCREL2 | TBI0 | TCREL2 | TBI | TCREL3 | TBI |
| TBI1 | TCREL1 | TBI1 | TCREL2 | TBI1 | TCREL2 | TBI1 |  |  |  |  |
| TBID0 | TCREL1 | TBID0 | TCREL2 | TBID0 | TCREL2 | TBID0 | TCREL2 | TBID | TCREL3 | TBID |
| TBID1 | TCREL1 | TBID1 | TCREL2 | TBID1 | TCREL2 | TBID1 |  |  |  |  |

###### 61613 Uop info

| 1 | When ENIAIBDADB signals is not valid the singlepac exclude PACGAXPAC execute as nop It will be decode as nop at D2 stage so the uop will not be dispatched The combinedpac inst will decode dstuop info as a nonAUT Instructionfor example BRAA Instruction is decoded as BR instruction only |
| --- | --- |
| 2 | All pacautstrip uop are decoded for only going to alu issq at D2 |
| 3 | Single AUT instruction has nf vld at D2 Cause it may generate exception and flush |
| 4 | ERETAA has an ERETAUT exception type to exc module |

###### 61614 Resolve and Exc

| 1 | IEX give resolve for aut uop at E6 stage with or without AUT exc fault IEX give resolve for strip uop at E2 fastest |
| --- | --- |
| 2 | Single aut can generate exception from iex to rob if iex get failed authentic result Then rob will make a flush to for exc module to take this exc Exc module get exc info from IEX fro ESR |
| 3 | The aut uop in combinedpac instruction has no exc fault with resolve at E6 stage it just modify pointer to get a translation fault when ifu fetch instruction from the wrong PC address |

###### 61615 ERETAAAB path

When ERETAAAB is resolved IEX give new ELR value to exc module When eret exc is taken by flushr3 it give the new ELR as restart pc to ifu at R4 stage.
| 6162 | BTI |
| --- | --- |

Branch target identification is v85BTI feature It allows memory pages to be guarded against the execution of instructions that are not the intended target of a branch A new ID field is added IDAA64PFR1EL1BT using bits[30] and its 4b0001.

###### 61621 Flow

| 61622 | INST |
| --- | --- |

New Instruction is added it is BTI.
BTI <targets>
| 61623 | BTYPE |  |  |
| --- | --- | --- | --- |
| 1 | Ifu would send ifuooogp70d1 with Instruction to denote that the instruction is in guarded page |  |  |
| 2 | A twobit PSTATE field is defined PSTATEBTYPE Every instruction update the btype and will work on next instruction to generate bti exc or not |  |  |
| 3 | The following list the btype of instruction The btype value is decoded at D1 stage and work on the following instruction at D1 The btype of the last instruction is kept in D2 flop for first instruction in next cycle |  |  |
| gp | dst | btype |  |
| Br | 1 | not X16 or X17 | 11 |
| Blr | Any | Any | 10 |
| Br | 0 | X16 or X17 | 01 |
| Others | Any | Any | 00 |
| 4 | When flush happened decode need recover the corresponding btype of last instruction before the flush instruction from robsrf or IEX Decode sample them at R4 or E4 stage |  |  |

Rob send btype to srf at R1 stage with commitr1 Srf save the btype at R2 and send to decode the right btype at R3 if there is no flush If that is cmt+flush srf will send btype at R4 stage Cause it need let spsrelx sample the lastest btype beyond btype from this cmt+flush instruction.
Decode send signal to iex through dsp into uop package which meas btype2b11 So when iex have bru flush it could send to decode the right btype of the flush instruction with bru flush at E2 stage.

###### 61624 BTI exception

| 1 | Decode use the newest btype for the instruction It generate bti exc by the following condition |
| --- | --- |
| a | Btype is not 2b00 |
| b | The instruction is in guard page |
| c | Not int debug state |
| d | Exclude following compatible cases |
| 2 | Decode send bti exc to exc module Exc module use the exc to update btype of SPSRElx with lastest commited btype before the flush instruction And update ESR with ECBTI and the btype |

#### 6.1.7 Thread arbiter

Please see chapter 77284 for details of.
D1 thread arbiter
D2 thread arbiter
D3 thread arbiter
To ifu thread arbiter.
D3 starving safe mode.
If one thread always needs more shared resources like ptag or issq than another thread in one cycle the thread could be blocked at d3 by shared resources for a long time It will trigger d3s starvation mode for this thread.
When one thread is blocked at d3 by shared resources and no uop can get out decode will start starving counter The counter increases by 1 when uop of another thread gets out once from skid buffer When counting to a certain number which can be set by chickbit another thread will be blocked at d1 so no instrutions can come into for that thread Until uop of the starving thread gets out from skid buffer the block of d1 will be removed and starving count will be cleared If uop of starving thread goes out from skid buffer during the counting process the counter will also be cleared.

#### 6.1.8 Special instruction behavior

##### 6.1.8.1 Prefetch

When prfop is PLI or imm the prefetch instruction is treated as nop When prfop is L3 the prefetch instruction can be treated as nop or normal instruction by chickbit.

##### 6.1.8.2 HivecTBD

- See 6159

##### 6.1.8.3 RamindexTBD

##### 6.1.8.4 GQMTBD

##### 6.1.8.5 PACTBD

##### 6.1.8.6 Small shiftmacro open item

#### 6.1.9 New uop break

##### 6.1.9.1 MADDMSUB break

3rd integer src support is removed both in the renamer and in execution units.
Thus MADDMSUB are broken in to 2 dependent uops.
The break should not be done if the accumulator is a zero register to avoid impact on MUL.

##### 6.1.9.2 INT LDP uop break

Load pair uops will be broken into 2 uops to align the renamer write lanes to uops.
The 2nd uop is only needed for renaming and allocating extra ptag for LDP and is not dispatched towards AGU IQ.

##### 6.1.9.3 INT PrePostinc LDP uop break

Load pair uops with increment will be broken into 3 uops to align the renamer write lanes to uops.
The 2nd uop is only needed for renaming and allocating extra ptag for LDP and is not dispatched towards LSU IQ.

##### 6.1.9.4 INT STR register uop break

INT STR instructions require 3 INT sources Rename is being reduces to 2 INT sources so this instruction will have to be broken into 2 uops STA and STD.
VFP STR instructions are not affected since FSTDs source is a VFP register.

##### 6.1.9.5 INT STP uop break

INT STP instructions require 3 INT sources Rename is being reduces to 2 INT sources so this instruction will have to be broken into 2 uops STA and STD VFP STR instructions are not affected since FSTDs source is a VFP register.

##### 6.1.9.6 INT Prepostincrement STP uop break

INT STP instructions require 3 INT sources Rename is being reduces to 2 INT sources so this instruction will have to be broken into 2 uops STA and STD On top of that an extra uop is needed for increment.
VFP STP instructions are not affected since FSTDs source is a VFP register.

##### 6.1.9.7 FP LDP uop break

Load pair uops will be broken into 2 uops to align the renamer write lanes to uops.
The 2nd uop is only needed for renaming and allocating extra ptag for LDP and is not dispatched towards LSU IQ.

##### 6.1.9.8 FP PrePostinc LDP uop break

Load pair uops with increment will be broken into 3 uops to align the renamer write lanes to uops.
The 2nd uop is used both to write 2nd VFP dst and to do the increment.
If desired the INT 3uop break option can be used to simplify the design.

##### 6.1.9.9 OthersTBD

SIMD LDST
SVE
I2FF2I
PAC

##### 6.1.1.0 complex decoder instructions

###### 61101 SIMD LDST with more than 5 ops

Referencr to uop table.

###### 61102 Gather

Gather will be decoded into the following 8uop sequence.
Gather will bypass the normal IFU restrictions of having no more than 4 loadsstores.

###### 61103 Scatter

Scatter will be decoded into the following 8uop sequence.
Scatter bypass the normal IFU restrictions of having no more than 4 loadsstores.
