# IEX Issue Queues and Execution

This document covers ALU, AGU, and BRU issue queues, ready tables, register files, bypass networks, execution pipes, execution units, move optimizations, profiling, and power mitigation.

> This Markdown document is the maintained specification. Sensitive metadata and internal reference labels have been removed while preserving architecture semantics.

## 5 Function Descriptions

### 5.1 ALU ISSQ

The ALU issue queues have six separate issue queues namely ALU0 IQ ALU1 IQ ALU2 IQ ALU3 IQ ALU4 IQ and ALU5 IQ The depth of each ALU IQ is 16 and each ALU IQ has 3 write ports and 1 read port It is noted that in target core ALU0 IQ and ALU3 IQ are symmetric ALU1 IQ and ALU4 IQ are symmetric ALU2 IQ and ALU5 IQ are symmetric To simplify ALU IQ is abbreviated to ALU in this chapter.

#### 5.1.1 Dispatch rules

Overall instructions/uops are categorized into five types namely ALU Branch System MSRMRS LDASTA load/store address and STD store data According to execution latency ALU uops are categorized into 1-cycle and Multicycles ALU STD and System uops are dispatched to ALU IQ The details could refer to the following rules and diagram.
Rule 1 Up to 8 uops can be dispatched to issue queues per cycle while up to 3 for each ALU IQ up to 3 for each AGU IQ up to 2 for each BRU IQ.
Rule 2 Register files RF are divided into 2 banks according to their index ALU0 ALU1 and ALU2 belong to bank0 while ALU3 ALU4 and ALU5 belong to bank1 As mentioned before ALU0 and ALU3 are symmetric so the following rules are same to ALU0 and ALU3 so as ALU1 and ALU4 ALU2 and ALU5.
Rule 3 1-cycle ALU uops can be dispatched to every ALU IQ but multicycles uops can only be dispatched to ALU2 and ALU5.
Rule 4 STD uops can only be dispatched to ALU0 and ALU3.
Rule 5 System uops including MSRMRS can only be dispatched to ALU2 and ALU5.
Rule 6 The ALU uops with CC destination ptag that update CPSR NZCV can be dispatched to ALU1 ALU4 ALU2 and ALU5 However ALU uops with CC source ptag can only be dispatched to ALU1 and ALU4.
Rule 7 There are some special uops for data interaction with Floatpoint registers and Integer registers They are named as F2IF2CI2FC2FP2II2P uops The details could refer to Chapter 6 In general I2FC2F uops can only be dispatched to ALU1 and ALU4 and I2P uops can only be sent to ALU2 and ALU5 F2I uops can be sent to ALU0 ALU3 ALU1 and ALU4 from FSU module while F2C uops can be sent to ALU1 and ALU4 from FSU module P2I uops can only be sent to ALU2 and ALU5 from FSU module.
Rule 8 Some instructions are related to PC such as BLRBL ADRPADR The splitted ALU uops of BLRBL and ADRPADR uops are dispatched to ALU1 and ALU4 In order to reduce area and power some bits in entry are reused for these PCrelated uops The detail could refer to the following material.
[internal reference removed]

5. 1 ALU IQ dispatch pattern diagram.

#### 5.1.2 ALU Pickers

Each ALU issue queue has its own picker picking up the oldest ready entry In most cases symmetric IQs like ALU0 and ALU3 can both pick the oldest ready entry no matter the state of another issue queue Therefore the max bandwidth for ALU pickers is 6 However in some cases only one uop can be picked in the symmetric IQs.
STD uops are divided into even and odd by their SID store index and only one even and one odd STD uop could be picked Therefore there is a SID roundrobin mechanism to guarantee that ALU3 can only pick even STD uops when ALU0 pick odd STD uops and vice versa.
There is only one group of system interface in the pipeline for system uops So only one system uop can be picked in ALU2 and ALU5 no matter in ST single thread mode or in MT multi thread mode.
There is only one DIV DP datapath logic and PAC DP So only one DIV uop can be picked in ALU2 and ALU5 per cycle so as PAC.
Furthmore there are also some other resource restrictions to limit the pickers If an oldest ready entry is always not picked it will enter into safe mode the detail could refer to Chapter 14.

#### 5.1.3 ALU wake up and ready

Each source field has a ready bit to indicate its status If set the data of source register is available in the pipeline otherwise it has to wait for the wakeup signals from its producer.
Totally speaking each uop in ALU IQ has at most 2 INT source ptags and 1 CC source ptag with SRCVLD source valid signals to represent source ready If SRCVLD is 0 there is no need to wakeup and corresponding source ready bit is already set when dispatched If SRCVLD is 1 the source ptag could be set ready by looking up the ready table Otherwise the source ptag has to be waked up by producer in the pipeline.
For INT source ptag the potential producer could be from ALU012345 LD012 and FSU And for CC source ptag the potential producer could be from ALU1245 BRU01 and FSU As for SYS uop it carries socalled TempCC ptag and it can only be waked up by another SYS uop with TempCC ptag In other words for TempCC source ptag the potential producer would be from ALU25.
The uop can only be picked and issued after all its source fields are ready.

5. 2 ALU wakeup diagram.

#### 5.1.4 FPtoInt wake up

The moveFPtoInt type instructionF2I that returns the floatingpoint data back to the integer register file sharing the write port with ALU pipe As mentioned before ALU0134 both support F2I When it is picked the FP unit will send it to ALU for waking up its consumers and block any other pick from IQ The specific description of F2II2F is in Chapter 6.

#### 5.1.5 Block conditions

There are many block conditions to prevent ALU pick even if all sources are ready In target core the block conditions could be concluded as following.
General for all ALU IQ.
SafemodeBlock When one entry enters safemode other entries in the same IQ will be all blocked until safemode exits More details could refer to Chapter 14.
PickedBlock When entry is already picked it is not picked again unless uop is canceled in the pipeline.
CPMDropBlock CPM drop consists of some special signals from the monitors out of core For example the pipeline is need to be limited when the voltage of core drops under threshold Then entry is blocked because of CPM drop.
Unique for ALU0ALU3.
STDRRBlock Only 1 odd or even STD uop will be picked in ALU0 and ALU3 IQ that means when ALU0 picks odd STD uop if ALU3 also picks odd STD uop it will be blocked.
STDOSWBlock The concept could refer to Chapter 31 if the STD uop is too young out of the LSU sliding window it is need to be blocked.
F2IInsertBlock Refer to Chapter 61.
FSTLFPickedBlock Refer to Chapter 1862.
Unique for ALU1ALU4.
F2IInsertBlock Refer to Chapter 61.
Unique for ALU2ALU5.
LatencyBlock Refer to Chapter 5713.
ReflowInsertBlock Refer to Chapter 5713.
PACRRBlock There is only one PAC DP so one of ALU2 and ALU5 is blocked when both pick PAC.
SYSINOrderBlockSome system instructions need wait nonflush RID or nextretired RID to pick and there is also a thread roundrobin mechanism for system instructions in ALU2 and ALU5.
L2CCredMSRBlock Refer to Chapter 74.
L3CBusyBlock Refer to Chapter 75.

#### 5.1.6 Age matrix

The age matrix tracks the relative age between two uops If there is more than one ready uop in issue queue the oldest is picked as a candidate to issue and the younger uops will not be issued out.
The age matrix is organized as below diagram Actually the triangle in the lower left corner and the one in the upper right corner are opposite to each other which means Age [i j] Age [j i] The age matrix is implemented by the triangle format.

5. 3 Issue queue age matrix illustration.

The relative age between two entries is recorded in a unit of the matrix.
If entry I is younger than entry J then the unit of IJ is set to one.
To save space only the units with I>J are kept.
When an entry K is written for each unit KJ with J<K is set to one provided that entry J is older eg entry J had already been valid or is written on an older write port than entry K All other units IK with I>K are cleared set to zero.
The age matrix is updated at the dispatch stage when a new entry is written The age value is meaningful only when the issue queue entry is valid For example if entry1 is written but entry2 is not written whether agevalue[12] is 1 or not has no meaning as entry2 is not valid at all.
An example of age matrix update is shown in the following figure where age changes are shown with six instructions dispatched The initial state has all zeros in the matrix however this is not necessary in the design since age is qualified with valid bit Six instructions are dispatched with the last one replaces the first instruction.

5. 4 Age matrix update example.

#### 5.1.7 Speculative issue and recovery

Instructions depending on a load will be speculatively issued from the issue queue assuming that the load will be a hit The dependent instruction is waked up by load at E1 stage At E5 stage the dependent instruction is deallocated from the issue queue if the load is a hit Otherwise the instruction will remain in the issue queue until the load miss returns data The speculatively issued instructions including its own dependents will have to be killed as shown in the following diagram.

5. 9 Speculative issue recovery.

For performance reason only the instructions that directly or indirectly depend on the load are killed Independent instructions can still freely flow through the pipeline The direct dependent can be recognized by matching the renamed register number but not straightforward for the second and third level dependents In order to do that we introduce a new term load gene vector which records direct or indirect dependent load instruction stage Any dependent instruction inherits the load vector from its producer not necessary a load instruction The details see the next section.

#### 5.1.8 Dependence tracking

##### 5.1.8.1 Dependence tracking vector

If register file is speculatively read for every source operand before knowing whether it is really needed or not and later it is found that the source operand comes from bypass network the register file read value will be discarded so the power wasted It is important to avoid unnecessary register file read to save register file power and also to reduce register file read port sharing conflict.
The dependence tracking vector structure is the way to solve the issue It records the result bus bypass stage if it is picked immediately Each source has a group of tracking vector Due to different execution unit has a different execution pipeline the vector does not record the execution stage like E1 but record the write back stage like W1 Therefore for 1-cycle ALU uop E1 is equal to W1 and for 2-cycle ALU uop E2 is equal to W1 and so on.
For ALUFSULD need bypass W1W3 data so the format of tracking vector is as follows.
ByW1 ByW2 ByW3
If the vector bits are all 0 then the data is got from regfile.
Besides tracking vector we also need know which pipe its producer come from Therefore 4bits DPDE dependence are added for each source to record the producer The encoding is as following table.
| producer | encoding |
| --- | --- |
| LSU0X | X000 |
| LSU0Y | 0001 |
| LSU1X | X010 |
| LSU1Y | 1001 |
| LSU2X | X100 |
| LSU2Y | 0111 |
| ALU0 | 0011 |
| ALU1 | 0101 |
| ALU2 | 0110 |
| ALU3 | 1011 |
| ALU4 | 1101 |
| ALU5 | 1110 |

##### 5.1.8.2 Tracking pipeline for different execution unit

- Green color is iex generate the tracking vector.
- Red color is bypass stage using the tracking vector picked.
- Orange color is data will be from regfile.

No matter for which producer the fastest wakeup stage is in WM2 stage Then the tracking vector is 100 If the consumer is picked as soon as possible then the tracking vector in the pipeline is 100 Otherwise it will be shifted right until picked Besides the wakeup stage could be in WM1 W0 or W1 stage the corresponding tracking vector is 010 001 and 000 as the chart above shows.

##### 5.1.8.3 Load gene vector for tracking load dependence

As mentioned before there is a load gene vector which records direct or indirect dependent load uops stage And when load is miss in LSU those direct and indirect dependent uops need to be canceled and wait for a new wakeup.
Since there are 3 load pipes in target core and load cancel is occurred in E5 stage the format of load gene vector is as follows.
E2ld2 E3ld2 E4ld2 E5ld2.
E2ld1 E3ld1 E4ld1 E5ld1.
E2ld0 E3ld0 E4ld0 E5ld0.
It consists of 12 bits and every 4 bits is for one load pipe To simplify the illustration only one load pipe is used in the following example It is noted that different from tracking vector load gene vector is shifted right after picked in the pipeline While tracking vector is not shifted right in the pipeline.

- Green color is iex generate the load gene vector.
- Red color means dependent uops are canceled or not waked up.

##### 5.1.8.4 Tracking mix ldvect

To reduce stored bits and save power the tracking vector and load gene vector can be merged into a new vector which called MXVECT trackmixldvect The format of MXVECT is as follows and consists of 13 bits.
Byw1 E2ld2Byw2 E3ld2Byw3 E4ld2 E5ld2.
E2ld1 E3ld1 E4ld1 E5ld1.
E2ld0 E3ld0 E4ld0 E5ld0.
The key point is to get true load gene vector and dependence tracking vector.
According to the format of MXVECT the second highest bit could represent tracking vector or ld2 gene vector But if it represents ld2 gene vector the highest bit of MXVECT must be valid As the chart shown below lets take case1 as an example The first level dependent uop is waked up at WM2 stage by load2 producer then the highest bit of MXVECT to simplify there are only 5bits is valid to record the dependence stage and the second highest bit is also valid to track load miss so the MXVECT is 11000 for 1st level consumer If it wakes up 2nd level dependent uop in the next cycle then the highest bit of MXVECT is still valid but load gene vector is shifted right so the MXVECT is 10100 for 2nd level consumer.
Therefore to get the true dependence tracking vector from the MXVECT need following logic.
Trackvect[2] MXVECT[12]
Trackvect[1] MXVECT [12] MXVECT [11]
Trackvect[0] |MXVECT [1211] MXVECT [10]
Similarly to get the true ld2 gene vector from the MXVECT need following logic As the first 1 from left to right must be the tracking vector we need find whether there is left 1 before each bit of ld2 gene vector and qualified with it However for the lowest bitbit0 of ld2 gene vector the logic can be simplified because tracking vector only has 3 bits.
Ld2vect[3] MXVECT[12] MXVECT [11]
Ld2vect[2] |MXVECT [1211] MXVECT [10]
Ld2vect[1] |MXVECT [1210] MXVECT [9]
Ld2vect[0] MXVECT [8]

#### 5.1.9 Allocation and Deallocation

For performance the issue queue entry is deallocated as early as possible once the entry is not used It generally happens when the entry is issued to the execution pipe However certain cases prevent the deallocation as listed below.
| 1 | Speculatively issued |
| --- | --- |

Load can speculatively wake up its dependent uops Those uops will remain in the issue queue until the loads status is known If load is a hit its dependent uops will be deallocated If load is a miss although its dependent uops are picked they will be canceled in pipeline and will remain in the issue queue and wait for next wakeup.
The uops that depend on those dependent uops will also have to remain in the issue queue until the loads status is known Therefore there is a loads dependent chain whose length is from E1 to E5 stage of load to prevent the fastest deallocation After E5 stage the status of load is definite.
The uops that are not in the loads dependent chain should be deallocated after issued.
| 2 | Pipeline conflict cancel |
| --- | --- |

The uops picked in the issue queue may meet conflict in the pipeline and return back to the issue queue There are some sorts of pipeline conflict such as regfile read ports conflict and sidedoor cancel The regfile read ports conflict is caused by the sharing policy of regfile read ports and the details could refer to Chapter 55 Sidedoor cancel is generated by LSU module assuming the load/store uop in LSU did not get PA successfully The details could refer to Chapter 31.
For the detail of pipeline cancel please refer to the specific sections in Chapter 57.
| 3 | Flush |
| --- | --- |

The entry can also be deallocated when meet flush including OOO flush and BRU flush For OOO flush as it can be generated from the oldest instruction its flush RID is not need to be compared with entry RID For BRU flush those entrys younger than flush RID are deallocated.
Once deallocated ALU issue queue entry can reused at S1 stage for new allocation.
Flushflushallocationdeallocation.

#### Figure 5 10 Deallocate ALU issue queue entry not depend ld

#### Figure 5 11 Deallocate ALU issue queue entry depend ld

##### 5.1.1.0 S2 speculative pick

It is noted that in target core there is a new S2 stage between S1 and entry for solving timing problem between OOO and IEX It means the issue queue entry is used to be written in S1 stage and it can be picked in next cycle if all source ptags are ready the fastest stage is like S1>P1>I1>I2>E1 but now entry is written in S2 stage and fastest stage is like S1>S2>P1>I1>I2>E1.
In order to make up the performance we support Speculative pick buffer for S2 speculative pick meaning the uops could be picked in S2 stage instead of entry if they are ready If it is canceled in the pipeline it can be repicked from the issue queue entry However if there are both S2 pick and entry pick entry pick has higher priority and S2 uop is written to entry.
The pipeline of S2 speculative pick could refer to the following diagram The red block is the detail of S2 stage There are 3 slots of S2 stage for writing the issq and speculative pick The fastest pick stage is still S1>S2P1>I1>I2>E1 same as before.

- vision

### 5.2 AGU ISSQ

The AGU issue queues have three separate issue queues namely AGU0 IQ AGU1 IQ AGU2 IQ The depth of each AGU IQ is 14 And each AGU IQ has 3 write ports and two read ports one load and one store All AGU IQ can pick LDA uops but only AGU IQ0 and AGUIQ1 can pick STA uops.

#### 5.2.1 Dispatch rules

Rule 0 A specific rule overrides general rules.
Rule 1 Up to 8 uops can be dispatched to issue queues per cycle each AGU IQ can take at most three Three AGU IQs can take at most 8 load or store uops.
Rule 2 Load uops can dispatch to AGU012 issue queue Store address uops can dispatch to AGU01 issue queue Up to 8 load LDA uops can be dispatched from OOO to IEX AGU issue queues Only AGU01 issue queue supports STA thus up to 6 store STA uops can be dispatched from OOO to IEX AGU issue queues.
Rule 3 Store data uops STD are dispatched to ALU03 issue queues.

#### 5.2.2 AGU Pickers

Each AGU issue queue has two pickers load picker and store picker.
The load picker picks the oldest ready load uop and sends to LDA pipe The store picker picks the oldest ready store uop and sends to STA pipe AGU IQ 01 can pick one load uop and one store address uop at the same time AGU IQ 2 can only pick one load uop.
| Picker ID | Pipe | Note |
| --- | --- | --- |
| AGU0LDApicker | PLDA0 | ALL Load Address UOPS |
| AGU0STApicker | PSTA0 | ALL Store Address UOPS |
| AGU1LDApicker | PLDA1 | ALL Load Address UOPS |
| AGU1STApicker | PSTA1 | ALL Store Address UOPS |
| AGU2LDApicker | PLDA2 | ALL Load Address UOPS |

#### 5.2.3 AGU Wakeup

The AGU Issue contains 14 entries Each entry includes the source ready information to indicate whether the uops source is available in the pipeline either in regfile or in bypass otherwise it has to wait for the wakeup signals from its producer.
For each load and store instruction it needs to wait for all three sources to be ready src0 src1 and srcp predicate When the uop source is not valid its source is default as ready.
As shown in the following Figure the AGU Issue Queue is based on CAMstructure For each cycle each entry in the AGU IQ takes result ptag from 12 sources and compare with the entrys source ptag.

5. 1 AGU wakeup diagram.

Compared to ALU issue queue the AGU issue queue takes wakeup signal from 12 sources result ptags from ALU0ALU5 in WM1 Stage and result ptags from three load pipes each requires two destination ptags load0x load0y load1x load1y load2x load2y Based on the source dpde Extended Depend Info each source entry selects one of the 12 ptags and compares with its own source ptag When both ptags are matched then the source ready bit is marked as ready.
When all three uop sources are ready including uops are already before writing in entry and the case when source is invalid the entry is marked as entrysrcxrdy Then the entry is eligible to be picked from AGU picker.

#### 5.2.4 ALU WM1 Wakeup AGU

Due to timing constrains in ALU W1 to LSU data bypass back to back instruction issue from ALU to AGU is not allowed Therefore all ALU uops take one extra cycle to wakeup all AGU uops Essentially ALULoad and ALUStore chain takes extra one cycle.
For example
ADDI X2 X1 1
LDR X3 [X2 0]
ADD X4 X2 1
The ADDI to ADD chain can schedule back to back with 1-cycle latency But the ADDILDR chain has to take two cycles in order for the load to issue.
Therefore the AGU wakeup signal takes all result ptags from WM1 stage from ALU.

#### 5.2.5 Allocation and deallocation

UOPs picked from issue queues can be cancelled by many reasons at pipelines The cancelled uops can be repicked from the original issue queue entry The issue queue entry can be deallocated only when it is guaranteed the uop is not cancelled.
A 1-bit stat entrypicked is used for each issue queue entry representing the entry has been picked before.
Entrypicked is set to zero when the entry is initialized written in S2 stage.
Entrypicked is set to one when the entry is picked If the entry is already picked it should not be picked again But entrypicked is cleared when there is cancel from pipelines or source load cancels After the entrypicked is cleared the issue queue entry can be picked again.
The entry is deallocated when the entry is already picked and the entry is not in speculative state The load gene vector can tells each source whether it might depends on a load directly or indirectly If the source is depending on a load that is still between E1E2E3E4 stage then the current issue queue entry is in speculative state and it should not be deallocated.

#### 5.2.6 AGE Matrix

The AGU IQ AGE Matrix is the same as the ALU age matrix specified in 515.
There are two age matrices instantiated for load picker and store pickers The load age matrix selects the oldest LDA uops whose sources are all ready The store address age matrix selects the oldest STA uops whose sources are all ready.

#### 5.2.7 AGU Speculative Pick

The AGU issue queue is placed closed to LSU port but far away from OoO D3 port After the instruction is dispatched and ready table lookup in S1 stage there is one extra flop before writing into AGU issue queue in S2 stage.
In order to improve performance the current AGU issue queue can also pick from write ports directlye without waiting the write into issue queue entries.
Therefore the following pipelines are supported in AGU IQ.
| Producer | P1 | I1 | I2 |  |  |
| --- | --- | --- | --- | --- | --- |
| Case1 | S1 | S2P1 | I1 | I2 |  |
| Case2 | S1 | S2 | S3P1 | I1 | I2 |

There two cases for S2P1 and S3P1 respectively S2P1 stage means that the uop is in S2 stage and its payload is directly picked from the issue queue write slot S3P1 stage means that the uop is in S3 stage and its payload is directly picked from the issue queue write slot Slot picks have lower priority than the entry picks since the uops in entries are always older than the slot When there is no uop can be picked from the entry the picker then picks the S2P1 stage and then S3P1 stage.
For the current implementation if the uop is waked up in S2 stage and becomes all source ready it will not be picked due to timing constraints Therefore only those uops that are ready from Ready TableRTAB and S1 wakeup S1WK can be qualified for S2 speculative pick.

#### 5.2.8 Load gene vector

The load gene vector is a persource operand bitmask structure used for tracking the life cycle of direct and indirect load producers The bit vector marks the exact stage of origin loads that contribute to the readiness of the source When one uop issued it broadcast its destination vector all source vector or together Sources which are waked up inherit the vector When the source load gene vector matches load cancel it will clear the ready bit and recover necessary status signal due to maybe issues.
The load gene vector is a 12-bit structure that tracks the original load stage.
DeponE2ld0 deponE3ld0 deponE4ld0 deponE5ld0.
DeponE2ld1 deponE3ld1 deponE4ld1 deponE5ld1.
DeponE2ld2 deponE3ld2 deponE4ld2 deponE5ld2.
Each bit is corresponding to a load in a particular load pipe and at a particular pipe stage.
Each uops destination carries the load dependence vector of all sources.
Uopdstdepvector[] uopsrcAdepvector[] | uopsrcBdepvector[]
Every producer passes the load gene vector of its destination to the source of the consumer.
ConsumeruopsrcAdepvector[] produceruopdstdepvector[]
ConsumeruopsrcBdepvector[] produceruopdstdepvector[]
After one cycle all load gene vectors are shifted right for 1 bit meaning that the origin load goes to the next stage If all load gene vectors are zero it means the source is not speculative and it is not depending on a speculative load.

#### 5.2.9 Load and store pair

The load pair instruction breaks into two uops in decode due to it has two destinations During AGU IQ write stage S2 the two uop is merged into one uop entry.
For store pair instructions its threesource instructions are broken into one STA uops and one STD uop Then it can satisfy the twosource constraint from AGU issue queues.

##### 5.2.1.0 Sliding window block

To prevent overflows of store queues in LSU the LSU broadcasts a range of allowed StoreID to AGU issue queues so that only stores in this range can be issued to LSU.
For each entry in AGU issue queue if it is a store instruction and its store ID is compared with the sliding window from LSU If the SID is outside the range of sliding window the store instruction is blocked in the issue queue entry It should not be picked from issue queue.

##### 5.2.1.1 MDB block

To reduce nuke flush caused by load store violation the MDB memory disambiguation buffer is implemented in AGU IQ For more details please refer to Feature MDB in section 19 One extra bit is added for each IQ AGU entry to represent whether a load instruction needs to wait for a store or not If it needs to wait for a store the load is blocked by MDBblock signal It needs to wait until the corresponding store to wakeup during the E1 stage.

### 5.3 BRU ISSQ

BRU issue queues is used to receive and issue all kinds of branch instructions to pipeline.
The BRU issue queue has two separate issue queue modules namely BRU0 IQ and BRU1 IQ The depth of each BRU IQ is 24 And each BRU IQ has 2 write ports and one read ports.
BRU ISSQ is organized as fastslow queue structure In target core 8 entries are fast entry and 16 entries are slow entry For fast entry src0 and srcc are waked up at alu wm2 stage src1 is waked up at alu wm1 stage For slow entry all sources are waked up at alu wm1 stage.

#### 5.3.1 Dispatch rules

The Branch uops can only be dispatched to BRU ISSQ Up to 8 uops can be dispatched to IEX per cycle while up to two branch uops can be dispatched to one BRU ISSQ In other words OOO can dispatch at most four branch uops to IEX in one cycle.
When two uops dispatched to the same ISSQ the older one always selects write port 0 and the younger uop can only use write port 1.
BRU ISSQ support BRU fusion when BRU fusion happens one specific alu uop is merged into next branch uop In this case both alu and branch uops are dispatched to BRU ISSQ Refer to 84 for more details about BRU fusion.
Once a branch uop dispatch to IEX BRU ISSQ will assign a free entry to this uop at S1 stage BRU ISSQ always allocates a fast entry if at least one fast entry is available BRU may allocate a slow entry only when all fast entry is occupied.
If there is only one fast entry when two uops dispatched to this ISSQ the older one can allcate the only fast entry and the younger one goes to a slow entry.
BRU ISSQ will write all the information from the dispatch uop into ISSQ entry at S2 stage.

#### 5.3.2 Pickers

BRU issue queue has one picker picking out one oldest ready uop The picked uop sends its payload to BRU pipe if no block happens in the same cycle For BRU ISSQ the block can be branch inorder block safemode block or cpm drop block.

#### 5.3.3 BRU wake up and ready

Each valid source field has a srcrdy bit to indicate its status If set the source registers data is available otherwise it has to wait for the wakeup signals from its producer The uop can only be picked and issued after all its source fields are ready.
For source 0 or source 1 potentially the producer counld be from 12 execution unit ALU012345 LOAD 0x0y1x1y2x2y.
For source CC potentially the producer counld be from 6 execution unit ALU1245 BRU01.
BRU ISSQ has fast entry and slow entry For fast entry src0 and srcc are fast wakeup src1 is slow wakeup For slow entry all source is slow wakeup.
Because BRU ISSQ only use source 1 when the entry is assigned to a fusion uop which is a rare case For timing consideration source 1 always use slow wake up in BRU ISSQ.

6. 1 BRU integer source fast wakeup diagram.

Here is the digram for integer source fast wakeup logic in fast wakeup all ALU producers uses wm2 stage to wakeup BRU consumers All LSU producers uses E1 stage to wakeup BRU consumer.

6. 2 BRU integer source slow wakeup diagram.

While in the integer source slow wakup logic all ALU producers uses wm2 delay one cycle to wakeup BRU All LSU producers keep E1 stage wakeup as fast wakeup.

6. 3 BRU CC source fast wakeup diagram.

Here is the digram for CC source fast wakeup logic in fast wakeup all ALU producers uses wm2 stage to wakeup BRU consumers All BRU producer uses E1 stage to wakeup BRU consumer.

6. 4 BRU CC source slow wakeup diagram.

While in the integer source slow wakup logic all ALU producer uses wm2 delay one cycle to wakeup BRU All BRU producer keep E1 stage wakeup as fast wakeup.

#### 5.3.4 Age matrix

BRU ISSQ has the same age matrix structure as ALU and AGU ISSQ For more details refer to 515.

#### 5.3.5 Speculative issue and recovery

Instructions depending on a load will be speculatively issued from the issue queue assuming that the load will be a hit The uop will carry a nonzero dependence tracking vector to BRU pipeline in this case Refer to 59 for more information about dependence tracking vector.
If the BRU uop reach E1 stage without any load cancel it is a successful speculative issue If a load cancel happens during I1 or I2 stage on the pipeline the speculatively issued uop need to be killed.
When a uop is killed on the pipeline due to load miss the ISSQ need to set the corresponding source ready bit and picked bit to zero In this case the victim entry will not be picked before it waked again by its load producer And once the source is waked up again the entry can be speculative issue again.
BRU only wake up its consumer at E1 stage which means speculative issued BRU uops do not wake up other uops before it changes to nonspeculative When other instruction only depends on a BRU instruction it will issue nonspeculatively.

#### 5.3.6 ISSQ deallocation scheme

For performance we want to deallocate the issue queue entry as early as possible if the uop is defineitely able to execute However speculative issue prevents the deallocations.
In current design BRU ISSQ only cares about the status of this producer Once all source of the entry can be labeled as no speculative and the entry has been issued to pipeline without RF read port conflict the BRU ISSQ entry can be deallocated at once For RF read port conflict refer to 5721 for more information.
If all sources are no speculative the entry can be deallocated at I1 stage For speculatively issued uop the entry can be deallocated at E1 stage if uop is not cancelled or flushed.
Once deallocated the BRU issue queue entry can be reused in the next cycle at S1 stage.

#### 5.3.7 Pick uops entering the issue queue

BRU ISSQ has the same S1 wake up structure as ALU ISSQ Refer to 518 for more details.

### 5.4 Ready table

The ready tables are accessed in S1 stage to lookup the INTCCP source ptags for each uop The source ptag can set ready by waked up at S1 or looking up the ready table Conversely if ready bit is set it means that the ptag producer no longer resides in the IQ Ready table has the same amount of entries as IRF and has a single entry per bit Ready bits are reset upon reallocation of ptags by the rename The amount of clear ports is according to the INT dispatch Bandwidth The clear port of INTCCP ready table are 946 respectively.
Ready bits have several requirements.
| 1 | We cannot set them too early if we do a dependent uop may wake up in the matrixspecready too early |
| --- | --- |

## 2 We cannot set the too late since WB and column deallocation are related and we do not want a new to have a dependency on column which was already reset it will create dependency on a younger unrelated uop

| 3 | To avoid design complexity must be already cleared by D3 |
| --- | --- |

We may have an inlining cases where we allocate ptag producer and consumer in the same window and we want to make sure that the consumer sees that the ptag is not ready without any special inliningbypass.

#### 5.4.1 Int ready table and wakeup

Ready bits are set in the issue pipeline and the pipestage used for setting depend on the uops latency.
For ALU pipeline all the uop set the ready bits at E1 stage the normal uops set the ready bits at pipe E1 stage but the reflow uops set the ready bits at reflow E1 stage For Load pipeline all the loads set the ready bits at E4 stage It is a 136-entry 1-bit 2bank structure Each bank has 4 clear port from OOO 10 writeset ports from nonspec wkup ALU ISQ 012bank0 ISQ 345bank1 AGU dstx+dsty3 W1 stage movi1 at S1 stage and 16 8uop2src read ports from OOO at S1 stage.
There are two virtual regfile addresses 9d510 9d0 The 9d510 implies the int register as a uop sources value is all zero srcvld1 The 9d0 implies the int register as a uop destinations value is all zero dstvld0 These ptags will be always ready in ready table.
INT ready bits setclear.

#### 5.4.2 CC ready table and wakeup

Ready bits are set in the issue pipeline and the pipestage used for setting depend on the uops latency.
For ALU pipelineall the uop set the ready bits at E1 stage The normal uops set the ready bits at pipe E1 stage but the reflow uops set the ready bits at reflow E1 stage If the ptag has been waked up at precycle the information will be recorded in ready table For cc ptag the ready table has been partitioned into two parts reg cc and tmp cc the low 72 bits for register cc and the high 72 bit is for tmp cc The ready state can clear by OOO at D3 stage if the ptag has been renamed for new uops ptag That means the ptag is nonspeculative ready when the ready state is set in ready table The rc table has 4 clear port from OOO 6 write ports from nonspec wkup ALU IQ 1245 BRU IQ 01 at W1 stage and 8 8uop1src read ports from OOO at S1 stage The tc table has 4 clear port from OOO 2 write ports from nonspec wkup ALU IQ 25 at W1 stage and 8 8uop1src read ports from OOO at S1 stage.
There is a virtual regfile address 7d127 that implies the cc src registers value is all zero This ptag will be always ready in ready table and its same with int zero source ptag The srcc ptag cannot be 7d127 Note the cc dst register does not exsit.
RegCC ready bits setclear.
TmpCC ready bits setclear.

#### 5.4.3 SVE predicate ready table and wakeup

The prdy table stores each for SVE predicate ptags ready state 1 means the ptag is nonspeculative ready 0 means the ptag is NOT nonspec ready yet It is a 96entry1bit table 6 clear ports from OOO 4 write ports from nonspec wkup ALU IQ 14 at W1 stage p2i2 and 8 8uop1src read ports from OOO at S1 stage Only the WHILE uop is executed at ppy 2 cycle the dst ptag is writtenset at W1 stage.
There is a virtual regfile address 7d127 that implies the p registers value is all zero The srcp ptag cannot be 7d127 Note the p dst register does not exsit.
P ready bits setclear.

### 5.5 Register files RF

The register file contains both the architectural and working register values The working register copy is upgraded to the architectural copy once its producer retires At the integer side there are two register files integer register file and condition code register file.

#### 5.5.1 Integer register file

The integer register file has 272 entries divided into 2 banks 13 read ports and 6 writes ports Figure 527 shows INT RF read ports sharing policy Read port sharing policy is to reduce conflict between two issue queues as more as possible It is common to see the priority of ALU issue queue is higher than LSU issue queueand the priority of LSU issue queue is higher than BRU issue queue in rd6.

#### Figure 5 27 Integer Regiter File

Figure 528 shows INT RF write ports sharing policy INT RF is partitioned into 2 banks Each bank have 6 write ports 3 will be dedicated to specific ALU IQ and 3 will be dedicated to loads In order to maximize write ports usage some write ports sharing result bus as well For example write port 345 is designed for load XY result writing but load XY cannot be chosen at the same time for same bank that means per bank of each write port can just only chose one pipe of issue queue Thus we can sharing with other result bus like MOVI and FSU MOVI0 share write port 34 in bank0 with LSU012 issue queue MOVI1 share write port 34 in bank1 with LSU012 issue queue Then F2I 0123 share write port with ALU 1430 in bank 01The absolute priority of F2I issue queue is higer than ALU and the priority of LSU issue queue is higher than MOVI issue queue.

#### Figure 528 write share strategy

Figure 529 shows the detail hierarchy of integer RF has 272 entrys totally The entire RF array is divided into low 32 bits part and high 32 bits part for xfm power savings Each part has 2 banks and each bank is divided into 17 groups which has 8 registers per group This bank partition strategy is for physical implementation.

#### Figure 5 29 The Detail Hierarchy of Integer Regfile

All 13 read ports are connected into each bank and each group For read and write enable of each register ptags decode are split into three segments as follows Since ptag number go from 192 to 288 the ptag num will be 1b0 bank0 1b1 bank 1 While bank num is 1b0 that refers to DST ZERO REG d0 b000000000 While bank num is 1b1 that refers to SRC ZERO REG d510 b111111110.

#### 5.5.2 Conditional Code register files CCRF

Table 5 6 Conditional code register files readwrite ports.
The CC register file has 72 entriesCC RF has 4 read ports 6 write ports FSU01 share the write port 02 with ALU IQ 14.
Figure 530 shows the detail hierarchy of CC RF The entire structure is similar with integer regfile.

#### Figure 5 30 The Detail Hierarchy of CC Regfile

#### 5.5.3 IRF read port I1 cancel

For integer register file because of two pipe sharing read port conflict may happen at some time Once this situation comes out one of the picked uop eg uop0 need to be canceled to ISSQ and wait to repick ALU uop cannot be cancled LDSTABRU uop can be cancled.
Read ports follow absolute priority the priority of ALU issue queue is higher than LSUBRU issue queueand the priority of LSU issue queue is higher than BRU issue queue in rd 6 Once conflict happened LSU src or BRU src will be canceled to ISSQ at I1 stage and wait to repick.

#### 5.5.4 INT W3 Bypass

For timing reason BN is separated to two directions ALU and LSU ALU BN is used for data path in IEX while LSU BN is used for LSU interface ALU012345 and BRU012 src data are all from ALU BN and overall diagram is shown as below W1 and W2 bypass are in ALU BN and W3 bypass is in RF.

5. 31 ALU Integer Bypass Network.
5. 32 LSU Integer Bypass Network.

#### 5.5.5 Condition Code W3 Bypass

5. 33 CC Bypass Network.

#### 5.5.6 ALU onecycle 1C pipe bypass

5. 34 ALU pipe bypass diagram.

ALU 1C pipe completes the execution at E1 and writes the data into the register file at E2 Since the data is available at E3 from the register file we need three bypass paths to cover three cycle bypass stages E1>I2 E2>I2 and E3>I2 at regfile The I2 select signals are generated by comparing I2 tag with E123 tags.

#### 5.5.7 ALU multi-cycle MC pipe bypass

5. 35 MC pipe bypass diagram.

MC pipe completes the execution at E4 and writes the data into the register file at E5 Since the data is available at E6 from the register file we need three bypass paths to cover three cycle bypass stages E4>I2 E5>I2 and E6>I2 The I2 select signals are generated by comparing I2 tag with E456 tags For three cycle mac the stage is E3E4E5.

#### 5.5.8 Load pipe bypass

5. 36 Load pipe bypass diagram.

Load pipe presented data earliest at E4 and writes the data into the register file at E5 Since the data is available at E6 from the register file we need three bypass paths to cover three cycle bypass stages E4>I2 E5>I2 and E6>I2 The I2 select signals are generated by comparing I2 tag with E345 tags.

#### 5.5.9 Power Saving to Exploit Wform Instructions

For 32-bit Wform data processing instructions only lower 32bits are valid results and the higher 32bits can be ignored by the consumers with src xfm tracking scheme So the whole bypass path and register file array only need to enable the write to lower 32bits when the uop is a Wform instruction and the high 32-bit flops of each stage can be clockgated to save power The enable of these clock gates are the uop itselfs Wform or Xform attribute named dst xfm instead of its producers Wform or Xform attributes which is src xfm.
This power saving scheme result in a fact that the high 32bits in the integer regfile array is only valid when the producer uop is a Xform 64-bit instruction While for a Wform 32-bit instruction its result in regfile entry will have its high 32-bit UNKNOWN value and should be ignored during verification result comparison.

##### 5.5.1.0 RF resize

The integer register file size can be configured in range of [192 288] entries with an interval of 16-entry The each bank of regfile is ranging from 96 to 144tries with an interval of 8-entry Number 288 is limited by PPA targets theoretically the max entry num can reach 384.
The CC register file size is ranging from 32 to 96 entries with an interval of 8-entry Group number can be resized and each group has 4 entries.
For range of the configurable parameters of Register File please refer to DSE configuration excel sheet [8] for details.

##### 5.5.1.1 ZERO Register

In target implementation the general purpose zero register known as XZRWZR with its ATAG normally encoded as 5b11111 in data processing instructions has NO physical entity However its ptag often shorted as tag exists and this ptag is different when it is mapped as a source vs a destination register in target implementation design For a source ZERO register named as SRCZEROREG the srcvld is decoded as 1 and the src tag is always nonspeculatively ready ready in ReadyTable and NO register file read happens for SRCZEROREG ie no real read activity for ZEROREG For a destination ZERO register named as DSTZEROREG its dstvld is decoded as 0 for integer instructions and NO register file write will happen.
For DSE the size of regfile IntCCpredicate can be configurable but their ptag WIDTH signals are fixed not changeable to ease the interface eg IQ packet etc among different Boxes OOO IEX and FSU etc For range of the configurable parameters please refer to DSE configuration excel sheet [8] for details.
In the current design of Integer regfile the SRCZEROREG is defined as LXTOPINTPTAGWIDTHd510b111111110whereas DSTZEROREG is defined as LXTOPINTPTAGWIDTHd511 all ones Note that LXTOPINTPTAGWIDTH is fixed to 9 in target implementation SRCZEROREG using d510b111111110 is the request from OOO.
For CC regfile and Predicate regfile NO architecture ZERO registier but there is a microarchitecture defined ZERO register for both CC regfile and Predicate regfile required by OOO rename and they are defined as LXIEXCCPTAGWIDTH all 1 and LXIEXPRDPTAGWIDTH all 1 respectively Both the LXIEXCCPTAGWIDTH and LXIEXPRDPTAGWIDTH are fixed to 7 in target implementation.
Since the ZERO PTAG for CC and Predicate regfile occupies a ptag number the Nbit ptag can represent a maximum number of 2N1 indexing from 0 to 2N2 physical entries plus one ZEROREG without physical entity but occupying a ptag For integer regfile 2 ptag number occupied by 2 ZEROREG For example an integer regfile with 9-bit ptag has At most 382 register entries + 2 ZEROREG totally 384 and same rule for CC regfile and predicate regfile.

- Notes in future it is expected to use all ones ie LXIEXINTPTAGWIDTH1b1 to implement ZERO register for BOTH SRCZEROREG and DSTZEROREG this may need to use activelow ready saved in ready table so as to read 0 from ReadyTable means ready Or in another way to specifically decode all ones ptag always as ready when reading ReadyTable.

### 5.6 Bypass Network

#### 5.6.1 Int bypass network

According to the PP configuration mentioned above the BN structure is divided into the following categories ALUXSRC0 ALUXSRC1 ALUYSRC0 ALUYSRC1 BRUSRC0 LSUSRC0 LSUSRC1 and STDSRC.

##### 5.6.1.1 Int alu bypass network

Alu03 bypass
Std and alu share bypass std and alu use i2 stage data and alu also use e1 data.
Alu is different from std in imm data.
Alu25
Alu25 include sys data.
Alu14
Src1 include imm data.

##### 5.6.1.2 Int lsu bypass network

If there is alu w1 bypass to lsu the path will be below.
If there is no alu w1 bypass to lsu the path will be below.
The MUX of LSU SRC0 includes pcdataimmdata.
Note that immdata is only applied to SVE vector plus imm uops scatter store gather load 3264bits in detail for vector plus imm vector base and imm offset are placed in SRC0 and SRC1 However losharehi is only implemented on SRC1 in target implementation Therefore SRC0 and SRC1 are exchanged imm is placed in SRC0 for gatherscatter SVE.
LSU result data W1 and FSU W1 bypass are not included for the reason of bad timing The LSU result data W1 is muxed in LSU.
The only instruction which utilizes PC is load literal All other instructions only use hash PC and hash PC is transferred to LSU at I1 stage directly instead of through the bypass network.
Small shift feature is not adopted in SRC0.
The MUX of LSU src1 include immdata but LSU result data W1 and FSU W1 bypass are not included for the reason of bad timing The LSU result data W1 is muxed in LSU.
Small shift feature is adopted in SRC0.
Load/store A64 register offset does not support extend small shift is applied which leads to the performance degradation.

##### 5.6.1.3 Int bru bypass network

#### 5.6.2 CC bypass network

Bru cc bypass nertwork.
Alu cc bypass network.

#### 5.6.3 P bypass network

### 5.7 Execution pipes

#### 5.7.1 ALU PIPE

There are six ALU pipelines namely PPALU0 PPALU1 PPALU2 PPALU3 PPALU4 and PPALU5 and each ALU pipeline is corresponding to its issue queue Therefore the instructon type is same as issue queue as mentioned in chapter 511 To simplify PPALU is abbreviated to ALU in this chapter.

##### 5.7.1.1 latency overview

The latency of most ALU uops is 1-cycle and the pipeline is P1 I1 I2 and E1 stage Some ALU uops are multicycles which from 2 to 8 and more For L3C SYS with handshaking mechanism In target core the latency more than 4 is involved with reflow stage and it will be described later.
There is one multiplicatonaccumulation MAC unit in ALU2 and ALU5 respectively MAC are fully pipelined with 2-cycle latency for 32-bit MACMUL and 3-cycle latency for 64-bit MACMUL For more details of MACMUL implementation refer to Chapter 58.
There is one CRC unit in ALU2 and ALU5 respectively and the latency is 2 cycles For more details of CRC implementation refer to Chapter 58.
There is only one shared DIV unit by ALU2 and ALU5 The DIV has variable latency 6 cycles for 32-bit DIV and 8 cycles for 64-bit DIV For detailed DIV implementation please refer to Chapter 58.
There is a unique PAC unit which includes 4latency pipelined circuit for PAC instructions and 2latency pipelined circuit for AUT instructions Refer to Chapter 10 for more details.
Only 1 MRSMSR can be support each cycle and it is dispatched to ALU2 or ALU5 The latency is unique For CC destination path the latency is 1-cycle But for INT destinaton path the cycle is 4 for normal MRS 7 for far MRS and uncertain cycles for L3C MRS Refer to Chapter 7 for more details.
Because multicycles uops are only dispatched to ALU2 and ALU5 latency information is only need in ALU2 and ALU5 issue queue entry The latency is indentified in issue queue by UID and TYPE which are contained in issue queue packet QPKT from OOO to IEX There are 4 bits for different latency as following chart shows It is noted that the 4bits latency may not be onehot as MRS is not only 1 cycle for CC path but also multicycles for Int Path The latency information is mainly used for latency block logic.
| Latency [30] | Cycle num |
| --- | --- |
| 0001 | 1 cycle |

##### 0.0.1.0 2 cycle

##### 0.1.0.0 3 cycle

##### 1.0.0.0 > 4 cycle

##### 5.7.1.2 Reflow stage

In order to decrease the stage of pipeline and save power the latency more than 4 is involved with reflow stage which means the uops will go through the pipeline twice As following chart shows for 8 cycles 64-bit DIV E1 issue stage is corresponding to reflow P0 stage Similarily I2 issue stage is corresponding to reflow P0 stage for 7 cycles MRS and I1 issue stage is corresponding to reflow P0 stage for 6 cycles 32-bit DIV For L3C SYS there is a handshaking mechniasm that there is an acknowledge from L3C after receiving IEX request then it will go throught the reflow stage.

##### 5.7.1.3 Latency block and reflow insert block

Because only one result bus and one resolve interface are shared by differentlatency pipelines the uop ready to be issued may need to be blocked if an earlier multicycles uop has reserved the result bus at the same cycle Besides reflow stage has higher priority than issue stage at the same cycle and need also insert block to the uops in entry.
The following chart shows the conditions of latency block and reflow insert block Take case2 as an example 3-cycle uops pick will be blocked by 4-cycle uops at P1 stage and also will be blocked by reflow P1 stage for latency block Besides 3-cycle uops pick will also be blocked by reflow P0 stage.

- Green color is the blocked uop.
- Red color means latency block.
- Orange color means reflow insert block.

##### 5.7.1.4 Pipeline resolve

IEX need send resolve information to OOO when uops are finished Totally speaking except for AUT and MSR instructions the uops are resolved at E2 stage IEX will send accurate resolve valid to OOO at E2 stage and resolve payload like RIDTID to OOO at E1 stage for timing consideration.
AUT instructions are special because they need authentication result and generate exception if fail They are resolved at E5 stage MSR do not need send resolve information but send system write valid to OOO and OOO will generate resolve by itself.
As there is only one resolve bus in each pipeline there is resolve conflict as following chart shows The orange block means the resolve conflict is handled in the pipeline For example if a 32-bit DIV at P1 stage when there is a 64-bit DIV at I2 stage 32-bit DIV will be canceled in the pipeline The yellow block means the resolve conflict is handled in the issue queue entry by latency block mechanism.

##### 5.7.1.5 Pipeline Cancel

First of all pipeline cancel is strongly associated with microarchitecture The following cases are just for target core and need to be reviewed in future version.
Although ALU pipeline shares the register file read ports with other pipelines ALU has higher priority and there is no read conflict in pipeline However the uops in ALU pipeline can still be canceled by other pipeline because of resource conflict.
ALU pipeline detects load miss event from three load pipes in P1 I1 and I2 stage The uop has load dependence with corresponding load pipe is killed when load miss happens.
ALU pipeline monitors flush event in I1 I2 and E1 stage In I1 stage OOO flush in R3 R4 and BRU flush in E3 E4 need to be checked In I2 and E1 stage OOO flush in R3 and BRU flush in E3 need to be checked Any uop will be killed when flush rid is older than its rid and flush tid match in I1I2E1 stage.
For ALU0 and ALU3 STD uops could be canceled by FSTD uops occupied same even or odd SID from FSU and could be canceled by STA DVM uops from AGU pipeline.
For ALU1 and ALU4 I2F uops could be canceled in P1 stage by repicked VFP Load uops in E1 stage from AGU pipeline.
For ALU2 and ALU5 I2P uops could be canceled when meet P2I uops from FSU.
For ALU2 and ALU5 there are many resolve conflicts for those reflow uops like DIV PAC and Far MRS because there is only one resolve bus in each pipeline.
In brief ALU pipeline may generate.
P1 stage cancel recognize load miss and pipeline conflict but usually delay 1 cycle to I1 stage to generate cancel because of critical timing problem.
I1 stage cancel OOOBRU flush load miss pipeline cancel.
I2 stage cancel OOOBRU flush load miss.
E1 stage cancel OOOBRU flush.

#### 5.7.2 Branch PIPE

There are two branch instructions execution pipes called BRU pipe 0 and BRU pipe 1 BRU pipe 0 receives uops picked in BRU ISSQ 0 and send uops to BRU 0 BRU pipe 1 receives uops picked in BRU ISSQ 1 and send uops to BRU 1.
| BRU ISSQ 0 | BRU pipe 0 | BRU 0 |
| --- | --- | --- |
| BRU ISSQ 1 | BRU pipe 1 | BRU 1 |

##### 5.7.2.1 BRU pipeline stage

BRU pipeline logic mainly located in P1 I1 I2 and E1 stage.
P1 stage
Flop source payload such vld ptag tkvect rfrd rffw to I1.
Flop destination payload such as vld ptag to I1.
Flop uops payload such as rid tid bid ppkt to I1.
I1 stage
Flop source payload such as vld ptag tkvect rffw sxtw dpde to I2.
Flop destination payload such as vld ptag to I2.
Flop uops payload such as rid tid bid ppkt dpkt lid sid to I2.
Judge cancel and flush.
Ouput pc idx pc vld to OOO.
Output rffw source bank source zero to RF.
I2 stage
Flop destination payload such as vld ptag to E1.
Judge cancel and flush.
Output bid tid to IFU.
Output dpkt pred info pc buf info lid sid etc to BRU DP.
Output source payload such as sxtw xfm dpde to BN.
E1 stage
Wake up consumer in ISSQ.
Set ready table
Write CC register file.
BRU DP execute branch uops.
Output executed branch informations to IFU.
E2 stage
Send resolve to OOO.
Send bru flush to all other boxes.

##### 5.7.2.2 Branch pipe regfile ports

Each BRU pipeline has two integer RF read ports and one CC RF read port For PPA consideration it is costly if each source has exclusive read port in integer Register Files In current design each BRU source share a read port with one ALUAGU source For read port share policy refer to 55 for more detail.
All BRU read ports are low priority in share policy which means when ALU or AGU source need to read register at the same cycle BRU need to cancel this uop and reissue this uop later.
Each BRU pipe has one exclusively CC register read port which means if a bru uop issued to pipeline it is able to get data from register files under any circumstances.
Each BRU pipe has one CC register write port For normal branch uops no destination needs to write CC register BRU only need to write CC register when executes the fusion uop refer to 84 for more detail BRU pipe will send write request to CC register files at E1 stage if the fusion uop is not cancelled or flushed at i2 stage.

##### 5.7.2.3 Pipe Cancel and flush

BRU pipe may generate cancel and flush at I1 and I2 stage when an uop is cancelled or flushed the correspongding pipe vld will be clear to zero in the next stage Here is the list of cancel and flush which may affect BRU pipe.

1. I1 stage cancelflush.

ROB flush
ROB flush r3
ROB flush r4
BRU flush
BRU flush e3
BRU flush e4
Regfile port conflict.
I1 load cancel
Depend LSU BRU meet e5 cancel BRU meet e6 cancel.
Depend ALU and does not depend LSU depend ALU i1 meet e5 cancel depend ALU i1 meet e6 cancel depend ALU i2 meet e5 cancel depend ALU i2 meet e4 cancel.

2. I2 stage cancelflush.

OOO flush
ROB flush r3
BRU flush
BRU flush e3
I2 load cancel
Depend LSU BRU meet e5 cancel BRU meet e4 cancel.
Depend ALU depend ALU i2 meet e5 cancel depend ALU i2 meet e4 cancel.

#### 5.7.3 AGU PIPE

There are three load pipes and two store address pipes namely PPPLDA0 PPPLDA1 PPPLDA2 PPPSTA0 and PPPSTA1 Three load + two store address uops can be executed at one cycle In the following text PLDA is used as an abbreviation for PPPLDA and PSTA is used as an abbreviation for PPPSTA.
| 5731 | PLDA |
| --- | --- |

For PLDA pipeline logic mainly located in P1 I1 I2 E1 E2 E3 E4 and E5 Stage.
P1 stage
Payload is picked from ISSQ AGU and flopped to I1 stage.
I1 stage
Flop payload to I2 stage.
Regfile read confictdepending on loaddepending on aludepending on fstlf loadsidedoor cancel generation.
OOO flush and bru flush handle.
Sxtwuxtwsmall shift logic generation.
Uoptype and uopsize generation.
Srcp information is sent to FSU module.
I2 stage
Flop payload to E1 stage.
Depending on loaddepending on alu cancel generation.
OOO flush and bru flush handle.
Select four sources two STD FSTLF result ptags repicked load ptags and issue load ptags The.
Merged ptags are used to wakeup consumers at E1 stage.
E1 stage
Flop payload to E2 stage.
OOO flush and bru flush handle.
Wkup signal is generated to wake up the load consumer uops.
FP load information such as dstxvlddstyvlddstxtagdstytag is sent to FSU module.
E2 stage
Flop payload to E3 stage.
OOO flush and bru flush handle.
Wkup signal is generated to wake up the load consumer uops.
Ldm index and ldm vld sent from LSU will be used for ldm deallocation In later stage.
E3 stage
Flop payload to E3 stage.
OOO flush and bru flush handle.
Wkup signal is generated to wake up the load consumer uops.
Fstlf cancel sent from pipe alu will be mixed with data cancel sent from LSU module in later stage.
E4 stage
Flop payload to E3 stage.
OOO flush and bru flush handle.
Wkup signal is generated to wake up the load consumer uops.
Signals for writing regfiles are also generated.
Fstlf cancel sent from pipe alu is mixed with data cancel sent from LSU.
E5 stage
Wkup signal is generated to wake up the load consumer uops.
Signals for setting the ready table are generated.
Ldm deallocation signal is sent to OOO module.
| 5732 | PSTA |
| --- | --- |

For PSTA pipeline logic mainly located in P1 I1 I2 E1 Stage.
P1 stage
Payload is picked from ISSQ AGU and flopped to I1 stage.
I1 stage
Flop payload to I2 stage.
Regfile read confictdepending on loaddepending on aludepending on fstlf loadsidedoor cancel generation.
OOO flush and bru flush handle.
Sxtwuxtwsmall shift logic generation.
Uoptype and uopsize generation.
Srcp information is sent to FSU module.
I2 stage
Flop payload to E1 stage.
Depending on loaddepending on alu cancel generation.
OOO flush and bru flush handle.
E1 stage
Signals used for wkuping up MDB LFST are generated.

##### 5.7.3.3 LSU Load Repick

For the reason such as TLB missing in LSU some load instruction has been already sent to LSU but IEX cannot get the load data normally At this moment LSU will send a load miss signal at E4 stage and at E5 stage LSU can send repick I2 signal in fastest situation The repick signal is companied with some other payloads such as ridtid at I2 stage The signals from STD fstlf has higher priority than those from repick and the signals from repick has higher priority than those from issue a set of signals are selected as I2 stage signals based on the priority described above.

##### 5.7.3.4 Pipe Cancel Handle

PLDA pipe cancel handle is demonstrated below.
PLDA shares the register file read ports with ALU pipelines and BRU pipelines PLDA has higher priority than BRU pipelines However PLDA has lower priority than ALU pipelines When register read conflict occurs the pipe with lower priority will be canceled For PLDA register read conflict cancel is handled in I1 stage.
PLDA pipeline detects load miss event from three load pipes in P1 I1 I2 stage The uop depending on corresponding load pipe will be cancelled when load miss happens Specifically P1 stage meet E5 cancel is detected and handled in I1 stage I1 stage meet E4E5 cancel is handled I2 stage meet E4 cancel is handled.
Due to timing constraints the merged STD data in the LSU could not bypass to load I2 directly in W1 stage Therefore we do not allow backtoback FSTLFLoad pattern If a load uop in I1 stage is immediately following a FSTLF in its W1 stage then this load is cancelled.
PLDA pipeline detects alu cancel event from five alu pipes in P1 I1 I2 stage The uop depending on corresponding alu pipe will be cancelled when alu cancel happens Specifically for SRC0 and SRC1 P1 stage meet Alu W1 is detected and handled in I1 stage I1 stage meet Alu W1 is handled For SRCP P1 stage meet Alu W1 is detected and handled in I1 stage I1 stage meet Alu W0 and W1 is handled I2 stage meet Alu W2 is handled.
The sidedoor cancel signal is from LSU When sidedoor cancel is valid IEX cancels issued ldasta uops at I1 stage and the srcdata that IEX sends to LSU is muxed to 0.
PSTA pipe cancel handle is demonstrated below.
PSTA shares the register file read ports with ALU pipelines PSTA has lower priority than ALU pipelines When register read conflict occurs the pipe with lower priority will be canceled For PSTA register read conflict cancel is handled in I1 stage.
PSTA pipeline detects load miss event from three load pipes in P1 I1 I2 stage The uop depending on corresponding load pipe will be cancelled when load miss happens Specifically P1 stage meet E5 cancel is detected and handled in I1 stage I1 stage meet E4E5 cancel is handled I2 stage meet E4 cancel is handled.
Due to timing constraints the merged STD data in the LSU could not bypass to load I2 directly in W1 stage Therefore we do not allow backtoback FSTLFLoad pattern If a store address uopin I1 stage is immediately following a FSTLF in its W1 stage then this store adredd uop is cancelled.
PSTA pipeline detects alu cancel event from five alu pipes in P1 I1 I2 stage The uop depending on corresponding alu pipe will be cancelled when alu cancel happens Specifically for SRC0 and SRC1 P1 stage meet Alu W1 is detected and handled in I1 stage I1 stage meet Alu W1 is handled For SRCP P1 stage meet Alu W1 is detected and handled in I1 stage I1 stage meet Alu W0 and W1 is handled I2 stage meet Alu W2 is handled.
The sidedoor cancel signal is from LSU When sidedoor cancel is valid IEX cancels issued ldasta uops at I1 stage and the srcdata that IEX sends to LSU is muxed to 0.

##### 5.7.3.5 Pipe Flush Handle

PLDA pipe flush handle is demonstrated below.
In P1 stage OOO flush r3 and BRU flush e3 are considered but they are handled in I1 stage.
In I1 Stage OOO flush r3r4 and BRU flush e3e4 are handled.
In I2 Stage OOO flush r3 and BRU flush e3 are considered but they are handled in E1 stage.
In E1 Stage OOO flush r4r5 and BRU flush e4e5 are handled.
In E2 Stage OOO flush r3r4 and BRU flush e3e4 are handled.
In E3 Stage OOO flush r3 and BRU flush e3 are handled.
In E4 Stage OOO flush r3 and BRU flush e3 are handled.
PSTA pipe cancel handle is demonstrated below.
In P1 Stage OOO flush r3 and BRU flush e3 are considered but they are handled in I1 stage.
In I1 Stage OOO flush r3r4 and BRU flush e3e4 are handled.
In I2 Stage OOO flush r3 and BRU flush e3 are handled.

##### 5.7.3.6 Movi Optimization

Immediate MOV optimization is that dispatch directly write the immediate data of the MOV to the regfile in the IEX The regfile write port of movi is shared with plda pipe.
At D2 stage
Detect the MOVI instruction if the reg move is also detected at the same cycle the movi should give up optimization and dispatch it to ALU issue queue.
The adrp pc buffer only has one entry so before dispatch take the last adrp pc from the buffer the following movi instructions cannot be optimized.
At D3 stage
MOVI optimization can be done only when the enable signal from the IEX to OOO is valid otherwise the MOVI optimization will be abort and dispatch it to ALU issue queue.
In the dispatch the optimized MOVI will send a resolve to the ROB at S1 stage instead of allocating alu issq port and index.
Besides above operation the immediate data should generate from a 32-bit opcode and 49-bit pc as the figure below shows and flop to S1 stage and write into regfile at S2 stage through write port shared with plda pipe.
The type of instruction need to optimize.
MOVN 3264
MOVZ 3264
ADR
ADRP
Plda pipe writes regfile in E4 stage In E2 stage which regfile write ports will not be used by plda pipe will be detected If there is at least one writing port not used by plda pipe the enable signal from the IEX to OOO is valid In this case MOVI optimization can be sent to IEX and directly write the immediate data of the MOV to the regfile through the write port which is not used by plda pipe Otherwise the MOVI optimization will be abort and dispatch it to ALU issue queue.

### 5.8 Execution Units

| 581 | ALU |
| --- | --- |
| 582 | ALU1C |

##### 5.8.2.1 ALU1C block diagram

The 1C modules diagram includes all the function units and the datapath in this module The input signals are simplified such as the ctrlsignals which indicate all the valid signals data size shift amounts etc.
Not all the included function units are valid in all pipes the difference among the six pipes are listed below Note that the pipe 0 and pipe 3 pipe 1 and pipe 4 pipe 2 and pipe 5 are totally same.

##### 5.8.2.2 Instructions supported

| A64 Instructions | Size | LAT | BW | PIPE |
| --- | --- | --- | --- | --- |
| ADD ADC AND BIC EON EOR ORN ORR SUB SBC | wx | 1 | 6 | All 6 pipes |
| ADDS ADCS ANDS BICS SUBS SBCS | wx | 1 | 4 | Pipe 14 Or Pipe 25 |
| ADDS SUBS | wx | 2 | 2 | Pipe 25 |
| ADD SUB | wx | 1 | 6 | All 6 pipes |
| ADDS SUBS | wx | 1 | 4 | Pipe 14 Or Pipe 25 |
| ADR ADRP | x | 1 | 2 | Pipe 25 |
| CCMN CCMP | wx | 1 | 2 | Pipe 14 |
| CSEL CSINC CSINV CSNEG | wx | 1 | 2 | Pipe 14 |
| AXFLAG XAFLAG | 1 | 2 | Pipe 14 |  |
| SETF8 SETF16 RMIF CFINV | 1 | 2 | Pipe 14 |  |
| AND BIC EON EOR ORN ORR | wx | 1 | 6 | All 6 pipes |
| AND BIC EON EOR ORN ORR | wx | 1 | 2 | Pipe 14 |
| ANDS BICS | wx | 1 | 4 | Pipe 14 Or Pipe 25 |
| ANDS BICS | wx | 2 | 2 | Pipe 25 |
| EXTR | wx | 1 | 2 | Pipe 25 |
| SBFM UBFM BFM | wx | 1 | 2 | Pipe 25 |
| MOVN MOVK MOVZ | wx | 1 | 6 | All 6 pipes |
| CLS CLZ | wx | 1 | 2 | Pipe 25 |
| RBIT REV REV16 REV32 | wx | 1 | 2 | Pipe 25 |
| ASR LSL LSR ROR | wx | 1 | 2 | Pipe 25 |
| 583 | ALU2C |  |  |  |

##### 5.8.3.1 Instructions supported

2-cycle ALU without SVE instructions are mainly divided into two types 2-cycle au and 2-cycle lu These instructions are 2 cycles only if the shift number is not 0.
| 1 | 2-cycle au instructions mean that instructions are realized in arithmetic unit au with 2-cycle latency The au module does 32bit64bit shift and addition or subtraction arithmetic operations |  |  |  |  |  |
| --- | --- | --- | --- | --- | --- | --- |
| 2 | 2-cycle lu instructions mean that instructions are realized in logic unit lu with 2-cycle latency The lu module does 32bit64bit shift and the typical logic operation such as ANDBIC |  |  |  |  |  |
| A64 | inst | cond | size | LAT | BW | PIPE |
| AU | ADDSUBshift | The shift number is not 0 |  |  |  |  |
| wx | 2 | 2 | Alu25 |  |  |  |
| ADDSSUBSshift | wx | 2 | 2 | Alu25 |  |  |
| ADDSUBextr | wx | 2 | 2 | Alu25 |  |  |
| ADDSSUBSextr | wx | 2 | 2 | Alu25 |  |  |
| LU | ANDSBICSshift | wx | 2 | 2 | Alu25 |  |
| SVE | inst | cond | size | LAT | BW | PIPE |
| AU | USQINCBHWD | wx | 2 | 2 | Alu25 |  |
| USQDECBHWD | wx | 2 | 2 | Alu25 |  |  |

USQINCP
| USQDECP | 1 uop |
| --- | --- |

1 F2IADD
2 uops
1 F2I
| 2 ADD | wx | 2 | 2 | Alu25 |
| --- | --- | --- | --- | --- |
| I2P | WHLEGE WHILEGT WHILELT WHILELE |  |  |  |

WHILEHS WHILEHI
WHILELO WHILELS
| WHILEWR WHILERW | Dstp |  |  |
| --- | --- | --- | --- |
| Dstcc | 2 | 2 | Alu25 |

##### 5.8.3.2 Block diagram

| 1 | In the 2calu module the instructions are only shift by immediate |
| --- | --- |

## 2 Instructions of the extended register support only left shift Instructions of the shifted register support LSL LSR ASR and ROR

| 3 | See Chapter 9 for details about SVE |
| --- | --- |
| 584 | MAC |

##### 5.8.4.1 Instructions supported

| inst | size | uop | sgn | lat | BW | PIPE |
| --- | --- | --- | --- | --- | --- | --- |
| MADDMSUB | WX | 21 | unsgn | 2+1W3+1X | 2 | ALU25 |

USMADDL
| USMSUBL | W | 21 | get long | 2+1 | 2 | ALU25 |
| --- | --- | --- | --- | --- | --- | --- |
| USMULH | X | get high half | 3 | 2 | ALU25 |  |
| USMNEGL | W | 1 | 2 | 2 | ALU25 |  |
| USMULL | W | 1 | 2 | 2 | ALU25 |  |
| MNEG | WX | 1 | unsgn | 2W3X | 2 | ALU25 |
| MUL | WX | 1 | unsgn | 2W3X | 2 | ALU25 |

Because there are only two srcs in IEX the instruction that needs to sum the third srcsrc3 is not XZR is split into two uops The first uop is used for multiplication and the second uop is used for addition.

##### 5.8.4.2 Block diagram

Integer multiplication will be calculated by integer multiply functional units Integer multiplyadd is mainly consists of 2 parts 32-bit MAC MAC32 and 64-bit MAC MAC64.
The bandwidth of the MAC is 2 The latency of MAC32 is 2W1E2 and the latency of MAC64 is 3W1E3.
| 585 | CRC |
| --- | --- |

##### 5.8.5.1 Instructions supported

| LAT | BW | PIPE |  |  |
| --- | --- | --- | --- | --- |
| CRC32 | BH | 2 | 2 | ALU25 |
| CRC32C | BH | 2 | 2 | ALU25 |

##### 5.8.5.2 Algorithm

Bits32 acc X[n] accumulator.
Bitssize val X[m] input value.
Bits32 poly if crc32c then 0x1EDC6F41 else 0x04C11DB7<310>.
Bits32+size tempacc BitReverseacc Zerossize.
Bitssize+32 tempval BitReverseval Zeros32.

- Poly32Mod2 on a bitstring does a polynomial Modulus over 01 operation.

X[d] BitReversePoly32Mod2tempacc EOR tempval poly.
Take CRC32B as an example.

##### 5.8.5.3 Block diagram

| 586 | DIV |
| --- | --- |

There are 2 types of instructions division like SDIV and UDIV each type division instruction have 2 size like 32 and 64 so there are 4 division instructios in total.
| Instruction name | Size |
| --- | --- |
| SDIV | 32 |

64
| UDIV | 32 |
| --- | --- |

64
| w132 | w164 |  |  |  |  |  |  |
| --- | --- | --- | --- | --- | --- | --- | --- |
| E1 | E2 | E3 | E4 | E5 | E6 | E7 | E8 |

Shared with pipe 2 and 5.
| 587 | BRU |
| --- | --- |

There are 2 BRUs in current design Each BRU corresponds with an exclusive BRU ISSQ and BRU pipe BRU can execulte all branch uops and some specific BRU fusion uops For these BRU fusion uops refer to 81 for more detail.
BRU DP have one BRU flush module which is used to generate bru flush signal When bru flush from different thread happen in the same cycle BRU flush module need to arbitrate use round robin algorithm The Uop lost arbitration will goes to flush buffer Refer to 113 for more details about flush buffer.
BRU logic mainly works during I2 E1 E2 stages.
I2 stage
| 1 | Receive source data from bypass network and uop payload from bru pipe |
| --- | --- |
| 2 | Select payload for normal branch uop or fusion uop |
| 3 | Decode branch uop control signal |
| 4 | Calculate Target PC Inst PC and next PC |
| 5 | Generate immediate data and select immediate data for immtype AULU uops of branch fusion |
| 6 | Select payload from normal branch uop of fusion uop |
| 7 | Compare rid age between bru0 and bru1 and flush buff |

E1 stage
| 1 | Calculate CCPASS and generate branch prediction and bru flush result for normal branch uop |
| --- | --- |
| 2 | Calculate CC value of ALU uop and generate branch prediction and bru flush result for fusion branch uop |
| 3 | Write back CC result to register file for fusion branch uop |
| 4 | Output executed branch informations to IFU |
| 5 | Handle flush r3e2e3 |
| 6 | Select oldest flush request for each thread and arbitrate |
| 7 | Buff another flush request who loses the arbitration |

E2 stage
| 1 | Send resolve to OOO |
| --- | --- |
| 2 | Send bru flush to all other boxes |

E3 stage
| 1 | Send flushed sid and lid to LSU |
| --- | --- |

BRU execute unit logic.
Bru flush logic

### 5.9 Mov optimizationMOVE TO LD PIPE

#### 5.9.1 Mov reg2reg

The Move register instructions copy a value from a generalpurpose register to another generalpurpose register We can directly copy source ptag to dst atag entry in the SMAP for a regtoreg move instruction and no uop need to dispatch to IEX issue queue and execute.
The Move register instructions are aliases for other data processing instructions The optimization only covers one kind of aliases ORR in the arm V8 spec document.
This optimization is mainly done at ooo the impact to iex is that need to distinguish source is 32-bit or 64-bit when bypass or read source data from regfile.
Like the uop sequence.
ADD1 X1 X2 X3
MOV W2 W1
ADD2 X4 X5 X2
When the mov is optimized by ooo and not sent to iex then iex need to know the high 32-bit of ADD2 X2 is all zero So define a xfm field at issq packet for every source to indicate its source datasource is register not include pc and imm is 32-bit or 64-bit If xfm is 1 means source data is 64-bit.

#### 5.9.2 Movi

Immediate MOV optimization is that dispatch directly write the immediate data of the MOV to the regfile in the IEX.
At D2 stage
Detect the MOVI instruction and make a flag on the oldest one because dispatch only has one resolve port to the ROB in one cycle.
If the reg move is also detected at the same cycle the movi should give up optimization and dispatch it to ALU issue queue.
The adrp pc buffer only has one entry so before dispatch take the last adrp pc from the buffer the following movi instructions cannot be optimized.
At D3 stage
MOVI optimization can be done only when the enable signal from the IEX to OOO is valid or the MOVI optimization will be abort and also dispatch it to ALU issue queue.
If the MOVI optimization can be done rename should set dst ptag nospec ready bit in the smap and in the vtab beside allocating a ptag to the dst atag and the consumer should set ready bit for the younger uops.
In the dispatch the optimized MOVI will send a resolve to the ROB at S1 stage instead of allocating alu issq port and index.
Besides above operation the immediate data should generate from a 32-bit opcode and 49-bit pc as the figure below shows and flop to S1 stage and write into regfile at S2 stage through a special port.
The type of instruction need to optimize.
MOVN 3264
MOVZ 3264
ADR
ADRP

#### 5.1.0 Statistical Profiling Support Interface with SPU

##### 5.1.0.1 Background

The SPU unit is responsible for Arm V83 Statistical Profiling feature implementation Arm Statistical Profiling extension is a mechanism for profiling software and hardware using randomized sampling There are two main steps to implement this feature instruction uop sampling and writing profiling buffer figure below shows the main steps.

##### 5.1.0.2 Information Collection

The lifespan of the sampled uop is start from dtuspu sending sample trigger to ooodec end with it is committed or flushed Spu use rid to judge whether the sampled uop is committed or flushed If the sampled uop is committed then the captured record will be written to Profiling Buffer through SP mode The entire records of sampled uops is as below.
According to SPU spec [7] IEXSPU information is as following.
Issue end signal to SPU For all issued uops if they are set sampled tag issq packet then iex will send issue end signal to spu.
All alu and bru issue to spu are sure not cancel before E1 stage all ldasta issue to spu are sure not cancel before I2 stage.
For storestore pair only send sample issue for sta not send for std.
For bru fusion head uop will not be sampled IEX only use tail uop.
For movi IEX will not send sample issue.
Sample output at e1 stage will not be valid if meet flush r3e3 before e1 stage will be valid if e1 stage meet flush r3e3.
Resolve end signal to SPU Indicate the tagged uop has completed execution and no longer capable of stalling any instruction that consumes its output.
For MSR including L3 reflow IEX is responsible to give resolve info at E2.
Give the sampled resolve signal for DIVSYS uops at reflow E2 Including normal SYS DIV reflow L2 MRS reflow L3 MSRMRS reflow.
For uop sent to LSU LSU is responsible to give the resolve signal.
Will not be valid if meet flush r4e4 before e2 stage will be valid if e2 stage meet flush r4e4.
Insttype to SPU The type of tagged uop.
00xx unconditional intructions of other not branch instructions.
01xx conditional instructions of other not branch instructions such as CSEL CSINC CSINV CSNEG CCMP CCMN.
1x00 direct and unconditional branch B BL.
1x01 direct and conditional branch Bcond TBZ TBNZ CBZ CBNZ.
1x10 indirect and unconditional branch BR BLR RET include SVE.
1x11 indirect and conditional branch illegal.
Eventtype to SPU The event type of tagged uop.

00. predict hit taken.
01. predict hit not taken.
10. mispredicted taken.
11. mispredicted not taken.

For a conditional branch instruction not taken means the branch was not taken For a conditional select no taken means the second operand was written to the result For a condition compare not taken means the condition flags were set to the immediate value and not the result of the compare.
Branch target address branch target address of tagged uop.
It is only valid for branch inst.
Branch target address will be sent no matter miss predict or not.

#### 5.1.1 Performance Monitor

LC930 PMU supports all Arm defined pmu events and selfdefined pmu evenets All PMU events must be valid and the monitor should not sample the uncertain state when DTU is counting.
In this case DTU use an enable signal called pmueventen to inform IEX that DTU wants to sample the pmu event The protocol of the signal requires that when DTU assert an enable at q0 all events signal should not be uncertain state at q8 DTU will sample the pmu event result at q10.
In current feedthough policy IEX will receive pmueventen at q2 and flop this signal to q3 as all pmu events enable signal And IEX need to output the valid pmu event at q5 to ensure the event signal can reach DTU no later than q8 In other words all pmu activity signal must flop 2 cycles inside IEX q4 q5.
Some PMU events are not SMT shared in this case PMU need to use specific thread pmueventen to enable the counting.
Here are the pmu events list that are related to IEX.

##### 5.1.1.1 Arm defined events

| Event ID | Event Name | Description |
| --- | --- | --- |
| 0x0010 | BRMISPRED | Mispredicted or not predicted branch speculatively executed |
| 0x0012 | BRPRED | Predictable branch speculatively executed |
| 0x8073 | SVEPLOOPTERMSPEC | SVE predicate loop termination speculatively executed |
| 0x8016B | STALLBACKENDBUSY | Backend stall cycles backend busy |
| 0x8016C | STALLBACKENDILOCK | Backend stall cycles input dependency |

##### 5.1.1.2 Linx defined events

| Event ID | Event Name | Description |
| --- | --- | --- |
| 0x700a | IS0ISSUESERBLK | Zero issue with serializing block |
| 0x700b | IS0ISSUENOSERBLK | Zero issue without serializing block |
| 0x700c | IS1ISSUE | Integer scheduling 1 issue per cycle |
| 0x700d | IS2ISSUE | Integer scheduling 2 issues per cycle |
| 0x700e | IS3ISSUE | Integer scheduling 3 issues per cycle |
| 0x700f | IS4ISSUE | Integer scheduling 4 issues per cycle |
| 0x7010 | IS5ISSUE | Integer scheduling 5 issues per cycle |
| 0x7011 | IS6ISSUE | Integer scheduling 6 issues per cycle |
| 0x7012 | IS7ISSUE | Integer scheduling 7 issues per cycle |
| 0x7013 | ISGE8ISSUE | Integer scheduling no less than 8 issues per cycle |
| 0x7014 | ISALUCANCELLED | Integer scheduling alu uop cancelled |
| 0x7015 | ISAGUCANCELLED | Integer scheduling agu uop cancelled |
| 0x7016 | ISALUISSUED | Integer scheduling alu uop issued |
| 0x7017 | ISBRUISSUED | Integer scheduling bru uop issued |
| 0x7018 | ISLDISSUED | Integer scheduling load uop issued |
| 0x7019 | ISSTISSUED | Integer scheduling store uop issued |
| 0x701a | LSSMALLSHIFT | LS reg offset with small shift per thread |
| 0x701b | ISLT8ISSUECNT | Integer scheduling less than 8 issued count |

#### 5.1.2 Max power mitigation

##### 5.1.2.1 Background

MPMM maxpower mitigation mechanism is limit average power over medium timescale to maximize efficiency over wide variety of workloads MPMM designed for electrical safety on 1us timescale aim to do not go overcurrent to avoid damage or brownout 1 us timescale Expect that decoupling capacitance will source excess current for spikes of shorter duration.
When system is running MPMM can be sufficiently controlled by two level register values to tune for different power cases.
The flow is roughly divide to 3 stages detect stage judge stage execute stage.
Detect stage monitor FSULSU Accumulate count of high activity events over training window to get activity counter.
Judge stage To compare activity counter with preset threshold counter in each 128 cycle both counter threshold and sample period is configurable.
Execute stage when mitigation triggered according to throttle valueIFULSUFSU will reduce activity by stall or disable high power consumption feature.

##### 5.1.2.2 IEX Block diagram

When maxpowerlimitvld from LSU is enable IEX will use a timer inside IEX and maxpowerlimitlevel signal which is also from LSU to judge whether IEX need to reduce activity or not When mitigation triggered IEX will set cpmdropenx3 to block all ISSQ issue any uops to pipeline.
The timer will count from 0 to 15 when the limit vld signal enable When the counter reaches a certain value IEX will trigger max power mitigation according to current limit level The limit level may change when limit vld enable.
IEX will output four maxpower hint event to LSU LSU will judge next limit level according to current hint event The hint event monitors the sum of pipe vld i1 in this cycle When sum is over 1 event0 will be one When sum is over 3 event1 will be one When sum is over 5 event2 will be one When sum is over 7 event3 will be one.
| Maxpower limit level | Trigger mitigation when counter value |
| --- | --- |
| 2b00 | [redacted numeric sequence] |
| 2b01 | [redacted numeric sequence] |
| 2b10 | [redacted numeric sequence] |
| 2b11 | [redacted numeric sequence] |
