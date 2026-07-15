# Call Flow content schema

The publisher accepts one UTF-8 JSON object containing Call Flow content version `1.0` and adds delivery metadata automatically.

Each object uses exactly the fields listed below; strict validation keeps the resulting source tour deterministic.

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

`project` is optional descriptive revision metadata containing one non-empty `revision`.

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

`id` must be unique, non-empty, at most 256 characters, and free of control characters. Use stable descriptive IDs.

Every `location` requires:

- `path`: normalized path relative to the repository root, using `/` and the form `segment[/segment...]` with canonical source-tree segments.
- `line`: positive 1-based source line.
- `column`: positive 1-based Android Studio column (UTF-16 code-unit offset plus one).

Optional location fields:

- `endLine` and `endColumn`: provide both together; the exclusive end is at or after the start.
- `symbol`: qualified symbol name when known.
- `anchorText`: short exact text from one source line. The publisher requires it to occur once within 20 lines and to start at the supplied line and column.

The publisher verifies that source files and coordinates exist under the current working directory. Source files use UTF-8 (an optional UTF-8 BOM is accepted), and source symlinks resolve to targets inside that directory.

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

Model error conditions with a `branch` node plus a labeled branch edge, and use a `return` or `note` node for the resulting exit when appropriate.

## Limits

- Title and edge label: at most 512 UTF-16 code units each.
- Nodes: 1 to 10,000.
- Edges: 0 to 50,000.
- Summary and `anchorText`: at most 16,384 UTF-16 code units each.
- Path and optional revision: at most 4,096 UTF-16 code units each.
- Optional symbol: at most 512 UTF-16 code units.

All required and optional string values are non-blank when present. The top-level `entry` references an existing node.
