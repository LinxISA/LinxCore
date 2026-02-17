# TMA 实施计划（TLOAD/TSTORE 真功能化）

## 1. 目标与边界

### 1.1 本期目标

- 将 `pyCircuit/designs/linxcore/src/tma/tma.py` 从占位模块改造成可执行的 TMA 单元。
- 支持 `TLOAD` 与 `TSTORE` 在 GM（Global Memory）与 TR（Tile Register/TMU 侧）之间搬移数据。
- 支持按指令参数执行数据排布转换（至少覆盖 `ND/DN/NZ/ZN` 相关路径与 `B.ARG` 选择）。
- 指令完成后产生完成响应，推动 BCC 侧提交推进（`non-flush ptr` / `ROB oldest ptr` 前移）。

### 1.2 本期明确不包含

- 不包含“块指令解码到 TMA”的实现（默认 `cmd_payload_tma` 已给出 TMA 需要的关键参数）。
- 不包含 LSU 中 memory model 要求的地址重叠范围检查实现。
- 不包含预测性 `TLOAD` 被 flush 时的回滚/取消处理流程。

## 2. 外部接口与协议约束

### 2.1 BCC -> TMA 命令接口

- 继续沿用现有入口：
  - `cmd_valid_tma`
  - `cmd_tag_tma`
  - `cmd_payload_tma`
- 在 `tma` 目录新增“payload 解包规范”，定义字段含义与版本：
  - 操作类型：`TLOAD`/`TSTORE`
  - 数据类型与元素字节数
  - 维度参数（等价 `LB0/LB1/LB2` 语义）
  - GM/TR 基地址、步长、tile 尺寸
  - layout/pad 配置（等价 `B.ARG`）
  - 事务大小配置（`128B`/`256B` 可选）

### 2.2 TMA -> BCC 完成接口

- 继续沿用现有出口：
  - `rsp_valid_tma`
  - `rsp_tag_tma`
  - `rsp_status_tma`
  - `rsp_data0_tma`
  - `rsp_data1_tma`
- 状态码建议：
  - `OK`
  - `DECODE_ERR`
  - `PROTOCOL_ERR`
  - `ACCESS_ERR`
  - `UNSUPPORTED`
- `rsp_tag_tma` 必须与入队命令一一对应，保证 BCC 可安全推进提交指针。

### 2.3 GM 侧 CHI 风格事务

- 非一致性事务风格：
  - 读：`ReadOnce`
  - 写：`WriteUnique`
- `WriteUnique` 必须覆盖三段交互：
  - `WriteReq`
  - `DBIDResp`
  - `WriteData`
- `Size` 可配置，首批支持：
  - `128B`
  - `256B`

### 2.4 TR/TMU 侧 CHI 风格事务

- 通过 Ring 访问 TMU，事务风格采用 CHI-like：
  - 读：`ReadNoSnp`
  - 写：`WriteNoSnp`
- 如果仓内缺失 Ring/TMU 协议细节：
  - 先在 `tma` 内定义最小可用 CHI-like 本地接口（req/rsp/data 分离）
  - 后续再通过 adapter 对接真实 Ring/TMU 实现。

## 3. 目标微架构

### 3.1 模块拆分

- `cmd_frontend`：命令接收、解包、参数合法性检查、排队。
- `desc_engine`：维度/步长/布局遍历器，生成逻辑 tile 访问序列。
- `layout_engine`：执行 GM<->TR 数据映射与可选 pad。
- `gm_chi_master`：GM 侧 CHI 读写主机（ReadOnce/WriteUnique）。
- `tr_chi_master`：TR 侧 CHI 读写主机（ReadNoSnp/WriteNoSnp）。
- `xfer_scheduler`：按资源可用性和依赖关系调度事务。
- `completion_unit`：收敛完成条件并回传响应。

### 3.2 TLOAD/TSTORE 主流程

- `TLOAD`：
  1. 发起 GM `ReadOnce`（按 `Size=128B/256B` 分片）
  2. 数据进入 `layout_engine`，执行 GM->TR 排布转换与 pad
  3. 发起 TR `WriteNoSnp`
  4. 全部分片完成后返回 `rsp_status=OK`
- `TSTORE`：
  1. 发起 TR `ReadNoSnp`
  2. 数据进入 `layout_engine`，执行 TR->GM 排布转换
  3. 发起 GM `WriteUnique`（Req->DBIDResp->Data）
  4. 全部分片完成后返回 `rsp_status=OK`

### 3.3 状态机建议

- 指令级状态：
  - `IDLE`
  - `DECODE`
  - `ISSUE_RD`
  - `WAIT_RD`
  - `TRANSFORM`
  - `ISSUE_WR`
  - `WAIT_WR`
  - `COMPLETE`
  - `ERROR`
- 分片级状态独立管理（支持未来扩展到多 in-flight）。

## 4. 分阶段实施

### Phase 0：规格冻结与接口建模

- 输出 `payload` 字段定义文档（位域、单位、约束、默认值）。
- 输出 GM/TR 两侧 CHI-like 端口定义（信号列表与时序规则）。
- 输出错误码、完成语义、时序约束文档。

交付物：

- `tma/PAYLOAD_SPEC.md`
- `tma/CHI_IF_SPEC.md`

### Phase 1：替换占位符为可控骨架

- 将 `tma.py` 改造成结构化模块：
  - 命令队列
  - 状态机
  - 错误与完成通路
- 暂用“空搬运路径”连通流程（不做真实数据变换），确保命令生命周期正确。

验收：

- 可以正确接收命令并返回 `tag/status`。
- `rsp_valid_tma` 只在完成时拉高。

### Phase 2：CHI 主机通路打通（不含 layout）

- 实现 GM 侧：
  - `ReadOnce`
  - `WriteUnique` 三阶段握手
- 实现 TR 侧：
  - `ReadNoSnp`
  - `WriteNoSnp`
- 先用线性地址映射（无 layout 转换）验证双向读写链路。

验收：

- 协议握手序列正确。
- 分片搬运（128B/256B）可运行。

### Phase 3：layout/pad 引擎接入

- 接入 `ND/DN/NZ/ZN` 映射逻辑。
- 接入 pad 策略（至少 `Null/Zero/Max/Min` 对应路径）。
- 完成 `TLOAD` 与 `TSTORE` 的数据排布闭环。

验收：

- 指定案例下 TR 数据与软件参考模型一致。
- `TSTORE` 不生成 pad，仅按目标 footprint 写回。

### Phase 4：错误处理与可观察性

- 增加协议超时/非法参数/访问异常处理。
- 增加调试与统计计数器：
  - 请求数
  - 分片数
  - 错误数
  - 周期占用
- 统一映射到 `rsp_status_tma`。

验收：

- 异常路径可稳定结束且不挂死。
- 统计信息可被测试读取。

### Phase 5：集成与回归

- 在 `top` 路径上接入 TMA 新模块并替换 stub 行为。
- 增加 directed tests：
  - `TLOAD` 基本流
  - `TSTORE` 基本流
  - `128B/256B` size 切换
  - layout 转换覆盖
  - 错误注入
- 增加随机化回归（参数随机 + 事务背压）。

验收：

- 回归通过率达到约定阈值（建议 > 95%）。
- 无死锁、无 tag 错配、无完成丢失。

## 5. 验证策略

### 5.1 参考模型

- 在测试侧实现软件 reference（Python）：
  - 地址遍历
  - layout 映射
  - pad 行为
- 硬件输出与 reference 按 tile 比对。

### 5.2 协议检查

- 对 CHI-like 接口加断言：
  - 请求-响应配对
  - `WriteUnique` 顺序约束（Req 在前，Data 在 DBID 后）
  - 分片边界与 size 合法性

### 5.3 集成检查

- 验证完成响应与 BCC tag 匹配，确认可驱动提交推进。
- 验证背压场景下不丢包、不重放、不错序。

## 6. 风险与缓解

- 风险：`cmd_payload_tma` 位宽/字段不足。
  - 缓解：Phase 0 先冻结位域；必要时引入扩展 payload 或旁带配置。
- 风险：Ring/TMU 真实接口晚于 TMA 开发。
  - 缓解：先做 CHI-like 本地接口 + adapter 抽象层。
- 风险：layout 逻辑复杂导致时序压力。
  - 缓解：分级流水（地址生成/搬运/重排拆级），优先功能正确再优化频率。

## 7. 下一步计划（本期后）

- 块指令解码到 TMA（把 `BSTART.TMA/B.ARG/B.DIM/B.IOR/B.IOT` 端到端落地到 `cmd_payload_tma`）。
- LSU 中 memory model 相关地址重叠范围检查。
- 预测执行下 `TLOAD` 被 flush 的取消、回滚与资源回收机制。

