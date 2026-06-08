# DNPM/DIP Integration Test Setup — Summary

## Query Input

Collect all necessary information to set up an as minimal as possible setup for the DNPM/DIP system, make a plan to set this up as a docker compose environment and write this information into a markdown for information retrieval. The DNPM/DIP system consists of a zKDK and multiple DIP nodes. 

The code for the zKDK can be found in "../central-data-node" and it's docker deployment in "../central-data-node-deployment". The code for DIP nodes can be found in "../api-gateway" and it's deployment project in "../deployment" . Analyze these repositories and make markdown files for necessary information to run these. If you find specific dependencies, tell me to download them and point you to them.

The purpose for this is to make an integration test of the distributed system that is composed of central zKDK and multiple DIP nodes.

## What was built

A minimal Docker Compose integration test environment for the DNPM/DIP distributed system, consisting of:

- 1 local DNPM broker (nginx)
- 2 DIP nodes (UKT:Tübingen, UKL:Leipzig), each with MySQL + Authup + api-gateway backend
- 1 CCDN/zKDK (central data node) with MongoDB

## File structure

```
dip-system-test/
├── docs/
│   ├── system-overview.md       # Architecture, flows, port table
│   ├── central-data-node.md     # CCDN env vars, config format, broker protocol, workflow
│   └── dip-node.md              # DIP node env vars, config file formats, API endpoints
├── broker/
│   ├── nginx.conf
│   └── sites-enabled/broker.conf   # Serves /sites JSON + routes by Host header
├── ccdn-config/
│   ├── config.json              # Polls UKT + UKL every 30s
│   └── logback.xml
├── node1-config/                # UKT:Tübingen — MTB+RD random data
│   ├── config.xml
│   ├── production.conf
│   └── logback.xml
├── node2-config/                # UKL:Leipzig — RD random data only
│   ├── config.xml
│   ├── production.conf
│   └── logback.xml
├── docker-compose.yml
└── .env                         # Ports, image tags, secrets, BfArM credentials
```

## To start the environment

```bash
cd /home/jonas/workspaces/dip-system-test
docker compose up -d
```

DIP node APIs will be at:
- Node 1 (UKT): http://localhost:8001
- Node 2 (UKL): http://localhost:8002

## Dependencies to resolve before first run

### 1. GitHub Container Registry — may need authentication
```bash
docker login ghcr.io
docker pull ghcr.io/dnpm-dip/api-gateway:latest
docker pull ghcr.io/dnpm-dip/central-data-node:latest
```
If the images are private (org: `dnpm-dip`), you need a GitHub PAT with `read:packages` scope.

### 2. HGNC gene set (~50 MB)
Each DIP backend downloads this from Google Storage on first startup. Needs outbound internet.
If air-gapped: download the file and set `HGNC_GENESET_URL=http://your-local-server/hgnc_complete_set.json` in `.env`.

### 3. BfArM credentials (optional)
The CCDN→BfArM upload step will fail with the dummy credentials in `.env`. This is expected and acceptable for testing CCDN↔DIP connectivity — the CCDN still polls DIP nodes and logs the reports. Set real credentials in `.env` only for full end-to-end testing.

### 4. DIP API version ≥ 1.3 required
As of 2026-06-01, the CCDN rejects DIP nodes running API version < 1.3. The `latest` tag should be fine. If the CCDN logs "no valid sites found", the image is too old — specify a newer tag via `BACKEND_IMAGE_TAG` in `.env`.

## How the local broker works

The production system routes CCDN↔DIP traffic through `https://dnpm.medizin.uni-tuebingen.de`. For local testing, a custom nginx container replaces this:

1. CCDN calls `GET http://broker:9010/sites` → gets `[{id: UKT, virtualhost: ukt.test}, {id: UKL, virtualhost: ukl.test}]`
2. For each DIP request, CCDN sets `Host: ukt.test` (or `ukl.test`)
3. Broker nginx routes by `Host` header → `node1-backend:9000` or `node2-backend:9000`

This is configured in `broker/sites-enabled/broker.conf`. To add more DIP nodes: add entries to the `map` block and to the `/sites` JSON response, then add the corresponding services to `docker-compose.yml`.
