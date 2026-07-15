# AI Call Flow Navigator

AI Call Flow Navigator 把本地 AI 分析出的源码调用链加载到 Android Studio，并在原生
`Call Flow` 工具窗口中提供精确行列跳转、Previous/Forward 历史以及 Next、Into、Over、
Out 导航。

GitHub 仓库：[Learner-Geek-Perfectionist/ai-call-flow-navigator](https://github.com/Learner-Geek-Perfectionist/ai-call-flow-navigator)

## 安装

### Android Studio 插件

如果装过 0.1.x 的 `Android Studio Bridge`（插件 ID `com.ouyang.asbridge`），请先卸载它。
新插件 ID 是 `com.youngx.aicallflow`，Android Studio 会把二者视为不同插件，不会自动升级或
移除旧插件。

1. 打开项目的 [最新 GitHub Release](https://github.com/Learner-Geek-Perfectionist/ai-call-flow-navigator/releases/latest)，下载 `youngx-ai-call-flow-navigator-<version>.zip`，不要解压。
2. 在 Android Studio 打开 `Settings/Preferences → Plugins`。
3. 点击齿轮菜单，选择 `Install Plugin from Disk...`，选中下载的 ZIP。
4. 按提示重启 Android Studio，然后打开需要阅读的源码项目。

插件没有 Settings 配置项，不需要端口、token 或 `projectRoot`。它自动绑定 Android Studio
当前项目，并通过系统临时目录中的 `file-ipc-v2` 接收 Call Flow。

### Codex 与 Claude Code Skill

仓库提供同一份 canonical `ai-call-flow-navigator` Skill。安装脚本会同时把它安装到：

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
目录中启动 AI，并这样调用。若是 monorepo 且 Android Studio 只打开其中的 `android/`
子目录，终端也必须进入该子目录，而不是上层 Git 根：

- Codex：`$ai-call-flow-navigator 从 MainActivity.onCreate 开始分析调用链`
- Claude Code：`/ai-call-flow-navigator 从 MainActivity.onCreate 开始分析调用链`

两个入口使用相同的 Skill 规范和 `file-ipc-v2` 协议，不需要分别维护不同提示词。
Skill 的投递器只使用 Python 3.8+ 标准库，不需要安装第三方 Python 包；Windows 可通过
`py -3` 运行。原子防覆盖投递需要系统临时目录所在文件系统支持 hard link；标准的
APFS、ext4、NTFS 支持，重定向到 FAT/exFAT 或部分网络文件系统的临时目录不支持，发布器会
安全失败而不会退化为可覆盖的复制。

## 零配置 Call Flow

正常使用只有三步：

1. 安装 AI Call Flow Navigator 插件。
2. 在 Android Studio 中打开源码项目。
3. 在同一台电脑上，让 AI 分析当前项目并生成、投递 Call Flow JSON。

插件自动使用 Android Studio 当前打开的项目。无需进入 Settings，不需要端口、token、
`projectRoot`、启用开关、curl 或 capabilities 探测。收到有效文件后，插件会自动打开
`Call Flow` 工具窗口并定位入口节点。

插件 ID 是 `com.youngx.aicallflow`，Vendor 显示为 `YoungX`。

### Call Flow 导航按钮

`Previous`/`Forward` 浏览用户已经访问过的节点；`Next`/`Into`/`Over`/`Out` 则沿着 AI
提交的调用链边移动。它们不会启动调试器，也不会根据当前源码临时猜测下一行。

| 按钮 | 行为 | 与 Debug 的对应关系 |
| --- | --- | --- |
| `Previous` | 回到上一个实际访问过的节点；通过节点列表或其他导航按钮产生的访问都会记入历史 | 后退 |
| `Forward` | 在执行过 `Previous` 后，沿访问历史回到较新的节点；选择新路径后会清空 Forward 历史 | 前进 |
| `Next` | 显示当前节点的全部出边；只有一条时直接前进，多条时让用户选择分支。它不是“源码下一行” | 按 AI 调用链继续 |
| `Into` | 只沿 `step_into` 边进入 AI 标记的函数实现节点 | Step Into |
| `Over` | 只沿 `step_over` 边跳过当前调用的内部步骤 | Step Over |
| `Out` | 只沿 `step_out` 边返回 AI 标记的调用方节点 | Step Out |

没有可用历史或没有对应类型的出边时，按钮会自动禁用。`Next` 还可以显示
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

AI 只生成 Call Flow 内容中的项目相对 `location.path`，不生成 `projectRoot` 或绝对项目根
路径。配套 Skill 的
`publish_call_flow.py` 会校验源码位置、自动添加请求 ID 与有效期，并按文件协议 `2.0` 投递；
Call Flow 顶层内容 `version` 仍是 `1.0`。发布器先在 `inbox` 中完整写入隐藏临时文件
`.request-<UUID>.tmp`，刷新后以不覆盖既有文件的方式原子创建
`request-<UUID>.json`。插件只处理发布完成的 `.json`，并把请求绑定到当前 Android Studio
Project 的 canonical root。同一 Android Studio 进程同时打开多个项目时，插件以
`AMBIGUOUS_PROJECT` 拒绝请求，不会猜测目标。

交换目录路径固定且可预测，不使用随机后备目录；如果固定根目录的 owner、权限或文件类型不
安全，插件会启动失败。插件通过 `.consumer.lock` 保证同一交换目录最多只有一个 Android
Studio 消费进程；锁已由另一进程持有时，当前插件不消费请求。

插件认领请求后会把文件移入 `processing`，校验 UTF-8、2 MiB 大小上限、投递信息和 Call
Flow 结构，然后把通过校验的原始 JSON 持久化到 IDE JVM 的系统临时目录并加载工具窗口。
最后在 `receipts/receipt-<UUID>.json` 写入 `accepted` 或 `rejected` 回执。AI 应等待同一
`requestId` 的回执；等待超时通常表示插件未运行或没有打开项目。

`<system-temp>` 来自 Android Studio JVM 的 `java.io.tmpdir`：macOS 通常与 `$TMPDIR`
一致，Linux 通常为 `/tmp`，Windows 通常与 `%TEMP%` 一致；显式设置
`-Djava.io.tmpdir` 时，AI 必须使用相同目录。这不是云端服务或电子邮箱，不需要网络、端口、
Token、实例索引或项目路径配置。完整格式见
[Call Flow File Protocol 2.0](docs/call-flow-protocol.md)。

### 已接受 JSON 的位置

插件将校验通过的 AI JSON 原样保存在 IDE JVM 的 `java.io.tmpdir` 下：macOS 通常对应
`$TMPDIR`，Linux 通常对应 `/tmp`，Windows 对应 `%TEMP%`；显式配置的
`-Djava.io.tmpdir` 优先。文件布局为：

```text
<system-temp>/youngx-ai-call-flow-navigator-archive[-user[-<uid>]-<random>]/call-flows/
  project-<base64url-sha256-project-root>/call-flow-<time>-<random>.json
```

POSIX 系统使用当前用户私有的 `0700/0600` 权限；Windows 校验 owner，并依赖用户 Profile
与 `%TEMP%` 的系统 ACL。每个项目以最近 50 条为保留目标。交换目录与落盘 JSON 都在项目
目录之外，不会修改源码仓库或造成脏工作树。

## 项目结构

```text
ai-call-flow-navigator/
  jetbrains/   Android Studio / JetBrains 插件，提供 Call Flow 和源码跳转
  skills/      Codex 与 Claude Code 共用的 canonical Skill
  scripts/     Skill 安装脚本
```

## 本地安全边界

本协议没有网络监听、端口或 Token。安全边界是本机用户的文件系统权限：交换目录、请求、
回执与落盘 JSON 都应仅允许当前操作系统用户访问，不应提交到版本控制、复制到日志或发送给
其他用户与远程服务。Call Flow 可能包含源码路径、符号名和 AI 摘要，应视为项目敏感数据。

该流程是本机临时文件交换，不是云端传输或电子邮箱。AI 与 Android Studio 必须运行在同一
台机器，并能读写同一个系统临时目录；纯云端 AI 无法直接访问该目录。

## 构建

JetBrains 插件构建需要 JDK 21 和 Android Studio。脚本支持 macOS、Linux、Windows 的常见
安装目录；非标准位置通过 `ANDROID_STUDIO_PATH` 或
`-PandroidStudioPath=<Android Studio IDE home>` 指定，不再依赖固定的 macOS 路径。

```bash
cd jetbrains
./gradlew clean test buildPlugin verifyPlugin
```

产物位置：

```text
jetbrains/build/distributions/youngx-ai-call-flow-navigator-0.6.0.zip
```

## 许可证

MIT
