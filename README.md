# 分轨伴奏 Banzou

Android 手机 App：用 AI 人声分离（MDX-Net）把 MP3 预制成伴奏文件，供家里的
MySing（Shield 电视卡拉OK）唱歌用。

- 选一次歌库文件夹（SAF），勾选歌曲，后台排队逐首分轨
- 每首只多生成一个文件：同文件夹、同名加「（伴奏）」的 MP3；原文件只读、绝不改动
- 分出来的人声轨不保留；已有伴奏的跳过、不覆盖
- 先写临时文件，成功才改成正式名，失败/中断不留半成品
- 模型内置、离线运行，**无 INTERNET 权限**
- 处理完可在 App 里原唱/伴奏来回切播对比试听

引擎：ONNX Runtime for Android + UVR_MDXNET_9482（MDX-Net 小模型，29.7 MB）；
MP3 编码：vendored LAME 3.100（JNI）。详见 `BUILD_REPORT.md`。

安装：下载 Release 里的 APK，用 KingInstaller 安装（与 MySing / MyLRC Auto /
LRC 编辑器同一把签名，可共存）。

本软件由 Muse 协助制作。
