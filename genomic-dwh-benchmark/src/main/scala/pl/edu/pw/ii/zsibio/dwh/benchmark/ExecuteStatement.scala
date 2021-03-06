package pl.edu.pw.ii.zsibio.dwh.benchmark

import java.io.File
import java.nio.file.Path

import org.rogach.scallop.ScallopConf
import pl.edu.pw.ii.zsibio.dwh.benchmark.dao.ConnectDriver.Driver
import pl.edu.pw.ii.zsibio.dwh.benchmark.dao.{EngineConnection, ConnectDriver}
import pl.edu.pw.ii.zsibio.dwh.benchmark.utils.{DdlParser, KuduUtils, QueryExecutorWithLogging}
import com.typesafe.config.{Config, ConfigFactory}

import scala.collection.mutable.ArrayBuffer
import scala.util.matching.Regex

/**
  * Created by marek on 14.01.17.
  */
object ExecuteStatement {

  class RunConf(args:Array[String]) extends ScallopConf(args){

    banner("Usage: ...")

    val dbName = opt[String]("dbName",required = true, descr = "Database name in HiveMetastore" )
    val useHive = opt[Boolean]("useHive",required = false, descr = "Run in Hive ")
    val storageType = opt[String]("storageType",required = true, descr = "Storage type parquet|orc|kudu|carbon")
    val useImpala = opt[Boolean]("useImpala",required = false, descr = "Create tables in Kudu" )
    val usePresto = opt[Boolean]("usePresto",required = false, descr = "Run in Presto" )
    val useSpark1 = opt[Boolean]("useSpark1",required = false, descr = "Run in Spark 1.x" )
    val useSpark2 = opt[Boolean]("useSpark2",required = false, descr = "Run in Spark 2.x" )

    //val connString =opt[String]("connString",required = false, descr = "Connection string for SparlSQL Server" )
    //val kuduMaster =opt[String]("kuduMaster",required = false, descr = "Kudu Master URL" )
    //val compression =opt[String]("compression",required = false, default= Some("gzip"), descr = "Compression algorithm gzip|snappy|none" )
    //val username =opt[String]("username",required = false, descr = "Username" )
    //val password =opt[String]("password",required = false, descr = "Password" )
    val queryDir =opt[String]("queryDir",required = true, descr = "A file containing a select statement in YAML format" )
    val logFile =opt[String]("logFile",required = false, descr = "A file for storing timing results", default = Some("results.csv") )
    val partNum =opt[Int]("partNum",required = true, descr = "Number of partitions",default = Some(100) )
    val dryRun = opt[Boolean]("dryRun",required = false, descr = "Create tables in Kudu", default = Some(false) )
    verify()
  }

  def main(args: Array[String]) {

    val runConf = new RunConf(args)
    val confFile = ConfigFactory.load()
    val prestoConnString = confFile.getString("jdbc.presto.connection")
    val hiveConnString = confFile.getString("jdbc.hive.connection")
    val impalaConnString = confFile.getString("jdbc.impala.connection")
    val impalaThriftString = confFile.getString("impala.thrift.server")
    val spark1ConnString = confFile.getString("jdbc.spark1.connection")
    val spark2ConnString = confFile.getString("jdbc.spark2.connection")
    val kuduMaster = confFile.getString("kudu.master.server")


    val userName = confFile.getString("jdbc.username")
    val password = confFile.getString("jdbc.password")

    val jdbcConfArray = new ArrayBuffer[(Driver, String)]()

    if( runConf.useHive() && !hiveConnString.isEmpty)
      jdbcConfArray.append((ConnectDriver.HIVE,hiveConnString ) )
    else if (runConf.useHive() && hiveConnString.isEmpty)
      throw new Exception("Hive to be used but Hive jdbc is missing in the conf file")

    if( runConf.useSpark1() && !spark1ConnString.isEmpty)
      jdbcConfArray.append((ConnectDriver.HIVE,spark1ConnString ) )
    else if (runConf.useSpark1() && spark1ConnString.isEmpty)
      throw new Exception("Spark 1.x to be used but Spark 1.x jdbc is missing in the conf file")

    if( runConf.useSpark2() && !spark2ConnString.isEmpty)
      jdbcConfArray.append((ConnectDriver.HIVE,spark2ConnString ) )
    else if (runConf.useSpark2() && spark2ConnString.isEmpty)
      throw new Exception("Spark 2.x to be used but Spark 2.x jdbc is missing in the conf file")

    if (runConf.usePresto() && !prestoConnString.isEmpty)
      jdbcConfArray.append((ConnectDriver.PRESTO,prestoConnString ))
    else if (runConf.usePresto() && prestoConnString.isEmpty)
      throw new Exception("Hive to be used but Hive jdbc is missing in the conf file")

    if (runConf.useImpala() && !impalaConnString.isEmpty  &&
      ( (runConf.storageType().toLowerCase =="kudu" && !kuduMaster.isEmpty) || (runConf.storageType().toLowerCase() == "parquet") ) )
      jdbcConfArray.append((ConnectDriver.IMPALA_JDBC,impalaConnString ) )
    else if (runConf.useImpala() && (impalaConnString.isEmpty  || kuduMaster.isEmpty) )
      throw new Exception("Kudu to be used but Impala jdbc or kuduMaster is missing in the conf file")

    jdbcConfArray.map( jobConf => {
        run(runConf, confFile, jobConf, kuduMaster, userName, password)

      }
     )
    }


  def run(runConf:RunConf, confFile:Config, jobConf:(Driver,String), kuduMaster:String, userName:String, password:String)={
    val conn = new EngineConnection(jobConf._1)
    conn.open(jobConf._1,jobConf._2, userName, password)
    val allFiles = getRecursListFiles(new File(runConf.queryDir()))
        .filter(f => f.getName.endsWith("yaml"))
        .sortBy(f => f.getName)
    allFiles.map {queryFile =>
      val query = QueryExecutorWithLogging
            .parseQueryYAML(queryFile.getAbsolutePath, runConf.storageType(), jobConf._2, kuduMaster,runConf.dbName(),runConf.dryRun())
            //.copy(queryEngine = jobConf._2.split(":")(1)) /*overrride query engine from cmd line*/
        .copy(queryEngine =  {
          if (runConf.useHive()) ConnectDriver.HIVE
          else if (runConf.useSpark1()) ConnectDriver.SPARK1
          else if (runConf.useSpark2()) ConnectDriver.SPARK2
          else if (runConf.useImpala()) ConnectDriver.IMPALA_JDBC
          else if (runConf.usePresto()) ConnectDriver.PRESTO
          else
            ConnectDriver.UKNOWN
        }.toString() )

        /*overrride query engine from cmd line*/
      if (query.queryType.toLowerCase() == "create" && !query.statement.toLowerCase().contains("create database")
        && query.storageFormat.toLowerCase() == "kudu") {

        val kuduUtils = new KuduUtils(kuduMaster)
        kuduUtils.createTable(query.statement, s"${runConf.storageType()}", false,
          confFile.getInt("kudu.table.partitions"), confFile.getInt("kudu.table.replication"))
       /* val connImpalaThrift = new EngineConnection(ConnectDriver.IMPALA_THRIFT)
        connImpalaThrift.open(jobConf._1,jobConf._2)
        QueryExecutorWithLogging.runStatement(query,connImpalaThrift, runConf.logFile())
        connImpalaThrift.close*/
      }

      QueryExecutorWithLogging.runStatement(query, conn, runConf.logFile(), runConf.dryRun())

    }
    conn.close
  }

  def getRecursListFiles(f: File): Array[File] = {
    val these = f.listFiles
    these ++ these.filter(_.isDirectory).flatMap(getRecursListFiles)
  }

}
