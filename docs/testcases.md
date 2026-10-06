# Test cases

`✓` = implemented in the integration test suite.

## Connectivity / broker (`BrokerSpec`)

- ✓ `GET /sites` returns a JSON array containing both UK1 and UK2
- ✓ Each site entry includes a `virtualhost` field
- ✓ Node1 health / fake-data endpoint responds with 200 and a patient record
- ✓ Node2 health / fake-data endpoint responds with 200 and a patient record
- ✓ Node1 exposes the peer2peer status / version endpoint
- ✓ Node2 exposes the peer2peer status / version endpoint
- CCDN version check (`GET /api/peer2peer/meta-info`) returns ≥ 1.3 for both nodes
- Broker routes by `Host` header: `uk1.test` → node1, `uk2.test` → node2

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
- MTB record posted to node2 (RD-only) → rejected (4xx) — ignored: the api-gateway registers MTB and RD routers regardless of `ACTIVE_FEDERATED_QUERY_USE_CASES`; the RD-only restriction only applies to federated queries
- `Initial` → `FollowUp` for the same patient/episode → two reports, both appear in CCDN polling
- `Test` submission type excluded from prior-submission history used in consent-check logic
- Upload for both MTB and RD use cases for the same patient → two independent report chains

## MVH / controlling API (`MvhApiSpec`)

- ✓ `GET /{uc}/peer2peer/mvh/submissions/{tan}` → full submission (matching `metadata.transferTAN`, patient ID)
- ✓ `GET /{uc}/peer2peer/mvh/submissions/{unknownTan}` → 404
- ✓ `GET /{uc}/peer2peer/mvh/deletion-events?after=…` → contains an event 1 s after `after`, not one 1 s before
- ✓ `GET /{uc}/controlling/local-controlling-info` → 200 (MTB, RD)
- ✓ `GET /{uc}/controlling/federated-controlling-info` → 200 (MTB, RD)
- `/{uc}/etl/mvh/submission-reports` and `/{uc}/etl/mvh/submissions` (routes moved in api-gateway 414c7f8) — not tested
- JSON projection on the submissions endpoint (api-gateway 3d22397) — not tested

## Federated query (`FederatedQuerySpec`)

- ✓ MTB query returns a query ID with status 200
- ✓ MTB query returns patient matches from at least node1
- ✓ MTB query does not contact node2 (node2 is RD-only)
- ✓ [counter] MTB query: UK1 (node1) is listed as online in the peers response (guards against the UK2-absence check passing on an empty peers list)
- ✓ RD query returns results from both UK1 and UK2
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
- ✓ Node1 paused → CCDN records UK1 as `offline` in MongoDB
- ✓ ccdn-mtb paused → RD submission gets `Submitted` by ccdn-rd; MTB submission stays `Unsubmitted`; MTB is processed after ccdn-mtb recovers
- Calling `:submitted` on an already-submitted report is idempotent (no error)
- One DIP node stopped → CCDN still polls and submits the remaining node successfully

## zKDK backups (`CcdnBackupSpec`)

- ✓ MVH-consented report → `submission` and `report` document in `ccdn.backup`, report is stored in `quarter-reports` and deleted from the queue, no longer archived to the file system (ArchivingReportRepository deprecated since 1.3.2) ([counter] to the missing-keyfile test)
- ✓ Backup documents: plaintext `tan`/`site`/`usecase`/`type`/`submittedAt` correct; `content` has exactly the fields of `EncryptionService.Encrypted`, valid base64, IV 16 bytes, encrypted key one RSA block, ciphertext not JSON
- ✓ Backups decrypt with `crypto/private.pem` (decrypted submission/report carry the TAN and patient ID)
- ✓ Submission of ~14.5 MB (node1's upload limit raised to 16 MB for this) whose encrypted backup exceeds MongoDB's 16 MB document limit → backed up and dequeued; ciphertext split into ≥ 2 parts in `largeBackupParts` (`content.ciphertextParts` lists them in order of their `index`), [counter] the report backup stays a single document; the submission decrypted with `BackupCrypto` and extracted with the PROD `backup-extract.sh` (downloaded from the public central-data-node-deployment repository, `main`) both equal the submission served by the DIP node
- ✓ Patient of that split backup deleted → backup ends up with only a `deletion` document for the TAN and its parts are removed from `largeBackupParts`; [counter] an injected part of another TAN (same site/use case/type) remains
- ✓ Report without MVH consent (injected into the queue, rebuilt from the `quarter-reports` entry of a consented report: the DIP node rejects uploads without sequencing consent — checked as precondition) → no backup documents, but dequeued into `quarter-reports`
- ✓ Prefilled queue re-delivers an already backed-up and flushed report (rebuilt from its `quarter-reports` entry) → still exactly one `submission` and one `report` document (WARN "already exists; skipped" for both); report leaves the queue, still exactly one `quarter-reports` entry (WARN "for quarter report already exists; skipped")
- ✓ Keyfile missing → report stays in the queue in state `confirmed`, no backup documents, ERROR logged
- ✓ Keyfile restored → the stuck report is backed up and dequeued
- ✓ `polling.minNumSubmissionDownloads`: 3 left-over `confirmed` reports per instance (produced with a missing keyfile) → ccdn-rd (set to 1) backs up one submission per workflow cycle, [counter] ccdn-mtb (set to 25) backs up all three in one cycle
- ✓ Patient with initial + correction deleted → one DeletionEvent per TAN from the DIP node; backup ends up with only a `deletion` document per TAN
- ✓ zKDK restart re-fetches the deletion history (WARN logged) → still exactly one `deletion` document per TAN
- ✓ RD deletion of a patient on UK1 → RD backups replaced by a `deletion` document, MTB backups of the same patient untouched, no MTB DeletionEvent
- Deletion before backup (patient deleted while its report is still queued in the zKDK) → the zKDK cannot confirm/download it. Rare, handled manually — not tested
- Deletion with `scope=query` → expected: no DeletionEvent, backup intact — not yet tested
- Deletion with `scope=mvgenomseq` → expected: DeletionEvent emitted — not yet tested
- Rejection of invalid downloaded submissions (`validateSubmission`) — out of scope

## `quarter-reports` collection (`CcdnBackupSpec`)

- ✓ Every dequeued report (with and without MVH consent) stored exactly once, `createdAt` as floating UTC, `year`/`quarter` matching it
- ✓ Schema validator rejects a document whose `quarter` does not match `createdAt` (code 121); [counter] same document with the matching quarter is accepted
