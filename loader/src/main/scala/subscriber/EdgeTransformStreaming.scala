package subscriber

import com.kakao.s2graph.EdgeTransform
import com.kakao.s2graph.client.GraphRestClient
import com.kakao.s2graph.core.mysqls.Model
import com.kakao.s2graph.core.utils.Configuration._
import com.kakao.s2graph.core.{Graph, GraphConfig, GraphUtil}
import kafka.serializer.StringDecoder
import org.apache.spark.streaming.Durations._
import org.apache.spark.streaming.kafka.KafkaRDDFunctions.rddToKafkaRDDFunctions
import s2.config.S2ConfigFactory
import s2.spark.{HashMapParam, SparkApp}

import scala.collection.mutable.{HashMap => MutableHashMap}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/**
  * Created by hsleep(honeysleep@gmail.com) on 2015. 12. 8..
  */
object EdgeTransformStreaming extends SparkApp {
  lazy val config = S2ConfigFactory.config
  lazy val className = getClass.getName.stripSuffix("$")

  implicit val graphEx = ExecutionContext.Implicits.global

  val initialize = {
    println("streaming initialize")
    Model(config)
    true
  }

  val graphConfig = new GraphConfig(config)

  val inputTopics = Set(graphConfig.KAFKA_LOG_TOPIC, graphConfig.KAFKA_LOG_TOPIC_ASYNC)
  val strInputTopics = inputTopics.mkString(",")
  val groupId = buildKafkaGroupId(strInputTopics, "etl_to_graph")
  val kafkaParam = Map(
    "group.id" -> groupId,
    "metadata.broker.list" -> graphConfig.KAFKA_METADATA_BROKER_LIST,
    "zookeeper.connect" -> graphConfig.KAFKA_ZOOKEEPER,
    "zookeeper.connection.timeout.ms" -> "10000"
  )

  lazy val builder = new com.ning.http.client.AsyncHttpClientConfig.Builder()
  lazy val client = new play.api.libs.ws.ning.NingWSClient(builder.build)
  lazy val rest = new GraphRestClient(client, config.getOrElse("s2graph.url", "http://localhost"))
  lazy val transformer = new EdgeTransform(rest)
  lazy val streamHelper = getStreamHelper(kafkaParam)

  // should implement in derived class
  override def run(): Unit = {
    validateArgument("interval", "clear")
    val (interval, clear) = (args(0).toLong, args(1).toBoolean)
    if (clear) {
      streamHelper.kafkaHelper.consumerGroupCleanup()
    }
    val intervalInSec = seconds(interval)

    val conf = sparkConf(s"$strInputTopics: $className")
    val ssc = streamingContext(conf, intervalInSec)
    val sc = ssc.sparkContext

    val acc = sc.accumulable(MutableHashMap.empty[String, Long], "Throughput")(HashMapParam[String, Long](_ + _))

    val stream = streamHelper.createStream[String, String, StringDecoder, StringDecoder](ssc, inputTopics)

    stream.foreachRDD { (rdd, ts) =>
      rdd.foreachPartitionWithOffsetRange { case (osr, part) =>
        assert(initialize)

        // convert to edge format
        val orgEdges = for {
          (k, v) <- part
          line <- GraphUtil.parseString(v)
          edge <- Try { Graph.toEdge(line) }.toOption.flatten
        } yield edge

        // transform and send edges to graph
        val future = Future.sequence {
          for {
            orgEdgesGrouped <- orgEdges.grouped(10)
          } yield {
            acc += ("Input", orgEdgesGrouped.length)
            for {
              transEdges <- transformer.transformEdges(orgEdgesGrouped)
              rets <- transformer.loadEdges(transEdges, withWait = true)
            } yield {
              acc += ("Transform", rets.count(x => x))
              transEdges.zip(rets).filterNot(_._2).foreach { case (e, _) =>
                logError(s"failed to loadEdge: ${e.toLogString}")
              }
            }
          }
        }

        Await.ready(future, interval seconds)

        streamHelper.commitConsumerOffset(osr)
      }
    }

    ssc.start()
    ssc.awaitTermination()
  }
}
