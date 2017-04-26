package gr.ml.analytics.cassandra

import com.outworkers.phantom.database.Database
import com.outworkers.phantom.dsl.KeySpaceDef
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
  * The database which stores user input such as ratings and items.
  * Currently only ratings are supported.
  *
  * @param connector Cassandra connector
  */
class InputDatabase(override val connector: KeySpaceDef) extends Database[InputDatabase](connector) with LazyLogging {

  object recommendationsModel extends ConcreteRecommendationModel with connector.Connector

  object ratingModel extends ConcreteRatingModel with connector.Connector

  object schemasModel extends ConcreteSchemaModel with connector.Connector

  object clusteredItemsModel extends ConcreteClusteredItemsModel with connector.Connector

  // create tables if not exist
  private val f1 = schemasModel.create.ifNotExists().future()
  private val f2 = recommendationsModel.create.ifNotExists().future()
  private val f3 = ratingModel.create.ifNotExists().future()
  private val f4 = clusteredItemsModel.create.ifNotExists().future()

  try {
    Await.ready(f1, 3.seconds)
    Await.ready(f2, 3.seconds)
    Await.ready(f3, 3.seconds)
    Await.ready(f4, 3.seconds)
  } catch {
    case e: Throwable =>
      //ignore
      logger.warn("Error creating models", e)
  }

}
