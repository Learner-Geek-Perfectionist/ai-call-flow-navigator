---
name: ai-call-flow-navigator
description: 由用户通过 `$ai-call-flow-navigator`（Codex）或 `/ai-call-flow-navigator`（Claude Code）加主题参数显式调用；根据指定入口或主题分析本地源码仓库，将执行顺序、分支、回调和异步流程建模为精确到行列的 Call Flow，校验后投递到 Android Studio 当前项目中的 AI Call Flow Navigator 插件。
---

# AI Call Flow Navigator

将静态代码分析转换为 Android Studio 中类似 Debug 的源码导览。以终端与 Android Studio 共同打开的项目根目录为基准，并按照 Call Flow schema 生成语义字段。

将用户在 Skill 命令后提供的 `<topic>` 作为入口符号、分析目标和可选范围；如果这些信息不足以唯一定位入口，再提出一个简短问题。

## 工作流程

1. 确认当前工作目录就是 Android Studio 打开的项目根目录，再以该目录为基准计算所有路径。在 monorepo 中，如果 IDE 打开的是 `repo/android`，所有路径便相对于 `repo/android`。
2. 将用户指定的入口点解析到唯一的声明。检查代码上下文后如果仍有多个符号匹配，在投递前向用户提出一个简短问题。
3. 阅读足够的调用方、被调用方、override、callback 和异步 continuation，建立用户所需范围的执行模型。用户未指定边界时，沿应用自身有意义的完整流程继续分析，直到流程返回、到达终态，或进入仓库中不可用的 framework、library、generated code；包含重要分支、catch 和异步恢复，同时合并简单辅助函数。明确区分静态证据支持的路径与依赖运行时值或 framework dispatch 的行为。
4. 按照 `references/call-flow-schema.md` 构建一个语义 Call Flow JSON 对象；`_delivery` 由发布器添加。
5. 对照当前工作树校验每个位置：
   - `path` 使用项目相对路径，并在所有平台统一使用 `/`。
   - 行列采用 1-based；列使用 Android Studio 的 UTF-16 editor offset。
   - 定位到最能解释当前步骤的表达式或声明。
   - 使用 `anchorText` 时，让它保持单行、从给定行列开始，并且在上下 20 行范围内唯一出现；短文本存在歧义时，选用更长的精确片段。
6. 在操作系统安全临时目录中保存私有 UTF-8 JSON 文件。使用不可预测的文件名和 exclusive-create 语义创建文件，并限制为当前用户访问（POSIX 使用 `0600`）。文件位于源码仓库、版本控制和日志之外。
7. 从项目根目录运行本 Skill 自带的 `scripts/publish_call_flow.py --delete-input` 并传入该文件。脚本路径相对于当前 `SKILL.md` 解析；`--delete-input` 会在读取后立即移除临时语义文件。
8. 读取发布器回执，仅在 `status` 为 `accepted` 时报告成功：
   - 遇到 `INVALID_CALL_FLOW` 或其他内容错误时，修正 Call Flow 并投递一个新请求。
   - 遇到 `AMBIGUOUS_PROJECT` 时，请用户关闭额外的 Android Studio 项目后再投递。
   - 遇到 `REQUEST_EXPIRED` 时，确认插件已准备好，再投递一个新请求。
   - 遇到 `LOAD_FAILED` 时，说明 IDE 或插件的加载错误，并在原因解决后再投递。
   - `publisherStatus: not_delivered` 且 `retrySafe: true` 表示发布器已移除尚未认领的请求；修复运行环境后可投递一个新请求。
   - `publisherStatus: unknown` 会给出准确的 `requestId` 和 `receiptFile`；先检查该路径与 Android Studio，再判断是否需要投递新请求。
9. 告知用户已接收的 Call Flow 标题，以及它已可在 Android Studio 的 **Call Flow** 工具窗口中查看。若静态分析存在重要不确定性，再做简短说明。

发布器调用示例：

```text
python3 "<this-skill-directory>/scripts/publish_call_flow.py" --delete-input "<temporary-call-flow.json>"
```

Windows 可使用 `py -3` 代替 `python3`。发布器使用 Python 3.8+ 标准库运行。

## 执行顺序建模

- 使用 `next` 表示同一调用帧中的普通执行顺序。
- 在调用点同时提供两种有意义的选择时，使用 `step_into` 指向被调用函数的第一个相关节点，使用 `step_over` 指向调用方的下一个相关节点。
- 从被调用函数的返回点或终态节点使用 `step_out` 指向调用方 continuation，使 **Out** 按钮可以工作。
- 使用 `branch_true` 和 `branch_false` 表示条件分支，并按照希望用户看到的顺序排列出边。
- 工作被调度或跨越异步边界时使用 `async`；控制流在 callback、listener 或 collector 中恢复时使用 `callback`。
- 仅在语义返回流程超出 debugger-like `step_out` 关系时添加 `return` 边。
- 使用 `branch` 表示错误判断，使用 `return` 或 `note` 表示错误出口，并在 summary 或边的 label 中说明 exception 或 failure 条件。
- 收录会改变控制流、用户可见状态、持久化状态或最终结果的错误路径；没有本地行为的机械式 rethrow 可以合并到 summary。
- 展示相关的多实现、分支、callback 和错误路径，并在节点 summary 或边的 label 中说明触发条件。
- 节点聚焦于有意义的调用、状态变化、判断、异步边界、返回和重要 framework callback；将简单语法合并到所属语义步骤。

`Next` 展示当前节点的全部出边。`Into`、`Over`、`Out` 分别筛选 `step_into`、`step_over`、`step_out`。`Previous` 和 `Forward` 使用访问历史，无需额外的边。

## 发布器行为

内置发布器会对照当前工作目录校验 schema 与源码行列，注入文件投递协议 `2.0`，再原子写入插件 inbox。发布器检查当前平台的标准临时目录候选，包括 macOS GUI 用户临时目录；存在多个有效 inbox 时，发布器会停止，并要求通过显式 `--temp-root` 指定。随后等待插件回执并以 JSON 输出。

退出码 `0` 表示插件回执为 `accepted`，`2` 表示回执为 `rejected`，`3` 表示需要先检查回执再决定是否重试。其他校验或 `not_delivered` 结果使用退出码 `1`；输出包含 JSON 时，检查 `publisherStatus` 和 `retrySafe`。

使用支持 hard link 的标准临时文件系统，例如 APFS、ext4 或 NTFS。Windows 目录隔离使用用户 Profile 的 `%TEMP%` ACL，插件在创建 inbox 时会进行校验。

找不到 inbox 时，请用户打开一个已启用插件的 Android Studio 项目，让插件完成创建后再投递。Android Studio 使用自定义 `-Djava.io.tmpdir` 启动时，将用户确认的相同目录通过 `--temp-root` 传入。

运行中的插件持有 `.consumer.lock` 排他锁并接收 Call Flow。结果为 unknown 的超时可能表示请求已进入 `processing`，再次操作前先检查 Android Studio。

## 静态分析准确性

当仓库证据不足以确定 reflection、dependency injection、dynamic dispatch、generated code、native call、server behavior 或仅运行时可知的条件时，将其标记为不确定。只使用在当前工作树中验证过的源码位置。
