# Testcases
## Uploads
- Upload in DIP node A happens. Expect a report to show up in a mock BfArM API and a feedback report
	- Polling interval should be minimal for that
- Duplicate TAN: upload the same `transferTAN` twice → expect 400 rejection on the second
- `Followup` without prior `Initial` for the same patient/episode → expect rejection
- `Initial` → second `Initial` for the same episode → expect rejection
- `Test` submission type: should not influence consent-check logic (filtered out from prior submission history)
- `ConsentRevocation` upload: patient record should be removed from the federated query index
- Upload for both MTB and RD use cases for the same patient → two independent report chains

## CCDN confirmation flow
- After CCDN confirms to BfArM and calls `POST …/submission-reports/{id}:submitted`, the DIP node marks the report as `Submitted` — re-fetching with `?status=Unsubmitted` should no longer include it
- Mock BfArM returns a non-2xx error → CCDN must NOT call `:submitted` back; report stays `Unsubmitted` and is picked up on the next polling cycle
- Calling `:submitted` on an already-submitted report is idempotent (no error)

## Multi-submission sequence
- `Initial` → `Followup` for the same patient/episode → two reports, both appear in CCDN polling
- `Initial` → `ConsentRevocation` → record deleted from query index

## Federated query
- Upload in DIP node A happens. Query in DIP node B happens
	- Case 1: Matching query. Results should match
	- Case 2: no match in query. Expect empty result
- Data in both node A and B; query from node A → aggregated results from both nodes
- Query before any upload → empty result
- Query after consent revocation → revoked patient's record excluded from results
- MTB query returns only MTB data, RD query returns only RD data (no cross-contamination)

## Connectivity / broker
- CCDN version check (`GET /api/peer2peer/meta-info`) returns ≥ 1.3 for both nodes
- One DIP node is stopped → CCDN still successfully polls and processes the remaining node
- Broker routes by `Host` header: `ukt.test` → node1, `ukl.test` → node2

## ETL / validation
- `POST /etl/patient-record:validate` with a valid record → 200 with no errors
- `POST /etl/patient-record:validate` with a malformed record → validation errors returned without persisting anything
- `DELETE /etl/patient/{id}` → patient no longer appears in federated query results from either node