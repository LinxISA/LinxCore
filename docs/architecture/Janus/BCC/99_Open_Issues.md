# BCC Open Issues and TBD Index

> **Document ID**: JCORE-BCC-AS-OQ
> **Version**: v0.1
> **Date**: 2026-05-14
> **Status**: Draft

---

## Change Log

| Version | Date | Changes |
|---------|------|---------|
| v0.1 | 2026-05-14 | Collected TBD/open issues from BCC, TileRename, BROB, Tile-side LSU and Vector-OOO notes |

---

## 1. BCC Top / System

| ID | Issue | Priority |
|----|-------|----------|
| SYS-1 | BCC 核参数需要整理成面积裁剪输入表。 | High |
| SYS-2 | BCC 乱序能力裁剪对应面积收益需要建模。 | High |
| SYS-3 | BCC 系统视角调度能力，如消除长尾效应，需要性能模型。 | Medium |
| SYS-4 | SMT QoS 能力需要需求拆解。 | Medium |
| SYS-5 | 标量 block 是否影响特殊 block 取指能力，需要 profile。 | Medium |
| SYS-6 | GET data 时延需要纳入建模。 | High |
| SYS-7 | DFX/PMU 事件结构需要统一提取。 | Medium |

## 2. Interface

| ID | Issue | Priority |
|----|-------|----------|
| IF-1 | 《TMU-Core接口规格.xlsx》中的 Dispatch/Core req/Resolve/Flush 信号需要抽取到 AS。 | High |
| IF-2 | Core req fixed latency 周期数需要定稿。 | High |
| IF-3 | get/set 请求不反压时，RF 端口和 bypass 时序需要确认。 | High |
| IF-4 | VEC/CUBE/TMA 私有连线 top-level 信号命名待定。 | Medium |
| IF-5 | L2_cache、LSU、OS、Stash 适配仍为 TBD。 | Medium |

## 3. TileRename

| ID | Issue | Priority |
|----|-------|----------|
| TR-1 | TileRename map depth、ReadyTable depth、各 hand entry 数待定。 | High |
| TR-2 | TileRename flush rollback 机制待定: checkpoint、walk reclaim 或 BID-tagged reclaim。 | High |
| TR-3 | TileRename 是否支持内部卷绕待定。 | High |
| TR-4 | 若支持卷绕，Vector 内地址计算需同步改动。 | High |
| TR-5 | pipe 字段初版是否完全不实现，还是保留为 tie-off 字段？ | Medium |
| TR-6 | TileReg size credit 单位和最大 size 编码需定稿。 | High |
| TR-7 | 软件 spill/临时空间约定需进入 ISA/ABI 说明。 | Medium |

## 4. B.IOR / GPR

| ID | Issue | Priority |
|----|-------|----------|
| GPR-1 | read counter / resolve counter 存储位置待定。 | High |
| GPR-2 | IOR rename 时寄存器值在 SRAM 上的搬入流程需细化。 | High |
| GPR-3 | LTPR 是否进入首版 CA 待性能仿真决策。 | Medium |
| GPR-4 | SAFE 状态叠加 other-core in-flight read 的精确定义待定。 | High |
| GPR-5 | 多条 B.IOR 的 wake_up_num/config counter 语义需 RTL 化。 | Medium |

## 5. BISQ / Issue

| ID | Issue | Priority |
|----|-------|----------|
| BIQ-1 | Vector/Cube 乱序 issue 的 age matrix 实现深度与端口待定。 | High |
| BIQ-2 | TMA 顺序 issue 与 BROB age、BISQ age 的关系需定稿。 | High |
| BIQ-3 | VCALL/MCALL 模式切换时 MCALL 最老块判断来源待定。 | Medium |
| BIQ-4 | Cube ACC Flag 同拍继承逻辑需要 RTL 时序验证。 | High |
| BIQ-5 | Cube 只有一个计算单元；ACC chain 作为逻辑依赖链时，链内顺序和共享 dispatch 仲裁规则需补充。 | Medium |
| BIQ-6 | TCOPY credit 数量和返回时机待定。 | Medium |

## 6. BROB / Recovery

| ID | Issue | Priority |
|----|-------|----------|
| BROB-1 | 默认每 STID 256 entries、`BID_W=ceil(log2(entries))`、wrap/sequence 独立已定；仅非默认 depth 的 PPA 选择待定。 | High |
| BROB-2 | block resolve 与 flush 同拍冲突优先级待定。 | High |
| BROB-3 | block commit 与 PE ROB flush 同拍优先级待定。 | High |
| BROB-4 | Tile resource release 是否释放 dst 或 old mapping，需要结合 Tile architecture 语义澄清。 | High |
| BROB-5 | GPR MAPQ range 如何与 BID 绑定待定。 | High |
| BROB-6 | 外部异步中断是否支持，粒度为 block 还是 group 待定。 | Medium |

## 7. Tile-Side LSU

| ID | Issue | Priority |
|----|-------|----------|
| LSU-1 | 第一阶段不支持 nuke_flush 后，ld_id 是否完全移除还是保留配置开关？ | Medium |
| LSU-2 | non_spec_ptr 更新需参考 wiki 并固化到 AS。 | High |
| LSU-3 | Continuous Flag 编译器未就绪时的 D2 判断逻辑需明确。 | Medium |
| LSU-4 | Gather LIQ / LDQ / VAB cancel 与 skid buffer 时序需验证。 | High |
| LSU-5 | PMU 设计待补。 | Medium |
| LSU-6 | STQ shared/private tracker 的 entry 数是否按 SMT4 或 SMT8 定稿？ | Medium |

## 8. Vector-OOO

| ID | Issue | Priority |
|----|-------|----------|
| VEC-1 | 小粒度计算 group 合并 CA 暂不实现，后续是否纳入路线图待定。 | Low |
| VEC-2 | 64bit 计算 pipe 合并 CA 暂不实现，但需模拟寄存器占用。 | Medium |
| VEC-3 | Uniform Register 表项数量与最大在飞 block 数需对齐。 | High |
| VEC-4 | Predicate BID/grp_id 恢复逻辑需要与 BCC flush 统一。 | High |
| VEC-5 | Block Dispatch 限制 FP64 只进入两个执行流的策略需放入调度器。 | High |

## 9. NPU-GPU Fusion / Vector Core / Unified Buffer

| ID | Issue | Priority |
|----|-------|----------|
| FUSION-1 | JCore 整体单线程、BCC 单线程、Vector/Memory PE 多线程的精确调度关系需 RTL 化。 | Medium |
| FUSION-2 | TileOp/Block、Group、MicroOp 三层指令在 debug/PMU 中的统一 ID 体系待定。 | Medium |
| FUSION-3 | Tile Register 12-bit buffer ID 与 BCC TileRename 21-bit physical address 的转换关系需定稿。 | High |
| VCORE-1 | Loop Ctrl 根据 LB0/LB1/LB2 拆 group 的边界 mask 生成需补正式图和伪代码。 | High |
| VCORE-2 | Block 最大 element size hint 的编码位置和语义待定。 | High |
| VCORE-3 | Group Buffer 并行/串行写入模式切换策略待定。 | Medium |
| VCORE-4 | Group ROB 每拍最多 2 个 commit 请求与 BCC resolve/commit 接口带宽需对齐。 | High |
| VCORE-5 | VIFU 对 BSTOP/TileOp/GROB next entry 的 PC 更新优先级需 RTL 时序验证。 | Medium |
| VCORE-6 | Vector TBuffer prefetch 由 BCC 触发时的预取量和最低优先级仲裁需建模。 | Medium |
| VCORE-7 | Vector block resolve 是否必须等待 TBuffer 所有 Modified writeback 完成，需要和 BCC ReadyTable wakeup 严格对齐。 | High |
| UB-1 | 1MB TileReg 与 256KB Unified Buffer 两套容量描述需要统一。 | High |
| UB-2 | 16 个 256x512 单口 SRAM 的 bank index 与 TileRename address format 需统一。 | High |
| UB-3 | Bank conflict cancel/retry 的周期、重发策略和 PE request buffer 协议需定义。 | High |
| UB-4 | Cube L0A/L0B/L0C 是否做成软件不可见 cache 需模型评估。 | Medium |
