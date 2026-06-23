# Android Studio Bridge VS Code 插件

这是 Android Studio Bridge 的 VS Code 实现。它在 VS Code 中启动一个本地 HTTP
bridge，让 HTML Preview 里的源码节点可以跳转到 Android Studio 的指定文件、行号和列号。

VS Code 插件适合这些场景：

- HTML 调用链页面主要在 VS Code 中预览。
- Android Studio 通过命令行 launcher 打开文件。
- 希望由 VS Code workspace root 来解析 HTML 节点里的相对路径。

## 工作方式

插件监听 `127.0.0.1:<port>`，默认端口 `17321`。HTML 页面通过 helper 脚本发送后台
请求：

```text
http://127.0.0.1:17321/open?path=app/src/main/java/com/example/MainActivity.kt&line=42&column=1
```

插件解析 `path`，拼接 project root，然后调用配置的 Android Studio 可执行文件。

## 配置

在 VS Code settings 中配置：

```json
{
  "asBridge.projectRoot": "D:\\android\\aosp",
  "asBridge.androidStudioExecutable": "C:\\Program Files\\Android\\Android Studio\\bin\\studio64.exe",
  "asBridge.port": 17321
}
```

配置项说明：

- `asBridge.projectRoot`：源码根目录。为空时使用第一个 VS Code workspace folder。
- `asBridge.androidStudioExecutable`：Android Studio launcher 或可执行文件路径。
- `asBridge.port`：本地 bridge 端口，默认 `17321`。

macOS / Linux 上，如果 `studio` 已经在 `PATH` 中，可以直接配置 `studio`。Windows 上
通常配置 `studio64.exe` 的绝对路径。

## HTML 节点格式

在 HTML `<head>` 中引入 helper：

```html
<script src="http://127.0.0.1:17321/as-bridge.js"></script>
```

节点推荐使用 `data-as-*` 属性：

```html
<a
  role="button"
  tabindex="0"
  data-as-path="frameworks/base/core/java/android/app/Activity.java"
  data-as-line="812"
  data-as-column="1"
>
  Activity.java:812
</a>
```

helper 会读取节点上的 `data-as-path`、`data-as-line`、`data-as-column`，并把点击转换为
后台 `/open` 请求。这个模式适用于普通 HTML 节点、SVG 节点、嵌套 SVG 子元素、键盘激活、
Cmd/Ctrl 点击和中键点击。

## 端口模型

如果另一个 VS Code 窗口、Android Studio 插件或其他 bridge 已经绑定当前端口，本插件会
显示 reusing 状态。当前端口的 bridge owner 负责处理 `/open` 请求，project root 使用
owner 的配置。

## 命令

- `Android Studio Bridge: Restart Server`
- `Android Studio Bridge: Show Status`

状态栏会显示当前 bridge 端口或 reusing 状态。

## 开发

```bash
npm install
npm test
npm run package
```

打包命令会生成：

```text
../dist/android-studio-bridge-vscode-0.1.1.vsix
```
