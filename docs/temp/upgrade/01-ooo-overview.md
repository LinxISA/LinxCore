# OOO Overview

This document introduces out-of-order execution terminology, the OOO unit responsibilities, feature scope, major structures, pipeline stages, and stall behavior.

> This Markdown document is the maintained specification. Sensitive metadata and internal reference labels have been removed while preserving architecture semantics.

## 1 Terminology

| Name | Description |
| --- | --- |
| OOO | Out of order unit including decode rename dispatch retire exception handling function blocks |
| ROB | Reorder buffer |
| MapQ | Mapping the architectural register number with renamed one |
| uop | The basic operation that can be handled in the execution pipes Uop is broken in decode stage if necessary |
| lane | Lane is used to allocate decode smap write ports resources |
| Dispatch | The stage of sending instructions from OOO to issue queue |
| VFP | SIMD and floating point |
| SVE | Scalable vector extension |
| Speculative Rename Map | The table records the mapping between architectural registers and physical registers The mapping reflects the latest mapping info at the rename stage To differentiate with the committed rename map this rename map is speculative and sometimes will be recovered by MapQ |
| Committed Rename Map | The table records the mapping between architectural registers and physical registers The mapping reflects the mapping info of all the committed instructions |

Table 1 1 OOO terminology.

## 2 Overview

The OOO unit is mainly responsible for handling instruction decodes rename dispatch and retire.
Each cycle up to eight instructions are received from the Fetch unit instructions are decoded into micro-ops (uops) up to 8 integer uops and 6 VFP uops per cycle can be decoded handled from D2 stage.
The register rename performs after decode where the source registers are renamed to the physical register numbers and the destination register is allocated a new physical register number.
After renaming the uops are dispatched in program order to corresponding issue queues For integer pipe the destination issue queues are ALU012345 BRU01and AGU012 issue queues For floating pipe the destination issue queues are FSU and FSTD01.
Instructions are executed out of order but will retire in program order enforced by the reorder buffer ROB The ROB tracks instructions status whether finished execution whether generated exception during execution in the pipeline and determines the number of instructions ready to retire in group.

- The mapping queue tracks the mapping between the architectural registers and the physical registers When an instruction retires the physical register for its destination register is upgraded in the mapping queue and points to the architectural register The previous physical register that pointed to the architectural register is recycled along with other resources occupied by the retired instruction.

The ROB supports the recovery on flush which could be triggered by branch misprediction exception or other internal events in the pipeline The flush broadcasts its thread ID and group ID any entries younger than group ID in same thread ID will be invalidated The invalidated instruction will release its physical register to the register pool In addition the mapping queue supplies the mapping info to the smap table for recovering the register renaming mapping.
The OOO unit detects instruction level exceptions at the decode stage and handles exception and interrupt at the retire stages.

## 3 OOO Feature List

V91V92V93 compatible ie RMENMILS64FGT XS HCX PACQARMA3 FPACCOMBINE BRBE MTPMU PMUv3p8 ETEv1p1 SPEv1p3 V90 compatible ie ETS PAN3 SVE2 TRF TRBE ETE DoPD PatialPartial v86v87 feature DGH I8MM WFXT.

- 6-wide instruction decode.
- 8 wide INT 6 wide FPSIMD 2 wide taken BR 6 ldstmax store is 4 max load is 6.
- 2 threads machine but only 1 thread is active at each pipeline stage.
- Decoders are shared between 2 threads.
- Staging flops are private for each thread.

Destination lane usage is calculated in IFU Instructions will not be sent to OOO if total destinations exceed the limit 8 int 6 fpsimd 4 cc.
Uop break scheme

- Break in dispatch stage S1 such as store becomes STASTD uop It can use 1 decoder lane.
- Break in decode stage D2 and combine in dispatch stage S1 such as LDP prepost index.
- Break in decode stage D2 such as CASP It can use 4 decode lanes and 3 dispatch lanes.
- A uop break engine is complex instruction if uop break more than five uops.
- A uop break engine is multi-cycle instruction if uop break it cannot be done in one cycle.
- A uop in downstream issue Q can be further broken into more uops scatter strore std uop.

Static resource allocation The availabilities of static resources are constant.

- Number of instructions lane 8 number of destination lanes 6 fp 4 cc number of issue Q write port 3 for each ALU Q 3 for each AGU Q 3 for each BRU Q 6 for FSU Q 3 for each FSTD Q.
- IFU detect some static resource limitation and stop instructions enter decode stage.
- Issq write port allocation is done by dispatch.

Dynamic resource allocation The availabilities of dynamic resources are changing all the time.

- Number of pTags 272 int 256 fpsimd 72 cc 72 shadow cc 96 Predicate Ptag number of PC buffer 64 number of RID 192.

Integer and floating point pipelines are decoupled in rename stage D3.

- 8 int 6 FPSIMD 4 cc destination can be rename in one cycle.
- 1 uop can rename 1 destination ptag.

Up to 1 int 1 fpsimd1 predicate or 1 cc or 1 pseudo cc destinations per uop.

- Most of resources allocation is done in D3 stage.

SMAP structures Used to store latest registers renaming information.

- Int 36 entry 4 tmp 8W16R.
- FPSIMD 36 entry4 tmp 6W18R.
- CC 1 entry 4W8R.
- Pseudo CC 1 entry 4W8R.
- Predicate 16 entry 6W18Rvfp pipeline.

Fast branch misprediction recovery and more pTags at one cycle through wider MapQ banks.

- MapQ structures.

Int 15 Banks 16 entry.
CC 9 Banks 8 entry.
FPUSIMD 14 Banks 16 entry.
Six wide retirement bandwidthRelease 6 RID at one cycle.

- 4 instructions can share 1 RID.
- RID is allocated in D3 stage.

Advanced PC management for reduced power/area.

- Setup based PC after reset done or thread on.
- PC buffer is 64 entry 1 extra for handling bru miss case 47 bit width 3W6R.
- PC buffer read port needed 2 retired instructions 2 bru01 PC 2 bru01 target PC.
- Generate hash PC of load/store instructions S1.
- dispatch PC for PCrelative loads S1.

Support fusion functions Fuse 2 instructions into 1 uop to issq.

- ALUCC+Bcond fusion AES fusion Movprfx fusion ORR+EOR fusion.
- Fusion is detected at D2.
- Grouping is done after fusion D3.

Dynamic issue queue allocation and balance D3 stage.

- ALU issue Q dispatch policies 6 ALU issue Q each Q has 3write ports.
- AGU issue Q dispatch policies 3 AGU issue Q each Q has 3 write ports.
- BRU issue Q dispatch policies 2 BRU issue Q each Q has 3 write ports.
- FP issue Q dispatch policies 1 FP issue Q each Q has 6 write ports.
- FSTD issue Q dispatch policies 2 FSTD issue Q each Q has 3 write ports.

Register-to-register MoveSXTW and imm Move instructions optimizations Imm Move write destination registers directly from OOO and not entering issue Q Movr and Movi optimization instruction resolve at S1 stage from DSP to ROB.

- At most 2 move imm optimization can be done per cycle.
- At most 3 int move regSXTW optimization can be done per cycle.
- At most 1 vfp move reg optimization can be done per cycle.
- Up to 6 vfp move XZR optimization can be done per cycle.

Instructions are grouped to use the same RID to reduce RID usage.

- Grouping is done in D2 stage after D1 decode exception detection before RID allocation.
- Up to 4 instructions can be grouped.

Undefined exception needs to be detected before uop breaking and fusion D1.
ROB 192 entries 8 banks 24 entries per bank.

- Allocate 8 rid at most per cycle in D3 stage and each lane contains 39 bits.
- Maximum 4 instructions per group.
- Commit 6 rid at most per cycle.
- How many resolve can ROB support.

Srfbru01buf alu012345 fsu0123 ld012 ldagen012 st01 stagen01 iexstd01 fsustd01.

- ROB function

Need to make sure instructions in the same group will have same PC base.
Need to make sure instructions in the same group will have same BID.
Commit condition All resolved collected No exception No pending interrupt.
Release resources
PC ptags ldst queue MapQ branch queue.
Update FPSR
Flush
Take Interrupt or not.

- 2 thread commit.

Each thread has a separate commit read pointer separate ROBMAPQ CMAP Two thread commit round robin.
SMT support

- Support STsingle thread and MTmulti thread mode.
- dynamically switch between ST mode and MT mode.
- ST mode can use all resource.
- MT mode mpqpcrob static partition of 11.
- Decode duplicate pipeline flops and share combinational logic.
- Private smapcmapsrfexception.
- PtagIQ is share with threshold.
- One cycle send one thread instructions from ifu to ooo.
- One cycle commit one thread instructions.

SVESVE2 support

- Support predicate rename.
- Support fp pair rename.
- Support FFR flow.
- Support new SVE system registers.
- Support new SVE exception.
- Support all SVE instructions decode.

## 4 OOO Structure Description

### 4.1 Block Diagram

#### Figure 4 3 OOO block diagram

### 4.2 Decode

The decode block supports AArch64 Instruction Set decode This includes Integer INT floating-point FP SIMD and SVE Scalable Vector Extension instructions The decoder will be provide information for instruction flow control renaming exception at or prior to the decode stage dispatching and execution as well as the ROBPC buffer resource allocation.
IFU provides up to 6 instructions per cycles one thread at a time and alternates between the two threads The flow-control interface will ensure the incoming instructions will never exceed the resource restrictions However it is still possible that the instructions may be stopped due to dynamic resource restrictions In this case the instructions in-flight within the decode block will be stored in staging flops per-thread When the resource is available again the instruction will enter the OOO pipeline and get renamed.

### 4.3 Rename

The rename block is responsible for mapping the architectural registers to the physical registers hence eliminate the WAW and WAR hazards There are three rename tables namely for integer floating point and conditional code registers Along with the rename table there are three corresponding committed register maps that track the committed mapping info only.
Rename maintain each PTag execution unit and size to tracking each physical register status when the uop is dispatch its source physical register status should read from the table and send to IEXFSU issue queue.

### 4.4 Dispatch

The dispatch block is responsible for dispatching instructions/uops to corresponding issue queues Each uop being dispatched carries a packet to hold the decoded info renamed registers and status bits The status bits will be updated every cycle to reflect current pipeline execution status.
The dispatch block maintains the entry status of issue queues so as to decide whether to dispatch and which issq to write There are 3 ALU issue queues the dispatch block will decide which ALU to dispatch based on the result of LFSR There are 2 AGU issue queue 2 STD issue queue 2 FP issue queue and 2 FSTD issue queue The dispatch block will also try to use different policies to dispatch.
Certain instructions will break uops at dispatch all the uop break in dispatch will go to different issq not support uop break to same kind of issq Like an integer store instruction dispatch will break a sta uop to AGU issq and a std uop to STD issq.
Certain uops will be merged to one uop at dispatch Like an ld pair dst1 uop and ld pair dst2 uop dispatch will merge to one ld pair uop to AGU issq.
| 45 | ROB |
| --- | --- |

The retirement block controls when instructions can fully retiredcommitment from pipeline or when to take exceptioninterrupt it also provide retired instructions pc and branch type to trace module.
For committed following operation will be done.
Release physical registers so it can be used for rename again.
Load/store queue can be deallocated.
Floating point exception flag can update FPSR.
Device load can access external memory.
Store can write to memory.
IFU update branch queue.
Provide retired instructions pc and branch type to trace module.
For exception rob will provide.
When to take exception.
When to take interrupt.
On which instructions pc the exception is taken.

### 4.6 Pc Buffer

Some instructions need pc program counter when execute like branch AdrAdrp LdSt for prefetch In Linxcore microarchitecture the entire pc value is not transferred with uop pipeline to save area and power The pc is divided into pc base and pc offset The pc buffer is used to store the pc base value the base pc index and pc offset value will go with uop to ALU issue queue When ALU issue queue pick a uop which need pc then will read pc buffer with the pc index pc buffer then sends the base pc value to issue queue issue queue will add the the base pc with pc offset to get the whole pc AGU issue queue only need the hash pc with uop so pc buffer does not have read ports for AGU and delivers hash pc to AGU issue queue instead of pc index and pc offset.
| 47 | SRF |
| --- | --- |

System register file holds Arm architecture special-purpose registers system registers and earlier-generation core implementation defined registers WFx is also executed in SRF.
| 48 | EXC |
| --- | --- |

Basically speaking exception handling module handles all the OoO Flush process and some control info for DTU module Besides it also handles the control of SMT switch and wfi mode switch.
| 5 | OOO Data Flow Description |
| --- | --- |

### 5.1 Overall OOO pipeline

#### Figure 4 1 Overall OOO pipeline

Note The above OOO pipeline is based on larger core class implementation and has some differences with earlier-generation core which is a mid-size core class For example the decode width is not 8 but 6 for earlier-generation core.

### 5.2 Pipeline stage descriptions

#### 5.2.1 D1 stage

IFU will provide up to 8 instructions from one thread per cycle Based on the information collected the following tasks will be done in D1 stage.
IFUOOO instruction flow-control handshaking protocol to provide steady instruction flow from IFU to OOO this includes the thread selection logic The function guarantees the instructions can flow through the OOO pipeline without blowing out the resource restrictions.
Uop break the majority of the instructions can be executed without breaking into multiple uop A small group of instructions has to be broken into uops and executed in certain order The D1 decoder will identify the encoding and uop synthesis.
Early exception identification D1 stage logic will digest the exception status collected by IFU If there is no exception from IFU decode will identify UNDEFINED instructions and instructions that generates others exception like trap via D1 decode.
Instruction Fusion the instruction fusion opportunities will be identified in the D1 stage and the instructions merger will be done at early stage of D2 in time for muxing to D3 flops and for processing info of head or tail uop in D2.
Instruction decode for ROB grouping The information needed for ROB grouping is decoded in D1 stage.
BranchQ ID BID increment IFU uses BID to keep track of BranchQ location when instruction retires or causes a flush OOO need to relay the information back to IFU The logic in D1 stage will keep track of BID increments.
Decode for PC management at D1 stage decoder will identify whether an instruction is using the Sequential PC previous PC + 4 or it is a predicted taken Branch which will jump to a new PC This is required for PC management.
Thread select Tidd1 and tidd2 used in D2 of next cycle will be generated in D1 stage from thread arbiter.

#### 5.2.2 D2 stage

Uop decode for renaming The source and destination registers will be extracted from the opcodes for renaming in each uop.
ROB and PC buffer resource allocation The ROB pointer handling in D2 stage and PC in D3 stage The ROB access will be in the D3 stage and PC in S1 stage If there is not enough space in the data structures the information will be sent to IFU via thread select interface with the D3 staging flops OOO will request IFU to send instructions from the other thread.
Thread select Tidd3 used in D3 of next cycle will be generated in D2 stage from thread arbiter.

#### 5.2.3 D3 stage

Decode send uop info or uop pkt to dsprenrobpc from skid buffer process and send the sampled uop info to spu.
Dispatch dispatch will decide which uop to which issq according to the dispatch policy and each issq occupied status.
PC buffer update base pc and generate pc for incoming uops Update the pc buffer write pointer.
ROB addr the commit related information for an instruction will enter rob.
BID handling BID calculate and ROB grouping Keep BID base of each thread calculate BID add Bid inc from IFU and sent to dispatch The BID inc will start new group and stored in the ROB The ROB maintains a BID base for each thread The BID will be reconstructed and the BID base will be updated in the case of flush or instruction retire.

#### 5.2.4 S1 stage

Dispatch packet each issq packet will be packed at s1 stage and will write issq at s1 stage.
PC buffer write pc buffer write data mux for each entry.
Hash PC generate hash pc for each load/store instruction.
Mpq write up to 8 dst mapping relation will be mux to specific bank and entry.
Rob write up to 6 commit related information will be mux to specific bank and entry.
Rename The speculative mapping for instruction destination registers will be written to the SMAP.

### 5.3 Pipeline Stall

#### 5.3.1 Stall flow

#### 5.3.2 D1 stall

IFU will stop instructions from entering OOO pipeline under the following conditions.
More than 8 instructions in total required among the instructions in D1 stage.
More than 8 uops in total required among the instructions in D1 stage.
More than 6 VFP uops register required among the instructions in D1 stage.
More than 8 int uops register required among the instructions in D1 stage.
More than 4 CC destination required among the instructions in D1 stage.
More than 2 predicted taken BR among the instructions in D1 stage.
More than 6 ld+st among the instructions in D1 stage.
More than 4 st among the instructions in D1 stage.
OOO will generate stall to stop instructions from entering OOO pipeline under the following conditions.
The pipeline is full When backend resource has exhaust and stall uop to D2 then OOO has to stop IFU from sending more instructions When the uop of one thread at D2 is not selected by D2 thread in mt mode OOO has to stop IFU from sending more instructions of this thread.
Spnzcv switch When do spnzcv switch then set stall after flush and will release after mpq commit done.
Multi-cycle instruction Some instruction will do more than one cycle then will set stall before the instruction finish uop break When the uop of other thread at D2 is mcyc inst and is selected by D2 thread in mt mode OOO has to stop IFU from sending more instructions of this thread.
Starve stall When one thread is starved by shared resource it stalls at D1 of other thread.
Other stall There are some special seniors will stall D1.

- pending interrupt.
- configuration override bit limit IPC.
- wfx or flush limit ipc.
- no cmt flush
- livelock of lsu.
- entering inorder mode.

#### 5.3.3 D3 stall

The ROB access and PC buffer allocation is in the D3 stage as well as the renaming logic If the following condition occurs the instructions will be stalled at D3.
ROB entry is not enough.
PC entry is not enough.
Issue Queue is not enough.
MapQ is not enough or in recovery mode not available for allocation.
Ptag intccfpsvepredict is not enough.
Dsbesbsbisb stall following instructions.
All stall logic are completed at D2 stage every cycle and work at D3 stage if no uop can get out by stalld3 all uop will be blocked at D3 flop.

##### 5.3.3.1 ROB stall

Every uops rid compares with next commit rid of rob to decide whether it can get out next cycle If its rid is younger than next commit rid of rob then it can get out.
Please see rob section for details of rid comparing.

##### 5.3.3.2 PC stall

When the uop is pc overflow or predtkn branch it will alloc a new pc entry PC buffer has 3 writing ports So if the number of allocing pc entry is greater than writing ports number or greater than left pc entry the corresponding redundant uop should be blocked at D3 flop.
Only uop at lane0 could be pc overflow and all lanes uop could be predtkn branch.
The above case shows when pc available number is 3 only u0u3 could get out next cycle Pc alloc means pc overflowonly u0 or predtkn branchu023.

##### 5.3.3.3 DSP stall

DSP stall includes pipeline stall threshold stall safemode stall.
Pipeline stall
When s1 stall happens in dispatch it will generate pipeline stall to decode and no uop can get out from D3 Pipeline stall can only stall all uop or not it cannot stall by lane.
Thresholdsafemode stall.
If accounting entry number by one issq type alufpagustdldmfstd of one thread exceeds the threshold value dsp will stall decode by the corresponding issq type.
For example the uop type of all lanesfrom lane5 to lane0 are ls ls alu fp fp alu fp.
When alu threshold stall happens only u0 can get out.
When fp threshold stall happens no uop can get out.
When ls threshold stall happens only u0u3 can get out.

##### 5.3.3.4 REN stall

The rename stall includes ptag not enough mapq not enough mapq recovering Rename can only stall all uop or not it cannot stall by lane The uop of d3 should satisfy availability of fp resource int resource and cc resource If so then they can get out.

##### 5.3.3.5 dsbesbsbisbpsbtsbdsbnxs stall

Dsbesbsbisb instructions will block the following instructions at D3 after them before they committed.
