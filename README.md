# AI Call Flow Navigator

AI Call Flow Navigator 以显式 AI Skill 作为唯一分析入口。Codex 或 Claude Code 先在本地工作树中定位用户主题对应的源码入口，再通过 File IPC v3 向 Android Studio 发送一个精简的 `analysis-request`。插件使用 PSI/UAST 生成不可变静态 Call Flow，并把已有或后续 Android Studio Debugger 会话记录为独立的 Live `TraceRun` 叠加层。

静态图回答“源码可能怎样执行”，Live Trace 回答“本次调试实际停在哪里”。两层共享源码导航和节点映射，但运行时事件不会改写静态图，也不会替代 Android Studio 原生 Debugger。

GitHub 仓库：[Learner-Geek-Perfectionist/ai-call-flow-navigator](https://github.com/Learner-Geek-Perfectionist/ai-call-flow-navigator)

## 最终架构

```text
Codex / Claude Code
  → 显式调用 ai-call-flow-navigator Skill
  → AI 定位 entry(path, line, column, symbol)
  → publish_analysis_request.py
  → file-ipc-v3 analysis-request
  → Android Studio 当前 Project
      → PSI/UAST 静态 Call Flow
      → XDebugger Live TraceRun 叠加
      → Call Flow 工具窗口与源码导航
```

职责边界：

- AI 负责定位唯一入口并保留用户 `topic`。
- Android Studio 使用当前 Project 的 PSI/UAST、索引和引用解析生成静态图。
- Live Debugger 监听已有与后续 XDebugger session，记录 pause、resume、frame change 和 Step 请求，并把可匹配位置叠加到静态节点。
- `TraceRun` 保存本次调试事件、节点命中次数与匹配置信度，并与静态播放历史分别维护。
- 项目根由 Android Studio Project 管理，IPC 传递项目相对入口。

## 安装

### Android Studio 插件

1. 打开项目的[最新 GitHub Release](https://github.com/Learner-Geek-Perfectionist/ai-call-flow-navigator/releases/latest)，下载 `youngx-ai-call-flow-navigator-<version>.zip`。
2. 在 Android Studio 打开 `Settings/Preferences → Plugins`。
3. 点击齿轮菜单，选择 `Install Plugin from Disk...`，选中下载的 ZIP。
4. 按提示重启 Android Studio，然后打开需要分析的源码项目。

插件 ID 为 `com.youngx.aicallflow`，Vendor 为 `YoungX`。

### Codex 与 Claude Code Skill

仓库中的 `skills/ai-call-flow-navigator` 是两个 AI 客户端共用的 canonical Skill。安装脚本会复制完整 Skill，并为各客户端写入显式调用元数据：

```text
~/.agents/skills/ai-call-flow-navigator
~/.claude/skills/ai-call-flow-navigator
```

macOS/Linux：

```bash
git clone https://github.com/Learner-Geek-Perfectionist/ai-call-flow-navigator.git
cd ai-call-flow-navigator
./scripts/install-skill.sh
```

Windows PowerShell：

```powershell
git clone https://github.com/Learner-Geek-Perfectionist/ai-call-flow-navigator.git
cd ai-call-flow-navigator
./scripts/install-skill.ps1
```

安装或更新 Skill 后，新开一个 Codex 或 Claude Code 会话。在 Android Studio 实际打开的项目根目录中启动 AI；如果 Android Studio 打开 monorepo 中的 `android/` 子目录，终端也应从该子目录启动。

## 唯一使用入口

Skill 不会隐式触发。只有用户主动输入以下命令时才运行：

- Codex：`$ai-call-flow-navigator <topic>`
- Claude Code：`/ai-call-flow-navigator <topic>`

例如：

```text
$ai-call-flow-navigator 分析登录按钮到首页的执行路径
/ai-call-flow-navigator 分析 MainActivity.onCreate 到首屏渲染
```

`<topic>` 用来描述业务目标、入口线索或分析范围。AI 会检查源码并把主题解析到唯一 Java/Kotlin 声明；仍有多个合理入口时，AI 会先询问用户，不会猜测。

一次完整使用流程：

1. 在 Android Studio 中打开源码项目，并确保插件已启用。
2. 在同一项目根目录显式调用 Skill。
3. AI 定位入口并投递 `analysis-request`。
4. 插件在索引可用后生成静态 Call Flow，打开工具窗口并定位入口。
5. 如需运行时轨迹，使用 Android Studio 正常启动或连接 Debugger；插件会自动绑定合适的 XDebugger session，并开始记录当前 `TraceRun`。

## 静态 Call Flow

插件从请求的 `entry.path`、1-based `line`/`column` 和 source-level `symbol` 解析入口，再使用 PSI/UAST 生成项目代码范围内的静态图。

静态图包含：

- 精确源码位置与符号导航。
- 项目内函数调用、调用方 continuation、分支、返回和 callback。
- 可以进入源码的调用对应 `Into`，同一调用方 continuation 对应 `Over`，正常出口对应 `Out`。
- `Into` 只指向能够解析到的项目源码声明或 callback body。
- `ComponentActivity.setContent(content)`、`MaterialTheme(content)` 和 `Scaffold(content)` 等 Compose callback 的 lambda 入口。

静态图描述代码中的可能路径；本次运行实际经过的路径由 Live Trace 展示。

### Static 控件

| 控件 | 行为 |
| --- | --- |
| `Previous` | 回到上一个实际访问过的静态节点 |
| `Forward` | 执行 `Previous` 后沿静态访问历史前进 |
| `Next` | 查看当前节点的全部静态出边 |
| `Into` | 沿 `step_into` 进入项目源码或 callback body |
| `Over` | 沿 `step_over` 跳到调用方 continuation |
| `Out` | 沿 `step_out` 返回调用方 |

节点列表支持方向键选择、`Enter` 导航和鼠标双击。Static 历史只表示用户浏览静态图的顺序，不控制正在运行的程序。

## Live Debugger TraceRun

静态图加载后，插件进入等待 Debugger session 的状态。用户通过 Android Studio 的标准 Run/Debug 配置启动或连接应用，插件随后自动绑定会话。

绑定 session 后，Live 层记录：

- session attach、stop、pause 与 resume。
- 当前 stack frame/source position 变化。
- 从工具窗口发出的 Pause、Resume、Step Into、Step Over 与 Step Out 请求。
- 运行时位置映射到静态节点后的命中次数、edge 语义和匹配置信度。

每次加载新的静态图都会创建新的 `TraceRun`。静态 `CallFlow` 保持不变，Live 事件作为叠加层展示；没有可信静态匹配的位置仍可保留为运行时样本。

### Live 控件

| 控件 | 行为 |
| --- | --- |
| `Previous Event` | 查看上一个已记录的 pause 样本，不执行反向调试 |
| `Next Event` | 查看下一个已记录的 pause 样本，不恢复程序 |
| `Pause` | 请求当前绑定的 Debugger session 暂停 |
| `Resume` | 恢复当前绑定的 Debugger session |
| `Step Into` | 调用 Android Studio XDebugger Step Into |
| `Step Over` | 调用 Android Studio XDebugger Step Over |
| `Step Out` | 调用 Android Studio XDebugger Step Out |

Live 的 Previous/Next 移动 TraceRun 查看光标；Static 的 Previous/Forward 管理静态浏览历史，程序执行方向继续由 Android Studio Debugger 控制。

## File IPC v3

Skill 自带的 `publish_analysis_request.py` 使用 Python 3.8+ 标准库。它验证入口相对路径、源码行列和精确 schema，注入 `_delivery.version = "3.0"`，再写入当前用户的系统临时目录：

请求与回执按完整 UTF-8 JSON 读取，协议支持任意文件大小，实际容量由本机存储与进程内存决定。

```text
<system-temp>/youngx-ai-call-flow-navigator/file-ipc-v3/
  .consumer.lock
  inbox/
  processing/
  receipts/
```

业务 payload 只有一种：

```json
{
  "version": "1.0",
  "type": "analysis-request",
  "topic": "分析登录按钮到首页的执行路径",
  "entry": {
    "path": "app/src/main/java/com/example/LoginFragment.kt",
    "line": 42,
    "column": 5,
    "symbol": "com.example.LoginFragment.onLoginClick"
  },
  "strategy": {
    "mode": "static-and-live",
    "scope": "project-code"
  }
}
```

Android Studio Project 提供项目根；发布器在本机使用当前工作目录验证 `entry.path`。多个已打开项目同时匹配入口时，回执状态为 `AMBIGUOUS_PROJECT`。

发布器使用私有临时文件、exclusive-create 和 hard-link no-replace 语义原子发布 `request-<UUID>.json`。插件认领后生成静态图，并写入同一 `requestId` 的 `accepted` 或 `rejected` 回执。成功回执包含生成的节点数、边数和入口节点 ID。

完整格式、安全校验、超时与错误码见 [Analysis Request File Protocol 3.0](docs/call-flow-protocol.md)。

## 本地数据安全

- AI、发布器与 Android Studio 在同一台机器、同一操作系统用户下通信。
- POSIX 交换目录使用 `0700`，请求与回执使用 `0600`；Windows 校验 owner，并使用当前用户临时目录 ACL。
- 交换文件位于源码仓库和 `.idea` 之外。
- 请求包含用户 topic、项目相对源码路径和符号，应按项目敏感数据管理。
- IPC 使用项目相对入口和系统临时文件完成本机通信。

## 项目结构

```text
ai-call-flow-navigator/
  jetbrains/   Android Studio 插件、PSI/UAST 静态图与 Live TraceRun
  skills/      Codex 与 Claude Code 共用的 canonical Skill
  scripts/     Skill 安装脚本
  tests/       Skill 安装器与 Python 发布器回归测试
  docs/        File IPC v3 analysis-request 协议
```

## 构建

JetBrains 插件构建需要 JDK 21 和 Android Studio。脚本识别 macOS、Linux、Windows 的常见安装目录，也可通过 `ANDROID_STUDIO_PATH` 或 `-PandroidStudioPath=<Android Studio IDE home>` 指定。

```bash
python3 -m unittest discover -s tests -v

cd jetbrains
./gradlew clean test buildPlugin verifyPlugin
```

产物位置：

```text
jetbrains/build/distributions/youngx-ai-call-flow-navigator-<version>.zip
```

## 许可证

MIT
