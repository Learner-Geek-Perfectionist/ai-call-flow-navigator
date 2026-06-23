# Android Studio Bridge JetBrains 插件

这个插件会在 Android Studio 或其他 JetBrains IDE 内部运行 Android Studio Bridge
HTTP server。

当 HTML 节点发出这样的请求时：

```text
http://127.0.0.1:17321/open?path=app/src/main/java/com/example/MainActivity.kt&line=42&column=1
```

插件会把 `path` 按配置的 project root 解析成本地文件，并在当前 IDE 项目里打开对应
行列。

## 配置

打开 `Settings | Tools | Android Studio Bridge`：

- `Enable local bridge`：启用或停止当前项目的 bridge。
- `Project root`：可选。为空时使用当前 IDE 项目根目录。
- `Port`：默认 `17321`。

## 端口复用

如果端口已经被另一个 IDE 窗口或 VS Code 插件占用，本插件不会弹启动失败错误。它会进入
reusing 状态，不再启动第二个 server。

注意：这不是共享配置。真正处理 `/open` 请求的是先启动并占用端口的 bridge，所以 project
root 也以先启动者为准。如果 AS 和 VS Code 配置的 root 不一样，请只启用其中一个 bridge，
或者给其中一个改端口，并在 HTML 中引用对应端口的 `/as-bridge.js`。

## HTML Helper

引入 helper 脚本：

```html
<script src="http://127.0.0.1:17321/as-bridge.js"></script>
```

节点使用 data 属性，不使用 `href`：

```html
<a role="button" tabindex="0"
   data-as-path="app/src/main/java/com/example/MainActivity.kt"
   data-as-line="42"
   data-as-column="1">
  MainActivity.kt:42
</a>
```

## 构建

```bash
./gradlew test
./gradlew buildPlugin
```

插件包生成在：

```text
build/distributions/android-studio-bridge-jetbrains-0.1.1.zip
```
