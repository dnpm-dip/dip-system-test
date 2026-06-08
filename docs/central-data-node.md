# Central Data Node (zKDK / CCDN)

## Overview
The CCDN is a JVM service (Scala 2.13, Amazon Corretto 21) that acts as the reporting hub of the DNPM network. It periodically polls all DIP nodes for MVH (Modellvorhaben Genomsequenzierung) submission reports, uploads them to BfArM, and confirms successful uploads back to the originating DIP nodes.

## Repositories
- **Code:** `../central-data-node`
  - `core/` — business logic, config, persistence interfaces
  - `connectors/` — DIP HTTP client, BfArM HTTP client, MongoDB persistence
- **Deployment:** `../central-data-node-deployment`

## Docker Image
```
ghcr.io/dnpm-dip/central-data-node:latest
```
Built from `central-data-node/Dockerfile`. Requires pre-built JARs placed at `./dnpm-ccdn-core.jar` and `./dnpm-ccdn-connectors.jar` (not included in the repo; produced by `sbt assembly`).

## Services in Production Deployment
| Service  | Image         | Purpose                    |
|----------|---------------|----------------------------|
| nginx    | nginxproxy/nginx-proxy:alpine | Forward proxy (mTLS outbound to broker on port 9010) |
| mongodb  | mongo:8       | Report persistence         |
| ccdn     | ghcr.io/dnpm-dip/central-data-node:latest | Main service |

## Environment Variables

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `CCDN_BROKER_BASEURL` | `http://nginx:9010` | Yes | Base URL of the DNPM broker (or local proxy) |
| `CCDN_BROKER_CONNECTOR_TIMEOUT` | `10` | No | Request timeout in seconds |
| `CCDN_BROKER_CONNECTOR_UPDATE_PERIOD` | — | No | Period (minutes) for auto-refreshing site list from broker; if unset, fetched once on startup |
| `CCDN_BFARM_API_URL` | `https://mvgenomseq.bfarm.de/api/upload` | Yes | BfArM upload endpoint |
| `CCDN_BFARM_AUTH_URL` | `https://mvgenomseq.bfarm.de/realms/mvgenomseq/protocol/openid-connect/token` | Yes | BfArM OAuth token endpoint |
| `CCDN_BFARM_AUTH_CLIENT_ID` | — | Yes | BfArM OAuth client ID |
| `CCDN_BFARM_AUTH_CLIENT_SECRET` | — | Yes | BfArM OAuth client secret |
| `CCDN_MONGODB_URI` | `mongodb://mongodb:27017/ccdn` | No | MongoDB connection string |
| `TZ` | `Europe/Berlin` | No | Container timezone |

## Configuration Files (mounted at `/ccdn_config`)

### `config.json`
Main operational config. Set via env var `CCDN_CONFIG_FILE` (default: `/ccdn_config/config.json`).

```json
{
  "polling": {
    "period": 10,
    "timeUnit": "SECONDS"
  },
  "dataNodeIds": {
    "MTB": "KDKTUE005",
    "RD":  "KDKTUE002"
  },
  "sites": {
    "UKT": {
      "submitterId": "260840108",
      "gdcId":       "GRZTUE002",
      "useCases":    ["MTB", "RD"]
    }
  }
}
```

- `polling.period` / `polling.timeUnit` — how often to poll DIP nodes
- `dataNodeIds` — CCDN's own BfArM-registered node IDs per use case
- `sites` — map of site ID → BfArM submitter info + active use cases. Only sites listed here are polled.

### `logback.xml`
Logback configuration. Logs to stdout and to `/ccdn_data/logs/`. Required on startup.

## Volumes
| Volume | Path inside container | Purpose |
|--------|-----------------------|---------|
| `ccdn-data` | `/ccdn_data` | MongoDB data + log files + queue |
| (host mount) | `/ccdn_config` | config.json, logback.xml |

## Broker Protocol
The CCDN talks to all DIP nodes through a single broker base URL. The broker must:

1. Serve `GET /sites` returning:
   ```json
   {
     "sites": [
       { "id": "UKT", "name": "Tübingen", "virtualhost": "ukt.dnpm.de" },
       { "id": "UKL", "name": "Leipzig",  "virtualhost": "ukl.dnpm.de" }
     ]
   }
   ```
2. Route all other requests to the appropriate DIP backend based on the `Host` header (virtualhost).

The CCDN sets the `Host` header to the site's virtualhost value when making requests through the broker.

## Workflow (one polling cycle)
1. `getApiCompatibleDipSites` — checks `/api/peer2peer/meta-info` on each configured site. Sites with API version < 1.3 (after 2026-06-01) are excluded.
2. Drain existing queue of `Submitted` reports by calling `confirmSubmissions`.
3. `pollReports` — for each valid site × use case: `GET /api/{usecase}/peer2peer/mvh/submission-reports?status=Unsubmitted`.
4. `uploadReports` — upload collected reports to BfArM.
5. `confirmSubmissions` — for each successfully uploaded report: `POST /api/{usecase}/peer2peer/mvh/submission-reports/{id}:submitted`.

## Dependencies to Download / Build
- The Docker image `ghcr.io/dnpm-dip/central-data-node:latest` must be pullable from GitHub Container Registry (may require `docker login ghcr.io`).
- If building from source, run `sbt assembly` in `../central-data-node` to produce the JAR files.
