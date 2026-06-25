package de.dnpm.dip.integration.specs

import org.scalatest.Suites

class IntegrationTests extends Suites(
  new BrokerSpec,         // smoke: connectivity and health endpoints
  new EtlValidationSpec,  // ETL validate / delete semantics
  new UploadSpec,         // upload dedup, type, correction rules
  new FederatedQuerySpec, // cross-node queries (uses pre-seeded random data)
  new CcdnWorkflowSpec,   // CCDN end-to-end polling flow — last, timing-sensitive
)