# Call Flow File Protocol 2.0

Call Flow File Protocol 让本地 AI 把完整的源码阅读路径交给 Android Studio。插件校验文件后，
在 `Call Flow` 工具窗口中显示节点，并提供 Previous、Forward、Next、Into、Over、Out
导航。

## 用户流程

正常用户只需安装插件、在 Android Studio 中打开项目，然后让同一台电脑上的 AI 分析并
投递 Call Flow。插件直接使用当前打开的项目，无需 Settings、项目根目录配置、开关、端口、
Token、curl 或 capabilities 探测。

仓库配套的 Claude/Codex Skill 只让 AI 生成 Call Flow 内容；其中的
`publish_call_flow.py` 负责校验源码行列、生成 `_delivery` 并执行下面的原子投递步骤。
普通用户无需手写投递字段或操作交换目录。

文件协议不启动网络服务，也不发布 Android Studio 实例索引。

## 系统临时交换目录

AI 与插件通过当前操作系统的标准本地临时目录交换文件：

```text
<system-temp>/youngx-ai-call-flow-navigator/file-ipc-v2/
  .consumer.lock
  inbox/
  processing/
  receipts/
```

`<system-temp>` 取 Android Studio JVM 的 `java.io.tmpdir`，不能在所有系统上硬编码为
`/tmp`。默认值通常如下；如果启动 IDE 时显式设置了 `-Djava.io.tmpdir`，命令行 AI 必须使用
相同目录：

| 平台 | 标准本地临时目录 |
| --- | --- |
| macOS | 通常与 `$TMPDIR` 一致，位于 `/var/folders/.../T` |
| Linux | 通常为 `/tmp` |
| Windows | 通常与 `%TEMP%` 一致，位于用户的 `AppData\Local\Temp` |

仓库发布器会检查当前平台的标准临时目录候选（包括 macOS GUI 用户临时目录）；只找到一个
有效 inbox 时自动使用它，发现多个时要求显式传入 `--temp-root`。这只是固定目录发现，不是
Android Studio 实例索引。

交换目录必须使用上面的固定、可预测路径，不使用带 UID 或随机后缀的后备目录。插件启动时
验证固定根目录的 owner、权限和文件类型；无法安全创建或验证时启动失败，不消费任何请求。
目录不存在时，AI 应提示插件可能未运行，而不是创建一个权限未知的公共目录。这是同一台
机器上的临时文件交换，不是云端服务或电子邮箱。

三个目录的职责是：

- `.consumer.lock`：Android Studio 消费进程持有的排他锁。
- `inbox`：AI 原子发布的待处理请求。
- `processing`：插件已经原子认领、正在校验或加载的请求。
- `receipts`：插件发布的最终成功或失败回执。

同一临时交换目录最多只能有一个 Android Studio 消费进程。插件必须先取得
`.consumer.lock` 才能扫描或认领请求；若另一进程已经持有该锁，当前插件保持不消费状态。

请求不包含 `projectRoot` 或绝对项目根字段，插件始终从当前 Android Studio Project 取得
canonical root 并完成绑定；AI 只负责 Call Flow 语义和源码相对 `location.path`。同一 Android Studio 进程同时打开多个项目
时，插件以 `AMBIGUOUS_PROJECT` 拒绝请求，不会猜测目标。协议不需要端口、Token、实例索引
或项目路径配置。

命令行 AI 的当前目录必须与 Android Studio 实际打开的 Project root 一致。monorepo 中若
Android Studio 只打开 `repo/android`，节点路径应相对 `repo/android`，不能相对上层 Git
root。插件会在写 `accepted` 回执前，使用自己的 canonical project root 检查每个节点文件
和行列；根目录不匹配会返回 `LOAD_FAILED`，不会接受一个无法导航的 Call Flow。

## 请求格式

请求是 UTF-8 JSON 对象，最大 2 MiB。它在普通 Call Flow JSON 顶层增加 `_delivery`：

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

这是请求唯一允许的 `_delivery` 格式，不接受额外的项目选择字段。AI 无需读取工作目录、
解析符号链接或 canonicalize 项目路径；项目根由插件从 Android Studio Project 获得。
Call Flow `location.path` 始终相对插件绑定的当前项目根目录，并使用 `/` 分隔。

顶层 Call Flow 的 `version` 与 `_delivery.version` 是两个独立版本：Call Flow 内容版本仍为
`1.0`，文件投递协议版本为 `2.0`。插件保存的原始 JSON 包含 `_delivery`，但 Call Flow 解析
和导航不会把它视为节点数据。

## 原子发布步骤

假设 `requestId` 是 `f6376eb3-a71b-44a4-ac91-98af42815e29`，AI 必须：

1. 确认 `inbox` 是当前用户拥有的真实目录，不是符号链接。
2. 在 `inbox` 同一目录创建
   `.request-f6376eb3-a71b-44a4-ac91-98af42815e29.tmp`。
3. 写入完整 UTF-8 JSON，刷新并关闭文件；POSIX 平台推荐设置为 `0600`。
4. 在同一目录原子创建最终名称
   `request-f6376eb3-a71b-44a4-ac91-98af42815e29.json`，且不得覆盖已有文件。仓库发布器
   使用 hard link 创建最终名称后删除临时名称；自定义客户端也可以使用操作系统明确保证
   no-replace 的原子 rename API，不能用可能覆盖目标的普通 rename/replace。
5. 等待 `receipts/receipt-f6376eb3-a71b-44a4-ac91-98af42815e29.json`。

插件只扫描 `request-<requestId>.json`，不会读取 `.tmp`。因此，即使 AI 写入较大的 JSON，
插件也不会看到半写文件。文件名中的 ID 必须与 `_delivery.requestId` 相同；requestId 不得
复用。跨文件系统 move/copy 不是有效发布方式，临时文件和最终文件必须位于同一个 `inbox`。

插件通常每 500 ms 扫描一次。当前 Android Studio 进程只打开一个项目时，插件绑定该项目并
将请求原子移入 `processing`；同一进程打开多个项目时则写入 `AMBIGUOUS_PROJECT` 拒绝回执。
AI 等待回执超时通常表示插件未运行或没有打开项目。

认领文件使用不可预测的内部 claim 名，不会覆盖同 requestId 的既有 processing 文件。插件
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
  "callFlowFile": "/system-temp/youngx-ai-call-flow-navigator/call-flows/project-<hash>/call-flow-<time>-<random>.json",
  "nodeCount": 12,
  "edgeCount": 14,
  "entry": "activity-on-create"
}
```

`accepted` 表示插件已经将原始 JSON 安全落盘并完成工具窗口加载。`callFlowFile` 是本次
保存文件的本机绝对路径；Windows 路径可能包含盘符和反斜杠，客户端不得假定它使用 `/`。

失败回执示例：

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
| `INVALID_DELIVERY` | 非法 UTF-8、超过 2 MiB、JSON 或 `_delivery` 格式错误 |
| `REQUEST_ID_MISMATCH` | 文件名 ID 与 `_delivery.requestId` 不一致 |
| `REQUEST_EXPIRED` | 插件处理时已超过 `expiresAtEpochMs` |
| `AMBIGUOUS_PROJECT` | 同一 Android Studio 进程打开了多个项目，无法唯一绑定当前项目 |
| `INVALID_CALL_FLOW` | Call Flow 结构、路径或认领后的内容校验失败 |
| `LOAD_FAILED` | 原始 JSON 落盘或 IDE 工具窗口加载失败 |

客户端应以回执的 `status` 为最终结果，不应仅凭请求从 `inbox` 消失就判断成功。AI 读取完
回执后可以删除它；插件也会尽力只保留最近 100 条回执。同一 requestId 已存在回执时，后续
同名重复请求不会再次加载。

## 已接受 JSON 落盘

插件只在 UTF-8、大小、投递信息与 Call Flow 协议全部校验通过后落盘，并保存与请求等价的
原始 JSON 文本，然后才交给工具窗口。写盘失败不会产生 `accepted`。

保存根目录来自 Android Studio JVM 的 `java.io.tmpdir`，不能硬编码为 `/tmp`：

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
预占。这个后备策略只用于已接受 JSON 的归档目录，绝不用于固定的 `file-ipc-v2` 交换目录。
每次成功投递创建不可预测的新文件，不会覆盖旧调用链。每个项目以最近 50 条为保留目标；
文件被占用时可能暂时超过 50。文件在 IDE 关闭后保留，之后由系统临时目录策略清理，插件
当前不会在启动时自动恢复旧文件。

## Call Flow JSON 格式

下面省略投递时必需的 `_delivery`，只展示 Call Flow 内容：

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
| `Next` | 返回当前节点的全部出边，不区分边类型 |
| `Into` | 只返回当前节点的 `step_into` 出边 |
| `Over` | 只返回当前节点的 `step_over` 出边 |
| `Out` | 只返回当前节点的 `step_out` 出边 |

候选为空时对应按钮禁用；候选只有一个时直接导航，存在多个候选时插件会按 `edges` 数组顺序
显示选择器，不会猜测唯一执行路径。选择任意候选，或从节点列表跳转到不同节点，都会把原
节点写入 Previous 历史并清空 Forward 历史。

`Next` 不是“源码下一行”，也不等同于 `next` 边：它包含当前节点的所有出边，包括
`next`、`step_into`、`step_over`、`step_out`、条件分支、返回、异步和回调。插件不会启动
调试器或补全 AI 未提供的边。

`project` 是可选字段；提供时 `revision` 必填。当前版本只校验并随原始 JSON 保存它，不做
Git revision 匹配，也不在工具窗口展示。
`edges` 字段必须存在，但单节点 Call Flow 可以使用空数组。`label`、`symbol` 和
`anchorText` 均为可选字段。

## 安全边界

- 协议没有 HTTP 服务、loopback 监听、端口、Token 或 IDE 实例记录。
- POSIX 交换目录权限为 `0700`、请求与回执为 `0600`；Windows 校验 owner，并依赖用户
  Profile 与 `%TEMP%` 的系统 ACL。Java 文件 API 无法在所有 Windows 配置中取消继承 ACL；
  如果管理员把这些父目录配置为其他普通用户可写，本协议不构成跨用户隔离边界。
- 插件拒绝符号链接、非普通请求文件和不属于当前用户的文件。
- 交换目录、回执和已接受 JSON 都位于项目目录之外，不会修改源码仓库或 `.idea`。
- 请求和回执可能包含源码路径、结构、符号与 AI 摘要，应视为项目敏感数据，不得上传、
  共享、记录到日志或提交到版本控制。

## 本地 AI 限制

文件投递要求 AI 进程与 Android Studio 位于同一台机器，并能访问同一个系统临时目录。这是
本机文件协议，不是云端传输或电子邮箱；纯云端 AI、IDE 后端不在同一主机的远程代理，以及
未获得本地文件权限的网页无法直接使用本协议。
