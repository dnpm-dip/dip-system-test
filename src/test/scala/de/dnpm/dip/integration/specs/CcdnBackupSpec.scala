package de.dnpm.dip.integration.specs

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{LocalDateTime, ZoneOffset}
import java.time.temporal.ChronoUnit
import java.util.{Base64, Date, UUID}
import scala.jdk.CollectionConverters._
import scala.util.Try
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import org.bson.Document
import play.api.libs.json._
import de.dnpm.dip.integration.support.{BackupCrypto, CcdnContainer, CcdnMongo, DipIntegrationSuite}

/** Tests for the zKDK backup workflow (central-data-node, branch feature-centralbackups).
 *
 *  After a report is confirmed to its DIP node, the zKDK
 *    → downloads the submission and stores it encrypted in ccdn.backup  (status "submissionbackedup")
 *    → stores the report encrypted in ccdn.backup                       (status "reportbackedup")
 *    → stores the report in plain text in ccdn.quarter-reports and removes it from its queue
 *  Reports without MVH consent skip both backup steps. DeletionEvents polled from the DIP nodes
 *  remove a TAN's backups and are themselves backed up.
 *
 *  Several tests restart or recreate ccdn-mtb, so this spec runs last. Tests are ordered:
 *  later ones reuse TANs produced by earlier ones and cancel if those are missing.
 */
class CcdnBackupSpec extends DipIntegrationSuite {

  private val ccdnMtb = new CcdnContainer("ccdn-mtb")

  private val MissingKeyPath = "/ccdn_config/does-not-exist.pem"
  private var keyfileHidden  = false

  // State handed on between tests
  private var consented: Option[(String, String)]           = None // (TAN, patient ID)
  private var withoutConsent: Option[(String, LocalDateTime)] = None // (TAN, createdAt)
  private var stuckWithoutKey: Option[String]                 = None
  private var deletedTans: Seq[String]                        = Seq.empty

  override def afterAll(): Unit =
    try if (keyfileHidden) restoreKeyfile()
    finally super.afterAll()

  private def restoreKeyfile(): Unit = {
    ccdnMtb.recreate()
    keyfileHidden = false
  }

  private def upload(useCase: String, patientId: String = UUID.randomUUID().toString): (String, String) = {
    val (tan, body) = generateFakeMvhSubmission(useCase, patientId = patientId)
    val resp        = node1.post(s"/$useCase/etl/patient-record", body)
    withClue(s"$useCase upload: ${resp.code} ${resp.body.merge}\n") {
      resp.code.code shouldBe 200
    }
    (tan, body)
  }

  private def withMetadata(body: String, fields: (String, Json.JsValueWrapper)*): String = {
    val json = Json.parse(body).as[JsObject]
    (json ++ Json.obj("metadata" -> ((json \ "metadata").as[JsObject] ++ Json.obj(fields: _*)))).toString()
  }

  private def dipReportCreatedAt(useCase: String, tan: String): LocalDateTime = {
    val resp = node1.get(s"/$useCase/peer2peer/mvh/submission-reports/$tan")
    withClue(s"GET submission-report $tan: ${resp.code} ${resp.body.merge}\n") {
      resp.code.code shouldBe 200
    }
    LocalDateTime.parse((Json.parse(resp.body.getOrElse(fail("Unexpected error body"))) \ "createdAt").as[String])
  }

  private def awaitBackedUpAndFlushed(tan: String, timeoutMs: Long = 90_000L): Unit =
    eventually(timeoutMs) {
      withClue(s"backup documents of TAN=$tan: ") {
        CcdnMongo.backupTypes(tan) shouldBe Seq("report", "submission")
      }
      withClue(s"TAN=$tan still in the ccdn-mtb queue: ") {
        ccdnMtb.queueFile(tan) shouldBe empty
      }
    }

  // ─── Backup of consented reports ───────────────────────────────────────────

  "The zKDK backup" should "back up submission and report of an MVH-consented report and remove it from the queue" in {
    // Also the [counter] to the missing-keyfile test: with the keyfile present, the report does not stay queued.
    val patientId = UUID.randomUUID().toString
    val (tan, _)  = upload("mtb", patientId)

    awaitBackedUpAndFlushed(tan)
    withClue(s"TAN=$tan should have been moved to the archive: ") {
      ccdnMtb.archivedFile(tan) shouldBe defined
    }
    consented = Some(tan -> patientId)
  }

  it should "store plaintext metadata and content in the format of EncryptionService.Encrypted" in {
    val (tan, _)  = consented.getOrElse(cancel("depends on the consented-backup test"))
    val createdAt = dipReportCreatedAt("mtb", tan)
    val docs      = CcdnMongo.backupDocs(tan)
    docs.map(_.getString("type")).sorted shouldBe Seq("report", "submission")

    for (doc <- docs) withClue(s"${doc.getString("type")} backup of TAN=$tan: ") {
      doc.getString("tan") shouldBe tan
      doc.getString("site") shouldBe "UK1"
      doc.getString("usecase") shouldBe "MTB"
      LocalDateTime.parse(doc.getString("submittedAt")) shouldBe createdAt

      val content = doc.get("content", classOf[Document])
      content.keySet.asScala.toSet shouldBe BackupCrypto.EncryptedFields
      content.getString("algorithm") shouldBe BackupCrypto.Algorithm

      def decoded(field: String): Array[Byte] =
        Try(Base64.getDecoder.decode(content.getString(field)))
          .getOrElse(fail(s"'$field' is not valid base64"))

      decoded("iv") should have length 16
      decoded("encryptedKey") should have length BackupCrypto.rsaBlockBytes.toLong
      val ciphertext = decoded("ciphertext")
      ciphertext should not be empty
      withClue("AES-CBC ciphertext length must be a multiple of the block size: ") {
        ciphertext.length % 16 shouldBe 0
      }
      withClue("ciphertext must not be plaintext JSON: ") {
        Try(Json.parse(new String(ciphertext, UTF_8))).isFailure shouldBe true
      }
    }
  }

  it should "produce backups that decrypt with the private key in crypto/" in {
    val (tan, patientId) = consented.getOrElse(cancel("depends on the consented-backup test"))
    val docs             = CcdnMongo.backupDocs(tan)
    docs.map(_.getString("type")).sorted shouldBe Seq("report", "submission")

    for (doc <- docs) withClue(s"${doc.getString("type")} backup of TAN=$tan: ") {
      val payload = BackupCrypto.decrypt(doc.get("content", classOf[Document]))
      doc.getString("type") match {
        case "submission" =>
          (payload \ "metadata" \ "transferTAN").asOpt[String] shouldBe Some(tan)
          Json.stringify(payload) should include(patientId)
        case "report" =>
          (payload \ "id").asOpt[String] shouldBe Some(tan)
          (payload \ "patient").asOpt[String] shouldBe Some(patientId)
      }
    }
  }

  // ─── Reports without MVH consent ───────────────────────────────────────────

  it should "not back up a report without MVH consent, but still remove it from the queue" in {
    val (templateTan, _) = consented.getOrElse(cancel("depends on the consented-backup test"))

    // The DIP node rejects submissions without sequencing consent (Fatal issue in the metadata
    // validator), so such a report cannot come in through the ETL API: put it into the queue directly.
    val (_, body)  = generateFakeMvhSubmission("mtb")
    val json       = Json.parse(body)
    val provisions = (json \ "metadata" \ "modelProjectConsent" \ "provisions").as[Seq[JsObject]].map { p =>
      if ((p \ "purpose").asOpt[String].contains("sequencing")) p ++ Json.obj("type" -> "deny") else p
    }
    val denied = withMetadata(body,
      "modelProjectConsent" -> ((json \ "metadata" \ "modelProjectConsent").as[JsObject] ++ Json.obj("provisions" -> provisions))
    )
    val deniedResp = node1.post("/mtb/etl/patient-record", denied)
    withClue(s"precondition: upload without sequencing consent should be rejected: ${deniedResp.code} ${deniedResp.body.merge}\n") {
      deniedResp.code.code should (be >= 400 and be < 500)
    }

    // A report in state "confirmed" (i.e. already confirmed to the DIP node) with a TAN unknown
    // to the DIP node, so the zKDK has no reason to contact the DIP node about it.
    val archived = ccdnMtb.archivedFile(templateTan).getOrElse(fail(s"No archived report for TAN=$templateTan"))
    val template = Json.parse(ccdnMtb.read(archived)).as[JsObject]
    val tan      = randomHex()
    val report   = template ++ Json.obj(
      "id"            -> tan,
      "patient"       -> UUID.randomUUID().toString,
      "status"        -> "confirmed",
      "consentStatus" -> ((template \ "consentStatus").asOpt[JsObject].getOrElse(Json.obj()) ++ Json.obj("mv-consent" -> false))
    )
    val fileName = archived.split('/').last.replace(templateTan, tan)
    ccdnMtb.write(s"${ccdnMtb.queueDir}/$fileName", Json.stringify(report))
    ccdnMtb.restart()

    awaitFlushedByZkdk(tan, "mtb")
    eventually(30_000L) {
      withClue(s"TAN=$tan still in the ccdn-mtb queue: ") {
        ccdnMtb.queueFile(tan) shouldBe empty
      }
    }
    withClue(s"a report without MVH consent must not be backed up: ") {
      CcdnMongo.backupDocs(tan) shouldBe empty
    }
    withoutConsent = Some(tan -> LocalDateTime.parse((template \ "createdAt").as[String]))
  }

  // ─── Idempotency ───────────────────────────────────────────────────────────

  it should "keep a single backup per document when a prefilled queue re-delivers a backed-up report" in {
    val (tan, _) = upload("mtb")
    awaitBackedUpAndFlushed(tan)
    val archived = ccdnMtb.archivedFile(tan).getOrElse(fail(s"No archived report for TAN=$tan"))

    // Simulate a zKDK start-up with a prefilled queue: put the report back in state "confirmed",
    // so that submission and report are backed up a second time. The archived copy stays, so
    // the final step also collides with the existing file in the archive.
    val fileName = archived.split('/').last
    val requeued = Json.parse(ccdnMtb.read(archived)).as[JsObject] ++ Json.obj("status" -> "confirmed")
    ccdnMtb.write(s"${ccdnMtb.queueDir}/$fileName", Json.stringify(requeued))
    val since = ccdnMtb.restart()

    eventually(90_000L) {
      for (kind <- Seq("Submission", "Report")) withClue(s"WARN about the existing $kind backup of TAN=$tan: ") {
        ccdnMtb.warnedExistingBackup(since, kind, tan) shouldBe true
      }
      withClue(s"WARN about $fileName already being archived: ") {
        ccdnMtb.logsSince(since).linesIterator.exists(l =>
          l.contains("WARN") && l.contains(s"File $fileName already exists in backup folder")
        ) shouldBe true
      }
      withClue(s"TAN=$tan still in the ccdn-mtb queue: ") {
        ccdnMtb.queueFile(tan) shouldBe empty
      }
    }
    CcdnMongo.backupTypes(tan) shouldBe Seq("report", "submission")
    withClue("the existing archived copy must be kept: ") {
      ccdnMtb.archivedFile(tan) shouldBe Some(archived)
    }
  }

  // ─── Missing encryption keyfile ────────────────────────────────────────────

  it should "keep a report in the queue in state 'confirmed' while the encryption keyfile is missing" in {
    keyfileHidden = true
    val since     = ccdnMtb.recreate("CCDN_MTB_PUBLIC_KEY_PATH" -> MissingKeyPath)
    val (tan, _)  = upload("mtb")

    awaitReportStatusInDipNode(tan, "Submitted", useCase = "mtb", timeoutMs = 90_000L)
    eventually(90_000L) {
      withClue(s"ERROR about the failed submission backup of TAN=$tan: ") {
        ccdnMtb.logsSince(since).linesIterator.exists(l =>
          l.contains("ERROR") && l.contains(s"Failed to back up Submission $tan")
        ) shouldBe true
      }
    }
    val queued = ccdnMtb.queueFile(tan).map(ccdnMtb.read).getOrElse(fail(s"TAN=$tan is no longer in the ccdn-mtb queue"))
    (Json.parse(queued) \ "status").asOpt[String] shouldBe Some("confirmed")
    CcdnMongo.backupDocs(tan) shouldBe empty
    stuckWithoutKey = Some(tan)
  }

  it should "back up and dequeue the stuck report once the keyfile is back" in {
    val tan = stuckWithoutKey.getOrElse(cancel("depends on the missing-keyfile test"))
    restoreKeyfile()
    awaitBackedUpAndFlushed(tan)
  }

  // ─── Deletions ─────────────────────────────────────────────────────────────

  it should "replace all backups of a deleted patient by one DeletionEvent backup per submission" in {
    val patientId              = UUID.randomUUID().toString
    val (initialTan, body)     = upload("mtb", patientId)
    val correctionTan          = randomHex()
    val correctionResp         = node1.post("/mtb/etl/patient-record",
      withMetadata(body, "type" -> "correction", "transferTAN" -> correctionTan))
    withClue(s"correction upload: ${correctionResp.code} ${correctionResp.body.merge}\n") {
      correctionResp.code.code shouldBe 200
    }
    val tans = Seq(initialTan, correctionTan)
    tans.foreach(awaitBackedUpAndFlushed(_, timeoutMs = 120_000L))

    val deleteResp = node1.delete(s"/mtb/etl/patient/$patientId")
    withClue(s"DELETE patient: ${deleteResp.code} ${deleteResp.body.merge}\n") {
      deleteResp.code.code shouldBe 200
    }
    val events = deletionEvents(node1, "mtb").filter(e => (e \ "patient").asOpt[String].contains(patientId))
    withClue("one DeletionEvent per submission of the patient: ") {
      events.flatMap(e => (e \ "tan").asOpt[String]).sorted shouldBe tans.sorted
    }

    eventually(90_000L) {
      for (tan <- tans) withClue(s"backup documents of deleted TAN=$tan: ") {
        CcdnMongo.backupTypes(tan) shouldBe Seq("deletion")
      }
    }
    deletedTans = tans
  }

  it should "not duplicate DeletionEvent backups when it re-fetches the deletion history after a restart" in {
    if (deletedTans.isEmpty) cancel("depends on the deletion test")
    // The last-queried timestamp per site and use case is kept in memory only, so after a restart
    // the complete deletion history is fetched and applied again.
    val since = ccdnMtb.restart()
    eventually(90_000L) {
      for (tan <- deletedTans) withClue(s"WARN about the existing DeletionEvent backup of TAN=$tan: ") {
        ccdnMtb.warnedExistingBackup(since, "DeletionEvent", tan) shouldBe true
      }
    }
    for (tan <- deletedTans) withClue(s"backup documents of deleted TAN=$tan: ") {
      CcdnMongo.backupTypes(tan) shouldBe Seq("deletion")
    }
  }

  it should "not touch MTB backups when the same patient is deleted from the RD use case" in {
    val patientId = UUID.randomUUID().toString
    val (mtbTan, _) = upload("mtb", patientId)
    val (rdTan, _)  = upload("rd", patientId)
    awaitBackedUpAndFlushed(mtbTan)
    eventually(90_000L) {
      withClue(s"backup documents of RD TAN=$rdTan: ") {
        CcdnMongo.backupTypes(rdTan) shouldBe Seq("report", "submission")
      }
    }

    val deleteResp = node1.delete(s"/rd/etl/patient/$patientId")
    withClue(s"DELETE RD patient: ${deleteResp.code} ${deleteResp.body.merge}\n") {
      deleteResp.code.code shouldBe 200
    }
    val deletedAt = java.time.Instant.now().minusSeconds(1)
    eventually(90_000L) {
      withClue(s"backup documents of deleted RD TAN=$rdTan: ") {
        CcdnMongo.backupDocs(rdTan).map(d => d.getString("type") -> d.getString("usecase")) shouldBe Seq("deletion" -> "RD")
      }
    }

    // Give ccdn-mtb two full cycles to (wrongly) apply anything before checking that nothing changed
    eventually(60_000L) {
      ccdnMtb.workflowCyclesSince(deletedAt) should be >= 2
    }
    withClue(s"MTB backups of the same patient (TAN=$mtbTan) must remain: ") {
      CcdnMongo.backupTypes(mtbTan) shouldBe Seq("report", "submission")
    }
    deletionEvents(node1, "mtb").flatMap(e => (e \ "tan").asOpt[String]) should not contain mtbTan
  }

  // ─── quarter-reports collection ────────────────────────────────────────────

  "The quarter-reports collection" should "hold every dequeued report exactly once, with year and quarter matching createdAt" in {
    val (consentedTan, _) = consented.getOrElse(cancel("depends on the consented-backup test"))
    val (noConsentTan, noConsentCreatedAt) = withoutConsent.getOrElse(cancel("depends on the no-consent test"))

    for ((tan, createdAt) <- Seq(consentedTan -> dipReportCreatedAt("mtb", consentedTan), noConsentTan -> noConsentCreatedAt))
      withClue(s"quarter-reports entry of TAN=$tan: ") {
        val docs = CcdnMongo.quarterReportDocs(tan, "MTB")
        docs should have size 1
        val doc = docs.head
        // Stored as "floating" UTC: the German wall-clock time interpreted as UTC
        LocalDateTime.ofInstant(doc.getDate("createdAt").toInstant, ZoneOffset.UTC) shouldBe createdAt.truncatedTo(ChronoUnit.MILLIS)
        doc.getInteger("year").intValue shouldBe createdAt.getYear
        doc.getInteger("quarter").intValue shouldBe (createdAt.getMonthValue - 1) / 3 + 1
      }
  }

  it should "reject a document whose quarter does not match its createdAt" in {
    assume(CcdnMongo.collectionExists("quarter-reports"), "the zKDK creates the collection on its first dequeued report")
    val createdAt = LocalDateTime.of(2026, 2, 15, 12, 0)

    def document(quarter: Int) =
      new Document("id", s"validator-test-${randomHex(8)}")
        .append("site", new Document("code", "UK1"))
        .append("useCase", "MTB")
        .append("createdAt", Date.from(createdAt.toInstant(ZoneOffset.UTC)))
        .append("year", 2026)
        .append("quarter", quarter)

    def insertAndRemove(doc: Document): Try[Unit] = {
      val outcome = Try(CcdnMongo.quarterReports.insertOne(doc)).map(_ => ())
      CcdnMongo.quarterReports.deleteOne(Filters.eq("id", doc.getString("id")))
      outcome
    }

    insertAndRemove(document(quarter = 3)).failed.toOption match {
      case Some(e: MongoWriteException) =>
        withClue(s"expected DocumentValidationFailure (121), got: ${e.getMessage}\n") {
          e.getCode shouldBe 121
        }
      case other =>
        fail(s"Document with quarter 3 for a February createdAt was not rejected by the validator: $other")
    }

    withClue("[counter] the same document with the matching quarter must be accepted: ") {
      insertAndRemove(document(quarter = 1)).isSuccess shouldBe true
    }
  }
}
