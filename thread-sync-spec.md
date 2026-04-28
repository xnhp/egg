# Egg GitHub PR Thread Sync Spec (v1)

## Goal

Add thread-centered pull/push commands to `egg` for use by `flow` sources and sink transitions.

- `flow` remains generic orchestration.
- `egg` owns GitHub-specific fetch/push mapping and remote collision checks.
- Local edits are made directly on domain fields (no intent side-channel fields).

## CLI Surface

### Pull

`egg gh pr thread pull [--repo owner/name] [--pr N] [--json]`

Behavior:

- Fetch review threads for repo/PR.
- Return source-friendly JSON:

```json
{
  "threads": [
    {
      "_sync": {
        "id": "THREAD_GRAPHQL_ID",
        "base": "sha256:..."
      },
      "repo": "owner/name",
      "prNumber": 123,
      "thread": {
        "id": "THREAD_GRAPHQL_ID",
        "isResolved": false,
        "isOutdated": false,
        "path": "src/Foo.kt",
        "line": 42,
        "comments": [
          {
            "id": 123456789,
            "author": "alice",
            "body": "text",
            "createdAt": "2026-04-28T12:34:56Z",
            "updatedAt": "2026-04-28T12:34:56Z",
            "url": "https://github.com/..."
          }
        ]
      }
    }
  ]
}
```

`flow` usage expectation: `unwrap: threads` and `dedup_key: _sync.id`.

### Push

`egg gh pr thread push [--repo owner/name] [--pr N] [--dry-run] [--json]`

Behavior:

- Read exactly one thread entity from stdin (entity-scope sink transition use).
- Validate shape and baseline.
- Compute delta against remote/base and apply supported operations.
- Return structured status JSON.

## Thread Entity Contract

Each local entity represents exactly one GitHub review thread.

Required fields:

- `_sync.id`: GitHub review thread GraphQL id.
- `_sync.base`: pull-time fingerprint used for optimistic concurrency.
- `repo`: owner/name.
- `prNumber`: PR number.
- `thread.id`: same logical thread id (may duplicate `_sync.id`).
- `thread.isResolved`: local desired resolution state.
- `thread.comments`: ordered comments list (root + replies).

## Pull Rules

- Pull includes full thread comments (not only top-level inline comments).
- Pull includes thread `isResolved` and `isOutdated` state.
- Pull computes `_sync.base` from canonical snapshot.

### Base fingerprint

`_sync.base` is `sha256:<hex>` over canonical JSON of:

- thread id
- resolution/outdated state
- anchor fields (`path`, `line`)
- ordered comments with: id, author, body, createdAt, updatedAt

Serialization must be deterministic (stable ordering, normalized null handling).

## Push Algorithm

Input: one local thread entity from stdin.

1. Validate input fields (`_sync.id`, `_sync.base`, `thread.comments`, `thread.isResolved`).
2. Fetch current remote snapshot for `_sync.id`.
3. Recompute remote fingerprint using the same canonicalization as pull.
4. If remote fingerprint differs from `_sync.base`, return conflict and perform no write.
5. Compute supported deltas:
   - New local comments appended to thread.
   - Resolution toggle:
     - `false -> true`: resolve thread.
     - `true -> false`: unresolve thread.
6. Execute operations in deterministic order:
   - Post new comments in list order.
   - Apply resolve/unresolve mutation.
7. Return structured result.

No local entity mutation on push; user refreshes via next pull/nudge.

## Supported and Unsupported Edits (v1)

Supported:

- Append new comments.
- Toggle thread resolved state both directions (resolve and unresolve).

Unsupported in v1 (must return invalid):

- Editing body of existing remote comments.
- Deleting existing remote comments.
- Reordering existing remote comments.

## Result Contract (`--json`)

Push emits:

```json
{
  "status": "applied|noop|conflict|invalid|error",
  "syncId": "THREAD_GRAPHQL_ID",
  "repo": "owner/name",
  "prNumber": 123,
  "operations": [
    { "type": "reply", "count": 1 },
    { "type": "unresolve" }
  ],
  "message": "human-readable summary"
}
```

Exit codes:

- `0`: applied or noop.
- `3`: conflict (baseline mismatch).
- `2`: invalid input or unsupported edit.
- `1`: runtime/API failure.

## Non-Goals (v1)

- Automatic three-way merge in `egg`.
- In-place local conflict resolution UX.
- Multi-entity batch push semantics.
