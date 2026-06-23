# Android Studio Bridge

Open source links from an HTML call-chain page in Android Studio at the
requested line and column.

The extension starts a local HTTP bridge on `127.0.0.1`. HTML links call the
bridge, and the bridge launches the configured Android Studio executable.

## Configuration

Set these in VS Code settings:

```json
{
  "asBridge.projectRoot": "D:\\android\\aosp",
  "asBridge.androidStudioExecutable": "C:\\Program Files\\Android\\Android Studio\\bin\\studio64.exe",
  "asBridge.port": 17321
}
```

`asBridge.projectRoot` is the source root used to resolve relative paths from
HTML links. If it is empty, the extension uses the first VS Code workspace
folder.

`asBridge.androidStudioExecutable` is the Android Studio launcher or executable.
On Windows, prefer an absolute path to `studio64.exe`. On macOS or Linux, a
command-line launcher such as `studio` also works if it is on `PATH`.

`asBridge.port` defaults to `17321`. The server listens on `127.0.0.1` only.

## HTML Target Format

Add the bridge helper once in the HTML `<head>`:

```html
<script src="http://127.0.0.1:17321/as-bridge.js"></script>
```

Then generate non-navigating targets like this:

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

`data-as-path` must be relative to `asBridge.projectRoot`. Absolute paths and
`../` traversal are rejected.

Do not put the bridge URL in `href`. With no navigable `href`, the browser has
no URL to open in a new tab. The helper reads `data-as-*`, sends a background
request to the bridge, and handles ordinary HTML elements, SVG elements, nested
SVG children, keyboard activation, and modifier or middle-click activation.

If another VS Code window already owns the configured port, later windows reuse
that existing bridge instead of starting a second server.

## Commands

- `Android Studio Bridge: Restart Server`
- `Android Studio Bridge: Show Status`

The status bar item shows the active bridge port when the server is running.

## Development

```bash
npm install
npm test
npm run package
```

The package command creates `../dist/android-studio-bridge-vscode-0.1.0.vsix`.
