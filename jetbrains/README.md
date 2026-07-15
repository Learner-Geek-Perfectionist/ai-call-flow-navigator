# AI Call Flow Navigator JetBrains 插件

AI Call Flow Navigator 接收本地 AI 生成的完整源码阅读链，在 Android Studio 的原生
`Call Flow` 工具窗口中显示节点、说明和分支，并提供调试式源码导航。

GitHub 仓库：[Learner-Geek-Perfectionist/ai-call-flow-navigator](https://github.com/Learner-Geek-Perfectionist/ai-call-flow-navigator)

## 从 GitHub Release 安装

1. 打开 [最新 GitHub Release](https://github.com/Learner-Geek-Perfectionist/ai-call-flow-navigator/releases/latest)，下载 `youngx-ai-call-flow-navigator-<version>.zip`。
2. 在 Android Studio 打开 `Settings/Preferences → Plugins`。
3. 点击齿轮菜单并选择 `Install Plugin from Disk...`，选中下载的 ZIP。
4. 按提示重启 Android Studio，然后打开需要阅读的项目。

插件安装后自动绑定当前 Android Studio Project，并通过系统临时目录中的 `file-ipc-v2`
接收 Call Flow。

## 配套 AI Skill

仓库中的同一套 canonical `ai-call-flow-navigator` Skill 内容可以同时用于 Codex 和 Claude
Code。macOS/Linux 运行 `./scripts/install-skill.sh`，Windows PowerShell 运行
`./scripts/install-skill.ps1`；脚本会写入各平台的显式调用元数据，并同时安装到：

```text
~/.agents/skills/ai-call-flow-navigator
~/.claude/skills/ai-call-flow-navigator
```

安装或更新 Skill 后先新开一个 AI 会话。在 Android Studio 实际打开的项目根目录启动 AI；
monorepo 中 IDE 若只打开 `android/` 子目录，终端也必须进入该子目录。Skill 仅由用户显式
触发：Codex 使用 `$ai-call-flow-navigator <topic>`，Claude Code 使用
`/ai-call-flow-navigator <topic>`。例如 `<topic>` 可写为“从 MainActivity.onCreate 开始分析
调用链”。安装脚本和源码位于
[AI Call Flow Navigator 仓库](https://github.com/Learner-Geek-Perfectionist/ai-call-flow-navigator)。
Skill 投递器使用 Python 3.8+ 标准库；Windows 可使用 `py -3`。原子投递支持标准 APFS、
ext4、NTFS 临时文件系统。

## 零配置使用

“零配置”指无需配置端口、Token 或 `projectRoot`；Skill 仍由上面的命令显式启动。

1. 安装插件。
2. 在 Android Studio 中打开源码项目。
3. 在同一台电脑上显式调用 Skill，让 AI 分析项目并投递 Call Flow JSON。

插件自动使用当前 IDE 项目根目录并监听本机系统临时交换目录。

插件 ID 是 `com.youngx.aicallflow`，Vendor 是 `YoungX`。

收到并校验 Call Flow 后，插件自动打开工具窗口并定位入口节点。Previous/Forward 按实际
访问历史移动，Next/Into/Over/Out 按 AI 提供的边移动。协议与 JSON 格式见
[Call Flow File Protocol 2.0](../docs/call-flow-protocol.md)。

## Call Flow 导航按钮

| 按钮 | 行为 | 与 Debug 的对应关系 |
| --- | --- | --- |
| `Previous` | 回到上一个实际访问过的节点；节点列表跳转和按钮导航都会写入这份历史 | 后退 |
| `Forward` | 执行 `Previous` 后回到较新的历史节点；选择新路径后会清空 Forward 历史 | 前进 |
| `Next` | 获取当前节点的全部后续路径；一条时直接前进，多条时弹出候选选择 | 按 AI 调用链继续 |
| `Into` | 只显示并沿 `step_into` 边进入函数实现 | Step Into |
| `Over` | 只显示并沿 `step_over` 边跳过当前调用的内部步骤 | Step Over |
| `Out` | 只显示并沿 `step_out` 边返回调用方 | Step Out |

按钮使用 AI 在 Call Flow 中明确提交的边，并在存在相应历史或路径时启用。
`Next` 包含所有出边，因此也负责展示条件分支、
回调、异步、返回以及 Into/Over/Out 路径；存在多个候选时，插件会要求用户选择。

例如在 `setContent` 节点，AI 可以同时提供一条 `step_over` 边前往 `onCreate` 返回节点，
以及一条 `callback` 边进入 Compose 根内容。此时 `Over` 只走前者，`Next` 会展示两条路径。

## 本地文件交换

插件和 AI 通过当前操作系统的标准临时目录交换文件：

```text
<system-temp>/youngx-ai-call-flow-navigator/file-ipc-v2/
  .consumer.lock
  inbox/
  processing/
  receipts/
```

AI 生成 Call Flow 语义和项目相对 `location.path`。配套 Skill 的 `publish_call_flow.py` 会
校验源码位置、添加 `_delivery` 中的唯一 `requestId` 与有效期，并使用文件协议 `2.0` 投递；
Call Flow 顶层内容 `version` 仍是 `1.0`。发布器先完整写入
`inbox/.request-<UUID>.tmp`，刷新后以 no-replace 语义原子创建
`inbox/request-<UUID>.json`。插件处理最终 JSON，并把它绑定到当前 Android Studio Project
的 canonical root。

交换路径固定且可预测，并校验 owner、权限和文件类型。`.consumer.lock` 协调 Android
Studio 消费进程。

插件把认领的请求移入 `processing`，校验 UTF-8、投递信息和 Call Flow 结构。通过后，原始
JSON 会先持久化到 IDE JVM 的系统临时目录，再交给工具窗口；结果写入
`receipts/receipt-<UUID>.json`。AI 等待 `accepted` 或 `rejected` 回执。

`<system-temp>` 来自 Android Studio JVM 的 `java.io.tmpdir`：macOS 通常与 `$TMPDIR`
一致，Linux 通常为 `/tmp`，Windows 通常与 `%TEMP%` 一致；显式设置
`-Djava.io.tmpdir` 时，AI 使用相同目录。

已接受文件的布局为：

```text
<system-temp>/youngx-ai-call-flow-navigator-archive[-user[-<uid>]-<random>]/call-flows/
  project-<base64url-sha256-project-root>/call-flow-<time>-<random>.json
```

每个项目以最近 50 条为保留目标。交换目录和已接受文件均位于项目目录之外，源码工作树
保持整洁。

## 本地数据安全

- AI 与 Android Studio 在同一台机器上使用同一个系统临时目录。
- POSIX 交换目录使用 `0700`、文件使用 `0600`；Windows 校验 owner，并使用用户 Profile
  与 `%TEMP%` 的系统 ACL。
- 请求、回执和落盘 JSON 按项目敏感数据管理。
- 源码位置使用当前项目根目录下的相对路径。

## 构建

需要 JDK 21（可直接使用 Android Studio 自带的 JBR）。构建脚本会尝试识别 macOS、Linux
和 Windows 的常见安装目录；非标准安装位置可设置 `ANDROID_STUDIO_PATH`，或传入
`-PandroidStudioPath=<Android Studio IDE home>`。macOS 的 IDE home 是 `.app/Contents`，
Linux/Windows 是 Android Studio 安装根目录。

```bash
./gradlew clean test buildPlugin verifyPlugin
```

Windows PowerShell 使用 `./gradlew.bat clean test buildPlugin verifyPlugin`。

插件包生成在：

```text
build/distributions/youngx-ai-call-flow-navigator-0.6.3.zip
```
