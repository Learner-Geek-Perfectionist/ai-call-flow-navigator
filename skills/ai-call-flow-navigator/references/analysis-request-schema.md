# Analysis request schema

只生成 analysis request 内容版本 `1.0`。发布器会自动添加 `_delivery`，不要在输入文件中编写它。

## 精确结构

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

所有对象只允许上述字段，不得增加自定义字段。顶层不得出现 `projectRoot`、`nodes`、`edges`、`frames` 或 `contexts`。

## 字段

- `version`：固定为 `"1.0"`。
- `type`：固定为 `"analysis-request"`。
- `topic`：必填非空字符串，保留用户在显式 Skill 命令中给出的分析目标。
- `entry`：AI 在当前工作树中定位的唯一入口。
  - `path`：项目相对、规范化路径，仅使用 `/`；不得是绝对路径、URI 或包含 `.`/`..` 段。
  - `line`：1-based 源码行号。
  - `column`：1-based Android Studio 列号，以 UTF-16 code unit 计数。
  - `symbol`：必填 source-level qualified symbol，最长 512 UTF-16 code units。不包含参数签名、泛型、返回类型或 JVM synthetic 名称。
- `strategy`：首版仅支持一种对象：
  - `mode`：固定为 `"static-and-live"`。
  - `scope`：固定为 `"project-code"`。

## 限制

- `topic`：最长 16,384 UTF-16 code units。
- `entry.path`：最长 4,096 UTF-16 code units。
- `entry.line` 与 `entry.column`：1 到 2,147,483,647。
- 所有字符串必须非空；识别字段不得带首尾空白或 ISO 控制字符。
- 发布器从当前工作目录验证 `entry.path`、`line` 和 `column`，但不把当前目录或解析后的绝对路径放入 payload。
