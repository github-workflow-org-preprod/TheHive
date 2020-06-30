package org.thp.thehive.controllers.v0

import java.lang.{Long => JLong}

import gremlin.scala.{__, By, Graph, GremlinScala, Key, Vertex}
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.models.Database
import org.thp.scalligraph.services._
import org.thp.scalligraph.steps.StepsOps._
import org.thp.scalligraph.steps.Traversal
import org.thp.thehive.models._
import org.thp.thehive.services.{CaseSteps, ShareSteps}
import play.api.libs.json._

import scala.collection.JavaConverters._

trait CaseRenderer {

  def observableStats(
      shareTraversal: GremlinScala[Vertex]
  )(implicit db: Database, graph: Graph): GremlinScala[JsObject] =
    new ShareSteps(shareTraversal)
      .observables
      .count
      .map(count => Json.obj("count" -> count))
      .raw

  def taskStats(shareTraversal: GremlinScala[Vertex])(implicit db: Database, graph: Graph): GremlinScala[JsObject] =
    new ShareSteps(shareTraversal)
      .tasks
      .active
      .groupCount(By(Key[String]("status")))
      .map { statusAgg =>
        val (total, result) = statusAgg.asScala.foldLeft(0L -> JsObject.empty) {
          case ((t, r), (k, v)) => (t + v) -> (r + (k -> JsNumber(v.toInt)))
        }
        result + ("total" -> JsNumber(total))
      }
      .raw

  def alertStats(caseTraversal: GremlinScala[Vertex]): GremlinScala[Seq[JsObject]] =
    caseTraversal
      .inTo[AlertCase]
      .group(By(Key[String]("type")), By(Key[String]("source")))
      .map { alertAgg =>
        alertAgg
          .asScala
          .flatMap {
            case (tpe, listOfSource) =>
              listOfSource.asScala.map(s => Json.obj("type" -> tpe, "source" -> s))
          }
          .toSeq
      }
  // seq({caseId, title})

  def mergeFromStats(caseTraversal: GremlinScala[Vertex]): GremlinScala[Seq[JsObject]] = caseTraversal.constant(Nil)

  def mergeIntoStats(caseTraversal: GremlinScala[Vertex]): GremlinScala[Seq[JsObject]] = caseTraversal.constant(Nil)

  def sharedWithStats(
      caseTraversal: GremlinScala[Vertex]
  )(implicit db: Database, graph: Graph): GremlinScala[Seq[String]] =
    new CaseSteps(caseTraversal).organisations.name.fold.map(_.asScala.toSeq).raw

  def originStats(caseTraversal: GremlinScala[Vertex])(implicit db: Database, graph: Graph): GremlinScala[String] =
    new CaseSteps(caseTraversal).origin.name.raw

  def shareCountStats(caseTraversal: GremlinScala[Vertex])(implicit db: Database, graph: Graph): GremlinScala[JLong] =
    new CaseSteps(caseTraversal).organisations.count.raw

  def isOwnerStats(
      caseTraversal: GremlinScala[Vertex]
  )(implicit db: Database, graph: Graph, authContext: AuthContext): GremlinScala[Boolean] =
    new CaseSteps(caseTraversal).origin.name.map(_ == authContext.organisation).raw

  def caseStatsRenderer(
      implicit authContext: AuthContext,
      db: Database,
      graph: Graph
  ): CaseSteps => Traversal[JsObject, JsObject] =
    _.project(
      _.apply(
        By(
          __[Vertex].coalesce(
            new CaseSteps(_)
              .share
              .project(
                _.apply(By(taskStats(__[Vertex])))
                  .and(By(observableStats(__[Vertex])))
              )
              .raw,
            _.constant(JsObject.empty -> JsObject.empty)
          )
        )
      ).and(By(alertStats(__[Vertex])))
        .and(By(mergeFromStats(__[Vertex])))
        .and(By(mergeIntoStats(__[Vertex])))
        //        .and(By(sharedWithStats(__[Vertex])))
        //        .and(By(originStats(__[Vertex])))
        .and(By(isOwnerStats(__[Vertex])))
        .and(By(shareCountStats(__[Vertex])))
    ).map {
      case ((tasks, observables), alerts, mergeFrom, mergeInto, isOwner, shareCount) =>
        Json.obj(
          "tasks"      -> tasks,
          "artifacts"  -> observables,
          "alerts"     -> alerts,
          "mergeFrom"  -> mergeFrom,
          "mergeInto"  -> mergeInto,
          "isOwner"    -> isOwner,
          "shareCount" -> (shareCount.longValue() - 1)
        )
    }
}
