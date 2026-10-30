#!/usr/bin/env bash
# Run the atom the way the runner does against a throwaway workspace: no Jenkins.
set -euo pipefail
cd "$(dirname "$0")/.."

export BUILD_NUMBER=7 JOB_NAME="$(basename "$PWD")-job"
./build.sh >/dev/null
jar="$(cd dist/ws-cleanup && pwd)/ws-cleanup.jar"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
fail() { echo "smoke failed: $*" >&2; exit 1; }

run() { # run <dir>
  local dir="$1"
  ( cd "$dir" && env atom_data_dir="$dir" atom_data_input=input.json atom_data_output=output.json \
      atom_workspace="$dir" "$java_bin" -cp "$jar" com.jenkinschina.atom.sdk.AtomRunner )
}

workspace() { # workspace <name> -> prints a workspace with the usual junk
  local dir
  dir="$(mktemp -d)/$1"
  mkdir -p "$dir/module/target/classes" "$dir/build" "$dir/.git" "$dir/src" "$dir/logs"
  printf 'class\n' >"$dir/module/target/classes/A.class"
  printf 'jar\n' >"$dir/build/app.jar"
  printf 'ref\n' >"$dir/.git/HEAD"
  printf 'keep me\n' >"$dir/src/Main.java"
  printf 'log\n' >"$dir/logs/build.log"
  echo "$dir"
}

# 1. patterns delete, keep protects, .git is never touched
dir="$(workspace one)"
cat >"$dir/input.json" <<'JSON'
{"patterns": "**/target, **/target/**, build/**, logs/*.log", "keep": "**/*.class.keep", "dryRun": "false"}
JSON
run "$dir" >"$dir/log.txt" 2>&1 || fail "run failed: $(cat "$dir/log.txt")"
[ ! -e "$dir/module/target/classes/A.class" ] || fail "target was not cleaned"
[ ! -e "$dir/build/app.jar" ] || fail "build was not cleaned"
[ ! -e "$dir/logs/build.log" ] || fail "log pattern did not match"
[ -e "$dir/src/Main.java" ] || fail "unmatched file was deleted"
[ -e "$dir/.git/HEAD" ] || fail ".git must survive"
grep -q '"status":"success"' "$dir/output.json" || fail "not a success: $(cat "$dir/output.json")"
python3 -c "
import json,sys
d=json.load(open('$dir/output.json'))['data']
n=int(d['deletedFiles']['value'])
sys.exit(0 if n >= 1 else 1)" || fail "nothing was reported as deleted: $(cat "$dir/output.json")"
[ ! -e "$dir/module/target" ] || fail "deleteDirs=true must remove the directory too"
grep -q '"freedBytes"' "$dir/output.json" || fail "freed bytes missing"

# 2. dryRun reports the same numbers and deletes nothing
dir="$(workspace two)"
cat >"$dir/input.json" <<'JSON'
{"patterns": "**/target/**, build/**", "keep": "", "dryRun": "true"}
JSON
run "$dir" >/dev/null 2>&1 || fail "dry run failed"
[ -e "$dir/module/target/classes/A.class" ] || fail "dry run deleted a file"
grep -q '"deletedFiles":{"type":"string","value":"2"}' "$dir/output.json" || fail "dry run count"
grep -q '"dryRun":{"type":"string","value":"true"}' "$dir/output.json" || fail "dry run marker missing"

# 3. keep wins over delete
dir="$(workspace three)"
cat >"$dir/input.json" <<'JSON'
{"patterns": "**/*.log, **/*.jar", "keep": "build/*.jar", "dryRun": "false"}
JSON
run "$dir" >/dev/null 2>&1 || fail "keep run failed"
[ -e "$dir/build/app.jar" ] || fail "keep did not protect the jar"
[ ! -e "$dir/logs/build.log" ] || fail "log should still be deleted"

# 4. no patterns is an error, not a silent no-op
dir="$(workspace four)"
cat >"$dir/input.json" <<'JSON'
{"patterns": "", "keep": "", "dryRun": "false"}
JSON
if run "$dir" >/dev/null 2>&1; then fail "empty patterns must fail"; fi
grep -q 'no patterns given' "$dir/output.json" || fail "message is not readable"

echo "smoke ok: delete+keep, dry run, keep precedence, empty patterns"
