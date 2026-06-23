# Android Studio Bridge

Android Studio Bridge 用来让 HTML 调用链页面里的节点跳转到 Android
Studio 源码文件的指定行列。

仓库里有两套实现，目录结构是：

- `vscode/`：VS Code 插件。监听 `127.0.0.1`，收到请求后调用配置的
  Android Studio 可执行文件打开源码。
- `jetbrains/`：Android Studio / JetBrains 插件。直接在 IDE 内监听
  `127.0.0.1`，收到请求后在当前项目里打开源码。

两套实现使用同一个本地 HTTP 协议：

```text
http://127.0.0.1:17321/open?path=<project-relative-path>&line=<line>&column=<column>
```

并且都会提供 helper 脚本：

```text
http://127.0.0.1:17321/as-bridge.js
```

生成 HTML 时引入这个脚本，点击节点会在后台请求 bridge，不会把浏览器或
VS Code Preview 导航到新的 `127.0.0.1/open?...` tab。

## HTML 节点格式

在 HTML `<head>` 中引入一次 helper：

```html
<script src="http://127.0.0.1:17321/as-bridge.js"></script>
```

节点使用 `data-as-*`，不要使用 `href`：

```html
<a
  role="button"
  tabindex="0"
  data-as-path="app/src/main/java/com/example/MainActivity.kt"
  data-as-line="42"
  data-as-column="1"
>
  MainActivity.kt:42
</a>
```

不要把 bridge URL 放进 `href`。没有可导航 URL 后，普通点击、Cmd/Ctrl
点击、中键点击、SVG 子元素点击都没有 bridge 页面可以打开成新 tab。helper
会读取 `data-as-path`、`data-as-line`、`data-as-column`，然后后台请求
`/open`。

`path` 必须是相对当前配置 project root 的路径。绝对路径和 `../` 越界路径都会
被拒绝。

## 端口复用

默认端口是 `17321`。如果 VS Code 插件和 Android Studio 插件都安装了，不会因为
端口冲突弹错误：

- 谁先启动，谁真正监听 `127.0.0.1:17321`。
- 后启动的一方发现端口被占用后，会进入 reusing 状态，不再启动第二个 server。
- 后启动的一方只是“安静复用已有端口”，不是共享配置。

注意最后一点：真正处理 `/open` 请求的是先启动的 bridge，所以 project root 也以
先启动者为准。如果 VS Code 和 AS 配置的 root 不一样，请只保留一个 bridge 启用，
或者给其中一个改成不同端口，并在 HTML 里引用对应端口的 `/as-bridge.js`。

## VS Code 插件

在 VS Code settings 中配置：

```json
{
  "asBridge.projectRoot": "/Users/me/AndroidStudioProjects/MyApplication",
  "asBridge.androidStudioExecutable": "/Applications/Android Studio.app/Contents/MacOS/studio",
  "asBridge.port": 17321
}
```

如果 `asBridge.projectRoot` 为空，插件会使用第一个 VS Code workspace folder。
`asBridge.androidStudioExecutable` 是 Android Studio launcher 或可执行文件路径。

## Android Studio / JetBrains 插件

如果希望 Android Studio 自己拥有 bridge，就安装 `jetbrains/` 插件。插件随项目启动，
默认配置是：

- project root：当前 IDE 项目根目录
- port：`17321`
- enabled：`true`

可以在 `Settings | Tools | Android Studio Bridge` 中修改 project root、端口或禁用
bridge。

## 构建

```bash
npm --prefix vscode install
npm test
npm run package
```

产物位置：

```text
dist/android-studio-bridge-vscode-0.1.1.vsix
jetbrains/build/distributions/android-studio-bridge-jetbrains-0.1.1.zip
```

## 许可证

MIT
