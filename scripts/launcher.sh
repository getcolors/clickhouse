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
[[ -L "$root/green/green" && $(readlink "$root/green/green") == ../skills/package-clickhouse-green/green ]] || fail 'green/green is not the payload symlink'
[[ -L "$root/red/red" && $(readlink "$root/red/red") == ../skills/package-clickhouse-red/red ]] || fail 'red/red is not the payload symlink'
[[ -L "$root/blue/blue" && $(readlink "$root/blue/blue") == ../skills/package-clickhouse-blue/blue ]] || fail 'blue/blue is not the payload symlink'
ok 'each colour dir symlinks its skill payload'

mkdir "$tmp/project"; cp "$launcher" "$tmp/project/green"; chmod +x "$tmp/project/green"
cp "$root/test/fixtures/colors.yml" "$tmp/project/colors.yml"
(cd "$tmp/project" && CLICKHOUSE_LIB_ROOT="$root" ./green build >/dev/null) || fail 'working-tree override failed'
[ -f "$tmp/project/.colors/clickhouse-fixture/clickhouse-infrastructure/shared/shared.tf.json" ] || fail 'render missing'
ok 'working-tree override renders from a copied payload'
mkdir -p "$tmp/project/deep/path"
(cd "$tmp/project/deep/path" && CLICKHOUSE_LIB_ROOT="$root" ../../green build >/dev/null) || fail 'upward colors.yml search failed'
ok 'finds desired state by walking upward'
out=$(cd "$tmp/project" && CLICKHOUSE_LIB_ROOT="$root" ./green nonsense 2>&1 || true)
grep -q Usage <<<"$out" || fail 'unknown verb has no usage'
ok 'unknown verb prints usage'
for verb in build create delete; do grep -q "\"$verb\"" "$launcher" || fail "missing verb $verb"; done
ok 'all lifecycle verbs are dispatchable'

# colors-compute-red declares the Red SDK as a peer, so a cold launcher cache
# installs the SDK only because PINS names it. The pin must be the one
# red/package.json tests against, and a cold cache must actually resolve it:
# a build inside the checkout reuses red/node_modules and cannot see a
# missing peer.
red_launcher="$root/skills/package-clickhouse-red/red"
[ -f "$red_launcher" ] || fail 'red payload launcher is missing'
red_sdk_sha=$(grep -oE '"red": "github:getcolors/red#[0-9a-f]{40}"' "$root/red/package.json" | grep -oE '[0-9a-f]{40}')
[[ -n $red_sdk_sha ]] || fail 'red/package.json carries no Red SDK pin'
grep -q "\"red\": \"github:getcolors/red#$red_sdk_sha\"" "$red_launcher" || fail 'red payload PINS the Red SDK at a different commit than red/package.json'
ok 'the red payload PINS the Red SDK at the red/package.json commit'
mkdir "$tmp/red-cold"
cp "$red_launcher" "$tmp/red-cold/red"; chmod +x "$tmp/red-cold/red"
cp "$root/test/fixtures/colors.yml" "$tmp/red-cold/colors.yml"
# One retry: a cold install fetches GitHub tarballs and a transient fetch
# failure is not a payload defect. Each attempt starts from empty caches.
cold_ok=0
for attempt in 1 2; do
  rm -rf "$tmp/red-cold/xdg" "$tmp/red-cold/bun" "$tmp/red-cold/.colors"
  if (cd "$tmp/red-cold" && XDG_CACHE_HOME="$tmp/red-cold/xdg" BUN_INSTALL_CACHE_DIR="$tmp/red-cold/bun" ./red build >"$tmp/red-cold/build.log" 2>&1); then cold_ok=1; break; fi
done
[[ $cold_ok == 1 ]] || { tail -5 "$tmp/red-cold/build.log" >&2; fail 'red payload does not build from a cold cache'; }
ok 'red payload builds from a cold cache with only its PINS'
echo "launcher: $checks checks passed"
