# AI Call Flow Analysis Request 本地文件协议

本文档定义 AI Skill 与 Android Studio 插件之间的本地 File IPC `3.0`。协议使用一种 `analysis-request`：AI 定位入口和分析策略，Android Studio 使用当前 Project 生成 Call Flow。

## 交换目录

所有文件位于当前操作系统用户的标准临时目录：

```text
<system-temp>/youngx-ai-call-flow-navigator/file-ipc-v3/
  .consumer.lock
  inbox/
  processing/
  receipts/
```

- `inbox/`：发布完成、等待插件认领的请求。
- `processing/`：插件已经认领的请求。
- `receipts/`：最终成功或失败回执。
- `.consumer.lock`：Android Studio 消费进程持有的排他锁。

POSIX 上交换目录权限为 `0700`，请求、回执和锁文件为 `0600`。Windows 使用当前用户 Profile 的临时目录 ACL，并校验 owner。交换目录、文件和锁不得是符号链接。

Android Studio 使用自定义 `-Djava.io.tmpdir` 时，发布器必须通过 `--temp-root` 使用同一目录。

## 请求文件

文件名固定为：

```text
request-<UUID>.json
```

UUID 必须与 `_delivery.requestId` 一致。请求是 UTF-8 JSON，精确结构如下：

请求与回执按完整 UTF-8 JSON 读取，协议支持任意文件大小，实际容量由本机存储与进程内存决定。

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
  },
  "_delivery": {
    "version": "3.0",
    "requestId": "f6376eb3-a71b-44a4-ac91-98af42815e29",
    "createdAtEpochMs": 1784044800000,
    "expiresAtEpochMs": 1784044920000
  }
}
```

顶层、`entry`、`strategy` 和 `_delivery` 均使用表格列出的精确字段集。

### 业务字段

| 字段 | 要求 |
| --- | --- |
| `version` | 固定为 `"1.0"` |
| `type` | 固定为 `"analysis-request"` |
| `topic` | 用户在显式 Skill 命令中给出的非空分析目标 |
| `entry.path` | 当前 Android Studio Project 内的规范化相对路径，仅使用 `/` |
| `entry.line` | 1-based 源码行号 |
| `entry.column` | 1-based Android Studio 列号，使用 UTF-16 code unit |
| `entry.symbol` | 必填 source-level qualified symbol，不含 JVM synthetic 名称或方法签名 |
| `strategy.mode` | 首版固定为 `"static-and-live"` |
| `strategy.scope` | 首版固定为 `"project-code"` |

项目根由 Android Studio 的 Project 模型提供；插件使用 `entry.path` 和 `entry.symbol` 在当前打开的项目中校验并定位入口。多个项目同时可匹配请求时，插件返回 `AMBIGUOUS_PROJECT`。

### 投递字段

| 字段 | 要求 |
| --- | --- |
| `_delivery.version` | 固定为 `"3.0"` |
| `_delivery.requestId` | UUID，与请求文件名一致 |
| `_delivery.createdAtEpochMs` | 创建时间，Unix epoch 毫秒 |
| `_delivery.expiresAtEpochMs` | 过期时间，必须晚于创建时间 |

`publish_analysis_request.py` 负责向 Skill 产生的业务 JSON 添加全部 `_delivery` 字段。

## 原子发布与认领

1. 发布器在 `inbox/` 中使用 `O_CREAT | O_EXCL`创建 `.request-<UUID>.tmp`。
2. 写入完整 UTF-8 JSON，刷新文件后，使用 hard link 以 no-replace 语义创建 `request-<UUID>.json`。
3. 删除临时 hard link，并在支持时刷新目录。
4. Android Studio 仅处理已发布的 `.json`，以 no-replace 原子移动到 `processing/`后开始校验和分析。
5. 插件生成并加载 Call Flow 后写入最终回执，然后清理 `processing/` 中的请求。

标准 APFS、ext4 和 NTFS 临时文件系统支持该流程。目标文件使用 no-replace 语义发布。

发布器等待回执超时时：

- 请求仍在 `inbox/` 中：移除请求，返回 `publisherStatus: not_delivered` 和 `retrySafe: true`。
- 请求已被认领：返回 `publisherStatus: unknown`、精确 `requestId` 与 `receiptFile`；下一步检查 Android Studio 和回执文件。

## 回执

回执文件名为：

```text
receipt-<UUID>.json
```

### 成功

```json
{
  "version": "3.0",
  "requestId": "f6376eb3-a71b-44a4-ac91-98af42815e29",
  "status": "accepted",
  "completedAtEpochMs": 1784044800521,
  "topic": "分析登录按钮到首页的执行路径",
  "entry": {
    "path": "app/src/main/java/com/example/LoginFragment.kt",
    "line": 42,
    "column": 5,
    "symbol": "com.example.LoginFragment.onLoginClick"
  },
  "generated": {
    "nodeCount": 12,
    "edgeCount": 14,
    "entryNodeId": "login-click"
  }
}
```

`accepted` 表示插件已根据请求生成 Call Flow，并完成 **Call Flow** 工具窗口加载。

### 失败

```json
{
  "version": "3.0",
  "requestId": "f6376eb3-a71b-44a4-ac91-98af42815e29",
  "status": "rejected",
  "completedAtEpochMs": 1784044800521,
  "error": {
    "code": "INVALID_ANALYSIS_REQUEST",
    "message": "entry symbol cannot be resolved"
  }
}
```

错误码：

| 错误码 | 含义 |
| --- | --- |
| `INVALID_DELIVERY` | UTF-8、JSON 或 `_delivery` 格式错误 |
| `REQUEST_ID_MISMATCH` | 文件名 UUID 与 `_delivery.requestId` 不一致 |
| `REQUEST_EXPIRED` | 插件处理时已超过 `expiresAtEpochMs` |
| `INVALID_ANALYSIS_REQUEST` | 业务 schema、入口路径、行列、symbol 或策略无效 |
| `AMBIGUOUS_PROJECT` | 多个已打开 Project 同时匹配入口 |
| `ANALYSIS_FAILED` | PSI/UAST 或实时分析失败，未能加载结果 |

客户端以回执 `status` 为最终结果。读取后可以删除回执；插件可以按 `requestId` 去重并限制历史回执数量。

## 本地数据安全

- IPC 仅使用同一台机器、同一操作系统用户的临时文件系统。
- 交换文件位于源码仓库和 `.idea` 之外。
- 请求包含源码相对路径、符号和用户 topic，应按项目敏感数据管理。
- 请求不传输 Project 绝对根路径。
