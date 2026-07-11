# LSU Overview, Features, and Basic Flows

This document introduces the load/store unit, its feature set, supported instruction classes, basic load/store flows, ordering requirements, structures, arbiters, pipelines, and external interfaces.

> This Markdown document is the maintained specification. Sensitive metadata and internal reference labels have been removed while preserving architecture semantics.

| 1 | Key Characteristics Overview |
| --- | --- |

This chapter summarizes the key characteristics of the target core load/store Unit LSU Chapter provides Overview of the features instruction flows interfaces structures.
The primary responsibility of the LSU is to execute Arm load and store instructions Load instructions read data from memory and write to integer and FP registers Store instructions read data from registers and write to memory The LSU also executes special Arm instructions including barriers system instructions software prefetch exclusives and atomics.

### 3.1 Features

LSU provides the following features.
| Feature | Description | Status |
| --- | --- | --- |
| 3 LDU Issue | Three load issue pipes lda0lda1lda2 | Baseline |
| 2 STA Issue | Two storeaddress STA issue pipes sta0sta1 | Baseline |
| 2 STD Issue | Two integer storedata STD issue pipes 16B8B istd0 16B8B istd1 | Baseline |
| ST pipe 32B | 2 store pipe both support 32B store for FPSVE | Baseline |
| FSU Issue Interface | Two FP storedata STD issue pipes 32B fstd0 32B fstd1 | Baseline |
| IEX Result Interface | Six 8B load result write ports to IEX integer regfile |  |

Baseline
| FSU Result Interface | Three 32B load result write ports to FSU FP regfile | Baseline |
| --- | --- | --- |
| No Uop Expansion in LSU | Remove uOp expansion from LSU to OOO |  |
| LSU executes 1 uOp per issue | Baseline |  |
| 3 Load Result Pipes | Three load result pipes pipeapipebpipec to support Three load data returns per cycle Each result pipe is 32B wide to IEX and 32B wide to FSU | Baseline |
| Mis Align Loads 32B Crossing | Misaligned loads within 32B have same latency as aligned loads Cross32B loads require Dual access to DC and merging two Data paths in Data Cache with 0 penalty | Baseline |
| 64B Mis Align Crossing SBMA | Skid buffer SB for LD MA Crossing a Cache Line |  |

64B misalign is handled in 5 cyc +1 latency LD to Use.
| For Skid buffer MA need wakeup at E1 | Dropped |  |
| --- | --- | --- |
| Load to Use Latency | 4-cycle default load to use latency on DC hit | Baseline |
| L1 Miss L2 Hit latency | 10-cycle fastest load to use latency on DC miss L2C hit | Baseline |
| L1 Data Cache | 64 KB L1 Data Cache DC with 64B cachelines 4way set associative virtually indexed VA[136] physically tagged PA[4812] writeback write allocate on write miss |  |

DC is divided into 8 slices by VA[53] and each slice is divided into 8 banks by VA[86] The memory in each bank is implemented by singleported 1RW memory with ECC protection LRU replacement policy MESI coherency states inclusive in L2cache L2C and ECC protection for tag and data RAMs.
| Supports max load BW of 3x32B 96BCyc | Baseline |  |
| --- | --- | --- |
| New DC SDB | 64 physical RAM banks each 128 entries x 8B for a total of 64KB of storage The array is banked according to va[83] 8 DC slices are Selected by VA[53] and per DC slice 8 bank are selected by VA[86] Each bank is singleported 1RW and has 1 SDB written by SCB | Baseline |
| Data Bank Arbiter | 3 LDU Fill Snoop SDB | Baseline |

Access DC on Falling clock.
| Access DC on falling clock of E2 E2H after doing pTAG Match | Baseline |  |
| --- | --- | --- |
| L1 DC TAG | SRAM with data banking on PA[6] Systolic TAG WR bus + to reduce Fill conflicts with loads over 1RW do we keep this latch or make SRAM | Baseline |
| Store TAGC | A copy of the DC TAG noted as TAGC provides additional read port for stores and prefetches |  |
| Store Tag use 1RW memory with PA[6] banking to reduce the fillstore rd confliction | Baseline |  |
| Fast WP | 16 Entry DC way predictor Fast WP using VA hash [3510] and VA[96] CAM that selects one DC DATA RAM way to readenable for power savings | Dropped |
| Partial LD Tag | Partial LD TAG in ResA VIVT that generates wayselect and hitmiss indication pTAG results are sent to LDUctrl to verify vs full LD TAG no match brings to cancel 3R from each LDU 1W fill ports Latch based memory | Baseline |
| Seperating ST and LD TLB | Seperated allocationdeallocation for loads and stores TLB The Load and Store TLBs are of different sizes and commonly hold some different pages | Baseline |
| 2 Level TLB | uTLB and MTLB to balance power and area |  |
| uTLB 16 entries MTLB 128 entries | Baseline |  |
| MTLB Arbiter | arbitrate uTLB miss request TLBI Request TLB PF request to MTLB | Baseline |
| LD TLB | 64-entry L1 Data TLB Replicated per load pipe with 3 CAM port Multiple page sizes and interface to MMU for hardware page table walking |  |

All Load TLB and Store TLB are updated separately.
| For SMT its shared by the 2 threads According to the common not provided | Dropped |  |
| --- | --- | --- |
| Use Base address for uTLB lookup | Use base address srca or srcb to lookup TLB only for ST | Baseline |
| ST TLB | ST TLB 40 entry 2 CAM ports for 2 store pipes | Dropped |
| Load InFlight Queue LIQ | Merging 910s LMQ and LAGB structures to single component Noted as LIQ Selecting to RepickReissue based on AGE of load |  |

48. entry LIQ Load Inflight Queue holds issued load instructions from IEX that are cacnelled and need to RepickReissue.

LIQ is partitioned to a queue per load pipe 13 total size.
Support for putting loads to sleep in LIQ and waking up loads and dependents once data return is ready.
| For SMT shared all resources between the two threads | Baseline |
| --- | --- |
| Load Hit Queue | 120-entry load hit queue LHQ which holds loads that returned data to IEXFSU resolved loads to check for ordering violations with older loadsstores and generate nuke flushes to ROB |

LHQ is partitioned to a buffer per load pipe 403 120 entry in total.
| For SMT split all resources in and double the control | Baseline |
| --- | --- |
| Memory Disambiguation Buffer | Support for speculatively executing younger loads before older stores generate addresses Detect and nuke flush incorrect speculations at byte overlap granularity and record such loads in a memory disambiguation buffer MDB so they wait for interacting stores in the future |

MDB is a 64-entry fully associative structure indexed by hashed load PC[120] shared by 2 threads which tracks up to 3 interacting stores and an extra SnR store per entry.
Supporting Save and Restore mechanism.
| Supporting Store PC mechanism | Baseline | Moved to OEX |  |
| --- | --- | --- | --- |
| SNR Nukes Filter | Support SNR save and restore store and loads in function calls indication in MDB | Baseline | Moved to OEX |
| Load Snoop Nuke Scheme | Stricter LDNuke scheme vs Arm |  |  |

Best case Snoop hits a resolve load in LHQ where all older loads are pgen and not hit by snoop in this case no need to send nuke.
2nd case Snoop hits resolve load where all older loads are pgen and one is hit by snoop in this do not send nuke.
3rd case snoop hits resolved load where all older loads are not pgen in this case send nuke on oldest resolved This is the most common case.
For all other cases it is nuke on oldest.
| This solution does not including 2nd oldest CAM logic Nuke decision is made immediately without a window | Baseline |
| --- | --- |
| Basic Buffer | 4-entry load data buffer BasicB shared by 2 threads Extra 2 buffer for misaligned NCDev load or oldest WB load Each thread has a separate Buffer |

Which holds load data return from L2C for NCDEV loads.
| BasicB also holds the load data for the oldest WB load for 2 threads to help guarantee forward progress | Baseline |  |
| --- | --- | --- |
| Safe Mode | Per local LIQ support Safe Mode for Oldest Mis Align Load | Baseline |
| Store Queue STQ | 40 entry store queue STQ shared by 2 threads Holds stores waiting to commit and forwards store data to younger loads | Baseline |
| vSTQ for loads | Store forwarding to loads based on full VA CAM at E1 stage with STQ data array read at E3 stage |  |
| Each load pipe includes a replication of the vSTQ CAM Single port CAM | Baseline |  |
| Changed PA hazard check | memory aliasing detection based on uTLB and MTLB hitmiss results |  |

Baseline
| PA STQ for Store Retire | Store retires work on PA STQ |
| --- | --- |

In SMT it retires the same threads inst per cycle SYS DMBDSBATIC one retirecycle.
| Adding one delay stage pipe in store retire | Baseline |
| --- | --- |
| 32B Store Pipe | St pipe 232B |

ST Pipe Width affects multiple boxs microarch.
| Mostly for SVE where we are better than Axx | Baseline |  |
| --- | --- | --- |
| Multi Hit STQ LDST FWD | Support STQ hit Multi hit where data is coming from 2 STQ entries |  |
| Non overlapping | Baseline |  |
| Store Retire BW | Support retire of two STQ entries up to 32B store data to SCBSCD per cycle | Baseline |
| Store Coalescing Buffer | Store coalescing buffer SCB shared by 2 threads with larger SCD 16X32B |  |
| With decoupled storage for address 14-entry SCA and data 16-entry SCD SCB merges committed stores to the samecacheline before writing to DC SCB receives up to two stores from STQ each cycle total 32B of store data and has 232B write interfaces to DC and 164B write interface to L2C | Baseline |  |
| Remove MOB from 920 | Move MOB tasks to handle NCDEV to LHQ Age order |  |

Move multi uOp resolve to OOO SIMD SVE.
Baseline
| LFB Line Fill Buffer | LFB task to merge all L2 requests from load pipes to a single request per CL |  |
| --- | --- | --- |
| The Store L2 missrequest are not handled by LFB StoresSCB L2 request have a seperate controller which is not part of the LFB port Both Arbitrating toward 2 RSB ports RSB port per PA[6] | Baseline |  |
| SBI2 Skid buffer I2 | The SBI2 provides 3 opportunities for an issued uOp to regain access to pipe when it was blocked due to I2 Arbitration logic |  |
| Remove I2E1 stage | Baseline |  |
| Level0 bypass for 1-cycle ALU to LSU | Level0 bypass from ALU to LDU | Baseline |
| Move Wakeup to I2 | Generate Wakeup to IEX dependent uOp at I2 was E1 in 920 | Baseline |
| L2C Interface | Support 64B L2C interface for fill data return |  |

Support 64B L2C interface for DC evictions copyback snoops NCDEVNT write requests.
PA Based Snoops in LHQ.
RSB Full indication LSU cannot send L2 Request when Indication is active.
| Allow issued loads to bypass data from fill pipe at C7 | Baseline |  |
| --- | --- | --- |
| Smooth L2C Latency | L2 send speculative wakeup C1C2 to LSU The LFB records wakeup and all load merged to this LFB entry could share the wakeup status | Baseline |
| Remove L1V nukes | Reduce snoops to L1 by removing nukes on L1V which are not evicted from L2 Only snoop nuke on L2V |  |
| Changed snoop from L1V to L2V and PA based hash to LHQ | Baseline |  |
| Memcpy Throughput | 64B load + 64B store per cycle memcpy throughput for both L1 hit and L2 hit cases | Baseline |
| SVE Loads and Stores | Support SVE 20V9 for R1 | Baseline |
| SVE Width | Support SVE gather loads and scatter stores for 256-bit vectors | Baseline |
| Streaming Stores | Detect a stream of stores to sequential addresses that miss DC and send these stores directly to L2C as writethrough requests instead of writeallocate requests to save fill bandwidth and reduce DC pollution | Baseline |
| Detect more complex patterns of streaming stores such as FWR benchmark | Baseline |  |
| Detect when size of store stream crosses threshold of DC size or L2C size and use this to control cache allocation level | Baseline |  |
| DCZVA Optimization | DCZVA occupies one STQ entry and has 1-cycle STA retire throughput no STD retire no SCB data entry SCD required 1-cycle DC write throughput 1-cycle writethough to L2C throughput control of cache allocation level like streaming stores | Baseline |
| Hardware Prefetcher | Monitor DC miss requests to L2C and generate prefetch requests for addresses that are predicted to be accessed in the future | Baseline |
| Preload Engine | Support for software to specify a table of virtual addresses that are prefetched in sequence when triggered by a particular MSR write instruction | Dropped |
| RAS | Reliability Availability Service |  |

RAS support including L1 DC ECC error handling L2C ECC and bus error handling error reporting and logging to RAS system registers.
| SMT RAS | Baseline |  |
| --- | --- | --- |
| MTE | Arm Memory Tagging Extension security feature intended to avoid security holes such as readafteruse and outofbound reference | Dropped |
| TRBE | Separate TRBE buffer per thread move to DTU | Dropped |
| DSB opt | improve performance and speed up the younger load store instructions to pass DSB its necessary to let younger loads and stores access TLB and memory speculatively | Baseline |
| NC load merge | LFB supports 64B NC Load Merge | Baseline |
| STLR opt | Allow the stlr wait in SCA and allow younger WB ST pass STLR | Baseline |
| DCZVA opt | Optimize the small size DCZVA DCZVA need to lookup L1 Tag and write to DC if L1 hit | Baseline |
| Atomic Opt | Atomic executed in Store pipe to optimize wires And support multi outstanding Atomic | Baseline |
| Exclusive Opt | Support PA snoop based exclusive monitor And allow speculative wakeup to optimize the STXR latency | Baseline |
| STQ SFE | new load forward multihit scheme with SFE | Baseline |
| Fast STLF | Solution for late STD generating FastSTLF request to OOO only for INT LD | Baseline |
| RF checker | responsible for XBAR control calculation and sending cancel to LDUs in case of conflict on write port to one of subbanks | Baseline |

Enhanced XP Flow
Speed up XP flow
Baseline
| E4 Cancel | requires new interface check OEX timing | Baseline |
| --- | --- | --- |
| STQ fwd high16B opt | Support stq high 16B forward data only when load repick | Baseline |
| TLB conflict fault opt | Baseline |  |
| SCB NC store merge | Support store NC merge in SCB | Baseline |
| fstd wakeup load add 1-cycle | For IEXFSU std timing | Baseline |

LSU provides the following Arm architecture V8xV9X features.
| Feature | Description | Status |
| --- | --- | --- |
| FEATRME | Baseline |  |
| FEATCMOW | Baseline |  |
| FEATETS2 | Baseline |  |
| FEATXS | Baseline |  |
| FEATLS64 | Baseline |  |
| ICIVAU support | Baseline |  |
| FEATHDBSS fault with OOO | Baseline |  |
| FEATRPRFM Range Prefetch Hint | Baseline |  |
| MPAM support | Baseline |  |
| TLB ramidx | Baseline |  |
| TSO mode | Baseline |  |
| PLI Support | Baseline |  |
| PMUv38 | Baseline |  |
| Simplify FAR report especially for watchpointV9 | Baseline |  |
| SPU report TLB hotcold page | Baseline |  |
| SPU DataSrc | Baseline |  |
| FEATLRCPC3 | Baseline |  |

### 3.2 Instructions

The following table shows the instructions executed by LSU.
| Arm Aarch64 Instructions | Description |
| --- | --- |
| LD ST | All flavors of load/store including WBNCDEV |
| DMB DSB LDAR STLR | Barriers load acquire store release |
| LDNP STNP | load/store nontemporal |
| PRFM | Software prefetch |
| DC IC TLBI AT | System instructions |
| LDXR LDAXR STXR STLXR CLREX | Exclusives |
| LD<OP> ST<OP> SWP CAS CASP | All flavors of V81 atomic instructions |
| SVE Instructions | Description |
| SVE LDST | SVE vector load/store instructions gather load scatter store |

### 3.3 Basic Load Flow

The diagram below shows the basic load execution flow Refer to Error Reference source not found for more details.

#### Figure 3 1 Basic Load Flow

### 3.4 Basic Store Flow

The diagram below shows the basic store execution flow Refer to Error Reference source not found for more details.

#### Figure 3 2 Basic Store Flow

### 3.5 load/store Ordering

This chapter summarizes the ordering requirements that LSU must enforce across load and store instructions The following table provides a summary of load/store ordering requirements based on the program order and memory attributes of each instruction.
| Older Instruction A | Younger Instruction B | Summary of Ordering Requirements |
| --- | --- | --- |
| WB Load | WB Load | If Load A resolves first with older data and becomes stale snoop invalidated and its not the oldest Load Load A must be nuke flushed |
| WB Load | NC Load | No ordering requirements |
| WB Load | DEV Load | Load A must resolve return data before Load B can send its request to L2C in case Load A has a data error that requires it to be flushed |
| WB Load | WB Store | Load A must resolve return data before Store B can become globally ordered |
| WB Load | NC Store | Load A must resolve return data before Store B can send its request to L2C |
| WB Load | DEV Store | Load A must resolve return data before Store B can send its request to L2C |
| NC Load | WB Load | No ordering requirements |
| NC Load | NC Load | Load A request must be RSB allocated before Load B can send its request to L2C |
| NC Load | DEV Load | Load A request must be RSB allocated before Load B can send its request to L2C |
| NC Load | WB Store | Load A must resolve return data before Store B can become globally ordered |
| NC Load | NC Store | Load A must resolve return data before Store B can send its request to L2C |
| NC Load | DEV Store | Load A must resolve return data before Store B can send its request to L2C |
| DEV load | WB Load | No ordering requirements |
| DEV load | NC Load | Load A request must be RSB allocated before Load B can send its request to L2C |
| DEV load | DEV Load | Load A request must be RSB allocated before Load B can send its request to L2C |
| DEV load | WB Store | Load A must be RSB allocated before Store B can become globally ordered |
| DEV load | NC Store | Load A must be RSB allocated before Store B can send its request to L2C |
| DEV load | DEV Store | Load A must be RSB allocated before Store B can send its request to L2C |
| WB Store | WB Load | Two cases when younger WB load B has PA match byte overlap with older WB store A |
| 1 | If store A reaches STQ PA array before load B CAMs STQ PA array load B detects STQ hit and STQ forwards store A data to load B |  |
| 2 | If load B CAMs STQ PA array before store A reaches STQ PA array load B detects STQ miss and may resolve return data Later when store A writes STQ PA array it CAMs LHQ detects LHQ hit on younger resolved load B and nuke flushes load B |  |
| WB Store | NC Load | No ordering requirements |
| WB Store | DEV Load | Store A must AGEN resolve detect faults before Load B can send its request to L2C |
| WB Store | WB Store | Store A must AGEN resolve detect faults before Store B can become globally ordered |
| WB Store | NC Store | Store A must AGEN resolve detect faults before Store B can send its request to L2C |
| WB Store | DEV Store | Store A must AGEN resolve detect faults before Store B can send its request to L2C |
| NC Store | WB Load | No ordering requirements |
| NC Store | NC Load | Store A must be RSB allocated before Load B can send its request to L2C |
| NC Store | DEV Load | Store A must be RSB allocated before Load B can send its request to L2C |
| NC Store | WB Store | Store A must AGEN resolve detect faults before Store B can become globally ordered |
| NC Store | NC Store | Store A must send its request to L2C before Store B can send its request to L2C |
| NC Store | DEV Store | Store A must send its request to L2C before Store B can send its request to L2C |
| Dev Stores | WB Load | No ordering requirements |
| Dev Stores | NC Load | Store A must be RSB allocated before Load B can send its request to L2C |
| Dev Stores | DEV Load | Store A must be RSB allocated before Load B can send its request to L2C |
| Dev Stores | WB Store | Store A must AGEN resolve detect faults before Store B can become globally ordered |
| Dev Stores | NC Store | Store A must send its request to L2C before store B can send its request to L2C |
| Dev Stores | DEV Store | Store A must send its request to L2C before store B can send its request to L2C |

#### 3.5.1 Ordering

The Ordering handling for LDLD STLD ordering for WBNCDev or Barriers Pelase refer to the LSU Ordering Notes.
[internal reference removed]
Additional information on ordering with Mixed attributes please refer to chapter 12 DS chapter.

### 3.6 Structures

The table below provides an overview of LSU data structures Refer to the Error Reference source not found chapter for more details on the number of banks number of entries entry width total size and readwriteCAM ports.
The Partitioning of the following structures to multiple sub structures is further detailed in Chapter 312.
| Structure | Description |
| --- | --- |
| Load DC TAG | The L1 Data Cache DC is a 64 KB with 64B cachelines 4way set associative virtually indexed physically tagged writeback write allocate on write miss LRU replacement policy MESI coherency states inclusive in L2 Cache L2C and ECC protected It is composed of TAG STATE and DATA arrays |

TAG is a Local read port per Load pipe A latch is used as LSU LD TAGCT by default.
| Store DC TAG | Same TAG array as load DC TAG ArraySingle Port SRAM |
| --- | --- |

The ST TAG is accessed.
| 1 | SCB tag read |
| --- | --- |
| 2 | Ramidxmbist |
| Load DC State | LSU has a Load DC STATE indication that tracks the Validity Invalid State state1bit for each cacheline in the L1 Data Cache 1K entry for 64KB L1 |

State is shared by to 3 LDU.
| Store DC State | LSU has a ST DC STATE array that tracks the MESI state2bit for each cacheline in the L1 Data Cache |
| --- | --- |

1K entry for 64KB L1.
| P Pre Fetch Bit Array | It is an extension to State Array to indicate if a CL was Demanded or Pre fetched Updated by loads and stores |
| --- | --- |

P. bit array provides information to hwpf.
Manages and tracks change p bit per CL on demand load/store P1 and provides information on bad prefetch table.
| Data Cache DC Array | LSU has a DC DATA array consisting of 64 physical RAM banks each 128 entries x 8B for a total of 64 KB of storage divided into 8 slices by VA[53] and each slice is divided into 8 banks by VA[86] The memory in each bank is implemented by singleported 1RW memory with ECC protection |
| --- | --- |
| Partial LD Tag | Partial LD TAG in ResA VIVT that generates wayselect and hitmiss indication pTAG results are sent to LDUctrl to verify vs full LD TAG no match brings to cancel 3R from each LDU 1W fill ports Latch based memory |
| Fast WP | LSU has a DC Fast Way Predictor Fast WP to save power by reducing DC DATA RAM accesses |
| Seperating ST and LD TLB | Seperated allocationdeallocation for loads and stores TLB The Load and Store TLBs are of different sizes and commonly hold some different pages |
| 2 Level TLB | uTLB and MTLB to balance power and area |

UTLB 16 entries MTLB 128 entries.
| LHQ Load Hit Queue | LSU has a 120-entry Load Hit Queue LHQ split into three Local LHQs per load pipe LHQ tracks loads from issue to retire and very Load and Store hazards LDLD Nuke STLD Nuke which need to be flushed due to ordering violations |
| --- | --- |
| LIQ Load Inflight Queue | LSU has a 48-entry Load Inflight Queue LIQ split into three Local LIQs per load pipe |

LIQ tracks load uops that could not yet Resolve return data The uOp in LIQ waits for data to be ready before it Wakeups and Repicked to get the load result.
Each LIQ entry records the wait condition and wakeup tag Once condition met the load uop is eligible to be repicked.
LIQ holds VA for Reissue reaccess TLB and vSTQ.
LIQ holds PA for Repick.
| MDB Memory Disambiguation Buffer | LSU has a 64-entry Memory Disambiguation Buffer MDB MDB is a fully associative structure CAM by hashed load PC[120] Shared by 2 threads which tracks loads that resolved returned data ahead of older stores to same address and had to be flushed Each entry corresponds to one load that had to be flushed and contains pointers to up to 3 older storesdeltas that caused the flush |
| --- | --- |
| BasicB Load Data Buffer | LSU has a 4-entry BasicB shared by 2 threads which serves as a load data return buffer for NCDEV loads 2 extra entry for the oldest WB load forward progress guarantee and misalign NCDEV load 1 entry for 1 thread |
| FDB Fill Pipe | LSU has a 4stage fill pipe C7 C10 stages that carries the fill PA way and data return from the L2C interface to the DC write |

Supports LDForwarding for load hit on pipe.
| Load vSTQ Virtual STQ for Load pipes | Pre load pipe a virtual STQ is replicated |
| --- | --- |

Each load pipe has a 40-entry Store Queue STQ shared by 2 threads which holds the address and data for each store uop until it is retired to SCB The STQ also forwards store data to younger loads with matching VA addresses.
STD is shared between all Load pipes and Store retire STQ.
| Store Retired STQ | LSU store retire pipe has a 40-entry Store Queue STQ 5050 partitioned by 2 threads which holds the address and data for each store uop until it is retired to SCB |
| --- | --- |

This STQ is PA accessed.
Pre Retired STQ
The preretire stq is the STQ status machine that controls the tlb miss stores and XP stores.
Includes a FSM to control Status It needs to handle the tlb miss and wakeup generate reissue.
The STQ status logic is not copied in Load vSTQ and PA STQ retire.
| SCB Store Coalescing Buffer | LSU has a 14-entry Store Coalescing Buffer SCB shared by 2 threads which merges the data from stores of the samecacheline before writing to the L1 Data Cache DC |
| --- | --- |
| LFB Line Fill Buffer | LSU has a 16-entry Line Fill Buffer shared by 2 threads which merges the Loads L2 Requests to L2C |
| TRBE Buffer | The LSU supports the TRBE debug feature by providing TRBE WR Data to L2C This is handled by the TRBE Buffer |
| Skid Buffer I2 | LSU has a I2 Skid buffer to allow Loads which are blocked due to I2 pipe stage arbitration to regain possession of E1 in following 3 cycles |
| HWP Hardware Prefetcher | LSU send the tlbdata trainingtrigger package to HWP and collecting the adaptive information control the L1 prefetchs degree |
| Watchpoint Registers | LSU has a set of software programmable watchpoint registers to detect watchpoint faults |

### 3.7 Arbiters

The table below provides an overview of the primary arbiters in LSU.
| Arbiter | Description |
| --- | --- |

LD TLB Arbiter in LD0 pipe.
When LSU needs to use the TLB ports for IssueRepick loads and in addition for purpose such as TLBI crosspage load/store translation or hardware prefetcher translation This is called a TLB sidedoor access.
The sidedoor access has highest priority and the LSU informs IEX to reject and reissue the load/store instruction that conflicted with the sidedoor access.
Priority TLBI> reissue> Issue>TLB prefetch.
For TLBI or TLB prefetch it only need 1 copy for Loads The LD TLB Arbiter is allocated only on load pipe 0.
ST TLB Arbiter in ST0 pipe.
By default the ST TLB port is used by Store and Atomic instructions from the IEX LS issue queues.
The ST TLB also supports sidedoor access TLBI crosspage load/store translation or hardware prefetcher translation.
The sidedoor access has highest priority and LSU informs IEX to reject and reissue the load/store instruction that conflicted with the sidedoor access.
Priority TLBI> reissue> Issue>TLB prefetch.
For TLBI or TLB prefetch it only need 1 copy for stores The ST TLB Arbiter is allocated only on store pipe 0.
Load TAG Arbiter
Per load pipe there is a Local TAG read port and Local TAG Arbier The latch is used as load tag by default and no conflict need to handle in this situation.
When sram tag is usedFill from L2C has highest priority to access the DC TAG arrayslatch array by default no read and write confliction for LD TAG.
A LIQ Repicked loads have 2nd highest priority followed by the issued loads including reissue issue from iex and issue from skid buffer.
Priority Fill TAG WR>>Repick>Issue.
| Store TAG Arbiter | Retirement of Store from STQ to SCB is accompanied with ST TAG access |
| --- | --- |

The ST TAG is accessed on the Store Retirement Pipe.
ST Tag Arbiter has following priority.
Priority Ramidx > scb rd.
Note Ramdix is only used in debug mode Rare operation Set highest priority to simplify the ramidx control logic.
DC DATA Bank Arbiter.
The DC DATA array is composed of singleport banks 1RW SRAM.
Overall the DC is a 4 Port RDWR component Up to 4 accesses per cycle Up to 96MB RD BW.
Fills from L2C that write DC DATA and copyback snoops that read DC DATA which are 64B transactions have highest priority to access each DC DATA bank.
Loads selected by the Result pipe arbiter which are up to 32B transactions have next highest priority repick>issue and random priority arbitration in other situations between pipe0 pipe1 pipe2.
Stores from SCB have lowest priority to read and write DC DATA After reaching a starvation threshold SCB stores can temporarily take priority over loads to access DC DATA banks.
Priority WR ports Fill 64B SCB WR 232B.
Priority Rd port ldu0 132B ldu1 132B snpscbrdldu2 164B.
Note load pipe 2 is shared with snpc read port.
Note only snpc dirty need to access the DC read port.
Load Result Pipe Arbiter.
Per load pipe there is a Local Result pipe arbiter.
Each load result pipe pipe0 pipe1 pipe2 has an arbiter that selects between loads from IEX issue queues Skid I2 and LIQ ReissueRepick.
Priority RepickReissue > Issue > SBI2.
| Load L2C Request Arbiter | RSB is split to 2 Arrays based on PA[6] Arbitration per PA[6] Array |
| --- | --- |

RSB and SCB arbitration for the Request port Priority per arbiter.
Priority SCB > LFB > issue pipe miss request.
In LFB Arbitrate over the two L2C request ports out of the requests being generated by 3 load pipes LFB merges the requests for the normal loads to same cache line.
SCB merges request of the normal stores to the same cache lines.

### 3.8 Pipelines

LSU has the following pipelines related to load instruction execution Refer to Load Pipelines and Datapaths for more details.
| Load Pipeline | Description |
| --- | --- |
| Load IEX Issue Pipeline | Load instruction issued from IEX LS issue queues to LSU Wakeup P I1 I2 E1E4 |
| Load LIQ Reissue Pipeline | LIQ reissue of load uop that did not yet obtain its PA from TLB Wakeup P I1 I2 E1E4 |
| Load LIQ Repick Pipeline | LIQ repick of load uop that already obtained its PA from TLB Wakeup P I2 E1E4 |
| Load Result Pipeline | Result pipe load returning data to IEXFSU and sending resolve to ROB |
| Load Cancel Pipeline | Result pipe load that cannot return data sends cancel to IEXFSU |
| StoreLoad Nuke Pipeline | Detection of load ordering violation between younger load and older store |
| LoadLoad Nuke Pipeline | Detection of load ordering violation between younger load and older load |
| DC WR Bus | L2 fill vld is start at C5 DC Data WR is at C9 pipe DC tag WR is C8 pipe |

LSU has the following pipelines related to store instruction execution Refer to Store Pipelines and Datapaths for more details.
| Store Pipeline | Description |
| --- | --- |
| STA IEX Issue Pipeline | Storeaddress STA instruction issued from IEX LS issue queues to LSU |
| STA STQ Reissue Pipeline | STQ reissue of storeaddress STA uop that did not yet obtain its PA from TLB |
| STA STQ Repick Pipeline | STQ repick of storeaddress STA uop that already obtained its PA from TLB |
| STD IEX Issue Pipeline | Storedata STD uop issued from IEX ALU issue queues to LSU |
| STD FSU Issue Pipeline | Storedata STD uop issued from FSU issue queue to LSU |

STD IEXFSU issue pipe is merged at I2 Only 1 pipe in lsu.
| STQ Retire Pipeline | STA and STD uops retired from STQ to SCB |
| --- | --- |
| Store DC TAG Pipeline | SCB store DC TAG check |
| Store DC DATA Read Pipeline | SCB store reading from DC DATA array |
| Store DC DATA Write Pipeline | SCB store writing to DC DATA array |

LSU has the following pipelines related to the L2C interface Refer to L2C Pipelines and Datapaths for more details.
| L2C Pipeline | Description |
| --- | --- |
| REQ Pipeline | LSU request interface to L2C and response from L2C to LSU |
| Fill Pipeline | Fill data return from L2C to LSU |
| 10-cycle L1MissL2Hit Pipeline | Best case scenario for L1missL2hit fill data return |
| CHL Fill Pipeline | Critical half line CHL fill data return from L2C to LSU |
| Snoop Pipeline | L2C snoop of L1 Data Cache DC and copyback data return from LSU to L2C |
| NCDEVNTFAR Store Pipeline | SCB write request and data to L2C |

LSU has the following pipelines related to the interface with MMU Refer to MMU Pipelines and Datapaths for more details.
| MMU Pipeline | Description |
| --- | --- |
| TLB Miss Request Pipeline | TLB miss request to MMU |
| Load TLB Fill Pipeline | TLB fill return from MMU to LSU |

### 3.9 Interfaces

The diagram below provides an overview of the LSU interfaces with the rest of the core Each arrow represents a group of interface signals for a particular function.

#### Figure 3 3 LSU CoreLevel Interfaces

The following interfaces represent key interface between LSU and its adjacent boxes This information is generally accurate and not exhaustive Refer to the Reference Document 15 target implementation Interface excel for more details on interface signal names and behaviors.
LSU has the following interfaces with IEX Refer to Error Reference source not found for more details.
| IEX Interface Group | Description |
| --- | --- |
| LDAISSUE | IEX can issue three load uOps to LSU each cycle lda0lda1lda2 pipes Each issue interface includes the op type attributes and source data required by LSU to generate the address and execute the instruction |
| STAISSUE | IEX can issue two storeaddress instructions to LSU each cycle sta0sta1 pipes Each issue interface includes the op type attributes and source data required by LSU to generate the address and execute the instruction |
| STDISSUE | IEX can issue two 16B store data values istd0istd2 pipes and two 8B integer store data values istd1istd3 pipes to LSU each cycle independent of the sta0sta1 pipes |
| LDRESULT | LSU has three 32B load result pipes pipeapipeb |

Each result pipe has two 64Bbit write ports on the integer regfile 16B loadpair uses both write ports remaining loads use one write port When LSU is ready to provide the data for a load it sends this load result to IEX at E4 stage.
| LDCANCEL | When a load flowing through the load pipe issued from IEX or repicked from LIQ cannot yet provide data LSU cancels the load to IEX IEX cancel the load dependents |
| --- | --- |

Cancelled dependent uOps return to the issue queue to reissue later The Canclled load is written to LIQ to be reissuedrepicked later.
| LDREPICKWAKEUP | When LSU eventually has data ready for a load that it previously canceled it repicks the load from LIQ and informs IEX to wakeup the dependents of that load |
| --- | --- |
| TLBSIDEDOOR | When LSU needs to use the TLB for an operation other than the LD and ST address translations it informs IEX of this sidedoor access The IEX must locally reject the uop that would conflict with the sidedoor access and reissue it again later |
| BRUFLUSH | On a BRU flush LSU must invalidate the affected uops from its internal structures |

Note Rob flush is nuke flush exception and flush all Bru flush is mispred branch and flush younger inst.
LSU has the following interfaces with OOO Refer to Error Reference source not found for more details.
| OOO Interface Group | Description |
| --- | --- |
| LDRESOLVE | LSU must send a uOp level resolve indication to the ROB for each load uop |

The resolve interface specifies whether exceptions were detected and when data has been written to the physical regfile.
In some case such as with TLB fault LSU need to send cancel for security and resolved with fault.
| AGENRESOLVE | LSU must send a resolve indication to the ROB for each storeaddress translation and DEV load address translation The resolve interface specifies whether exceptions were detected |
| --- | --- |
| STDRESOLVE | LSU must send a resolve indication to the ROB for each integer or FP storedata issue |

Notes The std resolve is generated.
| NUKE | When LSU detects a load ordering violation it must inform ROB to nuke flush the load |
| --- | --- |
| ROBNOFLUSH | ROB informs LSU of the window of oldest instructions which can no longer trigger a flush load/store uops which sent a resolve with no exception to ROB are eligible for this noflush window |

NCDEV loads within this window are allowed to send out their requests to L2C and stores within this window are allowed to retire.
| ROBCOMMIT | As ROB commits resolved instructions deallocates them from ROB it sends LSU an indication of the number of committed load/store uops and the next RID ROB ID to be committed |
| --- | --- |
| ROBFLUSH | ROB can generate a flush for exceptions nuked loads and other conditions On a flush LSU must invalidate the affected uops from its internal structures |
| SYSREG | When executing uops LSU relies on the current values of various system registers as provided by OOO |

LSU has the following interfaces with FSU Refer to Error Reference source not found for more details.
| FSU Interface Group | Description |
| --- | --- |
| STDISSUE | FSU can issue two 256-bit store data values fstd0fstd1 pipes to LSU each cycle independent of the sta0sta1 pipes |
| LDRESULT | LSU has three 256-bit load result pipes pipe0pipe1pipe2 Each result pipe has a 256-bit write port on the FP regfile When LSU is ready to provide the data for a load it sends this load result to FSU at E4 stage |
| LDCANCEL | When a load flowing through the result pipe issued from IEX or repicked from LIQ cannot yet provide data LSU cancels the load FSU must cancel the load dependents and keep them in the issue queue to reissue later |
| LDREPICK | When LSU eventually has data ready for a load that it previously canceled it repicks the load from LIQ and informs IEX to wakeup the dependents of that load |

LSU has the following interfaces with MMU Refer to Error Reference source not found for more details.
| MMU Interface Group | Description |
| --- | --- |
| TLBREQ | When the LSU TLB does not contain a translation for a particular address it sends a TLB fill request to MMU to obtain the translation |
| TLBFILL | Once MMU has retrieved the translation for a TLB fill request it sends this back to the TLB |
| Separate load and store TLBs | The DTLB is seprate by Load and Store However the TLBREQFILL is combined Only 1 requestfill interface with MMU |

LSU has the following interfaces with L2C Refer to Error Reference source not found for more details.
| L2C Interface Group | Description |
| --- | --- |
| L1LRU | The L1 Data Cache DC LRU array is located in L2C so that L2C can select the replacement way at fill time instead of request time This requires that LSU informs L2C of load/store DC hit events that need to upgrade the LRU value |
| REQ | When a load uop accesses WB memory and the DC is a miss DC does not contain the desired address LSU sends a fill request to L2C for this address |

LSU also sends requests to L2C for NCDEV load uops store uops barriers and system instructions To reduce latency LSU sends speculative fill requests to L2C based on the E3s tag hit.
If it turns out the way predictor was a false miss and the E2 stage DC tag lookup is a hit then LSU tells L2C to cancel the speculative fill request.
| L2 request ready | L2C sends 2 kinds of ready signals datless ready and data less The datless ready is used by the L1 miss request without data such as RDSRDM the data ready is used by miss request with data such as WRNT NCDEV ST DVM etc |
| --- | --- |
| FILL | L2C sends fill data to LSU on a 64B Fill interface The 64B DC fills are sent in Fill WR to DC in single cycle |

NC is also changed to 64B according to L2 Fill.
| STUPG | L2C sends an ACKE to LSU when a STUPG request is processed |
| --- | --- |
| SNPREQ | L2C sends a snoop request to LSU when it needs to evict a DC line replaced by an LSU fill request L2C also sends a snoop request to LSU when an external request needs to update DC state or copyback DC data |

LSU invalidates a DC line on an invalidating snoop and updates the DC state and provides DC data to L2C on a copyback snoop.
| SNPRESPONSE | On copyback snoop requests LSU informs L2C of whether the requested cacheline is clean E state or dirty M state LSU sends snoop copyback dirty data to L2C on a 64B interface 64B DC copybacks are sent as only 1 64B beat cycle NCDEVNTFAR store requests send data to L2C on the same interface as a single 64B beat |
| --- | --- |
| SPECIAL | When LSU sends a NT store or CMO request L2C informs when DC has been snooped if necessary When LSU sends a barrier request L2C informs when it has finished processing the barrier When LSU sends a STUPGExclusive or NCDEV storeexclusive request L2C informs the passfail result of the global exclusive monitor |
| RAS | LSU reports various error conditions to L2C |
| GLOBAL | L2C sends the core clockenable to LSU |

#### 3.1.0 Load Pipes

#### Figure 3 4 Load Pipe

Note In design the STQ to Load fwd is separated from DCSCB The STQ directly use a REG2REG aligner And then Muxed with the memory format data DCFDBSCB.

#### 3.1.1 Store Pipe

#### Figure 3 5 Store Pipe

Store Pipe Notes
| 1 | When FSU ST and INT Store write to STQ Priority Mux at the E1 selects which 2 ST access FSTD has higher priority than ISTD The prearb in I2 stage If the ISTD pipe meet the FSTD vld the ISTD pipe need to be cancelled Cancel is done by IEX |
| --- | --- |
| 2 | The STQ write at E3 stage due to the RC The STQ ltag write at E2 |
| 3 | To improve the store retire pipe timing move the STQ retire condition to R3 stagewas R4 stage in 910 and keep scb retire condition at R4 stage |

#### 3.1.2 uOP ID

Before describing the LSU execution flows it is necessary to explain the difference between instructions and uops The LSU in general is only interested in uOps multi uOp resolved is handled by OOO Some exceptions to rule are open item.
The term instruction refers to the parent Arm instruction.
Instructions are expanded into one or more uops for execution by Decoder.
A load/store uop can access up to 32B of data Generally the uop expansion is according to the total size of load/store instruction and splitted by 32B SVE gatherscatter is an exception they are splitted by elements.
Store instructions are split to separate storeaddress STA uOp and storedata STD uOp components which are independently issued in OOOIssQ.
LoadPair is broken at Decoder to 2 uOp single dst per uOp but are dispatched to same IssQ together.

- They are only halfbroken during decode to simplify the rename and ptag allocation logic and merged towards ISSQ dispatch At ISSQ only one uop is issued to LSU and it has two dst ptags so towards LSU theres no change from 910 The latencies are not changed in any way.

The LSU relies on OOO to attach IDs to each instruction at inorder decode time These IDs are then passed through IEX and FSU issue queues to LSU The following table specifies the required IDs For the purpose of ID assignment software prefetch PRFM instructions are considered loads and all remaining nonloadstore LSU instructions are considered stores.
| Instruction | ID | Width | Description |
| --- | --- | --- | --- |
| ALL TYPES | TID | 1b | Thread ID |
| RID | 9b | ROB ID Perthread sequential program order instructiongranularity ID across all instructions |  |
| UID | 3b | Uop ID inside one instruction |  |
| LOAD | LID | 10b | Load ID Perthread sequential program order uopgranularity ID across load uops Its used to preallocate the LHQ |
| YOST | 10b | SID of youngest of older stores SID of previous store uop in program order Used to keep the ordering between load/store |  |
| STORE | SID | 10b | Store ID Perthread sequential program order uopgranularity ID across store uops Its used to preallocate the STQ |
| YOLD | 10b | LID of the youngest of older loads LID of previous load uop in program order Used to keep the ordering between load/store |  |

The following table shows an example program order instruction stream and the IDs attached to each instruction and uop The LIDYOLD is in the same mechanism.
| RID | Instruction | Uop | YOST | SID |
| --- | --- | --- | --- | --- |

### 5.1 STR uop0 25

### 5.2 STR uop0 26

### 5.3 LDR uop0 26

### 5.4 ST4 uop0 27

| uop1 | 28 |
| --- | --- |
| uop2 | 29 |
| uop3 | 30 |

### 5.5 LDR uop0 30

### 5.6 ADD uop0

### 5.7 LDR uop0 30

### 5.8 STR uop0 31

### 5.9 LD4 uop0 31

| uop1 | 31 |
| --- | --- |
| uop2 | 31 |
| uop3 | 31 |

#### 3.1.3 Partitioning the LSU Structures

Applying RePartitioning Concepts to LSU Structures Partitioning to different RTL Hierarchies.
Separate CTRL and Data.
Split the LSU Structures to new RTL hierarchies.
Place CTRL where its needed.
Replicate CTRL If needed in several places.
Minimize Wires between RTL hierarchies.
Floorplan Oriented RTL Hierarchies.
New TOP RTL Hierarchies.
LoadC Load Control Pipe RTL Name lsuldu.
LoadS Shared Control RTL Name lsuldctrl.
DataBlock Data blocks and Result Pipes RTL Name lsudp.
StoreC Store Pipe Control RTL Name lsustagen.
StoreR Store Pipes Retired and pre Retired RTL Name lsustretire.
LoadC is instantiated 3 times 3 LDUs StoreC is shared structure Both Store pipes share single ST TLB Shared a sttag read port It has 2 st pipe under the same lsustagen.
Term Local Refers to a structure which in 910 are shared by multiple clients eg TLB Following Repartition becomes private and local and are only accessedservices the associate LoadC load pipeissQ.
Example LocalTLB LocalLHQ.
Local CTRL Structures in LoadC Replicating the CTRL Blocks.
The table below provides an overview of the Partitioning of the primary Structures in LSU.
| Structure | Partition |
| --- | --- |
| Load DC TAG | TAG Memory use 3R1W latch array each LoadC has separated read port |
| Store DC TAG | ST TAG is unchanged In Store Retire pipe StoreR |
| Load DC State | Together with the Load TAG it implement a LoadState Valid array and shared by all LDUs |
| Store DC State | For stores State 910 less ports is shared between StoreC pipes Accessed same as today |
| Data Cache DC Array | Shared by all load pipes In Data Block |
| Fast WP | Replicated per LoadC |
| Partial LD Tag | Shared by all load pipes |
| Seperating ST and LD TLB | Seperated allocationdeallocation for loads and stores TLB The Load and Store TLBs are of different sizes and commonly hold some different pages |
| 2 Level TLB | uTLB and MTLB to balance power and area |

UTLB 16 entries MTLB 128 entries.
| LHQ Load Hit Queue | Split to LocalLHQ per LoadC and all Locals report to LHQCTRL in LoadS |
| --- | --- |
| LIQ Load Inflight Queue | Split to LocalLIQ per LoadC and all Locals use some shared and slow resources in LIQCTRL in LoadS |
| MDB Memory Disambiguation Buffer | Shared between load pipes In LoadS hierarchy |
| BasicB Load Data Buffer | Not changed and is shared between load pipes In LoadS and Data Block hierarchies |
| FDB Fill Pipe | Split to LocalFDB ctrl for the tag match the data path is shared between load pipes |
| STQ Store Queue | The 910 STQ has 5 Shared Arrays PA BM Data Attributes Status |

In 920 the STQ is constructed and replicated as follows.
Local Load vSTQ STQ Local TAG is replicated per LoadC It includes all STQ TAG bits that are required to perform STRFWD and STQBlocking.
A Store PA STQTAG is a 2nd replication of the STQ TAG to StoreR Used to RD retired stores In addition to the STQ TAG task it also includes Preretirement PR Status Array.
The Data Attributes BM are allocated at Data Block or StoreR close to both.
Retirement Status The Status is divided to Pre retired StoreC and retired part StoreR Replicating information both components.

- StatusC
- StatusR

| SCB Store Coalescing Buffer | In 910 SCB is a 2 Array Address Array SCA and Data Array SCD |

In 920 the SCB TAG is replicated per LoadC and in each LDU.
In 920 SCB is replicated partitioned as follows.
SCA TAG per LoadC SCA TAG is replication of some of the SCA.

- CAM Match
- Forward able

SCA in Storeretired pipe.
SCD in Storeretired pipe.
The SCA and SCD are not changed in functionality and payload.
| LFB Line Fill Buffer | LFB CAM is Replicated per LoadC Single port |
| --- | --- |

LFB Arbiter which selects L2 Request priority is implemented in LoadS.
| Skid Buffer I2 | Replicated per LoadC Single port |
| --- | --- |
| HWPF | Replicated per LoadC |
| Watchpoint Registers | Replicated per LoadC |

#### 3.1.4 Block Diagram

The following figure depicts the LSU structures and RTL Hierarchies.

#### Figure 3 6 LSU Block Diagram

| 4 | LSU Structures |
| --- | --- |

This chapter summarizes the key Tasks and Design concepts of the LSU structures and building blocks.
Each chapter also refers to uArch document.
