# DIP Node (api-gateway)

## Overview
Each DIP node is a Play Framework 2 application (Scala 2.13, Amazon Corretto 21) that exposes REST APIs for clinical data queries. It supports two use cases (MTB = Molecular Tumor Board, RD = Rare Diseases) and can generate random data for testing. Authentication is handled by an embedded Authup instance.

## Repositories
- **Code:** `../api-gateway`
  - `app/controllers/` — Play routes and controllers for MTB, RD, admin, catalog
  - `conf/application.conf` — base Play config
  - `conf/routes` — URL routing
- **Deployment:** `../deployment`

## Docker Image
```
ghcr.io/dnpm-dip/api-gateway:latest
```
Built from a pre-built distribution zip (`dnpm-dip-api-gateway-{VERSION}.zip`). The image is multi-stage: Amazon Linux 2 for unzipping, Amazon Corretto 21 for runtime.

## Services in Production Deployment
| Service | Image | Purpose |
|---------|-------|---------|
| mysql | mysql:9 | Authup database |
| authup | authup/authup:1 | Authentication & authorization |
| portal | ghcr.io/dnpm-dip/portal:latest | Web frontend |
| backend | ghcr.io/dnpm-dip/api-gateway:latest | REST API |
| nginx | nginxproxy/nginx-proxy:alpine | Reverse proxy (port 80/443) + forward proxy (port 9010) |
| polling_module | ghcr.io/dnpm-dip/http-polling-module:latest | HTTP polling queue (optional, profile: polling) |

## Environment Variables

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `LOCAL_SITE` | — | **Yes** | Format: `{SiteID}:{SiteName}`, e.g. `UKT:Tübingen` |
| `AUTHUP_URL` | — | **Yes** | Authup connection string: `client://system:{secret}@http://authup:3000` |
| `HATEOAS_HOST` | — | **Yes** | Public base URL for hypermedia links, e.g. `http://localhost/api` |
| `CONNECTOR_TYPE` | `broker` | No | `broker` or `peer2peer` |
| `MTB_RANDOM_DATA` | `-1` | No | Number of random MTB patients to generate (positive int enables) |
| `RD_RANDOM_DATA` | `-1` | No | Number of random RD patients to generate (positive int enables) |
| `ACTIVE_FEDERATED_QUERY_USE_CASES` | `` (empty) | No | CSV of use cases to expose peer2peer endpoints for: `MTB,RD` |
| `HTTP_PORT` | `9000` | No | HTTP server port |
| `JAVA_OPTS` | `-Xmx2g` | No | JVM options |
| `APPLICATION_SECRET` | (random) | No | Play secret key |
| `HGNC_GENESET_URL` | (official HUGO URL) | No | Override HGNC gene set download URL |
| `TZ` | `Europe/Berlin` | No | Container timezone |

## Configuration Files (mounted at `/dnpm_config`)

All three files are **required** — the application fails to start if any is missing.

### `config.xml`
Connector configuration. Choose one mode:

**Broker mode** (default):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<Config>
  <Connector>
    <Broker baseURL="http://nginx:9010"/>
    <UpdatePeriod minutes="30"/>
  </Connector>
</Config>
```

**Peer-to-peer mode** (direct connections, no broker):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<Config>
  <Connector>
    <Peer id="UKT" name="Tübingen" baseUrl="http://node1-backend:9000"/>
    <Peer id="UKL" name="Leipzig"  baseUrl="http://node2-backend:9000"/>
    <Timeout seconds="30"/>
  </Connector>
</Config>
```

### `production.conf`
Play framework overrides (CORS, secret key, allowed hosts):
```hocon
include "application"

play {
  http.secret.key = ${?APPLICATION_SECRET}
  http.parser.maxMemoryBuffer = 2MB
  server.http.port = ${?http.port}

  filters {
    enabled  += "play.filters.cors.CORSFilter"
    disabled += "play.filters.hosts.AllowedHostsFilter"
    disabled += "play.filters.csrf.CSRFFilter"

    cors {
      pathPrefixes       = ["/"]
      allowedOrigins     = null
      allowedHttpMethods = null
      allowedHttpHeaders = null
      preflightMaxAge    = 3 days
    }
  }
}
```

### `logback.xml`
Logback configuration. Logs to stdout and `/dnpm_data/logs/`.

## Volumes
| Volume | Path inside container | Purpose |
|--------|-----------------------|---------|
| `backend-data` | `/dnpm_data` | HGNC gene set cache, logs, persistent data |
| (host mount) | `/dnpm_config` | config.xml, production.conf, logback.xml |

## Health Check
```
GET http://127.0.0.1:9000/peer2peer/status
```
Returns 200 when the service is ready. Interval 10s, retries 5, start period 5s.

## Key API Endpoints
| Method | Path | Description |
|--------|------|-------------|
| GET | `/peer2peer/status` | Health check |
| GET | `/api/peer2peer/meta-info` | Version and metadata (used by CCDN for version check) |
| GET | `/api/{mtb\|rd}/peer2peer/mvh/submission-reports` | List MVH reports (used by CCDN) |
| POST | `/api/{mtb\|rd}/peer2peer/mvh/submission-reports/{id}:submitted` | Confirm report submitted to BfArM |
| GET | `/api/{mtb\|rd}/...` | Use-case-specific clinical data APIs |

## Startup Dependencies
1. MySQL must be healthy before Authup starts.
2. Authup must be healthy before the backend starts.
3. The HGNC gene set is downloaded from the internet on first startup (~50 MB). Requires outbound internet access unless `HGNC_GENESET_URL` points to a local copy.

## Authup Configuration
Authup is the embedded OAuth2/OIDC authentication server. The backend uses a system client:
```
AUTHUP_URL = client://system:{AUTHUP_SECRET}@http://authup:3000
```
Default secret: `start123` (change in production via `AUTHUP_SECRET`).

Authup's MySQL database name (`DB_DATABASE`) must be unique per DIP node if multiple nodes share the same MySQL instance.

## NGINX in Production
Production deployments use nginx for:
- **Reverse proxy (port 80/443):** Routes `/api/`, `/auth/`, `/` to backend, authup, portal
- **Forward proxy (port 9010):** Adds mTLS client certificates for outbound requests to the DNPM broker

For local/integration testing, nginx can be omitted if the backend is accessed directly.

## Dependencies to Download / Build
- The Docker image `ghcr.io/dnpm-dip/api-gateway:latest` must be pullable (may require `docker login ghcr.io`).
- `ghcr.io/dnpm-dip/portal:latest` — optional for integration tests (only needed for UI access).
- If building from source: run the SBT build in `../api-gateway` to produce the distribution zip.
