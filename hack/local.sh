#!/usr/bin/env sh
# Local development helper for environments that cannot reach proxy.golang.org
# or golang.org/x vanity imports. It builds an alternate go.mod that fetches
# every module straight from GitHub via git. CI and normal machines do not
# need this; they use the real go.mod.
#
#   . hack/local.sh      # then: go build ./..., go test ./...
set -e
ROOT=$(git rev-parse --show-toplevel)
cp "$ROOT/go.mod" "$ROOT/hack/go.local.mod"
cat >> "$ROOT/hack/go.local.mod" <<'REPL'

replace golang.org/x/crypto => github.com/golang/crypto v0.39.0
replace golang.org/x/net => github.com/golang/net v0.41.0
replace golang.org/x/sys => github.com/golang/sys v0.33.0
replace golang.org/x/text => github.com/golang/text v0.26.0
replace golang.org/x/term => github.com/golang/term v0.32.0
replace golang.org/x/sync => github.com/golang/sync v0.15.0
replace golang.org/x/mod => github.com/golang/mod v0.25.0
replace golang.org/x/tools => github.com/golang/tools v0.33.0
replace golang.org/x/time => github.com/golang/time v0.12.0
REPL
[ -f "$ROOT/hack/go.local.sum" ] || : > "$ROOT/hack/go.local.sum"
export GOPROXY=direct GOSUMDB=off GOFLAGS="-mod=mod -modfile=$ROOT/hack/go.local.mod"
echo "go configured for direct GitHub fetching (modfile hack/go.local.mod)"
