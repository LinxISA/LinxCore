# OOO Rename and Dispatch

This document covers architectural-to-physical register mapping, speculative and committed maps, free lists, mapping queues, dispatch packet generation, issue-queue allocation, and virtual load/store indices.

> This Markdown document is the maintained specification. Sensitive metadata and internal reference labels have been removed while preserving architecture semantics.

### 6.2 Rename

#### 6.2.1 Architectural and Physical register

##### 6.2.1.1 Architectural integer registers

Aarch64 has 31 general purpose 64-bit integer registers named as X0X30Aarch64 also has one SP registers for selected EL03 modes There are four temporary registers added by microarchitecture and a ZERO register so the overall register table is shown in the table below.
| Register number | A64 reg |
| --- | --- |
| 0 | X0 |
| 1 | X1 |
| 2 | X2 |
| 3 | X3 |
| 4 | X4 |
| 5 | X5 |
| 6 | X6 |
| 7 | X7 |
| 8 | X8 |
| 9 | X9 |
| 10 | X10 |
| 11 | X11 |
| 12 | X12 |
| 13 | X13 |
| 14 | X14 |
| 15 | X15 |
| 16 | X16 |
| 17 | X17 |
| 18 | X18 |
| 19 | X19 |
| 20 | X20 |
| 21 | X21 |
| 22 | X22 |
| 23 | X23 |
| 24 | X24 |
| 25 | X25 |
| 26 | X26 |
| 27 | X27 |
| 28 | X28 |
| 29 | X29 |
| 30 | X30 |

### 3.1 SPElx

### 4.8 Temp0

### 4.9 Temp1

### 5.0 Temp2

### 5.1 Temp3

### 6.3 ZERO

Table 6 4 integer registers numbering.

##### 6.2.1.2 Physical integer registers

There are 272 physical integer registers to be mapped All of these ptags are splited into 2 banks and each bank contains 136 ptags.

##### 6.2.1.3 Architectural VFP registers

Aarch64 has 32 256-bit registers which can be treated as 32 128-bit or 64-bit or 32-bit registers as well There are 4 temporary registers added by microarchitecture The Aarch64 VFP register mapping table is shown below.
O0
| Q0 | D0 | S0 |
| --- | --- | --- |

O31
| Q31 | D31 | S31 |
| --- | --- | --- |

#### Figure 6 14 Aarch64 floating point register mapping

In addition 4 VFP temporary registers are added to support uop break So the overall register table is shown in the following table.
| Register number | A64 reg |
| --- | --- |
| 0 | S0D0Q0O0 |
| 1 | S1D1Q1O1 |
| 2 | S2D2Q2O2 |
| 3 | S3D3Q3O3 |
| 4 | S4D4Q4O4 |
| 5 | S5D5Q5O5 |
| 6 | S6D6Q6O6 |
| 7 | S7D7Q7O7 |
| 8 | S8D8Q8O8 |
| 9 | S9D9Q9O9 |

### 1.0 S10D10Q10O10

### 1.1 S11D11Q11O11

### 1.2 S12D12Q12O12

### 1.3 S13D13Q13O13

### 1.4 S14D14Q14O14

### 1.5 S15D15Q15O15

### 1.6 S16D16Q16O16

### 1.7 S17D17Q17O17

### 1.8 S18D18Q18O18

### 1.9 S19D19Q19O19

### 2.0 S20D20Q20O20

### 2.1 S21D21Q21O21

### 2.2 S22D22Q22O22

### 2.3 S23D23Q23O23

### 2.4 S24D24Q24O24

### 2.5 S25D25Q25O25

### 2.6 S26D26Q26O26

### 2.7 S27D27Q27O27

### 2.8 S28D28Q28O28

### 2.9 S29D29Q29O29

### 3.0 S30D30Q30O30

### 3.1 S31D31Q31O31

### 4.8 Temp0

### 4.9 Temp1

### 5.0 Temp2

### 5.1 Temp3

Table 6 5 Floating point register numbering.

##### 6.2.1.4 Physical VFP registers

There are 192 physical vfp registers to be mapped All of these ptags are splited into 2 banks and each bank contains 96 ptags.

##### 6.2.1.5 Architectural Predicate registers

Aarch has 16 general 32 bit predicate registers named as P0P15 the overall register table is shown in the following table.
| Register number | A64 reg |
| --- | --- |
| 32 | P0 |
| 33 | P1 |
| 34 | P2 |
| 35 | P3 |
| 36 | P4 |
| 37 | P5 |
| 38 | P6 |
| 39 | P7 |
| 40 | P8 |
| 41 | P9 |
| 42 | P10 |
| 43 | P11 |
| 44 | P12 |
| 45 | P13 |
| 46 | P14 |
| 47 | P15 |

Table 6 6 Predicate registers numbering.

##### 6.2.1.6 Physical predicate registers

There are 56 physical predicate registers to be mapped.

##### 6.2.1.7 Architectural Conditional Code registers

Aarch64 has one architectural CC register PSTATENZCV Beside architectural CC register we introduce one additional temporary register for MSRMRS instruction The CC register table is shown below.
| Register number | A64 reg |
| --- | --- |
| 0 | CC |
| 1 | CCTemp |

Table 6 7 Conditional Code register numbering.

##### 6.2.1.8 Physical Conditional Code registers

There are 64 physical Conditional Code registers for CC and 64 physical Conditional Code registers for CCTemp These registers cannot only be accessed by the integer pipe but also the FP pipe can directly access these registers.

##### 6.2.1.9 Zero register rename

Depending on instruction semantics X31 can represent a zero register which always has the value of zero The zero register is hardwired to a fixed location of the physical register files When the zero register is used as a source rename will rename it to physical register number 254 when the zero register is used as a destination DSP will rename it to physical register number 255.

#### 6.2.2 Block Diagram

#### Figure 6 13 rename microarch diagram

#### 6.2.3 Speculative Map Table

The speculative map tables smap are accessed in D3 stage to lookup the INTCCVFP source ptags for each valid source atag for each uop and each uop with a valid INTCCVFP destination atag will take a new ptag from the appropriate INTCCVFP ptag free list except optimized regtoreg move instructions see Register MOV Optimization part These destination ptags will be written into the smap entries indexed by the destination atags In addition if there is dependence between uops in the same cycle for example if the source atag of a younger uop matches the destination atag of an older uop in the same cycle the destination ptag of the youngest matched producer will be bypassed as the source ptag of the consumer.

##### 6.2.3.1 Renaming of PTags and IQIDs

In order to rename architectural registers to physical ones theres a lookup table called SMAP table where each entry indexed as atag and the entry itself holds physical register tag PTag allocated to the atag SMAP table should have 18 read ports 2 sources per op 8 ops and 8 write ports 1 destination per op 8 ops Size of INT SMAP table should be 36 entries 32 architectural register + 4 TMPs and each entry contains 9 bits payload 8-bit ptag 272 PTags requires 8 bits to address and 1-bit ptag sizes For conditional code register CCs therell be a separate register that will hold pointer to separate physical register file that holds CC values.
Dpdinfo of predicate atags are also stored in int IQID Map which means that dpdinfo of atag32atag39 are stored in int IQID Map and atag32atag47 are stored in vfp IQID Map This is because that for uops with F2I attributes uops can directly get dpdinfo from int module and do not need to access payload from vfp SMap Noted that only the first 8 predicate atags dpdinfo are stored in INT Smap for use of dummy entry creation.
To construct each ops dependency vector for dependency matrix each ops source has to be mapped to the IQ entry of the producing op op that writes it This information will be held in IQID Map that will consist of same 36 entries as SMAP in fact IQID Map is treated as part of payload of SMAP each entry will hold 7 bits in fact dpdinfo is 9 bits but smap only holds 7 bits to address entries within all IQ groups and entries.
To produce final dependency the result of reading SMAP and IQID map needs to pass inlining MUX to update inline dependencies inside current dispatch window IQ entry ID also needs to be qualified with PTag ready bits if producing op has already written to INT RF then this source is ready and consuming op does not need to wait for it to become ready.

##### 6.2.3.2 1CYCLE RENAME LOOP

Updating SMap and IQID Map is a 1-cycle loop both maps has to be read and written in the same cycle due to reg>reg move elimination Both SMap and IQID Map have multiple ports 12r6w so both read and write operations will be time consuming See those 1-cycle loops marked in figure below.

##### 6.2.3.3 INLINING MUX FOR DSTS AND SRCS PTAGSIQID

To rename DSTs first determine for each DST if its a destination of MOV op or not If its coming from MOV op assigning it with PTag from SRC Then if the SRC is dependent with previous ops DST in the same cycle based on inlining controls choose for each chain of MOVs the topmost uop and set its destination for all dependent DSTs.
For example
| op0 | X0 ADD X1 5 |
| --- | --- |
| op1 | X3 MOV X0 |
| op2 | X2 MOV X3 |
| op3 | X1 MOV X0 |

In the example above op 0 will be allocated with a new PTag ops 12 and 3 are in chain of moves and each should be assigned with op0s DST PTag For SRC its either architectural source from SMAP or if it depends on DST of other op in same window PTag assigned to DST of that op which it is dependent In this implementation first column of MUXes in DST and SRC inlining are the same and thus can be shared Heres how inlining MUX for DST and SRC are built.

##### 6.2.3.4 INLINING CONTROLS FOR DSTS AND SRCS

The inlining MUXes above require controls that basically are either point to op itself if its SRC or DST is independent or to the op which destination should be used instead Heres the proposed scheme.
The idea of the proposed scheme is that it has a simple and regular structure and is not timingcritical since it runs in parallel with SMAPIQID map read which will take considerable time both SMAP and IQID have 12 read ports.

#### 6.2.4 Commit Map Table

The commit map tables CMAP is accessed when mappings architecturetophyscial tag are committed from Mapping queue stage and update the CMAP entry which contains the mapping of the same atag The mappings committed are written to CMAP and the previous mappings in CMAP of the corresponding entries are retired the retired ptags will be release to the free list and will be renamed to a new destination atag.

#### 6.2.5 Ptag Free List

Detail see 6213

#### 6.2.6 Mapping Queue

The mapping queue MPQ is a queue which contains all architecturetophyscial tag mappings before the corresponding instructions are committed from ROB When the instruction is committed its mappings will be read out and update CMAP And when flush happens all of the the mappings whose rid are younger than flush rid include flush rid itself will be released to Ptag free list the remained mappings whose rid are older than flush rid will be read out and help to recover the SMAP.

##### 6.2.6.1 MPQ Control FSM

There is a microarch bug in int and vfp MPQ of TaishanVxxx which cause MPQ commit and flush pointer maintain error The reason is that MPQ commit and flush operation need more cycles than ROB to finish because it has a bandwidth limit to release free PTAGs To fix this bug one rule should be followed one thing is done and another can start To MPQ the flush operation should be exclusive with commit operation as recover operation that is flush operation will start at the same cycle with recover operation To ROB it will wait at flush RID or a reasonable youngest RID for MPQ until MPQ finish both commit and flush operations.
MPQ uses an FSM to control its work state as the figure below shows When ROB gives a commit order MPQ will enter Commit state and start to commit mappings from commit pointer until all of the mappings whose rid are older than ROB commit rid are commited the mappings equal with ROB commit rid will not be commited When a robbranch flush happens MPQ will enter Rebuild state and start to recover mappings from commit pointer to SMAP at the same time MPQ start to release flushed mapping from allocate pointer to the free list If commit and flush come at the same time MPQ firstly enter Commit state and pend flush event MPQ always finish current operation and then switch to another work state if there is a pending event Specially a flush event can interrupt Commit state MPQ can switch to Rebuild state if flush rid is older than current commit wrap rid in mpq for timing current commit wrap rid is the same as bank0 entries wrap commit rid MPQ will update embedded flush rid if it is older than last valid flush rid the rebuild operation use the buffered flush rid to compare with mapping rid The MPQ control FSM guarantee mapping commit and rebuild are exclusive and there is no influence between them.

#### Figure 6 15 MPQ control FSM diagram

ROB both in commit and flush is faster than MPQ If ROB commit so fast that its commit rid is one round younger than MPQ oldest mapping rid MPQ will end commit before reaching to real end location and left out some mappings which should commit originally or MPQ will overcommit some mappings which should be flushed originally To avoid ROB overcommit or undercommit ROB should pause commit and wait at a reasonable rid For flush ROB stop commiting at the flush rid and wait until MPQ finish all operation and return idle state for commit ROB stop committing at the rid which is one round younger than the oldest commit rid before the MPQ enter in rebuild or Commit state and wait until MPQ finish all operation and return to idle state For example if ROB start to commit from 0x7 instruction group so the oldest rid is 0x7 and ROB can go on committing to 0x86 rob commit rid is 0x87 and will be waiting at 0x87rob commit rid is 0x88 until MPQ finish all operation and return to idle state If a flush happens at MPQ working state ROB should update its commit waiting rid with the new flush rid if the flush rid is older The commit waiting rid limit ROB commit in a window to avoid ROB commit too fast and in the window MPQ can see the rid range must be in the ROB size so the result of commit and flush rid comparator are always correct and couldnt reverse.
MPQ will stall pipeline when receive flushnew until recoverend When flushnew meet commit commit new or commit state in fsm to reduce pipeline stall time flush could block commit state and to avoid ROB undercommit when flush rid must be older than mpq current commit wrap rid which means flush wrap rid is older than mpq current commit rid fsm can change commitrebuildpend state to rebuildcommitpend state For example if ROB start to commit from 0x7 the startpoint of next commit rid is 0x8 instruction group if ROB not receive commit done from mpq it can go on committing to 0x86 rob commit rid is 0x87 and decoder can allocate youngest rid 0x6 when new allocated instruction generate flush at rid 0x3 mpqs oldest commit rid must older than or equal to 0x83 before flush can block commit.

#### Figure 6 16 decrobmpq rid window diagram

##### 6.2.6.2 MPQ flush doing not stall pipeline

To increase performance new flush scheme can support both ptag flushing and write entry at same time As shown in figure 617 When MPQ meet ROB flush or bru flush mpq need recover and flush operations If recover entries are less than flush entries new mappings could be written into mpq after recover end To describe in detail first recover ptr move from older to younger flush ptr move from younger to older then when recover finished flush ptr move to where recover end recover ptr and flush from older to younger until no ptag can be flushed In this stage recover end and flush doing mpq can write entry from recover ptr when flush is doing So only recover operations need stall pipeline flush operations without recover do not need to stall pipeline.
But when recover is finished and flush is doing meet second rob or bru flush pipeline should be stalled until first flush operation is finished then respond second flush and begin another recover operation and flush operation.

#### Figure 6 17 mpq flushrecover

##### 6.2.6.3 New banking scheme and read loop

The new banking and subbanking scheme are intended to improve the timing of the 1-cycle flushcommit ptr update loop.
There is a critical timing path from mpq entry read flushcommit when mpq size up to 256 or more through the inorder read logic to the entryreadptr logic The whole logic is 1-cycle loop To optimize the timing path each MPQ bank is divided into 2 subbanks even and odd.
This partitioning guarantees that each subbank can be accessed at most once per 2 cycles For example if the even subbank is accessed in current cycle then in the next cycle only odd subbank can be accessed.
Therefore the 1-cycle loop can be divided into 2-cycle The first cycle is from MPQ entry read information to the evenodd indentification flag logic the second cycle is from evenodd flag to the entryreadptr logic The timing of first cycle is still tense but there is space for backend timing optimization.
The example below is for 2 banks but it can be trivially extended to 16 banks or more The orange loop is a 1-cycle loop the blue loop is the 2-cycle loop.

##### 6.2.6.4 Commit end optremove flush block commit open item

- nepro bank commitcommitend cmap copycommitptagcmap.
- recoverflush1bankcommit.

Recovercopycommitendflush.

#### 6.2.7 Append Mapping

There are three MapQueue INTCCVFP MapQueue They are completely separated and instantiated in the INTCCVFP rename unit For each instruction INTCCVFP rename calculates a MapQueue bank write pointer to write for each of its INTCCVFP destination mappings in corresponding SMAP unit The different destination mappings for a particular instruction will occupy entries in the different MapQueue.
The ordering across the same destination mappings within each INTCCVFP type for a particular instruction must follow the UOP ordering for that instruction The ordering across the sets of MapQueue entries for each instruction must follow the program order.
Up to 6 INT destination mappings and 4 CC destination mappings are written to INTCC MapQueue at S1 stage of the INT pipe Similarly up to 6 VFP destination mappings are written to VFP MapQueue at S1 stage of the VFP pipe.
On each of INTVFP pipes each MapQueue receives the uopdstvld mpqbankptr atag attributes and ptag attributes for each uop at S1 stage Again rename SMAP will flush the uopvld bits at S1 stage as needed and then do not write the destination mappings to the appropriate MapQueue entries The format of each MapQueue entry is shown in the following diagram.
MapQueue Entry Format.
| Field | Width | Description |
| --- | --- | --- |
| vld | 1 | 0 free |

1 valid
| rid | 8 | Rob id |
| --- | --- | --- |
| atag | 8intvfp1cc | Architecture reg tag |
| Ptag | 8int8vfp6cc6vpd | Physical reg tag |

Ptag index range
Int [272136][1350]
Vfp [223128][950]
Cc [630]
Vpd[550]
Dwrdint
| szvfpvpd | 1int |
| --- | --- |
| 2vfpvpd | Ptag size |

Dwrd 0>32-bit 1>64-bit.
Szvfp 00>32-bit 01>64-bit 10>128bit11>256-bit.
Szvpd 01>32-bit
| rmov | 1int 1vfp | RegMOV optimization flag |
| --- | --- | --- |
| sxtw | 1int | Sxtw optimization flag |
| dpdinfo | 6intvfp7cc | Dependence to show the result come from which execution unit |

Table 6 8 MapQueue data format.

#### 6.2.8 Commit Mapping

Each MapQueue entry contains an RID for each AtagPtag mapping ROB informs MapQueue that it has committed a set of instructions by asserting robcommitvldr2 and ROB gives next commit RID of the youngest instruction to be committed as robmpqcommitridr2 to the MapQueue.
Internally MapQueue compares the commit RID with the RID of each banks oldest valid entry which is pointed by each banks commit pointer If the oldest entrys RID is older than the ROB commit RID all payloads of the entry are read out ptag and size information is sent to the Commit Map CMAP until the entry pointed by its bank commitrdptr is younger than or equal to the ROB commit RID.
The commit operation can read the committed destination mappings from the MapQueue and update to the corresponding entry of the appropriate INTCCVFP CMap When a new mapping is updating to the CMap the Ptag from the previous mapping will be retired and released to the ptag free list.
It is possible that more than one committed destination mappings from MapQueue with the same Atag In this case only the youngest mapping will be updated to CMap and the remaining mappings should directly release their ptags to the ptag free list For temporary Atag the mappings are always not youngest and release directly because Temporary Atag is used between some UOPs of one instruction and no following instruction will depend to it The commit mapping interface from MapQueue to rename includes a youngest bit for each mapping to indicate whether current commited entry is the youngest one among all of the entries with the same atag in this cycle.
The following pipeline diagram shows the earliest possible ptag reuse at D3 stage after a commit operation According to the chart below if a ptag is committed from MPQ it can be reused by d3 ptag request in r5 stage at fastest Specially for cc ptag pair and pdptag the earliest possible reuse stage for ptag is r6 because these modules calculate stall signal with only ptag numbers in fifo but not the sum of ptags in freelist and fifo.
| R2 | R3 | R4 | R5D3 |
| --- | --- | --- | --- |
| commit from MPQ | retire from CMAP | allocate from freelist | allocate from Ptag FIFO |
| Ptag X commit from MPQ | Flop interface to R3 stage and retire Ptag X which is not youngest one | allocate Ptag X from free list and push to Ptag FIFO | Get the Ptag X from FIFO allocate ptag to a destination |
| free[X] 0 | free[X] 0 | free[X] 1 | free[X] 0 |

#### 6.2.9 Recover Mapping

When doing branch flush or rob flush instructions to be flushed may have already updated the speculative map tables These instructions may have also taken Ptags from the Ptag free lists to rename for their destination registers Therefore flush recovery process includes the following components.
Recover the speculative map table to the state just before the oldest flushed instruction.
Flush destination Ptags from flushed instructions back to the Ptag free lists.
The following pipeline diagram shows the speculative map table recovery operation.
Flush Pipeline Diagram Speculative Map Table Recovery Operation.
| Flushr2 | Flushr3 | Flushr4 | Flushr5 |  |
| --- | --- | --- | --- | --- |
| Current state is idle | Current state is rebuild | Current state is rebuild | Current state is rebuild | Current state is rebuild |
| update recoverrdptr with commitrdptr in each bank | Recover comparing | Recover comparing |  |  |

Flush copy cmaps to smaps.
| while bank youngest entry is to be recovered read the ptags from mpq | While any bank youngest entry isnt to be recovered smap recover is finished |
| --- | --- |
| update mappings to smaps | mappings visible to uops at D3 stage |

The following pipeline diagram shows the ptag flush operation.
| Flushr2 | Flushr3 | Flushr4 | Flushr5 | Flushr6D3 |
| --- | --- | --- | --- | --- |
| Current state is idle | Current state is rebuild | Current state is rebuild | Current state is rebuild |  |

Flush comparing
| while bank youngest entry is to be flushed read the ptags from mpq | While bank youngest entry isnt to be flushed mpq flush is finished |  |  |
| --- | --- | --- | --- |
| Ptag x in MPQ be flushed | set free1 for each ptag in its ptag free list | free1 visible in ptag free list | Ptag x is moved to FIFO |

Ptag x can be allocated by d3 ptag request.
| Free[x] 0 | Free[x]0 | Free[x]1 |
| --- | --- | --- |

##### 6.2.1.0 Flush Mapping

The following pipeline diagram shows the special handling for INTVFP pipe uops at D3S1 stages which align with flush at R3 stage.
Flush Pipeline Diagram INT Pipe.
| bruflushe2 or oooallflushr2 | flushr3 |
| --- | --- |

S1 stage
Kill uops at S1 regardless of stall.
Kill mpq write for uops at S1.
Uops at S1 have already taken destination ptags from free list ren will directly release these ptags back to free list in this cycle.
D3 stage
Kill uops at D3 regardless of stall.
Do not allow uops at D3 to take ptags from free list in this cycle.

##### 6.2.1.1 Rename Stall

###### 62111 Physical tag Stall

See 62144

###### 62112 Physical tag threshold Stall

When open threshold function at smt mode though system CFG register get the total ptag num that can be used by current thread all ptag num minus ptag num reserved for other thread then calculate the sum of used or to be used ptags which will be allocated for current thread at D2D3S1 stage occupied by MPQ entries commit r3 state and CMap entries then use total ptag num minus the sum num calculated if the result is positive threshold stall will not be generated otherwise genenate stall and next cycle no dst vld uop will be sent to d3.

###### 62113 MapQueue Stall

One Ptag can either be free in the free list or busy in the MapQueue or CMAP So if the number of all Ptags is less than the sum of the number of the MapQueue entries and CMAP entries ptag only can map to one Atag before the Ptag is released we can make sure that it is impossible that MapQueue has no entry to accept new mapping info that is the MapQueue will be never full But due to RegMOV executed in the OOO for higher performance violates the rule above one Ptag may be mapped to more than one Atag.
MapQueue is written at S1 stage and update at S2 stage At S2 stage MapQueue get free entry number by calculate the sum of entryvld then exclude the D3 and S1 cost number get the available number for next new coming uops at D2 stage Compare the total dst number of D2 with available number if total dst D2 num is less than or equal to available number then stall will not be generated otherwise stall signal will be set to high.

###### 62114 Recovery Stall

While the recovery operations are in progress speculative map table recover doing instructions refetched from IFU must be stalled at D3 stage before accessing the speculative map tables MapQueue will notify decode that the recovery has finished or not Decode will use it to generate the D3 stall once valid uops have reached D3 stage Noted that when the thread doing recover is stable and the other thread has no thread en recover end the last cycle of recover doing will not stall pipeline else recover end will stall pipeline.

###### 62115 Flush Stall

Please see 6262

##### 6.2.1.2 Rename Reset and initialize

The following table shows the reset values for each Rename and MapQueue structure.
| Structure | Reset Value |  |
| --- | --- | --- |
| intren | cmap | ptag 254 SRCZEROPTAG |
| smap | atag0314851 undefined |  |
| free | b0free [135 0] all 1 |  |

B1free [271136] all 1.
| mapq | Entryvld 1b0 |
| --- | --- |

- ther fields undefined.

| ccren | cmap | ptag 63 INVALIDPTAG |
| --- | --- | --- |
| smap | atag01 undefined |  |
| free | Free [620] all 1 |  |
| mapq | Entryvld 1b0 |  |

- ther fields undefined.

| vfpren | cmap | ptag 255 INVALIDPTAG of vfp |
| --- | --- | --- |

Ptag 63 INVALIDPTAG of pd.
| smap | atag031vfp undefined |
| --- | --- |

Atag3247vpd undefined.
Atatg4851tmp undefined.
| free | Free [950] all 1 vfp |
| --- | --- |

Free [223128] all 1 vfp.
Free [550] all 1 pd.
| mapq | vld 1b0 |
| --- | --- |

- ther fields undefined.

##### 6.2.1.3 The Life of a Ptag

Each INTCCVFP ptag has both a current state and current location The current state could be free speculative or committed The state is derived from the current location The possible ptag locations and corresponding ptag states are shown in the following table.
| Current Ptag Location | Current Ptag State |
| --- | --- |
| Free list | Free |
| Free fifo | Free |
| Flush R3 flop | Free |
| S1 flop | Speculative |
| MapQueue | Speculative |
| Commit R3 flop | Committed |
| Committed map table | Committed |

The possible ptag locations are also shown in the following diagram.
If a Ptag does not occupy any one of the specified locations it is a hardware bug A simulation checker could monitor the current location of each Ptag and report an error if a Ptag is dropped.
As shown in the reset section depending on the INTCCVFP type certain Ptags will start in the free lists and are at free State As uops are renamed at D3 stage the Ptags mapped to their destinations will be taken from the free fifo Free State and written to the MapQueue speculative state If these uops are flushed before being committed their Ptags will move from the MapQueue to the flush R4 flop and then free list Free State If the uops are committed their ptags will move to the commit R3 flop and then CommitMap table committed state The Ptags which previously occupied the corresponding entries of the CommitMap table will be released to the free list Free State.

##### 6.2.1.4 Int ptag management

###### 62141 Ptag Banking

To reduce the amount of write ports in Integer Register File farther IRF it was decided to split it to one bank per ALU IQ resulting in 2 banks Decode slot to IQ group randomization scheme which uniformly distributes ops to IQ groups should also result in uniform distribution of DST PTags between IRF banks.

###### 62142 Ptag format

IRF size is 192 which means that each bank contains 64 entries In order to cover all IRF range PTag size has to be 8 bits To simplify identifying which bank a PTag belongs to the 2 MSBs indicate the bank ID ranging between 0 to 2 2 bits and the remaining 6 bits represent the address inside its bank.
| bit | 8 | 7 | 6 | 5 | 4 | 3 | 2 | 1 | 0 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Designation | IRF bank ID | Entry index in bank |  |  |  |  |  |  |  |
| Legal values | [01] | [0135] |  |  |  |  |  |  |  |

###### 62143 Ptag preAllocation

At D2 stage each cycle PTag allocation logic will find 4 available PTags from free list per bank these Ptags are staged in FF and consumed at D3 stage when there are Ptag requests noted that D3 ptag requests only get ptags from staging FF and never get ptags from free list directly If previously preallocated PTags held in staging FF were consumed then Ptags in free list will be chosen to fill the staging FF as new prealloc Ptags to be consumed next time and the chosen ptags would be marked as inuse in freelist Staging is necessary since not every preallocted PTag in FF will be consumed there are 6 ops for 9 preallocated PTags and not every op has a destination eg stores or branches The scheme below describes more details of PTag allocation scheme per bank.

#### Figure 11 PTag allocation scheme per bank

PTag Free list is a bit array consisting of 136 bits x 2 banks Theres a logic that finds first second and third set free bits in PTag free list The output of this logic passes through encoder since Find logic operates on hot ones vectors and if PTag from staging FF was consumed during current cycle found ptag will be written into staging FF and cleared form free list If PTag value from staging FF was not consumed during this cycle then Staging FF control logic will prevent new value from being written into staging FF and from being cleared from free list.

###### 62144 Ptag stall

Ptag stall signal is generated for decode and acting on D2 stage which means that when stall signal is set to high there will not be uop sent from decode at D2 stage Each threadt0t1 will generate a stall signal and acting on corresponding thread seperately.
For each bank if the ptag number free is not enough then a stall signal will be generated.

##### 6.2.1.5 VFP ptag management

###### 62151 Ptag Banking

To reduce the amount of write ports in VFP Register File it was decided to split it to one bank per FSU IQ resulting in 2 banks Decode slot to IQ group randomization scheme which uniformly distributes ops to IQ groups should also result in uniform distribution of DST PTags between FRF banks.

###### 62152 Ptag format

FRF size is 256 which means that each bank will have 128 entries In order to index all of the Ptags in FRF PTag width should be 8 bits To simplify identifying which bank a PTag belongs to the MSB present the bank index which ranges between 0 to 1 1 bit and the remaining 7 bits indicate the address inside the bank.
| bit | 7 | 6 | 5 | 4 | 3 | 2 | 1 | 0 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Designation | IRF bank ID | Entry index in bank |  |  |  |  |  |  |
| Legal values | [01] | [095] |  |  |  |  |  |  |

###### 62153 Ptag preAllocation

At D2 stage each cycle PTag allocation logic will find 3 available PTags per bank vfp path up to 6 uop check that previously preallocated PTags held in staging FF was consumed and if yes mark PTag as inuse at PTag free list and write its value into staging FF Staging is required since not each PTag will be consumed The scheme below describes in more details PTag allocation scheme per one bank.

#### Figure 11 PTag allocation scheme per bank

Free PTag list is a bit array consisting of 96 bits x 2 banks Theres a logic that finds first second and third set bits in PTag free list The output of this logic passes through encoder since Find logic operates on hot ones vectors and if PTag from staging FF was consumed during current cycle will be written into staging FF and cleared form free list If PTag value from staging FF was not consumed during this cycle then Staging FF control logic will prevent new value from being written into staging FF and from being cleared from free list.

###### 62154 Neon and SVE Ptag

Each bank FP regfile is planned to be 48256 for SVE it can be used as 48 Z physical register for Neon it can be used as 96 Q physical register For rename the ptag size will be defined as 3264128 For SVE it need rename use a pair of ptag in same address of different banks for Neon it can be renamed using single ptag.
For Neon type will rename to [redacted numeric sequence]
For Sve type will rename to 012395 consume [redacted numeric sequence]
Pair and single ptag share the same one free list if 2 Ptags are located in different banks but same address they are treated as a pair of ptag else if a Ptag cannot pair with another ptag it will be treated as a single ptag Pair fifo pick pair ptags in free list as preallocated pair ptags single fifo pick single ptags in freelist as preallocated single ptags Bank 0 and Bank1 each have 3depth single fifo and there is also a 12depth pair fifo Similar to int part D3 ptag requests can only get ptags from fifo single fifo and pair fifo For each freelist take similar way to search free ptag as 62153 described for new uop allocate.
When ptags in single fifo are not enough for D3 uop ptag requests we can break pair ptags located in pair fifo into single ptags 1 pair can be broken into 1 single each bank if there are extra pair ptags left after D3 pair consumed If one of the broken single ptag is consumed and the other banks is left the left ptags will be written into freelist.

###### 62155 Ptag recycle

When there is a pair stall because that the number of pair ptags is not enough it is probably that single ptag in single fifo and freelist can be paired but they cannot because ptags can only be paired when they are both in the freelist So when there is a pair stall and at lease 1 ptag is in the single fifo this situation holds for several cycles can be configured by ckb ptag in the single fifo will be recycled into freelist and can have a chance to be paired up Noted that there must be no d3 uop when recycle is doing and this feature can be disabled by ckb.

###### 62156 Ptag stall

###### 621561 Pair

Pair stall do not discriminate among 2 threads When num of pair ptags in pair fifo is less than ptags may consume in the next 2 cycles D3 and D2 D3 ptag consumption num is real D3 dstvld num notice that if single ptag in fifo is not enough and pair ptags need to be broken and use as single these ptags should also be treated as D3 ptag consumption pair stall will be set to high and stall d3 pipeline.

###### 621562 Single

Single stall signals are calculated separately for each thread and each bank For each bank if the sum of free ptag number in single free lists and ptag number of single fifo and pair fifo is less than ptag that will be consumed in the next 2 cycles also D3 and D2 both pair and single request should be counted there will be a stall signal generated.

##### 6.2.1.6 CC ptag allocate

There are 64 ptags in CC freelist up to 4 ptags can be picked to fifo per cycle The algorithm of picking ptags from freelist is.
Step 1
Pick the ptag indexed by 63 not alloc when program run in freelist allocated to atag0 in ccmap.
Pick the ptag indexed by 127not alloc when program run in freelist allocated to atag1 in ccmap.
Step 2
Divide free ptags in free list into 2 Xbanks X0 and X1 X0 contains all of the even ptags and X1 contains all of the odd ptags.
Example

- X0 [redacted numeric sequence]

X1 [redacted numeric sequence]
Step 3
Select minimum and maximum idx in each Xbank of left free list If only one idx in one Xbank free list minimum idx of this Xbank is valid maximum idx of this Xbank is invalid.
Example free list of X0 and X1.
X0 [redacted numeric sequence]
X1 [redacted numeric sequence]
Then each min and max of Xbank is.
X0min 2 X0max 62
X1min 1 X1max 61
Step 4
Pick ptags after mask to ptags fifo According to the sequence of X0min X1min X0max X1max select true idx and send to ptag fifo.
Noted that if there are more than 4 free ptags in freelist but all in the same Xbank there is a possibility that only 2 ptags could be picked to fifo in one cycle.
| pick0 | x0min | x1min | x0max | x1max |
| --- | --- | --- | --- | --- |
| pick1 | x1min | x0max | x1max |  |
| pick2 | x0max | x1max |  |  |
| pick3 | x1max |  |  |  |

Pick cc ptags priority.
Step 5
Read and write rules of pfifo pfifo can store 15 ptags at most.

## 1 after reset only if there are more than 3 empty fifo entries can ptags picked from freelist to fifo

## 2 after reset if number of left ptags in pfifo is less than decoder need in stage D3 and D2 send ptagstall signal to decoder

##### 6.2.1.7 Register MOV Optimization

To optimize regtoreg move instructions rename replace destination atags ptagsizedpd with source atags correspond payload directly in smap at D3 Stage and resolved to Rob in S1 stage The ptag will belongs to two atags uops When the second uop is flushed to freelist the first uop is still in use and could not be allocated so the second uops flush cannot set the ptag free When the first uop which use the ptag is resolved to freelist For second uop which also use the ptag it has not resolved to freelist yet thus the ptag cannot allocate to new dst ptag and should not be set to free state in free list To solve this case add ptag lock operation.
Ptag will be locked after regtoreg move optimizion in SMAP Freelist allocate ptag to Smap need excludes locked ptags Only when locked ptags are in free list and cannot be found in CMAP the locked ptag can be unlocked.

##### 6.2.1.8 SXTW instruction optimization

SXTW instruction is an alias of SFBM SXTW means sign extend word which extends the sign bit of source register and writes the result to the destination register The size of source register is 32 bits and the destination register size is 64 bits This optimization is similar to MOV optimization the SXTW instruction will be done in rename stage and will not be sent to issue queue thus increase performance.
Just similar to MOV optimization to optimize SXTW instructions rename replace destination atags ptagdpd with source atags correspond payload directly in Smap at D3 Stage and resolved to Rob in S1 stage if optimized successfully The size of the destination atag is decided by decode information which is different with MOV implementation.
Rename will also generate an extend bit to tell iex that the source needs to do sign extend before using for each mapping which will be stored in smap as a component of payload the extend bit will be set to high and written into smap in S1 stage when it is the destination atag of SXTW so that consumer of SXTW can read the extend bit from smap or inline bypass in the same cycle However when the consumer is MOV type and of 32-bit destination size the destination atags extend bit will be cleared to zero.
The SXTW destination ptag belongs to more than 1 atags uops just like MOV optimization So that lock process is also demanded in SXTW optimization.

##### 6.2.1.9 FMOV XZR optimization

For FMOV general type instruction when the src operator is ZERO the instruction will be processed in rename stage and will not be sent to FSU issue queue With respect to detail implementation decode stage will detect FMOV XZR pattern and assign src valid signal as zero rename recognize the invalid src vld signal and then allocate the specific vfp INVALID ptag to the src operator which is defined by macro Decode will also send rename a fmov bit and so rename could manage this instruction as a fmov instruction whose src operator is vfp INVALID ptag So that the dst operator will be also allocated with vfp INVALID ptag The differences between this optimization and normal fmov optimization is that FMOV XZR do not need to set lock bit and only one normal fmov optimization can be done in 1 cycle however there is no such constraint for FMOV XZR optimization.

##### 6.2.2.0 Allocate mode Allocmode

There is a situation that one banks ptag is not enough because most of the ptags of this bank are occupied by cmap There will be a hang out because of ptag number lack if alloc mode is not realized Alloc mode is a kind of special ptag allocation scheme When the number of 1 ptag bank is not enough and the other 2 banks have enough ptags for several cycles rename will enter alloc mode At alloc mode ptag will be allocated from 2 banks instead of 3 banks in normal mode Each bank will allocate at most 3 ptags 1 cycle.

### 6.3 Dispatch

#### 6.3.1 Block Diagram

Dispatch receive destination and source ptag info from rename and uop info from decoder generate uop issqsrcdecpcaux packet at D3 stage dispatch to each issq according to uop type Uop vld and packets will be sent to IEX and FSU at S1 stage Dispatch unit will decide each uops INTFP ptag banking and destination issq number according to algorithms ALUAGUBRUFPFSTD issq index of each uop is calculated in corresponding IEXFSU unit At D3 stage dispatch will check issq free entry number and generate stall if there is no enough issq free entry Stalled uops will be held at S1 stage when issq release enough entry dispatch will continue to send uop to issq.
Some optimization will be done at dispatch and uop resolve should give to ROB such as fusion movr movi adradrp optimization.

#### Figure 6 24 Dispatch block diagram

There are total 5 types of issue queues that uop could be dispatched to including 6 ALU issq 3 AGU issq with 3 LDM 2 BRU issq 1 FSU L0 issq and 2 FSTD issq.
Dspsysflop lock config register from system register module.
Dspdfx sample dfx signal to ooodfx module.
Dspdec decode instuop information about syssveldstcnt etc.
Dspshare decode uidutype for each module in DSP sample uop according DEC identify.
Dspdp genetage issqsrcdecpcaux packet and send to IEX and some other function such as moviimovr optimizatiom and fusion.
Dspctrl control module of DSP.

- dspalualloc alu issq alloc to alu uop and calculate alu issq stall.
- dspfsualloc l0qfstd issq alloc to fsu uop and calculate fsu issq stall.
- dspagualloc agu issq alloc to sta or load uop ldm entry alloc to load uop and calculate agu issq and ldm stall.

Dspbrualloc bru issq entry alloc to bru uop and calculate bru issq stall.

- dspldmXfree ldm entry freelist allocate and deallocate ldm entry.

Dspagufree agu issq freenum calculate.

- dspbrufree bru issq freenum calculate.
- dspallocldid ldidstid calculate.
- ther feature pmupower event signal sample Thresholdsafemode stall calculate.

#### 6.3.2 Dispatch feature

#### 6.3.3 Generate and encapsulate packet as IQ payload for each uop

| 1 | Generate dec packet including op code atag etc |
| --- | --- |
| 2 | Generate Issq packet including UID UTYPE dstsrc vld etc |
| 3 | Generate src packet including ptag inline and dpde |
| 4 | Generate PC packet and AUX packet |
| 5 | Generate fusion success flag |
| 6 | Generate virtual LDIDSTID |

#### 6.3.4 Generate issue queue allocate informations for each uop

| 1 | decide which issue queue and which port of issue queue will uop to go |
| --- | --- |

The uops are orderpreserving in dispatch.
For uops to different issq there is no strict order.
For different ports of agu issq port0 is oldest.
For different ports of bru issq port0 is oldest.
For different ports of alu issq port0 is oldest.
In order dispatch if older uop is stalled then younger uops are also stalled.
It means that uopm must be send to issue queue before uopn if m<n mn 012345.
There are 2 or 3 write ports for each ALUFSU issue queue respectively port0 port1 and port2.
There are 3 write ports for each AGU issue queue port0 port1 and port2 The priority order is port0>port1>port2.
There are 3 write ports for each BRU issue queue port0 port1 and port2 The priority order is port0>port1>port2.
The detailed dispatch policy will introduced in 631314.
| 2 | cancel uop which is optimized |
| --- | --- |

If a uop is optimized it will not participate in the allocation.
The optimization condition include.
| a | MOVI optimization MOVKMOVNMOVZADRADRP |
| --- | --- |
| b | Fusion tail uop exclude MOVPREFIX fusion |
| c | MOVFREFIX fusion head uop |
| d | MOVR optimization include FMOVRIMOVRSXTW optimization |

FP fusion and MOVR optimization include FMOVR optimization cancel uop in Decoder at D2 stage not in Dispatch.
| 3 | ROB Flush R3 and branch flush e3 will flush all uops at D3 stage but it not take effect at D3 stage it will cancel the issue queue write en signals at S1 stage |
| --- | --- |

R3E3 > D3
R4E4 > S1
| 4 | Some type of uop may break into 2 or 3 uops at D3 stage but only support uop broke to different issq type |
| --- | --- |
| 5 | Some type of uop may merge into 1 uops at D3 stage For example when second uop is ldpardst2lsxlsdlda2 or first uop is alu uop of bcond fusion |

#### 6.3.5 Generate D3 dispatch stall

| 1 | If there is at least one uop in D3 stage that cannot be send out it will generate D3 stall D3 stall will prevent decode unit from sending uopvldd3 in next stage Uop will stay in SKB in case of D3 dispatch stall |
| --- | --- |
| 2 | If number of uops send to one issue queue is larger than the number of available write port of this issue queue or the available free entry number of this issue queue the older uops will be send out and the younger uops will stall at D3 stage |
| 3 | If a uop breaked into more than one splited uops they will all stall at D3 stage if any of the splited uop generate stall |
| 4 | If the older uop generate stall the younger uop will stall |

#### 6.3.6 Generate threshold and safemode stall

In MT mode multithread mode certain number of entries can be reserved for each thread in each type of issue queue sum of ALU queue or AGU queue or BRU queue.
The reserved number can be configured in selfdefine system register for each type of issue queue.
If current thread occupies too more entries in special type ALUAGUBRU of issue queue it will generate reference type ALUAGUBRU of threshold stall for this thread.
Current thread may occupy up to 7 more entry than its upper limit number This sense occurs as below.
Cycle 0 t0 occupy 99 entries in ALU issue queues ALU issue queue upper limit number for t0 is 100 It will not generate reference threshold stall.
Cycle 1 Decoder send 8 ALU uops to Dispatch then generate ALU threshold stall of t0 These ALU uop will send to ALU and t0 occupy 107 entries in ALU issue queues 7 more than its upper limit number.
Cycle N ALU threshold stall of t0 will be valid until t0 occupy entries num less 100 in ALU issue queues.
Similarly for each of the single issue queue certain number of entries can be reserved for each thread The reserved number can be configured in selfdefine system register for each type of issue queue If current thread occupies too more entries in special type ALUAGULDMBRUFPFSTD of issue queue it will generate reference type ALUAGULDMBRUFPFSTD of safemode stall for this thread.

#### 6.3.7 Calculate movi opt data and resolve moviimovrfmor opt uop

The MOVI opt instructions include MOVKMOVNMOVZADRADRP dispatch calculate the reference data by instruction code For ADRADRP dispatch get full PC and send to IEX in PC packet.
For ADRADRP if there is any PC offset overflow or Predicate Taken Branch before at the same cycle this ADRADRP instruction will not set MOVI optimization It is judged at D2 stage in Decoder.
For IMOVRincluding SXTW FMOVRMOVI opt dispatch will generate resolve to ROB at S1 stage.
For MOVI opt each bank of Register File can have one opt uop uop 024uop135 send to different bankSo there are 2 integer resolve port in dispatch.
Up to 2 MOVI opt uop in one cycle.
Up to 2 IMOVR opt uop in one cycle.
Up to 1 FMOVR opt uop in one cycle.
Up to 6 FMOV XZR opt uop in one cycle.
Up to 8 resolve to ROB at S1 stage.
FMOVRIMOVR optimization is ensured to be success at D3 stage.
MOVI optimization need to check if there is write port available in bank of the register file from IEX The maximum number of bank is 2.

#### 6.3.8 Virtual Store Queue index

Each store uop will be allocated a virtual store queue index at D3 stage each loadbru uop need take the older youngest store queue index.
Each branch dispatched will take an older youngest store queue index for branch flush recover When branch flush happens as well as the branch flush rid the older youngest store queue index are returned by IEX to recover allocate pointer in OOO If a ROB flush happens ROB will calculate the older youngest store queue index and send to dispatch to recover allocate pointer.
If ROB flush occur with branch flush simultaneously branch flush will be ignored.
For not store uop stid is the youngest of older store queue index.
For store uop send to ALUAGUISTD issue queuestid is the end store queue index of current uop.
For store uop send to FSTD issue queue stid is the end store queue index of current uop.
In current design there are 48 entries in store queue The stid is formed as below.
ST mode
| 9 | 8 | 7 | 6 | 5 | 4 | 3 | 2 | 1 | 0 |
| UPPER | LOWER |  |  |  |  |  |  |  |  |

The maximum value of lower bits is 31.
The total value is UPPER 32+ LOWER 1024.
MT mode
| 9 | 8 | 7 | 6 | 5 | 4 | 3 | 2 | 1 | 0 |
| UPPER | LOWER |  |  |  |  |  |  |  |  |

The maximum value of lower bits is 15.
The total value is UPPER 16 + LOWER 1024.
The reset value of stid is 10h3FF 3131 in ST mode and 10h3FF 6315 in MT mode.
Stid calc rule for IEX.
| aluld | sta | istd | stid |
| --- | --- | --- | --- |
| uop0 | 0 | 0 | 0 |
| uop1 | 1 | 0 | 1 |
| uop2 | 1 | 1 | 2 |
| uop3 | 0 | 1 | 3 |
| uop4 | 1 | 0 | 3 |
| uop5 | 0 | 0 | 3 |
| uop6 | 0 | 1 | 5 |
| uop7 | 0 | 1 | 5 |
| uop8 | 1 | 0 | 5 |

Stid may add 1 when send uop to AGUALU issq there are some rule as upper.
| 1 | stid may add 1 when current uop have sta or istd example uop 123 not 32 byte |
| --- | --- |
| 2 | stid may add 2 when current uop have sta or istd example uop 678 32 byte |
| 3 | When current uop have stastd and last uop have only stathen this uop neednt add 1 because last uop add 1 already example uop 4 |
| 4 | aluld uop except staistd uop stid value keep same with last uop |
| 5 | when meet flush stid will recover new value from ROB ooo flush or IEXbru flush |

Stid calc rule for FSTD.
| aluld | sta | fstd | stid | aluld | sta | fstd | stid | aluld | sta | fstd | stid |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| uop0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |  |  |
| uop1 | 1 | 0 | 1 | 0 | 0 | 1 | 4 |  |  |  |  |
| uop2 | 1 | 1 | 2 | 1 | 1 | 4 | 1 | 0 |  |  |  |
| uop3 | 0 | 1 | 3 | 1 | 0 | 1 | 0 |  |  |  |  |
| uop4 | 1 | 0 | 1 | 0 | 1 | 0 |  |  |  |  |  |
| uop5 | 0 | 0 | 1 | 0 |  |  |  |  |  |  |  |

Fstid send to FSTD issq only when uop type is fstd stid calc is similar as send to AGU except scatter inst scatter inst have only one fstd uop but 248 sta uop fstd uop in scatter need pick the end stid of current inst.

#### 6.3.9 Virtual Load Queue index

Each load uop will be allocated a virtual load queue index at D3 stage.
Each branch dispatched will take an older youngest load queue index for branch flush recover When branch flush happens as well as the branch flush rid the older youngest load queue index are returned by IEX to recover allocate pointer in OOO If a ROB flush happens ROB will calculate the older youngest load queue index and send to dispatch to recover allocate pointer.
If ROB flush occur with branch flush simultaneously branch flush will be ignored.
For not load uop ldid is the youngest of older load queue index.
For load uop send to ALUAGU issue queueldid is the start load queue index of current uop.
In current design there are 80 entries in each load queue indicate in LOW field The ldid is formed as below.
ST mode
| 9 | 8 | 7 | 6 | 5 | 4 | 3 | 2 | 1 | 0 |
| HIGH | LOW |  |  |  |  |  |  |  |  |

The maximum value of LOW is 79.
The total maximum value is HIGH 80+LOW640.
MT mode
| 9 | 8 | 7 | 6 | 5 | 4 | 3 | 2 | 1 | 0 |
| HIGH | LOW |  |  |  |  |  |  |  |  |

The maximum value of LOW is 39.
The total maximum value is HIGH 40+LOW640.
The reset value of ldid is 10h3CF 779 in ST mode and 10h3E7 1539 in MT mode.

##### 6.3.1.0 ALU issq allocation policy

###### 63101 ALU IQ structure

###### 63102 ALU uop banking scheme

ALU IQ allocation is binded with integer ptag banking scheme Banking decides the mapping of u0246 and u1357 to bank 0 and 1 The default scheme is round robin and also support LFSR which is controlled by configuration override bit When integer dst ptag exists in one cycles uop dispatch bundle received from decode the round robin will switch and change the mapping.

###### 631021 LFSR algorithm

Lfsrin[30] initial value 4b0001.
Lfsrinv initial value 1b0.
Lfsrin lfsr[3]lfsr[0] lfsr[31] after reset.
Lfsr < lfsrin if intdstvldd3.
Lfsrinv < lfsrinv if lfsrin 4b0001.
U0banksel lfsrinv lfsr[0] lfsr[0]

###### 631022 Allocation relation with banking

Accordingly if u0246 is mapped to bank0 u0246 could only be dispatched to ALU IQ 012 u1357 could only be dispatched to ALU IQ 345 vice versa.

###### 63103 ALU IQ allocation algorithm

##### 6.3.1.1 AGU issq allocation policy

AGU IQ will be split into 3 ports with one LD and one ST pipe each to minimize the picker size and read mux delay All sta uop and load uop will send to AGU but only load uop need to allocate LDM.

###### 63111 Maximal amount of writes perIQ

- IFU will limit load inst max is 6 store inst max is 4 sum of load and store inst max is 6 one cycle Most of the load/store inst only have one load/store uop The worst case requires 4 writes into one of the IQs.

###### 63112 AGU IQ structure

Each AGU IQ will have 14 entries each AGU have three write ports.

###### 63113 AGU uop steering outline

Loads and STAs are first mapped to the needed IQs and then to the needed write group inside their IQ.

###### 63114 Mapping stores to AGU IQ01

The following scheme are Round Robin for all store uop in the dispatch window slot [07]
StoreToAGUIQ0[slot] IsStore[slot] PrevStidID[0]

###### 63115 Mapping loads to AGU IQ012

The following scheme uses parallel calculation of LID3 for all load uop in the dispatch window.
Analytical equation are slot [07]
Slot 0
LoadToAGUIQ0[slot] IsLoad[slot] 0 LastLID 3.
Slot [17]
LoadToAGUIQ0[slot] IsLoad[slot] 0 count1sIsLoad[slot10] + LastLID 3.
LoadToAGUIQ1[slot] IsLoad[slot] 1 count1sIsLoad[slot10] + LastLID 3.
LoadToAGUIQ2[slot] IsLoad[slot] 2 count1sIsLoad[slot10] + LastLID 3.

###### 63116 LDM IQ and entry index selection

There are 3x16 LDM entries LDM IQ selection for each load uop is the same as AGU IQ selection since AGU IQ need to match LDM IQ in LSU.
However the port bubble squeeze is different so LDM has a separate bubble squeezing logic.
In 920 due to timing optimization LDM index selection is separate logic based on last ldid and current ldid vector of each uop.
LDM index results will have two usage one is packaged into IQ packet to be sent to IEX and the other is to match with uop rid to update LDM free vector and rid.
LDM uses Find three logic to find three free entries inside each IQ Free indexes will be written to a FIFO before send to AGU allocation logic Each cycle FIFO will provide at most three ldm indexes to AGU allocation.

##### 6.3.1.2 BRU IQ allocation policy

There will be 2 BRU IQs with 1 pipe each Total size will be 48 entries BRU uop IQ allocation policy is always Round Robin and is contiguous from last bru uops dispatch result.
Maximal amount of writes perIQ.
Each BRU IQ has two write ports.
The worst case 8 unconditional BRU uops requires 4 writes into each one of the IQs.

##### 6.3.1.3 FSU issq allocation policy

###### 63131 FSU uop banking scheme

Banking decides the mapping of u024 and u135 to bank 0 and 1 The default scheme is LFSR and also support Round Robin which is controlled by configuration override bit When fp dst ptag exists in one cycles uop dispatch bundle received from decode the LFSR will switch and change the mapping.
Special notice that when all 6 fp uop is fusion 3 pairs of fusion in same cycle banking scheme will change to Round Robin scheme.

###### 63132 FSU IQ allocation

FP uop will be directly sent to L0Q with bank selection results which decides FSU IQ 01 to go to L0Q will decide FSU IQ index.

##### 6.3.1.4 FSTD issq allocation policy

There will be 2 FSTD IQs with 1 pipe each Total size will be 36 entries.

###### 63141 Maximal amount of writes perIQ

Each FSTD IQ has 3 write ports The worst case 6 FSTDs requires 3 writes into each one of the IQs.

###### 63142 FSTD IQ allocation

FSTD allocation is based fstd uop stid oddeven Even stid will go to FDQ0 and odd stid will go to FDQ1.

##### 6.3.1.5 Fusion

In current design 2 adjacent uop can make fusion The fusion type is as below.
For ALU BCOND
For FSU ASECEASECD MOVPRFXMOVPRFXMZ ORREOR.
BCOND kill head uop.
ASECEASECD kill tail uop.
MOVPRFXMOVPRFXMZ kill head uop.
ASECEASECDMOVPRFXMOVPRFXMZ kill uop in Decoder at D2 stage through cancel fp01fp1 signal.
Same cycle fusion
For ASECEASECDMOVPRFXMOVPRFXMZBCOND fusion fusion operation cannot cancel at D3 stage Because the head and tail of these type must be vld at same time It is guarantee by Decoder The head and tail belong to same RID and PC base so nothing can block it.
Cross cycle fusion.
For BCONDMOVPRFXMOVPRFXMZORR EOR no cross cycle fusion It is guarantee by Decoder.
For ASECEASECD both STMT mode can support cross cycle fusion.
In ST mode there are 2 cases for head uop to check cross cycle fusion.
Case0
Case1
In MT mode there are 3 cases for head to check cross cycle fusion.
Case0
Case1
Case2
ASECEASECD make cross cycle fusion in each case above.
In case1 and case2 tail of same thread may not vld at D3 but must ready in SKB.

##### 6.3.1.6 DPDE and load hint

###### 63161 DPDE description

###### 63162 Metapfl1

Load src atag and dst atag is same value It is packed in src packet reusing srcp bits.
Except for ld wbk with idst from alu uop.

###### 63163 Phint

If one load itself is one of the followings it will encode phint into load uop DPDE.
Load pair
CASP
Load exclusive
Load register offset.
Load literal
When load uop in AGU IQ in IEX is waken up by a ptag IEX will combine phint and load uops attribute and generate indication to LSU to send to HWP for Meta prefetcher filtering.

##### 6.3.1.7 MDB information generation for IEX

Dispatch will get load/store uop color and mega information form decoder generate MDB information packets and sent to IEX.
Information includes.
MDB color vld only valid when uop is load/store uop and color 0.
MDB color idx 4 bit color from color table look up result in IFU.
MDB mega 1 bit mega hint from IFU look up Color will be 0 when mega 1 Mega will be used in LSU to let load wait for oldest of younger sid store to wake up.
MDB inline vector indicates if load uop depends on same cycle dispatch store uop ldcolor stcolor not address dependency and only indicates the younest producer This is for IEX to prevent yongner inline consumer from pick in S2 stage and also for S2 producer to wake S2 consumer.
