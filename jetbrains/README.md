# AI Call Flow Navigator JetBrains 插件

该目录包含 Android Studio / JetBrains 插件实现。用户通过 Codex 的 `$ai-call-flow-navigator <topic>` 或 Claude Code 的 `/ai-call-flow-navigator <topic>` 显式发起分析；插件从本机 `file-ipc-v3` inbox 接收唯一的 `analysis-request`，使用当前 Project 的 PSI/UAST 生成静态 Call Flow，并把 XDebugger 运行时事件记录为独立的 Live `TraceRun` 叠加层。

安装插件、安装 Skill 与日常使用方法见[仓库 README](../README.md)；请求、回执与安全约束见 [Analysis Request File Protocol 3.0](../docs/call-flow-protocol.md)。

## 运行结构

```text
AiCallFlowStartupActivity
  → AiCallFlowProjectService
  → AnalysisRequestFileInboxService / AnalysisRequestParser
  → StaticCallFlowGenerationService
  → CurrentCallableResolver / UastCallFlowGenerator
  → CallFlowSessionService
      → CallFlowPlayback / CallFlowNavigator
      → LiveDebuggerTraceService
          → LiveTraceRecorder / TraceRun
  → CallFlowToolWindowPanel
```

`AnalysisRequestFileInboxService` 是 application service，负责 `file-ipc-v3` inbox、原子认领、回执和 consumer lock。`AiCallFlowProjectService`、静态生成、Call Flow session 与 Live Debugger 服务按 Android Studio Project 隔离。

插件根据 Project 自身根目录验证项目相对 `entry.path`，再用 1-based 行列和 source-level `entry.symbol` 解析唯一入口。`StaticCallFlowGenerationService` 在 smart mode 下执行不可变 PSI/UAST 分析，并把结果加载到 `CallFlowSessionService`。

`LiveDebuggerTraceService` 使用公开 XDebugger lifecycle/session API 观察已有及后续调试会话。每次静态图加载都会启动新的 `TraceRun`；pause、resume、frame change 和工具窗口发出的 Step 请求形成运行时事件，再由源码位置映射到静态节点。Live 记录与静态 Call Flow 分层保存，调试控制交给 Android Studio 原生 Debugger。

工具窗口分别提供 Static 与 Live 控件。Static 的 Previous/Forward/Next/Into/Over/Out 浏览静态图；Live 的 Previous Event/Next Event 浏览已记录 pause 样本，Pause/Resume/Step Into/Step Over/Step Out 控制当前绑定的 XDebugger session。

## 构建

需要 JDK 21 和 Android Studio。构建脚本识别 macOS、Linux、Windows 的常见安装目录；非标准位置使用 `ANDROID_STUDIO_PATH` 或 `-PandroidStudioPath=<Android Studio IDE home>`。

```bash
./gradlew clean test buildPlugin verifyPlugin
```

Windows PowerShell：

```powershell
./gradlew.bat clean test buildPlugin verifyPlugin
```

插件包输出到 `build/distributions/youngx-ai-call-flow-navigator-<version>.zip`。
