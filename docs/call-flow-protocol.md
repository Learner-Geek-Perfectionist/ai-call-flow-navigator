# Call Flow File Protocol 2.0

Call Flow File Protocol 让本地 AI 把完整的源码阅读路径交给 Android Studio。插件校验文件后，
在 `Call Flow` 工具窗口中显示节点，并提供 Previous、Forward、Next、Into、Over、Out
导航。

## 用户流程

安装插件并在 Android Studio 中打开项目后，用户从同一项目根目录显式调用 Skill：

- Codex：`$ai-call-flow-navigator <topic>`
- Claude Code：`/ai-call-flow-navigator <topic>`

Skill 按 `<topic>` 分析并投递 Call Flow，插件自动绑定当前打开的项目。

仓库配套的 Claude/Codex Skill 负责让 AI 生成 Call Flow 内容；其中的
`publish_call_flow.py` 负责校验源码行列、生成 `_delivery` 并执行下面的原子投递步骤。

## 系统临时交换目录

AI 与插件通过当前操作系统的标准本地临时目录交换文件：

```text
<system-temp>/youngx-ai-call-flow-navigator/file-ipc-v2/
  .consumer.lock
  inbox/
  processing/
  receipts/
```

`<system-temp>` 由 Android Studio JVM 的 `java.io.tmpdir` 提供。各平台的标准位置通常如下；
启动 IDE 时显式设置 `-Djava.io.tmpdir` 后，命令行 AI 使用相同目录：

| 平台 | 标准本地临时目录 |
| --- | --- |
| macOS | 通常与 `$TMPDIR` 一致，位于 `/var/folders/.../T` |
| Linux | 通常为 `/tmp` |
| Windows | 通常与 `%TEMP%` 一致，位于用户的 `AppData\Local\Temp` |

仓库发布器会检查当前平台的标准临时目录候选（包括 macOS GUI 用户临时目录），自动使用唯一
有效的 inbox；多个有效 inbox 可通过 `--temp-root` 选择。

交换目录使用上面的固定路径。插件启动时验证根目录的 owner、权限和文件类型，Skill 使用由
插件创建并验证过的 inbox。

四个条目的职责是：

- `.consumer.lock`：Android Studio 消费进程持有的排他锁。
- `inbox`：AI 原子发布的待处理请求。
- `processing`：插件已经原子认领、正在校验或加载的请求。
- `receipts`：插件发布的 `accepted` 或 `rejected` 回执。

Android Studio 消费进程取得 `.consumer.lock` 后扫描和认领请求，以单消费者方式处理
Call Flow。

插件从当前 Android Studio Project 取得 canonical root 并完成绑定；AI 负责 Call Flow
语义和源码相对 `location.path`。

命令行 AI 的当前目录与 Android Studio 实际打开的 Project root 保持一致。monorepo 中若
Android Studio 打开 `repo/android`，节点路径便以 `repo/android` 为根。插件在写
`accepted` 回执前，使用自己的 canonical project root 检查每个节点文件和行列，确保已接收
的 Call Flow 可以直接导航。

## 请求格式

请求是 UTF-8 JSON 对象。它在普通 Call Flow JSON 顶层增加 `_delivery`：

```json
{
  "_delivery": {
    "version": "2.0",
    "requestId": "f6376eb3-a71b-44a4-ac91-98af42815e29",
    "createdAtEpochMs": 1784044800000,
    "expiresAtEpochMs": 1784044920000
  },
  "version": "1.0",
  "title": "MainActivity.onCreate flow",
  "nodes": [
    {
      "id": "entry-node",
      "kind": "entry",
      "location": {
        "path": "app/src/main/java/com/example/MainActivity.kt",
        "line": 18,
        "column": 5
      },
      "summary": "MainActivity.onCreate is the entry point."
    }
  ],
  "edges": [],
  "entry": "entry-node"
}
```

`_delivery` 字段定义如下：

| 字段 | 要求 |
| --- | --- |
| `version` | 必须是字符串 `2.0`，表示文件投递协议版本 |
| `requestId` | 每次投递唯一；推荐使用 UUID。只允许字母、数字、`_`、`-`，最长 96 字符 |
| `createdAtEpochMs` | 创建请求时的 Unix epoch 毫秒整数 |
| `expiresAtEpochMs` | 失效时间，必须晚于创建时间；建议留出 1 至 5 分钟 |

发布器按上表构造 `_delivery`。项目根由插件从 Android Studio Project 获得；Call Flow
`location.path` 相对当前项目根目录，并使用 `/` 分隔。

顶层 Call Flow 的 `version` 与 `_delivery.version` 是两个独立版本：Call Flow 内容版本为
`1.0`，文件投递协议版本为 `2.0`。插件保存包含 `_delivery` 的原始 JSON，并将投递信息与
节点数据分别解析。

## 原子发布步骤

假设 `requestId` 是 `f6376eb3-a71b-44a4-ac91-98af42815e29`，发布流程如下：

1. 校验 `inbox` 是当前用户拥有、以 NOFOLLOW 语义确认的普通目录。
2. 在 `inbox` 同一目录创建
   `.request-f6376eb3-a71b-44a4-ac91-98af42815e29.tmp`。
3. 写入完整 UTF-8 JSON，刷新并关闭文件；POSIX 平台推荐设置为 `0600`。
4. 在同一目录以 no-replace 语义原子创建最终名称
   `request-f6376eb3-a71b-44a4-ac91-98af42815e29.json`。仓库发布器使用 hard link 创建最终
   名称后删除临时名称；自定义客户端也可使用操作系统提供的 no-replace 原子 rename API。
5. 等待 `receipts/receipt-f6376eb3-a71b-44a4-ac91-98af42815e29.json`。

插件扫描发布完成的 `request-<requestId>.json`，写入过程中的 `.tmp` 与消费过程彼此隔离。
文件名中的 ID 与 `_delivery.requestId` 相同，每次投递使用新的 requestId；临时文件和最终
文件位于同一个 `inbox`，从而保持同文件系统原子发布。

插件通常每 500 ms 扫描一次，绑定当前项目并将请求原子移入 `processing`。

认领文件使用不可预测的内部 claim 名，为每个 processing 文件保留唯一名称。插件
处理期间每 500 ms 为本进程 claim 续租；正常关闭或动态卸载时，会把尚未完成的 claim 放回
`inbox`；进程异常退出遗留的 claim 在 10 分钟租约到期后恢复。若恢复时请求已经过期，插件
会按正常流程写入 `REQUEST_EXPIRED` 回执。跨进程崩溃边界采用 at-least-once 语义：若工具
窗口已经加载、但成功回执尚未落盘就崩溃，恢复后可能再次加载同一个请求。

## 回执

插件通过同目录临时文件和原子 rename 发布回执。成功回执示例：

```json
{
  "version": "2.0",
  "requestId": "f6376eb3-a71b-44a4-ac91-98af42815e29",
  "status": "accepted",
  "completedAtEpochMs": 1784044800521,
  "callFlowFile": "<system-temp>/youngx-ai-call-flow-navigator-archive.../call-flows/project-<hash>/call-flow-<time>-<random>.json",
  "nodeCount": 12,
  "edgeCount": 14,
  "entry": "activity-on-create"
}
```

`accepted` 表示插件已经将原始 JSON 安全落盘并完成工具窗口加载。`callFlowFile` 是本次
保存文件的本机绝对路径；客户端按当前操作系统的原生路径格式处理它。

`rejected` 回执示例：

```json
{
  "version": "2.0",
  "requestId": "f6376eb3-a71b-44a4-ac91-98af42815e29",
  "status": "rejected",
  "completedAtEpochMs": 1784044800521,
  "error": {
    "code": "INVALID_CALL_FLOW",
    "message": "Call Flow entry references an unknown node"
  }
}
```

当前错误码包括：

| 错误码 | 含义 |
| --- | --- |
| `INVALID_DELIVERY` | UTF-8、JSON 或 `_delivery` 格式错误 |
| `REQUEST_ID_MISMATCH` | 文件名 ID 与 `_delivery.requestId` 不一致 |
| `REQUEST_EXPIRED` | 插件处理时已超过 `expiresAtEpochMs` |
| `AMBIGUOUS_PROJECT` | 同一 Android Studio 进程打开了多个项目，无法唯一绑定当前项目 |
| `INVALID_CALL_FLOW` | Call Flow 结构、路径或认领后的内容校验失败 |
| `LOAD_FAILED` | 原始 JSON 落盘或 IDE 工具窗口加载失败 |

客户端以回执的 `status` 为最终结果。AI 读取完回执后可以删除它；插件以最近 100 条回执为
保留目标，并通过 requestId 对已处理请求去重。

## 已接受 JSON 落盘

插件完成 UTF-8、投递信息与 Call Flow 协议校验后，保存与请求等价的原始 JSON 文本，
再交给工具窗口并生成 `accepted` 回执。

保存根目录来自 Android Studio JVM 的 `java.io.tmpdir`：

| 平台 | 常见位置（仅示例） |
| --- | --- |
| macOS | 通常与 `$TMPDIR` 一致，位于 `/var/folders/.../T` |
| Linux | 通常为 `/tmp` |
| Windows | 通常与 `%TEMP%` 一致，位于用户的 `AppData\Local\Temp` |

文件布局为：

```text
<system-temp>/youngx-ai-call-flow-navigator-archive[-user[-<real-unix-uid>]-<random>]/call-flows/
  project-<base64url-no-padding(SHA-256(canonical-project-root))>/
    call-flow-<epoch-ms>-<random>.json
```

通常使用不带后缀的根目录；如果该名称已被其他用户、普通文件或符号链接占用，插件使用
带真实 Unix UID（平台支持时）和随机后缀的私有后备目录，避免共享临时目录中的可预测名称
预占。这个后备策略用于已接受 JSON 的归档目录；`file-ipc-v2` 交换目录保持固定。每次成功
投递创建不可预测的新文件。每个项目以最近 50 条为保留目标；文件被占用时可能暂时超过
50。文件在 IDE 关闭后保留，之后由系统临时目录策略清理。

## Call Flow JSON 格式

下面展示 Call Flow 内容；发布器会在投递时添加 `_delivery`：

```json
{
  "version": "1.0",
  "title": "Login flow",
  "project": {
    "revision": "978a9df"
  },
  "nodes": [
    {
      "id": "login-click",
      "kind": "entry",
      "location": {
        "path": "app/src/main/java/com/example/LoginFragment.kt",
        "line": 42,
        "column": 9,
        "endLine": 42,
        "endColumn": 35,
        "symbol": "com.example.LoginFragment.onLoginClick",
        "anchorText": "viewModel.login(account)"
      },
      "summary": "The click handler starts the login request."
    },
    {
      "id": "view-model-login",
      "kind": "call",
      "location": {
        "path": "app/src/main/java/com/example/LoginViewModel.kt",
        "line": 28,
        "column": 5,
        "symbol": "com.example.LoginViewModel.login",
        "anchorText": "repository.login(account)"
      },
      "summary": "The ViewModel delegates authentication to the repository."
    }
  ],
  "edges": [
    {
      "from": "login-click",
      "to": "view-model-login",
      "kind": "step_into",
      "label": "Enter LoginViewModel.login"
    }
  ],
  "entry": "login-click"
}
```

所有行列均为 1-based。`endLine` 和 `endColumn` 必须同时提供，结束位置按编辑器 offset
处理，推荐表示高亮范围的 exclusive end。`symbol` 和 `anchorText` 可选；当代码插入或移动
后，插件会在目标行附近寻找唯一的 `anchorText` 并修正跳转位置。

支持的节点类型：

- `entry`
- `declaration`
- `call`
- `branch`
- `return`
- `async`
- `callback`
- `note`

支持的边类型：

- `next`
- `step_into`
- `step_over`
- `step_out`
- `branch_true`
- `branch_false`
- `return`
- `async`
- `callback`

工具窗口按钮与协议边的精确关系如下：

| 按钮 | 数据来源与行为 |
| --- | --- |
| `Previous` | 返回上一个实际访问过的节点；节点列表跳转和边导航都会记入历史 |
| `Forward` | 执行 `Previous` 后沿访问历史前进；从历史中选择新路径后会被清空 |
| `Next` | 返回当前节点全部类型的出边 |
| `Into` | 只返回当前节点的 `step_into` 出边 |
| `Over` | 只返回当前节点的 `step_over` 出边 |
| `Out` | 只返回当前节点的 `step_out` 出边 |

候选为空时对应按钮禁用；候选只有一个时直接导航，存在多个候选时插件会按 `edges` 数组顺序
显示选择器。选择任意候选，或从节点列表跳转到不同节点，都会把原
节点写入 Previous 历史并清空 Forward 历史。

`Next` 包含当前节点的所有出边，包括 `next`、`step_into`、`step_over`、`step_out`、
条件分支、返回、异步和回调；`Into`、`Over`、`Out` 则提供对应类型的调试式导航。

`project` 是可选字段；提供时 `revision` 必填，并作为描述性元数据随原始 JSON 保存。
`edges` 字段必须存在，但单节点 Call Flow 可以使用空数组。`label`、`symbol` 和
`anchorText` 均为可选字段。

## 本地数据安全

- 协议通过当前用户的本地临时文件系统交换数据。
- POSIX 交换目录权限为 `0700`、请求与回执为 `0600`；Windows 校验 owner，并使用用户
  Profile 与 `%TEMP%` 的系统 ACL。
- 插件接收当前用户拥有的普通请求文件。
- 交换目录、回执和已接受 JSON 位于项目目录之外，源码仓库与 `.idea` 保持整洁。
- 请求和回执可能包含源码路径、结构、符号与 AI 摘要，按项目敏感数据管理。

## 运行环境

AI 进程与 Android Studio 位于同一台机器，并访问同一个系统临时目录。
