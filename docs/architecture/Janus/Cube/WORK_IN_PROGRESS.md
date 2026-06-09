# CUBE 架构细化工作进度记录

**日期**：2026-06-09（更新）
**状态**：进行中

---

## 📋 已完成工作

### 0. 对外接口完备性检查（2026-06-09）

- ✅ **补充 Tile Command Buffer**：
  - 在 BCC → CUBE 之间增加 4-deep 缓冲
  - 明确反压机制：Buffer 满时 `cube_cmd_ready` 拉低
  - 更新系统框图和模块说明表

- ✅ **补充 ACC 容量管理说明**：
  - 明确所有 ACC chain 共享 128 KB 总容量
  - 单链可使用全部容量（此时只能执行一条链）
  - BCC 侧维护使用量计数器防止溢出

- ✅ **新增 ACC 释放信号接口**：
  - `cube_acc_release_valid/chain/size`
  - acccvt 每完成一个 uop 写回，发送一次释放信号
  - BCC 可以实时更新 ACC 使用量计数器
  - 支持细粒度的 ACC 容量管理

### 1. 纠正重大架构错误

- ✅ **删除 K_chunk 概念**（该概念不存在）
- ✅ **修正 BufferC 作用**：
  - 之前：分chunk累加，每K_chunk次RMW一次
  - 现在：完整K方向累加，只在K全部完成后写入ACC
- ✅ **修正 TMU 接口**：
  - 之前：2048B burst
  - 现在：256B flit（TMU Ring协议）
  - CUBE使用 node1（读）和 node3（写）
- ✅ **明确细粒度依赖**：
  - tmatmul.acc链的依赖是**per-输出位置**的
  - 第二个tileop无需等第一个完全完成
  - 支持流水线并行

### 2. 确定关键参数

| 参数 | 值 | 说明 |
|------|-----|------|
| L0A容量 | 64 KB | 4-way组相联 |
| L0B容量 | 64 KB | 4-way组相联 |
| L0 Entry大小 | 512 B | 需要2个TMU flit（2×256B）填充 |
| Prefetch Buffer深度 | 32 entries | 每entry = 1个TMU读请求（256B） |
| TileStore Queue深度 | 8 entries | 每entry = 1个TMU写请求（256B） |
| BufferC容量 | 1 KB | 16×16×FP32，匹配单fractal输出 |
| ISQ深度 | 32 uop | **组织方式待定** |
| TMU读通道 | node1/pipe1 | Ring接口 |
| TMU写通道 | node3/pipe3 | Ring接口 |
| TMU Flit大小 | 256 B | 每个请求 |

### 3. 已修改文档

- ✅ `architecture.md`：
  - § 8 BufferC：重写，删除K_chunk
  - § 10.3 ACC映射表：增加细粒度依赖和流水线并行说明
  - § 12.1 TileStore接口：修正为256B TMU Ring协议
  - § 12.2 写回流程：增加详细时序示例

- ✅ `CUBE_SPEC.md`（2026-06-09 更新）：
  - § 2.1 系统框图：增加 Tile Cmd Buffer
  - § 2.2 关键模块说明：增加 Tile Cmd Buffer 条目
  - § 8.2 ACC 映射表：补充容量管理机制（共享、无固定分区）
  - § 10.1 块命令接收接口：详细定义 Tile Cmd Buffer 和反压机制
  - § 10.2.1 ACC 释放信号：新增细粒度 ACC 释放接口

- ✅ `README.md`（2026-06-09 更新）：
  - 文档状态表：添加 CUBE_SPEC.md 和 WORK_IN_PROGRESS.md
  - 更新最后修改日期

- ✅ `diagrams/cube_architecture.md`（2026-06-09 更新）：
  - § 1 顶层模块框图：增加 Tile Cmd Buffer (depth=4) 和反压机制
  - § 2 数据流图：更新命令接收路径（BCC → Tile Cmd Buffer → FSM）
  - 更新最后修改日期

---

## 🔴 待完成工作（RTL前需要明确）

### 高优先级

#### 1. FSM 状态机定义
```
需要定义：
- 状态列表（IDLE, DECODE, SPLIT, PREFETCH, ...）
- 状态转换条件
- 多链并发时FSM如何组织（per-chain? 单FSM多路复用?）
- FSM如何处理ISQ满的背压
```

#### 2. ISQ 多链并发机制
```
32-deep ISQ如何组织？
选项A：全局共享32 entries（2-4链竞争）
选项B：per-chain静态分区（4链×8 entries）
选项C：动态分区（推荐）
  - 每链最小保证：4 entries
  - 共享池：16 entries
  - 总计：4×4 + 16 = 32

多链uop如何在ISQ中仲裁发射？
不同链的uop能否跨链乱序发射？
```

#### 3. Prefetch 机制细节
```
触发时机：FSM SPLIT阶段？uop入队时？

流程：
1. 计算A/B地址
2. 生成TMU读请求（256B粒度）
3. L0 entry = 512B，需要2个flit
   - 如何处理地址对齐？
   - 如何匹配2个flit到同一entry？
4. 优先级：块内 > 块外
5. TMU返回 → 填充L0 Cache
6. 更新ISQ uop的src_ready
```

#### 4. MAC 阵列 issue 机制
```
当前假设：每拍最多1个uop发射

需要明确：
- MAC阵列是单issue还是多issue？
- 如果单issue，ISQ如何选择（优先级策略？）
- 如果多issue，带宽瓶颈在哪？
- 多个uop同时ready时的仲裁规则？
```

#### 5. ACC RMW 流水线
```
当前定义：9 cycles total
- 读取1KB：4 cycles (256 B/cy)
- 加法：1 cycle
- 写回1KB：4 cycles (256 B/cy)

关键问题：
- 这9 cycles能流水吗？
- 下一个uop能否在第5 cycle开始读？
- Bank冲突如何处理？
- 多个uop同时RMW同一slice如何互斥？
```

#### 6. FixPipe 并发度
```
当前：单issue流水线

问题：
- 能否同时处理多个slice？
- 如果单issue：128 slices × 11 cycles = 1408 cycles
- 吞吐量瓶颈：ACC读取256 B/cy，TileStore 256 B/cy

与matmul如何并行？
```

### 中优先级

#### 7. Cache miss 重试机制
- uop遇到cache miss后如何处理？
- 多个uop miss同一地址如何合并？

#### 8. 背压传播路径
```
ACC满 → ISQ不能分配新uop
ISQ满 → FSM不能生成新uop
TMU忙 → L0 Cache不能预取
TileStore满 → FixPipe阻塞

需要定义每个阶段的背压信号和握手协议
```

#### 9. Flush 处理细节
- 各stage如何响应flush？
- 哪些状态需保留（older）？
- 哪些状态需清除（younger）？
- ACC slice释放时机？

---

## 📊 架构示例（待验证）

### M=128, N=128, K=256 矩阵乘法

```
拆分：
M_tiles = 128/16 = 8
N_tiles = 128/16 = 8
K_tiles = 256/16 = 16

总uop数 = 8 × 8 × 16 = 1024 uop

单个输出位置[0,0]的计算：
uop[0,0,0]:  A[0,0]×B[0,0] → BufferC = result
uop[0,0,1]:  A[0,1]×B[1,0] → BufferC += result (依赖uop[0,0,0])
...
uop[0,0,15]: A[0,15]×B[15,0] → BufferC += result (依赖uop[0,0,14])
                              → ACC[slice_id] = BufferC (写入4 cycles)
```

### tmatmul.acc 流水线并行

```
第一个tmatmul：
Cycle 100: uop0[0,0,15] 完成 → ACC[slice_0_0] = BufferC
Cycle 105: uop0[1,1,15] 完成 → ACC[slice_1_1] = BufferC
...

第二个tmatmul.acc（无需等待）：
Cycle 105: uop1[0,0,0] 开始（依赖uop0[0,0,15]已满足）
Cycle 110: uop1[1,1,0] 开始（依赖uop0[1,1,15]已满足）
...

流水线重叠：约900 cycles
```

---

## 🔧 需要修改的其他文档

### 已完成

1. ✅ **datapath.md**（2026-06-09 完成）：
   - ✅ § 4 BufferC：删除 K_chunk
   - ✅ § 2 输入路径：明确 Prefetch Buffer 32 entries
   - ✅ § 6 输出路径：明确 TileStore Queue 8 entries
   - ✅ 删除重复的参数表

2. ✅ **isq.md**（2026-06-09 完成）：
   - ✅ 增加多链并发机制说明（§13）
   - ✅ 详细说明全局共享、静态分区、动态分区方案
   - ✅ 补充跨链发射仲裁策略

3. ✅ **l0cache.md**（2026-06-09 完成）：
   - ✅ 明确 512B entry 需要 2 个 TMU flit（§6.1）
   - ✅ 增加地址对齐和 flit 匹配逻辑
   - ✅ 更新 Prefetch Buffer 深度为 32 entries

4. ✅ **accumulator.md**（2026-06-09 完成）：
   - ✅ 增加 ACC 映射表详细结构（§3.2）
   - ✅ 增加 per-输出位置的 last_uop_id 记录
   - ✅ 补充细粒度依赖和流水线并行说明

5. ✅ **acccvt.md**（2026-06-09 完成）：
   - ✅ 明确 acccvtuop 与 matmul uop 的细粒度依赖（§3.2）
   - ✅ 删除旧的 K_chunk 提前 Wakeup 概念
   - ✅ 补充 per-slice 依赖和 Wakeup 示例

6. ✅ **diagrams/cube_architecture.md**（2026-06-09 完成）：
   - ✅ 增加 Tile Cmd Buffer 到顶层框图
   - ✅ 更新数据流图中的命令接收路径
   - ⏳ 修正 TMU 接口为 256B（待完成）
   - ⏳ 更新带宽表（待完成）

7. ✅ **README.md**（2026-06-09 完成）：
   - ✅ 更新文档状态表
   - ✅ 添加 CUBE_SPEC.md 和 WORK_IN_PROGRESS.md 条目
   - ⏳ 更新参数表（待完成）

---

## 📝 下一步建议

1. **先定义FSM状态机**（控制流核心）
2. **再明确ISQ多链并发机制**（调度核心）
3. **细化Prefetch流程**（性能关键）
4. **定义背压和流控**（正确性保证）
5. **绘制cycle-accurate时序图**（验证架构）

---

## 📂 相关文档路径

```text
docs/architecture/Janus/Cube/
├── README.md
├── architecture.md          ✅ 部分完成
├── datapath.md              ⏳ 待修改
├── isq.md                   ⏳ 待修改
├── l0cache.md               ⏳ 待修改
├── accumulator.md           ⏳ 待修改
├── acccvt.md                ⏳ 待修改
└── diagrams/
    └── cube_architecture.md ⏳ 待修改

docs/architecture/Janus/TMU/
└── TMU_SPEC_EN.md           📖 参考（Ring协议定义）
```

---

**恢复工作时**：从"FSM状态机定义"和"ISQ多链并发机制"开始。
