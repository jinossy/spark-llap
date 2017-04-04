/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive.llap

import java.sql.Connection
import java.util.regex.Pattern

import scala.reflect.runtime.{universe => ru}

import com.hortonworks.spark.sql.hive.llap.DefaultJDBCWrapper

import org.apache.spark.SparkContext
import org.apache.spark.sql.SQLConf.SQLConfEntry.{booleanConf, stringConf}
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.OverrideCatalog
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, Subquery}
import org.apache.spark.sql.execution.CacheManager
import org.apache.spark.sql.execution.datasources.{LogicalRelation, ResolvedDataSource}
import org.apache.spark.sql.execution.ui.SQLListener
import org.apache.spark.sql.hive.{HiveContext, HiveMetastoreCatalog, MetastoreRelation}
import org.apache.spark.sql.hive.client.{ClientInterface, ClientWrapper}

class LlapContext(sc: SparkContext,
    cacheManager: CacheManager,
    listener: SQLListener,
    @transient private val execHive: ClientWrapper,
    @transient private val metaHive: ClientInterface,
    isRootContext: Boolean)
  extends HiveContext(sc, cacheManager, listener, execHive, metaHive, isRootContext) {
  override protected[sql] lazy val catalog =
    new LlapCatalog(metadataHive, this) with OverrideCatalog

  override def newSession(): LlapContext = {
    new LlapContext(
      sc = sc,
      cacheManager = cacheManager,
      listener = listener,
      execHive = executionHive.newSession(),
      metaHive = metadataHive.newSession(),
      isRootContext = false)
  }

  def this(sc: SparkContext) = {
    this(sc, new CacheManager, SQLContext.createListenerAndUI(sc), null, null, true)
  }

  def connection: Connection = {
    if (conn == null) {
      conn = DefaultJDBCWrapper.getConnector(None, getConnectionUrl(), getUserString())
    }
    conn
  }

  var conn: Connection = null

  def getConnectionUrl(): String = {
    var userString = getUserString()
    if (userString == null) {
      userString = ""
    }
    var urlString = LlapContext.getConnectionUrlFromConf(sc)
    urlString.replace("${user}", userString)
  }

  def getUserString(): String = {
    LlapContext.getUserMethod match {
      case null => null
      case _ =>
        val instanceMirror = ru.runtimeMirror(this.getClass.getClassLoader).reflect(this)
        val methodMirror = instanceMirror.reflectMethod(LlapContext.getUserMethod)
        val user = methodMirror().asInstanceOf[String]
        if (user == null) {
          LlapContext.getUser()
        } else {
          user
        }
    }
  }

  private def functionOrMacroDDLPattern(command: String) = Pattern.compile(
    ".*(create|drop)\\s+(temporary\\s+)?(function|macro).+", Pattern.DOTALL).matcher(command)

  override protected[hive] def runSqlHive(sql: String): Seq[String] = {
    val command = sql.trim.toLowerCase
    if (functionOrMacroDDLPattern(command).matches()) {
      executionHive.runSqlHive(sql)
    } else if (command.startsWith("set")) {
      metaHive.runSqlHive(sql)
      executionHive.runSqlHive(sql)
    } else if (command.startsWith("show")) {
      val rs = connection.createStatement().executeQuery(sql)
      val result = new scala.collection.mutable.ArrayBuffer[String]
      while (rs.next()) {
        result += rs.getString(1)
      }
      rs.close()
      result
    } else {
      connection.createStatement().executeUpdate(sql)
      Seq.empty
    }
  }
}

class LlapCatalog(override val client: ClientInterface, hive: LlapContext)
    extends HiveMetastoreCatalog(client, hive) {

  override def lookupRelation(
      tableIdentifier: TableIdentifier,
      alias: Option[String] = None): LogicalPlan = {
    // Use metastore catalog to lookup tables, then convert to our relations
    val relation = super.lookupRelation(tableIdentifier, alias)
    val relationSourceName = "org.apache.spark.sql.hive.llap"

    // Now convert to LlapRelation
    val logicalRelation = relation match {
      case MetastoreRelation(dbName, tabName, alias) =>
        val qualifiedName = dbName + "." + tabName
        var options = Map("table" -> qualifiedName, "url" -> hive.getConnectionUrl())
        val resolved = ResolvedDataSource(
        hive,
        None,
        Array[String](),
        relationSourceName,
        options)
        LogicalRelation(resolved.relation)
      case _ => throw new Exception("Expected MetastoreRelation")
    }

    val tableWithQualifiers = Subquery(tableIdentifier.table, logicalRelation)
    alias.map(a => Subquery(a, tableWithQualifiers)).getOrElse(tableWithQualifiers)
  }
}

object LlapContext {
  val HIVESERVER2_JDBC_URL = stringConf(
    key = "spark.sql.hive.hiveserver2.jdbc.url",
    defaultValue = None,
    doc = "HiveServer2 JDBC URL.")

  val HIVESERVER2_JDBC_URL_PRINCIPAL = stringConf(
    key = "spark.sql.hive.hiveserver2.jdbc.url.principal",
    defaultValue = None,
    doc = "HiveServer2 JDBC Principal.")

  val HIVESERVER2_CREDENTIAL_ENABLED = booleanConf(
    key = "spark.yarn.security.credentials.hiveserver2.enabled",
    defaultValue = Some(false),
    doc = "When true, HiveServer2 credential provider is enabled.")

  /**
   * For the given HiveServer2 JDBC URLs, attach the postfix strings if needed.
   *
   * For kerberized clusters,
   *
   * 1. YARN cluster mode: ";auth=delegationToken"
   * 2. YARN client mode: ";principal=hive/_HOST@EXAMPLE.COM"
   *
   * Non-kerberied clusters,
   * 3. Use the given URLs.
   */
  def getConnectionUrlFromConf(sparkContext: SparkContext): String = {
    if (!sparkContext.conf.contains(HIVESERVER2_JDBC_URL.key)) {
      throw new Exception("Spark conf does not contain config " + HIVESERVER2_JDBC_URL.key)
    }

    if (sparkContext.conf.getBoolean(HIVESERVER2_CREDENTIAL_ENABLED.key, false)) {
      // 1. YARN Cluster mode for kerberized clusters
      s"${sparkContext.conf.get(HIVESERVER2_JDBC_URL.key)};auth=delegationToken"
    } else if (sparkContext.conf.contains(HIVESERVER2_JDBC_URL_PRINCIPAL.key)) {
      // 2. YARN Client mode for kerberized clusters
      s"${sparkContext.conf.get(HIVESERVER2_JDBC_URL.key)};" +
        s"principal=${sparkContext.conf.get(HIVESERVER2_JDBC_URL_PRINCIPAL.key)}"
    } else {
      // 3. For non-kerberized cluster
      sparkContext.conf.get(HIVESERVER2_JDBC_URL.key)
    }
  }

  def getUser(): String = {
    System.getProperty("hive_user", System.getProperty("user.name"))
  }

  private[llap] val getUserMethod = findGetUserMethod()

  private def findGetUserMethod(): ru.MethodSymbol = {
    val symbol = ru.typeOf[HiveContext].declaration(ru.stringToTermName("getUser"))
    val methodSymbol = symbol match {
      case ru.NoSymbol => null
      case null => null
      case _ => symbol.asMethod
    }
    methodSymbol
  }
}

