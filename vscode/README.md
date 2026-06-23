# Android Studio Bridge

VS Code 版 Android Studio Bridge 用来让 HTML 调用链页面里的节点跳转到
Android Studio 源码文件的指定行列。

插件会在 `127.0.0.1` 启动本地 HTTP bridge。HTML 节点请求 bridge 后，插件调用配置的
Android Studio 可执行文件打开源码。

## 配置

在 VS Code settings 中配置：

```json
{
  "asBridge.projectRoot": "D:\\android\\aosp",
  "asBridge.androidStudioExecutable": "C:\\Program Files\\Android\\Android Studio\\bin\\studio64.exe",
  "asBridge.port": 17321
}
```

`asBridge.projectRoot` 用来解析 HTML 节点里的相对路径。为空时，插件使用第一个
VS Code workspace folder。

`asBridge.androidStudioExecutable` 是 Android Studio launcher 或可执行文件路径。
Windows 建议配置 `studio64.exe` 的绝对路径。macOS / Linux 如果 `studio` 在 `PATH`
里，也可以直接配置 `studio`。

`asBridge.port` 默认是 `17321`。server 只监听 `127.0.0.1`。

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
  data-as-path="frameworks/base/core/java/android/app/Activity.java"
  data-as-line="812"
  data-as-column="1"
>
  Activity.java:812
</a>
```

`data-as-path` 必须相对 `asBridge.projectRoot`。绝对路径和 `../` 越界路径都会被拒绝。

不要把 bridge URL 放进 `href`。没有可导航 `href` 后，浏览器没有 URL 可以打开成新
tab。helper 会读取 `data-as-*`，后台请求 bridge，并处理普通 HTML 元素、SVG 元素、
嵌套 SVG 子元素、键盘激活、Cmd/Ctrl 点击和中键点击。

## 端口复用

如果另一个 VS Code 窗口、Android Studio 插件或其他 bridge 已经占用了配置端口，本
插件不会报错。它会进入 reusing 状态，不再启动第二个 server。

注意：真正处理请求的是先启动并占用端口的 bridge，所以 project root 也以先启动者为准。
如果两个插件配置的 root 不一样，请只启用一个 bridge，或者给其中一个改端口，并让 HTML
引用对应端口的 `/as-bridge.js`。

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

打包命令会生成 `../dist/android-studio-bridge-vscode-0.1.1.vsix`。
