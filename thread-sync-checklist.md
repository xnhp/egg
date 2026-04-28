# Egg Thread Sync Checklist

## Command and wiring

- [ ] Add `egg gh pr thread` command group.
- [ ] Implement `egg gh pr thread pull` options: `--repo`, `--pr`, `--json`.
- [ ] Implement `egg gh pr thread push` options: `--repo`, `--pr`, `--dry-run`, `--json`.
- [ ] Ensure push reads one entity from stdin in entity-scope mode.

## Pull implementation

- [ ] Fetch review threads (GraphQL) including `id`, `isResolved`, `isOutdated`, anchor fields.
- [ ] Include full ordered comments list (root + replies).
- [ ] Map to v1 thread entity contract.
- [ ] Compute deterministic `_sync.base` fingerprint (`sha256:<hex>`).
- [ ] Emit source envelope `{ "threads": [...] }`.

## Push implementation

- [ ] Parse and validate input entity shape (`_sync.id`, `_sync.base`, thread state).
- [ ] Fetch current remote snapshot for `_sync.id`.
- [ ] Recompute remote fingerprint and compare to `_sync.base`.
- [ ] Return conflict (`status=conflict`, exit code `3`) on mismatch.
- [ ] Detect appended comments and map to reply API calls.
- [ ] Detect resolved transition and call resolve mutation.
- [ ] Detect unresolved transition and call unresolve mutation.
- [ ] Execute operations in deterministic order (comments, then resolution mutation).
- [ ] Return structured result contract (`applied|noop|conflict|invalid|error`).

## Guardrails and validation

- [ ] Reject edits to existing remote comment bodies (`status=invalid`, exit code `2`).
- [ ] Reject deletions of existing remote comments (`status=invalid`, exit code `2`).
- [ ] Reject reordering of existing remote comments (`status=invalid`, exit code `2`).

## Tests

- [ ] Unit tests for canonical fingerprint stability.
- [ ] Unit tests for delta derivation (append, resolve, unresolve, noop).
- [ ] Unit tests for conflict detection.
- [ ] Unit tests for invalid edit rejection cases.
- [ ] Command-level tests for JSON outputs and exit codes.

## Validation

- [ ] Run manual command-level E2E: pull -> local edit -> push -> pull refresh.
