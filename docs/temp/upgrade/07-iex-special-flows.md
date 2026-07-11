# IEX Special Instruction Flows

This document covers floating-point interactions, system instruction handling, instruction fusion, SVE support, and pointer-authentication handling.

> This Markdown document is the maintained specification. Sensitive metadata and internal reference labels have been removed while preserving architecture semantics.

## 6 Interaction with Floating Point Unit

There are mainly 2 kinds of operations used for interaction with IEX and FSU One kind is F2I and F2 CC from FSU and they are used to update integer INT regfiles and CC regfiles and wake up their consumers in IEX The other one consists of I2F CC2F and I2P which are used to update floatingpoint regfiles and predicate regfiles in FSU from IEX respectively There is a special operation called P2I It comes from FSU to wakeup Srcp in IEX but not update any register.
The dispatch rules have been described before Here is a brief interaction between FSU and IEX in target core.

### 6.1 F2IF2CC

For F2I mov there are two kinds of operation one is FMOV and the other is FCVTUS Although for these two operations wake up and bypass data stage is different in FSU FSU will merge FMOV and FCVTUS into a unified stage in the interface Similarily F2CC FCMPFCCMPFCMPEFCCMPE window is also unified in the interface Therefore the pipeline of F2IF2CC could refer to the following diagram.
The valid and ptag signals of F2IF2CC are generated at W0 stage in FSU and the corresponding stage is WM3 in IEX IEX flop 1-cycle to wakeup and go through the pipeline The data of F2IF2CC is ready at W3 stage in FSU and transferred to IEX for its W0 stage The integer regfile is written at W2 stage and all the consumers can be set ready later Note that F2IF2CC will occupy the whole ALU pipeline and have higher priority to block issq as the red block in the diagram shows.

### 6.2 I2FCC2F

I2F mov FMOV uop and CC2F FCCMPFSEL uop will wake up FSU uops and update FP regfiles and the interface is shared Load gene vector is also needed in the interface to kill consumer when Load is miss The brief diagram is as following.
| 63 | I2P |
| --- | --- |

I2PWHILE is a kind of uops when supported SVE and it need update predicate registers in FSU However the pipeline of I2P is little bit different with I2F because there is a predicate ready table in IEX and those uops with srcp tags are waked up in IEX The brief diagram is as following.
| 64 | P2I |
| --- | --- |

P2I is an unofficially abbreviation as it does not need to update integer registers but used to wake up srcp ptags and set predicate ready table in IEX Note that P2I is picked nonspeculative FSU gurantee When I2P and P2I are both picked and meet in the pipeline P2I has higher priority and I2P will be canceled and waited for repick in the issue queue entry The brief diagram is as following.

### 6.5 FP Load

For FP load the renamed FP destination is sent to the integer pipeline as shown in the diagram below.

6. 1 FP load pipeline.

For the floatingpoint load instructions OOO will write the FP dst ptag to IEX issq the same FP LD uop entry through normal dispatch to IQAGU IEX will issue the LD uop to LSU module and send wake up signal at I2 stage to FSU FSU will flop 1 cycle and wake up consumers at E2 stage I2FFP load share.

### 6.6 FP store

The FP store breaks into two uops STA and FSTD Note that the STQ index has been allocated at the decode stage Both AGU issue queue and FP issue queue carry the STQ index for STA and FSTD respectively The FP pipeline sends the data to STQ entry using the carried index.

## 7 System Instruction Handling

We usually called the instructions carried with TempCC ptag as system instructions The typical instructions are MSRMRS and they are dispatched to ALU IQtempCC However there are also some system instructions related to cache and TLB and they are decoded like STA uops and dispatched to AGU IQ The next sections from 71 to 75 are for MSRMRS and section 76 gives a brief introduction to those special system instructions.

### 7.1 Issue

IEX will always issue MSRMRS uops in order which means a younger MSRMRS uop will not be issued until older MSRMRS uops issued.
MSRMRS is waked up by TempCC ptag and TempCC ptag is partitioned for different thread Therefore the MSRMRS can only be waked up by the older MSRMRS uop in the same thread.
For timing reason MSRMRS is wakep up when producer is at I1 stage That means for MSRMRS in the same threshold it can be picked once every two cycles in the fastest case But for different threshold the MSRMRS pick can still be fullpipelined as following example shows.
Example1 MSR1 followed by MRS2 and they are both from T0Thread0.
| T0 MSR1 | P1 | I1 | I2 | E1 |
| --- | --- | --- | --- | --- |
| T0 MRS2 | P0 | P1 | I1 | I2 |

Example2 MSR1 followed by MRS3 and they are both from T0Thread0 MSR2 followed by MRS4 and they are both from T1Thread1.
| T0 MSR1 | P1 | I1 | I2 | E1 | E2 |
| --- | --- | --- | --- | --- | --- |
| T1 MSR2 | P0 | P1 | I1 | I2 | E1 |
| T0 MRS3 | P0 | P1 | I1 | I2 |  |
| T1 MRS4 | P0 | P1 | I1 |  |  |

Some MSRMRS uops have special attributes for Nonflush and Nonspeculative Nonflush means there is no flush before the picked uop which has to wait until nonflushed RID rob entry index equals to its own RID It is to avoid the side effect of the uop in the false path Nonspeculative means the picked uop is the oldest one in the core to avoid the side effect of the MSRMRS speculatively issued.
It is noted that MSR is either Nonflush or NonSpeculative but MRS is not Most MRS uops are speculative while some MRS uops are Nonspeculative The fastest case for Nonflush and Nonspeculative MSRMRS uops is as follows.
Example3 MSR2 is Nonflushed MSR2 will be picked only after MSR1 update nonflushed RID The fastest latency is 10 cycles.
| MSR1 | P1 | I1 | I2 | E1 | E2 | E3 | E4 | E5 | E6 | E7 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| tempccwk | Resolve | Update |  |  |  |  |  |  |  |  |

NonFlush r2
| MRS2 | P0 |
| --- | --- |

Example4 MRS2 is nonflushed But MRS1 does not block nonflushed RID The fastest latency is still 2 cycles noflush rid.
| MRS1 | P1 | I1 | I2 | E1 |
| --- | --- | --- | --- | --- |

Tempccwk
| MRS2 | P0 | P1 | I1 | I2 |
| --- | --- | --- | --- | --- |

Example5 MSR2MRS2 is Nonspeculative MSR2MRS2 will be picked only after MSRMRS1 update nextretired RID The fastest latency is 10 cycles.
| MSRMRS1 | P1 | I1 | I2 | E1 | E2 | E3 | E4 | E5 | E6 | E7 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| tempccwk | Resolve | Update |  |  |  |  |  |  |  |  |

Nextret r2
| MRSMRS2 | P0 |
| --- | --- |

### 7.2 Speculative nonspeculative MRSMSR

The following table shows the details of different MSRMRS uops.
| Type | srcc | Src0 | Src1 | dst | in order |
| --- | --- | --- | --- | --- | --- |
| MSR nonflush | Phseudo |  |  |  |  |

Dependency
| for ordering | source tag | always ready | no use | compare non flush rid younger uop will hold |
| --- | --- | --- | --- | --- |
| MSR nonspeculative | Phseudo |  |  |  |

Dependency
| for ordering | source tag | always ready | no use | compare next retired rid younger uop will hold |
| --- | --- | --- | --- | --- |
| MRS nonspeculative | Phseudo |  |  |  |

Dependency
| for ordering | always ready | always ready | destination tag | compare next retired rid younger uop will hold |
| --- | --- | --- | --- | --- |
| MRS speculative | Phseudo |  |  |  |

Dependency
| for ordering | always ready | always ready | destination tag | NA |
| --- | --- | --- | --- | --- |

Since there is a group of system interfaces for MSRMRS only one MSRMRS is picked in the pipeline Besides MRS need send resolve to OOO but MSR does not send extra resolve to OOO because it can be resolved from system interfaces from IEX to OOO.
A special system instruction is ISB it is processed as a speculative MSR uop It has no source and no destination and need compare nonflush pointer to set ready.

### 7.3 Latency

For the relation between dst id and target module MRS latency is as following It is noted that except L3C MRS if latency more than 4 the MRS is called as far MRS.
| Target module | dst id | MRS latency |
| --- | --- | --- |
| OOO | SRLDST SRHDST | 4 |
| IEX | IEXDST | 4 |
| MMU | MMUDST | 4 |
| DTU | DBGDST ETMDST BRBDST PMUSPUDST IFUDST LSUDST | 7 |
| L2 | L2CDST GICDST TIMDST | 7 |
| L3 | L3CDST L3CG2DST | variable |

### 7.4 L2C Credit

Some special MSR uops to L2C Level 2 cache need handshakelike credit signals from L2C There is a credit counter in the pipeline for each thread and the max value is 3 The counter subtracts one if the MSR is issued successfully while the counter adds one when receiving credit signal Therefore iex will not send more than 3 write requests if not receive the credit signals The special system registers are.
Iccdirel1 iccsgi0rel1 iccsgi1rel1.
Iccasgi1rel1 icceoir0el1 icceoir1el1.
From the interface protocol with IEX and L2C all credit signals will be returned to IEX before the core power off To achieve this the next two corner cases are fulfilled.
| 1 | WFX mode |
| --- | --- |

When the core enters into the WFX mode the core clock will close If the gicc return ack L2C will set the gicwfxwakeup signal and enable the core clock temporarily to make sure the credit return to IEX.
| 2 | The thread turnoff |
| --- | --- |

In multithread mode if credit has not returned before the current thread turn off In this case the core clock is not closed So the IEX box and feedthrough module are able to receive the credit.

### 7.5 L3C MRSMSR

#### 7.5.1 Overview

Because core and L3C Level 3 cache are in the different clock domain L3C MSRMRS need to go through the asynchrony bridge To achieve this there is a handshaking mechniasm that iex will not send writeread request until receiving the previous writeread acknowledge The datapath latency is variable To forbid some unexpected cases all the L3C MSRMRS can be nonspeculative by configuring the configuration override bit in OOOCTLREL1[57]

#### 7.5.2 L3C MRSMSR Resolve flow

IEX empty is related to the process of core power off In order to guarantee L3C SYS uops are finished IEX cannot be send resolve to ooo until receiving the acknowledge from L3C The process of L3C MSR resolve is as below.
| 1 | OOO dispatches a L3C SYS uop to IEX ALU issq |
| --- | --- |
| 2 | After IEX issues a L3C SYS uop at E1 stage output request and data to L3C IEX will not output any resolve information to inform ooo that the L3 MSR has been issued |
| 3 | L3C handle the MSRMRS and return the acknowledge to IEX |
| 4 | After receiving the acknowledge IEX will start a reflow pipe |
| 5 | When the L3C reflow is finished IEX send L3C resolve information like dstid offset wrvld through IEXOOO system interface at E1 stage |

#### 7.5.3 L3 MRSMSR block

In target core L3C MSRMRS is one outstanding After IEX issued L3C MSRMRS in the P1 stage the signal called sysl3cbusy is set valid and is broadcasted to every issue queue for block Sysl3cbusy is cleared when receiving the acknowledge from L3C The diagram is as follows the red means the block stage and the blue means the pick stage.
Because L3C MSRMRS has reflow stage in order to gurantee the program order between L3C SYS and normal MSR which may have effect on L3C system state L3C SYS will also block normal MSR of the same thread.
| Single thread | L3 MSRMRS | not L3 MSR | not L3 MRS |  |
| --- | --- | --- | --- | --- |
| L3 MSRproducer | Block | Block | no Block |  |
| L3 MRSproducer | Block | Block | no Block |  |
| Multi thread | L3 MSRMRS | not L3 MSR |  |  |
| Same thread | not L3 MSR |  |  |  |
| different thread | not L3 MRS |  |  |  |
| L3 MSRproducer | Block | Block | no Block | no Block |
| L3 MRSproducer | Block | Block | no Block | no Block |

For SMT mode L3C SYS blocks normal MSR of the same thread and all L3C SYS.

#### 7.5.4 V85 Random instruction RNDRRNDRRS support

According to Arm Spec Random number instructions RNDRRNDRRS returns a 64-bit random number which is reseeded from the True Random Number generator If the hardware returns a genuine random number PSTATENZCV is set to 0b0000 if not in a reasonable period of time PSTATENZCV is set to 0b0100 and the data value returned in UNKNOWN.
In MicroArchitecture OOO will separate RNDRRNDRRS to two uops one is a L3C MRS and another is an ALU uop depending on the MRS by int ptag and update cc ptag RNDR and RNDRRS are distinguished by the sysoffset signals which L3C use to reseed the random number IEX will issue the L3C MRS nonspeculatively If the data was valid when ack returned the random ready will set to 1 and if not the random ready signal will set to 0 However whether the random data is ready or not when L3C MRS is reflowed it will wake up the ALU uop and update the CC register file as expected.
The picture shows the L3C MRS and ALU uop relationship When L3C MRS is reflowed at e2 stage it will wkup the ALU uop If the ALU uop issues at next cycle it can get the correct ready signal at its i2 stage If the ALU uop cannot issue immediately because of the ready signal is holding it can get the ready signal at its i2 stage as well.

### 7.6 Special sys uop to LSU

Some special system uop are decoded like STA uops and dispatched to AGU IQ and they are further compressed to 12bits information and sent to LSU For The details could refer to the following excel.
[internal reference removed]

## 8 Instruction Fusion

### 8.1 Overall

For SpecInt benchmark it is common to see the pair of an alu instruction update NZCV and a following bcond instruction use NZCV to calculate taken or not taken Without fusion the branch instruction cannot execute before the ALU instruction calculate the NZCV value In the most ideal case one extra cycle is need to bypass the NZCV from ALU to BRU If the bcond can be fused with the ALU update nzcv and execute two instruction in the same cycle it is good for performance.
Table 81 BRU Fusion instruction.
| instruction | type | execution option |  |
| --- | --- | --- | --- |
| HEAD | ADDS immediate | AU |  |
| SUBS immediate | AU |  |  |
| ADDS shifted register | AU | when imm6 is 000000 |  |
| SUBS shifted register | AU | when imm6 is 000000 |  |
| ADDS extended register | AU | when imm3 is 000 sf1 option is x11 when imm3 is 000 sf0 option is x1x |  |
| SUBS extended register | AU | when imm3 is 000 sf1 option is x11 when imm3 is 000 sf0 option is x1x |  |
| ANDS immediate | LU |  |  |
| BICS shifted register | LU | when imm6 is 000000 |  |
| ANDS shifted register | LU | when imm6 is 000000 |  |
| TAIL | bcond | BRU | Only support cond EQNE CSCC HILS GELT GTLE |
| bccond | BRU |  |  |

Note
The integer destination registers of ALUcc instruction must be zero register.
Other instruction pair not meet all requirement dispatch two separate uops.
If there is an exception between the alucc and bcond at D3 cancel branch fusion dispatch two separate uops.

### 8.2 Basic flow

To implement all fusion pattens OOO side detects the instruction sequence before D3 if found two instructions are at detect range then set fusion flag flopped to D3 stage OOO dispatch set fusion valid bit in issq packet information At S1 dispatch two instruction to IEX with TAIL instruction carry with uopvld and fbcvld and HEAD instruction with uopvld equal to zero.
For bcond fusion IEX will merge the info of HEAD and TAIL instruction into BRU issq At S2 stage IEX will merge and write all needed packet info from two UOP into one BRU ISSQ entry At P1 stage if the fused uop is being picked merged info send to BRU pipe At E1 stage BRU will execute the HEAD instruction and bypass the CC result to TAIL instruction At E2 stage only one resolve signal will be sent to OOO and OOO use this signal to commit two instructions.

### 8.3 Fusion pattern

Target core do not support cross-cycle fusion all fusion uops pair must dispatch in the same cycle If the possible HEADTAIL uop pair appears in two lanes OOO dispatch them as two instrucitons.
In current design only two backtoback uops can be fused together Two uops with another uop located between alucc and bcond cannot be fused OOO should dispatch them separately.

#### 8.3.1 Four pairs in one clycle

#### Figure 81 four pairs in one cycle

The maximum number of fused uops in one cycle is four In this case all even uops should be HEAD and all odd uops should be TAIL.

#### 8.3.2 Three pairs in one cycle

#### Figure 82 three pairs in one cycle

If three fusion pairs appear in one cycle there are ten situations.

#### 8.3.3 Two pairs in one cycle

#### Figure 83 two pairs in one cycle

If two pairs do fusion in one cycle there are fifteen situations.

#### 8.3.4 One pair in one cycle

#### Figure 84 one pair in one cycle

If one pair do fusion in one cycle there are seven situations.

### 8.4 BRU fusion implementation

#### 8.4.1 BRU ISSQ entry bit reuse

When BRU fusion happens one BRU entry have to store both alucc and bcond uop information In this case some alucc information is stored in some entry bit which bcond uop may not used.
For fusion reuse policy refer to svn below for more detail.
[internal reference removed]

#### 8.4.2 Execution data path

Because of the fusion uop only dispatch to BRU ISSQ AULUFLAGCCPASS logics are duplicated and simplified in BRU DP The ALUcc uop are executed in BRU DP and all ALU source data are directly given to BRU DP Condition code is calculated in BRU DP and bypass CC result to bcond logic to judge branch result At E1 stage BRU will write back CC data to register file.
BRU DP also need to process normal condition branch uop For timing consideration BRU pipe will simultaneously output the payload of normal branch uop and the payload of fusion pair BRU DP use the fbcvld signal to select the payload from fusion or from origin uop goes to the execution unit.
| 9 | SVE Instruction Support in IEX |
| --- | --- |

### 9.1 Overview of SVE Instructions IEX handling or involved

The majority of SVE Data Processing DP instructions are handled by FSU while a small number of SVE DP instructions or uops broken down from them are executed in IEX A simple rule is that SVE DP uops with Vector Register Sources or Predicate Register Sources are execute in FSU while SVE DP uops with General Register Sources or CC Register Sources are executed in IEX This mainly relies on where those register files are physically located A few instructions need to handle by both FSU and IEX and they are broken down with F2I incl P2I Predicate to Integer or I2F incl I2P Integer to Predicate uops.
All SVE load/store memory instructions execution are handled by LSU + FSU + IEX after decoding by OOO.
In IEX we look at the SVE instructions from two views 1 Data Processing or load/store Memory Accessing 2 With Predicate Register or Without Predicate Register.
Predicate Register is an important structure newly introduced in SVE instruction set The Predicate Register File is physically located in FSU It has two write ports dedicated for IEX to execute WHILExx instructions and write results directly to the Predicate RegFile.
The predicate register source tags need to wakeup And in IEX a Predicate RegFile ready table is duplicated.

#### Figure 11 1 IEX in SVE Control and DataPath Structure

### 9.2 SVE DP without Predicate handled by IEX

SVE without predicate instructions are mainly divided into three types 1-cycle au 1-cycle lu and 2-cycle saturation.
| 3 | 1-cycle au instructions mean that instructions are realized in arithmetic unit au with 1-cycle latency The au module does 32bit64bit addition or subtraction arithmetic operations |
| --- | --- |
| 4 | 1-cycle lu instructions mean that instructions are realized in logic unit lu with 1-cycle latency The lu module does all the typical logic operation such as mov and orr eor |
| 5 | 2-cycle saturation instructions mean that instructions are realized in arithmetic saturation and extension with 2-cycle latency Timing is not enough and need 2-cycle latency for predicate instructions WHILExx In order to reduce resources logical resource reuse is implemented for without predicate 2-cycle saturation instructions and WHILExx |
| 6 | 1-cycle au and 1-cycle lu instructions are supported in ALU0ALU5 And 2-cycle saturation instructions are only supported in ALU2ALU5 |

### 9.3 SVE DP with Predicate handled by IEX

SVE with predicate instructions are mainly divided into two types 1-cycle au and 2-cycle.
| 1 | 1-cycle au instructions mean that instructions are realized in arithmetic unit au with 1-cycle latency The au module does 32bit64bit addition or subtraction arithmetic operations |
| --- | --- |
| 2 | 2-cycle saturation instructions mean that instructions are realized in arithmetic saturation and extension with 2-cycle latency |
| 3 | 1-cycle au instructions are supported in ALU0ALU5 And 2-cycle saturation instructions are only supported in ALU2ALU5 |
| 4 | For series INCP scalar \ SQINCP scalar instructions are dispatched into 2 uops FSU counts the number of active elements in the source predicate Pg and IEX uses the result to increment decrement the scalar destination |
| 5 | For series WHILExx scalar IEX compare operand1 with operand2 generate predicate result resp and condition flags NZCV based on the predicate result |

### 9.4 SVE Contiguous load/store

SVE contiguous load/store mainly realize in agu iq so agu iq diagram as followSVE contiguous load/store mainly srcp process include srcp wakeup and srcp data bypass detailed describe as follows.
Agu iq diagram
Whilex uop predicate.
Whilex uop dstp is valid ISSQ need to carry dstp to alu pipe line the dstp wake up srcp in AGU ISSQ also wakeup srcp in fsu at the same time write dstp data to predicate RF in FSU.
Fsu always send dstp regfile data to lsu ldst srcp data For STALDA uopat I1 stage IEX will send out the srcp tag to FSU and IEX has the highest priority to read PRF Then pipe control logic will cam out the result whether STALDA need bypass srcp data from ALU result IEX will send out the signal to tell LSU get the data from bypass or FSU PRF.
Srcasp is valid when Scalar LDST and contingous LDST uop Rn equal 31.

### 9.5 SVE Gather Load and Scatter Store

For gather loadscatter store instructions OOO will separate out normal LDASTA and F2I uops as following In order to share highlow data and small shift with signedunsigned extend only on SRC1 Gather loadScatter store always decode vector address on SRC1 For vector plus imm instructions the imm data is on SRC0.
For SVE gather load.

- OOO will separate it to several normal LDA uops.
- OOO will allocate one ldm index for every lda uop.
- OOO will send maskvld to IEX and IEX reuse the dsty tag to store the maskvld and srcptag.
- IEX will use F2I data send from FSU For even number load uop IEX use the low 32 bit F2I data But for odd number load uop IEX use high 32 bit F2I data.
- IEX handle it as the normal LDA uops.

For SVE scatter store.

- OOO will separate it to several normal STA uops.
- IEX will use F2I data send from FSU For even number load uop IEX use the low 32 bit F2I data But for odd number load uop IEX use high 32 bit F2I data.
- IEX handle it as the normal STA uops.

### 1.0 PAC Instruction Handling in IEX

Pointer Authentication named ARMv83PAuth is mandatory in ARMv83 implementations And in ARMv86 it is further enhanced [6]
Here the socalled PAC instructions are the ARMv83PAuth instructions with ARMv86 extension supported EnhancedPAC2 Inserting PAC using XORing instead of replacing scheme and FPAC generating PACFail synchronous exception when AUT failing.
| Instruction | Address | Modifier | Key | Class | PAC Type |
| --- | --- | --- | --- | --- | --- |
| PACIA | Xd | Xn|SP | APIA | Basic | Add |
| PACIA1716 | X17 | X16 | APIA | Basic | Add |
| PACIASP | X30 | SP | APIA | Basic | Add |
| PACIAZ | X30 | 0 | APIA | Basic | Add |
| PACIZA | Xd | 0 | APIA | Basic | Add |
| PACIB | Xd | Xn|SP | APIB | Basic | Add |
| PACIB1716 | X17 | X16 | APIB | Basic | Add |
| PACIBSP | X30 | SP | APIB | Basic | Add |
| PACIBZ | X30 | 0 | APIB | Basic | Add |
| PACIZB | Xd | 0 | APIB | Basic | Add |
| PACDA | Xd | Xn|SP | APDA | Basic | Add |
| PACDZA | Xd | 0 | APDA | Basic | Add |
| PACDB | Xd | Xn|SP | APDB | Basic | Add |
| PACDZB | Xd | 0 | APDB | Basic | Add |
| PACGA | Xn | Xm|SP | APGA | Basic | Add |
| AUTIA | Xd | Xn|SP | APIA | Basic | AUTexc |
| AUTIA1716 | X17 | X16 | APIA | Basic | AUTexc |
| AUTIASP | X30 | SP | APIA | Basic | AUTexc |
| AUTIAZ | X30 | 0 | APIA | Basic | AUTexc |
| AUTIZA | Xd | 0 | APIA | Basic | AUTexc |
| AUTIB | Xd | Xn|SP | APIB | Basic | AUTexc |
| AUTIB1716 | X17 | X16 | APIB | Basic | AUTexc |
| AUTIBSP | X30 | SP | APIB | Basic | AUTexc |
| AUTIBZ | X30 | 0 | APIB | Basic | AUTexc |
| AUTIZB | Xd | 0 | APIB | Basic | AUTexc |
| AUTDA | Xd | Xn|SP | APDA | Basic | AUTexc |
| AUTDZA | Xd | 0 | APDA | Basic | AUTexc |
| AUTDB | Xd | Xn|SP | APDB | Basic | AUTexc |
| AUTDZB | Xd | 0 | APDB | Basic | AUTexc |
| XPACD | Xd | Basic | Strip |  |  |
| XPACI | Xd | Basic | Strip |  |  |
| XPACLRI | X30 | Basic | Strip |  |  |
| BRAA | Xn | Xm|SP | APIA | Combined | AUT |
| BRAAZ | Xn | 0 | APIA | Combined | AUT |
| BRAB | Xn | Xm|SP | APIB | Combined | AUT |
| BRABZ | Xn | 0 | APIB | Combined | AUT |
| BLRAA | Xn | Xm|SP | APIA | Combined | AUT |
| BLRAAZ | Xn | 0 | APIA | Combined | AUT |
| BLRAB | Xn | Xm|SP | APIB | Combined | AUT |
| BLRABZ | Xn | 0 | APIB | Combined | AUT |
| RETAA | X30 | SP | APIA | Combined | AUT |
| RETAB | X30 | SP | APIB | Combined | AUT |
| ERETAA | ELR | SP | APIA | Combined | ERET |
| ERETAB | ELR | SP | APIB | Combined | ERET |
| LDRAA | Xn|SP | 0 | APDA | Combined | AUT |
| LDRAB | Xn|SP | 0 | APDB | Combined | AUT |

#### 1.0.1 Overview of PAC instructions handling

The following features are supported in handling pointer authentication instructions.
| 1 | In target core IDAA64ISAR2EL1[1512] APA3 0101 and IDAA64ISAR2EL1[118] GPA3 0001 So we support QARMA3 algorithm for Address Authentication and Generic Authentication HaveEnhancedPAC2 TRUE HaveFPAC TRUE HaveFPACCombined TRUE HaveCONSTPACFIELD TRUE for all PAC instructions |
| --- | --- |
| 2 | All the basic AUT instructions will generate PACFail exception if PAC authentication is failed AUTIASP AUTIAZ AUTIA1716 AUTIBSP AUTIBZ AUTIB1716 AUTIA AUTDA AUTIB AUTDB AUTIZA AUTIZB AUTDZA AUTDZB |
| 3 | All the combined AUT instructions will generate PACFail exception if PAC authentication is failed RETAA RETAB BRAA BRAB BLRAA BLRAB BRAAZ BRABZ BLRAAZ BLRABZ ERETAA ERETAB LDRAA LDRAB |
| 4 | All PAC instructions are only supported in ALU2 and ALU5 ISSQ In target core the strip and AUT uops are picked with 2-cycle latency while other uops are picked with 4-cycle latency |
| 5 | Resolving of the AUT instructions should be accompanied with exception information whether exception valid and which exception info Because the exception information is available at E5 stage in IEX so the resolving of this pipe is combined with E5 stage resolving AUT and E2 stage resolving other than AUT |

## 6 Accessing to the Pointer Authentication Keys For each thread there are five 128-bit keys implemented in IEX which are APDAKeyHiLoEL1 APDBKeyHiLoEL1 APGAKeyHiLoEL1 APIAKeyHiLoEL1 and APIBKeyHiLoEL1 The read MRS to these Key registers can be speculative The write MSR to these Key registers looks at nonflush RID pointer All the Key registers will be set to zero after reset

The Key registers are not selfsync system register Software may add an ISB inst after MSR Key to keep the flow in order or the hardware will not make sure the correctness of the flow.
Keys are not banked by Exception level Arm expects software to switch the keys between Exception levels typically by swapping the values with zero so that the current key values are not present in memory from Arm Spec.
| 7 | ERETAA and ERETAB instructions are specially handled together with OOO refer to 1042 for details ERETAAB need to wait for nextretiredrid to issue from IEX |
| --- | --- |
| 8 | All the PAC instructions will neither start a new rid group nor end a rid group Because AUT instructions may generate fault all the basic and combine AUT instructions will start a new rid group Besides all the combine branch AUT instructions will end a rid group |

The critical information is concluded in the next chart but just for target core implementation.

#### 1.0.2 AUT Resolve with Exception

To help handle OOOs exception the AUT with exception uops will delay resolved at E5 stage the same cycle with corresponding fault and exception info.
When an AUT uop is resolved at E5 stage the new resolved uop at E2 stage has to be blocked It is implemented by Reflow block mechanism It can be illustrated as following diagram shows When picked AUT is at I2 stage it will generate a reflow p0 signal to block issue queue entry Therefore ALU uops will be picked in next cycle when AUT is at E1 stage to avoid resolve conflict.

#### 1.0.3 Basic PAC Instruction Handling

##### 1.0.3.1 AddPAC instructions

PACIA PACIA1716 PACIASP PACIAZ PACIZ PACIB PACIB1716 PACIBSP PACIBZ PACIZB PACDA PACDZA PACDB PACDZB PACGA.
These instructions consist one uop with mainly 2 source registers and 1 destination registers One source contains the original address the other one source contains the modifier and the final PAC address is generated according to the key algorithm The execution latency is 4 cycles and the instructions will be resolved at E2 stage.

##### 1.0.3.2 AutPAC instructions

AUTIA AUTIA1716 AUTIASP AUTIAZ AUTIZA AUTIB AUTIB1716 AUTIBSP AUTIBZ AUTIZB AUTDA AUTDZA AUTDB AUTDZB.
These instructions are used for authenticating PAC address that generated by the above AddPAC instructions and stripped PAC address if result is match The execution latency is 2 cycles and the instructions will be resolved at E5 stage because the result of authentication fail is generated at E4 stage.

##### 1.0.3.3 Strip PAC instructions

XPACD XPACI XPACLRI.
These instructions are just used for stripping PAC address The execution latency is 2 cycles and the instructions will be resolved at E2 stage.

#### 1.0.4 Combined PAC Instruction Handling

##### 1.0.4.1 Generic combined instruction

RETAA RETAB BRAA BRAB BLRAA BLRAB.
BRAAZ BRABZ BLRAAX BLRABZ.
These instructions need to be divided in two uops One for pointer authentication and the other for a normal RET BR or BLR.
| uop | src1 | src2 | dest |
| --- | --- | --- | --- |
| AUT | Xn | SPXm | tmp |
| RETBRBLR | tmp |  |  |

##### 1.0.4.2 ERETAA ERETAB

ERETAAB instructions only need one uop for pointer authentication AUT executed in IEX.
If the authentication passes ERETAAB is executed like a normal ERET and return back from exception handling process If the authentication fails ERETAAB generates a new exception and the restart PC is the authenticated address through OOOIEX interface A Translation fault will be generated when IFU to fetch instructions using the faultinjected address.
Note
| uop | src1 | src2 | dest |
| --- | --- | --- | --- |
| AUT | SP |  |  |

The exception handler execution.
| 1 | When an exception is generated the processor will enter the exception handler OOO hardware updates ELR as normal PAC code is not added by hardware |
| --- | --- |
| 2 | The pointer authentication code should be added to the ELR register by software PACIA instruction The example codes are shown above |
| 3 | Before exception return ERETAA or AUTIA + ERET can be used also is used to do authentication |
| 4 | If the authentication passes the PE continues execution at the target address pointed by the clean ELR generated by AUT of ERETAA If the authentication fails a Translation fault will be generated when IFU to fetch instructions using the faultinjected ELR address |

#### Figure 104 The interface for ERETAA ERETAB

| signal | IO | width | description |
| --- | --- | --- | --- |
| oooiexpacelrt01 | I | 64 | ELR register channel from ELR handler to IEX |
| iexooopacelrautt01 | O | 64 | Authentication result output to ELR handler at E5 |

To maintain the following program order ERETAAB depending on the ELR value directly from OOO to IEX has to wait for nextretiredrid to issue So OOO can capture the New ELR value And after ERETAAB resolved there is an ooo flush occur make sure that the next ERETAAB must get the right value.
MSR ELR x0
ERETAA

##### 1.0.4.3 LDRAA LDRAB

These two instructions need to be divided into two uops one for authentication uop the other for load uop with offset This instruction authenticates an address from a base register using a modifier of zero and the specified key adds an immediate offset to the authenticated address and loads a 64-bit doubleword from memory at this resulting address into a register.
| uop | src1 | src2 | dest |
| --- | --- | --- | --- |
| AUT | Xn | SP | tmp |
| LDR | tmp | Rt |  |
| ALU | tmp | Xn |  |

If the authentication passes the PE behaves the same as for an LDR instruction If the authentication fails a Translation fault is generated modify the bits of the result and no output signal of translation fault in IEX The authenticated address is not written back to the base register unless the preindexed variant of the instruction is used In this case the address that is written back to the base register does not include the pointer authentication code.
