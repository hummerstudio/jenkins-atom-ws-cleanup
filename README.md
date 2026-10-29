# jenkins-atom-ws-cleanup

The [ws-cleanup](https://plugins.jenkins.io/ws-cleanup/) plugin (≈200k installations) as an **atom**: delete what a
glob matches, keep what another protects.

Pipeline has `cleanWs()`; this is for jobs that call atoms, and for the cases where "everything" is too blunt:
`**/target`, `**/*.log`, but not `.git` or the directory the next atom reads.

* `patterns` / `keep` — comma-separated globs, relative to the workspace; `keep` wins
* `.git` and `.atoms` are never deleted, and `..` is rejected
* `dryRun` — report the same numbers, delete nothing
* outputs — `deletedFiles`, `deletedDirs`, `freedBytes`, `freedHuman`

## Build and smoke test

```bash
./build.sh
./tools/smoke.sh       # delete+keep, dry run, keep precedence, empty patterns
```
