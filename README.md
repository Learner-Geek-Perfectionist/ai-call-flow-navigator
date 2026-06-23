# Android Studio Bridge

Android Studio Bridge lets HTML call-chain pages open source files in Android
Studio at a requested line and column.

It provides two bridge implementations with the same local HTTP protocol:

- `vscode/`: a VS Code extension that listens on `127.0.0.1` and launches the
  configured Android Studio executable.
- `jetbrains/`: an Android Studio / JetBrains IDE plugin that listens inside
  the IDE and opens files in the current project.

Both implementations expose:

```text
http://127.0.0.1:17321/open?path=<project-relative-path>&line=<line>&column=<column>
```

They also serve:

```text
http://127.0.0.1:17321/as-bridge.js
```

Use that helper in generated HTML so clicks are handled quietly in the
background instead of navigating the browser to a new tab.

## HTML Target Format

Add the helper once in the HTML `<head>`:

```html
<script src="http://127.0.0.1:17321/as-bridge.js"></script>
```

Generate non-navigating nodes with `data-as-*` attributes:

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

Do not put the bridge URL in `href`. Without a navigable URL, normal click,
modifier-click, middle-click, and SVG child clicks have no bridge page to open
in a browser tab. The helper reads `data-as-path`, `data-as-line`, and
`data-as-column`, then sends a background request to `/open`.

`path` must be relative to the configured project root. Absolute paths and
`../` traversal are rejected.

## VS Code Extension

Configure VS Code settings:

```json
{
  "asBridge.projectRoot": "/Users/me/AndroidStudioProjects/MyApplication",
  "asBridge.androidStudioExecutable": "/Applications/Android Studio.app/Contents/MacOS/studio",
  "asBridge.port": 17321
}
```

If another VS Code window already owns the configured port, later windows reuse
the existing bridge quietly.

## Android Studio / JetBrains Plugin

Install the JetBrains plugin when you want Android Studio itself to own the
bridge. The plugin starts with the project and defaults to:

- project root: current IDE project base path
- port: `17321`
- enabled: `true`

Open `Settings | Tools | Android Studio Bridge` to override the project root,
change the port, or disable the bridge.

If another IDE window or VS Code already owns the configured port, the plugin
marks the bridge as reused instead of reporting a startup failure.

## Build

```bash
npm --prefix vscode install
npm test
npm run package
```

Package outputs:

```text
dist/android-studio-bridge-vscode-0.1.0.vsix
jetbrains/build/distributions/android-studio-bridge-jetbrains-0.1.0.zip
```

## License

MIT
