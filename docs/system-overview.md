# DNPM/DIP System Overview

## Architecture

The DNPM/DIP system is a distributed network for German molecular tumor boards and rare disease research. It has two main layers:

```
┌──────────────────────────────────────────────────────────────────┐
│                     zKDK (Central Data Node)                     │
│                                                                  │
│  Polls DIP nodes for MVH submission reports, uploads to BfArM,   │
│  confirms back to DIP nodes.                                     │
│                                                                  │
│  Services: CCDN service + MongoDB + (nginx forward proxy)        │
└──────────────────────┬───────────────────────────────────────────┘
                       │  polls via DNPM broker (HTTP + virtual host routing)
          ┌────────────┴───────────────┐
          ▼                            ▼
┌─────────────────┐          ┌─────────────────┐
│  DIP Node (UK1) │ ◄──────► │  DIP Node (UK2) │  (federated queries)
│                 │          │                 │
│ MySQL + Authup  │          │ MySQL + Authup  │
│ api-gateway     │          │ api-gateway     │
│ (nginx)         │          │ (nginx)         │
└─────────────────┘          └─────────────────┘
```

## Components

### zKDK / Central Data Node (CCDN)
- **Repo (code):** `../central-data-node`
- **Repo (deployment):** `../central-data-node-deployment`
- **Image:** `ghcr.io/dnpm-dip/central-data-node:latest`
- **Purpose:** Central reporting node that collects MVH submission reports from all DIP nodes and forwards them to BfArM (Bundesinstitut für Arzneimittel und Medizinprodukte).

### DIP Node / api-gateway
- **Repo (code):** `../api-gateway`
- **Repo (deployment):** `../deployment`
- **Image:** `ghcr.io/dnpm-dip/api-gateway:latest`
- **Purpose:** Site-level backend providing REST APIs for clinical data queries (MTB = Molecular Tumor Board, RD = Rare Diseases). Supports federated queries across all registered DIP sites.

## Communication Flows

### CCDN → DIP Nodes (report collection)
1. On startup and periodically, CCDN calls `GET {CCDN_BROKER_BASEURL}/sites` to get the site registry (ID, name, virtualhost).
2. For each configured site (from `config.json`), CCDN calls the DIP node via the broker with a virtual host header for routing:
   - `GET /api/peer2peer/meta-info` — version check (must be ≥ 1.3.x as of 2026-06-01)
   - `GET /api/{usecase}/peer2peer/mvh/submission-reports?status=Unsubmitted` — poll new reports
   - `POST /api/{usecase}/peer2peer/mvh/submission-reports/{id}:submitted` — confirm submission
3. Collected reports are uploaded to BfArM.

### DIP Node → DIP Node (federated queries)
- Uses either **broker** or **peer2peer** connector type.
- **Broker mode:** All inter-node requests go through the DNPM broker at `nginx:9010` (forward proxy). In production this is `https://dnpm.medizin.uni-tuebingen.de`.
- **Peer2peer mode:** Direct connections to peer nodes listed in `config.xml`.

### DNPM Broker (production)
The broker (`https://dnpm.medizin.uni-tuebingen.de`) is an nginx reverse proxy that routes requests based on the HTTP `Host` header. The broker exposes a `/sites` endpoint returning the full site registry with virtual host names.

## Key Ports

| Service        | Internal Port | Notes                             |
|----------------|:-------------:|-----------------------------------|
| DIP api-gateway | 9000         | REST API, health: `/peer2peer/status` |
| Authup          | 3000         | Authentication service            |
| MySQL           | 3306         | Authup database                   |
| NGINX rev proxy | 80           | HTTP reverse proxy to backend     |
| NGINX fwd proxy | 9010         | Forward proxy to broker (mTLS)    |
| MongoDB         | 27017        | CCDN persistence                  |

## Version Compatibility

As of **2026-06-01**, the CCDN requires DIP node API version **≥ 1.3.x**. Earlier versions are rejected. Check with:
```
GET http://{node}/api/peer2peer/meta-info
```
Response must include `"version": "1.3.x"` or higher.
