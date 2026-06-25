# Test cases

`✓` = implemented in the integration test suite.

## Connectivity / broker (`BrokerSpec`)

- ✓ `GET /sites` returns a JSON array containing both UKT and UKL
- ✓ Each site entry includes a `virtualhost` field
- ✓ Node1 health / fake-data endpoint responds with 200 and a patient record
- ✓ Node2 health / fake-data endpoint responds with 200 and a patient record
- ✓ Node1 exposes the peer2peer status / version endpoint
- ✓ Node2 exposes the peer2peer status / version endpoint
- CCDN version check (`GET /api/peer2peer/meta-info`) returns ≥ 1.3 for both nodes
- Broker routes by `Host` header: `ukt.test` → node1, `ukl.test` → node2

## ETL / validation (`EtlValidationSpec`)

- ✓ `POST …:validate` with a well-formed record → 200 with no issues
- ✓ `POST …:validate` with a malformed record (missing required field) → issues returned, nothing persisted
- ✓ `DELETE /etl/patient/{id}` → record no longer appears in submission reports
- ✓ Second upload with same patient ID but a different TAN → rejected (4xx)
- ✓ [counter] `DELETE /etl/patient/{id}`: TAN is present in the unsubmitted queue *before* deletion, absent *after* (precondition currently unverified)

## Uploads (`EtlUploadSpec`)

- ✓ Duplicate TAN: second upload with the same `transferTAN` → rejected (4xx)
- ✓ Second `Initial` for the same `EpisodeOfCare` → rejected (4xx)
- ✓ `FollowUp` without a prior `Initial` for the same TAN → rejected (4xx)
- ✓ `Correction` after an `Initial` → accepted (200)
- ✓ `ConsentRevocation` → patient record removed from the dataset
- ✓ [counter] `ConsentRevocation`: revocation TAN appears in the unsubmitted queue after upload, proving it was persisted and not silently dropped (note: the original initial TAN remains in the queue — patient data is deleted but queue entries are not cleaned up)
- ✓ `Test` submission type → accepted (200)
- ✓ MTB record uploaded to node1 does not appear in node2's RD submission report list
- ✓ [counter] RD record uploaded to node1 appears in the RD submission report list (guards against the MTB-isolation test passing on an always-empty RD list)
- ✓ MTB record posted to node2 (RD-only) → rejected (4xx)
- `Initial` → `FollowUp` for the same patient/episode → two reports, both appear in CCDN polling
- `Test` submission type excluded from prior-submission history used in consent-check logic
- Upload for both MTB and RD use cases for the same patient → two independent report chains

## Federated query (`FederatedQuerySpec`)

- ✓ MTB query returns a query ID with status 200
- ✓ MTB query returns patient matches from at least node1
- ✓ MTB query does not contact node2 (node2 is RD-only)
- ✓ [counter] MTB query: UKT (node1) is listed as online in the peers response (guards against the UKL-absence check passing on an empty peers list)
- ✓ RD query returns results from both UKT and UKL
- ✓ [counter] RD query: patient match count is > 0 (guards against both peers being listed but returning no data)
- ✓ Query without authentication → 4xx
- ✓ `GET /query/{unknownId}` → 404
- Query after consent revocation → revoked patient excluded from results
- No-match query → empty result set (not just an error)
- Query before any upload → empty result

## CCDN workflow (`CcdnWorkflowSpec`)

- ✓ MTB upload → CCDN polls, uploads to mock BfArM, calls `:submitted` → report status becomes `Submitted`
- ✓ RD upload on node1 → same round-trip as MTB
- ✓ RD upload on node2 → same round-trip
- ✓ Three concurrent MTB uploads → each triggers exactly one BfArM call, all reach `Submitted`
- ✓ Mock BfArM returns 503 → CCDN does not call `:submitted`; report stays `Unsubmitted`
- ✓ [counter] BfArM upload request body contains the TAN of the specific record that was uploaded (guards against CCDN sending a hardcoded or empty payload)
- ✓ Both sites recorded as `fully` available in MongoDB after normal polling
- ✓ Node1 paused → CCDN records UKT as `offline` in MongoDB
- Calling `:submitted` on an already-submitted report is idempotent (no error)
- One DIP node stopped → CCDN still polls and submits the remaining node successfully
