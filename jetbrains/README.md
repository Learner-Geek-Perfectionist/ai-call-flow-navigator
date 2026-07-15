# AI Call Flow Navigator JetBrains 插件

AI Call Flow Navigator 接收本地 AI 生成的完整源码阅读链，在 Android Studio 的原生
`Call Flow` 工具窗口中显示节点、说明和分支，并提供调试式源码导航。

GitHub 仓库：[Learner-Geek-Perfectionist/ai-call-flow-navigator](https://github.com/Learner-Geek-Perfectionist/ai-call-flow-navigator)

## 从 GitHub Release 安装

如果装过 0.1.x 的 `Android Studio Bridge`（插件 ID `com.ouyang.asbridge`），请先卸载它。
新插件 ID `com.youngx.aicallflow` 不会覆盖或自动移除旧插件。

1. 打开 [最新 GitHub Release](https://github.com/Learner-Geek-Perfectionist/ai-call-flow-navigator/releases/latest)，下载 `youngx-ai-call-flow-navigator-<version>.zip`，不要解压。
2. 在 Android Studio 打开 `Settings/Preferences → Plugins`。
3. 点击齿轮菜单并选择 `Install Plugin from Disk...`，选中下载的 ZIP。
4. 按提示重启 Android Studio，然后打开需要阅读的项目。

插件本身没有 Settings 配置项，不需要端口、token 或 `projectRoot`。它自动绑定当前 Android
Studio Project，并通过系统临时目录中的 `file-ipc-v2` 接收 Call Flow。

## 配套 AI Skill

仓库中的同一份 canonical `ai-call-flow-navigator` Skill 可以同时用于 Codex 和 Claude Code。
macOS/Linux 运行 `./scripts/install-skill.sh`，Windows PowerShell 运行
`./scripts/install-skill.ps1`；脚本会同时安装到：

```text
~/.agents/skills/ai-call-flow-navigator
~/.claude/skills/ai-call-flow-navigator
```

安装或更新 Skill 后先新开一个 AI 会话。在 Android Studio 实际打开的项目根目录启动 AI；
monorepo 中 IDE 若只打开 `android/` 子目录，终端也必须进入该子目录。Codex 使用
`$ai-call-flow-navigator 从 MainActivity.onCreate 开始分析调用链`，Claude Code 使用
`/ai-call-flow-navigator 从 MainActivity.onCreate 开始分析调用链`。安装脚本和源码位于
[AI Call Flow Navigator 仓库](https://github.com/Learner-Geek-Perfectionist/ai-call-flow-navigator)。
Skill 的投递器只依赖 Python 3.8+ 标准库；Windows 可使用 `py -3`。
原子投递要求系统临时目录所在文件系统支持 hard link（标准 APFS、ext4、NTFS 支持）；若
临时目录被重定向到 FAT/exFAT 或不支持 hard link 的网络文件系统，发布器会安全失败。

## 零配置使用

1. 安装插件。
2. 在 Android Studio 中打开源码项目。
3. 让同一台电脑上的 AI 分析项目并投递 Call Flow JSON。

插件自动使用当前 IDE 项目根目录并监听本机系统临时交换目录。无需进入 Settings，不需要
端口、token、`projectRoot`、启用开关、curl 或 capabilities 检查。

插件 ID 是 `com.youngx.aicallflow`，Vendor 是 `YoungX`。

收到并校验 Call Flow 后，插件自动打开工具窗口并定位入口节点。Previous/Forward 按实际
访问历史移动，Next/Into/Over/Out 按 AI 提供的边移动。协议与 JSON 格式见
[Call Flow File Protocol 2.0](../docs/call-flow-protocol.md)。

## Call Flow 导航按钮

| 按钮 | 行为 | 与 Debug 的对应关系 |
| --- | --- | --- |
| `Previous` | 回到上一个实际访问过的节点；节点列表跳转和按钮导航都会写入这份历史 | 后退 |
| `Forward` | 执行 `Previous` 后回到较新的历史节点；选择新路径后会清空 Forward 历史 | 前进 |
| `Next` | 获取当前节点的全部出边；一条时直接前进，多条时弹出候选选择。它不是源码的下一行 | 按 AI 调用链继续 |
| `Into` | 只显示并沿 `step_into` 边进入函数实现 | Step Into |
| `Over` | 只显示并沿 `step_over` 边跳过当前调用的内部步骤 | Step Over |
| `Out` | 只显示并沿 `step_out` 边返回调用方 | Step Out |

按钮只使用 AI 在 Call Flow 中明确提交的边，不会动态运行程序或自行推断缺失路径。没有可用
历史或没有对应类型的出边时，按钮会禁用。`Next` 包含所有出边，因此也负责展示条件分支、
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

AI 只生成 Call Flow 内容中的项目相对 `location.path`，不生成 `projectRoot` 或绝对项目根
路径。配套 Skill 的 `publish_call_flow.py` 会
校验源码位置、添加 `_delivery` 中的唯一 `requestId` 与有效期，并使用文件协议 `2.0` 投递；
Call Flow 顶层内容 `version` 仍是 `1.0`。发布器先完整写入
`inbox/.request-<UUID>.tmp`，刷新后以不覆盖既有文件的方式原子创建
`inbox/request-<UUID>.json`。插件忽略临时文件，并把最终 JSON 绑定到当前 Android Studio
Project 的 canonical root。同一 Android Studio 进程同时打开多个项目时，插件返回
`AMBIGUOUS_PROJECT`，不会猜测目标。

交换路径固定且可预测，不使用随机后备目录。固定根目录的 owner、权限或文件类型不安全时，
插件启动失败。`.consumer.lock` 保证同一临时交换目录最多只有一个 Android Studio 消费
进程；若另一进程已经持有锁，当前插件不消费请求。

插件把认领的请求移入 `processing`，校验 UTF-8、2 MiB 大小上限、投递信息和 Call Flow
结构。通过后，原始 JSON 会先持久化到 IDE JVM 的系统临时目录，再交给工具窗口；结果写入
`receipts/receipt-<UUID>.json`。AI 应等待 `accepted` 或 `rejected` 回执。等待超时通常表示
插件未运行或没有打开项目。

`<system-temp>` 来自 Android Studio JVM 的 `java.io.tmpdir`：macOS 通常与 `$TMPDIR`
一致，Linux 通常为 `/tmp`，Windows 通常与 `%TEMP%` 一致；显式设置
`-Djava.io.tmpdir` 时，AI 必须使用相同目录。这是本机临时文件交换，不是云端服务或电子邮箱。

已接受文件的布局为：

```text
<system-temp>/youngx-ai-call-flow-navigator-archive[-user[-<uid>]-<random>]/call-flows/
  project-<base64url-sha256-project-root>/call-flow-<time>-<random>.json
```

`<system-temp>` 来自 IDE JVM `java.io.tmpdir`：macOS 通常是 `$TMPDIR`，Linux 通常是
`/tmp`，Windows 是 `%TEMP%`；显式 `-Djava.io.tmpdir` 优先。每个项目以最近 50
条为保留目标。交换目录和已接受文件均位于项目目录之外，不会造成源码工作树变更。

## 安全与运行范围

- 协议不启动网络服务，也没有端口或 Token。
- POSIX 交换目录使用 `0700`、文件使用 `0600`；Windows 校验 owner，并依赖用户 Profile
  与 `%TEMP%` 的系统 ACL。若这些目录被管理员配置为其他普通用户可写，不应把文件协议
  当作跨用户安全边界。
- 请求、回执和落盘 JSON 可能包含源码路径、符号和 AI 摘要，不应记录、共享或提交到版本
  控制。
- 源码位置必须相对插件绑定的当前项目根目录，且不能越过该目录。

文件投递要求 AI 与 Android Studio 运行在同一台机器，并且可以读写同一个系统临时目录。
纯云端 AI 无法直接完成这一步。

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
build/distributions/youngx-ai-call-flow-navigator-0.6.0.zip
```
