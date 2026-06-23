# Android Studio Bridge JetBrains 插件

这是 Android Studio Bridge 的 JetBrains 实现。插件运行在 Android Studio 或其他
JetBrains IDE 内部，负责接收 HTML 调用链节点发来的本地 HTTP 请求，并在当前 IDE 项目中
打开对应源码文件。

相比 VS Code bridge，JetBrains 插件直接使用 IDE API 打开文件，路径解析和编辑器跳转都在
Android Studio 当前项目上下文中完成。主要在 AS 中阅读源码时，推荐使用这个版本作为主
bridge。

## 工作方式

插件监听 `127.0.0.1:<port>`，默认端口 `17321`。当 HTML 节点发出请求：

```text
http://127.0.0.1:17321/open?path=app/src/main/java/com/example/MainActivity.kt&line=42&column=1
```

插件会把 `path` 按 project root 解析成本地文件，然后在当前 IDE 项目里打开指定行列。

## 配置

配置入口：

```text
Settings | Tools | Android Studio Bridge
```

配置项：

- `Enable local bridge`：控制当前项目是否启动 bridge。
- `Project root`：源码根目录。为空时使用当前 IDE 项目根目录。
- `Port`：本地 bridge 端口，默认 `17321`。

## HTML Helper

在 HTML `<head>` 中引入 helper：

```html
<script src="http://127.0.0.1:17321/as-bridge.js"></script>
```

节点推荐使用 data 属性：

```html
<a role="button" tabindex="0"
   data-as-path="app/src/main/java/com/example/MainActivity.kt"
   data-as-line="42"
   data-as-column="1">
  MainActivity.kt:42
</a>
```

helper 负责把节点点击转换为后台 `/open` 请求，Android Studio Bridge 负责完成 IDE 内的
文件跳转。

## 端口模型

同一个端口只会有一个 bridge owner。AS 插件、VS Code 插件或多个 IDE 窗口同时存在时，最
先绑定端口的一方成为 owner，后启动的一方进入 reusing 状态。`/open` 请求由 owner 处理，
project root 使用 owner 的配置。

## 构建

```bash
./gradlew test
./gradlew buildPlugin
```

插件包生成在：

```text
build/distributions/android-studio-bridge-jetbrains-0.1.2.zip
```
