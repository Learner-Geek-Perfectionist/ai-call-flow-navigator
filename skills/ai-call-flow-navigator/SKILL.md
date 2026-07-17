---
name: ai-call-flow-navigator
description: 由用户通过 `$ai-call-flow-navigator` 加主题参数（Codex）或 `/ai-call-flow-navigator` 加主题参数（Claude Code）显式调用；在当前本地源码项目中定位主题对应的唯一入口，并向 Android Studio 的 AI Call Flow Navigator 插件投递静态与实时联合分析请求。仅在用户明确输入该 Skill 命令时使用，不要隐式触发。
---

# AI Call Flow Navigator

将命令后的 `<topic>` 视为分析目标。AI 只负责定位入口并投递 analysis request；不生成 Call Flow `nodes`、`edges`、`frames` 或 `contexts`。Call Flow 由 Android Studio 插件在当前 Project 中生成。

## 工作流程

1. 仅接受显式命令：Codex 使用 `$ai-call-flow-navigator <topic>`，Claude Code 使用 `/ai-call-flow-navigator <topic>`。`<topic>` 必须非空；不得因普通的源码分析请求自动运行本 Skill。
2. 确认当前工作目录是 Android Studio 打开的项目根目录。在 monorepo 中使用 IDE 实际打开的子目录，例如 `repo/android`。该目录只用于本地验证相对路径，不写入请求。
3. 根据 `<topic>` 搜索当前工作树，将入口解析到唯一的 Java/Kotlin 源码声明。阅读必要的调用点、类层次和注册代码，但不在 Skill 侧展开完整调用图。如果检查代码后仍有多个合理入口，先提一个简短问题，不要猜测。
4. 对照当前工作树确认入口位置：
   - `path` 是项目相对路径，经过规范化且仅使用 `/`。
   - `line` 和 `column` 均为 1-based；`column` 按 Android Studio 的 UTF-16 code unit 计数。
   - `symbol` 必填 source-level qualified symbol，不带参数签名、泛型或返回类型，不得使用 `$default`、`FooKt`、`$lambda`、`<init>` 等 JVM synthetic 名称。
5. 按 `references/analysis-request-schema.md` 构建唯一支持的请求。策略固定为 `{"mode":"static-and-live","scope":"project-code"}`。不得添加 `projectRoot`、Call Flow 节点或自定义字段；`_delivery` 由发布器添加。
6. 在操作系统安全临时目录中使用不可预测文件名和 exclusive-create 语义写入私有 UTF-8 JSON 文件；POSIX 权限设为 `0600`。不要把请求放在源码仓库、版本控制或日志中。
7. 从项目根目录运行本 Skill 的 `scripts/publish_analysis_request.py --delete-input` 并传入临时文件。脚本路径相对当前 `SKILL.md` 解析。
8. 读取发布器回执。仅当 `status` 为 `accepted` 时报告成功；如果返回输入错误，修正入口或请求后投递新请求。`publisherStatus: unknown` 表示请求可能已被 Android Studio 认领，先检查回执路径和 **Call Flow** 工具窗口，不要立即重复投递。
9. 告知用户插件已接收 `<topic>` 的分析请求，结果将显示在 Android Studio 的 **Call Flow** 工具窗口。

## 请求示例

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

发布器调用：

```text
python3 "<this-skill-directory>/scripts/publish_analysis_request.py" --delete-input "<temporary-analysis-request.json>"
```

Windows 可使用 `py -3`。发布器仅依赖 Python 3.8+ 标准库。

## 投递边界

- 发布器校验精确 schema、当前项目内的源码路径与行列，注入 File IPC `3.0` 投递元数据，再原子写入系统临时目录中的 `file-ipc-v3` inbox。
- 项目根由 Android Studio 当前 Project 管理，不通过 IPC 传输。
- 找不到 inbox 时，请用户在 Android Studio 中打开已启用插件的项目。如果 Android Studio 使用自定义 `-Djava.io.tmpdir`，通过 `--temp-root` 传入同一目录。
- 请求超时且仍在 inbox 时，发布器移除它并返回可安全重试；如果已被认领，返回 `unknown` 以防止重复分析。
