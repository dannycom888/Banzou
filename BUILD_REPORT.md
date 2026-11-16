# 分轨伴奏 v0.1.1（可行性样品版）构建报告

构建日期：2026-11-16（America/New_York）
规格来源：用户 2026-11-16 13:21 在「音乐分轨」主题批准的 v1 规格（平台=手机 App），
以及同日 13:48 的补充约束（中转式用法 + 导出/分享 + 一键清理中转副本）。
v0.1.0 为当日首个样品构建，已被本版（v0.1.1，补上中转两功能）取代，Release 以 v0.1.1 为准。

## 产物

| 项 | 值 |
|---|---|
| APK（工程根目录副本） | `~/workspace/banzou/Banzou-0.1.1.apk` |
| APK（Gradle 原始输出） | `app/build/outputs/apk/release/app-release.apk` |
| 大小 | 57,303,572 字节（约 54.6 MB） |
| SHA-256 | `8d1efaa11ff46ceca71fd7edd8a57f6be0d419d19b2126a32c5db30f69279602` |
| 包名 | `com.dannycom888.banzou` |
| 应用名 | 分轨伴奏 |
| versionName / versionCode | 0.1.1 / 2 |
| minSdk / targetSdk / compileSdk | 26 / 36 / 36 |
| ABI | arm64-v8a only（目标机 Note10+ 即 arm64；APK 因此瘦身） |
| 签名 | 与 MySing / MyLRC Auto / LrcEditor 同一把共享 keystore；`apksigner verify` 通过，证书 SHA-256 `1336ee0aefc229c3f0e6c025060ec0267825dbfc83f8799c3981dd74ea2befcc`，与 LrcEditor-1.0.0 逐字一致 |
| 权限清单 | **无 INTERNET 权限**（aapt 实查）。清单含 FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC / POST_NOTIFICATIONS / WAKE_LOCK（均为队列保活与通知所需），以及 Media3 清单合并带入的 ACCESS_NETWORK_STATE（仅读网络状态，不给联网能力，与 LrcEditor 同） |

APK 内容实查：`assets/UVR_MDXNET_9482.onnx`（29,704,738 字节）、
`lib/arm64-v8a/libonnxruntime.so`（19,347,616 字节）、
`lib/arm64-v8a/libbanzou_lame.so`（295,520 字节，4 个 JNI 导出符号用 llvm-nm 实查齐全）。

## 模型选型

| 项 | 值 |
|---|---|
| 模型 | **UVR_MDXNET_9482**（UVR 生态的 MDX-Net 人声模型，模型输出为人声谱，伴奏 = 原始混音 − 人声，与 UVR/sherpa-onnx 的用法一致） |
| 文件大小 | 29,704,738 字节（约 28.3 MiB）——几十 MB 量级的小模型，桌面级大模型（Inst_HQ 系列 60MB+、Roformer 数百 MB）未选 |
| 来源 | sherpa-onnx 官方模型发布：`https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/UVR_MDXNET_9482.onnx`；模型内嵌元数据注明其上游为 TRvlvr/model_repo（UVR 官方模型库） |
| 许可 | MDX-Net 架构与 UVR 工具链为 MIT（KUIELab / UVR 项目）；该权重为 UVR 社区公开发布、供免费使用的模型，权重本身未附单独的许可证文件——按社区惯例署名来源使用（本 App 为个人自用、非商业分发）。SHA-256：`9d78f8566fa8198065214ab628be1de966a500c57786695aa4b13e2b27a7727d`，MD5：`9376ca7dc89584a108edfe86e08754d5` |
| 输入规格（全部来自模型内嵌 metadata，实查） | 采样率 44100；n_fft 4096；Hann 窗 4096；hop 1024；dim_f 2048；dim_t 256（帧/段）；输入张量 `[batch, 4, 2048, 256]`（双声道 × 实/虚部）；opset 13 |
| 选型理由 | ①文件小、输入规格由内嵌元数据明确给出，不用猜；②sherpa-onnx（Apache-2.0）有同模型的完整端侧实现可对照，算法可复刻可验证；③9482 是 UVR 公认的人声分离常用模型，效果有口碑；④计算量（2048×256 输入）是 MDX 家族里偏小的一档，适合 Note10+ |

## 管线（App 内与 VM 验证共用同一套参数/算法）

MediaCodec 解码 MP3 → 44.1kHz 立体声 float32 PCM 临时文件（单声道复制为双声道；
非 44.1k 源走线性重采样慢路径）→ 按 sherpa-onnx UVR 算法分块：
外层 15 秒块（661,500 采样）+ 两侧各 44,100 采样 margin（边缘丢弃）→
块内按 257,024 采样步进切段、每段补零到 261,120 采样 → STFT（周期 Hann、
center reflect 填充）→ ONNX 推理 → iSTFT → 拼回 → 伴奏 = 混音 − 人声（时域）→
LAME 编码 MP3（CBR 192 kbps、44.1kHz、quality 2、关 VBR 标签）→ 临时文件 `.part`
→ 成功后改正式名。**输出 PCM 采样数与输入严格一致**（尾部截/补到原长，见下方实测）。

- 推理加速：先试 NNAPI 建 session，失败自动回退 CPU（再尝试 XNNPACK）；加速失败不闪退（代码 try/catch 实装，真机上哪条路生效待实测）。
- ORT 线程：intra-op 4 线程。
- MP3 编码：vendored **LAME 3.100** 源码（Debian 源码镜像取得的上游 tarball，
  LGPL-2.0-or-later）+ 手写最小 config.h + CMake/NDK 编译为 `libbanzou_lame.so`，
  JNI 桥 `lame_jni.c`。ffmpeg-kit 路线已排除（其官方二进制 2025 年从 Maven 下架）；
  jump3r（Java 版 LAME）已排除（其易用 API 依赖 javax.sound，Android 没有）。

## VM 管线验证（与 App 同模型、同 STFT 参数、同算法）

参考实现：`tools/sep_reference.py`（numpy + onnxruntime，复刻 sherpa-onnx 算法）。
素材：歌库副本 `~/workspace/karaoke-convert/library/extracted/music chinese boy/任贤齐 - 心太软.mp3`
（确切文件名即此；只读副本，未动原库）。

| 项 | 实测值 |
|---|---|
| 输入 | 15,302,112 采样 / 声道，346.987 秒，44.1kHz 立体声，源 MP3 约 203 kbps |
| 输出 PCM | **15,302,112 采样，346.987 秒——与输入采样级等长** |
| VM 耗时 | **154.7 秒（RTF 0.446）**——这是本 VM（1.5 共享核、ORT 2 线程）的耗时，**不是 Note10+ 耗时**；Note10+ 真实耗时待装机实测，本报告不编数字 |
| 分离 sanity 指标（前 60 秒） | 前奏无人声段（0–20s）：伴奏 RMS 与原曲相同、推得人声 RMS ≈ 0.001（没东西可去时不乱动）；人声段（30–60s）：推得人声 RMS 0.11–0.14，伴奏 RMS 保持 0.18 量级（音乐能量保留） |
| 输出 MP3（ffmpeg libmp3lame 192k 编码同一份伴奏 PCM） | 8,329,552 字节，时长 346.987 秒；成品留存于 `~/workspace/任贤齐 - 心太软（伴奏）.mp3`（未进 git 仓库） |
| 主机 LAME 实编（与 App 完全相同的 vendored 源码 + config.h + 参数） | 15,302,112 帧全部编完，输出合法 MP3：192 kbps CBR、44.1kHz 立体声、8,328,881 字节；ffprobe 容器时长 347.037 秒（比 PCM 长约 50ms，是 MP3 编码延迟+帧填充的固有现象，卡拉OK切换无感知） |

## 单元测试

`:app:testDebugUnitTest` —— **25 个测试，0 失败，0 错误，0 跳过**（数自 test-results XML 汇总）。

| 测试类 | 数 | 覆盖 |
|---|---|---|
| NamingTest | 8 | 生成名（含真实中文名《任贤齐 - 心太软》）、伴奏文件自识别与排除、临时名、已有伴奏判定、大写扩展名、拒绝给伴奏再做伴奏 |
| SegmentPlannerTest | 4 | 12 种长度（含《心太软》精确采样数 15,302,112）下计划输出长度与输入严格相等；块拼接无缝覆盖；短歌单块；空歌 |
| StftTest | 2 | 完整模型段（261,120 采样）STFT→iSTFT 往返误差 < 0.01；静音往返为静音 |
| PublishFlowTest | 5 | 用真实中文文件名走完 生产同款 发布流程（java.io.File 存储）：改名成功路径、改名被拒走复制路径、写入失败后临时与正式文件都不留、已有伴奏拒绝覆盖、陈旧临时文件先清；并验证原文件字节不动 |
| TransitRegistryTest | 6 | 中转登记的编码/解码往返（含中文名与 URI）、容错；`belongsToTree` 归属判定：中转树内（含子文件夹）判真、**车上 Music 库（自有树 ID）判假**、他树文件判假 |

## 非 ASCII 端到端（按 non_ascii_end_to_end_testing 技能）

- **真实传输已测**：VM 上用真实中文文件名《任贤齐 - 心太软.mp3》走完 解码→分轨→编码
  全链路，产物为正确命名的「任贤齐 - 心太软（伴奏）.mp3」，文件名逐字无乱码。
- **单元层已测**：PublishFlowTest 用中文名在真实文件系统上完成 选名→临时→改名/复制→成品
  全流程（见上表）。
- **未测（如实）**：Android 真机上 SAF 中文文件夹里的 选文件→处理→生成 全链路，
  要等用户装机后跑《心太软》实测确认——本版定位即如此，不报「已修好/已验好」。

## 功能对照规格

- ✅ SAF 选歌库文件夹（持久读写授权），递归列 MP3：排除 `（伴奏）` 文件本身与 `.part`
  临时文件；标「已有伴奏」；支持搜索；可单选/多选/全选未做的
- ✅ 队列：前台服务（dataSync 类型）+ WakeLock 保活、可息屏、通知 + App 内双进度
  （每首阶段/百分比 + 总进度）、顺序逐首不并发、可取消全部、失败列表可单独重试
- ✅ 铁律：原文件只读；同目录同名加「（伴奏）」；人声轨从不落盘；已有伴奏跳过不覆盖；
  `.part` 临时文件成功才改名，失败/取消自动清理（Publish 流程 + 单元测试覆盖）
- ✅ 试听：处理完点「试听」，原唱/伴奏来回切播，切时保持进度（ExoPlayer）
- ✅ 发热/耗电提示在队列面板明示（建议插电跑、可息屏、勿强关）
- ✅ 中文 UI、无 INTERNET、模型内置离线、同共享 keystore、GitHub Release 分发
- ✅ **中转式用法（2026-11-16 13:48 补充约束）**：手机只是加工台，不是仓库——
  少量歌曲拷进手机中转文件夹 → 在 App 里选这个文件夹分轨 → 伴奏导出到 Shield
  硬盘（与原歌同文件夹配对）→ 清理手机上的中转副本腾空间。两文件方法本身不变，
  车上 Music 原库始终不碰
- ✅ **导出/分享伴奏**：每首已完成项有「导出」按钮，队列面板有「导出全部伴奏」，
  走系统分享（ACTION_SEND / SEND_MULTIPLE + 读权限授予），可发送到文件管理器、
  云盘、聊天等任意目标，由用户把伴奏落到 Shield 硬盘对应文件夹
- ✅ **一键清理中转副本（带硬防护）**：处理完成时 App 把这首的源副本 + 伴奏副本
  登记进「中转登记表」（持久化，跨重启可清）。清理卡片只列**登记过且 URI 归属
  于当前所选中转文件夹树**的记录；点清理先弹确认框，逐个列明将删的全部文件、
  所在文件夹名与树路径，并明示「先确认伴奏已导出」「原始歌库（Music）不在此列」；
  确认后才逐个删除并回报成功/失败数。设计上杜绝误清原库：未被本 App 处理过的
  文件永远进不了登记表，他树（包括 Music 库自有树 ID）的文件永远通不过归属判定
  （TransitRegistryTest 覆盖）

## 已知限制 / 待真机验证（Note10+）

- **单首真实耗时未知**：装机后先跑《心太软》一首计时，这是本版的首要验证项。
- NNAPI 在 Note10+（Exynos 9825）上是否生效、还是回退 CPU/XNNPACK，待实测；
  若 NNAPI 建 session 成功但算错（个别驱动有先例），症状会是伴奏明显不对，需回报后关掉。
- MediaCodec 在 Note10+ 上输出按 PCM16 处理（目标机解码器如此）；若个别文件解出
  PCM float 格式会解码错误并在该首报失败（不会静默出坏文件之外的后果——失败即清理）。
- MP3 容器时长比 PCM 长约 50ms（编码延迟固有），与原文件切换时无感知差异预期，
  待耳朵实测。
- 只编 arm64-v8a：装到 32 位老设备会装不上（不在目标范围内）。
- 分轨效果（人声残留/音乐损伤程度）只能耳朵验：VM 指标只证明管线行为正确，
  不证明好听。先验《心太软》一首，再决定是否批量、是否换更大模型。
- 中转全流程待真机走一遍：拷一首进中转文件夹 → 分轨 → 用「导出」把伴奏发送/
  另存到 Shield 硬盘与原歌同文件夹 → 回 App 点「一键清理中转副本」，核对确认框
  列出的文件与路径无误、删后手机中转文件夹已空、且 Music 原库文件完好无损。
- 系统分享面板在 Note10+ 上能否顺利把 SAF 文件发给常用目标（如文件管理器/
  Google Drive），待实测；若某个目标拿不到读权限会表现为对方打开失败，回报后改路。

## 本软件由 Muse 协助制作
