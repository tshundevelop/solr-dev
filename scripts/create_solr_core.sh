#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <core-name> <schema-file>" >&2
  exit 1
fi

CORE_NAME="$1"
SCHEMA_FILE="$2"

if [ ! -f "$SCHEMA_FILE" ]; then
  echo "Schema file not found: $SCHEMA_FILE" >&2
  exit 1
fi

CONTAINER_NAME="${SOLR_CONTAINER_NAME:-solr}"
SOLR_URL="${SOLR_URL:-http://localhost:8983}"

# Bail out early if the Solr container is not reachable.
if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
  echo "Solr container '${CONTAINER_NAME}' is not running." >&2
  exit 1
fi

# Create the core if it does not already exist.
core_status=$(curl -sS --fail "${SOLR_URL}/solr/admin/cores?action=STATUS&core=${CORE_NAME}")
# A real core entry includes its name; an empty object means it does not exist yet.
if echo "${core_status}" | grep -q "\"name\":\"${CORE_NAME}\""; then
  echo "Core '${CORE_NAME}' already exists. Skipping creation."
else
  echo "Creating Solr core '${CORE_NAME}' inside container '${CONTAINER_NAME}'..."
  docker exec "${CONTAINER_NAME}" solr create -c "${CORE_NAME}"
fi

echo "Uploading schema '${SCHEMA_FILE}' to ${SOLR_URL}/solr/${CORE_NAME}/schema..."
if ! curl -sS --fail -X POST -H 'Content-type:application/json' --data "@${SCHEMA_FILE}" \
  "${SOLR_URL}/solr/${CORE_NAME}/schema"; then
  echo "Schema upload failed. Verify that core '${CORE_NAME}' exists and the schema API is enabled." >&2
  exit 1
fi

echo "Done."
