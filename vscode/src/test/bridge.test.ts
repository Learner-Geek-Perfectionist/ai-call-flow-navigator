import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";
import {
  buildStudioArgs,
  createOpenRequest,
  isAddressInUseError,
  resolveInsideRoot,
  startBridgeServer,
  type OpenRequest
} from "../bridge";

const root = process.platform === "win32"
  ? "C:\\src\\aosp"
  : "/src/aosp";

test("createOpenRequest parses a valid open URL", () => {
  const request = createOpenRequest(
    "/open?path=frameworks/base/core/java/android/app/Activity.java&line=812&col=5"
  );

  assert.deepEqual(request, {
    relativePath: "frameworks/base/core/java/android/app/Activity.java",
    line: 812,
    column: 5
  } satisfies OpenRequest);
});

test("createOpenRequest defaults missing column to 1", () => {
  const request = createOpenRequest("/open?path=app/src/main/java/Foo.kt&line=42");

  assert.equal(request.column, 1);
});

test("createOpenRequest accepts column as an alias for col", () => {
  const request = createOpenRequest("/open?path=app/src/main/java/Foo.kt&line=42&column=9");

  assert.equal(request.column, 9);
});

test("createOpenRequest rejects non-open endpoints", () => {
  assert.throws(
    () => createOpenRequest("/health?path=app/src/main/java/Foo.kt&line=42"),
    /Unsupported endpoint/
  );
});

test("createOpenRequest rejects invalid line and column values", () => {
  assert.throws(
    () => createOpenRequest("/open?path=app/src/main/java/Foo.kt&line=0&col=1"),
    /line must be a positive integer/
  );
  assert.throws(
    () => createOpenRequest("/open?path=app/src/main/java/Foo.kt&line=1&col=-2"),
    /column must be a positive integer/
  );
});

test("resolveInsideRoot resolves a relative path within project root", () => {
  const resolved = resolveInsideRoot(root, "frameworks/base/Activity.java");

  assert.equal(resolved, path.resolve(root, "frameworks/base/Activity.java"));
});

test("resolveInsideRoot rejects absolute paths", () => {
  const absolute = path.resolve(root, "frameworks/base/Activity.java");

  assert.throws(
    () => resolveInsideRoot(root, absolute),
    /Only relative paths are allowed/
  );
});

test("resolveInsideRoot rejects parent-directory traversal", () => {
  assert.throws(
    () => resolveInsideRoot(root, "../outside/File.kt"),
    /Path escapes project root/
  );
});

test("buildStudioArgs uses line, column, and absolute path", () => {
  const absolutePath = path.resolve(root, "app/src/main/java/Foo.kt");
  const args = buildStudioArgs({
    absolutePath,
    line: 42,
    column: 7
  });

  assert.deepEqual(args, ["--line", "42", "--column", "7", absolutePath]);
});

test("startBridgeServer returns the actual port when configured with port 0", async () => {
  const server = await startBridgeServer({
    projectRoot: root,
    androidStudioExecutable: "studio",
    port: 0
  });

  try {
    assert.ok(server.port > 0);
  } finally {
    await server.close();
  }
});

test("startBridgeServer returns 204 with no body after opening a source link", async () => {
  const launches: Array<{ executable: string; args: string[] }> = [];
  const server = await startBridgeServer(
    {
      projectRoot: root,
      androidStudioExecutable: "studio",
      port: 0
    },
    (executable, args) => {
      launches.push({ executable, args });
    }
  );

  try {
    const response = await fetch(
      `http://127.0.0.1:${server.port}/open?path=app/src/main/java/Foo.kt&line=42&col=7`
    );

    assert.equal(response.status, 204);
    assert.equal(await response.text(), "");
    assert.equal(launches.length, 1);
    assert.deepEqual(launches[0], {
      executable: "studio",
      args: [
        "--line",
        "42",
        "--column",
        "7",
        path.resolve(root, "app/src/main/java/Foo.kt")
      ]
    });
  } finally {
    await server.close();
  }
});

test("startBridgeServer accepts quiet POST requests from sendBeacon", async () => {
  const launches: Array<{ executable: string; args: string[] }> = [];
  const server = await startBridgeServer(
    {
      projectRoot: root,
      androidStudioExecutable: "studio",
      port: 0
    },
    (executable, args) => {
      launches.push({ executable, args });
    }
  );

  try {
    const response = await fetch(
      `http://127.0.0.1:${server.port}/open?path=app/src/main/java/Foo.kt&line=42&column=7`,
      { method: "POST" }
    );

    assert.equal(response.status, 204);
    assert.equal(launches.length, 1);
  } finally {
    await server.close();
  }
});

test("startBridgeServer serves a quiet click helper script", async () => {
  const server = await startBridgeServer({
    projectRoot: root,
    androidStudioExecutable: "studio",
    port: 0
  });

  try {
    const response = await fetch(`http://127.0.0.1:${server.port}/as-bridge.js`);
    const script = await response.text();

    assert.equal(response.status, 200);
    assert.match(response.headers.get("content-type") ?? "", /application\/javascript/);
    assert.match(script, /document\.currentScript/);
    assert.match(script, /bridgeOrigin/);
    assert.match(script, /asPath/);
    assert.match(script, /asLine/);
    assert.match(script, /asColumn/);
    assert.match(script, /encodeURIComponent/);
    assert.match(script, /composedPath/);
    assert.match(script, /keydown/);
    assert.match(script, /stopImmediatePropagation/);
    assert.match(script, /auxclick/);
    assert.doesNotMatch(script, /\bhref\b/i);
    assert.doesNotMatch(script, /getAttribute\("href"\)/);
    assert.doesNotMatch(script, /baseVal/);
    assert.doesNotMatch(script, /as-src/);
    assert.match(script, /preventDefault/);
    assert.match(script, /sendBeacon/);
  } finally {
    await server.close();
  }
});

test("isAddressInUseError detects EADDRINUSE errors", () => {
  const error = Object.assign(new Error("listen EADDRINUSE"), { code: "EADDRINUSE" });

  assert.equal(isAddressInUseError(error), true);
  assert.equal(isAddressInUseError(new Error("other")), false);
});
