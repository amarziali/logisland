package com.hurence.logisland.engine

import java.io.ByteArrayInputStream
import java.util
import java.util.Collections
import java.util.regex.Pattern

import com.hurence.logisland.components.PropertyDescriptor
import com.hurence.logisland.event.Event
import com.hurence.logisland.log.{StandardParserContext, StandardParserInstance}
import com.hurence.logisland.processor.{AbstractEventProcessor, StandardProcessContext, StandardProcessorInstance}
import com.hurence.logisland.serializer.{EventAvroSerializer, EventKryoSerializer, EventSerializer}
import com.hurence.logisland.utils.kafka.KafkaSerializedEventProducer
import com.hurence.logisland.validators.StandardValidators
import kafka.admin.AdminUtils
import kafka.serializer.{DefaultDecoder, StringDecoder}
import kafka.utils.ZKStringSerializer
import org.I0Itec.zkclient.ZkClient
import org.apache.avro.Schema
import org.apache.spark.streaming.kafka.KafkaUtils
import org.apache.spark.streaming.{Milliseconds, StreamingContext}
import org.apache.spark.{SparkConf, SparkContext, TaskContext}
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._

/**
  * Created by tom on 05/07/16.
  */
class SparkStreamProcessingEngine extends AbstractStreamProcessingEngine {

    val SPARK_MASTER = new PropertyDescriptor.Builder()
        .name("spark.master")
        .description("The url to Spark Master")
        .required(true)
         // The regex allows "local[K]" with K as an integer,  "local[*]", "yarn", "yarn-client", "yarn-cluster" and "spark://HOST[:PORT]"
         // there is NO support for "mesos://HOST:PORT"
        .addValidator(StandardValidators.createRegexMatchingValidator(Pattern.compile("^(yarn(-(client|cluster))?|local\\[[0-9\\*]+\\]|spark:\\/\\/([0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+|[a-z][a-z0-9\\.\\-]+)(:[0-9]+)?)$")))
        .defaultValue("local[2]")
        .build


    val SPARK_YARN_DEPLOYMODE = new PropertyDescriptor.Builder()
        .name("spark.yarn.deploy-mode")
        .description("The yarn deploy mode")
        .required(false)
        .allowableValues("client", "cluster")
        .build

    val SPARK_YARN_QUEUE = new PropertyDescriptor.Builder()
        .name("spark.yarn.queue")
        .description("The name of the YARN queue")
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build

    val SPARK_APP_NAME = new PropertyDescriptor.Builder()
        .name("spark.appName")
        .description("Tha application name")
        .required(true)
        .addValidator(StandardValidators.createRegexMatchingValidator(Pattern.compile("^[a-zA-z0-9-_\\.]+$")))
        .defaultValue("log-island")
        .build

    val memorySizePattern = Pattern.compile("^[0-9]+[mMgG]$");
    val SPARK_DRIVER_MEMORY = new PropertyDescriptor.Builder()
        .name("spark.driver.memory")
        .description("The memory size for Spark driver")
        .required(false)
        .addValidator(StandardValidators.createRegexMatchingValidator(memorySizePattern))
        .build

    val SPARK_EXECUTOR_MEMORY = new PropertyDescriptor.Builder()
        .name("spark.executor.memory")
        .description("The memory size for Spark executors")
        .required(false)
        .addValidator(StandardValidators.createRegexMatchingValidator(memorySizePattern))
        .build

     val SPARK_DRIVER_CORES = new PropertyDescriptor.Builder()
        .name("spark.driver.cores")
        .description("The number of cores for Spark driver")
        .required(false)
        .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
        .build

     val SPARK_EXECUTOR_CORES = new PropertyDescriptor.Builder()
        .name("spark.executor.cores")
        .description("The number of cores for Spark driver")
        .required(false)
        .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
        .build

    val SPARK_SERIALIZER = new PropertyDescriptor.Builder()
      .name("spark.serializer")
      .description("Class to use for serializing objects that will be sent over the network or need to be cached in serialized form")
      .required(false)
      .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
      .defaultValue("org.apache.spark.serializer.KryoSerializer")
      .build

    val SPARK_STREAMING_BLOCK_INTERVAL = new PropertyDescriptor.Builder()
        .name("spark.streaming.blockInterval")
        .description("The block interval")
        .required(true)
        .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
        .defaultValue("350")
        .build

    val SPARK_STREAMING_KAFKA_MAX_RATE_PER_PARTITION = new PropertyDescriptor.Builder()
        .name("spark.streaming.kafka.maxRatePerPartition")
        .description("")
        .required(true)
        .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
        .defaultValue("1")
        .build

    val SPARK_STREAMING_BATCH_DURATION = new PropertyDescriptor.Builder()
        .name("spark.streaming.batchDuration")
        .description("")
        .required(true)
        .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
        .defaultValue("200")
        .build

    val KAFKA_METADATA_BROKER_LIST = new PropertyDescriptor.Builder()
        .name("kafka.metadata.broker.list")
        .description("")
        .required(true)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .defaultValue("sandbox:9092")
        .build

    val KAFKA_ZOOKEEPER_QUORUM = new PropertyDescriptor.Builder()
        .name("kafka.zookeeper.quorum")
        .description("")
        .required(true)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .defaultValue("sandbox:2181")
        .build

    val SPARK_STREAMING_BACKPRESSURE_ENABLED = new PropertyDescriptor.Builder()
        .name("spark.streaming.backpressure.enabled")
        .description("")
        .required(false)
        .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
        .defaultValue("true")
        .build

    val SPARK_STREAMING_UNPERSIST = new PropertyDescriptor.Builder()
        .name("spark.streaming.unpersist")
        .description("")
        .required(false)
        .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
        .defaultValue("false")
        .build

    val SPARK_UI_PORT = new PropertyDescriptor.Builder()
        .name("spark.ui.port")
        .description("")
        .required(false)
        .addValidator(StandardValidators.PORT_VALIDATOR)
        .defaultValue("4050")
        .build


    val SPARK_STREAMING_TIMEOUT = new PropertyDescriptor.Builder()
        .name("spark.streaming.timeout")
        .description("")
        .required(false)
        .addValidator(StandardValidators.INTEGER_VALIDATOR)
        .defaultValue("-1")
        .build


    val INPUT_EVENT_SERIALIZER = new PropertyDescriptor.Builder()
        .name("input.event.serializer")
        .description("")
        .required(false)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .defaultValue("com.hurence.logisland.serializer.EventAvroSerializer")
        .build

    val OUTPUT_EVENT_SERIALIZER = new PropertyDescriptor.Builder()
        .name("output.event.serializer")
        .description("")
        .required(false)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .defaultValue("com.hurence.logisland.serializer.EventAvroSerializer")
        .build

    override def getSupportedPropertyDescriptors: util.List[PropertyDescriptor] = {
        val descriptors: util.List[PropertyDescriptor] = new util.ArrayList[PropertyDescriptor]
        descriptors.add(SPARK_MASTER)
        descriptors.add(SPARK_YARN_DEPLOYMODE)
        descriptors.add(SPARK_YARN_QUEUE)
        descriptors.add(SPARK_DRIVER_MEMORY)
        descriptors.add(SPARK_EXECUTOR_MEMORY)
        descriptors.add(SPARK_DRIVER_CORES)
        descriptors.add(SPARK_EXECUTOR_CORES)
        descriptors.add(SPARK_SERIALIZER)
        descriptors.add(SPARK_STREAMING_BLOCK_INTERVAL)
        descriptors.add(SPARK_STREAMING_KAFKA_MAX_RATE_PER_PARTITION)
        descriptors.add(SPARK_APP_NAME)
        descriptors.add(SPARK_STREAMING_BATCH_DURATION)
        descriptors.add(KAFKA_METADATA_BROKER_LIST)
        descriptors.add(KAFKA_ZOOKEEPER_QUORUM)
        descriptors.add(SPARK_STREAMING_BACKPRESSURE_ENABLED)
        descriptors.add(SPARK_STREAMING_UNPERSIST)
        descriptors.add(SPARK_UI_PORT)
        descriptors.add(SPARK_STREAMING_TIMEOUT)
        descriptors.add(INPUT_EVENT_SERIALIZER)
        descriptors.add(OUTPUT_EVENT_SERIALIZER)

        Collections.unmodifiableList(descriptors)
    }


    private val logger = LoggerFactory.getLogger(classOf[SparkStreamProcessingEngine])

    override def start(engineContext: EngineContext, processorInstances: util.List[StandardProcessorInstance], parserInstances: util.List[StandardParserInstance]) = {

        logger.info("starting Spark Engine")
        val sparkMaster = engineContext.getProperty(SPARK_MASTER).getValue
        //val sparkYarnDeploymode = engineContext.getProperty(SPARK_YARN_DEPLOYMODE).getValue
        val sparkDriverMemory = engineContext.getProperty(SPARK_DRIVER_MEMORY).getValue
        val sparkExecutorMemory = engineContext.getProperty(SPARK_EXECUTOR_MEMORY).getValue
        val sparkDriverCores = engineContext.getProperty(SPARK_DRIVER_CORES).getValue
        val sparkExecutorCores = engineContext.getProperty(SPARK_EXECUTOR_CORES).getValue
        val sparkSerializer = engineContext.getProperty(SPARK_SERIALIZER).getValue

        val maxRatePerPartition = engineContext.getProperty(SPARK_STREAMING_KAFKA_MAX_RATE_PER_PARTITION).getValue
        val appName = engineContext.getProperty(SPARK_APP_NAME).getValue
        val blockInterval = engineContext.getProperty(SPARK_STREAMING_BLOCK_INTERVAL).getValue
        val batchDuration = engineContext.getProperty(SPARK_STREAMING_BATCH_DURATION).asInteger().intValue()
        val brokerList = engineContext.getProperty(KAFKA_METADATA_BROKER_LIST).getValue
        val zkQuorum = engineContext.getProperty(KAFKA_ZOOKEEPER_QUORUM).getValue
        val backPressureEnabled = engineContext.getProperty(SPARK_STREAMING_BACKPRESSURE_ENABLED).getValue
        val streamingUnpersist = engineContext.getProperty(SPARK_STREAMING_UNPERSIST).getValue
        val timeout = engineContext.getProperty(SPARK_STREAMING_TIMEOUT).asInteger().intValue()

        val inSerializerClass = engineContext.getProperty(INPUT_EVENT_SERIALIZER).getValue
        val outSerializerClass = engineContext.getProperty(OUTPUT_EVENT_SERIALIZER).getValue

        /**
          * job configuration
          */
        val conf = new SparkConf()
        conf.set("spark.serializer", sparkSerializer)
        conf.set("spark.streaming.kafka.maxRatePerPartition", maxRatePerPartition)
        conf.set("spark.streaming.blockInterval", blockInterval)
        conf.set("spark.streaming.backpressure.enabled", backPressureEnabled)
        conf.set("spark.streaming.unpersist", streamingUnpersist)
        conf.set("spark.ui.port", "4050")
        conf.setAppName(appName)
        conf.setMaster(sparkMaster)



        if (sparkMaster startsWith "yarn") {
            // Note that SPARK_YARN_DEPLOYMODE is not used by spark itself but only by spark-submit CLI
            // That's why we do not need to propagate it here
            if (engineContext.getProperty(SPARK_YARN_QUEUE).isSet) {
                conf.set("spark.yarn.queue", engineContext.getProperty(SPARK_YARN_QUEUE).getValue)
            }
        }

        // Need to check if the properties are set because those properties are not "requires"
        if (engineContext.getProperty(SPARK_DRIVER_MEMORY).isSet) {
            conf.set("spark.driver.memory", engineContext.getProperty(SPARK_DRIVER_MEMORY).getValue)
        }
        if (engineContext.getProperty(SPARK_EXECUTOR_MEMORY).isSet) {
            conf.set("spark.executor.memory", engineContext.getProperty(SPARK_EXECUTOR_MEMORY).getValue)
        }
        if (engineContext.getProperty(SPARK_DRIVER_CORES).isSet) {
            conf.set("spark.driver.cores", engineContext.getProperty(SPARK_DRIVER_CORES).getValue)
        }
        if (engineContext.getProperty(SPARK_EXECUTOR_CORES).isSet) {
            conf.set("spark.executor.cores", engineContext.getProperty(SPARK_EXECUTOR_CORES).getValue)
        }


        @transient val sc = new SparkContext(conf)
        @transient val ssc = new StreamingContext(sc, Milliseconds(batchDuration))
        logger.info(s"spark context initialized with master:$sparkMaster, " +
            s"appName:$appName, " +
            s"blockInterval:$blockInterval, " +
            s"maxRatePerPartition:$maxRatePerPartition")


        /**
          * loop over processContext
          */
        parserInstances.toList.foreach(parserInstance => {
            val parseContext = new StandardParserContext(parserInstance)
            parserInstance.getParser.init(parseContext)


            // Define the Kafka parameters, broker list must be specified
            val kafkaParams = Map("metadata.broker.list" -> brokerList, "group.id" -> parseContext.getName)
            val zkClient = new ZkClient(zkQuorum, 3000, 3000, ZKStringSerializer)
            logger.debug("batchDuration: " + batchDuration)
            logger.debug("blockInterval: " + blockInterval)
            logger.debug("maxRatePerPartition: " + maxRatePerPartition)
            logger.debug("brokerList: " + brokerList)

            // create topics if needed
            val inputTopics = parseContext.getProperty(AbstractEventProcessor.INPUT_TOPICS).getValue.split(",").toSet
            val outputTopics = parseContext.getProperty(AbstractEventProcessor.OUTPUT_TOPICS).getValue.split(",").toSet
            val errorTopics = parseContext.getProperty(AbstractEventProcessor.ERROR_TOPICS).getValue.split(",").toSet

            createTopicsIfNeeded(zkClient, inputTopics)
            createTopicsIfNeeded(zkClient, outputTopics)
            createTopicsIfNeeded(zkClient, errorTopics)

            // Create the direct stream with the Kafka parameters and topics
            val kafkaStream = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder](
                ssc,
                kafkaParams,
                inputTopics
            )



            // setup the stream processing
            kafkaStream.foreachRDD(rdd => {

                rdd.foreachPartition(partition => {
                    val partitionId = TaskContext.get.partitionId()
                    // use this uniqueId to transactionally commit the data in partitionIterator

                    logger.debug("---------------------------------")
                    logger.debug(s"parseContext ${parseContext.getName}")
                    logger.debug(s"parsing topic $inputTopics => $outputTopics for spark partition $partitionId")

                    // convert partition to events
                    val parser = new Schema.Parser



                    val outgoingEvents = partition.map(rawEvent => {
                        val events = parserInstance.getParser.parse(parseContext, rawEvent._1, rawEvent._2)
                        if (events.nonEmpty)
                            events.head
                        else
                            new Event("void")
                    }).toList
                    //  logger.debug(s" ${incomingEvents.size} incomming log lines")

                    logger.debug(s" ${outgoingEvents.size} outgoing events")



                    val serializer = outSerializerClass match {
                        case "com.hurence.logisland.serializer.EventAvroSerializer" =>
                            val parser = new Schema.Parser
                            val outSchemaContent = parseContext.getProperty(AbstractEventProcessor.OUTPUT_SCHEMA).getValue
                            val outSchema = parser.parse(outSchemaContent)
                            new EventAvroSerializer(outSchema)
                        case _ =>
                            new EventKryoSerializer(true)
                    }


                    val kafkaProducer = new KafkaSerializedEventProducer(
                        brokerList,
                        parseContext.getProperty(AbstractEventProcessor.OUTPUT_TOPICS).getValue,
                        serializer)
                    kafkaProducer.produce(outgoingEvents.toList)
                })
            })
        })


        /**
          * loop over processContext
          */
        processorInstances.toList.foreach(processorInstance => {
            val processorContext = new StandardProcessContext(processorInstance)
            processorInstance.getProcessor.init(processorContext)

            // Define the Kafka parameters, broker list must be specified
            val kafkaParams = Map("metadata.broker.list" -> brokerList, "group.id" -> processorContext.getName)
            val zkClient = new ZkClient(zkQuorum, 3000, 3000, ZKStringSerializer)
            logger.debug("batchDuration: " + batchDuration)
            logger.debug("blockInterval: " + blockInterval)
            logger.debug("maxRatePerPartition: " + maxRatePerPartition)
            logger.debug("brokerList: " + brokerList)

            // create topics if needed
            val inputTopics = processorContext.getProperty(AbstractEventProcessor.INPUT_TOPICS).getValue.split(",").toSet
            val outputTopics = processorContext.getProperty(AbstractEventProcessor.OUTPUT_TOPICS).getValue.split(",").toSet
            val errorTopics = processorContext.getProperty(AbstractEventProcessor.ERROR_TOPICS).getValue.split(",").toSet

            createTopicsIfNeeded(zkClient, inputTopics)
            createTopicsIfNeeded(zkClient, outputTopics)
            createTopicsIfNeeded(zkClient, errorTopics)

            // Create the direct stream with the Kafka parameters and topics
            val kafkaStream = KafkaUtils.createDirectStream[Array[Byte], Array[Byte], DefaultDecoder, DefaultDecoder](
                ssc,
                kafkaParams,
                inputTopics
            )

            // setup the stream processing
            kafkaStream.foreachRDD(rdd => {

                rdd.foreachPartition(partition => {
                    val partitionId = TaskContext.get.partitionId()
                    // use this uniqueId to transactionally commit the data in partitionIterator

                    logger.debug("---------------------------------")
                    logger.debug(s"processor ${processorContext.getName}")
                    logger.debug(s"processing topic $inputTopics => $outputTopics for spark partition $partitionId")

                    // convert partition to events
                    val parser = new Schema.Parser

                    val deserializer = inSerializerClass match {
                        case "com.hurence.logisland.serializer.EventAvroSerializer" =>
                            val inSchemaContent = processorContext.getProperty(AbstractEventProcessor.INPUT_SCHEMA).getValue
                            val inSchema = parser.parse(inSchemaContent)
                            new EventAvroSerializer(inSchema)
                        case _ =>
                            new EventKryoSerializer(true)
                    }


                    val incomingEvents = deserializeEvents(partition, deserializer)
                    logger.debug(s" ${incomingEvents.size} incomming events")
                    val outgoingEvents = processorInstance.getProcessor.process(processorContext, incomingEvents)
                    logger.debug(s" ${outgoingEvents.size} outgoing events")



                    val serializer = outSerializerClass match {
                        case "com.hurence.logisland.serializer.EventAvroSerializer" =>
                            val outSchemaContent = processorContext.getProperty(AbstractEventProcessor.OUTPUT_SCHEMA).getValue
                            val outSchema = parser.parse(outSchemaContent)
                            new EventAvroSerializer(outSchema)
                        case _ =>
                            new EventKryoSerializer(true)
                    }


                    val kafkaProducer = new KafkaSerializedEventProducer(
                        brokerList,
                        processorContext.getProperty(AbstractEventProcessor.OUTPUT_TOPICS).getValue,
                        serializer)
                    kafkaProducer.produce(outgoingEvents.toList)
                })
            })
        })


        // Start the computation
        ssc.start()

        if (timeout != -1)
            ssc.awaitTerminationOrTimeout(timeout)
        else
            ssc.awaitTermination()
    }

    def createTopicsIfNeeded(zkClient: ZkClient, inputTopics: Set[String]): Unit = {
        inputTopics.foreach(topic => {
            if (!AdminUtils.topicExists(zkClient, topic)) {
                AdminUtils.createTopic(zkClient, topic, 1, 1)
                Thread.sleep(1000)
                logger.info(s"created topic $topic with replication 1 and partition 1 => should be changed in production")
            }
        })
    }

    def deserializeEvents(partition: Iterator[(Array[Byte], Array[Byte])], serializer: EventSerializer): List[Event] = {
        partition.map(rawEvent => {
            val bais = new ByteArrayInputStream(rawEvent._2)
            val deserialized = serializer.deserialize(bais)
            bais.close()

            deserialized
        }).toList
    }

    override def shutdown(context: EngineContext) = {
    }

    override def onPropertyModified(descriptor: PropertyDescriptor, oldValue: String, newValue: String) = {
        logger.info(s"property ${descriptor.getName} value changed from $oldValue to $newValue")
    }

    override def getIdentifier: String = {
        "none"
    }

}
