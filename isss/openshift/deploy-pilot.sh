#!/usr/bin/env bash
# ISSSDP-512 dev pilot on OpenShift Silver, namespace dabb52-tools (the only Epsilon namespace with
# quota headroom). One replica of everything. Upstream public images for FAM; the lookup API is built
# in-cluster from the fork branch that carries PR 17. Secrets are written from local files through
# temp files (never on a command line) and the temp files are removed on exit.
#
#   ./deploy-pilot.sh db        PostgreSQL (dev-grade) + the four secrets FAM reads
#   ./deploy-pilot.sh fam       nr-fam init (ConfigMap, policies), backend, frontend
#   ./deploy-pilot.sh lookup    lookup API build (ImageStream + BuildConfig, starts a build) and deployment
#   ./deploy-pilot.sh status    rollout status, pods, route
set -euo pipefail
OC=/opt/homebrew/bin/oc
NS=dabb52-tools
NAME=nr-fam            # keeps the upstream image path ghcr.io/bcgov/nr-fam/<component>
ZONE=isss-dev          # so every object is nr-fam-isss-dev-*
APP="$NAME-$ZONE"
DOMAIN=apps.silver.devops.gov.bc.ca
TAG=latest
FAM_CLIENT_ID=fam-arts-spike-6702
FAM_INTEGRATION_ID=6702
DEV_REALM=https://dev.loginproxy.gov.bc.ca/auth/realms/standard
PROD_REALM=https://loginproxy.gov.bc.ca/auth/realms/standard
CSS_API_URL=https://api.loginproxy.gov.bc.ca/api/v1
LOOKUP_NAME=nr-user-lookup-api
LOOKUP_APP="$LOOKUP_NAME-$ZONE"
TRUSTED_CLIENT=service-account-team-1750-6700
REQUESTER_GUID=76a4033a7fe24371b2ee1ecb7f41a217
BCEID_OSID=BC95-ED73-6464-462E
BCEID_URL=https://gws1.test.bceid.ca/webservices/Client/V10/BCeIDService.asmx

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRATCH="${PILOT_SCRATCH:?set PILOT_SCRATCH to a folder holding fam/nr-fam and user-lookup/repo checkouts}"
FAM_SRC="$SCRATCH/fam/nr-fam"
LOOKUP_SRC="$SCRATCH/user-lookup/repo/backend"
TMP="$HERE/tmp"; umask 077; mkdir -p "$TMP"; trap 'rm -rf "$TMP"' EXIT

$OC project "$NS" -q >/dev/null

# Read KEY=VALUE lines verbatim into variables named by $2.. from file $1 (no eval, quotes stripped).
read_env() {
  local file="$1"; shift
  local line key value
  for key in "$@"; do
    line="$(grep -E "^${key}=" "$file" | head -1 || true)"
    [ -n "$line" ] || { echo "missing $key in $file" >&2; exit 1; }
    value="${line#*=}"; value="${value#\"}"; value="${value%\"}"; value="${value#\'}"; value="${value%\'}"
    printf -v "$key" '%s' "$value"
  done
}

# oc create secret from files: apply_secret <name> key=value ... (values written to temp files)
apply_secret() {
  local name="$1"; shift
  local args=() kv key
  for kv in "$@"; do
    key="${kv%%=*}"; printf '%s' "${kv#*=}" > "$TMP/$name.$key"; args+=("--from-file=$key=$TMP/$name.$key")
  done
  $OC create secret generic "$name" "${args[@]}" --dry-run=client -o yaml | $OC apply -f - >/dev/null
  $OC label secret "$name" "app=$APP" --overwrite >/dev/null
  echo "  secret $name"
}

step_db() {
  echo "== database"
  if $OC get secret "$APP-database" >/dev/null 2>&1; then
    echo "  $APP-database exists; leaving the database alone"
  else
    $OC process -f "$HERE/postgres-dev.yml" -p NAME="$NAME" -p ZONE="$ZONE" | $OC create -f - | sed 's/^/  /'
  fi
  echo "== secrets"
  read_env "$HOME/.config/nr-fam/css.env" CSS_API_CLIENT_ID CSS_API_CLIENT_SECRET
  apply_secret "$APP-css" "css-api-client-id=$CSS_API_CLIENT_ID" "css-api-client-secret=$CSS_API_CLIENT_SECRET"
  apply_secret "$APP-user-lookup" \
    "user-lookup-base-url-dev=http://$LOOKUP_APP" \
    "user-lookup-token-url-dev=$PROD_REALM/protocol/openid-connect/token" \
    "user-lookup-client-id-dev=$CSS_API_CLIENT_ID" \
    "user-lookup-client-secret-dev=$CSS_API_CLIENT_SECRET" \
    "user-lookup-base-url-test=" "user-lookup-token-url-test=" "user-lookup-client-id-test=" "user-lookup-client-secret-test=" \
    "user-lookup-base-url-prod=" "user-lookup-token-url-prod=" "user-lookup-client-id-prod=" "user-lookup-client-secret-prod="
  apply_secret "$APP-forest-client" "fc-api-token-test=" "fc-api-token-prod="
}

step_fam() {
  echo "== nr-fam init (frontend env.json, network policies)"
  $OC process -f "$FAM_SRC/common/openshift.init.yml" -p NAME="$NAME" -p ZONE="$ZONE" -p DOMAIN="$DOMAIN" \
    -p KEYCLOAK_ISSUER_URI="$DEV_REALM" -p KEYCLOAK_CLIENT_ID="$FAM_CLIENT_ID" -p TARGET_ENV=dev | $OC apply -f - | sed 's/^/  /'
  echo "== nr-fam backend ($TAG)"
  $OC process -f "$FAM_SRC/backend/openshift.deploy.yml" -p NAME="$NAME" -p ZONE="$ZONE" -p DOMAIN="$DOMAIN" -p IMAGE_TAG="$TAG" \
    -p MIN_REPLICAS=1 -p MAX_REPLICAS=1 -p CPU_REQUEST=150m -p MEMORY_REQUEST=640Mi -p MEMORY_LIMIT=1Gi \
    -p KEYCLOAK_ISSUER_URI="$DEV_REALM" -p FAM_OIDC_CLIENT_ID="$FAM_CLIENT_ID" -p FAM_DEPLOYMENT_ENVIRONMENT=dev \
    -p CSS_API_URL="$CSS_API_URL" -p CSS_TOKEN_URL="$PROD_REALM/protocol/openid-connect/token" -p CSS_OWN_INTEGRATION_ID="$FAM_INTEGRATION_ID" \
    -p SMTP_HOST= -p SMTP_FROM= -p FC_API_BASE_URL_TEST= -p FC_API_BASE_URL_PROD= | $OC apply -f - | sed 's/^/  /'
  echo "== nr-fam frontend ($TAG)"
  $OC process -f "$FAM_SRC/frontend/openshift.deploy.yml" -p NAME="$NAME" -p ZONE="$ZONE" -p DOMAIN="$DOMAIN" -p IMAGE_TAG="$TAG" \
    -p MIN_REPLICAS=1 -p MAX_REPLICAS=1 | $OC apply -f - | sed 's/^/  /'
}

step_lookup() {
  echo "== lookup API build from the fork (PR 17)"
  $OC process -f "$HERE/lookup-api-build.yml" | $OC apply -f - | sed 's/^/  /'
  $OC start-build nr-user-lookup-api-backend 2>&1 | sed 's/^/  /' || true
  echo "== lookup API deployment"
  read_env "$HOME/.raven/.env" BCEID_WEB_SERVICE_USERNAME BCEID_WEB_SERVICE_PASSWORD
  cat > "$TMP/lookup.params" <<EOF
NAME=$LOOKUP_NAME
ZONE=$ZONE
DOMAIN=$DOMAIN
IMAGE_TAG=isss
REGISTRY=image-registry.openshift-image-registry.svc:5000
ORG=$NS
MIN_REPLICAS=1
MAX_REPLICAS=1
KEYCLOAK_ISSUER_URI=$PROD_REALM
USER_LOOKUP_TRUSTED_CLIENT_IDS=$TRUSTED_CLIENT
BCEID_WEB_SERVICE_URL=$BCEID_URL
BCEID_WEB_SERVICE_OSID=$BCEID_OSID
BCEID_WEB_SERVICE_REQUESTER_USER_GUID=$REQUESTER_GUID
BCEID_WEB_SERVICE_USERNAME=$BCEID_WEB_SERVICE_USERNAME
BCEID_WEB_SERVICE_PASSWORD=$BCEID_WEB_SERVICE_PASSWORD
EOF
  # Drop the template's network policies (the namespace already has equivalent ones) and its public
  # Route (FAM reaches the API in-cluster), and point the image at the in-cluster build.
  $OC process -f "$LOOKUP_SRC/openshift.deploy.yml" --param-file="$TMP/lookup.params" -o json \
    | python3 -I -c '
import json, re, sys
doc = json.load(sys.stdin)
keep = []
for o in doc["items"]:
    if o["kind"] in ("NetworkPolicy", "Route"):
        continue
    if o["kind"] == "Deployment":
        for c in o["spec"]["template"]["spec"]["containers"]:
            c["image"] = re.sub(r"/nr-user-lookup-api/[a-z]+:", "/nr-user-lookup-api-backend:", c["image"])
    keep.append(o)
doc["items"] = keep
json.dump(doc, sys.stdout)' | $OC apply -f - | sed 's/^/  /'
}

step_status() {
  echo "== pods"; $OC get pods -l "app in ($APP,$LOOKUP_APP)" -o wide 2>&1 | sed 's/^/  /'
  echo "== builds"; $OC get builds --no-headers 2>&1 | tail -3 | sed 's/^/  /'
  echo "== route"; $OC get route "$APP-frontend" -o jsonpath='https://{.spec.host}{"\n"}' 2>&1 | sed 's/^/  /'
  echo "== quota"; $OC get resourcequota compute-long-running-quota -o jsonpath='requests {.status.used.requests\.cpu}/{.status.hard.requests\.cpu} cpu, {.status.used.requests\.memory}/{.status.hard.requests\.memory} memory{"\n"}' | sed 's/^/  /'
}

case "${1:-}" in
  db) step_db ;;
  fam) step_fam ;;
  lookup) step_lookup ;;
  status) step_status ;;
  *) echo "usage: $0 db|fam|lookup|status"; exit 2 ;;
esac
