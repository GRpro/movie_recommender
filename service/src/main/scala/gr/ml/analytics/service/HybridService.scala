package gr.ml.analytics.service

import java.io.File

import com.github.tototoshi.csv.{CSVReader, CSVWriter}
import com.typesafe.config.{Config, ConfigFactory}
import gr.ml.analytics.service.cf.{CFJob, CFPredictionService}
import gr.ml.analytics.service.contentbased.{CBPredictionService, RandomForestEstimatorBuilder}
import gr.ml.analytics.util._
import org.apache.spark.ml.Pipeline
import org.apache.spark.sql.SparkSession
import org.slf4j.LoggerFactory

class HybridService(subRootDir: String, sparkSession: SparkSession, config: Config, source: Source, sink: Sink, paramsStorage: ParamsStorage) extends Constants{

  import sparkSession.implicits._
  val cfPredictionService = new CFPredictionService(subRootDir)
  val cbPredictionService = new CBPredictionService(subRootDir)
  val csv2svmConverter = new CSVtoSVMConverter(subRootDir)

  private val cfPredictionsTable: String = config.getString("cassandra.cf_predictions_table")
  private val cbPredictionsTable: String = config.getString("cassandra.cb_predictions_table")
  private val hybridPredictionsTable: String = config.getString("cassandra.hybrid_predictions_table")

  def run(cbPipeline: Pipeline): Unit ={
    sink.persistPopularItems()

    while(true){
      Thread.sleep(5000) // can be increased for production

      CFJob(sparkSession, config, None, None, paramsStorage.getParams()).run() // TODO add logging somehow

      // CBJob:
      val lastNSeconds = paramsStorage.getParams().get("hb_last_n_seconds").get.toString.toLong
      val userIds = source.getUserIdsForLastNSeconds(lastNSeconds)
      for(userId <- userIds){
        Util.tryAndLog(cbPredictionService.updateModelForUser(cbPipeline, userId), subRootDir + " :: Content-based:: Updating model for user " + userId)
        Util.tryAndLog(cbPredictionService.updatePredictionsForUser(userId), subRootDir + " :: Content-based:: Updating predictions for User " + userId)
        LoggerFactory.getLogger("progressLogger").info(subRootDir + " :: ##################### END OF ITERATION ##########################")
      }

      val collaborativeWeight = paramsStorage.getParams().get("hb_collaborative_weight").get.toString.toLong
      combinePredictionsForLastUsers(collaborativeWeight)
    }

  }

  def prepareNecessaryFiles(): Unit ={
    val startTime = System.currentTimeMillis()
    if(!(new File(String.format(moviesWithFeaturesPath, subRootDir)).exists()))
      new GenresFeatureEngineering(subRootDir).createAllMoviesWithFeaturesFile()
    val lastNSeconds = paramsStorage.getParams().get("hb_last_n_seconds").get.toString.toLong
    val userIds = source.getUserIdsForLastNSeconds(lastNSeconds)
    userIds.foreach(csv2svmConverter.createSVMRatingsFileForUser)
    if(!(new File(allMoviesSVMPath).exists()))
      csv2svmConverter.createSVMFileForAllItems()
    val finishTime = System.currentTimeMillis()

    LoggerFactory.getLogger("progressLogger").info(subRootDir + " :: Startup time took: " + (finishTime - startTime) + " millis.")
  }

//  def runOneCycle(cbPipeline: Pipeline): Unit ={
//    Util.tryAndLog(cfPredictionService.updateModel(), subRootDir + " :: Collaborative:: Updating model")
//    val lastNSeconds = paramsStorage.getParams().get("hb_last_n_seconds").get.toString.toLong
//    val userIds = source.getUserIdsForLastNSeconds(lastNSeconds)
//    for(userId <- userIds){
//      Util.tryAndLog(cbPredictionService.updateModelForUser(cbPipeline, userId), subRootDir + " :: Content-based:: Updating model for user " + userId)
//      Util.tryAndLog(cfPredictionService.updatePredictionsForUser(userId), subRootDir + " :: Collaborative:: Updating predictions for User " + userId)
//      Util.tryAndLog(cbPredictionService.updatePredictionsForUser(userId), subRootDir + " :: Content-based:: Updating predictions for User " + userId)
//      LoggerFactory.getLogger("progressLogger").info(subRootDir + " :: ##################### END OF ITERATION ##########################")
//    }
//  }

  def multiplyByWeight(predictions: List[List[String]], weight: Double): List[List[String]] ={
    predictions.map(l=>List(l(0), l(1), (l(2).toDouble * weight).toString))
  }

  def combinePredictionsForLastUsers(collaborativeWeight: Double): Unit ={
    val lastNSeconds = paramsStorage.getParams().get("hb_last_n_seconds").get.toString.toLong
    val userIds = source.getUserIdsForLastNSeconds(lastNSeconds) // TODO not good, that we have to get it every time...
    for(userId <- userIds){
      combinePredictionsForUser(userId, collaborativeWeight)
    }
  }

  def combinePredictionsForUser(userId: Int, collaborativeWeight: Double): Unit ={
    val cfPredictionsDF = source.getPredictionsForUser(userId, cfPredictionsTable).withColumn("prediction", $"prediction" * collaborativeWeight)
    val cbPredictionsDF = source.getPredictionsForUser(userId, cbPredictionsTable).withColumn("prediction", $"prediction" * (1-collaborativeWeight))

    val hybridPredictions = cfPredictionsDF // TODO should we use spark here?
      .select("key","userid", "itemid", "prediction")
      .as("d1").join(cbPredictionsDF.as("d2"), $"d1.key" === $"d2.key")
      .withColumn("hybrid_prediction", $"d1.prediction" + $"d2.prediction")
      .select($"d1.key", $"d2.userid", $"d2.itemid", $"hybrid_prediction".as("prediction"))
      .sort($"prediction".desc)
      .limit(100) // TODO extract to config

    // TODO we probably don't need to save it, since final predictions is enough.. check if we benefit in time if not persisting it
    sink.storePredictions(hybridPredictions, hybridPredictionsTable) // TODO should we use spark here?

    val finalPredictedIDs = hybridPredictions.select("itemid").collect().map(r => r.getInt(0)).toList
//    val finalPredictedIDs = hybridPredictions.map(l=>l(1).toInt)
//    cfPredictionService.persistPredictedIdsForUser(userId, finalPredictedIDs)
    sink.storeRecommendedItemIDs(userId, finalPredictedIDs)
  }
}

object HybridServiceRunner extends App with Constants{
  Util.windowsWorkAround()
  Util.loadAndUnzip(mainSubDir) // TODO new ratings will be rewritten!!
  //      val cbPipeline = LinearRegressionWithElasticNetBuilder.build(userId)
  val cbPipeline = RandomForestEstimatorBuilder.build(mainSubDir)
  //      val cbPipeline = GeneralizedLinearRegressionBuilder.build(userId)
  val sparkSession = SparkUtil.sparkSession()
  val config = ConfigFactory.load("application.conf")
  val source = new CassandraSource(sparkSession, config)
  val sink = new CassandraSink(sparkSession, config)
  val paramsStorage: ParamsStorage = new RedisParamsStorage
  val hb = new HybridService(mainSubDir, sparkSession, config, source, sink, paramsStorage)
  hb.run(cbPipeline)
}
