package de.dnpm.dip.integration.support

import scala.jdk.CollectionConverters._
import com.mongodb.client.{MongoClients, MongoCollection}
import com.mongodb.client.model.Filters
import org.bson.Document
import org.bson.types.ObjectId

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
}
