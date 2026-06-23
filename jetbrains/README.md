# Android Studio Bridge JetBrains Plugin

This plugin runs the Android Studio Bridge HTTP server inside Android Studio or
another JetBrains IDE.

When an HTML node sends a request like this:

```text
http://127.0.0.1:17321/open?path=app/src/main/java/com/example/MainActivity.kt&line=42&column=1
```

the plugin resolves `path` against the configured project root and opens the
file in the current IDE project.

## Settings

Open `Settings | Tools | Android Studio Bridge`.

- `Enable local bridge`: starts or stops the server for this project.
- `Project root`: optional. Empty means the current IDE project base path.
- `Port`: defaults to `17321`.

If the port is already used by another IDE window or the VS Code extension, this
plugin quietly reuses the existing bridge instead of showing a startup error.

## HTML Helper

Add the helper script:

```html
<script src="http://127.0.0.1:17321/as-bridge.js"></script>
```

Use data attributes, not `href`:

```html
<a role="button" tabindex="0"
   data-as-path="app/src/main/java/com/example/MainActivity.kt"
   data-as-line="42"
   data-as-column="1">
  MainActivity.kt:42
</a>
```

## Build

```bash
./gradlew test
./gradlew buildPlugin
```

The plugin package is generated at:

```text
build/distributions/android-studio-bridge-jetbrains-0.1.0.zip
```
