# AI Call Flow Navigator

AI Call Flow Navigator 把本地 AI 分析出的源码调用链加载到 Android Studio，并在原生
`Call Flow` 工具窗口中提供精确行列跳转、Previous/Forward 历史以及 Next、Into、Over、
Out 导航。

GitHub 仓库：[Learner-Geek-Perfectionist/ai-call-flow-navigator](https://github.com/Learner-Geek-Perfectionist/ai-call-flow-navigator)

## 安装

### Android Studio 插件

1. 打开项目的 [最新 GitHub Release](https://github.com/Learner-Geek-Perfectionist/ai-call-flow-navigator/releases/latest)，下载 `youngx-ai-call-flow-navigator-<version>.zip`。
2. 在 Android Studio 打开 `Settings/Preferences → Plugins`。
3. 点击齿轮菜单，选择 `Install Plugin from Disk...`，选中下载的 ZIP。
4. 按提示重启 Android Studio，然后打开需要阅读的源码项目。

插件安装后自动绑定 Android Studio 当前项目，并通过系统临时目录中的 `file-ipc-v2`
接收 Call Flow。

### Codex 与 Claude Code Skill

仓库提供同一份 canonical `ai-call-flow-navigator` Skill。操作说明使用中文，Call Flow、JSON、
edge kind 等协议名称保留英文。安装脚本会同时把它安装到：

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

安装或更新 Skill 后新开一个 Codex/Claude Code 会话。在 Android Studio 实际打开的项目根
目录中启动 AI，并这样调用。若是 monorepo 且 Android Studio 打开其中的 `android/`
子目录，终端便从该子目录启动：

- Codex：`$ai-call-flow-navigator 从 MainActivity.onCreate 开始分析调用链`
- Claude Code：`/ai-call-flow-navigator 从 MainActivity.onCreate 开始分析调用链`

两个入口共享同一套 Skill 规范、提示词和 `file-ipc-v2` 协议。Skill 投递器使用 Python
3.8+ 标准库，Windows 可通过 `py -3` 运行。原子投递支持标准 APFS、ext4、NTFS 临时文件
系统。

## 零配置 Call Flow

正常使用只有三步：

1. 安装 AI Call Flow Navigator 插件。
2. 在 Android Studio 中打开源码项目。
3. 在同一台电脑上，让 AI 分析当前项目并生成、投递 Call Flow JSON。

插件自动使用 Android Studio 当前打开的项目。收到有效文件后，插件会打开 `Call Flow`
工具窗口并定位入口节点。

插件 ID 是 `com.youngx.aicallflow`，Vendor 显示为 `YoungX`。

### Call Flow 导航按钮

`Previous`/`Forward` 浏览用户已经访问过的节点；`Next`/`Into`/`Over`/`Out` 沿着 AI
提交的调用链边移动。

| 按钮 | 行为 | 与 Debug 的对应关系 |
| --- | --- | --- |
| `Previous` | 回到上一个实际访问过的节点；通过节点列表或其他导航按钮产生的访问都会记入历史 | 后退 |
| `Forward` | 在执行过 `Previous` 后，沿访问历史回到较新的节点；选择新路径后会清空 Forward 历史 | 前进 |
| `Next` | 显示当前节点的全部后续路径；只有一条时直接前进，多条时让用户选择分支 | 按 AI 调用链继续 |
| `Into` | 只沿 `step_into` 边进入 AI 标记的函数实现节点 | Step Into |
| `Over` | 只沿 `step_over` 边跳过当前调用的内部步骤 | Step Over |
| `Out` | 只沿 `step_out` 边返回 AI 标记的调用方节点 | Step Out |

按钮在存在相应历史或路径时自动启用。`Next` 还可以显示
`branch_true`/`branch_false`、`callback`、`async`、`return` 等路径；多个候选项的顺序和
说明均来自 AI 提交的 Call Flow。

例如在 `setContent` 节点，`Over` 可以直接前往 `onCreate` 返回节点；`Next` 则可以同时提供
“跳过 Compose 内容”和“由 Compose 回调进入根 Composable”等候选路径。

## AI 如何投递文件

插件监听当前操作系统的本地临时交换目录：

```text
<system-temp>/youngx-ai-call-flow-navigator/file-ipc-v2/
  .consumer.lock
  inbox/
  processing/
  receipts/
```

AI 生成 Call Flow 语义和项目相对 `location.path`。配套 Skill 的
`publish_call_flow.py` 会校验源码位置、自动添加请求 ID 与有效期，并按文件协议 `2.0` 投递；
Call Flow 顶层内容 `version` 仍是 `1.0`。发布器先在 `inbox` 中完整写入隐藏临时文件
`.request-<UUID>.tmp`，刷新后以 no-replace 语义原子创建
`request-<UUID>.json`。插件处理发布完成的 `.json`，并把请求绑定到当前 Android Studio
Project 的 canonical root。

交换目录使用固定路径，并校验 owner、权限和文件类型。插件通过 `.consumer.lock` 协调
Android Studio 消费进程。

插件认领请求后会把文件移入 `processing`，校验 UTF-8、投递信息和 Call Flow 结构，然后把
通过校验的原始 JSON 持久化到 IDE JVM 的系统临时目录并加载工具窗口。
最后在 `receipts/receipt-<UUID>.json` 写入 `accepted` 或 `rejected` 回执。AI 应等待同一
`requestId` 的回执。

`<system-temp>` 来自 Android Studio JVM 的 `java.io.tmpdir`：macOS 通常与 `$TMPDIR`
一致，Linux 通常为 `/tmp`，Windows 通常与 `%TEMP%` 一致；显式设置
`-Djava.io.tmpdir` 时，AI 使用相同目录。完整格式见
[Call Flow File Protocol 2.0](docs/call-flow-protocol.md)。

### 已接受 JSON 的位置

插件将校验通过的 AI JSON 原样保存在 IDE JVM 的 `java.io.tmpdir` 下：macOS 通常对应
`$TMPDIR`，Linux 通常对应 `/tmp`，Windows 对应 `%TEMP%`；显式配置的
`-Djava.io.tmpdir` 优先。文件布局为：

```text
<system-temp>/youngx-ai-call-flow-navigator-archive[-user[-<uid>]-<random>]/call-flows/
  project-<base64url-sha256-project-root>/call-flow-<time>-<random>.json
```

POSIX 系统使用当前用户私有的 `0700/0600` 权限；Windows 校验 owner，并使用用户 Profile
与 `%TEMP%` 的系统 ACL。每个项目以最近 50 条为保留目标。交换目录与落盘 JSON 都在项目
目录之外，源码工作树保持整洁。

## 项目结构

```text
ai-call-flow-navigator/
  jetbrains/   Android Studio / JetBrains 插件，提供 Call Flow 和源码跳转
  skills/      Codex 与 Claude Code 共用的 canonical Skill
  scripts/     Skill 安装脚本
  tests/       Python 发布器回归测试
```

## 本地数据安全

AI 与 Android Studio 在同一台机器上通过当前用户的系统临时目录交换文件。交换目录、请求、
回执与落盘 JSON 使用当前操作系统用户权限。Call Flow 可能包含源码路径、符号名和 AI 摘要，
按项目敏感数据管理即可。

## 构建

JetBrains 插件构建需要 JDK 21 和 Android Studio。脚本识别 macOS、Linux、Windows 的常见
安装目录，也可通过 `ANDROID_STUDIO_PATH` 或
`-PandroidStudioPath=<Android Studio IDE home>` 指定 IDE 位置。

```bash
python3 -m unittest discover -s tests -v

cd jetbrains
./gradlew clean test buildPlugin verifyPlugin
```

产物位置：

```text
jetbrains/build/distributions/youngx-ai-call-flow-navigator-0.6.2.zip
```

## 许可证

MIT
