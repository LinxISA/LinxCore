# Tile-Side LSU Appendix

> **Document ID**: JCORE-BCC-AS-APP-LSU
> **Version**: v0.1
> **Date**: 2026-05-14
> **Status**: Draft
> **Parent**: [JCore_BCC_AS.md](JCore_BCC_AS.md)

---

## Change Log

| Version | Date | Changes |
|---------|------|---------|
| v0.1 | 2026-05-14 | Captured Tile-side LSU notes relevant to BCC BlockISQ / TMA ordering |

---

## 1. Motivation

TMA block 的发射规则必须和 Tile 侧 LSU 的 load/store 顺序模型对齐。Vector/Cube block 的 TileRegister 依赖可在 BCC BISQ 中显式解除，但 TMA 访存单元内部存在 memory 地址依赖和 store side effect，因此 BCC 需要理解 LSU 的 non-spec、STQ、SCB、VAB、gather/scatter 约束。

---

## 2. Vector / Tile-Side LSU Pipeline Context

原始流水图中，上方是 scalar pipe，下方是 vector pipe。访存请求均由 scalar pipe 发出。

Tile 侧 LSU 实现 load/store 指令，同时依赖 LSU 内部细节，以及与 LSU 直接交互的 OOO/IEX 内部细节对齐。

---

## 3. LD_ID / ST_ID Allocation

Vector 微架构为 SMT4，每个执行流称为 `logic_group`。一个 logic_group 内的指令有保序需求，通过:

- `lgid` / logic group id，原 tid。
- `ldid`
- `stid`

来标识顺序。

Decode 解出 instruction 是否是 load/store，并将 `is_load` / `is_store` 往下传。

Rename D3 中，ROB 为每条指令分配:

- `rid`
- `ldid`
- `stid`

`rid/ld_id/st_id` 均按 logic_group 区分。

示例:

| Inst | rid | ldid | stid |
|------|-----|------|------|
| add | 0 | 0 | 0 |
| load | 1 | 1 | 0 |
| store | 2 | 1 | 1 |
| add | 3 | 1 | 1 |
| add | 4 | 1 | 1 |
| load | 5 | 2 | 1 |
| add | 6 | 2 | 1 |
| store | 7 | 2 | 2 |
| add | 7 | 2 | 2 |

`nxt_cmmt_ldid` 为提交的 oldest 指令的 load_id + 1。若指令 1/2/3/4 同拍提交，提交的 oldest 指令为 4，其 load_id 为 3，则 `nxt_cmmt_ldid = 4`。

---

## 4. 2025-10-14 Change: First Stage Does Not Support nuke_flush

例会确定第一阶段暂不支持 `nuke_flush`。

含义:

- 不支持 `nuke_flush` 意味着 load 不能投机。
- load 不能投机不等于不能乱序。
- 如果 load resolve 后不会再被 store violation flush，则 LDQ 可在 resolve 后释放，不必等 commit。

原先维护 load_id 的作用:

- load 即使 resolve，在 commit 前也占据 LDQ/LHQ。
- store 从 STQ 发出时比较 LDQ 中 load 地址和 rid。
- 如地址冲突，触发 load flush，清除投机错误影响。
- LDQ 需要滑动窗口，避免 older 指令占用 entry 导致 younger load 无法进入。

当前不支持投机后:

- load resolve 后结果一定正确，不会再被 flush。
- LDQ 更接近只有 LIQ 的访存发射部件。
- ISQ 或 LSU 需要保证 load 指令 non_spec 时才能发出。
- 不再需要维护 LDQ 滑窗，也不需要 load_id 用于 flush 检查。
- LDQ 可更自然地在 logic_group 间共享。

---

## 5. Non-Spec Pointer

维护 `non_spec_id`，暂时等同于原 store_id。因为 load 不能越过 store 执行。

ROB 分配规则:

- 遇到 store 时，`non_spec_id + 1`。
- 遇到其他指令时，`non_spec_id` 不变。

ISQ 发射 load 时，load 必须是 non_spec。

该规则需要和 BCC TMA BISQ 的 TLOAD/TSTORE 发射条件对齐。

---

## 6. Load Instruction Flow

Decode / rename 流水级需要对 load/store 指令做 uop 拆分。Decode 可识别 load/store 是否连续访问。

编译器优化:

- 后续由指令携带 Continuous Flag。

编译器指令未就绪时，CA 在 D2 判断连续:

- 判断 `width & offset lc0 << ?` 是否对应。

连续访存:

```text
Load  -> lda
Store -> sta + std
```

离散地址:

```text
Load -> s2v + agen(add) + lda
```

说明:

- `s2v` 走 scalar pipe，将 scalar 基址转换为 vector 值，唤醒 agen。
- `agen` 做 vector 加法得到访存地址，地址写入 VAB。
- Vector pipe 计算结果 E3 发给 VAB。
- agen 在 vector pipe 中，仅当 VAB 有空余 entry 时才能 pick，VAB depth = 1。
- `lda` 在 scalar pipe，形式为 `lda VAB -> dst_vrf`。
- `lda` ready 条件特殊: 仅需要满足 non_flush，即可发送至 LSU，送至 VAB，表示 gather request 可开始发出。

---

## 7. Load Pipe

Load result pipe 有四路来源:

1. IEX issueQueue: 来自 IEX scalar pipe 的 lda 请求。
2. LDQ repick: scalar pipe load request miss 后写入 LDQ，等待 ring 回数 wakeup。
3. VAB 状态机 request: VAB 拆出的离散访存请求。
4. Gather LIQ repick: gather load request miss 后写入 Gather LIQ，等待 ring 回数 wakeup。

优先级暂定:

```text
Gather LIQ repick > VAB req > LDQ repick > IEX issueQ req
```

VAB:

- 维护 Gather LIQ entry credit，depth = 4。
- request 拆分完毕后，还需要 lda 唤醒才能发请求。
- VAB 状态机一次只能接收一条来自 IEX ISQ 的 gather load。
- ISQ 做资源反压，credit = 1。
- 离散访存请求全部收齐后，VAB 发出 writeback request，复用 result pipe，将数据写回寄存器，并向 ROB resolve。
- writeback request 不发实际 load req，不查 cache，只复用 pipe。

Cancel:

- LDQ/VAB 中请求被 cancel 时，内部表项 issued 回退，等待再次 repick。
- IEX 请求被 cancel 时，进入 skid buffer，4 拍后写入 LDQ，避免 cancel 请求和 result pipe 中 IEX load 请求发生 LDQ 写口冲突。

Load pipe stages:

| Stage | Function |
|-------|----------|
| I2 | 四路 request 到达，做 result pipe arbiter；只判断哪路上 pipe，不要求 ld addr 已到 |
| E1 | TileCache tag read；TileCache data bank arb；CAM SCB/CAM STQ/CAM STQ VAB |
| E2 | STQ forward cancel 检查；TileCache tag compare；产生 hit/miss，决定 E3 写 LDQ/Gather LIQ |
| E3 | miss 写 LDQ/Gather LIQ；hit 做 data arbiter 和 data merge，送 E4 |
| E4 | 根据 Continuous 决定写回寄存器或 VAB |
| E5 | 若 E4 写回寄存器，E5 向 ROB 发送 resolve |

架构假设:

- TileReg 单笔请求不跨 line 访问。

---

## 8. STQ and SCB

### 8.1 STQ Ordering

ROB 给 store 指令分配 store_id，并维护各 logic_group 的滑动窗口。

Store 指令进入 Tile 侧 LSU 后固定拍数后 resolve。

STQ 既是:

- store 指令 data buffer。
- store 请求保序和发射单元。

STQ 中存储 store 指令地址和数据。STQ 请求需要按 store_id 保序，等到 `nxt_commit_ptr` 后才能从 STQ 出队进入 SCB，真正向 ring 发起请求。

STQ 不能线程间完全共享的原因:

- STQ 内部承载保序功能。
- 若将保序功能拆出，做 logic_group partition，并将 STQ 仅作为 store request 发射单元，则 STQ 资源可以共享。

具体实现建议:

- 32 entry STQ。
- 28 entry shared。
- 4 entry 为各 logic_group 独享。
- 各线程通过独享 tracker 维护 32 深 sliding window。

### 8.2 Store Uop Break

离散 store:

```text
s2v + agen + sta/std
```

规则:

- `s2v` 走 scalar pipe，将 scalar 基址转换为 vector 值，唤醒 agen。
- `agen` 做 vector 加法得到访存地址并写 VAB。
- agen 在 vector pipe 中，仅当 VAB 有空余 entry 且该 agen 指令为 logic_group 最老时才能 pick。
- `sta VAB` uop 受 agen 指令唤醒，无其他 pick 条件。
- VAB 就绪后发电平信号，此后只能挑出对应 gather/scatter sta uop，屏蔽其他 sta pick。

### 8.3 Scatter Store

Scatter store 只有在变成线程最老时才能出队，期间屏蔽其他 sta uop 的挑选。

---

## 9. Shared STQ/LDQ Entry Design

以八线程 LDQ 为例:

- 表项资源分 shared 和 private。
- entry_idx 0..7 固定预留给 t0..t7。
- 每个线程只有对应最老 ldid 可占用其 private entry。
- 其余 24 个资源全共享，先来先占。

指令申请到 entry_id 后:

- 根据 tid 和 `ldid[3:0]` 写入对应线程 tracker 的固定位置。
- 记录申请到的 entry_idx 和 rid。

为避免下一轮 LD/ST 踩踏前一轮指令:

- LSU 仍需滑窗限制可进入的最大 lid。
- PE ISQ 只允许发小于 `yst_ldid = next_commit_lid + 16` 范围内的 load。

上游 ISQ 管理:

- `share_credit`，5 bit。
- 每线程 private_credit，1 bit。

ISQ 发 LSU 条件:

```text
(share_credit > 1 || (ldid == nxt_cmt_ldid && private_credit != 0))
&& ldid < yst_ldid
```

ISQ 发给 LSU 时携带使用 shared/private 的指示。LSU 释放 shared 时返回 credit 数目；释放 private 时返回释放 private 信号给 ISQ。

---

## 10. CA Implementation Items

1. st_id 维护。
2. ld_id 保留配置开关。
3. non_spec_ptr 实现。
4. Decode 处 load/store 指令拆分。
5. ISQ 处唤醒及反压逻辑。
6. CA 按 LSU 直接写 LDQ 实现。
7. VAB 实现。
8. LDQ 主通路实现。
9. forward & cancel 逻辑实现。
10. STQ 主通路实现。
11. SCB 实现。
12. TileCache 替换策略调整。
13. STQ entry 多 group shared 和 tracker 实现。
14. PMU 设计。
