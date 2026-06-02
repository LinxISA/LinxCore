# BCC OoO Architecture Notes

> **Document ID**: JCORE-BCC-AS-003
> **Version**: v0.1
> **Date**: 2026-05-14
> **Status**: Draft
> **Parent**: [JCore_BCC_AS.md](JCore_BCC_AS.md)
> **Keywords**: IFU, PE, Rename, CMD_ISQ, ROB, DISP, TileRename, BlockISQ

---

## Change Log

| Version | Date | Changes |
|---------|------|---------|
| v0.1 | 2026-05-14 | Initial BCC OoO pipeline notes, formatted with AS metadata |

---

## 1. BCC 顶层定位

BCC 中除 LSU 外的组件构成 BCC_IOE。JCore BCC 相比原 GFU 的主要新增点:

- IFU 中新增 BROB，维护 block 顺序提交。
- 新增数据块块头微指令路径: BSTART、B.TEXT、B.IOR、B.IOT、B.DIM、B.IOD。
- 新增两写两 pick 的 CMD_ISQ，完成块头微指令依赖解除。
- 新增 TileRename，将 src tile index 转换为 Tile tag 和 base address。
- 分别为 VEC、CUBE、TMA 增加对应 BlockISQ。
- 接收特殊核 GET 命令，通过专用 RF 读口返回 src ptag data。
- 接收特殊核 dst ptag / dst tilereg 写回，并 wakeup 依赖指令或 block。
- 接收特殊核 bid_resolve，用于 BROB 顺序 commit。
- 广播 flush 到特殊核。

## 2. 外部交互模块

| 模块 | 职责 |
| --- | --- |
| RIU | BCC 与协处理器总线 CXB 的接口，读写 BCC msgbuf 并唤醒线程 |
| TH_CTRL | BCC 内部线程管理，释放 HPOE/BWE 线程资源 |
| PMU_CTRL | 将 BCC PMU 事件送入 JCore 全局 PMU |
| INT | 汇聚 BCC 异常中断到 JCore 中断单元 |
| REG_SLV | BCC 寄存器读写访问 |
| VEC/CUBE/TMA | 通过私有连线交互，接收 dispatch、get/set、resolve、flush |
| L2_cache | L1 I/D cache miss 访问，细节 TBD |

## 3. IFU

IFU 负责块指令与微指令取指。为复用 INST_BUF，CT 模块迁移到 IFU 后端。

块头处理:

- 解析到块头时，将块头 PC、跳转 offset 等信息存入 Header Branch Buffer。
- Pre-decoder 解析到下一个块头指令时，读取 HBB 与预测信息比较。
- BSTART/B.TEXT/B.IOR/B.IOT/B.DIM 需要经 INST_BUF 传给 PE。
- IBCT_INST_BUF 出队时，一拍最多出 2 条块头微指令，最多包含 1 条 BSTART。
- INST_BUF 支持 16bit、32bit、48bit 三种长度，考虑按 16bit 粒度存储。

EOB_Nop:

- BSTOP 作为 Nop 微指令进入 PE 后端，指示 End of Block。
- 若同一个 fetch bundle 同时包含当前块最后一条块体微指令和下一块 BSTART/CT，则当前块 EOB_NOP 可消除，EOB 标记打到最后一条块体微指令上。
- 若最后一条块体微指令正好在 fetch bundle 边界，下一 fetch bundle 才取到下一块 BSTART/CT，则 EOB_NOP 不消除。

线程 PC 来源优先级:

1. 外部 flush 输入 PC、BWE commit 后下一次 wakeup 起始 PC。
2. 主预测器 F4 计算 next PC。
3. uBTB F1 快速预测 next PC。
4. 寄存器配置初始 PC。

其中 ROB flush 和 commit 不存在同拍情况，因此实际写 PC 优先级为 `flush/commit > main predictor > uBTB`。

## 4. IFU_BROB

IFU_BROB 在分配 BID 时记录 BID/TID，并接收 scalar PE、VEC、CUBE、TMA 的 resolve。

关键动作:

- 通过 commit pointer 维护 block 顺序提交。
- commit 信息广播给 scalar PE，用于 GPR MAPQ entry 搬移到 CMAP。
- BROB 记录 dst TileReg 在 TileRename 中分配的 Tile tag/type。
- block resolve 时，将 Tile tag/type/tid 送 TileRename 做 wakeup。
- block commit 时，将 Tile type/tid 送 TileRename 做资源释放。

详见 [02_BROB.md](02_BROB.md)。

## 5. PE_D1 / Decode

PE_D1:

- 按线程接收 IFU IBCT_INST_BUF 输出，最多 4 inst/cycle。
- D1_MUX_THREAD 对四线程 RR 调度。
- Decode Unit 译码后产生控制和数据信息，送 D2。
- 响应 PE 后端整体反压和线程级反压。

相比 KV500:

- 微架构基本相同。
- 微指令格式变化，译码单元需匹配块头指令格式。

## 6. PE Rename

GPR 和 ClockHands 寄存器的 MAPQ 需要分开:

- GPR MAPQ: 由 BROB 管控提交，GPR 是 block 第一层架构状态。
- ClockHands MAPQ: 由 PE ROB 管控提交，相对索引寄存器是微指令第二层架构状态。

B.IOR 在 rename 流水级处理:

- 对 getlist 查询 GPR 映射 ptag。
- 对 setlist 分配新的物理寄存器。
- IOR rename 时对外部读 GPR 的 ptag read counter +1。
- 若寄存器值在 SRAM，需要在 IOR rename 时搬入。

LTPR/SAFE:

- 只针对 CMAP 做释放可能不足。
- 对投机态寄存器释放需要扩展 MAPQ。
- SAFE 状态需考虑其他 core 在途读。
- read counter == resolve counter 时，ptag 才可安全释放。

## 7. CMD_ISQ

BCC PE 额外新增 2 写 2 pick 的 CMD_ISQ，专用于特殊核块头微指令处理。

CMD_ISQ pipe:

- `cmd_isq_pipx`: 处理 B.IOR。B.IOR src ready 后直接写入记录的 BlockISQ entry，不需要读口。
- `cmd_isq_pipy`: 处理 B.IOT 和 B.DIM，受 TileRename head flop 反压；B.IOT 需要顺序 pick，需要 1 个读口。

关键规则:

- B.IOT 必须顺序 pick，并按顺序写入 BlockISQ。
- B.DIM 以 loopnest 作为写入位置标识。
- 多条 B.IOR 为保持 BlockISQ 中 IOR0/IOR1/IOR2 顺序，在写 CMD_ISQ 同时也写 BlockISQ。
- CMD_ISQ 对 IOR 的 pick 仅用于 BISQ wake_up_num / config counter 判断。
- CMD_ISQ 收到 wakeup 后打 3 拍；若无 cancel，再真正做 src reg wakeup，用于简化 load_cancel 处理。

## 8. PE ROB

PE ROB 负责微指令乱序执行后的顺序提交，并在投机错误时产生 flush 广播给 IFU、PE 前端和 PE 后端。

当前材料中，PE_ROB 相比 KV500 暂无主要差异。

与 BCC 新增能力的关系:

- 微指令级 commit 仍由 PE ROB 管理。
- GPR block 级 commit 改由 BROB 管控。
- ClockHands 相对索引寄存器仍由 PE ROB 提交和释放。
- PE ROB flush 需要与 BROB flush、TileRename 回滚、CMD_ISQ/BISQ 清理协同。

## 9. PE TPCBUF

每条微指令携带专属 TPC。TPC_BUF 保存 base TPC，并传递其余指令相对 base 的 offset。

功能:

- 发生 flush 时，根据 offset 和 base TPC 计算恢复 PC 送 IFU。
- 根据初始 TPC 和 Decode 调度指令数量计算 offset 是否越界。
- 越界后写入新的 base TPC 或直接跳转指令 next TPC。

相比 KV500 暂无主要差异。

## 10. PE Dispatch

PE_DISP 将不同类型微指令分发到对应 ISQ，并管理 IQ entry credit。

新增点:

- 数据块块头微指令进入 PAR_BLOCK DISP / CMD_ISQ。
- PE_DISP 需要管理 CMD_ISQ credit。
- 不进入 CMD_ISQ 的指令不消耗 CMD_ISQ credit。

常规分发规则:

- BR/SETC 类只进入 AB ISQ。
- MSGBUF/MUL/ADDPC 类只进入 AM ISQ。
- ALU/STA 类可进入 AB 或 AM，按 IQ 空位负载均衡。
- STD/LDA 类可进入 LS0 或 LS1，按 IQ 空位负载均衡。

分发结果体现在 DPD 域段。后续依赖某个 dst ptag/ttag 时，会在 PRST 中查找其结果来自哪个执行通道。

## 11. Uop Break

最多拆分 2 个 uop 的指令包括:

| 指令类型 | 拆分结果 |
| --- | --- |
| ST | STA、STD |
| ST.A | STA、STD |
| LB.A / PRF.A.L2 | LDA、ALU |
| SC.D / SC.W | STA、STD、LDA resolve |
| DSB | STA resolve、DSB resolve |
| LD / PRF.L2 | LDA resolve |
| CSEL | 完成 3 个 src reg 的拆分 |

## 12. PE TileRename

PE_TILERENAME 是新增模块。

职责:

- 根据 B.IOT 的输入输出 TileReg，为 output TileReg 在 ring buffer 上申请存储空间。
- 查询 input TileReg 对应空间和 tag。
- 解析 TileReg 起始地址。
- 将 Tile tag、Tile 地址、ready 初值随路写入 BlockISQ。

TileReg 空间:

- 总空间示例为 1MB。
- T/U/M/N 分别占 512KB/256KB/128KB/128KB。
- BCC 两线程 partition 占据 TileReg 空间。

详见 [01_TileRename_BlockISQ.md](01_TileRename_BlockISQ.md)。

## 13. PE BlockISQ

PE_BLOCKISQ 是新增模块。E2 处 ReadyTable 表项个数与 TileRename 映射表深度一致。

行为:

- 下游 block resolve 后，BROB 标记 block resolve。
- BROB 将 dst tag 唤醒 TileRename/ReadyTable。
- BISQ 中等待该 tag 的 src ready 被置 1。
- BISQ 按 block 类型、ready、config、age、credit 发射给下游执行核。

详见 [01_TileRename_BlockISQ.md](01_TileRename_BlockISQ.md)。

## 14. PE RF

RF 包含两套物理寄存器实体:

- PRF: Physical Register。
- UTRF: Temporal Physical Register。

读写口需求:

- PRF 基础规格参考 8R6W，LTPR 需额外 1R1W。
- UTRF 参考 6R5W。
- 为 CUBE、VEC、TMA 的固定 latency Get data 各自开辟一路独立读口。
- 为 VEC、TMA 写 RF 各自开辟一路独立写口。

RF 内部实现部分 W3 -> I2 bypass 前移到 W2 -> I1。

## 15. PE Bypass / EXE

PE_BN 差异:

- 完成 B.TEXT 的 TPC 计算。
- 完成 B.DIM 的 `+imm` 运算。

PE_EXE:

- 当前材料中与 KV500 基本相同。
- 2 拍 ALU 指令细节 TBD。

## 16. 与特殊核交互

Dispatch:

- 推送 datatype、tileop、tile_src、tile_dst、reg_src_ptag、reg_dst_ptag。
- 采用 credit 流控。

Core req:

- 特殊核发起 Get_src_data。
- BCC 根据 get_src_ptag 读 RF，并返回 src_data。
- get/set 请求固定 latency，不被反压，至少需要 3 路读口支持 CUBE/VEC/TMA。

Writeback / Resolve:

- 特殊核写 dst_ptag，BCC wakeup 依赖该 ptag 的指令。
- 特殊核写 dst_tilereg / block resolve，BCC wakeup 依赖该 TileReg 的 BISQ entry。
- 特殊核返回 bid_resolve，BROB 顺序 commit。

Flush:

- BROB/PE ROB 汇聚 flush 后广播给特殊核。
- 特殊核需清理对应 BID 之后的投机状态。

## 17. CA 实现要点

- IFU 支持块头微指令经 INST_BUF 传给 PE。
- IFU 新增 BROB，管理 BID 分配、resolve、commit。
- PE Rename 拆分 GPR MAPQ 和 ClockHands MAPQ。
- 新增 CMD_ISQ，支持 2 写 2 pick 和 B.IOR/B.IOT/B.DIM 特化路径。
- PE_DISP 增加 CMD_ISQ credit 管理。
- 新增 TileRename，维护 T/U/M/N 映射表、ReadyTable、credit 和地址分配。
- 新增 BlockISQ，完成 TileReg block 级依赖解除和发射。
- RF 增加特殊核 Get data 独立读口和 VEC/TMA 写口。
- BN 增加 B.TEXT TPC 计算和 B.DIM 加法。
- Flush 需要统一覆盖 IFU_BROB、PE ROB、CMD_ISQ、TileRename、BlockISQ、特殊核在途状态。
