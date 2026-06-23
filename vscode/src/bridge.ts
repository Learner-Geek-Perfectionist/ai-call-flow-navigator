import http from "node:http";
import path from "node:path";
import { spawn, type SpawnOptions } from "node:child_process";

export interface OpenRequest {
  relativePath: string;
  line: number;
  column: number;
}

export interface LaunchRequest {
  absolutePath: string;
  line: number;
  column: number;
}

export interface BridgeConfig {
  projectRoot: string;
  androidStudioExecutable: string;
  port: number;
}

export interface BridgeServer {
  port: number;
  close: () => Promise<void>;
}

export type Launcher = (
  executable: string,
  args: string[],
  options: SpawnOptions
) => void;

export function createOpenRequest(rawUrl: string): OpenRequest {
  const url = new URL(rawUrl, "http://127.0.0.1");

  if (url.pathname !== "/open") {
    throw new Error(`Unsupported endpoint: ${url.pathname}`);
  }

  const relativePath = url.searchParams.get("path") ?? "";
  if (!relativePath.trim()) {
    throw new Error("path is required");
  }

  return {
    relativePath,
    line: parsePositiveInteger(url.searchParams.get("line") ?? "1", "line"),
    column: parsePositiveInteger(
      url.searchParams.get("col") ?? url.searchParams.get("column") ?? "1",
      "column"
    )
  };
}

export function resolveInsideRoot(projectRoot: string, relativePath: string): string {
  if (!projectRoot.trim()) {
    throw new Error("projectRoot is required");
  }

  if (isAnyPlatformAbsolute(relativePath)) {
    throw new Error("Only relative paths are allowed");
  }

  if (relativePath.includes("\0")) {
    throw new Error("Path contains an invalid character");
  }

  const root = path.resolve(projectRoot);
  const resolved = path.resolve(root, relativePath);
  const relation = path.relative(root, resolved);

  if (relation === "" || (!relation.startsWith("..") && !path.isAbsolute(relation))) {
    return resolved;
  }

  throw new Error("Path escapes project root");
}

export function buildStudioArgs(request: LaunchRequest): string[] {
  return [
    "--line",
    String(request.line),
    "--column",
    String(request.column),
    request.absolutePath
  ];
}

export function openInAndroidStudio(
  executable: string,
  request: LaunchRequest,
  launcher: Launcher = defaultLauncher
): void {
  if (!executable.trim()) {
    throw new Error("androidStudioExecutable is required");
  }

  launcher(executable, buildStudioArgs(request), {
    detached: true,
    stdio: "ignore",
    shell: needsShell(executable)
  });
}

export function startBridgeServer(
  config: BridgeConfig,
  launcher: Launcher = defaultLauncher
): Promise<BridgeServer> {
  const server = http.createServer((request, response) => {
    const rawUrl = request.url ?? "/";
    const url = new URL(rawUrl, "http://127.0.0.1");

    if (request.method === "OPTIONS") {
      sendNoContent(response);
      return;
    }

    if (url.pathname === "/health") {
      if (request.method !== "GET") {
        sendText(response, 405, "Method not allowed");
        return;
      }

      sendText(response, 200, "OK");
      return;
    }

    if (url.pathname === "/as-bridge.js") {
      if (request.method !== "GET") {
        sendText(response, 405, "Method not allowed");
        return;
      }

      sendJavaScript(response, quietScript());
      return;
    }

    if (url.pathname !== "/open") {
      sendText(response, 404, `Unsupported endpoint: ${url.pathname}`);
      return;
    }

    if (request.method !== "GET" && request.method !== "POST") {
      sendText(response, 405, "Method not allowed");
      return;
    }

    try {
      const openRequest = createOpenRequest(rawUrl);
      const absolutePath = resolveInsideRoot(config.projectRoot, openRequest.relativePath);

      openInAndroidStudio(
        config.androidStudioExecutable,
        {
          absolutePath,
          line: openRequest.line,
          column: openRequest.column
        },
        launcher
      );

      sendNoContent(response);
    } catch (error) {
      sendText(response, statusForError(error), messageForError(error));
    }
  });

  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(config.port, "127.0.0.1", () => {
      server.off("error", reject);
      const address = server.address();
      const port = typeof address === "object" && address ? address.port : config.port;

      resolve({
        port,
        close: () => closeServer(server)
      });
    });
  });
}

export function isAddressInUseError(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "code" in error &&
    (error as { code?: unknown }).code === "EADDRINUSE"
  );
}

function defaultLauncher(executable: string, args: string[], options: SpawnOptions): void {
  const child = spawn(executable, args, options);
  child.unref();
}

function parsePositiveInteger(value: string, name: string): number {
  if (!/^\d+$/.test(value)) {
    throw new Error(`${name} must be a positive integer`);
  }

  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 1) {
    throw new Error(`${name} must be a positive integer`);
  }

  return parsed;
}

function isAnyPlatformAbsolute(value: string): boolean {
  return path.isAbsolute(value) || path.win32.isAbsolute(value) || path.posix.isAbsolute(value);
}

function needsShell(executable: string): boolean {
  return process.platform === "win32" && /\.(cmd|bat)$/i.test(executable);
}

function sendText(response: http.ServerResponse, status: number, body: string): void {
  response.writeHead(status, {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Content-Type": "text/plain; charset=utf-8"
  });
  response.end(body);
}

function sendNoContent(response: http.ServerResponse): void {
  response.writeHead(204, {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS"
  });
  response.end();
}

function sendJavaScript(response: http.ServerResponse, body: string): void {
  response.writeHead(200, {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Content-Type": "application/javascript; charset=utf-8"
  });
  response.end(body);
}

function quietScript(): string {
  return `(() => {
  const scriptUrl = document.currentScript ? document.currentScript.src : "";
  const bridgeOrigin = scriptUrl ? new URL(scriptUrl, document.baseURI).origin : "http://127.0.0.1:17321";

  const getData = element => {
    return {
      asPath: element.dataset ? element.dataset.asPath : element.getAttribute("data-as-path"),
      asLine: element.dataset ? element.dataset.asLine : element.getAttribute("data-as-line"),
      asColumn: element.dataset
        ? (element.dataset.asColumn || element.dataset.asCol)
        : (element.getAttribute("data-as-column") || element.getAttribute("data-as-col"))
    };
  };

  const hasTarget = value => {
    return value instanceof Element && Boolean(getData(value).asPath);
  };

  const findTarget = event => {
    const path = typeof event.composedPath === "function" ? event.composedPath() : [];

    for (const value of path) {
      if (hasTarget(value)) {
        return value;
      }
    }

    let current = event.target;

    while (current instanceof Element) {
      if (hasTarget(current)) {
        return current;
      }

      current = current.parentElement || current.parentNode;
    }

    return null;
  };

  const toBridgeRequest = element => {
    const { asPath, asLine, asColumn } = getData(element);
    if (!asPath) {
      return "";
    }

    const line = asLine || "1";
    const column = asColumn || "1";
    return bridgeOrigin
      + "/open?path=" + encodeURIComponent(asPath)
      + "&line=" + encodeURIComponent(line)
      + "&column=" + encodeURIComponent(column);
  };

  const shouldHandleEvent = event => {
    if (event.type === "click") {
      return event.button === 0;
    }

    if (event.type === "auxclick") {
      return event.button === 1;
    }

    if (event.type === "keydown") {
      return event.key === "Enter" || event.key === " ";
    }

    return false;
  };

  const openQuietly = requestUrl => {
    if (navigator.sendBeacon && navigator.sendBeacon(requestUrl, "")) {
      return;
    }

    fetch(requestUrl, {
      method: "POST",
      mode: "cors",
      credentials: "omit",
      keepalive: true
    }).catch(() => {});
  };

  const handleActivation = event => {
    if (!shouldHandleEvent(event)) {
      return;
    }

    const target = findTarget(event);
    const requestUrl = target ? toBridgeRequest(target) : "";

    if (!requestUrl) {
      return;
    }

    event.preventDefault();
    event.stopImmediatePropagation();
    openQuietly(requestUrl);
  };

  document.addEventListener("click", handleActivation, true);
  document.addEventListener("auxclick", handleActivation, true);
  document.addEventListener("keydown", handleActivation, true);
})();`;
}

function statusForError(error: unknown): number {
  const message = messageForError(error);

  if (message.includes("Unsupported endpoint")) {
    return 404;
  }

  if (
    message.includes("Only relative paths are allowed") ||
    message.includes("Path escapes project root")
  ) {
    return 403;
  }

  return 400;
}

function messageForError(error: unknown): string {
  return error instanceof Error ? error.message : "Unknown error";
}

function closeServer(server: http.Server): Promise<void> {
  return new Promise((resolve, reject) => {
    server.close(error => {
      if (error) {
        reject(error);
        return;
      }

      resolve();
    });
  });
}
