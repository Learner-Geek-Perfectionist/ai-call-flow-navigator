# Call Flow content schema

The publisher accepts one UTF-8 JSON object containing Call Flow content version `1.0`. It rejects `_delivery`; delivery metadata is generated automatically.

Only the fields listed below are allowed at each object level. Unknown or duplicate fields are rejected so accidental future/legacy metadata cannot silently change the tour.

## Shape

```json
{
  "version": "1.0",
  "title": "MainActivity.onCreate startup flow",
  "project": {
    "revision": "optional Git revision"
  },
  "nodes": [
    {
      "id": "activity-on-create",
      "kind": "entry",
      "location": {
        "path": "app/src/main/java/com/example/MainActivity.kt",
        "line": 18,
        "column": 5,
        "endLine": 18,
        "endColumn": 22,
        "symbol": "com.example.MainActivity.onCreate",
        "anchorText": "override fun onCreate"
      },
      "summary": "Android calls MainActivity.onCreate for the new Activity instance."
    }
  ],
  "edges": [],
  "entry": "activity-on-create"
}
```

`project` is optional. If present, it contains only a non-empty `revision`. It is descriptive metadata, not a filesystem root.

## Nodes

Required node fields are `id`, `kind`, `location`, and `summary`.

Node kinds:

- `entry`
- `declaration`
- `call`
- `branch`
- `return`
- `async`
- `callback`
- `note`

`id` must be unique, non-empty, at most 256 characters, and free of control characters. Use stable descriptive IDs rather than array indexes.

Every `location` requires:

- `path`: normalized repository-relative path using `/`; no absolute path, URI, `.`, or `..` segment.
- `line`: positive 1-based source line.
- `column`: positive 1-based Android Studio column (UTF-16 code-unit offset plus one).

Optional location fields:

- `endLine` and `endColumn`: provide both or neither; the exclusive end cannot precede the start.
- `symbol`: qualified symbol name when known.
- `anchorText`: short exact text from one source line. The publisher requires it to occur once within 20 lines and to start at the supplied line and column.

The publisher verifies that source files and coordinates exist under the current working directory. Source files must be UTF-8 (an optional UTF-8 BOM is accepted). Source symlinks are accepted only when their real target remains inside that directory.

## Edges

Every edge requires `from`, `to`, and `kind`; `label` is optional. Both endpoints must name existing nodes.

Edge kinds:

- `next`
- `step_into`
- `step_over`
- `step_out`
- `branch_true`
- `branch_false`
- `return`
- `async`
- `callback`

The top-level `entry` must reference an existing node; use kind `entry` for a normal user-requested starting point. The edge array order controls candidate order in Android Studio. An empty array is valid only when the flow needs no transition, such as a single-node tour.

The protocol has no dedicated error kind. Use a `branch` node plus a labeled branch edge for an error condition, and use a `return` or `note` node for the resulting exit when appropriate.

## Limits

- Encoded request: at most 2 MiB.
- Title and edge label: at most 512 UTF-16 code units each.
- Nodes: 1 to 10,000.
- Edges: 0 to 50,000.
- Summary and `anchorText`: at most 16,384 UTF-16 code units each.
- Path and optional revision: at most 4,096 UTF-16 code units each.
- Optional symbol: at most 512 UTF-16 code units.

All required and optional string values, when present, must be non-blank. The top-level `entry` must reference an existing node.
