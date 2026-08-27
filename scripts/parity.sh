#!/usr/bin/env bash
set -euo pipefail

# One desired state, three colours, byte for byte. golden.sh is green's
# regression net against the committed goldens; this is the net across colours:
# each backend variant is rendered by green, red, and blue into separate work
# directories and the trees must be identical — and the template trees each
# colour carries must be identical too, because the copies are the mechanism
# (red/resources and blue's embedded resources are copies of green's tree, not
# references to it).
#
# Two variants, because the goldens have a second axis: the same fixture is
# rendered under the local state backend and again under r2, the way
# golden.sh produces its r2 tree — COLORS_PAR_PROVIDER_BACKEND=r2 overlaid on
# the one fixture. Parity means every backend.tf.json agrees in every colour.
#
# Renders resolve each colour's package from this working tree (the
# CLICKHOUSE_LIB_ROOT overrides), while green, once, red, and blue stay on
# their pins — a change that lands here passes parity before it is pushed or
# pinned anywhere.

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
state="$root/test/fixtures/colors.yml"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

build_variant() {
  local variant=$1; shift
  (cd "$root/green" && env CLICKHOUSE_LIB_ROOT="$root" \
    COLORS_PAR_WORKDIR="$tmp/$variant/green" "$@" ./green build -f "$state" >/dev/null)
  (cd "$root/red" && env CLICKHOUSE_LIB_ROOT="$root/red" \
    COLORS_PAR_WORKDIR="$tmp/$variant/red" "$@" ./red build -f "$state" >/dev/null)
  (cd "$root/blue" && env COLORS_PAR_WORKDIR="$tmp/$variant/blue" "$@" \
    uv run python -m package_clickhouse_blue build -f "$state" >/dev/null)
  diff -r "$tmp/$variant/green" "$tmp/$variant/red"
  diff -r "$tmp/$variant/green" "$tmp/$variant/blue"
}

build_variant local
build_variant r2 COLORS_PAR_PROVIDER_BACKEND=r2

diff -r "$root/green/src/resources/io/github/getcolors/clickhouse" "$root/red/resources"
diff -r "$root/green/src/resources/io/github/getcolors/clickhouse" "$root/blue/src/package_clickhouse_blue/resources"

echo "green, red, and blue ClickHouse artifacts are byte-identical"
