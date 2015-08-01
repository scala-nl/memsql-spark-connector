package com.memsql.spark.connector

import java.sql.{Connection, DriverManager, PreparedStatement}

import org.apache.spark.{Logging, SparkException}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import org.apache.spark.sql.DataFrame

import com.memsql.spark.connector.rdd.MemSQLRDD
import com.memsql.spark.connector.dataframe.MemSQLDataFrameUtils
import com.memsql.spark.connector.dataframe.MemSQLDataFrame

import scala.reflect.ClassTag
import org.apache.spark.{SparkException, Logging}
import com.memsql.spark.context.MemSQLSparkContext

abstract class MemSQLKey
case class Shard(columns: String) extends MemSQLKey
case class Key(columns: String) extends MemSQLKey
case class KeyUsingClusteredColumnStore(columns: String) extends MemSQLKey
case class PrimaryKey(columns: String) extends MemSQLKey
case class UniqueKey(columns: String) extends MemSQLKey

class DataFrameFunctions(df: DataFrame) extends Serializable with Logging 
{
    /*
     * Saves a Spark dataframe to a memsql table with the same column names.
     * If dbHost, dbPort, user and password are not specified,
     * the MemSQLSparkContext will determine where each partition's data is sent.
     * Otherwise, all partitions will load into the node specified by MemSQLSparkContext
     */
    def saveToMemSQL(dbName: String,
                     tableName: String,
                     dbHost: String = null,
                     dbPort: Int = -1,
                     user: String = null,
                     password: String = null,                     
                     onDuplicateKeySql: String = "",
                     useInsertIgnore: Boolean = false,
                     upsertBatchSize: Int = 10000,
                     useKeylessShardedOptimization: Boolean = false)
  {
        val insertTable = new StringBuilder()
        insertTable.append(tableName).append("(")
        var first = true
        for (col <- df.schema)
        {
            if (!first)
            {
                insertTable.append(", ")
            }
            first = false;
            insertTable.append(col.name)
        }
        val insertTableString = insertTable.append(")").toString
        df.rdd.saveToMemSQL(dbName, insertTableString, dbHost, dbPort, user, password,  onDuplicateKeySql, useInsertIgnore, upsertBatchSize, useKeylessShardedOptimization)
    }

    /*
     * Creates a MemSQL table with the schema matching the Spark dataframe and loads the data into it.
     * If dbHost, dbPort, user and password are not specified,
     * the MemSQLSparkContext will determine where each partition's data is sent.
     * Otherwise, all partitions will load into the node specified by MemSQLSparkContext
     */
    def createMemSQLTableAs(dbName: String,
                            tableName: String,
                            dbHost: String = null,
                            dbPort: Int = -1,
                            user: String = null,
                            password: String = null,
                            ifNotExists: Boolean = false,
                            keys: Array[MemSQLKey] = Array(),
                            useKeylessShardedOptimization: Boolean = false) : DataFrame = 
    {
        val resultDf = df.createMemSQLTableFromSchema(dbName, tableName, dbHost, dbPort, user, password, ifNotExists, keys)
        df.saveToMemSQL(dbName, tableName, dbHost, dbPort, user, password, useKeylessShardedOptimization=useKeylessShardedOptimization)
        return resultDf
   }

    def createMemSQLTableFromSchema(dbName: String,
                                    tableName: String,
                                    dbHost: String = null,
                                    dbPort: Int = -1,
                                    user: String = null,
                                    password: String = null,
                                    ifNotExists: Boolean = false,
                                    keys: Array[MemSQLKey] = Array()) : DataFrame = 
    {
        val sql = new StringBuilder()
        sql.append("CREATE ")
        sql.append("TABLE ")
        if (ifNotExists)
        {
            sql.append("IF NOT EXISTS ")
        }
        sql.append(tableName).append(" (")
        for (col <- df.schema)
        {
            sql.append(col.name).append(" ")
            sql.append(MemSQLDataFrameUtils.DataFrameTypeToMemSQLTypeString(col.dataType))

            if (col.nullable)
            {
                sql.append(" NULL DEFAULT NULL")
            }
            else
            {
                sql.append(" NOT NULL")
            }
            sql.append(",")
        }
        val hasShardKey = keys.exists(_ match 
        { 
            case shardKey: Shard => true 
            case pk: PrimaryKey => true
            case _ => false
        })
        val theKeys = if (hasShardKey) keys else keys :+ Shard("")
        for (i <- 0 until theKeys.size)
        {
            theKeys(i) match
            {
                case shardkey: Shard => 
                {
                    sql.append("SHARD(").append(shardkey.columns).append(")")
                }            
                case index: Key => 
                {
                    sql.append("INDEX(").append(index.columns).append(")")
                }                                
                case pk: PrimaryKey => 
                {
                    sql.append("PRIMARY KEY(").append(pk.columns).append(")")
                }                                
                case uk: UniqueKey => 
                {
                    sql.append("UNIQUE KEY(").append(uk.columns).append(")")
                }                                
                case projection: KeyUsingClusteredColumnStore => 
                {
                    sql.append("KEY(").append(projection.columns).append(") USING CLUSTERED COLUMNSTORE")
                }                                
            }      
            if (i != theKeys.size - 1)
            {
                sql.append(",")
            }
        }
        sql.append(")")

        var theHost: String = dbHost
        var thePort: Int = dbPort
        var theUser: String = user
        var thePassword: String = password
        if (dbHost == null || dbPort == -1 || user == null || password == null) 
        {
            df.rdd.sparkContext match 
            {
                case _: MemSQLSparkContext => 
                {
                    var msc = df.rdd.sparkContext.asInstanceOf[MemSQLSparkContext]
                    theHost = msc.GetMemSQLMasterAggregator._1 // note: it's fine for this to be the MA, since its gaurenteed to be a fully pushed down query.  
                    thePort = msc.GetMemSQLMasterAggregator._2
                    theUser = msc.GetUserName
                    thePassword = msc.GetPassword
                }
                case _ => 
                {
                    throw new SparkException("saveToMemSQL requires intializing Spark with MemSQLSparkContext or explicitly setting dbName, dbHost, user and password")
                }
            }
        }
        val conn = MemSQLRDD.getConnection(theHost, thePort, theUser, thePassword, dbName)
        val stmt = conn.createStatement
        stmt.executeUpdate(sql.toString) // TODO: should I be handling errors, or just expect the caller to catch them...
        stmt.close()
        return MemSQLDataFrame.MakeMemSQLDF(df.sqlContext, theHost, thePort, theUser, thePassword, dbName, "SELECT * FROM " + tableName)
    }
}

