# Vector-OOO Appendix for BCC Block Dispatch

> **Document ID**: JCORE-BCC-AS-APP-VEC-OOO
> **Version**: v0.1
> **Date**: 2026-05-14
> **Status**: Draft
> **Parent**: [JCore_BCC_AS.md](JCore_BCC_AS.md)

---

## Change Log

| Version | Date | Changes |
|---------|------|---------|
| v0.1 | 2026-05-14 | Captured Vector Decode/Rename/ROB notes relevant to BCC block dispatch and Uniform parameters |

---

## 1. Vector Decode

### 1.1 D1 Stage

D1:

- 按线程接收 IFU IBCT_BUF 输出微指令，最多 4 inst/cycle。
- 执行指令预解码与 uop 拆分预处理。
- 考虑 ISQ 写口和一拍可分配寄存器资源限制，不能取出超过两条 load 指令，不能超过两条 VALU 指令。

取指约束:

- BR_setc 每拍只能出两条。
- Scalar 指令每拍只能出两条，vector load/store 也算 scalar 指令。
- Store 指令每拍只能出一条。
- VALU 指令每拍只能出两条。

### 1.2 D2 Stage

D2:

- 不感知指令来自 IB 还是 CT。
- 不显式感知 BID 连续性。
- D2_MUX_THREAD 对四线程 RR 调度。
- Decode Unit 产生必要控制和数据信息并输出至 Rename。
- 接收 PE 后端整体资源反压和线程级反压。

Core 级反压:

| Resource | Stall Rule |
|----------|------------|
| rf_ptag | free PRF < 4 时 stall |
| vrf_ptag | ptag_fifo credit < 4 时 stall |
| pair_ptag_fifo | 当前 4 条含 FP64 时，pair_ptag_fifo credit < 4 时 stall |
| iq_entry | IQ 槽位不足时 stall |

线程级流控:

- 某线程 ROB 不够时，该线程不参与 RR。
- 某线程 MAPQ 不够时，该线程不参与 RR。
- 某线程 flush 处理时，该线程不参与 RR。

Block Dispatch 约束:

- 为避免 FP64 使用双倍寄存器导致死锁，在 Block Dispatch 时需保证只有两个执行流 ROB 可接受 FP64 指令。
- 例如只将 FP64 group 分在执行流 0 和 1。

---

## 2. Vector Rename

### 2.1 Register Classes

Vector 中寄存器分三类:

1. VRF: 使用相对索引，宽 2048 bit。
2. RF: 使用相对索引，宽 64 bit。
3. Uniform Register: 存储来自 BCC 的 GPR 输入，与 RF 共用物理寄存器。

Rename 将输入/输出寄存器替换为物理寄存器:

- `vt/vu/vm/vn` 对应 VRF。
- `t/u` 或 `arg` 寄存器对应 PRF。

ROB 在 D3 分配 RID。D3 可将指令拆分为多个 uop，输出 uop 类型、RID、src_ptag、dst_ptag、BID 等。

FP64 指令:

- 分配两个连续 ptag。
- 占用一个 IQ entry。
- 出队时占用两个 picker 口。
- 需要管理普通 freelist 和 pair freelist。

### 2.2 Supported Features

- Reuse hint: 源寄存器不会再使用时，可提前释放物理资源。
- Clock Hands: 不同 hand 表征不同释放速率。
- 寄存器资源共享: 不同 hand、不同执行流共享物理寄存器资源。
- 低精度计算合并，FP64 计算拆分。
- 重命名消除。
- Uniform 寄存器映射管理。

CA 遗留:

- 暂不实现小粒度计算 group 合并。
- 暂不实现 64bit 计算 pipe 合并，但模拟寄存器占用。

---

## 3. VRF Rename Structures

### 3.1 SCLK / SMAP

SCLK 记录当前最新相对索引寄存器到 ptag 的映射关系。

结构:

- 4 线程 private。
- `(4+4+4+4)*4 = 64 entries`。
- 每个线程每个 hand 类似 FIFO。
- 一拍可为 4 条指令 rename。

表项:

- 每个 hand 维护 allocate ptr。
- `allocate ptr - 1/2/3/4` 分别存储 `vt#1/#2/#3/#4` 的 ptag。
- 每 entry 包含 `vld` 和 `pair`。
- `pair` 表示 FP64 输出，占两个 2048bit 寄存器。

端口:

- 读 SCLK: 12 个读口，4 inst x 3 src。
- 写 SCLK: 4 个写口，4 inst x 1 dst。
- ClockHands 无需处理同拍 WAW，但需处理同拍 RAW。

### 3.2 MAPQ

MAPQ 暂时线程 partition。

功能:

- 记录所有投机态寄存器映射关系。
- 指令从 ROB commit 后，MAPQ 中相应映射关系出队并写入 CCLK。
- flush 时，所有比 flush rid 年轻的映射刷掉，对应 ptag 回收到 freelist。
- 剩余映射关系用于恢复 SCLK 到正确投机状态。

### 3.3 CCLK / CMAP

CCLK 记录已经提交的寄存器 Clock 状态。

结构:

- 4 线程 private。
- `(4+4+4+4)*4 = 64 entries`。
- 每线程每 hand 类似 FIFO。

Commit:

- n 个写口，一拍提交 n 条指令。
- 将新映射写入 CCLK。
- 将旧物理寄存器释放回 freelist。

### 3.4 PTAG Manage

Freelist 以 bitmap 表征物理寄存器占用状态。

FP64:

- 希望分配连续 2 个物理寄存器。
- freelist 中每两个 bit 相与得到 pair_freelist。

每拍:

- Manage Unit 从 free list 挑选 4 个 ptag 放入 ptag_fifo，depth 16。
- 或从 pair_freelist 挑选 4 组 `2*2048` ptag 放入 pair_ptag_fifo，depth 16。

仲裁策略:

- 初始 RR，在 ptag_fifo 和 pair_ptag_fifo 之间轮询。
- 某个 fifo 满会阻止轮询选择。
- 当流水因 ptag 不足 stall 且当前指令不含 FP64 时，将 pair_ptag_fifo 中所有 ptag 释放，策略切为仅填 ptag_fifo。
- 当流水因 pair_ptag_fifo 不足 stall 时，策略改为轮询。

时序长路径:

```text
tid/hand -> select one of 16 hand pointers -> compute address
address = tid * 16 + hand * 4 + hand_ptr - offset
```

关键路径包含:

- 16 选 4 mux，2bit。
- 64 读 4。

---

## 4. Scalar and Uniform Rename

Scalar 寄存器 rename 与 Vector 类似，但无需 FP64 pair。

Uniform Register:

- 存储来自 BCC 的寄存器输入。
- 与 RF 共用物理资源。
- 每个存活 block 有一套 Uniform register。
- Uniform 共 16 个。
- 若 Vector block 可接 8 个 block，则 Uniform 表项约 `8*16=128`。
- scalar 寄存器共 32 个。
- 表项总数约 160。

Reduce 到 RO 寄存器时，指令包含隐藏输入:

```text
Reduce.add (RO1) vt#1 -> RO1
```

RO1 的 src 输入是隐含的。

RO rename:

- RO 的 smap 表项中有 `first_aloc` 指示。
- block 结束时，表项 vld 全清 0。
- 某指令写 RO 时:
  - 如果映射表项 `vld = 0`，置 `vld = 1` 且 `first_aloc = 1`。
  - 如果映射表项 `vld = 1`，置 `first_aloc = 0`。
- first_aloc 影响 reduce 逻辑和 rename 依赖建立。

---

## 5. Predicate Rename

Predicate 使用特殊 tag 表示全 1，例如 tag0 表示值全 1。

Src rename:

- src_tag 可能来自重命名表项。
- 也可能来自初始全 1。
- 查询表项同时比对指令 BID/grp_id 与重命名部件中 BID/grp_id。
- 如果相同，选择表项 tag。
- 如果 vld=0 或 BID/grp_id 比对失败，predicate 为全 1，tag=0。
- BID/grp_id 比对失败时，将 vld 置 0。

Dst rename:

- 分配重命名表项。
- 将指令自己的 BID/grp_id 写入重命名部件。
- vld 置 1。

Commit state:

- 维护 commit_vld、commit_BID、grp_id。
- 普通指令 commit 时，比对 commit BID/grp_id，不相等则 commit_vld 清 0。
- 更新 predicate 的指令 commit 时，commit_vld 置 1，并更新 commit BID/grp_id。

Flush:

1. 将 vld/BID/grp_id 恢复为 commit 点状态。
2. Commit 点到 flush 点之间，将最老 group 的 BID/grp_id 更新进重命名表项。
3. 如果其中有更新 predicate 的指令，则 vld 置 1。
4. 重命名表项指针做相应恢复。

---

## 6. Reuse Hint

Reuse hint 标记某个源寄存器在该指令之后是否还会被重复使用。

示例:

```text
vadd vt#1(reuse), vt#2 -> vt
```

含义:

- `vt#1` 后续还会被使用。
- `vt#2` 未给 reuse 标记，表示此条指令后不会再被索引，硬件可提前释放。

硬件使用:

- ROB 增加 src ptag 域段。
- 指令 commit 时，如果可确定 src ptag 不再使用，则提前释放。
- 如果块内不存在跳转且按 group 粒度做 flush/异常恢复，释放点可提前到 resolve。

收益评估:

- 既有无线业务负载中约 80%-90% 寄存器使用一次后不再使用。
- cmap 中 80%-90% 指令可不保存。
- 平均每个 hand 残留一个寄存器。
- 架构状态可从 `t/u/m/n * 4 ROB * 4 = 64` 降至 `t/u/m/n * 4 ROB * 1 = 16`。
- 节省约 `48 * 2048bit` 寄存器。

---

## 7. Vector Dispatch

Vector Dispatch:

- 维护 IQ entry credit，完成反压。
- 根据 uop 类型分发至对应 IQ。
- 例化 PRST，维护各 ptag ready、dpd、timer 等 src ptag 相关信息。
- 具体接口需与下游对齐后完善。

---

## 8. Vector ROB

ROB 功能:

- 记录指令真实顺序。
- 指令乱序执行，按 ROB 顺序提交。
- 正确处理异常和中断。
- 寄存器资源释放依赖顺序 commit。

Clock Hands 资源释放:

- 硬件通过 dst 寄存器写入信息判断旧寄存器是否不再使用。
- 不依赖完整生产者-消费者关系释放，以避免循环/跳转/函数调用带来的复杂性。
- Reuse hint 作为编译器辅助。

### 8.1 ROB Group

一个 group 最多 4 条指令。

会启动新 group 的情况:

- load/store。
- 前一 group 已有 4 条指令。
- 条件跳转指令。
- 执行前已检测异常的指令，如未定义指令。
- 后续指令未到来，前一个指针已经 commit，需要新起一个。
- load/store pipeline 中处理的系统指令，如 DSB/DC/IC。

示例:

```text
ADD -> rob_id 0
LD  -> rob_id 1
ADD -> rob_id 1
BEQ -> rob_id 1
ADD -> rob_id 2
SUB -> rob_id 2
MOV -> rob_id 2
MUL -> rob_id 2
ADD -> rob_id 3
```

中断只能在 group 边界处进行。

D1 修改:

- group_start 由自身属性和前一条指令共同决定。
- group_start 生成是时序瓶颈，需占据完整一拍。

ROB 修改:

- rob_id 按 group 累加。
- 一拍最多分配 4 个 rid，例如 4 个 load。
- group commit 需要 rslv_cnt 归零。

Commit 条件:

- ROB group 内 resolve counter = 0。
- RID 为最老。
- group 内不存在异常。
- 不存在 nuke_flush。
- flush 发生时先响应 flush。

### 8.2 Non-Flush Pointer

non-flush 标志:

- 异常指令将 non-flush 置 0。
- load/store 将 non-flush 置 0，等待完整 resolve 后置 1。
- branch 类块内跳转指令将 non-flush 置 0，等待 resolve 后置 1。

non-flush ptr 可用于:

- load 指令提前发出。
- load 从 LHQ 出队。
- kill-hint 作用于 instruction non-flush。

### 8.3 ROB Resource Allocation

D3 完成 RID 资源分配，对 load/store 分配 LD_ID/ST_ID。

ROB:

- 128 entries。
- Partition 结构。
- SMT2: 每线程 64。
- SMT4: 每线程 32。
- RID 共 8 bit，最高 bit 为卷绕位。
- RID 从对应线程 base_idx 开始计数，跨 block 不清零，遇 end_idx 卷绕。

micro ROB:

- 与 CPU 基本一致。
- 需要记录 group id。
- commit 时将信息传给 GroupROB。

---

## 9. Notes for BCC Integration

- BCC B.IOR 参数传入 Vector Uniform Register，与 RF 共享物理资源。
- BCC 的 block BID/grp_id 需要与 Vector predicate/Uniform/ROB 状态管理对齐。
- BCC Block Dispatch 中 FP64 执行流限制需要反馈到 BCC scheduler 或 Vector front-end。
- Vector ROB group/non-flush 规则会影响 Tile侧LSU load/store 发射条件。
