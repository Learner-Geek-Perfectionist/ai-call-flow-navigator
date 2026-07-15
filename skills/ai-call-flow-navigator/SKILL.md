---
name: ai-call-flow-navigator
description: Analyze a local source repository from a requested entry symbol, model the meaningful execution order and branches as an exact line-and-column Call Flow, validate it, and deliver it to the AI Call Flow Navigator plugin in the currently open Android Studio project. Use when the user asks to trace, explain, visualize, step through, or navigate a function, method, callback, coroutine, request, or other code path in Android Studio.
---

# AI Call Flow Navigator

Turn static code analysis into a debugger-like source tour in Android Studio. Work from the project root opened in both the terminal and Android Studio, and emit the semantic fields defined by the Call Flow schema.

## Workflow

1. Verify that the current working directory is the project root opened by Android Studio, then calculate every path relative to it. In a monorepo, an IDE project at `repo/android` uses paths relative to `repo/android`.
2. Resolve the requested entry point to one exact declaration. When several symbols genuinely match after inspecting the code context, ask one short question before publishing.
3. Read enough callers, callees, overrides, callbacks, and asynchronous continuations to model the requested scope. When the user gives no boundary, follow the complete meaningful application-owned flow until it returns, reaches a terminal state, or crosses into unavailable framework/library/generated code; include material branches, catches, and asynchronous resumptions, but collapse trivial helpers. Clearly distinguish a statically supported path from behavior that depends on runtime values or framework dispatch.
4. Build one semantic Call Flow JSON object using `references/call-flow-schema.md`; the publisher adds `_delivery`.
5. Verify every location against the working tree:
   - `path` is repository-relative and uses `/` on every platform.
   - Lines and columns are 1-based. Columns use Android Studio's UTF-16 editor offsets.
   - Point at the expression or declaration that best explains the step.
   - When using `anchorText`, it must be single-line, start at the supplied coordinate, and occur exactly once within 20 lines. Choose a longer exact fragment when a short anchor is ambiguous.
6. Save the semantic object as a private UTF-8 JSON file in the operating system's secure temporary directory. Create it with exclusive-create semantics under an unpredictable name and current-user-only access (`0600` on POSIX). Keep it outside the repository, version control, and logs.
7. From the repository root, run this skill's bundled `scripts/publish_call_flow.py --delete-input` with that file. Resolve the script relative to this `SKILL.md`; the flag removes the temporary semantic file immediately after reading it.
8. Read the publisher's receipt. Only report success when `status` is `accepted`:
   - For `INVALID_CALL_FLOW` or another content error, correct the flow and publish a new request.
   - For `AMBIGUOUS_PROJECT`, ask the user to close extra Android Studio projects before retrying.
   - For `REQUEST_EXPIRED`, check that the plugin is ready, then publish a fresh request.
   - For `LOAD_FAILED`, report the IDE/plugin load error and retry only after its cause is addressed.
   - `publisherStatus: not_delivered` with `retrySafe: true` means the publisher removed an unclaimed request; after fixing the environment, a fresh request is safe.
   - `publisherStatus: unknown` includes the exact `requestId` and `receiptFile`. Inspect that path and Android Studio before deciding whether a new request is needed.
9. Tell the user the accepted flow title and that it is ready in Android Studio's **Call Flow** tool window. Briefly mention material static-analysis uncertainty, if any.

Example publisher invocation:

```text
python3 "<this-skill-directory>/scripts/publish_call_flow.py" --delete-input "<temporary-call-flow.json>"
```

On Windows, `py -3` can be used instead of `python3`.
The publisher runs on Python 3.8 or newer using the standard library.

## Model execution order

- Use `next` for ordinary same-frame ordering.
- At a call site, use `step_into` to the callee's first relevant node and `step_over` to the next relevant caller node when both choices are useful.
- Use `step_out` from a callee return or terminal node to the continuation in its caller so the **Out** button works.
- Use `branch_true` and `branch_false` for conditional alternatives. Order outgoing edges in the order they should appear to the user.
- Use `async` when work is scheduled or crosses an asynchronous boundary, and `callback` when control resumes in a callback/listener/collector.
- Include `return` edges only when they express semantic return flow beyond the debugger-like `step_out` relation.
- Represent an error decision with `branch`, an error exit with `return` or `note`, and describe the exception/failure condition in the summary or edge label.
- Include error paths that change control flow, user-visible state, persisted state, or the final result. A mechanically rethrown exception with no local behavior can remain summarized instead of becoming a separate node.
- Represent relevant alternative implementations, branches, callbacks, and error paths, and explain their conditions in node summaries or edge labels.
- Keep nodes focused on meaningful calls, state changes, decisions, asynchronous boundaries, returns, and important framework callbacks; group trivial syntax into the surrounding semantic step.

`Next` shows every outgoing edge. `Into`, `Over`, and `Out` filter `step_into`, `step_over`, and `step_out` respectively. `Previous` and `Forward` are visit history and require no special edges.

## Publisher behavior

The bundled publisher validates the schema and source coordinates against the current working directory, injects file-delivery protocol `2.0`, and atomically writes to the plugin inbox. It checks the platform's standard temporary-directory candidates (including the macOS GUI user temp directory); if more than one valid inbox exists, it stops and requires an explicit `--temp-root`. It waits for the plugin receipt and prints that receipt as JSON.

Exit code `0` is an accepted plugin receipt, `2` is a rejected plugin receipt, and `3` requires receipt inspection before retry. Other validation/not-delivered outcomes use exit code `1`; inspect `publisherStatus` and `retrySafe` when JSON output is present.

Use a standard hard-link-capable temporary filesystem such as APFS, ext4, or NTFS. On Windows, directory isolation uses the user-profile `%TEMP%` ACLs that the plugin validates when it creates the inbox.

If the inbox is missing, ask the user to open one Android Studio project with the plugin enabled so the plugin creates it, then retry. If Android Studio was launched with a custom `-Djava.io.tmpdir`, pass the confirmed matching directory with `--temp-root`.

The running plugin holds the shared consumer lock and receives the flow. A timeout with an unknown outcome may mean the request is already in `processing`, so inspect Android Studio before trying again.

## Static-analysis accuracy

Mark reflection, dependency injection, dynamic dispatch, generated code, native calls, server behavior, and runtime-only conditions as uncertain when repository evidence is incomplete. Include only source locations verified in the working tree.
