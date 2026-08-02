#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
launcher="$root/skills/package-clickhouse-green/green"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
checks=0
fail(){ echo "launcher: FAIL — $*" >&2; exit 1; }
ok(){ checks=$((checks+1)); echo "  ok — $*"; }

grep -q 'io.github.getcolors.clickhouse.workflow/workflow' "$launcher" || fail 'no workflow dispatch'
ok 'dispatches to library workflow'
for bad in 'defn.*-step' 'tofu/' 'ansible/'; do ! grep -qE "$bad" "$launcher" || fail "launcher contains $bad logic"; done
ok 'contains no tool logic'
grep -qE '\(def \^:private clickhouse-sha (nil|"[0-9a-f]{40}")\)' "$launcher" || fail 'invalid pin site'
ok 'has one managed pin site'

mkdir "$tmp/project"; cp "$launcher" "$tmp/project/green"; chmod +x "$tmp/project/green"
cp "$root/test/fixtures/colors.yml" "$tmp/project/colors.yml"
(cd "$tmp/project" && CLICKHOUSE_LIB_ROOT="$root" ./green build >/dev/null) || fail 'working-tree override failed'
[ -f "$tmp/project/.colors/clickhouse-fixture/clickhouse-network/main.tf" ] || fail 'render missing'
ok 'working-tree override renders from a copied payload'
mkdir -p "$tmp/project/deep/path"
(cd "$tmp/project/deep/path" && CLICKHOUSE_LIB_ROOT="$root" ../../green build >/dev/null) || fail 'upward colors.yml search failed'
ok 'finds desired state by walking upward'
out=$(cd "$tmp/project" && CLICKHOUSE_LIB_ROOT="$root" ./green nonsense 2>&1 || true)
grep -q Usage <<<"$out" || fail 'unknown verb has no usage'
ok 'unknown verb prints usage'
for verb in build create delete; do grep -q "\"$verb\"" "$launcher" || fail "missing verb $verb"; done
ok 'all lifecycle verbs are dispatchable'
echo "launcher: $checks checks passed"
