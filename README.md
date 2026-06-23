# Android Studio Bridge

Android Studio Bridge 是一组本地插件，用来把 HTML 调用链图里的节点连接到
Android Studio 源码。你可以在 VS Code 或浏览器里预览 HTML，点击节点后，Android
Studio 会打开对应项目文件并跳到指定行列。

这个项目适合源码阅读、调用链梳理、AI 生成代码分析报告等场景。HTML 负责展示链路，
bridge 负责把节点里的文件路径、行号和列号交给 Android Studio。

## 项目结构

```text
android-studio-bridge/
  vscode/      VS Code 插件，适合作为 HTML Preview 所在编辑器的 bridge
  jetbrains/   Android Studio / JetBrains 插件，适合让 AS 自己接住跳转请求
```

两套插件使用同一个本地 HTTP 协议：

```text
http://127.0.0.1:17321/open?path=<project-relative-path>&line=<line>&column=<column>
```

并提供同一个 HTML helper：

```text
http://127.0.0.1:17321/as-bridge.js
```

## 推荐工作流

1. 在 Android Studio 中打开源码项目。
2. 在 VS Code 或浏览器中打开生成的 HTML 调用链页面。
3. HTML 页面引入 `as-bridge.js`。
4. 调用链节点使用 `data-as-path`、`data-as-line`、`data-as-column` 描述目标源码位置。
5. 点击节点后，bridge 在后台请求 `/open`，Android Studio 打开对应文件。

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
  data-as-path="app/src/main/java/com/example/MainActivity.kt"
  data-as-line="42"
  data-as-column="1"
>
  MainActivity.kt:42
</a>
```

这个格式把“可点击节点”和“可导航 URL”分离开：页面点击由 helper 接管，后台请求
bridge，浏览器地址栏保持在原来的 HTML 页面。helper 支持普通 HTML 元素、SVG 元素、
嵌套 SVG 子元素、键盘激活、Cmd/Ctrl 点击和中键点击。

`data-as-path` 是相对 project root 的路径。bridge 的解析范围限定在 project root 内，
适合直接放 Android 项目中的 `app/src/...`、`frameworks/base/...` 等路径。

## 两种 bridge

### Android Studio / JetBrains 插件

`jetbrains/` 插件运行在 Android Studio 内部。它直接调用 JetBrains Platform API 打开
当前项目中的文件，少了一次外部 `studio` launcher 转发。主要阅读源码时，推荐让 AS
插件作为主 bridge。

默认配置：

- project root：当前 IDE 项目根目录
- port：`17321`
- enabled：`true`

配置入口：

```text
Settings | Tools | Android Studio Bridge
```

### VS Code 插件

`vscode/` 插件运行在 VS Code 中。它接收 HTML Preview 的请求，再调用配置的 Android
Studio 可执行文件打开源码。它适合把 VS Code 作为独立 bridge，或者在 Android Studio
尚未打开时通过 launcher 唤起 AS。

VS Code settings 示例：

```json
{
  "asBridge.projectRoot": "/Users/me/AndroidStudioProjects/MyApplication",
  "asBridge.androidStudioExecutable": "/Applications/Android Studio.app/Contents/MacOS/studio",
  "asBridge.port": 17321
}
```

## 端口模型

默认端口是 `17321`。VS Code 插件、Android Studio 插件、多个 IDE 窗口可以同时安装。
端口采用单 owner 模型：

- 最先启动并绑定端口的一方成为 bridge owner。
- 后启动的一方检测到端口已存在后进入 reusing 状态。
- `/open` 请求由当前 bridge owner 处理。
- project root 以当前 bridge owner 的配置为准。

如果 VS Code 和 Android Studio 配置了不同 root，可以选择一个插件作为主 bridge；也可以
给另一个插件配置不同端口，并让 HTML 引用对应端口的 `/as-bridge.js`。

## 构建

```bash
npm --prefix vscode install
npm test
npm run package
```

产物位置：

```text
dist/android-studio-bridge-vscode-0.1.2.vsix
jetbrains/build/distributions/android-studio-bridge-jetbrains-0.1.2.zip
```

## 许可证

MIT
