# OOO ROB, Exceptions, PC, SMT, and SVE

This document covers ROB organization, commit and non-flush behavior, exception handling, PC buffering, SRF behavior, SMT controls, resource sharing, SVE rename support, and related traps.

> This Markdown document is the maintained specification. Sensitive metadata and internal reference labels have been removed while preserving architecture semantics.

### 6.4 Reorder Buffer

#### 6.4.1 Block Diagram

#### Figure 6 33 ROB block diagram

Robdecinf decode interface handle decode writing request after decoding generate allocation request to each rob entry.
Robbank rob main structure total 128-entry split into 8 bank each bank contains 16 entry cells.
Robexeinf execution unit interface Deal with resolve interface and generate resolve request to each rob entry.
Robctrl rob control module generate commit and flush.

#### 6.4.2 ROB feature

Keep outoforder executed instruction update processor state in program order.
Writing FPSR in program order.
Trigger exception in program order.
Keep outoforder executed instruction release physical resources in program order.
Release PC buffer in program order.
Release load queuestore queue in program order.
Release IFU branch queue in program order.
Decide when to take interrupt.
Decide when nonspeculatively executed instructions can start executing.
Generate ETMBRBE required information including retired instructions PC branch type etc.
Generate PMUAMU required information.

#### 6.4.3 Grouping concept

##### 6.4.3.1 Grouping concept

Instead of each instruction occupy a rob entry a groups of instructions share a rob entry they have the same robid committedflushed under same condition.
Group start
All instructions that may generate exception will start a group the 1st instruction of pair fusion and the instruction which bid increment including.
Load/store
System instructions which are handled in load/store pipeline DSBDMBCLREXDCICTLBIATRAMINDEX.
MSRMRSISBWFIWFEWFITWFETSEVSEVL
Instructions that already detected exception before execution such as undefined instruction iabort breakpoint SVC HVC SMC BRK HLT DCPS MRS instruction trap etc.
The 1st instruction of pair fusion exclude bcond fusion.
The instruction which bid increment because there is only 1 bid increment flop in rob entry.
Group end
All instructions that may generate a flush next request or for some special cases will end a group including.
Branch
All system instructions.
An instruction which is going to allocate a new pc buffer entry Its used to guarantee only one pc buffer index can be stored in one ROB entry.
Ifuooopredictxd1 is valid and instruction has been aeecpt by decode.
Special instruction grouping.
Following instruction will be in a single group.
All system instruction except NOPYIELD including DSBDMBCLREXDCICTLBIATRAMINDEXMSRMRS ISBWFIWFESEVSEVLHVCSVCSMC.
If configuration override bit is enabled it is used to force each instruction in one group.
In earlier-generation core a group can have up to 4 instructions and the max number in one group could be 12 or 4 which can be controlled by the configuration override bit of ooofrcgrpinstnum.
Below is an example of grouping.
| ADD | robid 0 |
| --- | --- |
| LDR | robid 1 Load will start a group |
| BEQ | robid 1 |
| ADD | robid 2 Last instruction is BEQ it will end group 1 |
| SUB | robid 2 |
| ADD | robid 2 |
| SUB | robid 2 |
| ADD | robid 3 Group 2 already has 4 instructions |

Interrupt can only be taken at group boundary.

##### 6.4.3.2 Grouping Implementation

Decode requirement.
Groupstart
Whether an instruction will start a group not only depends on itself such as load/store exception but also depends on the previous instruction such as branch group full.
In order to avoid the case that the group comp is not set because the instructions of same group are blocked in different stages the group start will set on the 1st blocked instruction and group comp is bypassed to the last not blocked instruction Beside if no instruction is decoded next cycle then when resumed decoding the new instruction will start a new group.
Calculate robid
The robid calculation logic is at D2 stage robid is accumulated by group First we need grouping the instructions by generating groupstart signal and then generate robid for each instruction.

#### 6.4.4 ROB format

Table below shows the main bits of rob entrys format.
| Name | width | Notes |
| --- | --- | --- |
| vld | 1 | Valid bit |

Set on allocation
Clear on flush or commit.
| wrap | 1 | wrap bit Used for to determine which rob entries should be flushed |
| --- | --- | --- |
| Grpcomp | 1 | Rob entry has collected all instructions of that group |

Can be set if
| 1> | Decrobgrpcompd3 is set |
| --- | --- |
| 2> | Data abort or nuke when lsu send exc to rob |

Clear on flush or commit.
| bid | 1 | Branchid for IFU BP use |
| --- | --- | --- |

IFU send 7-bit bid to OoO Based on index accumulation nature rob do not save 7-bit bid When bid of the instruction is accumulated by one it will start a new group Therefore.
Bid whether bid changed for the first instruction of a group compared with last group.
| Brvld | 1 | The group contains branch uop |
| --- | --- | --- |
| Ldvld | 1 | the group contains Load uop will calculate number of load |
| Stvld | 1 | the group contains store uop will calculate number of store |
| Fpvld | 1 | the group contains fp uop |
| Setffrvld | 1 | the group contains setffr uopreuse for bti |
| stnumb | 3 | store queue entry number in compressed manner qualified by stvld |

000> 1 store
001> 2 store
010> 3 store
011> 4 store
100> 5 store
101> 6 store
110> 7 store
111> 8 store
| ldnumb | 3 | load queue entry number in compressed manner qualified by ldvld |
| --- | --- | --- |

000> 1 load
001> 2 load
010> 3 load
011> 4 load
100> 5 load
101> 6 load
110> 7 load
111> 8 load
| brtype | 3 | To distinguish branch property of direct or indirect push or pop whether is RET etc |
| --- | --- | --- |
| pcbuf | 1 | pc buffer based valid On commit will release pc buffer entry |

Iex bru miss resolve can also update pcbuf.
| exc | 1 | exception bitSet on allocation stageUNDFTRAPetc or resolve stage LSU exception |
| --- | --- | --- |
| Excnxt | 1 | For SVCISBERETWFx type exception commit the instruction then flush pipeline |
| Exctype | 2 | Exception type |

2b00 decodesrf detected exception.
2b01 IEX AUT exception.
2b10 nuke exception.
2b11 LSU exception.
| instcnt | 2 | number of instruction in a group |
| --- | --- | --- |

2b00 contains 1 inst.
2b01 contains 2 inst.
2b10 contains 3 inst.
2b11 contains 4 inst.
| nfrdy | 1 | the entry is ready for nonflush |
| --- | --- | --- |
| nfrcnt | 4 | nonflush resolve count |

Including ldst agen resolve aut instruction resolve and srf resolve.
| Grprcnt | 5 | Including ALU FSU load/store and Srf resolve count |
| --- | --- | --- |

The max num of grp rcnt in one rob entry is 20.
| Bruresolved | 1 | Indicate that branch instruction has been resolved |
| --- | --- | --- |

Set to 0 on allocation.
If brvld is 0 no need for bruresolved.
If brvld is 1 waiting for bruresolved to noflush and commit.
| Brumiss | 1 | Indicate branch miss predicate which need generate bruflush and update pcbuf |
| --- | --- | --- |
| Brutaken | 1 | Indicate branch is taken |
| commitrdy | 1 | the entry is ready for commit |
| esrrt | 5 | Rt field of LS instruction for updating of ESR |
| esrsf | 1 | SF field of LS instruction for updating of ESR |

Qfbit can be deleted from rob entry for a new mechanism.
Because FPSR just record the exception information which means it only record the qfbit from 0 to 1 Therefore we can make a qfbit index table out of the entry to record the oldest update information of each bit.
Qfbit index table
| Qfbit | 6 | 5 | 4 | 3 | 2 | 1 | 0 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Oldest rid | 13 | x | 13 | x | x | 18 | 34 |
| Oldest vld | 1 | 0 | 1 | 0 | 0 | 1 | 0clear by bru flush |

After fsu resolve if corresponding qfbit is updated from 0 to 1 then need to store the rid and vld in the index table Rid is chosen by rid compare logic to record the oldest information.
When commit ptr is equal to oldest rid update corresponding bit to FPSR and clear the vld Besides ooo flush and bru flush can also clear younger vld.
However there is one barrier in current implementation MSR FPSR is nonspec but not selfsync.
Lets take a simple example.
Robid inst flow qfbit[0]fpsr[0]

13. SIMD fp wr 1
15. SIMD fp wr 1
18. MSR fpsr wr 0
23. SIMD fp wr 1

Because SIMD fp could executed speculatively rid 23 can be executed before rid 18 But the qfbit index table only record rid 13oldest When commit ptr is 23 fpsr is still 0 but it is expected to be 1.
The solution is to make MSR FPSR selfsync and it can be optimized to only selfsync when MSR FPSR from 1 to 0 Then in above example rid 23 should be refetch and reexecuted after flush The fpsr could be update to 1.

#### 6.4.5 ROB commit

Set commit ready
ROB need to make sure all the instructions are executed we introduce resolvecount rcnt.
The rcnt is split into 4 category ALUrcnt LSUrcnt FSUrcnt branchrcnt The initial rcnt is set by decode in 2binary complement manner each time there is a resolve the related rcnt will be accumulated only when all the rcnt is set to 0 commitready will be set If the group does not contain an instruction type the related rcnt will be set to ZERO.
The resovle port to rob is shown as following table.
| Resolve module name | descriptions |
| --- | --- |
| Srf | MSRWFxSVC etc |
| Bru0 | Branch0 instruction |
| Bru1 | Branch1 instruction |
| Bru2 | Branch2 instruction |
| Bru buf | Branch buf instruction brumiss is always 1 |
| ALU03 | ALU03 instruction including 2 ports |
| ALU14 | ALU14 instruction including 2 ports |
| ALU25 | ALU25 instruction including 2 ports |
| FSU0 | FSU0 instruction resolve |
| FSU1 | FSU1 instruction resolve |
| FSU2 | FSU2 nstruction resolve |
| FSU3 | FSU3 instruction resolve |
| LD0 resolve | Load0 resolve |
| LD1 resolve | Load1 resolve |
| LD2 resolve | Load2 resolve |
| Agenresolveld0 | load0 agen resolve |
| Agenresolveld1 | load1 agen resolve |
| Agenresolveld1 | Load2 agen resolve |
| ST0 resovle | Store0 resolve |
| ST1 resovle | Store1 resolve |
| Agenresolvest0 | Store0 agen resolve |
| Agenresolvest1 | Store1 agen resolve |
| ALU0LDresolve | FSTLF alu0 ld resolve |
| ALU3LDresolve | FSTLF alu3 ld resolve |
| IEX STD0 resolve | Integer store datas resolve |
| IEX STD1 resolve | Integer store datas resolve |
| FSU STD0 resolve | Floatingpoint store datas resolve |
| FSU STD1 resolve | Floatingpoint store datas resolve |

The table below shows the possible scenario of each rcnt.
| name | width | updated by |
| --- | --- | --- |

Group rslv
Including
LS
ALU
| FSU | 5 | lsuoooresolveld0e5 |
| --- | --- | --- |

Lsuoooresolveld1e5.
Lsuoooresolveld2e5.
Lsuoooresolvest0e5.
Lsuoooresolvest1e5.
Srfrobrslve2
Iexoooalu0resxvlde2.
Iexoooalu0resyvlde2.
Iexoooalu1resxvlde2.
Iexoooalu1resyvlde2.
Iexoooalu2resxvlde2.
Iexoooalu2resyvlde2.
Iexoooalu0fstlfldvlde2.
Iexoooalu3fstlfldvlde2.
Iexoooresolvestd0e2.
Iexoooresolvestd1e2.
Fsuoooresolvestd0e2.
Fsuoooresolvestd1e2.
Fsuooores0vldw0
Fsuooores2vldw0
BRU
| 1 | iexooobru0resolvede2 |
| --- | --- |

Iexooobru1resolvede2.
Iexooobru2resolvede2.
Iexooobrubufresolvede2.
Commit pipe
In earlier-generation core the retirement width is changed to be 6 for grouping mechanism.
To commit a group it must match following conditions.
All required resolves has been collected commitrdy means the instruction has been finished execution.
Not in exception handling progress because the commit ready may not cleared yet after exception.
No pending interrupt or even if interrupt is pending it is blocked.
Wait intccvfp mpq commited all ptags when commitrid equals to commitwaitrid.
Commitwaitrid when commit occurs commitwaitrid first becomes the wrapped commit pointer Then during the recover period if there is any flush adjust the commitwaitrid to the oldest flushrid.
A special scheme when etmbrbe function enable.
If there is no etm stall the number of released branch instruction and pcoverflow instruction cannot more than 1.
If there is etm stall all branch instruction pcoverflow instruction and instbased exception are not allowed to commit.
If brbe function is enabled the number of released branch taken instruction and pcoverflow instruction cannot more than 1.
Table below shows the pipeline from execution unit resolve to commit.
Load/store resolve to commit pipe.
| cycle | 0 | 1 | 2 | 3 | 4 |
| --- | --- | --- | --- | --- | --- |
| stage | E5 | E6 | R0 | R1 | R2 |
| operation | Prerslv rid | Rslv valid | update rcnt in rob entry | generate commit | commit broadcast |

STD resolve to commit pipe.
| cycle | 1 | 2 | 3 | 4 |
| --- | --- | --- | --- | --- |
| stage | E2 | R0 | R1 | R2 |
| operation | rslv rid |  |  |  |
| rslv vld | update rcnt | generate commit | commit broadcast |  |

DSP resolve to commit pipe.
| cycle | 1 | 2 | 3 | 4 |
| --- | --- | --- | --- | --- |
| stage | S1 | P1R0 | R1 | R2 |
| operation | rslv rid |  |  |  |
| rslv vld | update rcnt | generate commit | commit broadcast |  |

BRU resolve to commit pipe.
| cycle | 1 | 2 | 3 | 4 |
| --- | --- | --- | --- | --- |
| stage | E2 | R0 | R1 | R2 |
| operation | rslv rid |  |  |  |
| rslv vld | update rcnt | generate commit | commit broadcast |  |

ALU resolve to commit pipe.
| cycle | 1 | 2 | 3 | 4 |
| --- | --- | --- | --- | --- |
| stage | E1 | E2R0 | R1 | R2 |
| operation | rslv rid | rslv vld |  |  |
| update rcnt | generate commit | commit broadcast |  |  |

SRF resolve to commit pipe.
| cycle | 1 | 2 | 3 | 4 |
| --- | --- | --- | --- | --- |
| stage | E2 | R0 | R1 | R2 |
| operation | rslv rid |  |  |  |
| rslv vld | update rcnt | generate commit | commit broadcast |  |

FSU resolve to commit pipe.
| a> | Along with resolve FSU unit also send qfbit to OoO FSU output resolve at W0 stage and output qfbit at W3 stage |  |  |  |  |  |  |
| --- | --- | --- | --- | --- | --- | --- | --- |
| cycle | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
| stage | W0 | W1Rm1 | W2R0 | W3R1 | W4R2 | W5R3 | R4 |
| operation | FSU rslv rid |  |  |  |  |  |  |
| FSU rslv vld | add rcnt | update rcnt |  |  |  |  |  |

FSU Qfbit
Generate commit
| write qfbit | qfbit updated |
| --- | --- |
| read qfbit | writing FPSR |

Commit Output
Commit indicate that instruction has been successfully executed all its related resources should be freed including.
PC buffer entry can be released.
Physical register can be release.
On commit rob will output current commit pointer to mapq.
In each entry of mapq it will compare its rid with commit rid free younger mapq entry thus free physical register file.
Store queue can be released.
In rob we keep how many store queues are occupied in a group When committing rob add committed store queue number and send to LSU.
Update FPSR when executing FP instructions with error.
The final FPSR write value is committed groups FP exception bits ORed together.
Send branch information to ETMBRBE for tracing.
Generate flush if exception is detected.
Decide whether to take interrupt or not.
Calculate PC and send to ETM.
There is a PC buffer to maintain all the instructions that changed program flow for example taken branch or exception.
PC is calculated by PCbase + PCoffset When PCoffset > 63 will start a new PCbase.
The instruction that start a new PC base is record in rob as pcbuf field so that when it is committed pc buffer can release an entry.
With rob grouping a group of instructions pc may related to two pc buffer entry due to pcoffset overflow caused by instruction in the middle of group This will makes PC generation logic complicated.
The PC buffer logic should be updated by if uop0 pcoffset > 58 start a new pc buffer This will make sure all the instructions in a group has the same pcbase.
Commit and Flush pipeline.
In the old implementation the pipeline of commit and flush is not in the same cycle which means the commit is one cycle earlier than the flush for the flushnxt sceranios MSRSVCISBERETWFx It is a little bit confused for some sceranios and it has many problems on the resource reuse in mt mode Therefore the pipeline is changed that commit and flush happen in the same cycle The simplified timing sequence of the old and new implemantaton is as follows.
Table1 Old pipeline of commit and flush.
| Commit pipeline | R1 | R2 | R3 | R4 |
| --- | --- | --- | --- | --- |
| Flush pipeline | R1 | R2 | R3 |  |
| Nextcommitptr | 0x3 | 0x3 | 0x3 | 0x3 |
| Commitptr | 0x2 | 0x3 | 0x3 | 0x3 |
| Flushptr | 0x2 | 0x2 | 0x3 | 0x3 |
| Nonflushptr | 0x2 | 0x2 | 0x2 | 0x3 |

Table 2 New pipeline of commit and flush.
| Commit pipeline | R1 | R2 | R3 | R4 |
| --- | --- | --- | --- | --- |
| Flush pipeline | R1 | R2 | R3 | R4 |
| Nextcommitptr | 0x3 | 0x3 | 0x3 | 0x3 |
| Commitptr | 0x2 | 0x3 | 0x3 | 0x3 |
| Flushptr | 0x2 | 0x3 | 0x3 | 0x3 |
| Nonflushptr | 0x2 | 0x2 | 0x3 | 0x3 |

There are also some modification in bid PC and other output signals.
Exception PC is one cycle earlier as flush is happen one cycle earlier.
Flush bid is in the same cycle with commit bid.
Recover stid is one cycle earlier.
Oooiexnextretiredvldrid for flushnxt scenario used to be flushed in IssueQ pipeline now is flushed in P1 pipeline.
Ooolsunextcommitvldrid it is same as IEX signals flush is one cycle earlier.

#### 6.4.6 Non flush

Why nonflush
In old implementation NCDevice load is executed nonspeculatively it means only one load returns data can another load start.
In old implementation MSR is also issued nonspeculatively nonspeculative means MSR should wait until all older instructions are executed and committed If in a program MSR are back to back then every 9 cycles can we execute a MSR.
The table below shows why it takes 9 cycles to execute back to back MSR.
| 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| P1 | I1 | I2 | E1 | E2 | R0 | R1 | R2 | R3 | P1 |
| Rslv | Update rcnt | Commit | Next msr nonspec | Wakeup |  |  |  |  |  |

Nonflush basic idea.
In order to speed up these instructions execution nonflush concept is introduced it runs faster than commit so originally nonspeculatively executed instructions which is relied on commit pointer can now rely on nonflush pointer.
This can be illustrated by example below.
The commit is blocked by a load which missed in L1 The load has sent agenresolve to OoO indicate there is no exception If there is data abort when L2 return data the data abort will be treated as async event.
Once ROB get agen resolve from LSU nonflush pointer will move forward and stopped by a branch Lets assume branch has no data dependency with load and can be executed it will report branch resolve to ROB if branch is correctly predicted nonflush pointer will move to next instruction.
The instruction after branch is a MSR in decode stage we will check whether MSR is selfsync MSR or normal MSR For selfsync MSR after execution ROB will generate ISB flush In this case nonflush pointer will stop in this instruction.
Again lets assume the two MSR are normal MSR nonflush pointer will keep move to next instruction and finally stopped to ISB.
In this example unlike MSR issue must wait until loadaddbranch are executed in old implementation MSR will be issued before older instructions are executed.
To support nonflush rob maintain a valid bit nfrdy and a pointer rid Nonflush pointer can only move to next entry if current entrys valid bit is set Rob guarantee instructions older than nonflush pointer never get flushed NCdevice load and MSR can thus start executing without waiting for all older instruction committed.
Like commit pointer nonflush pointer is also maintained in program order It can be accumulated if an instruction is identified that is will not generate exception.
Nonflush valid

- Valid bit can be set to 1 on rob allocation for a group of instructions that will not generate any exception such as ALU instructions.
- Valid bit can be set to 0 on rob allocation in this case nonflush counter will be set to a certain value based on the number of nonflush resolve received If all the nonflush resolves are collected nonflush valid bit will be set to 1.

Load/store instruction system instruction MSRDCICSVC etc and branch instruction may set nonflush valid bit to 0 for LS instruction and cacheTLB maintance instruction nonflushcnt is updated by agenresolve for branch instruction nonflushcnt is updated by bruresolve For other system instruction nonflushcnt is updated by srfresolve.
Nonflush resolve is as following types of instruction resolve.
| Insttype | Nonflush resolve interface | Nf resolve required maxium |
| --- | --- | --- |
| load/store | lsuoooagenresolvedld0 |  |

Lsuoooagenresolvedld1.
Lsuoooagenresolvedst0.
Lsuoooagenresolvedst1.
Iexooostd0fstlfldvlde2.
| Iexooostd1fstlfldvlde2 | 8 |
| --- | --- |
| Branch | Iexooobru0resolvede2 |

Iexooobru1resolvede2.
Iexooobru2resolvede2.
| Iexooobrubufresolvede2 | 1 |  |
| --- | --- | --- |
| MSR | Srfrobrslve2 | 1 |
| AUT | Iexoooalu012resyvld | 1 |
| Other | 0 |  |

Nonflush pointer
Reset As commit pointer nonflush pointer is reset to 0.
Accumulate
Whether nonflush pointer should be accumulated or not depends on.
| 1 | Nonflush ready |
| --- | --- |

Normally an instruction will set nonflush ready directly except the instruction that may generate flush either by exception.
For exception we need to wait.
| 1> | All load/store instructions uops are agenresolve |
| --- | --- |
| 2> | SRF instruction is resolved for ISBWFxselfsync MSR |
| 3> | Branch is resolved |
| 4> | AUT instruction is resolved |
| 2 | Pending interrupt |

When interrupt is asserted current nonflush pointer selected instruction will be guarantee be finished but nonflush pointer will be stalled at next entry prepared to take interrupt.
Nonflush output to other modules.
Nonflush valid and nonflush rid ooolsunoflushr2 ooolsuiexnoflushridr2.
This interface is to tell execution units that the noflushrid pointed instruction will not be flushed by interrupt or branch But if the instruction itself detected exception the group of instruction can still flushed by itself.
Rob will output nonflushvld and nonflushrid nonflushpointer to IEX and LSU these two modules can start executing instructions that should be executed nonspeculatively if the instructions rid equal or older than nonflushrid.
IFU can release branch queueoooifunfcommitbidr2.
Once instructions are nonflushed ROB will recover its BID and send back to IFU.
Load queue can be releasedooolsunoflushlidnumr3.
In rob we keep how many load queues are occupied in a group For earlier-generation core performance when nonflushed rob add nonflushed load queue number and send to LSU.

#### 6.4.7 Interrupt handling

Rob will take interrupt if all of the following conditions are matched.
Commit pointer equal with nonflush pointer Make sure all nonflushed instructions are committed.
Not in the process of exception handling.
Interrupt is stable Interrupt may not stable if ELSCRHCR is changed there is a handshake scheme between OOOL2C If EL is changed after ooo flush ooo will send a change request to L2C and L2C will respond with an acknowledgement Similarily if SCRHCR is written when msr ooo will send a change request to L2C and L2C will also respond with an acknowledgement During the process of handshake interrupt will be blocked.
The detailed timing sequence of interrupt block and stall d1 is shown in following pictures.

#### 6.4.8 Exception handling

Single step exception.
Either in software step or halt step the state machine changed from inactive to activenotpending is by ERET or exit of debug state so there will be ooo flush for both steps.
Pling condition of activenotpending can be designed after ooo flush.
Whenever software step or halt step is enabled.
Only one instruction can be committed If there is no exception including exceptionentry and non exc flush for that instruction rob will commit current instruction insert a step flush which behaves as ISB.
If the instruction tobe committed has exception then exception will be taken.
Exception in progress handling.
After ooo flush rob enters into the exception in progress state and wait for recovering In the exception in progress state there is no commit nonflush vld and ooo flush Only when rob collect enough recover signals can it exit the special state.
Restart vld from EXC It is necessary in nondebug state It means that EXC module has sent restart vld and restart pc to IFU to refetch new instruction.
Mpq commit flush done It is necessary It means that MPQ has finished surplus commit and flush.
Sp nzcv commit doneIt is optional It is used for the exception with spnzcv switch process In that case rob firstly receive a spnzcv request from REN and collect needed spnzcv commit done signals.
Generally speaking exception in progress is asserted in R2 stage ooo flush and deasserted in R8 stage.

#### 6.4.9 Rob stall

When there is no enough rob entry left then need stall uop at D3 The stall logic is as following picture calculate the youngest rid that rob can accept according to next commit pointer then compare it with D2 uop rid For uop which is older than the acceptable youngest rid it can go down otherwise will be stalled.

##### 6.4.1.0 ROB limit ipc

There are several schemes implemented in ROB to stall D1 limit ipc Some of them is to limit ipc of its thread some is to limit ipc of the other thread and some is to fully stall both thread The details is described as follow.
| 1 | Pending interrupt |
| --- | --- |

According to the Prioritization and recognition of interrupts of Arm Spec pending interrupt before a context synchronization event needs to be taken before 1st instruction of the new context Therefore create a signal to DEC to fully stall OOO to IFU so that the new instruction will make sure be flushed by interrupt flush.

## 2 Dec bandwidth configuration

The dec bandwidth can be configured by hardware configuration and software configuration The software configuration is enabled after corresponding register bit is written by msr The hardware configuration is not only controlled by register configuration but also controlled by l2 pin If l2coooft2cfgipc is set choose the min bandwidth by the comparison of hardware configuration and software configuration otherwise choose software configuration The default value is 3b000 meaning no stall from OOO to IFU The relation of dec bandwidth configuration and IPC stall from OOO to IFU is shown in following chart.
| Frcdecbandwidth[20] | IPC stall[50] |
| --- | --- |
| 000 | 000000 |
| 001 | 111110 |
| 010 | 111100 |
| 011 | 111000 |
| 100 | 110000 |
| 101 | 100000 |
| 110 | 000000 |
| 111 | 000000 |
| default | 000000 |
| 3 | DIDT control |

When core is in wfx status the power is low When it is waked up form wfx it will have a rapid power increment which will cause DIDT noise so as rob flush refetch and cpm drop relieve The scheme to reduce DIDT is gradually release the stall from OOO to IFU The decode bandwidth will gradually change from 0 to 8 according to the configuration of CPMCTLREL3 register The details is shown in following table.
| Bit | Configure | Description | Reset |
| --- | --- | --- | --- |
| [22] | Cpm drop relieve limit ipc enable | 0 Cpm drop relieve not limit ipc |  |
| 1 Cpm drop relieve limit ipc | 1b0 |  |  |
| [21] | all rob flush refetch limit ipc enable | 0 not all rob flush refetch limit ipc |  |
| 1 all rob flush refetch limit ipc | 1b0 |  |  |
| [20] | wfx wake up limit ipc enable | 0 wfx wake up will not limit ipc |  |
| 1 wfx wake up will limit ipc according to config | 1b0 |  |  |
| [19] | wfi wake up limit ipc disable | 1 wfi wake up will not limit ipc |  |
| 0 wfi wake up will limit ipc according to config if bit[20] is 1b1 | 1b0 |  |  |
| [1817] | wfx flush wake up limit ipc scale | 00 limit mask shift 1 when counter count the num config by [1611] [redacted numeric sequence] |  |

01. limit mask shift 2 when counter count the num config by [1611] [redacted numeric sequence]

| 1x limit mask shift 3 when counter count the num config by [1611] [redacted numeric sequence] | 2b00 |  |
| --- | --- | --- |
| [1611] | wfx flush wake up limit ipc mask shift count number | Config the counter number for limit mask shift |
| The cyclecnt cfgnum 8 | 6b0 |  |
| 4 | In order mode |  |

When core enters into in order mode only one instruction can be committed Therefore the ports of 15 from ifu to dec is stalled Port 0 begins to stall after a new instruction sent to DEC from IFU and releases stall when the instruction commited or flushed.
In order mode can be configured by software by MSR or hardware Hardware sets this bit to 1 when trigger nocmtflush event or livelock flush event if corresponding configuration override bit is valid And hardware sets this bit to 0 when configured number of instructions are executed under in order mode.
| 5 | Livelock in order stall |
| --- | --- |

LSUIFU module sends livelock request to OOO module After OOO response the request livelock flush the core may enters into in order mode In mt mode the thread which responses livelock request and enter into in order mode not only stall its instruction like normal in order mode but also fully stall the other thread However the stall is relieved if the thread responsing livelock request enters into power downdebug statesp modewfx state The purpose is to avoid disturbance from the other thread until lsu solve livelock problem If there are two livelock requests from two threads in the meanwhile only one request can be acknowledged and the other request is dropped.
| 6 | Smt livelock stall |
| --- | --- |

If there is no commit for a long time LSUL2CIEXOOO will generate smt livelock request according to their counter After ROB receive those requests it may generate a signal to fully stall both thread and wait for rob empty to relieve stall If both thread are stalled the surplus resource will be executed and rob will be empty in theory If not it means the core probably enters into deadlock problem Smt livelock stall has no effect on it and the stall can be relieved So there is also a configuration override bit to relieve stall spontaneously when time out.
| 7 | No commit flush stall |
| --- | --- |

No commit flush is triggered when an oldest instruction is not retired for a long time because of starvation or deadlock So there is a need for flush to solve the problem.
However if no commit flush is triggered but the other thread still occupies the resource The problem is not solved thoroughly There is an alternative way to stall the other thread after no commit flush The stall can only be relieved when the flushed thread commit one instruction The no commit flush stall scheme is configured by configuration override bit If both thread generate stall due to no commit flush only one stall is valid.

##### 6.4.1.1 SMT tid selection

In mt mode when thread0 and thread1 are both working there is only one thread that can commit or flush in the same cycle even if both thread are ready This is because some hardware resources are shared and can only response one thread request Therefore to support mt mode there is a need of an effective and reliable rule for thread id simplified as tid selection.
If the thread is ready to commit or flush it should has the opportunity to commit or flush at once However if both thread are ready to commit or flush then only one thread can be selected For fairness the other thread commit or flush in next cycle Note it is not perhaps the most effective way If the thread is not ready to commit or flush then tid will not toggle for power consideration Because of timing problem it is implemented that tid selection mechanism is one cycle after thread ready The following 2 tables illustrates the mechanism T0 and T1 represent thread0 and thread1 respectively.
| Current cycle | Next cycle tid sel |
| --- | --- |
| T0rdyT1rdy | Round robin |
| T0rdyT1nordy | T0 |
| T0nordyT1rdy | T1 |
| T0nordyT1nordy | Maintain |
| Tidsel | T0init |

T1
T0
| T1 | T1 | T1 |
| --- | --- | --- |
| T0 | T1 |  |

T0
| T0rdy | Yes | Yes | No | No | No | Yes | No | Yes | Yes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| T1rdy | Yes | Yes | Yes | Yes | No | No | Yes | Yes | Yes |

The black arrow shows the tid selection The red dotted line block is a little bit confused It is because tid selection is one cycle after thread ready due to timing problem.
Moreover there is a problem on how to judge thread ready In consideration of timing we cannot use all commitnoflushflush logic Therefore we choose the critical and timingfriendly signals from commitnoflushflush logic For example we choose cmtready signal for commit exc signal for syncexception and asyncreq for asyncexception although asyncexception may not happen even if asyncreq is valid In order to avoid the selected logic incomplete there is a configuration override bit to force tid round robin.

##### 6.4.1.2 New banking scheme and retirement loop

64121 oddeve ptr scheme.
There is a critical timing path from rob entry allocated information through the inorder retirement logic to the entryreadptr logic The whole logic is 1-cycle loop To optimize the timing path each of ROB bank is portioned into 2 subbanks even and odd.
This partitioning guarantees that each subbank can be accessed at most once per 2 cycles For example if the even subbank is accessed in current cycle then in next cycle only odd subbank can be accessed.
Therefore the 1-cycle loop can be divided into 2-cycle The first cycle is from rob entry allocated information to the evenodd indentification flag logic the second cycle is from evenodd flag to the entryreadptr logic The timing of first cycle is still tense but there is space for frontend timing optimization.
The example below is for 2 banks but it can be trivially extended to 8 banks or more The red loop is a 1-cycle loop the orange loop is the 2-cycle loop.
64121 oddeve ptr payload open item.

##### 6.4.1.3 No cmmit IRQTBD

| 1 | NOCMTIRQCPU |
| --- | --- |

Hi1630V100Hi1630V200.
1feedthoughX
2NOCMTIRQMASKSTATUS
[internal reference removed]
Hi1630V100SOCMASK
Hi1630V200SOCSOCNOCMTIRQMASKNOCMTIRQ.

2. Hi1981NOCMTIRQ.

NOCMTIRQNOCMTIRQCluster.
CMT
| 1 | nocmtirq muticyc x |
| --- | --- |
| 2 | nocmtirqmaskstatusnoimtirqennocmtirq |

3
| 4 | UTnocmtirqnoimtirqen |
| --- | --- |

- 1 NOCMTIRQ CPUMASKSTATUS.

2. CKBNOCMTIRQ

A. CKB0NOCMTIRQclear
B. CKB1NOCMTIRQCMTFlushNOCMTIRQ.

1. nocmtirq
2. nocmtirqennocmtirqcnt32bit18bit1wfxnocmtirq nocmtirqen nocmtirq.

- 1 nocmtirqmuticyc.

2 configuration override bit bit0 chickenbit 1 nocmtirq commitrobflush nocmtirq.

##### 6.4.1.4 STLD PC for MDBTBD

1store pc loadnukeload nukestpc.
2load pc LoadnukeloadPC.
Robpc3

1. Lastcommitpc cycle commit grppc.
2. brcurrpcbrtarpc ETM ETMrobcommit pcoverflowtakenbranch pc.
3. robexcpct01r3exceptionrobflushpc.

Load pc
Robexcpct01r3flushpcload nukeflushflush r3 nuke flush pc.

- store pc
- 3pcstpcrob2pcbrcurrpc Lastcommitpcnuke waitcommit st.

### 6.5 Exception

#### 6.5.1 Overview

Basically Say Exception handling module handles all the OoO Flush process and some control info for DTU module including.
Sync ExceptionDBG detectionhandling.
Asynchronous ExceptionDBG detectionhandling.
Other Flush event handling.
Control logic send to DTU module.
Control of SMT switch and wfi mode switch.

#### 6.5.2 Block Diagram

#### 6.5.3 Pipeline Description

D1 Stage
1Receive and prioritized IFU exception info.
2Detect all frontend exception inside OOO includingUNDEF TRAP EXCGENincluding ERET instruction decode.
D2 Stage
Based on exception priority requirement and exception pipe vld info MUX out ONE final exception info.
Write the final exception info to decsrfexctype register This exception info is write to decsrfexctype speculatively So when there is branch flushthis exception info could be flushed.
R2 Stage
When exception occurs ooo will tell EXC the source of this exception at the same cycle with oooflushr2 the source includes.
Exceptions from LSU.
Exceptions from IEX.
Exceptions from DEC or SRF.
Exceptions from Interrupt.
NonException Flush.
Based on different exception source the different exception info will be write to exctype register.
R3 Stage
Exctype contains the reason for this OOO flush Based on different exception type EXC module will start different exception process.
Exception Entry Flow.
Exception Return Flow.
NonException Flush Flow.
Exception Entry Flow.

1. based on exception info calculate Target EL.
2. based on the Target EL update corresponding system registers including.

PSTATEELESRELxFARELxHPFARELxELRElxSPSRELxSPELx.

3. based on Target EL and exception info select out corresponding VBAR and calculate the offset to form the restart VA.

Exception Return Flow.

1. Detect illegal return info.
2. if not illegal return copy corresponding SPSRELx to PSTATE.
3. Sel out corresponding ELRELx to form restart VA.

NonException Flush Flow.

1. No need to update EL and system registers but only flush the pipeline.
2. The restart VA is Current PC or PC+4.

R4 Stage
EXC module sends restart VA to IFU all system register is ready.
From R4 stage CPU will see a new State and gets a new PC to execute.

#### 6.5.4 Sync exceptionDBG detection and handling

Frontend exception.
All frontend exceptiondbg event is detected and prioritized in OOO frontend pipelineD1D2 inside ooodecexcvincluding.
Reset catch debug event.
Exception catch debug event.
Single Step debug event.
IFU ExceptionBreakpoint DBG Event.
IMP DEF Trap exception.
Illegal return exception.
UNDEF exception
Instruction that will generate exceptionsdbg eventsSystem Call ERET HLT and so on.
All exceptions and debug event are prioritized according to Arm definition.
All frontend exceptiondbg event are detected in D1 Stage and flopped one cycle to D2 stage.
At D2 Stage exception module will do the exception prioritization job and generate exception info for each instruction.
Exception from SRF.
SRF sends exception info through resolve interface.
SRF is only responsible for MSRISBWFX exception detection and MSRISBWFX instruction will not be flushed by branch so when the resolve interface indicates an exception detected from SRF it will generate OOO flush for sure.
VFP Exception Handling.
VFP exception is not supported but only record the error status to FPSR.
IEX Exception Handling.
AUT instruction may generate IEX exception When there is a flush generated by IEX Exception EXC module will get all required exception information esr info and restart va from IEX to begin exception handling process.
LSU Exception Handling.
All LDST instucton may cause LSU exception It consist of data abort exception watchpoint exception and SPmisalign exception according to Arm definition For LDFFxLDNFx SVE instruction it may casue selfdefined FFRfault exception to update FFR register Besides there is a nuke exception when load bypass load or load bypass store and it is also selfdefined.
For the priority data abort exception watchpoint exception and SPmisalign exception are prioritized according to Arm definition The priority of nuke is lower than them and the FFRfault exception is lower than nuke When there is a flush generated by LSU Exception EXC module will get all required exception info from LSU to begin exception handling process.
For floorplan issueall exception related signals from LSU are flopped two cycles inside oooexclsuflopv module before used in exception handling process.
For ESRFARHPFAR signals are directly flopped two cycles For lsuoooexctpye signal after flop one cycle it is extended and encoded into 7 bit version named as exctypelsu to match the exctype format.
Whether lsuooo signals are valid is determined by lsuooofaresrbitsvld signal so in oooexclsuflop module all flopped signals are gated with this signal and its flopped version.
At OOO Flush R2 Stage ROB asserts robexclsuexc signals indicate this flush is generated due to LSU exception At the same cycle exctypelsu info will be flopped in to exception type reigsiter.
Exception type info comes from LSUTLB module and before LSU instruction is resolved the exception info is stable.
Selfdefined IMPDEF Exception To EL3.
In LinxCore It introduces IMPDEF exception In Arm SPEC ESREC is 011111 Route EL is EL3 ISS and Priority is All IMPDEF.
In LinxCore Trap Instruction OPCODE is considered as one type of IMPDEF exception.
INSTTRAP feature is to trap specified instruction according to two sysregs configurations INSTBIN32 bit Binary INSTMASK32 bit Mask to indicates which bit in INSTBIN can be ignored.
However if software incorrect config INSTBIN or INSTMASK it will trap unnecessary instructions and cause software out of control see below two cases.

1. Config INSTMASK to all 1s which means all instructions will be in exception no one can commit.
2. Config INSTBININSTMASK results in the first instruction in Trap handler So it will cause recursive exception.

To avoid the above two cases we decide to mask the enable bit when taking this IMPDEF exception by hardware and if software still wants to enable the trap feature it can enable it again in software handler.
It is controlled by three system register.
| Register | op0 | op1 | crn | crm | op2 | Description | Permission |
| --- | --- | --- | --- | --- | --- | --- | --- |
| TRAPINSTBINEL3 | 3 | 6 | c15 | c0 | 3 | Instruction Opcode to be trapped | RW at El3 |
| TRAPINSTMASKEL3 | 3 | 6 | c15 | c0 | 4 | Mask bit for opcode in TRAPINSTBINEL3 | RW at El3 |
| CPMCTLREL3[63] | 3 | 6 | c15 | c0 | 6 | Used to enable Opcode Trap Logic |  |
| When IMPDEF exception takes the bit is automatically updated to 0 | RW at El3 |  |  |  |  |  |  |

When certain instruction OPCODE is matched this IMPDEF exception is occurred And the exception behavior is shown below.
| Exception Info | Description | Comments |
| --- | --- | --- |
| Route Level | Always EL3 | Defined by Arm |
| Priority | Lower than All IFU exception Higher than all OOO Exception | IMPDEF According to synchronous exception priorities of Arm SPEC it is lower than breakingpoint exception and higher than illegal execution state exception |
| ESREL3EC | 011111 | Defined by Arm |
| ESREL3ISS | All zero | IMPDEF for this case it is all Zero |

In LinxCore there is also an IMPDEF trap exception that the ESREC is 011111 and Route EL is EL3 To distinguish with above INSTTRAP feature ESRISS is 1.
The trap is introduced by EL3EL2 PA restriction for security purpose The range of EL3EL2 PA is determined by CPUPARANGEEL3 CPUPARANGEEL2 register and it is written once by ISB flush If the PA of fetched instruction in EL3EL2 is out of range then there is an exception.

#### 6.5.5 Exctype Register

Exctype register contains the specified types for OOO Flush Based on different flush types exception module will start different flush process.
OOO flush is asserted at R2 Stage from ROB so at R2 Stage all flush type information is ready and will be saved into exctype register At R3 Stage the exctype register is updated ready exception handling process can start based on exctype info.
Different flush types info comes from different module see below.
LSU Exception exception info from LSUTLB piped inside oooexclsulop saved in exctype register at R2 stage.
VFP Exception exception info from FSU which is not supported.
IEX Exception exception info from IEX piped inside oooexchdl saved in exctype register at R2 stage.
Non Exception Flush does not need exception info At Flush R2 stage ROB will indicate oooexc this is a non exception flush this info will be saved in exctype register at R2 stage.
Frontend Exception exception info from oooexcdec module and saved in decsrfexctypeshared with SRF register At Flush R2 stage decsrfexctype info will be flopped into exctype register.
SRF Exception exception info from ooosrf module and saved in decsrfexctype register At Flush R2 stage decsrfexctype info will be flopped into exctype register.
At R3 stage exctype register is updated ready so from R3 Stage exception handling process begins.

#### 6.5.6 Flush Type Table

All 830 flush type are listed below Need to check correctness.
| Flush Type | Category | Restart VA | NZCV | Update SysReg | Exctype encoding | Comments |
| --- | --- | --- | --- | --- | --- | --- |
| INVALID | NA | NA | NA | NA | 7h00 | Invalid Type |
| SVC | Sync EXC | VBAR+Offset | Save | Exception class | 7h01 | System Call |
| HVC | Sync EXC | VBAR+Offset | Save | Exception class | 7h02 | System Call |
| SMC | Sync EXC | VBAR+Offset | Save | Exception class | 7h03 | System Call |
| ERET | EXC Return | ELRELx | Restore | PSTATE | 7h04 | Exception Return |
| BKR | Sync EXC | VBAR+Offset | Save | Exception class | 7h05 | Debug Exception |
| PCMIS | Sync EXC | VBAR+Offset | Save | Exception class | 7h06 | PC misalign |
| SPMIS | Sync EXC | VBAR+Offset | Save | Exception class | 7h07 | SP misalign |
| IABT | Sync EXC | VBAR+Offset | Save | Exception class | 7h08 | Inst abort from IFU |
| EXCBKPT | Sync EXC | VBAR+Offset | Save | Exception class | 7h09 | Debug Exception |
| ISB | NON EXC Flush | NXT PC | NA | NA | 7h0a | Include ISBSelfSync |
| EXCWPT | Sync EXC | VBAR+Offset | Save | Exception class | 7h0b | Debug Exception |
| DABT | Sync EXC | VBAR+Offset | Save | Exception class | 7h0c | Data abort from LSU |
| UNDEF | Sync EXC | VBAR+Offset | Save | Exception class | 7h0d | Include UnallocateSys UNF |
| ILSTATE | Sync EXC | VBAR+Offset | Save | Exception class | 7h0e | Return to Illegal state from exception process or debug state |
| VFP | Sync EXC | VBAR+Offset | Save | Exception class | 7h0f | do not support in 830 |
| HLTREQ | Async DBG Event |  |  |  |  |  |
| DBG STATE | NO | Save | DBG State Class | 7h10 | Request from DTUDBG |  |
| HLTBKPT | Sync DBG EventDBG STATE | NO | Save | DBG State Class | 7h11 | Enters debug state because of breakpoint hit |
| HLTSS | Sync DBG EventDBG STATE | NO | Save | DBG State Class | 7h14 | Halt Single Step |
| HLTSSNOR | Sync DBG EventDBG STATE | NO | Save | DBG State Class | 7h15 | Halt Single Step with Normal Syndrome Used for EDSCRStatus |
| HLTSSNOSYN | Sync DBG EventDBG STATE | NO | Save | DBG State Class | 7h16 | Halt Single Step with No syndrome Used for EDSCRStatus |
| HLTSSLDEX | Sync DBG EventDBG STATE | NO | Save | DBG State Class | 7h17 | Halt Single Step with LDEX Used for EDSCRStatus |
| RSTCATCH | Sync DBG EventDBG STATE | NO | Save | DBG State Class | 7h19 | Reset Catch Debug Event |
| HLTWPT | Sync DBG EventDBG STATE | NO | Save | DBG State Class | 7h1a | Enters debug state because of watchpoint hit |
| HLTINST | Sync DBG EventDBG STATE | NO | Save | DBG State Class | 7h1b | Enters debug state because of HLT execution |
| HLTSWACC | Sync DBG EventDBG STATE | NO | Save | DBG State Class | 7h1c | Enter debug state because of Software Access |
| EXCCATCH | Sync DBG EventDBG STATE | NO | Save | DBG State Class | 7h1d | Enter debug state because of Exception Catch |
| TRAPEL1GEN | Sync EXC | VBAR+Offset | Save | Exception class | 7h20 | To EL1 EC 18 |
| TRAPEL1VFP | Sync EXC | VBAR+Offset | Save | Exception class | 7h21 | To EL1 EC 7 |
| TRAPEL2GEN | Sync EXC | VBAR+Offset | Save | Exception class | 7h22 | To EL2 EC 18 |
| TRAPEL2VFP | Sync EXC | VBAR+Offset | Save | Exception class | 7h23 | To EL2 EC 7 |
| TRAPEL2SMC | Sync EXC | VBAR+Offset | Save | Exception class | 7h24 | To EL2 EC17 |
| TRAPEL3GEN | Sync EXC | VBAR+Offset | Save | Exception class | 7h25 | To EL3 EC 18 |
| TRAPEL3VFP | Sync EXC | VBAR+Offset | Save | Exception class | 7h26 | To EL3 EC 7 |
| TRAPEL1WFE | Sync EXC | VBAR+Offset | Save | Exception class | 7h27 | WFE Trap to EL1 EC 01 |
| TRAPEL2WFE | Sync EXC | VBAR+Offset | Save | Exception class | 7h28 | WFE Trap to EL2 EC 01 |
| TRAPEL3WFE | Sync EXC | VBAR+Offset | Save | Exception class | 7h29 | WFE Trap to EL3 EC 01 |
| TRAPEL1WFI | Sync EXC | VBAR+Offset | Save | Exception class | 7h2a | WFI Trap to EL1 EC 01 |
| TRAPEL2WFI | Sync EXC | VBAR+Offset | Save | Exception class | 7h2b | WFI Trap to EL2 EC 01 |
| TRAPEL3WFI | Sync EXC | VBAR+Offset | Save | Exception class | 7h2c | WFI Trap to EL3 EC 01 |
| ESBDEFFR | NonEXCFLush | NXT PC | NA | Exception class | 7h2d | Take ESB and update ISRDISR register |
| SPEXIT | Exit from SPU State | SPLR | NA | Exception class | 7h2e | Exit SP mode |
| SPENT | Enter SPU state | NO | NA | Exception class | 7h2f | Enter SP mode |
| DCPS1 | Sync EXC in DBG STATE | NA | Save | Exception class | 7h30 | Change EL to EL1 in DBG STATE |
| DCPS2 | Sync EXC in DBG STATE | NA | Save | Exception class | 7h31 | Change EL to EL2 in DBG STATE |
| DCPS3 | Sync EXC in DBG STATE | NA | Save | Exception class | 7h32 | Change EL to EL3 in DBG STATE |
| DRPS | Exc Return in DBG STATE | NA | Restore | PSTATE | 7h33 | ERET in DBG STATE |
| INTIRQ | Async EXCIRQ | VBAR+Offset | Save | Exception class | 7h34 | Include VIRQPIRQ |
| INTFIQ | Async EXCFIQ | VBAR+Offset | Save | Exception class | 7h35 | Include VFIQPFIQ |
| INTSER | Async EXCSER | VBAR+Offset | Save | Exception class | 7h36 | Include VSERRPSERR |
| WFXSLEEP | WFX | NA | NA | NA | 7h37 | WFX Sleep |
| EXCSSNOR | Sync DBG EventException | VBAR+Offset | Save | Exception class | 7h38 | Software single step exception with Normal instruction |
| EXCSSIABT | Sync DBG EventException | VBAR+Offset | Save | Exception class | 7h39 | Software single step exception with instruction abort |
| DBGEXIT | Exit from DBG State | DLREL0 | Restore | PSTATE | 7h3a | Exit DBG state is an async event |
| NONEXCFLUSH | NON EXC Flush | NXTCUR PC | NA | NA | 7h3b | Include Nuke Flush inorderflush nocmtmatchflush step flush int dis livelock flush and smt switch async flush |
| INTREQ | Async EXC | VBAR+Offset | Save | Exception class | 7h3c | All Interrupt |
| SELFSYNC | NonEXC Flush | NXT PC | NA | NA | 7h3d | Access Special purpose register and 830 defined system register will generate OOO Flush |
| IMPDEFEL3 | Sync EXC | VBAR+Offset | Save | Exception class | 7h3f | IMP DEF Exception to El3 |
| TRAPEL1SVE | Sync EXC | VBAR+Offset | Save | Exception class | 7h40 | To EL1 EC 19 |
| TRAPEL2SVE | Sync EXC | VBAR+Offset | Save | Exception class | 7h41 | To EL2 EC 19 |
| TRAPEL3SVE | Sync EXC | VBAR+Offset | Save | Exception class | 7h42 | To EL3 EC 19 |
| SETFFR | NonEXC Flush | NXT PC | NA | NA | 7h43 | Setffr fault |
| FFRFAULT | NonEXC Flush | NXT PC | NA | NA | 7h44 | Ldff fault |
| AUTFAIL | Sync EXC | VBAR+Offset | save | Exception class | 7h45 | AUT fault |
| TRAPEL2PAC | Sync EXC | VBAR+Offset | Save | Exception class | 7h46 | To EL2 EC 09 |
| TRAPEL3PAC | Sync EXC | VBAR+Offset | Save | Exception class | 7h47 | To EL3 EC 09 |
| ERETAUT | EXC return after AUT fault | PAC ERET PC | Restore | PSTATE | 7h48 | Exception return |
| BTI | Sync EXC | VBAR+Offset | Save | Exception class | 7h49 | BTI fault |
| ECBHB | NonEXC Flush | NXT PC | NA | NA | 7h4a | Selfdefine flush to clear BHB |
| EL3PAFAULT | Sync EXC | VBAR+Offset | Save | Exception class | 7h50 | Security El3 pa fault |
| TRAPEL1WFET | Sync EXC | VBAR+Offset | Save | Exception class | 7h51 | WFET Trap To EL1 EC 01 |
| TRAPEL2WFET | Sync EXC | VBAR+Offset | Save | Exception class | 7h52 | WFET Trap To EL2 EC 01 |
| TRAPEL3WFET | Sync EXC | VBAR+Offset | Save | Exception class | 7h53 | WFET Trap To EL3 EC 01 |
| TRAPEL1WFIT | Sync EXC | VBAR+Offset | Save | Exception class | 7h54 | WFIT Trap To EL1 EC 01 |
| TRAPEL2WFIT | Sync EXC | VBAR+Offset | Save | Exception class | 7h55 | WFIT Trap To EL2 EC 01 |
| TRAPEL3WFIT | Sync EXC | VBAR+Offset | Save | Exception class | 7h56 | WFIT Trap To EL3 EC 01 |
| TRAPEL2ERET | Sync EXC | VBAR+Offset | Save | Exception class | 6h57 | Trap To EL2 EC 1A |
| TRAPEL2ERETAA | Sync EXC | VBAR+Offset | Save | Exception class | 6h58 | Trap To EL2 EC 1A |
| TRAPEL2ERETAB | Sync EXC | VBAR+Offset | Save | Exception class | 6h59 | Trap To EL2 EC 1A |
| TRAPEL2SVC | Sync EXC | VBAR+Offset | Save | Exception class | 6h5a | Trap To EL2 EC 15 |

Exception Type to ETM Flush Type.
| Flush Type Defined in LinxCore | Type in ETM SPEC | encoding | excpc to ETM | dbgexcvldr2 | Flush Type to ETM | Comments |
| --- | --- | --- | --- | --- | --- | --- |
| INVALID | NA | 7h00 | NA | NA | NA |  |
| SVC | Call | 7h01 | PC + 4 | 1 | TYPESVC |  |
| HVC | Call | 7h02 | PC +4 | 1 | TYPESVC |  |
| SMC | Call | 7h03 | PC +4 | 1 | TYPESVC |  |
| ERET | NA | 7h04 | NA | 0 | SLNTBRN |  |
| BKR | instdebug | 7h05 | PC | 1 | BKTPNTREGHIT |  |
| PCMIS | Alignment | 7h06 | PC | 1 | PRCPCPABORT |  |
| SPMIS | Alignment | 7h07 | PC | 1 | PRCPCPABORT |  |
| IABT | INST Fault | 7h08 | PC | 1 | PRCINTPABORT |  |
| EXCBKPT | instdebug | 7h09 | PC | 1 | BKTPNTREGHIT |  |
| ISB | NA | 7h0a | NA | 0 | ISB |  |
| EXCWPT | datadebug | 7h0b | PC | 1 | WTCHPNT |  |
| DABT | Data Fault | 7h0c | PC | 1 | PRCINTDABORT |  |
| UNDEF | Trap | 7h0d | PC | 1 | UNDEF |  |
| ILSTATE | Trap | 7h0e | PC | 1 | UNDEF |  |
| VFP | Trap | 7h0f | PC | 1 | UNDEF |  |
| HLTXXX | Debug Halt | 7h1x | PC | 1 | HLTINST |  |
| TRAPXXX | Trap | 7h202c |  |  |  |  |

7h4042 7h4647
7h515a
| PC | 1 | UNDEF |  |  |  |
| --- | --- | --- | --- | --- | --- |
| ESBDEFER | NA | 7h2d | NA | 0 | MEMNUKE |
| SPEXIT | NA | 7d2e | NA | 0 | MEMNUKE |
| SPENT | NA | 7h2f | NA | 0 | MEMNUKE |

DCPS123
| DRPS | NA | 7h3033 | NA | 0 | MEMNUKE | Can only happen in Debug State |
| --- | --- | --- | --- | --- | --- | --- |
| INTIRQ | IRQ | 7h34 | PC | 1 | PIRQ |  |
| INTFIQ | FIQ | 7h35 | PC | 1 | PFIQ |  |
| INTSER | System Error | 7h36 | PC | 1 | PIMPEXTDABORT |  |
| WFXSLEEP | NA | 7h37 | NA | 1 | WFI |  |
| EXCSSXX | INST Debug | 7h3839 | PC | 1 | BKTPNTREGHIT |  |
| DBGEXIT | NA | 7h3a | NA | 1 | DBGHALTEXIT |  |
| NONEXCFLUSH | NA | 7h3b | NA | 0 | MEMNUKE |  |
| INTREQ | NA | 7h3C | NA | NA | NA | cannot see in DTU |
| SELFSYNC | NA | 7h3D | NA | 0 | MEMNUKE |  |
| IMPDEFEL3 | IMPDEF | 7h3f | PC | 1 | IMPDEF |  |
| SETFFR | NA | 7h43 | NA | 0 | MEMNUKE |  |
| FFRFAULT | NA | 7h44 | NA | 0 | MEMNUKE |  |
| AUTFAIL | INST Fault | 7h45 | PC | 1 | PRCINTPABORT |  |
| ERETAUT | NA | 7h48 | NA | 0 | SLNTBRN |  |
| BTI | INST Fault | 7h49 | PC | 1 | PRCINTPABORT |  |
| ECBHB | NA | 6h4a | PC+4 | 0 | MEMNUKE |  |
| EL3PAFAULT | IMPDEF | 7h50 | PC | 1 | IMPDEF |  |

#### 6.5.7 System reg RW with flush SelfSync

All earlier-generation core System reg RW with flush are listed below.
| register | access | flush with condition | note | block inst until commit |
| --- | --- | --- | --- | --- |
| ALL sysreg | msr reg | flush when |  |  |
| 1cbooofrcmsrisb is 1default 0 | No |  |  |  |
| DAIF | msr regimm | flush when |  |  |

1cbdaifoptdisable is 1default 0 or.
| 2sw stephlt stepdebug enable | pstate | Yesblock commit when cboooctrlel1[17]is 0default 0 |  |  |
| --- | --- | --- | --- | --- |
| DIT | msr regimm | always flush | pstate | Yesblock commit when cboooctrlel1[17]is 0default 0 |
| SPSEL | msr regimm | flush when |  |  |

1cbooodisimpisb is 0default 1 or.
| 2value changed | pstate | Yesblock commit when cboooctrlel1[17]is 0default 0 |  |  |
| --- | --- | --- | --- | --- |
| SSBS | msr regimm | always flush | pstate | Yesblock commit when cboooctrlel1[18]is 0default 0 |
| PAN | msr regimm | flush when |  |  |

1cbooodisimpisb is 0default 1 or.
| 2value changed | pstate | Yesblock commit when cboooctrlel1[18]is 0default 0 |
| --- | --- | --- |
| UAO | msr regimm | flush when |

1cbooodisimpisb is 0default 1 or.
| 2value changed | pstate | Yesblock commit when cboooctrlel1[18]is 0default 0 |
| --- | --- | --- |
| FPCR | msr reg | flush when |

1cbooodisimpisb is 0default 1 or.
| 2value changed | Yesblock commit when cboooctrlel1[17]is 0default 0 |  |
| --- | --- | --- |
| ELRElx | msr reg | flush when |
| 1cbooodisimpisb is 0default 1 | No |  |
| FPSR | msr reg | flush when |

1cbooodisimpisb is 0default 1 or.
| 2value changed | No |  |
| --- | --- | --- |
| SPSRELx | msr reg | flush when |
| 1cbooodisimpisb is 0default 1 | No |  |
| ZCRELx | msr reg | flush when |

1cbooodisimpisb is 0default 1 or.
| 2value changed | No |  |
| --- | --- | --- |
| FFR | wrffr | flush when |

1cbooodisimpisb is 0default 1 or.
| 2value changed | No |  |  |  |
| --- | --- | --- | --- | --- |
| ICCSREELx | msr reg | always flush | No |  |
| ICCPMREL1 | msr reg | always flush | No |  |
| DSPSREL0 | msr reg | always flush | No |  |
| DLREL0 | msr reg | always flush | No |  |
| screl3 | msr reg | always flush | No |  |
| hcrel2 | msr reg | always flush | No |  |
| hcrxel2 | msr reg | always flush | No |  |
| oooctlrel1 | msr reg | always flush | imp def | No |
| oooctlr1el1 | msr reg | always flush | imp def | No |
| ooosmtctlrel1 | msr reg | always flush | imp def | No |
| fsuctlrel1 | msr reg | always flush | imp def | No |
| cpuprfctlrel1 | msr reg | always flush | imp def | No |
| cpuprfctlr2el1 | msr reg | always flush | imp def | No |
| cpuactlrel1 | msr reg | always flush | imp def | No |
| cpuactlrel3 | msr reg | always flush | imp def | No |
| cpmctlrel3 | msr reg | always flush | imp def | No |
| lsuctlrel1 | msr reg | always flush | imp def | No |
| nocmtcntel3 | msr reg | always flush | imp def | No |
| inorderinstcnt | msr reg | always flush | imp def | No |
| cpusystrap | msr reg | always flush | imp def | No |
| cpusyslockmask0 | msr reg | always flush | imp def | No |

#### 6.5.8 SMT switch control

The chart below is SMT switch structure Based on the requirement the control logic consists of two parts SMT pin switch control logic and WFI switch control logic.
SMT pin control logic adjust the hardware resources based on the configure pin from SOC while the WFI control logic could automatically adjust hardware resources based on the software execution state.

##### 6.5.8.1 SMT pin switch control logic

The part mainly process two scenarios.
The first one is SMT switch when one thread is always on in this scenario there is an asyncrequest to trigger a flush in order to flush the pipeline so that each module could adjust hardware resources The other one is when hardware resources switch with no thread working so there is no need to trigger a flush The basic flow is as follows.
Note
When asyncreq send and the thread is in WFX state it will directly regard as taking the request and will not generate restart Besides when the thread is in debug state and not receive the signal of exiting debug state then it will block the D1 stage and wait ooo empty to directly take the request and will not generate restart.
When WFX control logic is not in IDLE SMT switch logic does not work.
The constraint of input pin.
Smten thread0en thread1en only could switch in same cyle in one SMT swith round.
Smten thread0en thread1en could not change until the smt ack generate.
When smten is low only one of thread0 and thread1 could be high.
Thread0 or thread1 could not switch down before corresponding thread enters into WFI state.
The next switch round should not start until L3 ack of last switch round.

##### 6.5.8.2 WFI switch control logic

RTL has defined several state to support WFI switch control.
MTIDLE
When the core not work in mt mode or the wfi smt switch function is closed then FSM in IDLE state In this state the mtpart t0act t1act are same with mtmode t0en t1en.
MT001
When t1 enter into WFI and t0 still active run then into this state In this state t0act1 and t1act0 mtpart0.
MT010
When t0 enter into WFI and t1 still active run then into this state In this state t0act0 and t1act1 mtpart0.
MT111
When t0 and t1 are in running then the state is MT111 In this state t0act1 t1act1 mtpart1.
MT100
When t0 and t1 are all in WFI state then the state is MT100 In this state t0act0 t1act0 mtpart1.
There are 4 middle states for the switch that refer to hardware resource adjust.
MT111TOMT001MT111TOMT010MT001TOMT111MT010TOMT111
In these states there is a fake interrupt to the thread which is always active then wait rob to take the interrupt or the active thread is in WFX state After several cycles and related module is empty it begins to switch to real target state.

##### 6.5.8.3 Block mechanism for the switch

There is a basic rule for switch that the restart should not be generated until the PIN is updated and has been delayed certain cycle Because each box need several cycles to adjust internal hardware source and can response the restart The delay cycle is configurable default is 12 cycles.
Block WFIWFE wakeup.
When SMT or WFI switch happened maybe refer to fake interrupt switch flow when switch happened the WFIWFE wakeup should be blocked based on rules if wakeup is not blocked it will come anytime if it happened right after pin updated then may lead to the hardware adjust not ready but the restart generate then lead the hardware confuse in a middle state.
Block restart
This scenario only happened in WFI switch in SMT switch the final purpose we block WFIWFE wake up is to block restart but in WFI switch we should see the WFI change to update the FSM state so in WFI state we should pass the WFI wakeup and block the restart.
Block FSM state change.
In WFI switch the PIN changing is binding with FSM state When PIN changed and the restart does not generate the FSM state cannot change again Because the state change may lead to another PIN switch it may has conflict with restart.
Configuration override bit configure block.
Some implementation defined registers involve some shared hardware resources or have requirements for the execution state of the hardware If one thread is running under smt another thread configure this kind of implementation defined registers may cause running incorrectly or hang.
Therefore there is a SMT share configuration override bit CPUACTLR EL3SMTCKBCFG to indicate whether share implementation defined resigster are configured completed or not If the bit is 0 only one thread could power on So there is a block mechanism for this There is a signal called configure finish flag to indicate the configuration is finished There are three scenarios for this.
| 1 | Two thread power on at the same time then t1 will be blocked Only after T0 set the configure finish flag then t1 can real on |
| --- | --- |
| 2 | Only t0t1 one thread on then if the other thread is going to power on it will be blocked until the configure finish flag set |
| 3 | Only t0t1 one thread on after a while this thread is off Then no block is triggered if any thread goes on later it will follow rules 1 and 2 |

### 6.6 PC Buffer

#### 6.6.1 Overview

##### 6.6.1.1 PC management overview

After a reset in most cases the PC of an instruction is the PC of the previous instruction plus the size in byte of the instruction Therefore if a program is running without changing the control flow all instructions can use a PC base plus the offset in byte to represent its PC.
However there are cases can cause abrupt change in PC and a new PC base is required.
Predicted Taken Branch IFU predicted a Branch will be taken and the instruction will jump to the target address of the taken Branch IFU provides the new PC via ifuooobrtgtaddr02t0d1[482] and ifuooobrtgtaddr02t1d1[482] interface.
Taken Exception The PC base address will be changed to where the program restarts.
Branch Flush the PC base address will be the updated Branch Target Address.
In order to keep track of the PC base changes the PC bases will be stored in the PC buffer As the result the PC information can be reduced to PC buffer index and an offset If an ALU instruction requires PC instead of carrying the PC with the instruction through the pipeline stages only the PC buffer index and an offset is needed If the ALU instruction that uses PC is at execution I2 stage the PC will be reconstructed by adding the PC base read from PC Buffer and the PC offset.
If new PC base is needed a new PC buffer entry will be allocated and the previous entry will be marked as release PC Buffer in the ROB All the subsequent instruction will use the value of new entry as their PC base.
The PC management pipeline is illustrated in the diagram below.

##### 6.6.1.2 When PC is used

PC is needed for the following cases.
PCrelative branch
PCrelative load
PCrelative address calculation instructions such as ADRADRP.
To support memory disambiguation all the load instructions also need PC for prediction.
Exceptions and interrupt need PC as its return address.
Data prefetch
ETMBRBE need released instructions PC.
SPU sample PC of the uop.
We need PC for all the above cases this section describes how PC is generated piped and consumed in the pipeline However in ARMv8 the width of PC is 64-bit We absolutely do not want to carry 64-bit PC from fetch to each execution unit which will consume huge power We use an alternative way to get PC.
The basic idea is that in normal program flow the PC of each instruction can be obtained by the PC of previous instruction plus the size of previous instruction unless the previous instruction is a taken branch or has exception If it is taken branch or has exception the PC of present instruction is the branch target address or exception entry address In these situations we store target address or exception entry address into a buffer and carry the buffer index on pipeline When PC is required in later pipeline stage read pc base from the buffer of that index and plus the offset also carried on pipeline The offset is the number of bytes between current instruction and base instruction.

#### 6.6.2 Block Diagram

#### 6.6.3 PC buffer feature

For pc write
For each thread three write ports for predication taken branch or pc overflow.
Rob restart and bru flush will write pc buffer.
For pc read
Alu0 read port for IQ0 pipe1 BRADRADRP.
Alu1 read port for IQ1 pipe1 BRADRADRP.
Alu2 read port for IQ2 pipe1 BRADRADRP.
Commit has two read ports for last commit pctarget pccurrent pc.
ROB release a maximum of 6 PC entry in 1 cycle.
Generate PC
For PCrelative loads the full VA will be precalculated in s1 by IEX.
Load and store uops require hashed 12-bit PC for accessing the prefetcher.
Generate free number for Decoder.
The release pointer will never catch write pointer It is guarantee by system.
PC buffer need to generate free number for Decoder to prevent write pointer overflow release pointer.
PC buffer is shared by two threads in partition mode as thread 0 use 023 thread 1 use 2447.

#### 6.6.4 Initial flow

| Stage 0 | Stage 1 | Stage 2 | Stage 3 |
| --- | --- | --- | --- |
| Core reset release | Thread 0 start work | Thread 0 ROB flush | Thread 0 restart |
| Thread 1 start work | Thread 1 ROB flush | Thread 1 restart |  |

Thread start is after core reset release because thread start signal delay at least 1 cycle in Exception module.
After stage 0two threads run independently.
The operations at every stage are as below.
Stage 0Set all entries to 64d0 This is required by DBG the init value can be any value except X state.
Stage 1Read RVBAR PC value from TOP to entry0 entry24 for thread 1 in MT partition mode Set write point to 0 24 for thread 1 in MT partition mode.
Stage 2Set write point to 0 24 for thread 1 in MT partition mode.
Stage 3Read restart PC base from Exception module to entry0 entry24 for thread 1 in MT partition mode the write pointer remain 024.
There must exist ROB flush of each thread before partitionexclusive switch.

#### 6.6.5 Write operation

The conditions that generate PC buffer write operation are as below.
| Operation | Effect on write pointer | Data effect | Effect Stage |
| --- | --- | --- | --- |
| Reset release | All entries set to 0 |  |  |
| Thread start | Set write point to 0 24 for thread 1 in MT partition mode | Read RVBAR PC value from TOP to entry0 entry24 for thread 1 in MT partition mode |  |
| ROB flush | Set write point to 0 24 for thread 1 in MT partition mode | R4 |  |
| Restartexception | Read resart PC value from Exception module to entry0 entry24 for thread 1 in MT partition mode |  |  |
| Branch flush | Set write point to branch PC idx + 1 | Write correct branch target to branch PC idx + 1 | E4 |
| PC offset overflow | Write to the PC of next instruction of uop0 to next PC idx | S1 |  |
| taken branch | Write the target of reference taken branch to accumulated PC idx | S1 |  |

Reset releaseThread startRestart will not appear at the same cycle with any other write operation It is guarantee by system.
For other write operation the priority is as below.
ROB restart > Branch flush > taken branch > PC offset overflow.
It means if more than one writing operation appear only the highest priority operation will affect other write operation will be not affected.
PC offset overflow only appears in uop0 the first uop of an instruction it is guaranteed by Decoder If uop0 is also a predicate taken branch uop it will be taken as predicate taken branch operation.
Predicate taken branch may appear in each uop in one cycle but must be the last uop of an instruction.
The target of reference predicate taken branch is get from IFU.
The total number of PC offset overflow and Predicate taken branch must not exceed 3 It is for timing and guaranteed by Decoder.
The reference operation at every pipeline stage is as below.
| D3decode | S1 |
| --- | --- |

Generate write idx and pipe to S1.
Generate write enable and pipe to S1.
Open reference RCG of PC entries.
Generate PC idx for every uop for dispatch.
Generate current PC base to S1.
| Generate PC for each uop and pipe to S1 | Generate write data |
| --- | --- |

Write data to reference entry.
Write pointer moved.
| E3bru flush | E4 |
| --- | --- |

Generate write idx and pipe to S1.
Generate write enable and pipe to S1.
| Open reference RCG of PC entries | Write data to reference entry |
| --- | --- |

Generate current PC base.
Write pointer moved.
| R3rob flush | R4 | R5 |
| --- | --- | --- |
| Generate write idx and pipe to S1 | Generate write enable and pipe to S1 |  |
| write pointer moved | Write data to reference entry |  |

#### 6.6.6 Read and release operation

IEX read PC buffer at I1 stage the reference PC base is sent to IEX at I2 stage.
ROB release PC buffer at R2 stage.
The read pointer of PC moves at R3 stage.
ROB release up to 6 entries in 1 cycle.
ROB get the current released pc base and last released pc base at R2.

#### 6.6.7 Free num

The writer pointer means the idx of last written entry.
The read pointer means the idx of last released entry.
The free num is support to Decoder to ensure how many uops can be sent to PC next cycle The maximum is 3 In consideration of timing PC buffer provides not accurate results to the decoder and then the decoder calculates the exact value of free number.
When the reserved 1 entry is occupated by branch flush the free num is 0 When there is only 1 entry available the freen num supported to Decoder is also 0.
Also for timing reason the freen num supported to Decoder may not accurate but must guarantee not overflow.
Current implementation is as below uopvldd3 means there is at least one uop atcurrent D3 stage.
| PC buffer real free number | Uopvldd3 | Decoder used free number |
| --- | --- | --- |
| >7 | 3 |  |
| <7 | 1 | 0 |
| >4 <7 | 0 | 3 |
| >1 <4 | 0 | Real free number 1 |
| 0 | 0 |  |

There is a Corner Case for generating free num for Decoder.
This case appears only if when there is one entry left in PCB and the the last entry is used by a branch flush predicted not taken.
The more corner case is as below.
This case cannot appear normally it only appears when there is only one entry available Then a branch flush predicted not taken appears and the write pointer catches the release pointer.

#### 6.6.8 Pipeline Stage Description

D2 stage
Generate offset for each instruction and check pc overflow.
Offset
In implementation the offset is 6-bit width and is accumulated by incoming instruction size when overflow is generated on updating offset next instruction will be treated as taken branch.
After D2 we will carry 6-bit pc index and 6-bit offset in pipeline.
D3 stage
Generate pc buffer write address.
Target address can be.
| 1 | Predicted taken branchs target address comes from fetch |
| --- | --- |
| 2 | If take exception the base should be exception entry address |
| 3 | If a branch flush happens the base should be updated to the branch target pc |
| 4 | Artificially generated target address As the width of pc offset is 6 bits up to 64 instructions can share the same base pc If the continuous 64 instructions are not taken branch or no flush during this period we need to artificially create a new PC base and let subsequent instructions use the new base |

So we insert a virtual taken branch into the program flow Currently if fetching 64 instructions without a taken branch we store 65th instructions PC into PC buffer and reset PC offset to zero.
S1 stage
Generate write data and write pc buffer.
I1 stage
Access PC base buffer.
When PC is required by issued uop in I1 stage it will access PC buffer by pcbaseidx.
I2 stage
Calculate PC
This stage we just add pcbase[630] and pcoffset[60] to get final PC.
R2 stage
Release PC base buffer entry if entry is marked with release pc buffer attributes.

#### 6.6.9 PC buffer maintenance

Write pointer
| a> | When there is taken branch from fetch write pointer will be accumulated |
| --- | --- |
| b> | When there is branch flush write pointer set to the write pointer sent from BRU plus 1 the younger PC buffer entries are flushed |
| c> | When there is rob flush write pointer set to ZERO PC buffer is emptied on rob flush |

Release pointer
PC buffer has a release pointer it is updated when no instruction in the pipe rely on pointed entry to calculate PC.
| a> | taken branch retirement will free previous entry accumulate pc release pointer |
| --- | --- |
| b> | On take exception release pointer will be set to maximum index value |
| c> | Predicted taken branch miss prediction will flush younger entries while the entry related to this branch will be released normally |
| d> | The mispredicted branch instruction will be marked with pc release bit in ROB When this instruction retired the previous PC buffer entry will be released |

##### 6.6.1.0 Hashed PC for loads and stores

Load and store uops require hashed 12-bit PC for accessing the prefetcher.
To avoid having dedicated PCBuf read port for 2 load and 2 store issue pipelines 12-bit hashed PC will be calculated in S1 and it will replace the 6-bit PCBufidx and 6-bit PC offset fields in the IQ.

##### 6.6.1.1 PC for PCrelative loads

For PCrelative loads the full VA will be calculated in IEX I1 by adding the loads PC with the offset resulting in 48-bit value The PC of loads will be overloaded on the following fields that are not needed for PCrelative loads src1 ptag 9bits PC[102] src2 ptag 9bits PC[1911] srcp ptag 7bits PC[2620] hash PC 13bits PC[3927] src0 xfm1bit PC[40] src0 dpd3bit PC[4341] src1 dpd3bit PC[4644] srcp dpd2bit PC[4847]
A new bit is added to ALU IQ payload to denote the original PCrelative load instruction there are only 7 of them Value of zero will mean that the instruction was not overloaded.
Upon load issue from ALU IQU the original instruction will be reconstructed from these 3 bits while the overloaded fields will produce the VA that will be sent to the AGU on one of the src buses.
As before a separate 12-bit field will be used to keep the hashed PC to be used by MDBprefetch.
| 67 | SRF |
| --- | --- |

#### 6.7.1 Overview

Srf module maintains the system registers system state and system instruction reslove The update mechanism of System registers and system state is strictly followed the Arm spec rules There are also some selfdefined implementation registers for configuration override bit configuration and the detail could refer to the document of earlier-generation core implementation defined registers Srf structure is illustrated in the following figure The key logic is independent for each thread because each thread has its own configuration and system state and maybe they have different system states at the same time.

#### 6.7.2 Resolve generation

The msr instruction is resolved from SRF Wfiwfewfitwfet instructions are also decoded as system instructions and resolved from SRF.
The resolve consists of two parts based on resolve stage One is generated directly at e2 stage for most conditions The other one is generated at any stage if wfe trap delay is configured The resolve time is not a fix latency and based on the wakeup or timeout condition.
Resolved at e2 stage.
This part include msr and wfx when trap delay configuration is not enabled Srf receives system write signal from iex at e1 stage then sends resovle to rob at e2 stage.
There is also a resolved status accompanied with resolved signal It indicates that the resolve will trigger a flush or not The status is generated by selfdefined force flush when msr wfe wfet wfi wfit and normal ISB.
Delayed resolve
It only happened when wfewfet is not regard as nop instruction and wfe trap delay is configured When trap delay enabled and the trap condition matched the resolve is blocked and the wfe state is locked.
The wfe state can be unlocked when trap condition is masked by L2 event or interrupt or reach to time out If trap condition is masked the resolve will be triggered and it is treated as a wfewfet instruction If reach to time out and trap condition is not masked then resolve will also be triggerd but it is regard as trap and the trap infomation will report to exc module.

#### 6.7.3 Systerm registers and states

Most system register are in maintenance in srf module The registers can be updated by following mechanisms.
Normal update
In normal conditions the system registers are updated when core init thread power on some could be updated when thread off and directly written by msr.
Special update
Besides normal update rules some registers also have special update rules like pstate spsr dspsr etc These registers can be updated by exception entry exception return enter debug state exit from debug state or directly written when instruction commit like pstatebtype.
System configuration and state output.
System configuration and state is output to all other related modules For the sample consistence of different modules there are sample signals syswrclken separately to each module which are effective when reset thread on or ooo flushbasically on R4 or R5 stage.

#### 6.7.4 Wfx state process

Wfx wfewfetwfiwfit instruction execution has 3 conditions including nop trap and wfx sleep The priority of them is nop > trap > wfx sleep.
Nop
Wfx instruction can be decoded as nop instruction in dec module It is happened when cpuactlrel [7] and cpuactlrel [8] bit is valid or in debug mode Besides according to Arm spec wfet and wfit instruction can be treated as nop when local time out events are triggered before its resolve without event registers set The detailed information could refer to Arm spec.
Trap
If wfx is normally sent into iex and srf receive system write signal from iex srf module will judge the trap condition.
If wfx meet trap condition the wfx window will not set and srf will not send wfx req to L2C If trap condition is not masked srf will report trap information to exc module after ooo flush.
If wfx meet trap condition and also meet an interrupt the trap is masked Then wfx window will set and send req to l2c The interrupt wakeup flow is based on L2C internal logic.
Sleep
If wfx not meet trap or trap condition is masked in trap delay period wfx will be executed normally and core is ready to enter into wfx state after ooo flush Then OOO sends wfx req to L2 module After L2 delay certain cycles it will check empty of each box and check whether there is an event If all condition is matched l2 will turn off clk and the core is in lowpower status and wait for wake up The timing sequence could refer to the following picture.

#### 6.7.5 Exception info generate

Some exception is recognized by srf module mainly through msrsystem register write Srf module will update exception infomation to exc module Moreover the rid should also send to exc module because the exception in srf should replace younger decode exception by rid compare logic The following shows the exception type recognized by srf module.
ISB normal mode with an ISB.
SPEXIT sp mode with an ISB.
SELFSYNC selfdefined force flush.
WFX SLEEP wfewfetwfiwfit with no trap.
WFX TRAP wfewfetwfiwfit with trap.

#### 6.7.6 Shared register configuration

There are some shared registers or shared configuration override bits In ST mode they are used for thread0 or thread1 respectively But in MT mode they are used for both thread Shared registers are effective especially for safemode and threshold function Besides there is a need for same feature configuration for different thread which also need shared configuration override bits Moreover share registers can save power and area compared to individual registers.
However some implementation defined registers involve some shared hardware resources or have requirements for the execution state of the hardware If one thread is running under MT mode another thread configure this kind of implementation defined registers may cause running incorrectly or hang.
Therefore shared registers are needed to be configured before system works It is recommended that software configure the shared registers in poweron process as soon as possible The details of shared register configuration could refer to Chapter 78.

### 6.8 OtherTBD

#### 6.8.1 ooopmu

#### 6.8.2 oooftpinTBD

## 7 SMT

### 7.1 Mode Support

There are several planned modes to support.
ST Single thread mode all shared resource can be fully used.
MT Multithread mode two threads have no priority try to improve overall throughput.

### 7.2 Interface

| Signal | Direct | Description |
| --- | --- | --- |
| smten | input | Smt mode flag |

1 MT mode
0 ST mode
| thread0en | input | 1 thread 0 active |
| --- | --- | --- |

0. thread 0 deactive.

| thread1en | input | 1 thread 1 active |
| --- | --- | --- |

0. thread 1 deactive.

| smtack | output | For each smt mode switch or thread activedeactive need handshake by smtack cannot send another mode switch or thread activedeactive request to core if not receive last smtack |
| --- | --- | --- |

### 7.3 Protocol

Each thread has individual RVBAR and sampled at thread01en positive edge.
During reset smten and thread01en keep 1b0.
Thread01en can be set when reset deassert or N cycle after reset deassert.
Ooo see the thread01en to drive restart fetch if thread01en is low will not restart.
After reset deassert if thread01en is not set then smten can change without constraint Software will not use like this just for robustness if thread01en is set then smten change will follow power down and power up flow.
When smten is 1b0 then thread0en and thread1en cannot both are 1b1.
When smten is 1b1 then thread0en and thread1en can both are 1b1 or one of them is 1b1.
When reset with ST mode smten is 1b0 can start with thread0 or thread1.
When reset with MT mode smten is 1b1 can start with thread0 and thread1 same time or one by one or only one.
When switch from MT mode to ST mode smten only can change when one thread is deactive at least.
When switch from ST mode to MT mode new thread enable cannot be set earlier than smten set.
Thread01en change from 1b1 to 1b0 only by power down flow.
In MT mode one thread deactive the other thread can keep in MT mode or change to ST mode control by smten.
For any change for smtenthread0enthread1en core will give a smtack cpm cannot send another smtenthread0enthread1en change request before receive last smtack.
There is a l3ooowfiack when one thread starts ooo will wait that flag to start fetch instruction cpm cannot send another smtenthread0enthread1en change request before give last l3ooowfiack if last request has thread start.
When the powerdown register is configured by software cpupwrctlrel1[0] is 1b1 and before core goes to wfi state the dtu should mask the async halt req to ooo.

### 7.4 Mode switch driven by software

Due to there are many mode status and switch scenario exc will monitor the direct input smten and thread01en status and control the switch flow then generate the internal smten and thread01en to other modules.

#### 7.4.1 Reset

Before reset software can config the core restart with ST mode or MT mode.
Reset with ST mode.
When reset with ST mode will send restart VA of thread 0 or thread 1 when thread01en is set.
Reset with MT mode.
When reset with MT mode will send restart VA of thread 0 and thread 1 one after the other when thread01en is set.

#### 7.4.2 ST Mode Switch to MT Mode

Exc monitor the smten from 1b0 to 1b1 generate internal interrupt to current active thread.
Rob stop nflush pointer move forward wait for current instructions older than nflush pointer commit then generate current thread ooo flush.
Exc wait emptyonly include mpq empty and stq empty and count for 16 cyclesto avoid mpq empty and stq empty falsely to generate internal smten and thread01en.
Each module adjust control pointer range and finish initialization at internal thread01en posedge or smten posedge.
Exc send restart va of current thread then send a smtack for the handshake.
Rob generate ooo flush of new thread send new thread restart va.

#### 7.4.3 STMT Mode Deactive

Software config special system register Msr xxx.
Execute WFI
Core send empty signal to power control.
Power control pull down thread01en after collect all empty.
Rob does not generate ooo flush but update internal thread01en.

#### 7.4.4 MT Mode Switch to ST Mode

At least one thread has been in deactive.
Exc monitor the smten from 1b1 to 1b0 generate internal interrupt to current active thread.
Rob stop nflush pointer move forward wait for current instructions older than nflush pointer commit then generate current thread ooo flush.
Exc wait emptyonly include mpq empty and stq empty and count for 16 cyclesto avoid mpq empty and stq empty falsely to generate internal smten and thread01en.
Each module adjust control pointer range and finish initialization at internal smten negedge.
Exc send restart va of current thread then send a smtack for the handshake.

#### 7.4.5 STMT Mode Deactive to Reactive

Exc monitor the smten not change but the thread01en change from 1b0 to 1b1.
Exc update internal thread01en to each module.
Each module finish initialization.
Rob generate ooo flush of new active thread send new active thread restart va.

### 7.5 Mode switch by hardware when wfi

At MT mode to improve the performance when one thread sleep down due to wfi support switch the partition resource usage mode automaticly by hardware This kind of switch is not a real MT mode switch its a kind of resource usage mode switch which means partition resource fully used by one thread or partition used by two threads To support this feature need to define another set of signals to indicate the resource usage mode and which thread is active.
| definition | signal | description |
| --- | --- | --- |
| Intput software mode stauts | smten | The smt mode request from power ctrl to core configured by software Only visible for exception |
| thread0en | The thread0 power up or power down status from power ctrl to core controlled by software Only visible for exception |  |
| thread1en | The thread1 power up or power down status from power ctrl to core controlled by software Only visible for exception |  |
| Internal software mode status | mtmode | Internal software smt mode indication visible for whole core after exception finish handshake with power ctrl |
| t0en | Internal thread0 power up or power down indication visible for whole core after exception finish handshake with power ctrl |  |
| t1en | Internal thread1 power up or power down indication visible for whole core after exception finish handshake with power ctrl |  |
| Hardware switch mode status | mtpartition | Hardware partition or fulled used indication used for partition resource mode switch Its change may be due to software configuration mode or wfi sleepwakeup |
| t0active | Hardward thread0 active indication |  |
| t1active | Hardward thread1 active indication |  |

WFI hardware switch from partition to fully used only happens when software mtmode is 1b1 and both t0en and t1en is 1b1 When reset with mt mode but only run one thread this will be considered as the software wants to run that thread in hardware partition mode hardware will go into mtpartition mode and t0active or t1active according to which thread is running.
When running in mt mode and both thread is active then if one thread executes WFI hardware can automatically switch from partition to fully used for the other thread There will be some cross scenarios for the two threads which in nonwfiwfiwake up state.
First list the partition resource and t0t1 active status at different SMT mode and different t0t1 status.
| mode | t0en | t0status | t1en | t1status | mtpartition | t0active | t1active |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ST | on | any | 0 | 1 | 0 |  |  |
| on | any | 0 | 0 | 1 |  |  |  |
| MT | on | any | off | 1 | 1 | 0 |  |
| off | on | any | 1 | 0 | 1 |  |  |
| on | wfi | on | wfi | 1 | 1 | 1 |  |
| wfi | wfi | 0 | 1 | 0 |  |  |  |
| wfi | wfi | 0 | 0 | 1 |  |  |  |
| wfi | wfi | 1 | 0 | 0 |  |  |  |

For the resource status indication mtpartition t0active t1active the switch driven by software please see previous description The switch flow by hardware automatically in wfi is similar as previous flow basically is by generating a fake interrupt The following table list the mtpartition t0active t1active switch status at different hardware wfi scenarios.
| Old | New | Comment |  |  |  |  |
| --- | --- | --- | --- | --- | --- | --- |
| pt | t0 | t1 | pt | t0 | t1 |  |
| 1 | 1 | 1 | 0 | 0 | 1 | Two threads are running and t0 goes into wfi |
| 0 | 1 | 0 | Two threads are running and t1 goes into wfi |  |  |  |
| 1 | 0 | 0 | Two threads are running and t0t1 goes into wfi at same cycle |  |  |  |
| 0 | 0 | 0 | Not support |  |  |  |
| 0 | 0 | 1 | 1 | 1 | 1 | t1 is running and t0 is waked up from wfi |
| 1 | 0 | 0 | t1 is running and then t1 goes into wfi |  |  |  |
| 0 | 0 | 0 | Not support |  |  |  |
| 0 | 1 | 0 | t0 is waked up and t1 goes into wfi at same cycle will first process t1 goes into wfi then process t0 waked up |  |  |  |
| 0 | 1 | 0 | 1 | 1 | 1 | t0 is running and t1 is waked up from wfi |
| 1 | 0 | 0 | t0 is running and then t0 goes into wfi |  |  |  |
| 0 | 0 | 0 | Not support |  |  |  |
| 0 | 0 | 1 | t1 is waked up and t0 goes into wfi at same cycle will first process t0 goes into wfi then process t1 waked up |  |  |  |
| 1 | 0 | 0 | 0 | 0 | 1 | Two thread are in wfi and t1 is waked up |
| 0 | 1 | 0 | Two thread are in wfi and t0 is waked up |  |  |  |
| 1 | 1 | 1 | Two thread are in wfi and t0t1 is waked up at same cycle |  |  |  |
| 0 | 0 | 0 | Not support |  |  |  |

There will be a configuration override bit to control whether hardware automatically switch mode The configuration override bit is one physical register shared by two threads only support config after reset detail see share configuration override bit config flow default is automatically switch if configure not switch then disable automatic switch The exception level to configure the configuration override bit is EL1 and above EL1.

### 7.6 Resource Share Choice

For SMT there are many resources need to decide how to share between different threads The possible share policy in LinxCore is as following.
| 1 | Full share |
| --- | --- |

Each thread can use all of the resource with no limit.
| 2 | Threshold |
| --- | --- |

Each thread can use most of the resource and cannot use more if reach the upper threshold.
| 3 | Private |
| --- | --- |

Only can be used for one thread and will not be used by the other thread even in single thread mode.
| 4 | Partition |
| --- | --- |

Divide the resource with dedicate ratio can be 11.

### 7.7 SMT Microarchitecture

#### 7.7.1 Resource Share Policy

The basic resource share policy is shown as following picture Two different color stands for different threads.

#### Figure 3 Main resource share policy for SMT

- Decode share combinational logic but has private pipeline flops.
- Dispatch share one path.
- Ptag full share threshold is optional.
- Cmap commit architecture register mapping private.
- Smap latest architecture register mapping private.
- Mpq speculative register mapping record table partition.
- Pc base pc buffer partition.
- Rob reorder buffer partition.
- Srf system register private.
- Exc exception handler private.

#### 7.7.2 Some Details

##### 7.7.2.1 Decode Path

Decode has private pipeline flop for each thread share one set of decode logic.

#### Figure 6 Two private path for SMT

##### 7.7.2.2 Partition

For SMT RobMpqPc buffer is static partition This means each thread will occupy half of these resource in MT mode Take Mpq as one example in ST mode the control writecommitrecoverflush pointer will cover whole resource range in MT mode there will be two set of writecommitrecoverflush control pointer each will cover half resource range.

#### Figure 7 Current mpq without SMT

#### Figure 8 Partition for SMT

##### 7.7.2.3 Cmap Recycle and Initial for deactive and reactive

When one thread deactive commit all older instructions than power down sequence flush speculative fetched instructions then do a special logic to recycle the ptag in cmap.
When one thread reactive trigger an initial logic to initialize cmap architecture register to Zero register.

##### 7.7.2.4 Rob commit

For rob commit only one thread can commit in one cycle it can reduce pc read ports and control logic The detail of tid selection logic could refer to chapter 6411.

##### 7.7.2.5 Dispatch protocol

Dispatch protocol between OOO dispatch and issue queue is described in the following diagram.
1For each issue queue it will send entry number used by T0 and T1 at S2 stage It is plused by cost num in S1 stage for calculation.
2the upper limit signal means as follow.
| upper limit alu t1 | alu issq depth threshold alu t0 |
| --- | --- |
| upper limit alu t0 | alu issq depth threshold alu t1 |
| upper limit agu t1 | lsu issq depth threshold agu t0 |
| upper limit agu t0 | lsu issq depth threshold agu t1 |
| upper limit fsu t1 | fsu issq depth threshold fsu t0 |
| upper limit fsu t0 | fsu issq depth threshold fsu t1 |

Threshold should be configured by software.
Threshold T0 means that the minimum entries supported by corresponding issq for T0 Threshold T1 means that the minimum entries supported by corresponding issq for T1.
Upper limit T0 means the maximum entries can be used by T0 upper limit T1 means the maximum entries can be used by T1.
3When T0 used num more than upper limit t0 in any issqinclude 3 alu issq 2 agu issq and 2 fsu issq T0 exceed threshold When T1 used num more than upper limit t1 in any issq include 3 alu issq 2 agu issq and 2 fsu issq T1 exceed threshold.
4the stall policy work as follow.
| T0 exceed threshold | T1 exceed threshold | T0 threshold stall | T1 threshold stall |
| --- | --- | --- | --- |
| 0 | 0 | 0 | 0 |
| 0 | 1 | 0 | 1 |
| 1 | 0 | 1 | 0 |
| 1 | 1 | 1 | 1 |

##### 7.7.2.6 Issue policy

In issue queue agebased issue policy is used which means if all issue condition meets sources are all ready no conflicts uop which goes earlier in issue queue will issue first Theres no priority between threads.
For issue uop to ldq if one thread occupy too much ldq entries will block this thread not ready in issq and try to issue the other thread It can be configured by configuration override bit.

##### 7.7.2.7 Ptag threshold policy

- To prevent one thread pick too many ptags and the other thread is always hungryAdd ptag threshold policy for float path ptags which is configured by configuration override bit Ptag threshold policy is described in the following.

| 1 | Through thresholdtypesystem resgister obtain the maximum number of ptags that can be used by each thread |
| --- | --- |

Maxuseptag ptagnum thresholdtype x 16 16.
| 2 | Calculate the number of ptags in each thread mpq mpqptagnum include d3 and s1 stage and cmap cmapptagnum |
| --- | --- |
| 3 | When one thread use ptags exceed maxuseptag ptag freelist product a ptag threshold stall ptagthresholdavail to decoder |

Ptagthresholdavail maxuseptag mpqptagnum cmapptagnum > decdstnumd2.

##### 7.7.2.8 Thread Arbitration

###### 77281 Decode thread arbitration

There are several points related with thread arbitration in decode First is generate the thread selection signals to ifu ifu will select the instructions of corresponding thread except that thread instruction is empty The others are D2 and D3 pipeline instructions selection for different threads.
For each thread selection signal there is a lot of payload to be selected so the selection signal must have good timing The basic requirement is the thread selection signal need be flop out and the logic of D side need be loose the selection signal will borrow some timing from previous stage So the thread generate logic is cycle N decide cycle N+1 selection.
Thread arbiter
Two thread are both active in MT mode Each cycle IFU will send one threads instruction to decode based on the thread selection given by decode in the previous cycle Only one thread can be selected to go in pipeline for each cycle at D1 D2 and D3 stage in decode Each thread has a set of flops to store information at D2 and D3 and there is only one set of combination logic for all thread at D1 D2 and D3 stage So decode has four sets of thread arbitration logic working in every cycle to select corresponding thread to use in ifu and decode.
1Arbiter of thread selecting from OOO to IFU Decode will send selecting thread to ifu at F4D1 stage IFU would send this threads instructions to decode at D1 stage if there are instructions of this thread existing in instq This arbitration behavior is implemented as follows.
Step1 If any thread is in special mode or rob remaining resources are equal thread is selected with round robin like t0 t0 t1 t1 If no thread is in special mode and rob remaining resources are not equal the thread with more rob remaining resources is selected But if the selected thread has a D2 stall another thread does not have a D2 stall switch to another thread.
Step2 If one thread is continuously selected and the starvingcount is reached another thread will be selected for 2 cycle or 4 cycle.
Step3 If a yield instruction appears in one thread this arbiter will be forced to select another thread during yieldcounting period Only one threads yield behavior is permitted at a time.
Step4 If one thread enters wfx or inactive mode another thread will be selected.
2Arbiter of thread selecting for D1 Decode will select a thread at D1 and process the threads instructions at same cycle This arbitration behavior is implemented as follows.
Step1 If any thread at D2 has mcyc hint and this thread is selected at D2 now D1 would also select this thread for D1 Otherwise D1 select ifuoootidd1 to process.
3Arbiter of thread selecting for D2 Decode will select a thread at D1 and process the threads instructions at D2 stage next cycle This arbitration behavior is implemented as follows.
Step1 Select the thread which has uop at D2 next cycle If there are no uops for both thread at D2 next cycle the current selection remains unchanged If both thread have uops at D2 next cycle arbiter will go through step2 and step3 if not just go to step4.
Step2 Select 1-cycle previous thread sending by decode to IFU or just select with round robin which could be configured by chickbit.
Step3 If the thread which was not selected at D2 at present cycle was stalled then the current selection remains unchanged.
Step4 If one thread enters wfx or inactive mode choose another thread.
4Arbiter of thread selecting for D3 Decode will select a thread at D2 and process the threads instructions at D3 stage next cycle This arbitration behavior is implemented as follows.
Step1 Select the thread which has uop at D3 next cycle If there are no uops for both thread at D3 next cycle the current selection remains unchanged If both thread have uops at D3 next cycle arbiter will go through step2 and step3 if not just go to step4.
Step2 Select 2-cycle previous thread sending by decode to IFU or just select with round robin which could be configured by chickbit.
Step3 If the thread which was no selected at D3 at present cycle was stalled then the current selection remains unchanged.
Step4 If one thread enters wfx or inactive mode choose another thread.

###### 77282 Rob thread arbitration

Rob thread arbitration decide which thread to do commit and update nonflush pointer the arbiter logic is cycle N logic decide cycle N+1 select which thread If cycle N both thread is ready for commit then will select round robin If one thread is ready and the other is not ready will select the ready thread.

###### 77283 Mpq thread arbitration

Mpq thread arbitration decide which thread to do ptag mapping commit flush and recover the arbiter logic is cycle N logic decide cycle N+1 select which thread If cycle N both thread need do commit or rebuild then will select round robin If one thread need and the other is not need will select the need thread.

### 7.8 Shared implementation defined register configure

Some implementation defined registers involve some shared hardware resources or have requirements for the execution state of the hardware If one thread is running under smt another thread configure this kind of implementation defined registers may cause running incorrectly or hang.
For this kind of implementation defined register it is designed to have only one physical register and both threads can write but when one thread is configured the other thread cannot execute any instruction.
If need configure the shared implementation defined registers need first set the register in soc sub control field which indicates need configure shared implementation defined registers.
There is another implementation defined flag register CPUACTLREL3SMTCKBCFG accessed by msr in the core to indicate whether shared implementation defined registers are configured completed or not The default value is 0 when it is 1 means the kind of configuration is completed.
If the flag value is 0 when the hardware sees that both threads are enabled one of the threads will be blocked from fetching instructions until the flag value is 1.
When the software configures shared implementation defined register first need check whether the flag register CPUACTLREL3SMTCKBCFG is 1 or not If flag is 0 configure shared implementation defined register normally write the flag register to 1 after the configuration is completed mandatory not write the flag to 1 may hang If flag is 1 do not configure shared implementation defined register again unpredictable.
In order to improve efficiency the software configures shared implementation defined register as forward as possible in the program.
So software typical configure flow is like.
| Step 1 | Set register in soc sub control to indicate need configure shared implementation defined register |
| --- | --- |
| Step 2 | Read CPUACTLREL3SMTCKBCFG if the value is 0 then configure the shared register write CPUACTLREL3SMTCKBCFG to 1 after configure if the value is 1 then directly end |

## 8 SVE

### 8.1 Predicate rename

Predicate register is a new architecture register need a new rename For predicate register rename still use similar smapcmapmpqfree structure For sve instruction uop break src1src2 can be general fp register or predicate register src3 is only general fp register new srcp is only predicate register dst can be general fp register or predicate register So predicate register rename bandwidth is still 3 of each uop.
Block diagram
Due to some source and destination is reused for general fp register and predicate register predicate rename design need consider how to cooperate with general fp register.
Smap
To reuse some control logic and save mpq area predicate register atag will map to 3247 in implementation that is p0>atag32 p15>atag47 original atag is 6bits this map will not increase implementation atag bit For src1 and src2 which can be general fp register and predicate register will look up atag0atag31 and atag32atag47 to get ptagdpd information Src3 still look up atag0atag31 for above information Srcp will look up atag32atag47 for above information.
Free
Free module is used to maintain free ptag list and look for free ptag to update ptag fifo Ptag fifo will support ptag for smap destination rename There will be separate ptag free for general fp register and predicate register General fp register ptag will increase to 96 pair predicate register ptag is 56 Each ptag free will support individual free ptag to smap.
Mpq
Map queue is used to record each renamed atag and ptag mapping relation and store some necessary information like dpdridmovsz Map queue will commit ptag to cmap according to rob commit rid and will recover ptag to smap when flush There will be a shared map queue for general fp register and predicate register rename When commit and recover different atag will go to different smap and cmap part general fp register and predicate register will be distinguished naturally Map queue depth will be adjusted according to performance tuning result If map queue is full then will generate stall to smap rename.
Cmap
Commit map is used to record commited atag and ptag mapping Its extension is similar with smap predicate register atag will map to atag32atag47 Release ptag when commit will be distinguished according to atag arrange.

### 8.2 Support Neon and SVE for Fp rename

#### 8.2.1 Neon and SVE Ptag alloc

Each bank FP regfile is 128bit128 for SVE pair it can be used as 256 Z physical register for Neon it can be used as 128 Q physical register For rename the ptag will be defined 0 255 For SVE pair it need rename use a same ptag of each bank for Neon it can be renamed using single ptag.
For Neon type will rename to [redacted numeric sequence]
For Sve type will rename to 012345 126127consume 00128 l 11129 l [redacted numeric sequence]

- For each cycle rename will divide the freelist into two parts pairsingle For each freelist take similar way to search free ptag as 62153 for new uop allocate.
- When single fifo not enough for uop we can break pair ptag to single and if have break ptag left after uop use Left ptag will write to single ptag freelist.

### 8.3 FFR flow

FFR related instructions are WRFFRSETFFRLDFFxLDNFxRDFFR RDFFR will read architecture FFR register to update predicate register the others will update architecture FFR value.
From function view any write and read FFR should keep order to insure correct function From performance view will try to increase high frequency instruction execution efficiency According to arm spec WRFFR is a low frequency instruction mainly used for restore a saved FFR The others are all high frequency instructions.
WRFFR
This will be executed by nonspec msrsee next retired rid and will gen rob flush The source of WRFFR is predicate register it is floating point register due to iex do not bypass all predicate update value so there will be one f2i operation before execute the msr.
LDFFxLDNFx
This will be executed out of order Due to load has fault is small probability event and simplify design if there is a fault for this kind of instructions there will be a rob flush to insure the order of read ffr after the load In lsu there is a fault buffer will store the oldest update ffr value when fault due to lsu update part must be continuous zero so record the fault index is enough.
When rob commit this kind of instructions will check the fault information and tell srf to update architecture ffr register if necessary.
NOTE for LDFFx if the first active element has fault then will take exception not update architecture ffr value.
SETFFR
Srf will send rob one bit to indicate whether architecture ffr value is all ones or not in real time Rob will check the ffr all ones bit from srf when setffr enters into rob entry If ffr value is not all ones it will set excexcnxt signals valid then will generate rob flush when commit If there are ldff or wrffr before setffr nonflush pointer will be blocked before setffr Therefore ffr all ones bit is correct when setffr commit When flush rob will tell srf to update architecture ffr register.
RDFFR
This will be dispatched to fsu and execute out of order Srf will send a copy of architecture ffr to fsu fsu will update the architecture ffr to predicate destination.

### 8.4 System register

#### 8.4.1 ID Register Update

In earlier-generation core we fully support FEATSVE and FEATSVE2.
For FEATSVE IDAA64PFR0EL1SVE[3532] is updated to 4b0001.
For FEATSVE2 because we fully support all optional features.
IDAA64ZFR0EL1SM4[4340] is updated to 4b0001.
IDAA64ZFR0EL1SHA3[3532] is updated to 4b0001.
IDAA64ZFR0EL1BitPerm[1916] is updated to 4b0001.
IDAA64ZFR0EL1AES[74] is updated to 4b0010.
IDAA64ZFR0EL1SVEver[30] is updated to 4b0001.

#### 8.4.2 CPACREL1CPTREL2CPTREL3

CPACREL1ZENCPTREL2TZCPTREL3TZ is added to trap All SVE instructions.
CPACREL1FPENCPTREL2TFPCPTREL3TFP traps all SVE and SIMDFP instructions.
A trap due to SVE takes priority over a trap due to previous SIMDFP according to SVE Arm SPEC.
In LinxCore the above choice is the bits higher or equal to 128 of the Zn register is always zero.

#### 8.4.3 ZCRELx

Accessing ZCRELx is under control of CPACREL1CPTREL2CPTREL3 related control bits If trap the related ESREC is 0x19.
ZCRELxLEN Constrains the SVE vector register length for ELx and lower exception levels with the same security state to LEN+1 128 bits The max vector length is 256 so the valid LEN value is only 0 or 1 OOO is responsible for selecting correct Vector Length and send to module needs this info.
From the SVE SPEC ZCREL x should also in SelfSync List but in optimization way see SelfSync optimization The SPEC defines as below An indirect read of ZCRELxLEN appears to occur in program order relative to a direct write of the same register without the need for explicit synchronization.
From SVE SPEC ZCRElx Reset Value is UNKOWN LinxCore defines the ZCRElx reset value is 4b0000.

### 8.5 Exception

#### 8.5.1 UNDEF Exception

According to SVE XML SVEHPCxml00rel401 OOO will detect all UNDEF Exception.
When in Debug State the below instructions does not change behavior according to SVE SPEC.
And for CMPNE compare vector not equal to imm instruction has unchanged in Debug state.
And for all other SVE instructions SVE SPEC permits CONSTRAINED UNPREDICTABLE behavior see below.
OOO prefer to choose the last option behavior the same as NonDebug State.
So from above Description OOO does not need to do anything for SVE instruction in Debug State.

#### 8.5.2 Trap Exception

A new EC0x19 is added for all SVE instructions and ZCRELx trap control.
Newly added exception types exctype are TRAPEL1SVETRAPEL2SVETRAPEL3SVE.

### 8.6 Gather load instruction optimization

Gather load is a kind of complex instruction which is broken into up to 8 uopsuop0uop7 see details in 61102 Among these uops uop0s dst will be temporary and uop6 will be a move instruction with a temporary src uop0s dst so these uops need to be specially processed For uop0 the temporary dst should not be recovered or commited but need to be flushed For uop6 the dst need to be recovered and commited but should not be flushed Decoder will generate 2 signals to indicate the the attribute of these uops gatherfstvld and decfmov For uop0 gatherfstvld is high and decfmov is low in vfp smap it will generate a mpqfmov signal and set to high so when flush happens dst is temporary and will not be recoverd but can be flushed because it is temporary reg with mpqfmov attribute when commit happens the tmp ptag will not be release to ptag freelist with mpqfmov attribute For uop6 gatherfstvld is low and decfmov is high smap will set mpqfmov signal as high but will not set lock signal as high like normal move instructions when flush happens recover will do normally but should not be flushed with the attribute of renfmov and not temporary when commit happens commit will do normally renfmov and not temporary.
