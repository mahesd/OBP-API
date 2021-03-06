package code.bankconnectors

import java.util.UUID

import akka.actor.{Actor, ActorRef}
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, ProducerSettings, Subscriptions}
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import code.actorsystem.{ObpActorHelper, ObpActorInit}
import code.util.Helper.MdcLoggable
import net.liftweb.json
import net.liftweb.json.{DefaultFormats, Extraction, JsonAST}
import net.liftweb.util.Props
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}

import scala.concurrent.{ExecutionException, Future, TimeoutException}

/**
  * Actor for accessing kafka from North side.
  */
class KafkaStreamsHelperActor extends Actor with ObpActorInit with ObpActorHelper with MdcLoggable with KafkaConfig with AvroSerializer {

  implicit val formats = DefaultFormats

  implicit val materializer = ActorMaterializer()

  import materializer._
  /**
    *Random select the partitions number from 0 to kafka.partitions value
    *The specified partition number will be inside the Key.
    */
  private def keyAndPartition = scala.util.Random.nextInt(partitions) + "_" + UUID.randomUUID().toString

  private val consumerSettings = ConsumerSettings(system, new StringDeserializer, new StringDeserializer)
    .withBootstrapServers(bootstrapServers)
    .withGroupId(groupId)
    .withClientId(clientId)
    .withMaxWakeups(maxWakeups)
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetResetConfig)

  private val consumer: ((String, Int) => Source[ConsumerRecord[String, String], Consumer.Control]) = { (topic, partition) =>
    val assignment = Subscriptions.assignmentWithOffset(new TopicPartition(topic, partition), 0)
    Consumer.plainSource(consumerSettings, assignment)
      .completionTimeout(completionTimeout)
  }

  private val producerSettings = ProducerSettings(system, new StringSerializer, new StringSerializer)
    .withBootstrapServers(bootstrapServers)
    .withProperty("batch.size", "0")
    .withParallelism(3)
  //.withProperty("auto.create.topics.enable", "true")

  private val producer = producerSettings
    .createKafkaProducer()

  /**
    * communication with Kafka, send and receive message.
    * This method will send message to Kafka, using the specified key and partition for each topic
    * And get the message from the specified partition and filter by key
    */
  private val sendRequestAndGetResponseFromKafka: ((TopicPair, String, String) => Future[String]) = { (topic, key, value) =>
    //When we send RequestTopic message, contain the partition in it, and when we get the ResponseTopic according to the partition.
    val specifiedPartition = key.split("_")(0).toInt 
    val requestTopic = topic.request
    val responseTopic = topic.response
    //producer will publish the message to broker
    val message = new ProducerRecord[String, String](requestTopic, specifiedPartition, key, value)
    producer.send(message)
    
    //consumer will wait for the message from broker
    consumer(responseTopic, specifiedPartition)
      .filter(_.key() == key) // double check the key 
      .map { msg => 
        logger.debug(s"sendRequestAndGetResponseFromKafka ~~$topic with $msg")
        msg.value
      }
      // .throttle(1, FiniteDuration(10, MILLISECONDS), 1, Shaping)
      .runWith(Sink.head)
  }

  private val stringToJValueF: (String => Future[JsonAST.JValue]) = { r =>
    logger.debug("kafka-response-stringToJValueF:" + r)
    Future(json.parse(r) \\ "data")
  }

  val extractJValueToAnyF: (JsonAST.JValue => Future[Any]) = { r =>
    logger.debug("kafka-response-extractJValueToAnyF:" + r)
    Future(extractResult(r))
  }

  val anyToJValueF: (Any => Future[json.JValue]) = { m =>
    logger.debug("kafka-response-anyToJValueF:" + m)
    Future(Extraction.decompose(m))
  }

  val serializeF: (json.JValue => Future[String]) = { m =>
    logger.debug("kafka-response-serializeF:" + m)
    Future(json.compactRender(m))
  }

  //private val RESP: String = "{\"count\": \"\", \"data\": [], \"state\": \"\", \"pager\": \"\", \"target\": \"banks\"}"

  override def preStart(): Unit = {
    super.preStart()
    val conn = {

      val c = Props.get("connector").openOr("Jun2017")
      if (c.contains("_")) c.split("_")(1) else c
    }
    //configuration optimization is postponed
    //self ? conn
  }
  
  /**
    * Check the Future, if there are Exceptions, recover the Exceptions to specific JValue 
    * @param sender the sender who send the message to the Actor
    * @param future the future need to be checked 
    *               
    * @return If there is no exception, pipeTo sender
    *         If there is exception, recover to JValue to sender 
    */
  def pipeToSender(sender: ActorRef, future: Future[Any]) = future recover {
    case e: InterruptedException => json.parse(s"""{"error":"sending message to kafka interrupted"}""")
      logger.error(s"""{"error":"sending message to kafka interrupted,"${e}"}""")
      throw new RuntimeException("Kafka_InterruptedException"+e.toString)
    case e: ExecutionException => json.parse(s"""{"error":"could not send message to kafka"}""")
      logger.error(s"""{"error":"could not send message to kafka, "${e}"}""")
      throw new RuntimeException("Kafka_ExecutionException"+e.toString)
    case e: TimeoutException => json.parse(s"""{"error":"receiving message from kafka timed out"}""")
      logger.error(s"""{"error":"receiving message from kafka timed out", "${e}" "}""")
      throw new RuntimeException("Kafka_TimeoutException"+e.toString)
    case e: Throwable => json.parse(s"""{"error":"unexpected error sending message to kafka"}""")
      logger.error(s"""{"error":"unexpected error sending message to kafka , "${e}"}""")
      throw new RuntimeException("Kafka_Throwable"+e.toString)
  } pipeTo sender

  def receive = {
    case value: String =>
      logger.debug("kafka_request[value]: " + value)
      for {
        t <- Future(Topics.topicPairHardCode) // Just have two Topics: obp.request.version and obp.response.version
        r <- sendRequestAndGetResponseFromKafka(t, keyAndPartition, value)
        jv <- stringToJValueF(r)
        any <- extractJValueToAnyF(jv)
      } yield {
        logger.debug("South Side recognises version info")
        any
      }

    // This is for KafkaMappedConnector_vJun2017, the request is TopicCaseClass  
    case request: TopicCaseClass =>
      logger.debug("kafka_request[TopicCaseClass]: " + request)
      val f = for {
        t <- Future(Topics.createTopicByClassName(request.getClass.getSimpleName))
        d <- anyToJValueF(request)
        s <- serializeF(d)
        r <- sendRequestAndGetResponseFromKafka(t,keyAndPartition, s)
        jv <- stringToJValueF(r)
        any <- extractJValueToAnyF(jv)
      } yield {
        any
      }
      pipeToSender(sender, f)

    // This is for KafkaMappedConnector_JVMcompatible, KafkaMappedConnector_vMar2017 and KafkaMappedConnector, the request is Map[String, String]  
    case request: Map[String, String] =>
      logger.debug("kafka_request[Map[String, String]]: " + request)
      val orgSender = sender
      val f = for {
        t <- Future(Topics.topicPairFromProps) // Just have two Topics: Request and Response
        d <- anyToJValueF(request)
        v <- serializeF(d)
        r <- sendRequestAndGetResponseFromKafka(t, keyAndPartition, v)
        jv <- stringToJValueF(r)
        any <- extractJValueToAnyF(jv)
      } yield {
        any
      }
      pipeToSender(orgSender, f)
  }
}
/**
  * This case class design a pair of Topic, for both North and South side.
  * They are a pair
  * @param request  eg: obp.Jun2017.Request.GetBanks
  * @param response eg: obp.Jun2017.Response.GetBanks
  */
case class TopicPair(request: String, response: String)

object Topics {
  
  /**
    * Two topics:
    * Request : North is producer, South is the consumer. North --> South
    * Response: South is producer, North is the consumer. South --> North
    */
  private val requestTopic = Props.get("kafka.request_topic").openOrThrowException("no kafka.request_topic set")
  private val responseTopic = Props.get("kafka.response_topic").openOrThrowException("no kafka.response_topic set")
  
  /**
    * set in props, we have two topics: Request and Response
    */
  val topicPairFromProps = TopicPair(requestTopic, responseTopic)

  def topicPairHardCode = TopicPair("obp.Request.version", "obp.Response.version")

  def createTopicByClassName(className: String): TopicPair = {
    /**
      * get the connectorVersion from Props connector attribute
      * eg: in Props, connector=kafka_vJun2017
      *     -->
      *     connectorVersion = Jun2017
      */
    val connectorVersion = {
      val connectorNameFromProps = Props.get("connector").openOr("Jun2017")
      val c = if (connectorNameFromProps.contains("_")) connectorNameFromProps.split("_")(1) else connectorNameFromProps
      c.replaceFirst("v", "")
    }
  
    /**
      *  eg: 
      *  obp.Jun2017.N.GetBank
      *  obp.Jun2017.S.GetBank
      */
    TopicPair(s"obp.${connectorVersion}.N." + className.replace("$", ""),
      s"obp.${connectorVersion}.S." + className.replace("$", ""))
  }

}
