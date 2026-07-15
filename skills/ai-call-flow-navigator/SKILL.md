---
name: ai-call-flow-navigator
description: Analyze a local source repository from a requested entry symbol, model the meaningful execution order and branches as an exact line-and-column Call Flow, validate it, and deliver it to the AI Call Flow Navigator plugin in the currently open Android Studio project. Use when the user asks to trace, explain, visualize, step through, or navigate a function, method, callback, coroutine, request, or other code path in Android Studio.
---

# AI Call Flow Navigator

Turn static code analysis into a debugger-like source tour in Android Studio. Work from the repository that the user opened in both the terminal and Android Studio; never put a project root, port, token, or IDE instance identifier in the JSON.

## Workflow

1. Confirm that the current working directory is exactly the project root opened by Android Studio and that exactly one plugin-enabled Android Studio process has that project open. Do not automatically switch to a higher VCS/monorepo root: if Android Studio opened `repo/android`, run and calculate paths from `repo/android`, not `repo`. Do not put that absolute root in the flow.
2. Resolve the requested entry point to one exact declaration. If several symbols genuinely match and code context cannot disambiguate them, ask one short question before publishing.
3. Read enough callers, callees, overrides, callbacks, and asynchronous continuations to model the requested scope. When the user gives no boundary, follow the complete meaningful application-owned flow until it returns, reaches a terminal state, or crosses into unavailable framework/library/generated code; include material branches, catches, and asynchronous resumptions, but collapse trivial helpers. Clearly distinguish a statically supported path from behavior that depends on runtime values or framework dispatch.
4. Build one semantic Call Flow JSON object using `references/call-flow-schema.md`. Do not include `_delivery`; the publisher adds it.
5. Verify every location against the working tree:
   - `path` is repository-relative and uses `/` on every platform.
   - Lines and columns are 1-based. Columns use Android Studio's UTF-16 editor offsets.
   - Point at the expression or declaration that explains the step, not merely the beginning of the file or function.
   - When using `anchorText`, it must be single-line, start at the supplied coordinate, and occur exactly once within 20 lines. Choose a longer exact fragment when a short anchor is ambiguous.
6. Save the semantic object as a private temporary UTF-8 JSON file outside the repository. Use the operating system's secure temporary-file facility with an unpredictable name and current-user-only access (`0600` on POSIX); never follow or overwrite a pre-created path. Do not commit or log it.
7. From the repository root, run this skill's `scripts/publish_call_flow.py --delete-input` with that file. Resolve the script relative to this `SKILL.md`; do not recreate the delivery protocol by hand. The flag removes the temporary semantic file immediately after reading it.
8. Read the publisher's receipt. Only report success when `status` is `accepted`:
   - For `INVALID_CALL_FLOW` or another content error, correct the flow and publish a new request.
   - For `AMBIGUOUS_PROJECT`, ask the user to close extra Android Studio projects before retrying.
   - For `REQUEST_EXPIRED`, check that the plugin is ready, then publish a fresh request.
   - For `LOAD_FAILED`, report the IDE/plugin load error and retry only after its cause is addressed.
   - `publisherStatus: not_delivered` with `retrySafe: true` means the publisher removed an unclaimed request; after fixing the environment, a fresh request is safe.
   - `publisherStatus: unknown` includes the exact `requestId` and `receiptFile`. Do not automatically republish; inspect that path and Android Studio because the plugin may have claimed the first request.
9. Tell the user the accepted flow title and that it is ready in Android Studio's **Call Flow** tool window. Briefly mention material static-analysis uncertainty, if any.

Example publisher invocation:

```text
python3 "<this-skill-directory>/scripts/publish_call_flow.py" --delete-input "<temporary-call-flow.json>"
```

On Windows, `py -3` can be used instead of `python3`.
The publisher requires Python 3.8 or newer and has no third-party dependencies.

## Model execution, not just references

- Use `next` for ordinary same-frame ordering.
- At a call site, use `step_into` to the callee's first relevant node and `step_over` to the next relevant caller node when both choices are useful.
- Use `step_out` from a callee return or terminal node to the continuation in its caller so the **Out** button works.
- Use `branch_true` and `branch_false` for conditional alternatives. Order outgoing edges in the order they should appear to the user.
- Use `async` when work is scheduled or crosses an asynchronous boundary, and `callback` when control resumes in a callback/listener/collector.
- Include `return` edges only when they express semantic return flow beyond the debugger-like `step_out` relation.
- There is no `error` node or edge kind. Represent an error decision with `branch`, an error exit with `return` or `note`, and describe the exception/failure condition in the summary or edge label.
- Include error paths that change control flow, user-visible state, persisted state, or the final result. A mechanically rethrown exception with no local behavior can remain summarized instead of becoming a separate node.
- Do not invent a single runtime path where the code has multiple possible implementations, branches, callbacks, or error paths. Include the relevant alternatives and explain their conditions in node summaries or edge labels.
- Keep nodes meaningful: calls, state changes, decisions, asynchronous boundaries, returns, and important framework callbacks. Avoid one node per trivial syntax line.

`Next` shows every outgoing edge. `Into`, `Over`, and `Out` filter `step_into`, `step_over`, and `step_out` respectively. `Previous` and `Forward` are visit history and require no special edges.

## Publisher behavior

The bundled publisher validates the schema and source coordinates against the current working directory, injects file-delivery protocol `2.0`, and atomically writes to the plugin inbox. It checks the platform's standard temporary-directory candidates (including the macOS GUI user temp directory); if more than one valid inbox exists, it stops and requires an explicit `--temp-root`. It waits for the plugin receipt and prints that receipt as JSON.

Exit code `0` is an accepted plugin receipt, `2` is a rejected plugin receipt, and `3` is an unknown post-publication outcome that must not be retried automatically. Other validation/not-delivered failures use exit code `1`; inspect `publisherStatus` and `retrySafe` when JSON output is present.

The standard temporary filesystem must support hard links (the normal case for APFS, ext4, and NTFS). The publisher fails safely instead of falling back to an overwrite-prone copy/rename on unsupported filesystems. On Windows, directory isolation ultimately relies on the user-profile `%TEMP%` ACLs that the plugin validates when it creates the inbox.

If it says the inbox does not exist, ask the user to open exactly one Android Studio project with the plugin enabled, then retry. Do not create the inbox yourself. If Android Studio was launched with a custom `-Djava.io.tmpdir`, pass the matching directory with `--temp-root` only after the user confirms it.

The lock file can remain after Android Studio exits; its existence alone does not prove the plugin is running. A timeout with an unknown outcome means the request may already be in `processing`, so inspect Android Studio before trying again. The protocol cannot route among multiple running Android Studio processes: only the process holding the shared consumer lock can receive a flow.

Do not use `curl`, probe capabilities, start a server, search for a token, or add `projectRoot`. This integration is local file IPC only.

## Scope and honesty

Static analysis is not a debugger trace. Mark reflection, dependency injection, dynamic dispatch, generated code, native calls, server behavior, and runtime-only conditions as uncertain when the repository cannot prove the target. Never fabricate a source location to make the graph complete.
