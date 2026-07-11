# LSU Structures and Instruction Requirements

This document covers issue pipes, data cache, translation, memory-order structures, queues, store queues, special system instructions, barriers, address translation, cache maintenance, acquire/release, exclusives, and non-temporal behavior.

> This Markdown document is the maintained specification. Sensitive metadata and internal reference labels have been removed while preserving architecture semantics.

### 4.1 Issue Pipe I1 I2 E1AGU

#### 4.1.1 Load Issue Pipe

Per load pipe LoadC replicating the load issue pipe which include I1 I2 AGUAGEN address Generation pipe stages Table describes the issue pipe tasks Issue Pipe is also tightly coupled to IssQ.
| Pick | I1 | I2 | E1 |
| --- | --- | --- | --- |
| TLB Arbiter for LD0 | Result Pipe Arbiter | Skid Buffer |  |
| Register Read | TAG Arbiter |  |  |

VA[130]
| Access TAG | Store Pipe AGUAGEN |
| --- | --- |

Adress Generation for VA[4714]
WR to Skid buffer if lose Arbiter.
Send Wakeup based on winner of I2 Arbitration.
MA Detection

##### 4.1.1.1 Replicating Store AGU to Load pipes

The Store AGU address gen unit is replicated in the LoadC as local entities This helps with Floorplan constrains as the Store Issue logic replication is placed near the LoadC.

#### 4.1.2 Store Issue Pipe

Table describes the issue pipe tasks Issue Pipe is also tightly coupled to IssQ.
| Pick | I1 | I2 | E1 |
| --- | --- | --- | --- |

TLB Arbiter for ST0.
| Register Read | VA[130] |
| --- | --- |

Store Pipe AGUAGEN.
Adress Generation for VA[4714]
MA detection

#### 4.1.3 The TLB Arbiter I1

The L1 LD TLB has a LD TLB Arbiter per load pipe By default this port is used by instructions from the IEX issue pipe Per pipe the IEX issue pipe arbitrate between the Issue and the LIQ Reissue request tlb miss vSTQ blocking or cross page translation When LSU needs to use the TLB ports for a different purpose such as TLBI crosspage load/store translation or hardware prefetcher translation this is called a TLB sidedoor access.
The TLB Arbiter is done in I1 pipe stage allowing TLB Sidedoor to gain access to TLB Since sidedoor only needs to access 1 copy for LD and ST tlb it is strictly allocated to LD0 and ST0 pipes.
The TLB arbiter is used by IssueReissue and TLB Sidedoor TLBITLB prefetch Priority is TLBI>reissue >TLB prefetch>Issue When IssueReissue lose arbitration the LSU informs IEX to reject and reissue the load/store instruction that conflicted with the sidedoor access.
The TLB prefetch need flow control The sidedoor request always has higher priority than the IEX issue request It might hit some corner case keep sendings TLB prefetch blocks demanding request.

#### 4.1.4 LD I2 Arbiters

Result pipe Arbiter and Load TAG Arbiter are I2 structures They are further covered in L1 Data Cache DC chapter 423 and chapter 424.
Arbiters are replicated per LoadC alongside the entire issue pipe.

#### 4.1.5 SBI2 Skid Buffer I2

The Skid Buffer I2 SBI2 is an I2 structures Only used on Loads.
The SBI2 is replicated per LoadC alongside the entire issue pipe.
The SBI2 purpose is to avoid repick via LIQ It eliminates when successful the latency of LIQ repick and the the writing of a uop to the LIQ in the cases where the issued LD loses in Result Pipe Arbiter and TAG Arbiter Loss at I2.
The Skid buffer is constructed as 3 pipe stages SB0 SB1 SB2 representing 3 opportunities to regain access to E1 Execution stage If SBI2 wins pipe it generates Wakeup on E1 and eliminates the E3 Cancel and the write to LIQ for Repick.
The I2 Arbitration loss skid buffer is defined as follows.
The SBI2 is an E1 to I2 bypass Where.

- I2 Arbitration loss is detected in I2 TAG Arbiter Result Pipe Arbiter uOp is captured in SBI2 stage 0 SB0 at end of I2.
- At next cycle the uOp attempts to regain access to E1 by reaccessing the I2 Arbiters.

Refer to I2 Arbiter The SBI2 has lower priority in I2 Arbiters than IEX Issue.
Skid buffer is constructed from 3 pipe Stages SB0 SB1 and SB2 Prioritize SB2>SB1> SB0 when more than 1 uOp is allocated in SBI2.

#### 4.1.6 Load Mis Align Detection

At I2 MA is checked and identified.
| Name | Ca |
| --- | --- |
| Cross32B0 | size24 va30>8 |
| Cross32B1 | size32 va300 |
| Cross64B | size+va >64 |

At I2 stage LSU detects whether an issued load instruction has a misaligned address.
Misaligned load uops consist of two components The MA1 component represents the bytes before crossing the 32B or 64B boundary and the MA2 component represents the bytes after crossing the 32B64B boundary The LIQ entry handles both MA1 and MA2 components of a misaligned load uop To support this each LIQ entry contains the following address fields.
PAPAGE[4812] for the aligned or MA1 component also covers samepage misaligned MA2.
VAINDEX1[130] for the aligned or MA1 component.
VAINDEX2[130] for the MA2 component.
Missing special misalign description of DEV load for Dev load cross 32B boundary or element size align but total size not aligned dev load is treated as MGW Its split by the element size and send out the request onebyone.
Load CrossPage Detection.
At E1 stage LSU detects whether an IEX issued load instruction has a crosspage XP misaligned address XP load instructions must perform separate TLB translations for the MA1 and MA2 pages Refer to CrossPage Load Translation Flow for details.
The cross page is always cross 4K boundary Not related to actual page size.

#### 4.1.7 Store Mis Align Detection

At I2 stage LSU detects whether an issued STA instruction has a misaligned address.
Misaligned STA uops consist of two components The MA1 component represents the bytes before crossing the 16B boundary and the MA2 component represents the bytes after crossing the 16B boundary For nonXP cases the issued STA is written to STQ at E2 stage with its misaligned type Each STQ entry contains one PAPAGE1[4812] field and two VAINDEX[130] fields VAINDEX1 corresponds to the aligned or MA1 component of the STA uop and VAINDEX2 corresponds to the MA2 component Similarly each STQ entry contains two BYTEMASK[150] fields one for MA1 and one for MA2.
For crosspage XP cases the STA instruction must perform separate TLB translations for the MA1 and MA2 pages Each STQ entry has the PAPAGE1[4812] field for the aligned or MA1 component of the STA uop but there is only a single copy of the PAPAGE2[4812] field for the MA2 component To avoid contention a STA uop can only use the PAPAGE2 field when its SID becomes the oldest SID in the STQ sliding window XP STA instructions must wait in STQ until this condition becomes true The STQ then uses the reissue mechanism as described above in the section on Store TLB Translation.

##### 4.1.7.1 Store CrossPage Detection

At E1 stage LSU detects whether an issued STA instruction has a crosspage XP misaligned address based on the page size from TLB translation XP load instructions must perform separate TLB translations for the MA1 and MA2 pages Refer to Error Reference source not found for details.

#### 4.1.8 Load Cancel

The Load pipe returns Cancel to IEX.

#### Figure 4 1 Basic Load Flow

At E3 stage of the load result pipe if the load cannot return its result data to IEXFSU then LSU must cancel the load Refer to Load Sleep for a summary of hazard cases that prevent a load from returning its result data.
Sending cancel to IEXFSU is necessary since the issue queues already speculatively woke up dependents of the load destination tag at E1 stage of the result pipe At E4 stage the issue queues are responsible for clearing the source ready bits for source registers dependent on the canceled load For dependent instructions that already issued the load result cancel propagates through the bypass network in parallel with the result data Dependent instructions receive the cancel attached to their source data and must cancel themselves.

#### Figure 4 2 Cancel Flow

There are two types of cancel internal for pipe and interface for IEXFSU.
New cancel

- SBI2 blocking cancel when skid buffer succeed.
- LHQ sliding window check.
- Snoop cancel

### 4.2 L1 Data Cache DC

The L1 Data Cache DC is 64 KB with 64B cachelines2 bank 4way set associative virtually indexed physically tagged writeback write allocate on write miss LRU replacement policy MESI coherency states inclusive in L2 Cache L2C and ECC protected It is composed of TAG STATE and DATA arrays.
The following sections describe the detailed structure of each component in the DC block.

#### 4.2.1 Load TAG

Because of the false hit or multihit in the hashed partial tag another scheme is needed to further confirm whether the partial tag hits are valid or not or which way is really needed in multihitting These are achieved by load tag.
In Linx930 LSU each load pipe has a load tag memory LD tag is a full PA tag which is updated by PA[4012] when the cache line is filled to DC The LD tag is divided into two banks by VA[6] and each bank contains two pieces of 1RW memory that saves the PA tag corresponding to way01 and way23 respectively Each piece of memory is 128depth accessed by VA[137] and the data width is 29+72 which contains 29-bit tag and 7-bit ECC of two ways.
The LD tag top diagram for one load pipe is shown in the following figure.
Because of the single read and write port of the LD tag memory 1RW memory it requires to handle read and write conflicts When the conflict occurs the write operation have an absolute high priority and a cancel signal will be sent to the read operator to tell this is a failed load operation.
DISCUSS For better performance it needs to reduce the load failures caused by write operations Access conflicts can be reduced to some extent by using the bank division method described above which enables to read from one band and write to other bank Some other conflict solutions are listed below.
Use a systolic write bus with several opportunities to write LD tag memory within several consecutive cycles when the conflict occurs The load cancel signal only be generated at the end of the systolic write bus where the write operation gets an absolute high priority.
If read and write conflict occurs on one bank the load still read the other two ways in the other bank If load hit in the other two ways with a partial tag hit the same way it will be hit and resolved If miss in these two ways and the fill write way hit partial tag the load is sent to LIQ for repick without sending the request to L2 If miss in these two ways and the fill write way also miss in the partial tag send the request to L2.
LD tag summary
| Way | 4 | way0way3 |
| --- | --- | --- |
| Memory blocks | 12 | 3pipe2bank4way2 |
| Entries per memory | 128 | Index VA[137] |
| Entry width | 72 bits | PA[4012] + ECC[60]2 |
| Ports | 1RW | Each memory block support 1RW Two banks can be handled at the same time by a read and a write |
| Mem size | 72128 bits |  |
| TOTAL size | 110592 bits | 7212812 |

NOTE
If PTag miss the load request is sent to L2 directly without checking the LD tag hit information.
After PTag lookup the DC state array these valid state for each way will also be used by LD tag to qualify the raw hit vector to generate the final LD tag hit or miss information to decide whether it needs to send a miss request to L2C.

#### 4.2.2 Tag access pipeline

#### 4.2.3 Load TAG Arbiter

The LSU LD TAGCT uses SRAM as Load TAG and requires to handle Read and Write conflicts Per Local Load TAG per load pipe a TAG Arbiter arbitrates local competition for TAG Access.

#### Figure 4 5 Load TAG Arbiter

This allows a load uop and a fill to read and write different physical banks in the same array at the same cycle.
If the read and fill hit the same Bank PA[6] and the fill has higher priority the read uOp should be cancelled and Repicked.
At I2 stage of the load issue pipe the load sends its request to the DC TAG arbiter Similarly at I2 stage of the LIQ repick Reissue pipe the load sends its request to the DC TAG arbiter The LIQ for each entry keeps track of whether its load needs to check the DC TAG If so at I2 stage of the LIQ repick pipe the load sends its request to the DC TAG arbiter The DC TAG arbiter selects a request based on the priorities shown in Result Pipe Arbiter.
When a load does not win the DC TAG arbiter it must be allocated to LIQ as immediately ready to repick so it can attempt its DC TAG check again In parallel the load is captured in skid buffer I2 if coming from issue pipe not from LIQ and attempts to reaccess the load pipe E1 prior to uop being written to LIQ If successful then LIQ does not have to capture load and will not repick.
The LIQ entry tracks this situation with 4 state bits as shown in the table below Each LIQ entry has separate copies of these bits for the MA1 and MA2 components of the load On LIQ repick dcspec1 indicates the load must check the DC TAG.
LMQ Entry State Bits.
| dcspec | dchit | dchitway[10] | Description |
| --- | --- | --- | --- |
| 0 | 0 | Real miss DC TAG miss |  |
| 0 | 1 | hitway | Real hit DC TAG hit with specified hitway |
| 1 | X | Unknown hitmiss DC TAG not checked DC TAG check required on LIQ repick |  |

#### 4.2.4 STATE Array

In 930 the State Array FF based is partitioned differently between loads and stores to service its two purposes.
State Valid for Loads.
Full State Array MESI for Stores.
The Stores require full same as 910 State functionality Shared between the ST Pipes.

##### 4.2.4.1 Loads STATE Array

For Loads only Valid bit SetReset is needed per TAG entry State Valid structure is Replicated Per pipe LoadC as part of the TAG and the State Valid is FF based.

3. pipe can read state array at at same time at e1 stage.

Snp c7fill c8acke dropc9 write DC STATE array to update tag valid informationwhich will not update the STATE aaray at the same time.

##### 4.2.4.2 Store STATUS Array

Store Pipe StoreC holds a flopbased DC STATUS array with 3-bit entry corresponding to each cacheline in the L1 TAGData Cache The entry format consists of the 2-bit MESI state and a P bit to indicate the cacheline was filled by a HWP request and a PFTYP bit to indicate the HWP request is generated by NL or MOP When a load/store demand request accesses a cacheline with P1 an indication is sent to the HWP logic and the P bit is cleared The following table shows the structure of the DC STATE MESI array.
The 2-bit MESI state array is called DCSTATE And the 1-bit PPF state array is called DCADP They have the same readwrite ports however to improve timing both of the read and write enble of the DCADP is 1 cycle behind the DCSTATUS.
| DC STATE | Comment |  |
| --- | --- | --- |
| Banks | 4 | banked on way[30] |
| Entries per bank | 256 | index va[136] |
| Entry width | 2 bits | state[10] state encoding 00Invalid 01Shared 10Exclusive 11Modified |
| Ports per bank | 2R 4W | RD DC TAG tagc read arbiter |

RD copyback snoop E or M.
WR store DC write E to M.
WR STUPG request ACKE E to M.
WR fill S or E
WR snoop EM to S EM to I S to I.
| Bank size | 512 bits |
| --- | --- |
| TOTAL SIZE | 2048 bits |

#### 4.2.5 P bit Array

The P bit Array is used indicate if a CL was Demanded or Pre fetched Updated by loads and stores The p bit arrayadp array provides information to hwpf.
WR change p bit on demand load/store P1.
| DC ADP | Comment |  |
| --- | --- | --- |
| Banks | 4 | banked on way[30] |
| Entries per bank | 256 | index va[136] |
| Entry width | 1bits | PF |
| Ports per bank | 4R 5W | RD hwp p bit3 ld tagst tag l2 l1v ramidxst tag |

WR demand 3 load 1 store P1 to P0 fill P>1.
| Bank size | 256 bits |
| --- | --- |
| TOTAL SIZE | 1024 bits |

The P bit Array has 1 bit P bit.
P 1 Prefetch line 0 demanding line.
The P bit is set by the fill and the fill is generated by HWP And in L1 it need to be cleared to 0 when the demanding request access this cache line.
In L1V the P bit is also read out from P bit array If the P is still 1 its a bad prefetch and it updates the bad prefetch counter +1.
They are used for the adaptive prefetch.

#### 4.2.6 DATA Cache Array aka DC

The L1D is divided into 8 slices by VA[53] and each slice is divided into 8 banks by VA[86] The memory in each bank is implemented by singleported 1RW memory with ECC protection Therefore the L1D memory totally includes 64 physical RAM macros and each has 128 entries and 64-bit data + 8-bit ECC width for a total of 64KB of storage The following Figure shows the memory macro organization.

#### 4.2.7 DC Access Arbitration

The flowing Figure shows the arbiter diagram.
Linx930 LSU has three load pipes pipe0 pipe2 to handle the issued and repicked load requests and in each pipe the repick load has a higher priority than the issued load L1D also supports other read and write requests from Mbist Ram index L2C snoop L2C fill and SDB The arbiter needs to handle all the above requests nonconflicting access for the physical banks.
The random arbitration is used among different pipes requests if bank conflict occurs.
The Mbist read and ramindex share the load pipe0 request channel The snoop shares the load pipe2 request channel The Mbist write and fill are handled in a separate write channel.
The requests and the arbitration priority in each channel are listed below.
Pipe0 Mbist read > Ramidx > Liq0 > Lda0.
Pipe1 Liq1 > Lda1
Pipe2 Snoop > Liq2 > Lda2.
Write Mbist write > fill.
In addition the SDB also can initiate read or write request to memory SDB requests carry the starve attribute which is used to change the arbitration priority with other requests.
If two or more requests from different channels go to the same bank a global priority order is set as shown below.
Mbist > fillsnp > Ramidx > Starved SDB readwrite > Liq load > issue load > nostarve sdb readwrite.
After the arbitration only one request is valid on bank access port.
NOTE
The arbitration logic performs arbitration among the bank conflicting requests and provides slice enable bank enable index and hit way for who wins the arbitration to access the data bank.
When two loads has the same address both loads can be serviced and get data The data is used by both loads with no bank conflict.
The SDB in each bank may need to merge data with the memory that in the same bank and ECC correction is needed for the memory data on the merge path To reduce the ECC correct logic and routing overhead only two merge path is set each serving four banks SDB with an RR arbitration.
Snoop needs to read DC only when the accessed cache line is dirty.
Open item Currently there are two versions of RTL code Version one implements all the arbitration logic in E1 stage and simplifies the logic in E2 stage to set more timing margin for the memory input logic However this causes timing issues at E1 stage as well as the performance degradation because the SDB is occupied for a longer time Version 2 implements arbitration in both E1 and E2 stages E1 stage implements the bank conflict arbitration in the pipe0 pipe1 pipe2 and Write channel separately E2 stage implements the arbitration between SDB and the other channels Version 2 has a better timing at E1 stage and can release SDB more quickly but the timing margin for the memory input may be small which is unfavorable to later frequency increase.
NOTE The following description is based on the Version 2 with the arbitration in both E1 and E2 stages.

#### 4.2.8 DC slice

The L1 data cache system includes 8 data slice indexed by VA[53] and each slice composed of 8 banks indexed by VA[86] The slice diagram is shown below.
Each bank includes a peace of memory and an SDB.
The memory is accessed by Way+Index so the tag hit information is needed before memory accessing Linx930 LSU includes a latch based partial tag Ptag array that is accessed before the data access to get the hit way.
The SDB is dedicated to receiving write data from SCB If the data in SDB has a full byte mask SDB triggers a write request to the memory to push the data into memory and then release the SDB to free If the data in SDB only has a partial byte mask SDB will trigger a read request to the memory and then merge the read data into SDB to form a full byte mask data and then write it into memory.
The slice receives arbitrated requests from the access channel pipe0 pipe1 pipe2 and Write and dispatch the requests to each bank In each bank the input requests are arbitrated again with the SDB request and then send to the memory port and SDB port If an available data with full byte mask is hit in SDB by a read request the bank output data only comes from the SDB If an available data with partial byte mask is hit in SDB the bank output data partly comes from the SDB and memory based on the SDB byte mask The bank output data is sent to the pipe from which the read request is received.

#### 4.2.9 DC access pipeline

Linx930 LSU has a partial tag filled with VA[2114]TID VA[2822] to provide hit way information for the three load pipe request before access data bank The Ptag array is checked at E1 stage and there is a timing critical path from the tag array output to the data memory input Therefore Linx910 LSU extends E1 stage as 15 cycle on the memory input path to meet the setup time To achieve this the lowenable latch is introduced on the memory input side to lock the bank access signal during the high clock of E2 stage E2H The memory read and write are triggered on the falling edge of the clock from E2H to E2L stage In order to smoothly transition the memory output data to the rising edge triggered flops in the E4 stage the highenable latch is introduced on the memory output side to lock the output data during the low clock of E3 stage E3L.
The following figure shows the bank access pipeline.

##### 4.2.1.0 SDB Store Data Buffer

###### 42101 Overview

SDB is introduced to Linx930 as an extension of DC to accept the write data from SCB and then simplify the readmodifywrite logic between the SCB and data memory When the SDB is free SCB can deallocate by write the data to SDB.
SDB can trigger read or write requests and participates in the arbitration with other requests to access memory.
The SDB payload
| Payload field | Bit width | Description |
| --- | --- | --- |
| Data | 64 | Data from SCB |
| Index | 5 | Data Index from SCB |
| Way | 2 | Data Way from SCB |
| BMorECC | 8 | Reused by ECC code and byte mask |

When byte mask is full this field holds ECC encode of SDB data.
When byte mask is partial this field holds byte mask.
The read and write requests from SDB can be starved or normal and the arbitration priority see the DC access arbitration section.
Because the memory only accepts 8B data with a full byte mask if data with a partial byte mask from SCB write to SDB it initiate a readmodifywrite process to merge data with memory That is SDB triggers a read request to memory and then merges the SDB data with memory output data to generate a data with full byte mask and then write the merge data and ECC encode back to SDB After that SDB will triggers a write request to write memory If the data written to SDB by SCB has a full byte mask SDB can write the data to memory directly.
As an extension of DC SDB needs to support data supply for read requests If a read request hit an available SDB the data bytes with valid byte mask bits should be fed to the read request correctly rather than to get data from memory Specially if a snoop copy back invalid request hit the SDB the SDB data needs to be sent to snoop request and change the SDB to free status.
NOTE
Each bank includes an SDB and a piece of memory and each SDB has its own control FSM.
Not support the SCB write data merging with the SDB data.
SDB has two input data one is from SCB and the other is from RMW Each input data has its own ECC encode logic The BMorECC filed holds byte mask only when a partial SCB write to SDB.
SCB write the data to SDB only when SCB hit L1 No need to add SDB status in tag state array.
SCB has two write ports to DC Port0 write lower half line to SDBs in slice0slice3 Port1 write higher half line to SDBs in slice4slice7.
Each write port has a starve signal that is broadcasted to all the SDBs in its corresponding written slices.
To reduce the ECC correct logic on the RMW path only two RMW paths are set in each slice bank0bank3 share one RMW path and bank4bank7 share the other RMW path Only two ECC correct logic are needed in each slice On each RMW path the read requests from four SDBs are arbitrated with RR.

###### 42102 SDB FSM

Each SDB has its own control FSM with four states totally SDBFREE SDBREADYRD SDBWAITRD SDBREADYWR.
SDBFREE SDB entry is empty SDB data has been written into DC or been invalidated by snoop operation It is also the SDB reset state.
SDBREADYRD the SDB is filled with a partial SCB data and the RMW has not been executed yet The byte mask is not 8hFF In this state SDB will trigger a memory read request to initiate an RMW process.
SDBWAITRD SDB has already sent a read request to memory for the RMW process SDB is waiting the read grant and the completion of the merge operation.
SDBREADYWR SDB is filled with a full SCB data or a partial SCB data but the RMW has been executed The byte mask is 8hFF In this state SDB will trigger a memory write request to write data to memory and then release the SDB to free status.
The state transition is shown in the following Figure.
The following table lists the state transition conditions.
| status | Operation |
| --- | --- |
| sdbfreetoreadyrd | scb wr sdb and byemask 8hFF |
| sdbfreetoreadywr | scb wr sdb and byemask 8hFF |
| sdbreadyrdtowaitrd | sdb send rd request |
| sdbwaitrdtoreadywr | sdb rd gnt |
| sdbreadyrdtofree | snpci match sdb |
| sdbwaitrdtofree | snpci match sdb |
| sdbreadywrtofree | snpci match sdb or sdb wr request grant |

###### 42103 SDB pipeline

Like the DC memory does the lowenable latch is introduced to lock the SDB input signal from the SCB write port and the SDB write are also triggered on the falling edge of the clock from E2H to E2L stage as well as the transition of the SDB FSM.
DISCUSS If SDB triggered by raising edge it will have half cycle path from the SDB to memory input and from the SDB to falling edge flops on the RMW path which are timing critical In addition it will have a path from the raising edge flops to high enable latch on the SDB output to load data path All of the above issues will bring additional resources and timing penalties However if SDB triggered by falling edge we will have half cycle path from the SCB raising edge flops to the SDB which lead us to add lowenable latch on the SDB write port to solve the timing issues After a tradeoff we chose the falling edge SDB design.
A full byte mask SCB data write to SDB and then write to memory.
E1H
SCB create a full write.
Generate SCB ack bypass.
E1L
Latch SCB write request.
E2H
Flopped and send SCB ack SDB not free.
E2L and E3H
SCB ack not free
Data write into SDB.
SDB initiate a memory write request.
Generate write grant valid.
E3L
SCB ack not free
SDB FSM free
E4H
SCB ack free
SDB FSM free
A partial byte mask SCB data write to SDB and then initiate a RMW process.
E1H
SCB create a partial write.
Generate SCB ack bypass.
E1L
Latch SCB write request.
E2H
Flopped and send SCB ack SDB not free.
E2L E3H
SCB ack not free
Data write into SDB.
SDB initiate a memory read request.
Generate read grant valid.
E3L E4H E4L E5H
SCB ack not free
Wait read grant
E5L E6H
SCB ack not free
Merged data write back to SDB.
E6L E7H
SCB ack not free
SDB initiate a memory write request.
Generate write grant valid.
E7L
SCB ack not free
SDB FSM free
E8H
SCB ack free
SDB FSM free

### 4.3 TLB Translation Lookaside Buffer

The TLB is a latchbased Translation Lookaside Buffer TLB shared by two threads which provides virtual address VA to physical address PA translations and fault detection for load/store instructions.
The TLB support 8 kinds of pagesizes 4K16K64K256K2M32M512M1G.
In 930Its a 2 level TLB structure It contains the following structure.
UTLB LD micro TLB for the 3 Load Pipe Fully associative latch based 1 entries Support 4 kinds of page size4K16K64K2M.
UTLB ST micro TLB for the 2 Store Pipe Fully associative latch based 16 entries Support 4 kinds of page size4K16K64K2M.
MTLB Arbiter Arbitrate the uTLB miss request TLBI request to mTLBtlbi> stoldest>lda0tlbpflda1ld2>st0tlbpfst1.
MTLB Main TLB shared by load/store Fully associative latch based 128 entries Support all kinds of page size according to the granularity size.
MRB TLB Miss Request Buffer 4 entry to support up to 4 outstanding TLB miss request.

#### 4.3.1 Decoupled StoreLoad UTLB and MTLB

UTLB LD micro TLB for the 3 Load Pipe Fully associative latch based 1 entries Support 4 kinds of page size4K16K64K2M.
UTLB ST micro TLB for the 2 Store Pipe Fully associative latch based 16 entries Support 4 kinds of page size4K16K64K2M.
MTLB Main TLB shared by load/store Fully associative latch based 128 entries Support all kinds of page size according to the granularity size.
In 930 the Store and Load UTLB functionality are decoupled as follows.
Seperating the Store TLB and Load TLBs Store TLB may hold different pages with Load TLB and vice versa Load TLB and Store TLB are decoupled different content replacement policy Size.
TLB Arbiter for loads and Stores are separated Refer to chapter 412.
Separating ST and LD TLB accesses to MMU Maintaining single MMU port and adding simple arbitration logic between Store and Load TLB pipes in MRB Adding a 2-bit to MRB interface which indicate if to write to LD TLB or ST TLB or Both.
The HWPF supports TLB PF Due to decoupling the HWPF accesses different TLBs see TLB Arbiter for store TLB PF and load TLB PF.

#### 4.3.2 Load UTLB

Load UTLB is a 16-entry latchbased Translation Lookaside Buffer TLB shared by two threads three load pipes which provides virtual address VA to physical address PA translations and fault detection for load instructions.
The TLB structure consists of two components an Adder CAM and RAM The load VA is compared against each entry VA in the CAM component then the final PA is read out of the RAM component of the matching entry The following tables show the structure and entry format of the TLB.
| TLB | Comment |  |
| --- | --- | --- |
| Entries | 16 |  |
| Entry width | 101 bits | 51b Adder CAM entry + 50b RAM entry 101bits |
| Ports | 3R 1W 3C | RD E1 IEX lda0 issueE1 IEX lda1 issueE1 IEX lda2 issue |

WR TLB fill Mtlb update.
CAM E1 IEX lda0 issueE1 IEX lda1 issueE1 IEX lda2 issue.
| TOTAL SIZE | 1616 bits |
| --- | --- |

##### 4.3.2.1 Load UTLB Translation

An issued load instruction CAMs the TLB at E1 stage for virtual address VA to physical address PA translation The TLB provides the hit or miss result at the end of E1 stage On a TLB hit the TLB provides the translated PA memory attributes shareability and page size On a TLB miss the TLB attempts to arbitrate to access mtlb.

##### 4.3.2.2 Load Fault Detection

The TLB is responsible for detecting load faults at E1 and E2 stage The TLB provides the fault valid bit at the end of E2 stage The load result pipe attaches the fault valid bit to the load resolve sent to ROB and the ROB writes this fault valid bit to the ROB entry The TLB maintains the fault attributes for the oldest faulting instruction in its fault buffer When the ROB commit logic tries to commit a ROB entry with an LSU instruction fault it uses the fault attributes provided by the TLB to generate an exception and update the architectural state.

#### 4.3.3 Store UTLB

The Store UTLB is a 16-entry latchbased Translation Lookaside Buffer TLB shared by two threads two store pipes which provides virtual address VA to physical address PA translations and fault detection for load/store instructions.
Store is smaller than LD TLB Shared by two Store pipes.
| TLB | Comment |  |
| --- | --- | --- |
| Entries | 16 |  |
| Entry width | 101 bits | 51b Adder CAM entry + 50b RAM entry 101bits |
| Ports | 2R 1W 2C | RD E1 IEX sta0 issue |

RD E1 IEX sta1 issue.
WR ST TLB fill
CAM E1 IEX sta0 issue.
CAM E1 IEX sta1 issue.
| TOTAL SIZE | 1616 bits |
| --- | --- |

The ST TLB is single entity with 2 RD ports servicing two store pipes Separate Fill pipe from Load UTLB.

##### 4.3.3.1 Store TLB Translation

An issued STA instruction CAMs the TLB at E1 stage for virtual address VA to physical address PA translation The TLB provides the hit or miss result at the end of E1 stage On a TLB hit the TLB provides the translated PA memory attributes shareability and page size On a TLB miss the TLB attempts to arbitrate to access mtlb.

##### 4.3.3.2 Store Fault Detection

The TLB is responsible for detecting STA faults at E1 and E2 stage The TLB provides the fault valid bit at the end of E2 stage The store AGEN pipe attaches the fault valid bit to the AGEN resolve sent to ROB and the ROB writes this fault valid bit to the ROB entry The TLB maintains the fault attributes for the oldest faulting instruction in its fault buffer When the ROB commit logic tries to commit a ROB entry with an LSU instruction fault it uses the fault attributes provided by the TLB it generates an exception and update the architectural state.

#### 4.3.4 Main TLB

In R3 the TLB subsystem is a 2 level structure The uTLB is the level 0 TLB mTLB is the level 1 TLB The 2 level structure has a good balance between the capacity and power.
In order to support all kinds of page sizes the mTLB use fully associative latch base128 entry shared by LoadStoreaccording to the granularity size.
MTLB has 1 read port and 1 write port.
| Entries | 128 |  |
| --- | --- | --- |
| Entry width | 101 bits | 51b Adder CAM entry + 50b RAM entry 101bits |
| Ports | 1R 1W 1C | RD E1 ldst utlb miss |

WR TLB fill
CAM ldst utlb miss.
| TOTAL SIZE | 12928 bits |
| --- | --- |

##### 4.3.4.1 mTLB arbiter

The 5 issue pipe 3 LD+2 ST is possible to generated up to 5 issuereissue uTLB miss request And the TLBI request are also attend the mTLB arbiter Its 6 request in total and arbitrate just for 1 mTLB read ports The access priority.
1readportTLBI> 3ld2st.
1writeportMMU fill.

#### 4.3.5 TLB Faults

All fault types are supported in both Load and Store UTLBs and MTLB Every pipe three load pipes two store pipes has its own fault detection.
The other TLB faults are for specific instruction such as stxr atomic cmo These TLB Faults have special rule for fault checking The special instructions are only issued in ST pipe.

### 4.4 MOB Memory Order Buffer

This chapter is only a reference to the term MOB In 910 the MOB was responsible to enforce order.
In 920 the MOB task to enforce order between loads and stores is done by LocalLHQ.
In 920 the MOB task to report Resolve on multi uOp instructions is done by the OOOLD Matrix.

### 4.5 MDB Memory Disambiguation Buffer

LSU has a flopbased Memory Disambiguation Buffer MDB shared by 2 threads which tracks loads that resolved returned data ahead of older stores with same address and had to be flushed Each entry corresponds to one load that had to be flushed and contains pointers to up to 3 older stores that caused the flush The MDB is Indexing by the load hash PC[120] Each load checks for valid store pointers in MDB and then waits to execute until those stores generate their addresses The following tables show the structure and entry format of the MDB.
The MDB in 920 provides the following features compared to 910.
| Feature | Description |
| --- | --- |
| 64 ent fullyassociative | 1way64set |
| 3 delta 1mega | Each entry record 3 delta after reach 3 delta use mega to let load waiting for all older stores |
| Support SMT | Shared in SMT |
| Use hash PC | Hash PC Indexing PC[120] |

The following tables show the structure and entry format of the MDB.
| MDB | Comment |  |
| --- | --- | --- |
| Entries | 64 entry | HASHPC[120] indexing |
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
| Ports | 3R 1W | RD E1 pipe0 load |
| --- | --- | --- |

RD E1 pipe1 load
RD E1 pipe2 load
WR R6 store load nuke.
| TOTAL SIZE | 4280 bits |
| --- | --- |

#### 4.5.1 Load MDB Lookup

In 910 The load MDB lookup is valid for both issuereissuerepick loads.
In target implementation only issue load lookup MDB reissuerepack loads lookup SNR see 122.
At E1 stage of the load result pipe the load uses its hashpc[120] as the index to lookup the 64-entry MDB It reads out 3 sets of deltavld delta deltastpc fields plus a megavld bit If any of the deltavld bits are valid and deltastpc is same as the stpc stored in MDBSTPCsee 122 the access is considered an MDB hit If the megavld is valid the access is also considered an MDB hit.
An MDB hit indicates that the load has been nuke flushed by a store in the past so it must wait for one or more SIDs store IDs to generate their virtual address VA before proceeding through the result pipe.
An MDB hit with megavld means the load has to check that all older stores have generated their VA An MDB hit without megavld means the load only has to wait for up to 3 specific stores to generate their VA corresponding to each of the valid deltavld02 The SIDx for each valid deltavldx is calculated at E1 stage using the load YOST pointer SID of youngest of older stores as shown in the following equation.
LDMDBHITSIDx LDYOST LDMDBHITDELTAx.
Based on the MDB hit megavld and deltavld02 the load checks STQ at E2 stage and takes the following actions.
| 1 | If megavld1 and all older stores have generated VA take no action |
| --- | --- |
| 2 | If megavld1 and some set of older stores have not yet generated VA select youngest SID from that set and put load to sleep in LIQ waiting for that SID to generate its VA |
| 3 | If megavld0 and each of LDMDBHITSID 012 corresponding to LDMDBHITDELTAVLD0121 LDMDBHITSTPC012mdbstpc012 has generated VA take no action |
| 4 | If megavld0 and some set of LDMDBHITSID012 corresponding to LDMDBHITDELTAVLD0121 LDMDBHITSTPC012mdbstpc012 have not yet generated VA select youngest SID from that set and put load to sleep in LIQ waiting for that SID to generate its VA |

### 4.6 LDQs Load Queues Overview

The Load Queue in 920 is constructed of the following structures.
LIQ Load Inflight Queue.
LHQ Load Hit Queue.
LocalLIQ LocalLHQ observing only LDs issued in associated Load Pipe LDU.
Shared Resources in LoadS.

- LIQCTRL

Cross Load pipe control in LoadS.
LHQCTRL

#### Figure 4 6 LDQ scheme in LSU

The Load Queues in 920 have following characteristics.
Larger OOO requires to handle larger LDQ structures.
Simplify LDQs control Simplify Allocation and Deallocation logic.
No uop expansion in LSU Single entry per uOp in LDQs Single entry handle the MA1 and MA2.
Preallocated entries in LIQ LHQ at Dispatch.
LHQ is organized Age Ordered IssQ only issues in LHQ Sliding Window.
LIQ is organized to accommodate the OOOs LD Column Matrix Dependency matrix Wakeup Cancel.
Repartition the design to Local structures per load pipe leads to additional design aspects of the LDQ.
Split control of tasks to Local and some Central structures.
Note SMT is handled like 910 All resources are split in two Applies to Local and shared structures.
No LDQ back pressure LIQ is aligned to Load Matrix so in case of LIQ Full dispatch will be delayed No LHQ Backpressure scheme The IssQ is not aware of LHQ window It issues a uOp when the ready In case out of LHQ Window the uOp waits in the LIQ to be reissuedrepicked The LHQ entry is checked at LIQ at E3.
The LDSnoopNuke Scheme is more restrictive than what Arm permits Remove cross Load pipe snoops reduce Ports.
With LHQ Age ordered moving Order enforcement for eg NCDEV from MOB to LHQCTRL Includes LHQCTRL interfacting with SCB Replacing MOB.
Without uOp expansion SVE SM need to control up to 8 uOps into a single ordered operation Identifying Resolved on multi uOp instructions SVE SIMD task is moved to the OOO LD matrix has the Resolve information Replacing MOB.
The LDQLHQ takes control over MOB tasks Ordering between Load and Store instructions Handling NCDEV is covered in this chapter.
The LHQ CAM is used for the LDLD and STLD snoops Snoop scheme is covered in this chapter.

#### 4.6.1 LHQ Load Hit Queue

LSU has a flopbased Load Hit Queue LHQ shared by 2 threads which tracks loads that may need to be flushed due to ordering violations When an older store matches a younger resolved load to same address in LHQ the younger load must be flushed This is referred to as a stld nuke flush When a snoopinvalidate matches a load in LHQ it leads to a ldld nuke flush.
The following tables show the structure and entry format of the LHQ.
| LHQ | Comment |  |
| --- | --- | --- |
| Entries | 340 | Divide to three localLHQ array and 40 entries per array |
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
As above table shows the local LHQ has 1 write ports the load result pipe will write pgenmem attrother payload to LHQ if its in the LHQ sliding window.

#### Figure 4 7 LHQ Scheme

The following are key characteristics of the LHQ.
Single LHQ Entry per uOp Age Ordered Deallocation of entries is in AGE order.
LHQ entry is PreAllocated at dispatch of uOp Continuous numbering per LDULoad pipe.

- LID represents Age.
- LHQ entry ID represents Local AllocationDeallocation order.
- The LocalLHQ assignment is done in modulo 3 fashion meaning that age can be identified between LocalLHQs.

Removing 910 LHQ Stall Remove the 910 LHQ backpressure scheme Vacancy for uOp is guaranteed at IssQ The LHQ Window deallocation is in retired order.
The LHQ tracks all Loads from Issue E4 to Retire.
All LDs are written to LHQ NC DEV WB.
Write Once Policy to LHQ at Issue write to LHQ when PGEN is available At E4.
No need to identify oldest Stale Load in LHQ Flush on Oldest when not all older loads are pgen valid.
Move the MOB responsibilities to LHQ Perform the tasks to enforce order.

- Required Ordering across NCDEV load and store uops.
- Includes SVECTRL logic.

RePartitioning to following structures.
LocalLHQ

- LHQ Array Preallocated per LDULoadC.
- Local MOB L2 Request Wakeup mechanism.

LHQCTRL

- LHQCTRL receives information from Locals and makes ordering decisions.
- MOB Tasks to control over between Special instructions NCDEV Interfaces with LocalLHQs and SCB.
- NukeCtrl STLD
- SnoopCTRL LDLD.

For SMT handling LHQ resources are split in half Per LocalLHQ Arrays and LHQCTRL SMs.
The STLD Nuke behavior does not change in 920 but due to repartition it is redesigned.
Implementation Note on LHQ implementation in 920.
The comparators for LHQLID sliding window are checked in the LocalLIQ The LHQ is written to only if LD is in the sliding window Else it waits in LIQ for Wakeup when pre allocated entry in LHQ is vacant.
For Big core the OOO assigns LID at dispatch and maintains order of LID between load pipes in modulo3 method Allocation and deallocation to LHQ is age ordered.
For Middle core the LHQ is a unified structure The OOO no longer dispatches according to mod3 and is free to dispatch to any free issQ entryload pipe LHQ is organized based on preallocated LID.
LHQ sliding window now uses Committed load rcnt to deallocate.

- In every commit OOO send the committed load cnt And LSU maintain the oldest lid ptr to deallocate LHQ.

For NCDEV and ldxr they are written to LHQ in the sliding window as well as Cancelled and remain in LIQ till LHQ wakesup according to ordering rule.

#### Figure 4 8LHQ Scheme

##### 4.6.1.1 Load LHQ Allocation Deallocate

In 920 the LHQ is preallocated and saved in order in side local LHQ Its deallocated by next committed RID.
Any entrys RID is older than next committed RID it could be deallocated.
Since the LHQ is orderd its not necessary to add a RID comparator in each LHQ entry It read out the oldest LHQ entrys RID and compared with next committed RID And then decide whether its deallocate or not.

##### 4.6.1.2 Store LHQ Nuke

When a younger load issues and resolves ahead of an older store to same address any overlapping bytes the younger load must be nuke flushed The 2 stores are sent to LHQ to detect such storeload ordering violations The LHQ CAMs the STA PA against younger resolved LHQ entries of the same thread at E4 stage and sets the nuked bit and stli bit storeload interaction on a PA overlap This operation happens at the same cycle that the STA is written to the STQ PA array so younger loads that resolve after this point will detect the PA overlap in STQ.
Once the LHQ entry nuked bit is set the independently operating LHQ nuke picker selects this nuked LHQ entry when it becomes the oldest nuked entry of its thread RESOLVED not oldest and sends the nuke flush to ROB.
For any nuked load the load is recorded in the MDB with a pointer to the store that nuked it to help prevent the same load from being nuke flushed again in the future.

#### 4.6.2 LIQ Load InFlight Queue

LSU has a flopbased Load In Flight Queue LIQ shared by 2 threads which holds load instructions issued by IEX that require TLB Translation reaccessing the vSTQ or require WakeupRepick for loads that are waitng for Data to be resolved.
Each LIQ entry records the wait condition and corresponding wakeup tag for both the MA1 and MA2 components of a load uop Once both MA1 and MA2 components have data ready or only the MA1 component for aligned loads the load uop is eligible to be repicked Each cycle LIQ picks the oldest ready load to reflow back in the load result pipes If a repicked load encounters another hazard condition preventing data return it is put back to sleep in its pre allocated LIQ entry If the repicked load is resolved its LIQ entry ID is deallocated and resolve ID to Load Matrix IEX.
The LIQ is organized as 3 subarrays The attribute and PA arrays have a regular datapath structure with readwrite ports while the status array consists of control logic to track load sleep and wakeup events The following tables show the structure and entry format of the LIQ.
The LIQ consists of an array and an age matrix to track the relative program order age across entries for RepickReissue priority.
| LIQ | Comment |  |
| --- | --- | --- |
| Entries | 16 | 16 shared entries |
| Entry width | 175 bits |  |
| Ports | 1R 1W 2C | RD I1I2 LIQ repick |

WR E3 IEX lda0 issue.
CAM E3 IEX lda0 issue.
CAM R4 T0T1 flush
| TOTAL SIZE | 2800 bits |
| --- | --- |

LIQ is further characterized with following.
LIQ entry is PreAllocated based on LDmatrixcolumnID Write once policy for non resolved loads at E3.

- The Total Size of LIQ is dictates by size of LD Dependency Matrix.

A uOp is pre allocated at Dispatch guard band between LSU Resolved to Dispatch No Credit or backpressure needed.
The LIQ tracks all noncompleted Loads from Issue E3 to Resolve.
The LIQ with LHQ control performs Local MOB tasks WakeupRepick of NCDEV.
LIQ Partitioning

- Local LIQ

Local LIQ VA TLB miss and vSTQ FWD Reissue.
Local LIQ PA Holds Loads with Pgen Repick.
Local LIQ Attributes associatedlocated with LIQ PA.
Local Page Cross
Local Safe Mode Solve livelock Prioritizes MA2 mis align to block the younger loads repick and sending miss request only oldest one to execute in this mis align scenario.

- Shared LIQ Handles shared resources low usage high area.

MGW Mis aligned Device load Split it by element size Ld3 Ld4.
Load Exclusive request Share the monitor Very Big Very rare.
LIQ does not require to support Backpressue It is guaranteed that a LIQ entry is available to receive an issued uOp as LIQ size correlated to Load Matrix.
The allocation and release of LocalLIQ entry is tracked per Local LIQ based on Execution completion within LoadC.
In 920 the Cross Page mechanism is replicated per Local LIQ This mechanism relates to TLB 2 different fault detection.
In 920 with virtual STQ need to handle mis align single LIQ entry will handle mis align the same way Possibly with Region Overlap check.

##### 4.6.2.1 Load Sleep in LIQ

At E3 stage any load that cannot return its result data to IEXFSU on its current pass through the load pipes must be put to sleep in LIQ Every hazard condition that puts a load to sleep has a corresponding wakeup condition that sets the load as ready to reissuerepick A particular load may encounter multiple hazard conditions so LIQ must prioritize these cases to determine the wakeup condition to monitor.
The tables below summarize the load sleep cases.
The first table shows the sleep priority and wakeup conditions for loads put to sleep in LIQ RISS All of the LIQ RISS sleep cases are higher priority than LIQ sleep cases.
The second table shows the sleep priority and wakeup conditions for loads put to sleep in LIQ.
| Priority | LIQ Sleep Case | Stage Detected | LAGB Sleep FSM State | LAGB Wakeup Condition |
| --- | --- | --- | --- | --- |
| 1a | Issued load TLB miss Allocated miss request buffer MRB entry and sent request to MMU | E3 | WAITMRBFILL | MMU fill return for appropriate miss buffer ID |
| 1b | Issued load TLB miss Miss request buffer MRB full | E3 | WAITMRBDEALLOC | TLB miss buffer entry becomes available |
| 1c | Issued load TLB miss Miss request buffer MRB port conflict | E3 | READYREISSUE | Immediately ready for LAGB reissue |
| 2a | Issued load is XP misaligned regardless of MA1 fault detection | E3 | WAITLIQOLDEST | Load RID matches OLDESTLIQRID |
| Priority | LIQ Sleep Case | Stage Detected | LMQ Sleep FSM State | LMQ Wakeup Condition |
| 1a | Issued load is NC | E3 | WAITNCOLDEST | Load LID matches OLDESTUNORDEREDRID |
| 1b | Issued load is DEV | E3 | WAITDEVOLDEST | Load RID matches OLDESTUNORDEREDLID and older than or equal to NOFLUSHRID from ROB |
| 2 | Issued load is LDXR and LID younger than OLDESTLDXRLID | E3 | WAITLDXROLDEST | Load LID matches OLDESTLDXRLID |
| 3a | Issued load has fault but lost result pipe resolve interface to LIQ repick load | E3 | READYBB | Immediately ready for LIQ repick |
| 3b | Issued load has DC TAG conflict unknown DC TAG hitmiss status | E3 | READYDC | Immediately ready for LIQ repick |
| 3c | Issued load is cross16B misaligned needs ma1ma2 expansion | E3 | READYDC | Immediately ready for LIQ repick |
| 4a | WB load DC TAG miss Lost first 3 stages of REQARB arbitration | E3 | READYREQ | Immediately ready to send request from LIQ to L2C |
| 4b | WB load DC TAG miss Won REQARB arbitration miss request will be sent to L2C | E3 | WAITLFBFILL | Wait for L2 FILL |
| 5 | Issued load lost result pipe to LIQ repick load | E3 | READYDC | Immediately ready for LIQ repick |
| 6a | Load has MDB hit and specified stores have unknown PA | E3 | WAITSTQPGEN | Store SID with unknown PA obtains PA pgen |
| 6c | Load matches STQ store with no store data | E3 | WAITSTQDGEN | Store SID with no data obtains data dgen |
| 7 | Load matches nonbypassable SCB store | E3 | WAITSCBDEALLOC | Nonbypassable SCB hazard tag wakeup from SCB |
| 8a | Load has DC DATA conflict | E3 | READYDC | Immediately ready for LIQ repick |
| 8b | Load has DC DATA miss DC DATA hit way was not clockenabled | E3 | READYDC | Immediately ready for LIQ repick |
| 8c | Load MA1 at E3 matched older store at E2 | E3 | READYDC | Immediately ready for LIQ repick |
| 9 | Load miss RSB status Reject due to hazard with existing RSB entry | E3 | WAITLFBDEALLOC | LFB have credict |
| 10 | Not in LHQ sliding window issued load had no other sleep case except LHQ entry | E3 | WAITLHQDEALLOC | LHQ entry becomes available |

##### 4.6.2.2 Load LIQ Allocate

At E3 stage of the load issue pipe LSU checks whether the load instruction needs to be put to sleep in LIQ.
The Issue pipe at E3 write to LIQ if needed VAPA For the skid succeed load it write at the skid issue pipe E3 the issue E3 write is cancelled.
The LIQ Preallocation guarantees a LIQ entry is always available Allocated to LIQ based on LD Matrix ID of uOp.
Local LIQ has age matrix and picker to select oldest ready loads for execution in the result pipes.

##### 4.6.2.3 Load LIQ Wakeup

When a load instruction is put to sleep in LIQ the entry sleep FSM waits for the appropriate wakeup condition.
The table below shows the sleep FSM states and the next state after wakeupreissuerepick.
| LIQ Riss Sleep FSM State | Encoding[30] | Description | Next State |
| --- | --- | --- | --- |
| IDLE | 0000 | Not sleeping or ready to reissuerepick | WAIT or READY |
| WAITMRBDEALLOC | 0001 | Load TLB miss but TLB miss request buffer MRB was full Wait for MRB entry to deallocate | READYREISSUE |
| WAITLIQOLDEST | 0010 | Load cannot execute until it is oldest in LIQ Wait for load LID to match OLDESTLIQLID from LHQ | READYREISSUE |
| READYREISSUE | 0110 | Ready to reissue | IDLE |
| WAITMRBFILL | 1 MRBID[20] | Load TLB miss sent miss request to MMU Wait for TLB fill return from MMU for specified MRBID[20] | READYREISSUE |
| LIQ Sleep FSM State | Encoding[50] | Description WAIT | Next State |
| IDLE | 000000 | Not sleeping or ready to repick | WAIT or READY |
| WAITMGWDONE | 000010 | DEV load uop is elementsizealigned totalsizemisaligned MGW Wait for MGW engine to finish sending elementsize requests to L2C and receiving all data into BasicB | READYBB |
| WAITLDXROLDEST | 001001 |  |  |
| WAITNCOLDEST | 001010 |  |  |
| WAITDEVOLDEST | 001011 |  |  |
| WAITSTQPGEN | 001100 |  |  |
| WAITSTQDGEN | 001101 |  |  |
| WAITSTQDEALLOC | 001110 |  |  |
| WAITSCBDEALLOC | 001111 |  |  |
| WAITLFBCREDIT | 010010 |  |  |
| WAITL2FILL | 010100 |  |  |
| WAITLHQDEALLOC | 010101 | WB load DC hit but LHQ was full outside LHQ sliding window Wait for LHQ entry to deallocate | READYREPICK |
| LIQ Sleep FSM State | Encoding[50] | Description READY to Repick for Result Pipe | Next State |
| READYBB | 100000 |  |  |

100001
100010
| READYFDBE2C6 | 100011 |
| --- | --- |
| READYFDBE2C7 | 100100 |
| READYFDBE2C8 | 100101 |
| READYFDBE2C9 | 100110 |
| READYFDBE2C10 | 100111 |
| READYDC | 101001 |

##### 4.6.2.4 Load LIQ Repick Reissue

LocalLIQ has age matrix and a unified picker to select oldest uOp to repick reissue.
LIQ reissue is used for cases when the load did not yet obtain its PA including load TLB miss and crosspage XP misaligned load.
When selecting Reissue at P1 stage picker selects the oldest LIQ entry whose sleep FSM is in READYREISSUE or READYREPICK state If the selected entry requires reissue it is sent through the TLB arbiter to the lda0 issue pipe.
When selecting Repick where the load already obtained its PA from TLB.
For an LIQ entry to be eligible for repick the first requirement is that its sleep FSM must be in a READY state excluding REQREADY At P1 stage this READY state is filtered to prevent conflicts with in-flight DC snoops and fills from L2C This is referred to as conflictaware repick.
For more details on LIQ FSM and the relationship between repick and reissue refer to LIQ FSM excel in reference documents.
At P0 stage each LIQ entry compares its RID against the OLDESTNUKERRID for its thread.
After each LIQ entry generates its final P1 stage ready bit the subpickers for each LIQ bank select the oldest ready entry for each thread in their bank based on the age matrix for that bank The picker for each LIQ then uses workconserving round robin to select a final LIQ entry from across its two per-thread subpickers.

#### 4.6.3 LDLD Snoop scheme

The LDLD Nuke behavior changes in 920.
L2 Snoop Hashing is changed to PA based snoop and only check the L2V snoop.
Key change is that Local LHQ do not includes cross load pipes snoops as in 910 and Stale Logic to detect STLD order except below corner case.

- For DMB It still need the stale logic All the loads hit by snoop younger than DMB need to be nuked No matter the address.
- If the snoop hit a load and all the older load are pgened and no same CL load the load is not be flushed But the staled need be set If an older DMB comes these loads need to be flushed.

The LHQ applies short Snoopdecision window for determining the nuked load Stricter loadsnoop policy that Arm permits In most common cases nuke on oldest load.
Nuke request is sent to OOO only on resolved loads same as 910.

- For non resolved loads the LIQ+Pipe Snoop allows load not to be not nuked but rather repicked again and get new Data The LIQ snoop changes TAG Hit to Miss.

LIQ snoop is extended to include snoopingcancelling E2 E3 E4 pipe stages to capture transient between Repick and Resolve for RTL E2 maybe done in E3 next cycle.

- L2 snoop accesses E2E3E4 pipes Cancelling all in-flight uops to LIQ.
- L2 snoop accesses LIQ to invalidating any TAGWay information Repick has to reaccess TAG in next pick.

As LHQ is partitioned so does the LD Snoop needs to handle this The Local LHQ observes Snoop on Local uOps The Snoop will access LocalLHQ and will observe all pgen valid not just resolved.
The following table summarizes entry status for Loadsnoop match.
| Entry status | Condition | Comment |
| --- | --- | --- |
| Entrynotresolved | entryvld resolved WB pgen pamatch || |  |

Entryvld resolved nopgen ||.
Entryvld resolved ldar ||.
| entryvld | Have older unresolved WB load when snoop valid |  |
| --- | --- | --- |
| Entrysnoopresolved | entryvld resolved WB snoopmatchhit | Resolve load is snooped |

Need pipe e3e4c7 bypass.
When snoop arrives use LHQ entry status to find the oldest LID entry which need to be nuked.
For LDAR with WBNCDEV make older entry to be unresolved and set snoop nuke.

##### 4.6.3.1 Snoop types

There are 3 kinds of snoop for LSU.
Snpi Its the snoop invalid for the CL S status it access the dcstatus and LHQ.
Snpci Its the snoop copyback and invalid its used for the CL EM state it need to access dcstatus DC and LHQ both EM state is generate snpci.
Snpcs its the snoop copyback S status it used for the CL EM status after executing the CL changed to S state It need to access dcstatus DC The data is not changed so it does not need to access LHQ.

##### 4.6.3.2 Coherent snoop for SMT

Between 2 thread the ldld ordering need to be considered.
The 2 thread ldld ordering is supported is by reusing the single thread ld nuke path.
In side LSU an internal coherent snoop is generated and injected at the snoop pipe C6 And the rest path snoop C7C11s handling logic is the same with external snoops coherent handling.

##### 4.6.3.3 LIQ Snoop

The LIQ monitor snoops from L2C Specifically the SNPI snoopinvalidate and SNPCPBKI snoopcopybackinvalidate types The SNPCPBKS snoopcopybackshared type does not affect load instructions.
In LIQ only valid entries with MA1 or MA2 in real DC hit state dcspec0 dchit1 are snooped The LIQ snoop CAM operation is shown in the equations below On a snoop match the appropriate LIQ entry MA1 or MA2 is changed to speculative DC miss state dcspec1 dchit0 When the LIQ entry is repicked later the MA1 or MA2 component with dcspec1 checks the DC TAG again and sends a miss request to L2C on DC TAG miss.
SNPLMQVLD SNPREQVLD SNPREQTYPE SNPI |.

- SNPREQTYPE SNPCPBKI.

LIQxSNPMATCH1 LIQxPGEN1VLD SNP LIQVLD.

- LIQxVA1[1312] SNPREQENCWAY[32]
- LIQxDCHITWAY1[10] SNPREQENCWAY[10]
- LIQXVA1[116] SNPREQADDR[116]

LIQxSNPMATCH2 LIQxPGEN2VLD SNPLIQVLD.

- LIQxVA2[1312] SNPREQENCWAY[32]
- LIQxDCHITWAY2[10] SNPREQENCWAY[10]
- LIQXVA2[116] SNPREQADDR[116]

##### 4.6.3.4 LHQ Snoop

All pgen valid LHQ entries are snooped.
The LHQ snoop is changed to L2V snoop And its PA based and need an extra snoop ports with L2C.

#### 4.6.4 Allocating MOB tasks to LHQ

The LHQ supports Waking up NCDEV instructions when they are eligible to request L2.
LHQ is Age order
LHQ captures all Loads including NCDEV oppose to 910 Adding type to indications to LHQCTRL.
LHQ takes control over MOB tasks to enforce order for all special loads and stores Allocating MOB Tasks to LocalLHQ and LHQCTRL Tasks mainly includes Ordering across NCDEV load and store uops.
The LHQ controls the order sending L2 Requests of the NCDEV Decision on sending L2 request is performed in LocalLHQ.
Note Every issued NCDEV Load is Cancelled on first issue and must be repicked from LIQ.
Repick of NCDEV is upon controlled WakeupOldest procedure that in 920 the wakeup is generated in the localLHQ or SCB.
Two mechanisms help LHQ and LIQ to cover extreme scenarios for NCDEV.

- The safemode comes to solve livelock By Prioritizes Oldest NCDEVWB with Mis Align blocks younger loads repick in LIQ Only oldest can execute in this mis align scenario.
- The BasicB serves as a load data return buffer for the following cases 1 NCDEV load data 2Oldest WB load data forward progress guarantee.
- Handles scenario of fill line to L1 and in parallel a snoop to same line.

As in 910 Optimizing NCDEV performance the LHQCTRL will sends NCDEV requests as outstanding directly to L2RSB for higher priority transaction.

##### 4.6.4.1 Load ROB Noflush

For loads the ROB maintains a window of oldest instructions that can no longer trigger a flush The Local LHQ uses the NOFLUSHRID to determine when it wakeup LIQ to send a DEV load request to L2C When the NOFLUSHRID is younger than or equal to the DEV load RID and the DEV load RID matches the OLDESTUNORDEREDLID the DEV load is eligible to send its request to L2C.
Note LIQ needs to check older store need to be retired to SCB and SCB has no dev storedev store is GO.

##### 4.6.4.2 Load ROB Commit

A load is considered ROB committed when ROB deallocates the load RID Load ROB commit does not trigger any LSU activity.

##### 4.6.4.3 Load Ordering

The following table summarizes these oldest RID cases The sections below provide more details on how each oldest RID is generated.
| Oldest RIDLID Generated by LHQ | Description | Comment |
| --- | --- | --- |
| Oldest LIQ LID | LID of oldest load that still needs to use LIQ oldest unresolved load instruction | Total |
| Oldest Unordered LD LID | LID of oldest unresolved WB load or nonLFB allocated NCDEV load | Total |
| Oldest LDXR LID | RIDLID of oldest load exclusive instruction | Total |

The LDLD Nuke behavior changes in 920.
L2 Snoop Hashing is changed to PA based snoop and only check the L2V snoop.
Key change is that Local LHQ do not includes cross load pipes snoops as in 910 and Stale Logic to detect STLD order except below corner case.

- For DMB It still need the stale logic All the loads hit by snoop younger than DMB need to be nuked No matter the address.
- If the snoop hit a load and all the older load are pgened and no same CL load the load is not be flushed But the staled need be set If an older DMB comes these loads need to be flushed.

The LHQ applies short Snoopdecision window for determining the nuked load Stricter loadsnoop policy that Arm permits In most common cases nuke on oldest load.
Nuke request is sent to OOO only on resolved loads same as 910.

- For non resolved loads the LIQ+Pipe Snoop allows load not to be not nuked but rather repicked again and get new Data The LIQ snoop changes TAG Hit to Miss.

LIQ snoop is extended to include snoopingcancelling E2 E3 E4 pipe stages to capture transient between Repick and Resolve for RTL E2 maybe done in E3 next cycle.

- L2 snoop accesses E2E3E4 pipes Cancelling all in-flight uops to LIQ.
- L2 snoop accesses LIQ to invalidating any TAGWay information Repick has to reaccess TAG in next pick.

As LHQ is partitioned so does the LD Snoop needs to handle this The Local LHQ observes Snoop on Local uOps The Snoop will access LocalLHQ and will observe all pgen valid not just resolved.
The following table summarizes entry status for Loadsnoop match.
| Entry status | Condition | Comment |
| --- | --- | --- |
| Entrynotresolved | entryvld resolved WB pgen pamatch || |  |

Entryvld resolved nopgen ||.
Entryvld resolved ldar ||.
| entryvld | Have older unresolved WB load when snoop valid |  |
| --- | --- | --- |
| Entrysnoopresolved | entryvld resolved WB snoopmatchhit | Resolve load is snooped |

Need pipe e3e4c7 bypass.
When snoop arrives use LHQ entry status to find the oldest LID entry which need to be nuked.
For LDAR with WBNCDEV make older entry to be unresolved and set snoop nuke.

##### 4.6.4.4 Snoop types

There are 3 kinds of snoop for LSU.
Snpi Its the snoop invalid for the CL S status it access the dcstatus and LHQ.
Snpci Its the snoop copyback and invalid its used for the CL EM state it need to access dcstatus DC and LHQ both EM state is generate snpci.
Snpcs its the snoop copyback S status it used for the CL EM status after executing the CL changed to S state It need to access dcstatus DC The data is not changed so it does not need to access LHQ.

##### 4.6.4.5 Coherent snoop for SMT

Between 2 thread the ldld ordering need to be considered.
The 2 thread ldld ordering is supported is by reusing the single thread ld nuke path.
In side LSU an internal coherent snoop is generated and injected at the snoop pipe C6 And the rest path snoop C7C11s handling logic is the same with external snoops coherent handling.

##### 4.6.4.6 LIQ Snoop

The LIQ monitor snoops from L2C Specifically the SNPI snoopinvalidate and SNPCPBKI snoopcopybackinvalidate types The SNPCPBKS snoopcopybackshared type does not affect load instructions.
In LIQ only valid entries with MA1 or MA2 in real DC hit state dcspec0 dchit1 are snooped The LIQ snoop CAM operation is shown in the equations below On a snoop match the appropriate LIQ entry MA1 or MA2 is changed to speculative DC miss state dcspec1 dchit0 When the LIQ entry is repicked later the MA1 or MA2 component with dcspec1 checks the DC TAG again and sends a miss request to L2C on DC TAG miss.
SNPLMQVLD SNPREQVLD SNPREQTYPE SNPI |.

- SNPREQTYPE SNPCPBKI.

LIQxSNPMATCH1 LIQxPGEN1VLD SNP LIQVLD.

- LIQxVA1[1312] SNPREQENCWAY[32]
- LIQxDCHITWAY1[10] SNPREQENCWAY[10]
- LIQXVA1[116] SNPREQADDR[116]

LIQxSNPMATCH2 LIQxPGEN2VLD SNPLIQVLD.

- LIQxVA2[1312] SNPREQENCWAY[32]
- LIQxDCHITWAY2[10] SNPREQENCWAY[10]
- LIQXVA2[116] SNPREQADDR[116]

##### 4.6.4.7 LHQ Snoop

All pgen valid LHQ entries are snooped.
The LHQ snoop is changed to L2V snoop And its PA based and need an extra snoop ports with L2C.

#### 4.6.5 LDQ Flush

The LSU combines the IEX branch flush and OOO ROB flush to create a merged flush indication for each thread Each stage of the load issue pipes and load result pipes carries its TID and RID On an R3 stage merged flush LSU compares the flush TIDRID against each pipeline stage TIDRID If the pipeline RID is younger than or equal to the flush RID of the same thread the load at that stage is invalidated.
Similarly each entry in the LIQ and LHQ has TID and RID fields On an R4 stage merged flush LSU compares the flush TIDRID against each entry TIDRID If the entry RID is younger than or equal to the flush RID of the same thread the entry is invalidated.

#### 4.6.6 BasicB Load Data Buffer

The LSULDQ has a flopbased Basic Buffer BasicB shared by 2 threads that serves as a load data return buffer for the following cases.
NCDEV load data
Oldest WB load data forward progress guarantee.
The following tables show the structure and entry format of the BasicB.
| BasicB | Comment |  |
| --- | --- | --- |
| Entries | 6 | 1 ma entry per thread + 4 shared align entries |
| Entry width | 265 bits |  |
| Ports | 3R 1W 3C | RD E2 pipea load |

RD E2 pipeb load
RD E2 pipec load
WR C8 fill data return.
CAM E2 pipea load
CAM E2 pipeb load
CAM E2 pipec load
| TOTAL SIZE | 1060 bits |
| --- | --- |

### 4.7 LFB Line Fill Buffer

The LFB has the task of merging all LDmiss L2 requests such that a single request is generated per Cache Line CL All loads will cam PA[486] with Local LFB CAM.
Note Stores are not handled by LFB.
The LFB allocating PA E3 issue PA E2 STQ and SCB at stage E2 as shown in figure 21 Based on PA cam results there are four stages including allocating or merging with LFB Wait LFB credit and wait SCB fill.
Load will cam SCB and LFB at E2 If tag miss and hit SCB load will not allocate LFB and wait fill that corresponding SCB request If tag miss and not hit SCB load will allocate or merge LFB LFB and SCB can send different bank request to L2 at same cycle LSU will not send kinds of request to L2 until it sends corresponding request ready to LSU After receiving LSU request L2 will fill LSU with corresponding request ID which will wake up SCB or LIQ.

#### Figure 4 10 LSU L2 Request with LFB

When LFB is Full it stops sending L2 Requests SCB can continue sending requests.
The LFB supports 64B NC Load Merge.
Refer to L2C Pipeline chapter and LFB in DS chapter for more information.

### 4.8 FDB Fill Pipe

The FDB is a flopbased pipeline that carries fill data return from the L2C interface to the DC write E2 stage loads that match the fill PA can bypass fill data from C7 C10 stages The following tables show the structure and entry format of the FDB.
| FDB | Comment |  |
| --- | --- | --- |
| Entries | 4 | 4 stage pipeline C7 to C10 |
| Entry width | C7 568 bits |  |

C8 580 bits
C9 580 bits
C10 580 bits
| Ports | 4R 1W 3C | RD E2 pipea load |
| --- | --- | --- |

RD E2 pipeb load
RD E2 pipec load
RD C9 fill DC write setup.
WR C7 fill return depending on field.
CAM E2 pipea load
CAM E2 pipeb load
CAM E2 pipec load
TOTAL SIZE
Load at E1 can take Data when hits on FDB on Fill at C7.
The FDB fill pipe propogates the fill PA Way and Data return from the L2C interface to the DC arrays A fill writes its PA[4812] to the DC TAG array at C10 stage where it will be visible to load DC TAG checks at C11 stage Before this point an E2 stage load checking DC TAG for the same cacheline being filled would see a DC TAG miss To prevent such a load from sending a miss request an E2 stage load in the DC TAG pipe CAMs its PA[486] against each stage C7 C10 of the fill pipe On a match the load considers this FDB hit as a DC TAG hit with the DC hit way taken from the matching fill pipe stage The FDB hit signal is used to gateoff the miss request to L2C.

### 4.9 STQ Store Queue

LSU has a flopbased Store Queue STQ shared by 2 threads which holds the address and data for each store uop until it is retired to SCB A store uop is eligible to retire to SCB once its RID enters the noflush window of ROB The STQ also forwards store data to younger loads with matching addresses.
The STQ is organized as five subarrays The attribute PA bytemask and data arrays have a regular datapath structure with readwriteCAM ports while the status array consists of control logic to track PA generate data generate and STQ retire events.
These arrays in 920 are partitioned and replicated as follows.
Local vSTQ TAG is replicated per LoadC It includes all STQ TAG bits that are required to perform STRFWD and STQBlocking.

- Range Compare is used to minimize BM replication.

A Store PA STQTAG is replicated to StoreR Used to RD retired stores.

- This STQ TAG is PA even if LoadC STQ TAG is Virtual TAG.
- Also includes Preretirement PR Status Array.

The Data Attributes BM are allocated at Data Block or StoreR where information is used Used at R3 for retirement and RD to STQ Head flops In R4 move Head Flops to SCB.
Retirement Status The Status is divided to Pre retired StoreC and retired part StoreR Replicating information both components.

- StatusC
- StatusR

Retirement Status
Record the retirement for STA and STD in this order STA links STD and SCA Need status whether SCB is done for some special instructions.
Split the control of Retirement pre retirement part.
Range compare between loads and stores.
Range compare helps avoid storing BM information in LocalvSTQ Using Range compare on LD Width + Start address to match overlap STQ.
| STQ | Comment |  |  |
| --- | --- | --- | --- |
| Entries | 40 | DSE define 324048 |  |
| Entry width | 305 bits |  |  |
| Ports | attribute | 2R 4W 2C | RD R4 STA retire0 |

RD R4 STA retire1
WR E2 IEX sta0 issue.
WR E2 IEX sta1 issue.
CAM R4 T0 flush
CAM R4 T1 flush
| bytemask | 5R 2W | RD E3 pipe0 load |
| --- | --- | --- |

RD E3 pipe1 load
RD E3 pipe2 load
RD R4 STD retire0
RD R4 STD retire1
WR E2 IEX sta0 issue.
WR E2 IEX sta1 issue.
| data | 5R 2W | RD E3 pipe0 load |
| --- | --- | --- |

RD E3 pipe1 load
RD E3 pipe2 load
RD R4 STD retire0
RD R4 STD retire1
WR E2 std0 issue
WR E2 std1 issue
| TOTAL SIZE | 9760 bits |
| --- | --- |

#### 4.9.1 Local Load Virtual STQ

In 920 the STRFWD is a Virtual Operation Load Cancel is a physical.
To support the operation the Virtual STQ TAG is broken down to Virtual and Physical.
Youngest Physical [110] Block on possible PA mismatch.
Full Virtual [470] This is STRFWD Match.
STRFWD and Blocking.
Refer to table below.
If Loads VA1 has no STQ Hit no VA[470] overlap do not Care.
If Loads VA1 overlaps VA2 Hit Age where VA2 is also Youngest Physical[110] then do STRFWD Same TID.
If Loads VA1 overlaps VA2 Hit Age But VA2 is not the Youngest Physical[110] then Block because we do not trust the upper VA.
Due to performance loss of False PA blocking adding 9 PA bits PA2011] in the STQ Match to reduce False Blocking.
LD VA1[full] VA2
Hit in STQ
| Same TID | VA2[110] |  |  |
| --- | --- | --- | --- |
| is Yougest | PA1PA2 | Action |  |
| 0 | do not Care | do not Care | No StoreFWD or blocking |
| 1 | 1 | Yes | Do STRFWD |

Same Thread ID can do virtual match to do STRFWD.
| 1 | 1 | No | Block on PA check this Load |
| --- | --- | --- | --- |

Do STQ blocking Send to LIQ.
| 1 | 1 | Yes but different TID | Block on PA check this Load Use extra PA Bits in STQ to reduce false blocks |
| --- | --- | --- | --- |

Do STQ blocking Send to LIQ.
Note Corner case of Store retire pipe blocked and STQ holding multiple already committed instructions need to take care of draining the STQ from retired stores before enabling ST forwarding Solution adds a fwd disable bit in STQ If this case happens block the ldst fwdinglet the load wait for stq deallocation.
| Entries | 48 | Each vSTQ array split to odd and even |
| --- | --- | --- |

STQ bank is partitioned to two bank according to SID instead of tid that to say each bank may include store of both thread So each bank array need the t0 flush and t1 flush.
| Entry width | 67 bits | Pgen 1-bit |
| --- | --- | --- |

Partpa[2012] 9 bits.
Va[480] 49 bit
Size[30] 4 bit
Allactive indicate there is no hole due to sve 1 bit.
Xp a load will be cancel when there is xp in stq 1 bit.
Forward disable 1 bit.
Cmodczva 1 bit
| Ports | TAG | 1R 1W 1C | RD E1 lda issue by VA |
| --- | --- | --- | --- |

RD E2 lda issue for Partial PA[2012]
WR E2 IEX sta0 issue ODD WR E2 IEX sta1 issue EVEN.
CAM R4 T0 flush
| TOTAL SIZE | 9760 bits |
| --- | --- |

Note there is no RID stored in vstq so it will not compare with flush RID The stq PA will handle flush logic and tell vstq to deallocate may due to flush or retire.
Following diagram depicts how STQ array allocates T0 and T1 stores On the left is 910 and the right is 920.

##### 4.9.1.1 Load vSTQ CAM Read

At E1 stage of the load result pipe the load CAMs its VA[486] and Range Compare against the MA1 and MA2 components of all older STQ entries The resulting STQ CAM match vector is used for forwarding store data to younger loads.
A LDs VA[486] CAMs vSTQ and range compare uses va[50] + size to do the range compare in the same cache line.
When store is cross page cancel load and do not forward data to load.
When store cross page load will be cancel so only one pgen is needed.
Current 920 scheme does not support store forwarding to load when store is misalign not cross page open item if it could be supported.
For cross cache line if timing is critical also it can store vaidx1[116] and vaidx0[116] in stq local tag entry.
The STQ needs to cancel load when there is older stxr or stat It can be merged to a forward disable bit.

- if 920 support misalign storenot cross page forward data to load ma[10] is not needed it should be.

| STQ Entry Field Used By STQ CAM | Description |
| --- | --- |
| vaidx1[110] | MA1 and MA2 VA index |
| Store Size [30] | For Range compare |
| FullVA[4812] | MA1 and nonXP MA2 VA[4812] |
| pgen1vld | For PA Compare at E2 |
| PA[2012] | Reducing false PA blocking |
| Stxrstat | Stxr or stat |
| Xp | Crosspage XP |
| Cmo | CMO instruction |

Forward Disable
If 920 does not support misalign storenot cross page forward data to load ma[10] is needed it should be.
| STQ Entry Field Used By STQ CAM | Description |
| --- | --- |
| vaidx1[110] | MA1 and MA2 VA index |
| Store Size [30] | For Range compare |
| FullVA[4812] | MA1 and nonXP MA2 VA[4812] |
| pgen1vld | For PA Compare at E2 |
| PA[2012] | Reducing false PA blocking |
| ma[10] | Encoded misaligned |

00. Aligned AL
01. Cross64B XL crosscacheline.
10. Crosspage XP

| Stxrstat | Stxr or stat |
| --- | --- |
| Cmo | CMO instruction |

Forward Disable
By default the STQ CAM operation checks for a bytegranularity VA match between the result pipe load and each STQ entry However in the cases listed below the STQ CAM operation checks for a cachelinegranularity PA match instead.
| 1 | Load is LDXRLDAXR loadexclusive |
| --- | --- |
| 2 | Load is LDAT atomic near load uop |
| 3 | STQ entry is CMO instruction |

In certain cases the STQ CAM operation is disabled based on the load instruction type or STQ entry type These STQ CAM matchdisable cases are listed below.
| 1 | Load is PRFM software prefetch |
| --- | --- |
| 2 | Load is LDSTX storeexclusive load uop that returns passfail status |
| 3 | Load is LDODB atomic load uop that returns old data |
| 4 | STQ entry is setway CMO instruction |
| 5 | STQ entry is ATTLBIICRAMIDXCLREXDMBDSBESB instruction |

In certain cases STQ store data forwarding is disabled In these cases if the STQ CAM operation is still enabled the STQ CAM match vector is only used for detecting STQ hazard conditions and putting younger loads to sleep The STQ CAM forwarddisable cases are listed below.
| 1 | All STQ CAM matchdisable cases |
| --- | --- |
| 2 | Load is LIQ repick and NCDEV |
| 3 | STQ entry is STXR STLXR storeexclusive |
| 4 | STQ entry is atomic instruction |
| 5 | STQ entry is CMO instruction |

Not accessing the STQ on Repick means that when a load is repicked it will be disabled to forward data from stq In 920 LHQ is written to once pgen valid not resolved In scenario where load is pgen valid and was cancelled due to eg bank conflict it will be repicked without accessing the STQ Any event where a new older ST is issued later there will be stnuke.

##### 4.9.1.2 STQ Hazard Blocking

When the result pipe load encounters a STQ hazard condition it must be canceled and put to sleep in LMQ The following table summarizes the STQ hazard cases and the corresponding wakeup condition that STQ sends to LMQ to set the load as ready to repick.
| Priority | Load Type | STQ Entry Type | STQ Hazard Case | STQ Wakeup Condition |
| --- | --- | --- | --- | --- |
| 1 | WB load | Any | MDB megavld1 and load YOST pointer outside STQ sliding window | YOST store obtains PA |
| 2 | WB load | Any | MDB megavld1 and any older store has unknown PA | Youngest unknown PA store obtains PA |
| 3 | WB load | Any | MDB megavld0 any delta123 store has unknown PA | Youngest of delta123 stores with unknown PA obtains PA |
| 4 | LDSTX | STXR STLXR | STXRSTLXR store uop did not yet obtain passfail result for its load uop | STXRSTLXR obtains its passfail result |
| 5 | WB load | WB store | Load has STQ multihit and youngest matching store does not fully cover load |  |
| 6 | LDXR LDAXR LDAT | Any store | LDXRLDAXRLDAT matches cacheline PA of older store | Youngest matching store deallocated from STQ |
| 7 | WB load | STXR STLXR | Load matches older STXRSTLXR | Youngest matching STXRSTLXR deallocated from STQ |
| 8 | WB load | CMO | Load matches cacheline PA of older CMO instruction | Youngest matching CMO deallocated from STQ |
| 9 | WB load | WB store with no data | Load matches older store with no data | Youngest matching store obtains store data |

When the result pipe load has a STQ CAM match with no STQ hazard condition the STQ must forward store data to the load The STQ selects the youngest program order STQ entry from the STQ CAM match vector to provide the store data.
The STQ supports Storeload forwarding for mis align in case that the load matches both MA1 and MA2 components of same STQ entry Store in a stq entry will be split to st1 and st0 if its cross 16B and both st1 and st0 can forward data to a younger load.

#### 4.9.2 ST PA STQ

In 920 the STR Retirement is done from ST PA STQ.
| STQ | Comment |  |
| --- | --- | --- |
| Entries | 40 | DSE define 324048 |
| Entry width | 305 bits |  |
| PA | 2R 2W | RD R4 STA retire0 |

RD R4 STA retire1
WR E3 IEX sta0 issue.
WR E3 IEX sta1 issue.
| TOTAL SIZE | 9760 bits |
| --- | --- |

The LTC+ feature is a STQ Requirement feature It is a store prefetch feature which looks up tag TAGC earlier and send miss earlier.
The STQ generate LTC+ before retire When retirement is blocked And there are some barrier ahead So STQ generate LTC+ at R6 Separate pipe from STQ retirement LTC+ is droped.

#### 4.9.3 STQ Sliding Window Configuration

STQ sliding window management has 2 modes as shown in the table below.
The STQ mode is software programmable using the STQMODE[10] configuration register.
| STQMODE[10] Config Reg | STQ Sliding Window Mode | Description |
| --- | --- | --- |
| 01 | 2-thread Static | STQ static partition 20 entries for each thread |
| 1x | 1-thread Static | STQ static partition all 40 entries for T0 no entries for T1 |

In 1-thread static and 2-thread static modes the number of STQ entries assigned to each thread is fixed.

##### 4.9.3.1 STQ Sliding Window Management

To manage the sliding windows the STQ tracks the following state for each thread.
| PerThread STQ State for Sliding Window Management | Description |
| --- | --- |
| STQTxOLDESTSID | Oldest SID in the sliding window for each thread Resets to zero and increments on each STQ deallocate Not affected by flush |
| STQTxNUMPGRP[30] | Current number of 4-entry STQ physical groups max 8 assigned to the sliding window for each thread The current STQTxWINSIZE number of store uops in the sliding window equals 4 STQTxNUMPGRP droped |
| STQTxDEALLOCCNT[40] | Counter that starts at STQTxWINSIZE1 max 31 and decrements on each STQ deallocate When the counter reaches zero the next decrement is called STQTxWINDONE and restarts the counter at STQTxWINSIZE1 droped |

The STQ also tracks the following state across both threads droped.
| STQ State for Sliding Window Management | Description |
| --- | --- |
| STQEFFCNT[160] | STQ efficiency counter that tracks the balance of STQ efficiency measurements across T0 and T1 |

Reset to zero at the start of each STQPERIOD.
Incremented on STQT0WINDONE.
Decremented on STQT1WINDONE.

#### 4.9.4 Store STQ Allocate and DeAllocate

As described in the section on Store STA IEX Issue the IEX LS issue queues only issue a STA instruction if the first uop SID is inside the LSUspecified STQ sliding window for the appropriate thread This means STQ always has an entry available to receive an issued STA instruction.
As shown in the section on Store Pipelines and Datapaths there is an E2 stage store PA mux that selects between the issued STA PA and the STQ repicked STA PA This mux feeds the STQ PA array write ports stqastqb The STQ repicked STA has priority over the issued STA when writing the STQ PA array.
After a STD uop has STD retired from STQ to SCB its STQ entry is no longer needed and can be deallocated The STD retire operation increments the STD retire pointer past the SID of the retired STD uop This advances the STQ sliding window of the appropriate thread that is sent to the IEXFSU issue queues This allows IEXFSU to issue younger STA instructions and STD uops to reuse the deallocated STQ entries.

#### 4.9.5 Store STQ Reissue

The STQSAGB is used to capture stores which didnt resolve and make them available to reissue STQ also is used to capture stores which didnt resolve and make them available to reissue.

#### 4.9.6 Store STA Retire

A store must advance to SCB before it can write to the DC DATA array or send a request to L2C The process of moving store addresses STA uops from STQ to SCB is called STA retire Store addresses are STA retired in program order within each thread at R4 stage The STQ maintains a STA retire pointer for each thread that tracks the next SID to STA retire This pointer is incremented by the number of STA uops retired each cycle The basic requirements for STA retire are shown below Additional requirements are based on the instruction type.
| 1 | The STA uop RID must be older than or equal to the NOFLUSHRID and each uop of the store instruction must obtain a PA with no fault detected |
| --- | --- |
| 2 | The SCB address array SCA must have a free entry to allocate or already contain a valid entry with samecacheline PA[486] into which this STA uop can merge |

The STA retire operation is twowide The STQ has four AFIFO and two STA head flops which can hold a copy of the six oldest STA uops from one thread or mix of difference thread Different from lx920 the STA head only can service one thread at the same time The STA retire flow is shown in the following figure.
At R2 stage the STQ will reload next oldest ready has get pa STA from two thread to available AFIFO At R3 stage the AFIFO checks whether the up to two oldest STA uops for one thread satisfy requirement 1 above Based on this result the STQ a select the next two STA uops to load into available STA head flops At R3 stage Each STA which need to write head flop also CAMs the SCB address array SCA to check for a cacheline PA[486] match for requirement 2 In the next cycle at R4 stage each STA head flop checks whether instructionspecific requirement are satisfied On a match the STA uop can STA retire by merging into the matching entry SCAMERGE If no match the STA uop must wait for a free SCA entry that it can allocate SCAALLOC If both STA head flops are occupied by the same thread the second oldest STA uop head1 cannot STA retire before the oldest head0 At R5 the retirement is completed to SCA.
In the SCAMERGE case the SCA entry FSMs are not changed In either case a pointer to the SCA entry that received the STA uop is saved in the corresponding STQ entry This SCA pointer is needed by the STD retire operation described in the next section.

#### 4.9.7 Store STD Retire

The process of moving store data STD uops from STQ to SCB is called STD retire STD Retire takes place after STA retire.
The STD uops are STD retired in program order within each thread at R4 stage The STQ maintains a STD retire pointer for each thread that tracks the next SID to STD retire This pointer is incremented by the number of STD uops retired each cycle The basic requirements for STD retire are shown below.
| 1 | The STD uop was issued by IEXFSU and written to STQ data array |
| --- | --- |
| 2 | The STA uop corresponding to this STD uop has already STA retired to an SCA entry |
| 3 | The SCB data array SCD must have a free entry to allocate or the SCA entry already has a SCD entry attached for the appropriate halflane into which this STD uop can merge |

The STD retire operation is twowide The STQ has two DBUF STD buffer flops for each thread which can hold a copy of both the two oldest STD uops from each thread The STQ has two STD head flops which can hold a copy of either the two oldest STD uops from one thread or the oldest STD uop from each thread STD uops are loaded from DBUF into STD head flops at R3 stage in the same sequence as STA uops in the STA retire operation In the next cycle at R4 stage each STD head flop detects when the STD retire requirements are satisfied Once requirements 2 is satisfied the STQ entry obtains a pointer to the SCA entry for the STD uop If this SCA entry has the SCD entry attached for the same halflane as the STD uop the STD uop can merge its data into this SCD entry SCDMERGE If not the STD uop must wait for a free entry that it can allocate SCDALLOC If both STD head flops are occupied by the same thread the second oldest STD uop head1 cannot STD retire before the oldest head0 The basic STD retire flow is shown in bellow.
Open Issue The STD retire takes place at least one cycle after STA retire to SCB So this should be R6 head stage In the SCDALLOC case the STD uop must setup the initial state of the SCDFSM for its halfline in the SCA entry The initial state can be dirtylocal DL or dirtyownership DO based on the SCAFSM state If the SCAFSM is in HITE or HITM states the SCDFSM starts at DO state Otherwise the SCDFSM starts at DL state In the SCDMERGE case the SCDFSM typically maintains its current state.

#### 4.1.0 SCB Store Coalescing Buffer pangyachuan

LSU has a flopbased Store Coalescing Buffer SCB shared by 2 threads which merges the data from stores of the samecacheline before writing to the L1 Data Cache DC The goal of SCB is to reduce the number of DC writes for stores Stores are retired in program order from STQ to SCB after their RID enters the noflush window of ROB The retire operations for store addresses STA and store data STD are decoupled Up to two STA and two STD can retire from STQ to SCB each cycle SCB can also merge store data into DC fills from L2C saving an extra DC write and provide store data to loads But not provide data for copyback snoops anymore.
The SCB consists of two components the SCA address array and SCD data array Each SCA entry tracks the address and MESI state of one cacheline as well as the data state and pointers for up to 2 SCD entries Each SCD entry holds 32B of store data and its bytemask.
In 930 we keep SCB data width 32B The ECC is kept the same at 4B32bits data + 7 bits ECC The SCB tag RD port is still one port The max DC WR width is 232B and SCB do not support DC read except atomic instruction.
The SCB writes to DC in 8B Granule The 32B is the max WR bandwidth The data access is based on the 4B granule The SCB access physical bank based on the store data size The physical bank is 8B granule memory data size is 256X64excluding ECC For the data size < 8B the SCB perform RD modify WR before writing to DC For example the store data size is 1 B it need to do RMW And access size is 8B In the confliction calculation it accesses a 8B bank.
In 930 the TAG is replicated 4 times per LoadC and in StoreR.
In 930 SCB is replicated partitioned as follows.
SCA TAG shard by 3 Load SCA TAG is replication of some of the SCA information tasks.

- CAM Match
- Forward able

SCA in Storeretired pipe.
SCD in Storeretired pipe.

##### 4.1.0.1 Local Load SCA TAG Address Array

The Load SCA TAG is a VAPA array The VAPA array is a regular datapath structure with readwriteCAM ports The partical VA is used to cam Load and the full PA is used to qualify the VA cam resultThe following tables show the structure and entry format of the SCA.
| SCA ltag | Comment |  |
| --- | --- | --- |
| Entries | 14 |  |
| Entry width | 72 bits | VA3012 + PA array486 + status array10 |
| Ports | 1W 1C | WR STQ STA retire r5 |

CAM load pipe E1 cam VA E2 cam PA.
| SCD ltag | Comment |  |
| --- | --- | --- |
| Entries | 16 |  |
| Entry width | 5 bits | SCAPTR4bits pa5 |
| Ports | 1W 1C | WR STQ STA retire r5 |

CAM load pipe E1 cam VA E2 cam PA.
| TOTAL SIZE | 1088 bits | 1440 bits arrays |
| --- | --- | --- |

##### 4.1.0.2 Store Retire SCA Address Array

The SCA is organized as two subarrays the PA array and the status array The PA array is a regular datapath structure with readwriteCAM ports while the status array consists of control logic and FSMs to track store state transitions across STQ retire DC readwrite and L2C fillupgradesnoop events The SCA also includes an age matrix to track entry allocation age and prevent starvation of DC TAGDATA requests and L2C requests The following tables show the structure and entry format of the SCA.
| SCA | Comment |  |
| --- | --- | --- |
| Entries | 14 |  |
| Entry width | 120 bits | PA array + status array |
| Ports | 1R 1W 1C | RD DC TAG check |

RD DC DATA read
RD DC DATA write
RD L2C request
WR STQ STA retire
CAM STQ STA retire.
CAM C7 snoop
| TOTAL SIZE | 1680 bits | 1440 bits arrays |
| --- | --- | --- |

##### 4.1.0.3 SCD Data Array

The SCD is organized as two subarrays the data array and the status array and banked by pa[6] The data array is a regular datapath structure with readwrite ports while the status array consists of control logic to detect starvation of DC DATA requests The SCD also includes an age matrix to track entry allocation age and prevent starvation of DC DATA requests The following tables show the structure and entry format of the SCD.
| SCD | Comment |  |
| --- | --- | --- |
| Entries | 16 |  |
| Entry width | 302 bits | data array256bits data + 32 bits bytemask + 1-bit psn + status array13bits |
| Ports | 10R 3W | RD E2 pipe012 load forward 512bits data + 64bits bm |

RD C8 fill merge or I2 DC DATA write0 512bits data + 64bits bm.
RD CBC2 wrop data to L2 512bits data + 64bits bm.
WR STQ STD retire r4 25256bits data + 232bits bm.
WR DC DATA read 256bits data + 4bits dwordmask.
| TOTAL SIZE | 4832 bits | 2640 bits arrays |
| --- | --- | --- |

##### 4.1.0.4 Load SCB CAM Read

At E2 stage of the load result pipe the load uses its PA[485] and size to CAM the SCB PA arrays to get the data in SCB data arrays.

##### 4.1.0.5 Store DC TAG Check

When the STA retire operation allocates a new SCA entry SCAALLOC the SCA entry FSM transfer to READYDCTAG state if the STA uop is a cacheable WB store The SCA entry must check the DC TAG to determine if the cacheline is a hit and its MESI state SCB has a DC TAG check picker to select the oldest ready SCA entry in FSM READYDCTAG state The selected SCA entry sends its request and PA to the DC TAG tagc arbiter and sets its REQFSM state to WAITDCTAG After the DC TAG arbiter selects the SCB request and returns the result the SCA entry FSM starts tracking the cacheline MESI state.

#### 4.1.1 Watchpoint Registers

| 5 | Special Instructions Flows |
| --- | --- |

This chapter describes the LSU special instruction flows.
Special instructions are either System instructions or User instructions.
The system instructions include.
Memory Barriers DMB DSB ESB.
Addresss Translate AT.
TLB Invalidate TLBI.
Cache Maintenance
User instructions are all the rest AcquireRelease Exclusive Non Temoral Software Prefetch Atomic operations Data Gathering Hint.

### 5.1 Global definitions

Global observation.

- Loads write to LHQ execution is done.
- Stores SCB commit NC DV Far atomic complete.

Bypass memory operation younger in program order is globally observed before older memory operation in program order.
Bypass is allowed for.

- Stores can bypass.

Stores to different locations.

- Loads can bypass.

Stores to different locations.
Loads to different locations.
Complete execution is complete.

### 5.2 System Instructions Memory Barriers DMB DSB ESB

LSU supports 3 types of barrier instructions DMB DSB and ESB The general requirement for barrier instructions is that all memory access instructions before the barrier in program order must be globally ordered before any of the memory access instructions after the barrier in program order are globally ordered Refer to the Arm specification for complete details on barrier requirements.

#### 5.2.1 DMB Data Memory Barrier Requirements

The DMB instruction is a memory barrier instruction that ensures the relative order of memory accesses before the barrier and memory accesses after the barrier The DMB instruction DOES NOT ensure the COMPLETION of any of the memory accesses for which it ensures relative order thus local ordering of memory operations within the PE that executes the DMB instruction is NOT required.
DMB instruction insures that all memory operations loads stores that are older than DMB in program order are Globally Observed GO before all memory operations that are younger than DMB in program order.
There are options that define which memory operations loads store both should be ordered in relation to which memory operations For the complete list of options please see Memory barriers section in ARMARM document.

#### 5.2.2 DSB Data Synchronization Barrier Requirements

A DSB instruction is a memory barrier that ensures that memory accesses that occur before the DSB instruction have completed before the completion of the DSB instruction In doing this it acts as a stronger barrier than a DMB and all ordering that is created by a DMB with specific options is also generated by a DSB with the same options.
As opposed to DMB instruction DSB enforces COMPLETION serialization such that all older memory operations are guaranteed to complete before DSB operation is complete and DSB is guaranteed to complete before all younger memory operations are complete.
Execution of a DSB instruction.
At EL2 ensures that any memory accesses caused by Speculative translation table walks from the EL10 translation regime have been observed.
At EL3 ensures that any memory accesses caused by speculative translation table walks from the EL2 EL10 or EL20 translation regimes have been observed.
In addition to memory accesses DSB instruction also guarantees the following when it completes.
If the required access types of the DSB is reads and writes then all cache maintenance instructions all TLB maintenance instructions and all PSB CYNC instructions issued by PEs before the DSB are complete for the required shareability domain.
In addition no instruction that appears in program order after the DSB instruction can alter any state of the system or perform any part of its functionality until the DSB completes other than.
Being fetched from memory and decoded.
Reading the generalpurpose SIMD and floatingpoint Specialpurpose or System registers that are directly or indirectly read without causing sideeffects.
The above restriction means that all younger instructions coming after DSB in program order are execution serialized to the completion of DSB This is a hard requirement that determines that LSU cannot see these uops will not be issued any younger than DSB memory operations before DSB is complete.
Like with DMB instruction there exists a list of options for the instruction as to which operations should be ordered in relation to which operations For the complete list of operations please refer to Memory barriers section of the ARMARM document.

#### 5.2.3 LSU Behavior

The following table summarizes the LSU behavior for each type of barrier.
| Execution Phase | Instruction | LSU Behavior |
| --- | --- | --- |
| OOO Decode | DMB | No special handling |
| DSBESB | OOO blocks decode of younger instructions in program order after decoding DSBESB |  |

OOO unblocks decode on the AGEN RESOLVE for the DSBESB RID.
| IEX STA Issue | DMBDSBESB | No TLB translation for barrier instructions |
| --- | --- | --- |

No AGEN RESOLVE sent to ROB on issue flow.
| IEX STD Issue | DMBDSBESB | No STD issue for barrier instructions |
| --- | --- | --- |

No STD RESOLVE sent to ROB for barrier instructions.
| STQ STA Retire Requirements | DMBDSBESB DXB | DXBRID NEXTCOMMITRID implies all older loads have returned data and resolved |
| --- | --- | --- |

DXBRID NOFLUSHRID implies OOOIEX cannot flush this DXBRID except for the ESB flush special case below.
DXB reached older of two STA head retire slots implies older STQ entries STA retired.
STAretireSID STDretireSID implies older STQ entries STD retired.
All WB stores in SCB are globally ordered.
No valid NCDEVNT stores or TBLIICRAMIDXCMO instructions in SCB.
| STQ NDCPEND Check | DMB | NDCPEND is a 1-bit FSM in STQ to track pending NCDEVNT stores CMOs and FAR atomics |
| --- | --- | --- |

NDCPEND0 No NCDEVNT store CMO or FAR atomic was sent to L2C since previous barrier request was sent to L2C.
NDCPEND1 No barrier request was sent to L2C since previous NCDEVNT store CMO or FAR atomic was sent to L2C.
When Fast DMBs are enabled sysdisdmbfast0 LSU only needs to send a DMB request to L2C when NDCPEND1 Otherwise LSU has enough local information to enforce the DMB requirements.
| STQ STA Retire | Fast DMB | Fast DMB sysdisdmbfast0 and NDCPEND0 |
| --- | --- | --- |

After satisfying STA retire requirements drop DMB from STQ STA head do not STA retire DMB to SCB.
Inform store issue pipe to send AGEN RESOLVE to ROB for NEXTCOMMITRID.
When dmb resolve inform LHQ to nuke stale loads regardless of address.
| Slow DMB | Slow DMB sysdisdmbfast1 or NDCPEND1 |
| --- | --- |

After satisfying STA retire requirements STA retire DMB to free SCB SCA entry.
Advance STAretireSID.
| DSBESB | After satisfying STA retire requirements STA retire DSBESB to free SCB SCA entry |
| --- | --- |

Advance STAretireSID.
| STQ NDCPEND Update | DMBDSB | Clear NDCPEND bit on DMBDSB STA retire |
| --- | --- | --- |
| ESB | Do not change the NDCPEND state on ESB STA retire since L2C does not send the ESB barrier to L3 |  |
| STQ STD Retire | DMBDSBESB | After STAretireSID advances past the barrier by STA retire or STA drop drop barrier from STQ STD head do not STD retire barrier to SCB |

Advance STDretireSID.
Deallocate STQ entry.
| SCB SCA Barrier Request to L2C | Slow DMB DSBESB | Send DMBDSBESB request to L2C |
| --- | --- | --- |

Resend the request as needed after RSB reject until RSB accepted.
Wait for L2C to return ackbar to LSU.
Inform store issue pipe to send AGEN RESOLVE to ROB for NEXTCOMMITRID.
Inform LHQ to nuke stale loads regardless of address.
Deallocate SCA entry no SCD entries attached since no STD retire for barriers.
| ESB Flush | ESB | A special case for ESB is that after LSU sends AGEN RESOLVE to ROB for ESB OOO is allowed to flush the ESB RID even though ESBRID NOFLUSHRID at this point |
| --- | --- | --- |
| Younger Load Executing Before Barrier Resolved | DMB |  |

In optimized DMB mode younger WB loads are allowed to issue send requests to L2C return data and resolve before an older DMB is resolved.
As usual such younger WB loads are allocated to LHQ when resolved and marked stale on a matching snoop invalidate.
When STQ for fast DMB or SCB for slow DMB informs the store issue pipe to send AGEN RESOLVE to ROB it also informs LHQ to nuke stale loads regardless of address match.
| DSBESB | Since OOO blocks decode of younger instructions in program order after DSBESB until receiving the AGEN RESOLVE for DSBESB it is not possible for younger loads to execute before DSBESB is resolved |
| --- | --- |

### 5.3 Address Translate AT Requirements

#### 5.3.1 Address Translate AT Requirements

AT instructions return the result of translating an input address supplied as an argument to the instruction using a specified translation stage or regime.
The available instructions only perform translations that are accessible from the Security state and Exception level at which the instruction is executed That is.

- No instruction executed in Nonsecure state can return the result of a Secure address translation stage.
- No instruction can return the result of an address translation stage that is controlled by an Exception level that is higher than the Exception level at which the instruction is executed.

Assembly syntax of AT instructions is.
AT <operation> <Xt>.
Xt is the register that contains the VA to be translated while operation is composed of <stages><level><read|write><pan>.
Stages

- S1 Stage 1 page tables translation.
- S12 stage 1 followed by stage 2 translation.

Level

- E0 EL0
- E1 EL1
- E2 EL2
- E3 EL3

NOTE If level is higher than currently executing EL instruction is UNDEFINED.
Read Write

- R Read
- W Write

Pan

- Only available when FEATPAN2 is implemented Optional but if present.
- P Determines action based on value of PSTATEPAN.
- Only permitted for <stages>S1 and <level>E1.

Valid option is any of S1E1R S1E1RP S1E1W S1E1WP S1E0R S1E0W S12E1R S12E1W S12E0R S12E0W S1E2R S1E2W S1E3R or S1E3W.
If the address translation is successful the resulting output address is returned in PAREL1PA and PAREL1F is set to 0 to indicate that the translation was successful.
In case address translation is not successful translation induced fault has occurred the exception is not signaled for the AT instruction but rather documented in PAREL1FST field that holds the Fault status information In these cases the PAREL1PA field does not hold the output address of the translation.
For the complete list of applicable synchronous and asynchronous faults that can be generated following an AT instruction please see detailed description in Synchronous faults generated by address translation instructions section of ARMARM document.
Because AT address translation instructions result in a system register update non renamed register update explicit synchronization must be performed before the result is guaranteed to be visible to subsequent direct reads of the PAREL1 This means that it is not defined that AT should generate serialization on its own and that SW should create such serialization via usage of DSB instruction.

### 5.4 TLB Invalidate TLBI

#### 5.4.1 TLBi Requirements

TLB Translation Lookaside Buffer Invalidation.
The TLBI instruction is used to invalidate entries in the TLBs The syntax of this instruction is.
TLBI < type >< level >IS|OS < xt >.
Where
Type Which entries to invalidate.

- All All entries.
- VA Entry matching VA and ASID in Xt.
- VAA Entry matching VA in Xt for any ASID.
- ASID Any entry matching the ASID in Xt.
- IPA Intermediate Physical Address can be used only to invalidate stage 2 entries.
- VM VMID
- R invalidation by address range Address can be given in IPA VA and must match ASID be global Range invalidation would invalidate any address that.

The entry is within the address range determined by the formula [BaseADDR < VA < BaseADDR + NUM +125SCALE +1 TranslationGranuleSize]
Range invalidation is present only when FEATTLBIRANGE is implemented.
Level Which address space to operate on.

- E1 EL01 virtual address space.
- E2 EL2 virtual address space.
- E3 EL3 virtual address space.

IS|OS Whether an operation is Inner Shareable IS or Outer Shareable OS.
Xt Which address or ASID to operate on Only used for operations by address or ASID.
For a complete list of all applicable options and detailed description please see TLB maintenance instructions section in ARMARM document.
TLB invalidation is needed if one of the following is desired.
Unmap an address Take an address that was previously valid or mapped and mark it as faulting.
Change the mapping of an address Change the output address or any of the attributes For example change an address from readonly to readwrite permissions.
Change the way the tables are interpreted This is less common But for example if the granule size was changed then the interpretation of the tables also changes Therefore a TLB invalidate would be necessary.
It is important to stress that while using TLBI SW is changing system state that is non renamable To insure proper ordering between TLBI instruction and younger older instructions in program order SW is expected to use proper barrier serializing instructions An example to typical SW sequence for TLBI usage is.

### 5.5 Cache Maintenance

#### 5.5.1 Cache Maintenance Requirements

It is sometimes necessary for software to clean or invalidate a cache for various reasons not in the scope of this document.
When performing cache maintenance operation there are 3 types of operations that apply.
Invalidation of a cache or cache line means to clear it of data by clearing the valid bit of one or more cache lines The cache must always be invalidated after reset as its contents are undefined This can also be viewed as a way of making changes in the memory domain outside the cache visible to the user of the cache.
Cleaning a cache or cache line means writing the contents of cache lines that are marked as dirty out to the next level of cache or to main memory and clearing the dirty bits in the cache line This makes the contents of the cache line coherent with the next level of the cache or memory system This is only applicable for data caches in which a writeback policy is used This is also a way of making changes in the cache visible to the user of the outer memory domain but is only available for data cache.
Zero This zeroes a block of memory within the cache without the need to first of all read its contents from the outer domain This is only available for data cache.
The structure of cache maintenance instructions is.
<cache> <operation> <Xt>.
Cache
DC Data Cache
IC Instruction Cache.
Operation
All All means the entire cache and is not available for the data or unified cache.
VA Invalidate cache line according to its Virtual Address.
SW Invalidate a cache line according to its Set+ Way.
C prefix Clean
I invalidate
U PoU Point of Unification cache where code and data are unified typically L2.
C suffix PoC point of Coherency point at which all observers for example cores DSPs or DMA engines that can access memory are guaranteed to see the same copy of a memory location Typically this is the main external system memory.
Xt register that contain VA set+way information.
Following table summarizes the above.
Important notes
No alignment requirements apply to VA Any VA will be round down to cache line base address.
DC invalidation by VA DC IVAC requires write permissions else permission fault is generated.
All cache maintenance instructions can execute in any order relative to other instruction cache maintenance instructions data cache maintenance instructions and loads and stores unless a DSB is executed between the instructions.
Data cache operations other than DC ZVA that specify an address are only guaranteed to execute in program order relative to each other if they specify the same address.
Those operations that specify an address execute in program order relative to all maintenance operations that do not specify an address.
Cache line zeroing behaves in a similar fashion to a prefetch in that it is a way of hinting to the processor that certain addresses are likely to be used in the future However a zeroing operation can be much quicker as there is no need to wait for external memory accesses to complete Instead of getting the actual data from memory read into the cache you get cache lines filled with zeros It enables hinting to the processor that the code completely overwrites the cache line contents so there is no need for an initial read.
The caches must be disabled at the start of the sequence to prevent the allocation of new lines midsequence If the caches were exclusive a line could migrate between levels.

#### 5.5.2 ICache Maintenance IC

Any cache maintenance instruction operating by VA includes as part of any required VA to PA translation.

- For an instruction executed at EL1 or at EL2 when HCREL2E2H1 the current ASID.
- The current Security state.
- Whether the instruction was executed at EL1 or EL2.
- For an instruction executed at EL1 the current VMID.

That VA to PA translation might fault However for an instruction cache maintenance instruction that operates by.
VA

- It is IMPLEMENTATION DEFINED whether the instruction can generate.
- An Access flag fault.
- A Translation fault.
- The instruction cannot generate a Permission fault except for.
- The possible generation of a Permission fault by the execution of an IC IVAU instruction at EL0 when.

The specified address does not have read access at EL0.

- The possible Permission fault on a Stage 2 fault on a stage 1 translation table walk.

#### 5.5.3 DCache Maintenance DC

If a data cache maintenance by setway instruction specifies a set way or level argument that is larger than the value.
Supported by the implementation then the instruction is CONSTRAINED UNPREDICTABLE.
Any cache maintenance instruction operating by VA includes as part of any required VA to PA translation.

- For an instruction executed at EL1 or at EL2 when HCREL2E2H is 1 the current ASID.
- The current Security state.
- Whether the instruction is executed at EL1 or EL2.
- For an instruction executed at EL1 the current VMID.

That VA to PA translation might fault However a data or unified cache maintenance instruction that operates by.
VA cannot generate a Permission fault except in the following cases.

- The possible generation of a Permission fault by.
- The execution of a DC IVAC instruction when the specified address does not have write permission.
- The execution of an enabled DC instruction at EL0 when the specified address does not have read.

Access at EL0
The description of Permission faults includes possible constraints on the generation of Permission faults on.
Cache maintenance by VA instructions.

- The possible Permission fault on a Stage 2 fault on a stage 1 translation table walk.

When executed at EL1 a DC ISW instruction performs a clean and invalidate meaning it performs the same.
Maintenance as a DC CISW instruction if all of the following apply.

- EL2 is implemented and enabled in the current Security state.
- Either
- The value of HCREL2SWIO is 1 forcing a cache clean to perform a clean and invalidate.
- The value of HCREL2VM is 1 meaning EL10 stage two address translation is enabled.

When executed at EL1 a DC IVAC instruction performs a clean and invalidate meaning it performs the same.
Maintenance as a DC CIVAC instruction if all of the following apply.

- EL2 is implemented and enabled in the current Security state.
- The value of HCREL2VM is 1 meaning EL10 stage two address translation is enabled.

#### 5.5.4 RAMINDEX

[internal reference removed]

### 5.6 AcquireRelease LDAR STLR

LSU supports the LoadAcquire LDAR and StoreRelease STLR halfbarrier instructions The general requirement for LoadAcquire is that its load component must be globally ordered before any of the memory access instructions after the LoadAcquire in program order are globally ordered The general requirement for StoreRelease is that all memory access instructions before the StoreRelease in program order must be globally ordered before the store component of the StoreRelease is globally ordered Additionally for the case of a LoadAcquire after StoreRelease in program order the store component of the StoreRelease must be globally ordered before the load component of the LoadAcquire is globally ordered Refer to the Arm specification for complete details on AcquireRelease requirements.

#### 5.6.1 LDAR Requirements Load Acquire Register Requirements

The basic principle of both LoadAcquire and LoadAcquirePC instructions is to introduce order between.
The memory access generated by the LoadAcquire or LoadAcquirePC instruction.
The memory accesses appearing in program order after the LoadAcquire or LoadAcquirePC instruction such that the memory access generated by the LoadAcquire or LoadAcquirePC instruction is Observedby each PE to the extent that the PE is required to observe the access coherently before any of the memory accesses appearing in program order after the LoadAcquire or LoadAcquirePC instruction are Observedby that PE to the extent that the PE is required to observe the accesses coherently.
The above means that all younger memory operations will be globally observed AFTER LDAR is globally observed In other words all memory operations cannot bypass LDR.

#### 5.6.2 STLR Requirements Store Release Resigster Requirements

The basic principle of a StoreRelease instruction is to introduce order between the following.
A set of memory accesses RWx that are generated by the PE executing the StoreRelease instruction and that appear in program order before the StoreRelease instruction together with those that originate from a different PE to the extent that the PE is required to observe them coherently Observedby the PE before executing the Storerelease.
The memory access generated by the StoreRelease Wrel such that all of the memory accesses RWx are Observedby each PE to the extent that the PE is required to observe those accesses coherently before Wrel is Observedby that PE to the extent that the PE is required to observe that access coherently.
The above means that all older memory operations will be globally observed BEFORE STLR is globally observed.
In addition if STLR is followed by a younger LDAR in program order STLR is guaranteed to be globally observed BEFORE LDAR is globally observed.

#### 5.6.3 LoadAcquire LDAR Implementation

The following table summarizes the LSU behavior for LoadAcquire instructions.
| Execution Phase | Instruction | LSU Behavior |
| --- | --- | --- |
| IEX Issue | LDAR | LDAR is allowed to execute speculatively |

Device LDAR does not send AGEN RESOLVE to ROB This prevents NOFLUSHRID from advancing past the LDAR and younger loads so that younger stale loads can still be nuked when LDAR resolves.
| Younger LDAR vs Older STLR | LDAR | At E2 stage if there is older STLR in stq LDAR is cancelled and put to sleep in LIQ until its STLR deallocate |
| --- | --- | --- |

At E2 stage LDAR also checks if any pending STLR exists in SCB If yes then LDAR is cancelled and put to sleep until that STLR is globally ordered.
Similarly an NCDEV LDAR in LIQ cannot send its request to L2C until ordered and no pending STLR exists in SCB in addition to standard NCDEV request ordering conditions.
| Younger Load Executing Before LDAR Resolved | LDAR | Similar to optimized DMB mode younger WB loads are allowed to issue send requests to L2C return data and resolve before an older LDAR is resolved |
| --- | --- | --- |

Ldar set LFB mergeable to 0.
Unresolved ldar set ld snp nuke to keep order with resolved ld do not need to match PA.

#### 5.6.4 StoreRelease STLR Implementation

The following table summarizes the LSU behavior for StoreRelease instructions.
| Execution Phase | Instruction | LSU Behavior |
| --- | --- | --- |
| IEX STA Issue | STLR | AGEN RESOLVE sent to ROB on STLR issue flow |
| IEX STD Issue | STLR | Send STD RESOLVE to ROB for STLR like a regular store |
| STQ STA Retire Requirements | STLR | STLRRID NEXTCOMMITRID implies all older loads have returned data and resolved |

STLRRID NOFLUSHRID implies OOOIEX cannot flush this STLRRID.
STLR reached older of two STA head retire slots implies older STQ entries STA retired.
STAretireSID STDretireSID implies older STQ entries STD retired.
Chk bit on All stores in SCB are globally ordered otherwise do not need scb go.
| STQ NDCPEND Check | STLR | NDCPEND is a 1-bit FSM in STQ to track pending NCDEVNT stores CMOs and FAR atomics |
| --- | --- | --- |

NDCPEND0 No NCDEVNT store CMO or FAR atomic was sent to L2C since previous barrier request was sent to L2C.
NDCPEND1 No barrier request was sent to L2C since previous NCDEVNT store CMO or FAR atomic was sent to L2C.
LSU only needs to send a prebarrier DMB request to L2C when NDCPEND1 Otherwise LSU has enough local information to enforce the STLR requirements.
| STQ STA Retire | Fast STLR | Fast STLR NDCPEND0 |
| --- | --- | --- |

After satisfying STA retire requirements STA retire STLR to free SCB SCA entry with prebarr40 do not trigger SCB prebarrier DMB request.
Advance STAretireSID.
| Slow STLR | Slow STLR NDCPEND1 |
| --- | --- |

After satisfying STA retire requirements STA retire STLR to free SCB SCA entry with prebarr41 to trigger SCB prebarrier DMB request.
Advance STAretireSID.
| STQ NDCPEND Update | STLR | Clear NDCPEND bit on STLR STA retire |
| --- | --- | --- |
| SCB PreBarrier DMB Request to L2C | Slow STLR | SCB has a 1-entry BARFSM inside SCA array to handle STLR prebarrier DMB requests |

On valid STA retire with prebarr41 as the store component of STLR is STA retired to an SCA entry the prebarrier DMB component of STLR is STA retired to the BARFSM.
BARFSM sends a DMB request to L2C.
BARFSM resends the request as needed after RSB reject until RSB accepted.
BARFSM waits for L2C to return the ackbar to LSU.
While BARFSM is busy.

- SCB blocks wrop SCA entries from sending requests to L2C only the SCA entry for this STLR instruction would have attempted to send a request at this point.

When ackbar is received from L2C.

- Clear wait go bit |.

| --- | --- | --- |
| STQ STD Retire | STLR | STD retire store data to SCB SCD entry like a regular store |

Deallocate STQ entry at STD retire.
| SCB SCASTLR Tracking | All STLR | Each SCA entry has an SCASTLR bit to track an STLR instruction that is not yet globally ordered A younger LDAR instruction that matches an SCA entry with its SCASTLR bit set must be canceled and put to sleep in LIQ until the STLR becomes globally ordered |
| --- | --- | --- |
| WB STLR | In WB STLR case set SCASTLR bit when STLR store data is STA retire |  |

Clear SCASTLR bit once that SCA go.
| NCDEV STLR | In NCDEV STLR case set SCASTLR bit when STLR store data is STA retire |
| --- | --- |

After NCDEV store request to L2C is RSB accepted the STLR SCA entry must send a postbarrier DMB request to L2C.
After postbarrier DMB request is RSB accepted and then LSU receives ackbar from L2C do not clear SCASTLR bit set PBAR bit.
| SCB PostBarrier DMB Request to L2C | NCDEV STLR | In NCDEV STLR case the STLR SCA entry must send a postbarrier DMB request to L2C after the NCDEV store request to L2C is accepted |
| --- | --- | --- |

This is necessary for a potential younger LDAR to determine whether older STLR instructions in program order have been globally ordered.
STLR SCA entry sends a DMB request to L2C.
STLR SCA entry waits for L2C to return the ackbar to LSU.
While wrop request and its postbarrier DMB request are pending SCB blocks all other SCA entries from sending requests to L2C.
When ackbar is received from L2C SCB unblocks SCA wrop requests to L2C.

### 5.7 Exclusives LDXR STXR

LSU supports the LoadExclusive LDXR and StoreExclusive STXR instructions The general behavior is that a program executes LDXR to arm a hardware exclusive monitor with a particular cacheline PA for which the thread or core if singlethreaded has exclusive access If the thread then loses exclusive access to that cacheline PA the monitor transitions from exclusive state to open state After executing some other instructions the program executes STXR to check whether the exclusive monitor is still in exclusive state for the STXR cacheline PA If yes the store component of STXR is executed and STXR returns the passing status value 0 to the STXR destination register If not the store component of STXR is dropped and STXR returns the failing status value 1 to the STXR destination register.
For complete and extensive description of exclusive operations please refer to section Synchronization and semaphores in ARMARM document.

#### 5.7.1 Exclusive Mechanism load/store Exclusive Register Requirements

Basic mechanism of exclusive operations relies on a monitor setting and reading mechanism.
LDXR Load instruction that functions as a regular load but unlike a regular load will arm a monitor for the VA of the load instruction The monitor is armed per implementation defined memory block size current 910 implementation defines block size cache line size.
STXR Store instruction that will conditionally complete depending on the state of the aforementioned monitor If the monitor for the block accessed by the STXR instruction is armed store will complete like a regular store but unlike a regular store will also write back a success indication value of 0 to the destination register In the case the monitor is not armed store will abort and will write back abort indication value of 1 to the destination register.
CLREX instruction to clear disarm the exclusive monitor.
NOTE Arm architecture defines two types of monitors global monitor influenced by other PEs and local monitor influenced ONLY by PE that executes exclusive sequence LinxCore implements a single physical monitor that obeys the rules of both local and global monitors.
Armed state of monitor is thus localarmed globalarmed.
FSM to define the local monitor state is as follows.
Open access monitor NOT armed.
Exclusive access monitor armed.
The above state machine implies that.

- The IMPLEMENTATION DEFINED options for the local monitor are consistent with the local monitor being constructed so that it does not hold any PA but instead treats any access as matching the address of the previous LoadExclusive instruction.
- A local monitor implementation can be unaware of LoadExclusive and StoreExclusive instructions from other PEs.
- The architecture does not require a load instruction by another PE that is not a LoadExclusive instruction to have any effect on the local monitor.
- It is IMPLEMENTATION DEFINED whether the transition from Exclusive Access to Open Access state occurs when the Store or StoreExcl is from another observer.

FSM to define the global monitor state is as follows n stating PE that executes exclusive sequence.
Open access monitor NOT armed.
Exclusive access monitor armed.
The above state machine implies that.
The architecture does not require a load instruction by another PE that is not a LoadExclusive instruction to have any effect on the global monitor.
A LoadExclusive instruction can only update the marked shareable memory address for the PE issuing the LoadExclusive instruction.
When the global monitor is in the Exclusive Access state it is IMPLEMENTATION DEFINED whether a CLREX instruction causes the global monitor to transition from Exclusive Access to Open Access state.
It is IMPLEMENTATION DEFINED.

- Whether a modification to a Nonshareable memory location can cause a global monitor to transition from Exclusive Access to Open Access state.
- Whether a LoadExclusive instruction to a Nonshareable memory location can cause a global monitor to transition from Open Access to Exclusive Access state.

#### 5.7.2 LoadExclusive LDXR Implementation

The following table summarizes the LSU behavior for LoadExclusive instructions.
| Execution Phase | Instruction | LSU Behavior |
| --- | --- | --- |
| IEX Issue | LDXR | LDXR is put to sleep in LIQ on first issue when it is not the oldest ldxr and woken up when it becomes the oldest |

Device LDXR does not send AGEN RESOLVE to ROB.
| LMQ Repick | LDXR | LDXR is put to sleep in LIQ if any older STQ entry does not have valid PA and woken up when the youngest of such STQ entries obtains its PA |
| --- | --- | --- |

LDXR is put to sleep in LMQ if any older STQ entry has matching cacheline PA and woken up when the youngest of such STQ entries is deallocated from STQ.
LDXR is put to sleep in LMQ if any store in SCB with matching cacheline PA is not yet globally ordered and woken up when that store becomes globally ordered.
| Request to L2C | WB LDXR | WB LDXR requests RDS are always sent to L2C with reqexcl0 |
| --- | --- | --- |
| NCDEV LDXR | NCDEV LDXR requests RDNCDEV are always sent to L2C with reqexcl1 |  |
| SCB Exclusive Monitors | LDXR | SCB maintains two exclusive monitors |

SMON Speculative Monitor.

- SMON armed at E4 stage LDXR resolve.
- SMON tracks cacheline PA state across matching snoops and clear events.
- SMON has the following states.

IDLE No LDXR execution in progress.
INV Exclusive monitor invalid.
VLD Exclusive monitor valid.

- SMON state discarded return to IDLE on flush.

CMON Committed Monitor.

- SMON state copied to CMON state at LDXR commit time.
- CMON tracks cacheline PA state across matching snoops fills and clear events.
- CMON has the following states.

FAIL Most recent LDXRSTXR failed.
PASS Most recent LDXRSTXR passed.

- CMON state is checked by STXR to determine passfail result.

SMON and CMON are invalidated on the following clear events.
Snoop invalidate matched monitor.
R4 stage CLREX instruction.
R5 stage ERET instruction.
NonSTXR store STA retire cacheline PA matched monitor.
The opposite threads same CL STXR success.
STXR STA retire cacheline PA does not match monitor.
STXR done obtained final passfail result.
Global monitor stupgexcl fail.
| Load Resolve | LDXR | When LDXR returns data and resolves at E4 stage it arms the SMON with its PA attributes and DC waystate if WB LDXR |
| --- | --- | --- |
| Load Commit | LDXR | When LDXR is committed by ROB the current SMON state is copied to the CMON state |

#### 5.7.3 StoreExclusive STXR Implementation

The following table summarizes the LSU behavior for StoreExclusive instructions.
| Execution Phase | Instruction | LSU Behavior |
| --- | --- | --- |
| OOO Decode | STXR | STXR is a store instruction with a destination register like a load |

OOO creates STA and STD components for STXR like it does for every store.
OOO does not create a load uop for STXR like it did on V110.
OOO attaches the STXR destination reg PTAG to the STA component of STXR.
| IEX STA Issue | STXR | IEX issues STXR destination reg PTAG to LSU on iexlsusta01dsttagi2[70] interface |
| --- | --- | --- |

No AGEN RESOLVE sent to ROB on STXR issue flow.
| IEX STD Issue | STXR | Send STD RESOLVE to ROB for STXR like a regular store |
| --- | --- | --- |
| STQ STA Retire Requirements | STXR | STXRRID NEXTCOMMITRID implies all older loads have returned data and resolved |

STXRRID NOFLUSHRID implies OOOIEX cannot flush this STXRRID.
STXR reached older of two STA head retire slots implies older STQ entries STA retired.
After satisfying STA retire requirements STXR allocates a new SCA entry.
STXR is not allowed to STA retire merge into an older SCA entry.
| STQ STD Retire | STXR | STD retire STXR store data to SCB SCD entry like a regular store |
| --- | --- | --- |

Deallocate STQ entry at STD retire.
| CMON to SCA State Transfer | STXR | SCA fsm jump as normal st if no stxrfail |
| --- | --- | --- |

When sca is in idle missidle hitsidle hitmsca harzard if stxr fail can jump to waitdrop.
| Return STXR PassFail Status | LDSTX | STXR is considered done when SCB declares the final STXR passfail status |
| --- | --- | --- |

At this point the passfail status is lockedin CMON PASS or FAIL state and cannot be changed.
LSU internally generates a load uop called LDSTX to return the final passfail status to the STXR destination reg PTAG.
After stxr get the ownership trigger the ldstx immediately at the ldstx e3 if stxr not GO send cancel and then waiting for the stxr done trigger ldstx again.
LIQ use this signal to punch a 2-cycle hole in reissue and repick so that resultpipe can inject the LDSTX uop.
The LDSTX uop has higher priority than an issued load at the resultpipe arbiter.
SCB provides the STXR destination reg PTAG from STA issue time to resultpipe to use on the LSU to IEX I2 stage repick interface.
When the LDSTX flows through resultpipe it cannot be canceled and it will not generate a load resolve to ROB.
| STXR AGEN RESOLVE | STXR | For the last step of STXR execution after the LDSTX uop is injected into resultpipe SCB informs store issue pipe to send AGEN RESOLVE to ROB for the NEXTCOMMITRID |
| --- | --- | --- |

### 5.8 AcquireRelease Exclusives LDAXR STLXR

#### 5.8.1 LDXAR Load Exclusive Acquire Register Requirements

Will perform and have the same semantics as LDXR + LDAR meaning it will function as a load exclusive with acquire semantics.

#### 5.8.2 STXLR Store Exclusive Release Register Requirements

Will perform and have the same semantics as STXR + STLR meaning it will function as a store exclusive with release semantics.

### 5.9 NonTemporal

In general nontemporal also named streaming means that the data loaded stored is not predicted to be used in time proximity to the load store event It is a SW hint that allows HW to make certain assumptions thus optimize the case hinted by the SW.

#### 5.9.1 LDNP STNP Requirements

The load/store Nontemporal Pair instructions provide a hint to the memory system that an access is nontemporal or streaming and unlikely to be repeated in the near future This means that data caching is not required However depending on the memory type the instructions might permit memory reads to be preloaded and memory writes to be gathered to accelerate bulk memory transfers.
In addition there is an exception to the usual memory ordering rules If an address dependency exists between two memory reads and a Load Nontemporal Pair instruction is the younger of the two then in the absence of any other barrier mechanism to achieve order the memory accesses can be globally observed in any order.
If a Load NonTemporal Pair instruction specifies the same register for the two registers that are being loaded then behavior is CONSTRAINED UNPREDICTABLE and one of the following must occur.

- The instruction is treated as UNDEFINED.
- The instruction is treated as a NOP.
- The instruction performs all the loads using the specified addressing mode and the register that is loaded takes an UNKNOWN value.

#### 5.9.2 Load NonTemporal LDNP Implementation

LDNP will affect L1 LRU in L2.

#### 5.9.3 Store NonTemporal STNP Implementation

STNP attempt to send writethrough WRNT requests to L2C instead of RDM miss requests.

#### 5.1.0 Software Prefetch PRFM Prefetch Memory

The prefetch memory instructions signal to the memory system that memory accesses from a specified address are likely to occur in the near future The memory system can respond by taking actions that are expected to speed up the memory access when they do occur such as preloading the specified address into one or more caches Because these signals are only hints it is valid for the PE to treat any or all prefetch instructions as a NOP.
Because they are hints to the memory system the operation of a PRFM instruction.

- Cannot cause a synchronous exception.
- Can cause asynchronous exceptions for example SError in case data corruption on prefetched cahceline or external asynchronous error.

PRFM instructions will.

- Only have an effect on SW visible structures such as caches and TLBs.
- Not access DEVICE memory.
- PLI hint instructions prefetch cannot be executed speculatively.
- Has a special prfop field that is composed of the following sub fields <type><target><policy>.
- Type

PLD Prefetch for load.
PST Prefetch for store.
PLI Preload instructions.

- Target

L1 L1 cache
L2 L2 cache
L3 L3 cache

- Policy

KEEP Allocate normally to cache temporal access.
STRM NonTemporal or Streaming for data that is used only once.

#### 5.1.1 Atomic Operations

Atomic operations include the following.
Arithmetic add set bit clear bit min max xor.
Swap SWP
Compare and swap CAS.
For a complete list of all atomic instructions please see Atomic instructions section of ARMARM document.
In general these instructions perform an atomic RMW ReadModifyWrite operation on the memory location provided and require alignment to overall access size A fault will be signaled if an unaligned access is performed If FEATLSE2 is not implemented.
Arithmetic atomic instructions are dubbed St <op> if no return value for the instruction is needed and Ld <op> otherwise These will have ST LD prefix accordingly.
As part of this RMW operation the instruction will.
Read memory location perform a load operation.
Perform calculation if needed.
Place old value in destination register only if return value is needed for Ld <op>.
Write the new value to location perform a store operation.
Important notes
For the purpose of permission checking and for watchpoints all of the Atomic memory operation instructions are treated as performing both a load and a store.
The instructions include memory ordering options of Acquire and Release When used these memory ordering options will cause the instructions to have the same memory ordering semantics as LDAR when Acquire option is used and of STLR when Release option is used.
For the LD<OP> instructions where the source and destination registers are the same if the instruction generates a synchronous Data Abort then the source register is restored to the value it held before the instruction was executed.
The ST<OP> instructions and LD<OP> instructions where the destination register is WZR or XZR are not regarded as doing a read for the purpose of a DMB LD barrier.

#### 5.1.2 Data Gathering Hint DGH

LSU supports the Data Gathering Hint DGH instruction The general requirement for DGH is that it is expected to be performance optimal to make memory accesses with NormalNC memory appearing in program order before the hint instruction visible at its endpoint without waiting for any memory accesses appearing after the hint instruction This instruction is designed as a hint to close any gathering occurring within the microarchitecture The DGH flow is very similar to the NC store flow without address and data STD part so it is forward disable It is not need to look up the TLB and DC but need to give an agen resolve in store issue pipe It also can be reject in the shadow reject window which like NC store instructions The other difference between DGH and NC store is that the DGH is not need to set NDCPEND signal In the interface with OOO the ooolsuroballocxtype[30] of DGH instruction is 4b1000 which is the same as default 1uop store In the interface with IEX the iexlsustaxtype[110] is 12b110000111111 In the interface with L2C the DGH request information is very similar to the DMB request the only difference is the lsul2cpipexreqmod[50] of DGH is 6b010000 There is a configuration override bit in OOO which can decode the DGH instruction as a NOP instruction then the DGH instruction will have no effect at all.
| 6 | PipeLines |
| --- | --- |

This chapter describes the LSU pipelines for each phase of load/store execution as well as the pipelines for LSU interfaces to L2C and MMU This chapter also shows the fundamental LSU datapaths that have critical timing and require careful physical design.
