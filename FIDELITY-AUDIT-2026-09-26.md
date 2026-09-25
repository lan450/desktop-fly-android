# 数字果蝇（安卓 MVP）忠实性审计报告

- **审计时间**：2026-09-26 05:10 CST（UTC+8）
- **审计对象**：`~/Projects/desktop-fly-android`（交付版 APK `sha256 88b86cef…d250d4`，源码最新改动 04:51:42；项目不是 git 仓库，故以该 APK 哈希为基线）
- **参照实现**：`~/Projects/desktop-fly`（macOS 原版，Swift；`DesktopFly --simtest/--behaviortest/--locomotortest` 三套自带测试全部 PASS）
- **审计口径（用户指定）**：只看"项目本来打算模拟、却因为程序 bug 没模拟到"的内容。不看运行期崩溃，不看美术/观感，不看参考实现本身的建模取舍（翼展 4 Hz、DNa 左右符号、LIF 参数、肌肉激活映射等——这些被原样继承，不算移植 bug）。MVP 明确不做的（脑可视化、锹甲、多显示器、多果蝇）不计。
- **本报告不含**：真机/模拟器运行验证（用户已确认可运行，美术已定稿）。本次全部是 JVM 层差分与代码级比对。

---

## 0. 结论

**仿真核心（脑→索→腿→身体→本体感觉回灌）没有发现忠实性 bug。** 用与 Swift 测试完全相同的随机种子做逐毫秒差分，Android 的 Java 核心与 macOS 参考实现**数值完全一致**：`--simtest` 的 7 行指标逐字相同，`--locomotortest` 的 13 项因果试验与 5 项姿势连续性场景全部逐位相同。

问题集中在两处，**都不在仿真数学里**：

1. **测试脚手架有 bug**（B1–B3）：JVM 自测没有固定种子、当前约 **7%/次** 概率失败；而且它不是 `--simtest` 的移植（挂了神经索），其中一段"本体感觉"测试实际是空转。README 声称的"JVM 自测对照 `--simtest` 指标通过"**目前无法稳定复现**。
2. **接线/尺度**（B4–B6）：温度耦合被写死、真机尺寸下感官阈值没换算、行为级回归测试套件缺失。

结论一句话：**仿真是对的，验证和接线没做完。**

---

## 1. 方法与证据

### 1.1 差分方法（可复现）

macOS 参考实现的测试用 `TestRandom`（FNV-1a 标签 → LCG32）固定随机流，所以 `--simtest` 是**确定性**的。Android 侧用的是未固定种子的 `java.util.Random`，两边噪声实现不同，无法逐值比较。

因此我把 Swift 的 `TestRandom` 原样搬进一份内核副本（算法见附录 A），再用同样的调用顺序复跑参考协议的三个阶段，即可做**逐值**对照：

```sh
# 参考侧
cd ~/Projects/desktop-fly && ./DesktopFly --simtest && ./DesktopFly --behaviortest && ./DesktopFly --locomotortest

# Android 侧（当前状态，注意会随机挂）
cd ~/Projects/desktop-fly-android
javac -d build/jvm $(find src/com/maltjuice/fly/core test -name '*.java')
java -cp build/jvm com.maltjuice.fly.core.SelfTest
java -cp build/jvm com.maltjuice.fly.core.PerfTest
```

本次会话的差分脚手架（`SimTestMirror` / `LocomotorMirror` / `TransitionMirror` / `SenseLoop` / `PhoneScale` / `GfRate`）在 `/tmp/dfcheck`，属会话临时产物；附录 A、B 给出的算法与基线数字足以重建。

### 1.2 数值对照（Android Java 核心 vs Swift 参考）

| 协议 | 参考实现输出 | Java 核心输出 | |
|---|---|---|---|
| `--simtest` Phase1 自发 4 s | pop 5.21 Hz，LC 0.0，DNa L/R 6.2/7.8，MDN 4.1，**GF 0** | 完全相同 | ✅ |
| Phase2 突现 loom 0.4 s | LC 179.7 Hz，GF 2 发，首尖峰 **4 ms** | 完全相同 | ✅ |
| Phase3 20 s 步行（gaitDrive） | walk-on **42%**，groom-on 11%，DNp09 0.0–12.1 Hz，pop 5.7 | 43%（2001 采样差 1 个），其余相同 | ✅ |
| Phase3b 午休 scale 0.84 | walk-on 17% | 相同 | ✅ |
| Phase4 气流 1 s | GF 13 发 | 13 发 | ✅ |
| Phase5 左眼 loom | DNa L−R +0.1 → **−3.0** Hz，LC 31.2 | 完全相同 | ✅ |
| Phase6 点击探针 | GF cluster 有尖峰，DNg11 群放电 193 Hz | 相同 | ✅ |
| `--locomotortest` 12 项因果试验 | 六腿峰值 34.9,41.9,29.7,48.3,42.5,78.8；forward 10.643657；contacts [20,22,19,11,16,14]；yaw −1.248/−0.661/−1.459；MDN −12.66；sensory 34593，motor 12868/13605 | **逐值相同** | ✅ |
| 5 项 transition（理毛/睡眠/起降/惊转/窗台端点） | joint 0.112/0.102/0.120/0.117/0.071，toe 1.031/0.833/0.730/0.955/0.905，状态序列逐项 | **逐位相同** | ✅ |
| 温度耦合通路 | thermal tempo joint error 0.0 | 0.0 | ✅ |

（唯一差异：Phase3 的 walk-on 42% vs 43%，是 2001 个采样点里差 1 个；其余一切包括 GF 发数、群体放电率、DNa 左右差、六腿峰值频率、足端坐标都精确一致。相差量级远小于行为学意义。）

### 1.3 另外已验证的事实

- **数据是真的**：APK 内 `assets/data/circuit.json`、`locomotor_circuit.json` 与参考仓库（MaleCNS v1.0 / FlyWire v783 导出）**字节相同**（sha256 `da53640e…`、`8f76d940…`）。
- **交付包是最新的**：`~/Inbox/DesktopFly-MVP.apk` 与项目内 APK sha256 相同，且新于全部源码（无"改了代码没重打包"）。
- **回路分组**：loom L/R 162/152、GF 2、DNa 2/2、MDN 4、DNp09 2、DNg11 6、escW 6、ascend 27、sens 16 —— 与参考一致。
- **触觉转导层**（`FlyView`，参考实现无对应物，单独做端到端验证）：
  - 手指从左逼近：`loomL 0.63` vs `loomR 0.08` → 左右眼**没有搞反**，并触发 GF 起飞；
  - 快速滑动：airPuff 上升 → 起飞（风感觉通路有效）；
  - 点一下：惊飞（sensory 通路有效）；
  - 威胁固定在左侧 120 dp：净转 −4.2 rad；固定在右侧：+1.7 rad → **两个方向都远离威胁**（DNa→索→腿力学整条转向链方向正确）。

---

## 2. Bug 清单（按优先级，附验收标准）

### B1【代码 bug，1 行】`LIFSim.step()` 无条件解引用神经索 → 无索配置必崩

- **位置**：`src/com/maltjuice/fly/core/LIFSim.java:208` —— `locomotor.feedback = legFeedback;`
- **参考做法**：`Sim.swift:335` `locomotor?.feedback = legFeedback`（可选链）。
- **证据**（实测，未打补丁的原始源码）：

  ```
  UNPATCHED android core, no cord: java.lang.NullPointerException:
      Cannot assign field "feedback" because "this.locomotor" is null
  ```
- **影响**：App 里神经索永远存在，所以**用户看不到崩溃**；但这让 `LIFSim.java:236` 的"无索回退"（ascending 本体感觉输入）成为**不可达死代码**，并且 Java 核心**无法运行参考实现的 `--simtest` 配置**（那是不挂索的），这正是 B3 的根因之一。
- **修法**：`if (locomotor != null) locomotor.feedback = legFeedback;`
- **验收**：`new LIFSim(circuit, null)` 后 `sim.step(1)` 不抛异常；挂索路径行为不变（附录 B 的全部数字不变）。

### B2【测试 bug】JVM 自测没有固定随机种子 → 约 7% 概率失败

- **位置**：`src/com/maltjuice/fly/core/FlyMath.java:21`（`new Random()`）与 `:26–28`（`rnd/rndFloat/rndInt`）。
- **现象**：`java -cp build/jvm com.maltjuice.fly.core.SelfTest` 跑 30 次，**2 次** 以 `[exit code: 1]` 退出，失败行是

  ```
  FAIL GF silent at rest; spontaneous population active (5.39259 Hz, Swift: 5.21)
  ```
  （即断言 `SelfTest.java:57` 的 `gfSpont == 0`：自发 4 s 内 GF 偶发 1 发。这不是仿真错，是"没有种子 → 断言变抛硬币"。）
- **影响**：README「JVM 自测与 `DesktopFly --simtest` 指标对照通过」**不可稳定复现**；以后无法把自测当回归门禁。
- **修法**：把 `TestRandom` 搬进 `FlyMath`（算法见附录 A），并在每个测试开头 `FlyMath.reset("<测试名>")`（参考实现就是按测试名 reset 的）。
- **验收**：把种子接上后，`--simtest` 协议 7 行输出与附录 B 的 Swift 基线**逐字相同**（本次已实测可达，只有 Phase3 存在 1 个采样点的四舍五入差）；同一命令连跑 30 次结果完全一致、0 次失败。

### B3【测试 bug】`SelfTest` 不是 `--simtest` 的移植（挂索 + 缺两相）

- **位置**：`test/com/maltjuice/fly/core/SelfTest.java:36`（`new LIFSim(data.circuit, new LocomotorSim(data.locomotor))` —— 挂了索），对照 `main.swift:165`（`LIFSim(circuit:spikeBus:)` —— 不挂索）。
- **后果**：
  1. Phase 3 声称的 "20 s with walking proprioception"（`SelfTest.java:77`）**实际是空转**：`sim.gaitDrive/gaitPhase` 只在 `locomotor == null` 时进入（`LIFSim.java:236`），挂索后整段输入被丢弃 → 这就是 walk-on 29% vs 参考 42% 的原因（我去掉索后复跑得到 43%，与 Swift 一致）。
  2. Phase 1–5 期间 `sim.legFeedback` 从未被赋值（保持长度 0），索在这 40 s 里是开环的。
  3. 缺参考实现的两相：**午休 siesta**（scale 0.84 下 walk-drive 必须 >3%）与**左眼 loom 转向探针**。
- **修法**：拆成两支——`SelfTest`（挂索，验 App 真实闭环）+ `SimTestMirror`（不挂索，逐行对齐 `--simtest`，含 siesta 与左眼相）；后者能跑通的前提是先修 B1。
- **验收**：两支都通过，且 `SimTestMirror` 的输出与附录 B 基线逐字一致。

### B4【接线缺失】温度耦合被写死 → 变温动物/温度对运动速度的影响静默消失

- **位置**：`src/com/maltjuice/fly/app/FlyView.java:198` —— `s.tempo = 1;`
- **参考做法**：`main.swift:903` `s.tempo = tempo`，来源 `Environment.swift:91 thermalTempo()`（按 `ProcessInfo.thermalState` 返回 1.0/1.15/1.35/1.5）。`tempo` 会经 `motorDT = dt * motorTempo` 直接缩放腿动力学时间（`Fly.java:264–265`），并影响步行速度目标 `(14 + walkDrive*55) * tempo`。
- **证据**：通路本身是好的——我把 `--locomotortest` 的 "thermal tempo reaches active motor mechanics" 移植到 Java 后 **joint error 0.0000000000**。
- **影响**：Android 上机械时间永远 1.0×，即"热 Mac = 快果蝇"这条被静默去掉；README 的「与 macOS 版的差异」**没有**列这一条，所以倾向于判断为漏了，而不是有意。
- **修法**：接 `PowerManager.getCurrentThermalStatus()`（API 29+，需给 minSdk 24 加运行时分支并保持默认 1.0），或明确写进 README 差异表并删掉 `tempo` 相关代码以免误导。
- **验收**：真机/模拟器上 `s.tempo != 1` 时腿关节推进速度随之变化；或 README 明确声明该项未移植。

### B5【回归网缺失】Android 没有 `--behaviortest` / `--locomotortest` 的对应物

- **现状**：`test/` 只有 `SelfTest`（还不稳定）和 `PerfTest`。参考实现有 18 项行为/因果检查（六腿招募、持续行走、突触切断、运动神经元损毁、双侧转向扰动、MDN 倒退、本体感觉回灌、60/120 Hz 一致性、完整脑→体链、legacy 不得绕过肌肉静默、足端一致、温度、以及 5 项姿势连续性/状态序列）。
- **风险**：你已经踩过两次的正是这一类（翅膀画在身体下层导致尾部闪烁、膝盖 sin/cos 写反导致肢体不对称）——**这类回归在 Android 上目前没人拦**。
- **修法**：把 18 项里可移植的 17 项并进 `test/`（第 13 项 "rendered toes agree with physical feedback" 在 Java 侧两端同用一份 `fkFoot`，属恒真，可略）。需要给被测类加少量测试钩子：`LocomotorSim.meanRate(role, leg)` / `roleIndices(role)` / `silence(ids)`（参考实现本来就有 `meanRate`/`indices`，属忠实移植）、以及 `Fly` 暴露物理 `legDynamics.feedback`。
- **本次已实测**：这 17 项在 Android Java 核心上**全部通过**（数值见 1.2），所以移植是"把已验证的东西固化"，不是探索。
- **验收**：`test/` 里新增套件全绿，且故意在 `LegDynamics`/`Fly` 里制造一个符号错误时能红。

### B6【尺度未换算】感官与飞行几何沿用桌面绝对单位，手机上"距离衰减"名存实亡

- **位置**：`FlyView.java:161–178`（loom/puff）、`:251–258`（tap 半径 520 dp）、`Fly.java:150`（`EDGE_MARGIN` 50）、`:165`（逃逸"远"阈值 350 / 闲飞 260）、`:154,156`（窗台跳 ≥180、窗台最短 90 dp）。
- **量化的后果**（真机场景按 393×873 dp 算，见 `FlyView.onSizeChanged` 用 dp）：
  - **点按**：屏幕面积 343k dp²，而 520 dp 半径圆面积 849k dp²（≈2.5 倍屏幕）→ 只要果蝇不在屏幕远端，**任意位置点一下都会刺激到它**（屏幕中心到角落仅 479 dp，强度 0.079，仍高于 0.05 阈值）；只有果蝇在一端、手指在另一端（>520 dp）才完全不响应。参考实现的 1512 pt 窗口里，"点远处没反应"是成立的。
  - **loom/风的距离衰减**：`(1−dist/800)`、`(1−dist/500)` 在手机上几乎不会接近 0（跨越整屏也才 ~480 dp），感官场没有按屏幕归一化。
  - **逃逸目标**：必须 >350 dp 才被接受，而采样盒只有 292×752 dp，从中心看满足条件的面积约 16%；16 次重试后约 94% 至少命中一次，剩余约 6% 会退化为"直接用最后一次采样点"（这条退化路径参考实现也有，但桌面场地大得多，几乎不触发）。**此条为几何推算，未逐次测量。**
  - **测试场地不真实**：`SelfTest.java:120` 与 `PerfTest.java:27` 用的是 **1080×2160 dp**，任何手机都没有这个尺寸（约 7.5 倍面积），所以 JVM 行为测试**结构上无法**覆盖真机尺度问题。
- **我按真机尺寸实测过（不是推算）**：393×873 dp 场地跑 6 分钟闭环——行走 37% / 停 38% / 理毛 21% / 飞 4%，10 次自发起飞、2 次落上窗台；与桌面尺度（36/37/23/4%，9 次起飞）分布几乎一致。**结论：行为是健康的，这是调参尺度问题，不是坏掉。**
- **修法**（可选，二选一）：按屏幕尺寸归一化这些阈值（例如以 `min(W,H)` 为基准换算 130/500/520/800 dp 与 260/350 的逃逸距离），或明确把它们定义为"设备无关的绝对感官常数"并写进 README，同时把 JVM 测试场地改成真机尺寸。

---

## 3. 设计差异与"非本次移植引入"的观察（需要你决策，不算 bug）

1. **睡眠规则**（`FlyView.java:154`）：深夜（22:00–06:00）+ 用户 2 分钟未触碰 → 睡。参考实现是 `(idle>600s && 夜间) || idle>1800s`。README 已写明 2 分钟，属设计选择；两点提醒：① 判据是**用户闲置**而不是**果蝇静止**；② 真实果蝇"睡眠"的定义是连续静止 **≥5 分钟**。另外当前实现要求"至少触碰过一次"才可能睡（`lastTouchMs > 0`）。
2. **出厂配置下 body saccade 模块是死的**（参考实现同样如此，非移植引入）：`stepSaccade` 只在 `signals.legCommands == null` 时调用（`Fly.java:271`），而 walking 分支又把 `saccade` 清零（`:276`）；同理 `walkDrive` 调速（`:362`）与 `turnBias` 直接转向（`:365`）也只在无索时生效。结果是：代码里按 Geurten 2014 实测标定的 saccade 行走，在 Mac 和手机上都看不到，转向全部来自腿力学涌现（dart 逃跑转向是另一条路，实测正常）。如果想让手机上的果蝇真的"一段一段地扭"，这里就是要动的地方。
3. **未移植的感官**：键盘振动（substrate vibration）、新窗口出现（window loom）、菜单里的 Scare Flies / loomOverride。前两项在手机上无对应物，README 的差异表可补一句。
4. **注释笔误**：`BrainSignals.java:17` 把腿序写成 "RF LF RM RH LH"（漏 LM）；其余处（CLAUDE/README）都是 RF LF RM LM RH LH。
5. **死代码**：`Fly.pickNextState`（`:228`）、`stateTimer`、以及 `Fly.update` 没有 `signals == null` 分支——同一根源：Android 版删掉了 legacy/brainless 路径（App 里 `signals` 永远非空）。可留可删，但建议加一行注释说明"仅在有索 App 中使用"，避免以后误改。

---

## 4. 明确不在本次范围

- 参考实现本身的建模取舍：翼展 4 Hz（真实约 200 Hz，用残影盘表达）、DNa 左右符号约定、LIF 参数（tau 20 ms、阈值 1.0、噪声 p 0.0022、突触权 0.0008、跨标本 4 ms 抑制延迟）、肌肉激活映射、`ascend` 正弦本体感觉（挂索后本就不参与）。这些两边一致，且都是有意的模型选择。
- 渲染观感、配色、图标、构图（用户已确认美术定稿）；本次只检查"渲染是否忠实表达物理状态"，未发现问题（俯视腿=直线是几何必然、翅膀 z 序与残影方向已修、`scale/alt/shadow` 与参考公式一致）。
- 真机/模拟器运行验证与性能：`PerfTest` 的 worst frame 是 JVM 桌面数字，不代表手机 CPU/GPU，本次**未重跑 PerfTest**；另外我用真机尺寸（393×873 dp）跑了 6 分钟闭环，不崩、位置有界、状态分布健康（见 B6）。

---

## 5. 建议执行顺序（给实现者）

| 顺序 | 动作 | 验收 |
|---|---|---|
| 1 | B1：`LIFSim.java:208` 加 null 判断 | 无索 `step()` 不抛异常；附录 B 数字不变 |
| 2 | B2：`FlyMath` 接入 `TestRandom`（附录 A），各测试开头 reset | `SelfTest` 连跑 30 次 0 失败且输出完全一致 |
| 3 | B3：拆出 `SimTestMirror`（不挂索，逐行对齐 `--simtest`，补 siesta + 左眼 loom 相） | 与附录 B 基线逐字一致 |
| 4 | B5：移植 17 项行为/因果检查到 `test/` | 全绿；人为制造一个关节符号错误时能变红 |
| 5 | B4：温度耦合（接 thermal status，或写进 README 差异表并清理 `tempo`） | 二选一落地 |
| 6 | B6：决定尺度归一化 or 声明为绝对常数；把 JVM 测试场地改成真机尺寸（393×873） | 决策落地 + `PerfTest` 场地更新 |

**注意**：1–4 项都不需要改仿真数学，预期全部数字保持不变；任何一项改完若附录 B 的数字变了，说明改错了。

---

## 附录 A：`TestRandom`（Swift 原版算法，用于复现差分）

```swift
// Sim.swift:11-54
seed = 2166136261
for c in label.utf16 { seed = (seed ^ UInt32(c)) &* 16777619 }   // FNV-1a
state = seed
// 每个随机数：
next = state &* 1664525 &+ 1013904223                            // LCG32
state = next
u = Double(next) / 4_294_967_296                                 // [0,1)
// rnd(lo,hi)   = lo + (hi-lo) * Float(u)   （Float 精度！）
// rndInt(a...b) = a + min(b-a, Int(u * Double(b-a+1)))
```
`--simtest` 的标签是 `"desktop-fly-tests"`（seed `0x4305055b`）；`--behaviortest`/`--locomotortest` 里每个 `scenario`/`bodyCheck`/`transitionCheck` 用**自己的名字**做标签重新 reset。Java 侧建议实现成 `FlyMath.reset(String)` + `rnd/rndFloat/rndInt`，注意 `rnd` 保持 Float 语义、`rndInt` 用上面的 min 公式（不是 `nextInt`）。

## 附录 B：回归基线（Swift 参考实现的输出，改完必须逐字一致）

```
test RNG: desktop-fly-tests, seed 0x4305055b
circuit: 668 neurons | loom L/R: 162/152 | GF: 2 | DNa L/R: 2/2 | MDN: 4 | DNp09: 2 | DNg11: 6 | escW: 6 | ascend: 27 | sens: 16
spontaneous 4s: pop 5.21 Hz/neuron, LC 0.0 Hz, DNa02 L/R 6.2/7.8 Hz, MDN 4.1 Hz, GF spikes: 0
abrupt loom 0.4s: LC rate 179.7 Hz, GF spikes 2, first at 4 ms
behavior 20s: walk-drive on 42%, groom-drive on 11%, DNp09 0.0-12.1 Hz, pop 5.7 Hz
siesta 15s (scale 0.84): walk-drive on 17%
air puff 1s: GF spikes 13
left-eye loom: DNa L-R rate diff +0.1 -> -3.0 Hz, LC 31.2 Hz
click probes: GF cluster -> spike yes, DNg11 cluster -> groom rate 193 Hz
```

```
locomotortest（13 项全部 PASS）：
  quiet 0 spikes / recruits 34.9,41.9,29.7,48.3,42.5,78.8 / sustains forward 10.64, late path 60.55, contacts [20,22,19,11,16,14]
  synapses cut 0 / motor lesion 0.0 / steering straight -1.248, left -0.661, right -1.459 / MDN -12.66
  feedback sensory 34593, motor closed/open 12868/13605 / 60Hz forward 10.643657, spikes 12868
  app chain late path 81.26, contacts [57,69,19,36,40,22]
  legacy bypass: position (0.0,0.0), heading 0.0 / thermal joint error 0.0
  groom and resume: joint 0.112, toe 1.031, heading 0.015, pitch 0.000
  idle sleep and wake: joint 0.102, toe 0.833, heading 0.018, pitch 0.000
  flight and landing: joint 0.120, toe 0.730, heading 0.067, pitch 0.061
  nervous turn: joint 0.117, toe 0.955, heading 0.076, pitch 0.000
  ledge endpoint: joint 0.071, toe 0.905, heading 0.067, pitch 0.021; endpoint reversed yes, moved support takeoff yes, delta 0.000
```

## 附录 C：本次审计的已知局限

1. 未在真机/模拟器上跑（用户已确认可运行）；B6 的"逃逸目标退化率 6%"是几何推算，未逐次测量。
2. 差分脚手架是会话临时产物（`/tmp/dfcheck`），若已清理需按附录 A 重建；基线数字（附录 B）是永久有效的验收依据。
3. `PerfTest` 的帧时间数字来自 JVM 桌面，不代表手机 CPU/GPU；本次未做性能复测。
4. 触觉转导层没有参考实现可比，只做了"手势 → 预期行为"的端到端验证（见 1.3），未做逐值对照。
