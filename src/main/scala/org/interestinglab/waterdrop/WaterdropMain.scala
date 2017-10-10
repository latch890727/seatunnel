package org.interestinglab.waterdrop

import scala.collection.JavaConversions._
import org.apache.spark.streaming._
import org.apache.spark.SparkConf
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.interestinglab.waterdrop.config.ConfigBuilder
import org.interestinglab.waterdrop.filter.UdfRegister

object WaterdropMain {

  def main(args: Array[String]) {

    val configBuilder = new ConfigBuilder
    val sparkConfig = configBuilder.getSparkConfigs
    val inputs = configBuilder.createInputs
    val outputs = configBuilder.createOutputs
    val filters = configBuilder.createFilters

    var configValid = true
    val plugins = inputs ::: filters ::: outputs
    for (p <- plugins) {
      val (isValid, msg) = p.checkConfig
      if (!isValid) {
        configValid = false
        printf("Plugin[%s] contains invalid config, error: %s\n", p.name, msg)
      }
    }

    if (!configValid) {
      System.exit(-1) // invalid configuration
    }

    println("[INFO] loading SparkConf: ")
    val sparkConf = createSparkConf(configBuilder)
    sparkConf.getAll.foreach(entry => {
      val (key, value) = entry
      println("\t" + key + " => " + value)
    })

    val duration = sparkConfig.getLong("spark.streaming.batchDuration")
    val ssc = new StreamingContext(sparkConf, Seconds(duration))
    val sparkSession = SparkSession.builder.config(ssc.sparkContext.getConf).getOrCreate()

    // find all user defined UDFs and register in application init
    UdfRegister.findAndRegisterUdfs(sparkSession)

    for (i <- inputs) {
      i.prepare(sparkSession, ssc)
    }

    for (o <- outputs) {
      o.prepare(sparkSession, ssc)
    }

    for (f <- filters) {
      f.prepare(sparkSession, ssc)
    }

    val dstreamList = inputs.map(p => {
      p.getDStream
    })

    val unionedDStream = dstreamList.reduce((d1, d2) => {
      d1.union(d2)
    })

    val dStream = unionedDStream.mapPartitions { partitions =>
      val strIterator = partitions.map(r => r._2)
      val strList = strIterator.toList
      strList.iterator
    }

    dStream.foreachRDD { strRDD =>
      val rowsRDD = strRDD.mapPartitions { partitions =>
        val row = partitions.map(Row(_))
        val rows = row.toList
        rows.iterator
      }

      val spark = SparkSession.builder.config(rowsRDD.sparkContext.getConf).getOrCreate()

      val schema = StructType(Array(StructField("raw_message", StringType)))
      var df = spark.createDataFrame(rowsRDD, schema)

      for (f <- filters) {
        df = f.process(spark, df)
      }

      inputs.foreach(p => {
        p.beforeOutput
      })

      outputs.foreach(p => {
        p.process(df)
      })

      inputs.foreach(p => {
        p.afterOutput
      })

    }

    ssc.start()
    ssc.awaitTermination()
  }

  private def createSparkConf(configBuilder: ConfigBuilder): SparkConf = {
    val sparkConf = new SparkConf()

    configBuilder.getSparkConfigs
      .entrySet()
      .foreach(entry => {
        sparkConf.set(entry.getKey, String.valueOf(entry.getValue.unwrapped()))
      })

    sparkConf
  }
}
