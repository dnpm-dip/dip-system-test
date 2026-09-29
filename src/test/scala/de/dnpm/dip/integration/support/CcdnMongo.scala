package de.dnpm.dip.integration.support

import scala.jdk.CollectionConverters._
import com.mongodb.client.{MongoClients, MongoCollection}
import com.mongodb.client.model.Filters
import org.bson.Document

/** Read access to the zKDK's MongoDB ("ccdn" database, shared by ccdn-mtb and ccdn-rd). */
object CcdnMongo {

  private lazy val client = MongoClients.create("mongodb://localhost:27017")

  private def db = client.getDatabase("ccdn")

  def backup: MongoCollection[Document] = db.getCollection("backup")

  def quarterReports: MongoCollection[Document] = db.getCollection("quarter-reports")

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
