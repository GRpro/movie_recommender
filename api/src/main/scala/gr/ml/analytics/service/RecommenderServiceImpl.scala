package gr.ml.analytics.service

import com.typesafe.scalalogging.LazyLogging
import gr.ml.analytics.cassandra.CassandraStorage
import gr.ml.analytics.online.ItemItemRecommender

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class RecommenderServiceImpl(inputDatabase: CassandraStorage, itemItemRecommenderOption: Option[ItemItemRecommender]) extends RecommenderService with LazyLogging {

  private lazy val recommendationModel = inputDatabase.recommendationsModel

  /**
    * @inheritdoc
    */
  override def getTop(userId: Int, n: Int): Future[List[Int]] = {
    // The logic is:
    // If model has been trained for a given user and predictions are stored return those predictions
    // If there is no model trained return result from online item-to-item CF algorithm
    recommendationModel.getOne(userId).map {
      case Some(recommendation) => recommendation.topItems.take(n)
      case None => throw new RuntimeException()
    } recoverWith {
      case e: RuntimeException =>
        itemItemRecommenderOption match {
          case Some(recommender) =>
            val res = recommender.getRecommendations(userId.toString, n)
              .map(seq => seq.map(pair => pair._1.toInt).toList)
            res
          case None =>
            logger.info(s"No recommendations for user $userId")
            Future(List())
        }
    }
  }
}
