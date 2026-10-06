package de.dnpm.dip.integration.support

import java.time.{LocalDateTime, ZoneOffset}
import scala.jdk.CollectionConverters._
import com.mongodb.client.{MongoClients, MongoCollection}
import com.mongodb.client.model.Filters
import org.bson.Document
import org.bson.types.ObjectId
import play.api.libs.json.{JsObject, Json}

/** Read access to the zKDK's MongoDB ("ccdn" database, shared by ccdn-mtb and ccdn-rd). */
object CcdnMongo {

  private lazy val client = MongoClients.create("mongodb://localhost:27017")

  private def db = client.getDatabase("ccdn")

  def backup: MongoCollection[Document] = db.getCollection("backup")

  def quarterReports: MongoCollection[Document] = db.getCollection("quarter-reports")

  /** Ciphertext parts of backups too large for a single document, see [[ciphertext]] */
  def largeBackupParts: MongoCollection[Document] = db.getCollection("largeBackupParts")

  def largeBackupParts(tan: String): Seq[Document] =
    largeBackupParts.find(Filters.eq("tan", tan)).into(new java.util.ArrayList[Document]()).asScala.toSeq

  /** Ciphertext of a backup's "content": either stored in place, or split into the "largeBackupParts"
   *  documents whose "_id"s "ciphertextParts" lists in order.
   */
  def ciphertext(content: Document): String =
    Option(content.getList("ciphertextParts", classOf[ObjectId])).map(_.asScala.toSeq) match {
      case None => content.getString("ciphertext")
      case Some(ids) =>
        val parts = largeBackupParts.find(Filters.in("_id", ids.asJava)).into(new java.util.ArrayList[Document]())
          .asScala.map(p => p.getObjectId("_id") -> p.getString("ciphertext")).toMap
        ids.map(id => parts.getOrElse(id, throw new NoSuchElementException(s"Missing ciphertext part $id"))).mkString
    }

  def collectionExists(name: String): Boolean =
    db.listCollectionNames().into(new java.util.ArrayList[String]()).asScala.contains(name)

  def backupDocs(tan: String): Seq[Document] =
    backup.find(Filters.eq("tan", tan)).into(new java.util.ArrayList[Document]()).asScala.toSeq

  /** Sorted "type" values of all backup documents of `tan`; duplicates are kept so they show up in assertions. */
  def backupTypes(tan: String): Seq[String] =
    backupDocs(tan).map(_.getString("type")).sorted

  def quarterReportDocs(tan: String, useCase: String): Seq[Document] =
    quarterReports
      .find(Filters.and(Filters.eq("id", tan), Filters.eq("useCase", useCase)))
      .into(new java.util.ArrayList[Document]())
      .asScala.toSeq

  /** The report of `tan` as the zKDK keeps it in its queue, rebuilt from its quarter-reports document
   *  (Report JSON plus "year"/"quarter", "createdAt" as floating UTC date, truncated to millis).
   */
  def queuedReportFromQuarterReports(tan: String, useCase: String): Option[JsObject] =
    quarterReportDocs(tan, useCase).headOption.map { doc =>
      val createdAt = LocalDateTime.ofInstant(doc.getDate("createdAt").toInstant, ZoneOffset.UTC)
      Seq("_id", "year", "quarter").foreach(k => doc.remove(k))
      doc.put("createdAt", createdAt.toString)
      Json.parse(doc.toJson).as[JsObject]
    }
}
