#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "Usage: $0 <core-name> [--delete-data]" >&2
  exit 1
fi

CORE_NAME="$1"
DELETE_DATA="${2:-}" 

CONTAINER_NAME="${SOLR_CONTAINER_NAME:-solr}"
SOLR_URL="${SOLR_URL:-http://localhost:8983}"

if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
  echo "Solr container '${CONTAINER_NAME}' is not running." >&2
  exit 1
fi

core_status=$(curl -sS --fail "${SOLR_URL}/solr/admin/cores?action=STATUS&core=${CORE_NAME}")
if ! echo "${core_status}" | grep -q "\"name\":\"${CORE_NAME}\""; then
  echo "Core '${CORE_NAME}' does not exist. Nothing to delete."
  exit 0
fi

cmd=("docker" "exec" "${CONTAINER_NAME}" "solr" "delete" "-c" "${CORE_NAME}")
if [ "${DELETE_DATA}" = "--delete-data" ]; then
  # Include Solr flags to remove data/index directories and instance dir.
  cmd+=("-deleteDataDir" "-deleteInstanceDir" "-deleteIndex")
fi

echo "Deleting Solr core '${CORE_NAME}' inside container '${CONTAINER_NAME}'..."
"${cmd[@]}"

echo "Done."
