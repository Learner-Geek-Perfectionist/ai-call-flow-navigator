# AI Call Flow Navigator JetBrains 插件

这个目录包含 Android Studio / JetBrains 插件实现。插件自动绑定当前打开的 Project，从本机 `file-ipc-v2` inbox 接收 AI 生成的 Call Flow，在原生 `Call Flow` 工具窗口中提供源码定位和 Previous、Forward、Next、Into、Over、Out 导航。

安装插件、安装 Codex/Claude Code Skill 和日常使用方法见[仓库 README](../README.md)；文件交换与 JSON schema 见 [Call Flow File Protocol 2.0](../docs/call-flow-protocol.md)。

## 运行结构

```text
AiCallFlowStartupActivity
  → AiCallFlowProjectService
  → CallFlowFileInboxService
  → CallFlowDeliveryParser / CallFlowParser
  → CallFlowFileStore
  → CallFlowSessionService
  → CallFlowPlayback / CallFlowNavigator
  → CallFlowToolWindowPanel
```

`CallFlowFileInboxService` 是 application service，负责 inbox、原子认领、回执和 consumer lock。其余服务按 Android Studio Project 隔离；`AiCallFlowProjectService` 提供 IDE 实际打开的 canonical project root。

## 构建

需要 JDK 21 和 Android Studio。构建脚本识别 macOS、Linux、Windows 的常见安装目录；非标准位置使用 `ANDROID_STUDIO_PATH` 或 `-PandroidStudioPath=<Android Studio IDE home>`。

```bash
./gradlew clean test buildPlugin verifyPlugin
```

Windows PowerShell 使用：

```powershell
./gradlew.bat clean test buildPlugin verifyPlugin
```

插件包输出到 `build/distributions/youngx-ai-call-flow-navigator-<version>.zip`。
