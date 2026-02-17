# TMA Payload 规范（v1, no-desc-ptr）

## 1. 设计原则

- `txn_size` 不是 BCC->TMA 输入字段。  
  `TLOAD/TSTORE` 被 TMA 拆分为 CHI uop 后，`txn_size` 由 TMA 内部策略决定，仅体现在 GM 侧 CHI 请求上。
- 不使用 `desc_ptr` 指向内存描述符。  
  TMA 执行所需参数应由 BCC 直接传递给 TMA（当前接口不足时，采用接口扩展，而非“内存间接索引”）。
- 维度语义对齐 ISA：
  - `gm_inner_elems` ~= `LB0`
  - `gm_outer_elems` ~= `LB1`
  - `tr_inner_elems` ~= `LB2`
  - `tr_outer_elems` 作为显式输入传递（不再传 `tile_bytes`）
- stride 约束：
  - 仅允许 `gm_inner_stride_B` 可配置；
  - 其他三个维度 stride 固定由布局/元素宽度推导，不允许外部传 stride。

## 2. BCC->TMA 参数集合

说明：下面定义的是**逻辑 payload 集合**（`TMA_CMD`）。  
实现上可映射为：

1. 扩展 `bctrl_to_pe_stage_cmd_payload` 位宽，或  
2. 增加并行旁带参数口。  

不采用 `desc_ptr` + 外部内存读取模式。

`TMA_CMD` 字段：

- `payload_ver[3:0]`
- `op[1:0]`
- `elem_type[2:0]`
- `layout_mode[2:0]`
- `pad_mode[1:0]`
- `flags[7:0]`
- `gm_base_addr[63:0]`
- `tr_base_addr[63:0]`
- `gm_inner_elems[15:0]`
- `gm_outer_elems[15:0]`
- `tr_inner_elems[15:0]`
- `tr_outer_elems[15:0]`
- `gm_inner_stride_B[15:0]`

### 2.1 编码值定义

`payload_ver`:

- `4'h1`: 本文档版本（v1）
- 其他：`DECODE_ERR`

`op`:

- `2'b00`: `TLOAD`
- `2'b01`: `TSTORE`
- 其他：`UNSUPPORTED`

`elem_type`:

- `3'b000`: `INT8`
- `3'b001`: `INT16`
- `3'b010`: `INT32`
- `3'b011`: `FP16`
- `3'b100`: `FP32`
- 其余：保留

`layout_mode`（与 ISA `B.ARG` 对齐）:

- `3'b000`: `NORM`
- `3'b001`: `ND2NZ`
- `3'b010`: `ND2ZN`
- `3'b011`: `DN2NZ`
- `3'b100`: `DN2ZN`
- 其余：保留

`pad_mode`:

- `2'b00`: `Null`
- `2'b01`: `Zero`
- `2'b10`: `Max`
- `2'b11`: `Min`

`flags`:

- `bit0`: `strict_align_en`
- `bit1`: `irq_en`（预留）
- `bit2`: `trace_en`
- `bit3`: `ordered_en`
- `bit7:4`: 保留（写 0）

## 3. 参数组（按原 Wn 讨论）

为便于和你之前问题对应，保留“Wn”命名，仅表示参数分组，不表示“内存描述符地址索引”。

- `W0`: `gm_base_addr[63:0]`
- `W1`: `tr_base_addr[63:0]`
- `W2`: `gm_inner_elems | gm_outer_elems | tr_inner_elems | tr_outer_elems`
- `W3`: `gm_inner_stride_B | rsvd`

约束：

- `W4` 取消（`gm_rows/gm_cols/tr_rows/tr_cols` 不再单独传递）。
- `W6` 移到下一步计划（当前阶段不在 payload 中）。
- `W5` 移到下一步计划（当前阶段不在 payload 中）。
- `W7` 移到下一步计划（当前阶段不在 payload 中）。

## 4. 为什么 W5 与 W7 延后

`W5`（控制参数）:

- `timeout_cycles`：防止协议异常或对端无响应导致 TMA 挂死。
- `split_count_max`：限制单指令拆分的 uop 数量，避免占满内部队列和 CHI credit。

`W7`（软件标识）:

- `sw_cookie`：由软件/运行时注入，在完成时回传，便于把 TMA 完成事件与上层任务实例关联。
- 不影响硬件语义，只用于可观测性与调试追踪。
- 当前阶段先聚焦“功能正确的 TLOAD/TSTORE 搬运 + layout 转换 + CHI 收发闭环”，因此将两组参数延后。

## 5. txn_size 生成规则（TMA 内部）

- 输入：`op/layout/elem_type/对齐条件/剩余长度/通道拥塞状态/实现参数`。
- 输出：每个 CHI uop 的 `size`（首批允许 `128B/256B`）。
- 约束：
  - 同一 uop 不跨越非法边界（对齐/页边界策略由实现定义）。
  - 写请求严格满足 CHI 顺序：`WriteReq -> DBIDResp -> WriteData -> Comp`。

## 6. 完成返回编码

接口：

- `rsp_status_tma[3:0]`
- `rsp_data0_tma[63:0]`
- `rsp_data1_tma[63:0]`

建议：

- `rsp_data0_tma[31:0]`: `done_beats`
- `rsp_data0_tma[63:32]`: `error_info`
- `rsp_data1_tma[31:0]`: `elapsed_cycles`
- `rsp_data1_tma[63:32]`: `rsvd`（W7 引入后再定义为 `sw_cookie` 回传）

## 7. 错误码

- `4'h0` `OK`
- `4'h1` `DECODE_ERR`
- `4'h2` `PROTOCOL_ERR`
- `4'h3` `ACCESS_ERR`
- `4'h4` `TIMEOUT`
- `4'h5` `UNSUPPORTED`
- `4'hF` `INTERNAL_ERR`
