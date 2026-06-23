import * as vscode from "vscode";
import {
  isAddressInUseError,
  startBridgeServer,
  type BridgeConfig,
  type BridgeServer
} from "./bridge";

let server: BridgeServer | undefined;
let existingBridgePort: number | undefined;
let statusBar: vscode.StatusBarItem | undefined;

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  statusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
  statusBar.command = "asBridge.showStatus";
  context.subscriptions.push(statusBar);

  context.subscriptions.push(
    vscode.commands.registerCommand("asBridge.restart", async () => {
      await restartServer();
    }),
    vscode.commands.registerCommand("asBridge.showStatus", () => {
      const config = readConfig();
      const state = server
        ? `running on 127.0.0.1:${server.port}`
        : existingBridgePort
          ? `reusing the existing bridge on 127.0.0.1:${existingBridgePort}`
          : "stopped";
      vscode.window.showInformationMessage(
        `Android Studio Bridge is ${state}. Root: ${config.projectRoot}. Launcher: ${config.androidStudioExecutable}.`
      );
    }),
    vscode.workspace.onDidChangeConfiguration(async event => {
      if (
        event.affectsConfiguration("asBridge.projectRoot") ||
        event.affectsConfiguration("asBridge.androidStudioExecutable") ||
        event.affectsConfiguration("asBridge.port")
      ) {
        await restartServer();
      }
    }),
    {
      dispose: () => {
        void stopServer();
      }
    }
  );

  await restartServer();
}

export async function deactivate(): Promise<void> {
  await stopServer();
}

async function restartServer(): Promise<void> {
  await stopServer();

  const config = readConfig();
  existingBridgePort = undefined;
  updateStatus("starting");

  try {
    server = await startBridgeServer(config);
    updateStatus("running", server.port);
  } catch (error) {
    server = undefined;
    if (isAddressInUseError(error)) {
      existingBridgePort = config.port;
      updateStatus("existing", config.port);
      return;
    }

    updateStatus("failed");
    vscode.window.showErrorMessage(
      `Android Studio Bridge failed to start: ${messageForError(error)}`
    );
  }
}

async function stopServer(): Promise<void> {
  existingBridgePort = undefined;

  if (!server) {
    return;
  }

  const closing = server;
  server = undefined;
  await closing.close();
  updateStatus("stopped");
}

function readConfig(): BridgeConfig {
  const config = vscode.workspace.getConfiguration("asBridge");
  const configuredRoot = config.get<string>("projectRoot")?.trim() ?? "";
  const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? "";
  const projectRoot = configuredRoot || workspaceRoot;

  return {
    projectRoot,
    androidStudioExecutable: config.get<string>("androidStudioExecutable")?.trim() || "studio",
    port: config.get<number>("port") ?? 17321
  };
}

function updateStatus(
  state: "starting" | "running" | "existing" | "stopped" | "failed",
  port?: number
): void {
  if (!statusBar) {
    return;
  }

  if (state === "running") {
    statusBar.text = `AS Bridge :${port}`;
    statusBar.tooltip = `Android Studio Bridge is listening on 127.0.0.1:${port}`;
    statusBar.show();
    return;
  }

  if (state === "existing") {
    statusBar.text = `AS Bridge reusing :${port}`;
    statusBar.tooltip = `Reusing the bridge already listening on 127.0.0.1:${port}`;
    statusBar.show();
    return;
  }

  if (state === "failed") {
    statusBar.text = "AS Bridge failed";
    statusBar.tooltip = "Android Studio Bridge failed to start";
    statusBar.show();
    return;
  }

  statusBar.text = `AS Bridge ${state}`;
  statusBar.tooltip = `Android Studio Bridge is ${state}`;
  statusBar.show();
}

function messageForError(error: unknown): string {
  return error instanceof Error ? error.message : "Unknown error";
}
