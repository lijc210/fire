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

package com.zto.fire.jdbc

import java.sql.{Connection, PreparedStatement, ResultSet, SQLException, Statement}

import com.mchange.v2.c3p0.ComboPooledDataSource
import com.zto.fire.common.anno.Internal
import com.zto.fire.common.conf.FireFrameworkConf
import com.zto.fire.common.util.{DatasourceManager, StringsUtils}
import com.zto.fire.core.connector.{ConnectorFactory, FireConnector}
import com.zto.fire.jdbc.conf.FireJdbcConf
import com.zto.fire.jdbc.util.DBUtils
import com.zto.fire.predef._
import org.apache.commons.lang3.StringUtils

import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag

/**
 * 数据库连接池（c3p0）工具类
 * 封装了数据库常用的操作方法
 *
 * @param conf
 * 代码级别的配置信息，允许为空，配置文件会覆盖相同配置项，也就是说配置文件拥有着跟高的优先级
 * @param keyNum
 * 用于区分连接不同的数据源，不同配置源对应不同的Connector实例
 * @author ChengLong 2020-11-27 10:31:03
 */
private[fire] class JdbcConnector(conf: JdbcConf = null, keyNum: Int = 1) extends FireConnector(keyNum = keyNum) {
  private[this] var connPool: ComboPooledDataSource = _
  // 日志中sql截取的长度
  private lazy val logSqlLength = FireFrameworkConf.logSqlLength
  private[this] var username: String = _
  private[this] var url: String = _
  private[this] var dbType: String = "unknown"
  private[this] lazy val finallyCatchLog = "释放jdbc资源失败"

  /**
   * c3p0线程池初始化
   */
  override protected[fire] def open(): Unit = {
    tryWithLog {
      // 从配置文件中读取配置信息，并设置到ComboPooledDataSource对象中
      this.logger.info(s"准备初始化数据库连接池[ ${FireJdbcConf.jdbcUrl(keyNum)} ]")
      // 支持url和别名两种配置方式
      this.url = if (StringUtils.isBlank(FireJdbcConf.jdbcUrl(keyNum)) && this.conf != null && StringUtils.isNotBlank(this.conf.url)) this.conf.url else FireJdbcConf.jdbcUrl(keyNum)
      require(StringUtils.isNotBlank(this.url), s"数据库url不能为空，keyNum=${this.keyNum}")
      val driverClass = if (StringUtils.isBlank(FireJdbcConf.driverClass(keyNum)) && this.conf != null && StringUtils.isNotBlank(this.conf.driverClass)) this.conf.driverClass else FireJdbcConf.driverClass(keyNum)
      require(StringUtils.isNotBlank(driverClass), s"数据库driverClass不能为空，keyNum=${this.keyNum}")
      this.username = if (StringUtils.isBlank(FireJdbcConf.user(keyNum)) && this.conf != null && StringUtils.isNotBlank(this.conf.username)) this.conf.username else FireJdbcConf.user(keyNum)
      val password = if (StringUtils.isBlank(FireJdbcConf.password(keyNum)) && this.conf != null && StringUtils.isNotBlank(this.conf.password)) this.conf.password else FireJdbcConf.password(keyNum)
      // 识别数据源类型是oracle、mysql等
      this.dbType = DBUtils.dbTypeParser(driverClass, this.url)
      logger.info(s"Fire框架识别到当前jdbc数据源标识为：${this.dbType}，keyNum=${this.keyNum}")

      // 创建c3p0数据库连接池实例
      val pool = new ComboPooledDataSource(true)
      pool.setJdbcUrl(this.url)
      pool.setDriverClass(driverClass)
      if (StringUtils.isNotBlank(this.username)) pool.setUser(this.username)
      if (StringUtils.isNotBlank(password)) pool.setPassword(password)
      pool.setMaxPoolSize(FireJdbcConf.maxPoolSize(keyNum))
      pool.setMinPoolSize(FireJdbcConf.minPoolSize(keyNum))
      pool.setAcquireIncrement(FireJdbcConf.acquireIncrement(keyNum))
      pool.setInitialPoolSize(FireJdbcConf.initialPoolSize(keyNum))
      pool.setMaxStatements(0)
      pool.setMaxStatementsPerConnection(0)
      pool.setMaxIdleTime(FireJdbcConf.maxIdleTime(keyNum))
      this.connPool = pool
      this.logger.info(s"创建数据库连接池[ $keyNum ] driver: ${this.dbType}")
    }(this.logger, s"数据库连接池创建成功", s"初始化数据库连接池[ $keyNum ]失败")
  }

  /**
   * 关闭c3p0数据库连接池
   */
  override protected def close(): Unit = {
    if (this.connPool != null) {
      this.connPool.close()
      logger.debug(s"释放jdbc 连接池成功. keyNum=$keyNum")
    }
  }


  /**
   * 从指定的连接池中获取一个连接
   *
   * @return
   * 对应配置项的数据库连接
   */
  def getConnection: Connection = {
    tryWithReturn {
      val connection = this.connPool.getConnection
      this.logger.debug(s"获取数据库连接[ ${keyNum} ]成功")
      connection
    }(this.logger, catchLog = s"获取数据库连接[ ${FireJdbcConf.jdbcUrl(keyNum)} ]发生异常，请检查配置文件")
  }

  /**
   * 更新操作
   *
   * @param sql
   * 待执行的sql语句
   * @param params
   * sql中的参数
   * @param connection
   * 传递已有的数据库连接，可满足跨api的同一事务提交的需求
   * @param commit
   * 是否自动提交事务，默认为自动提交
   * @param closeConnection
   * 是否关闭connection，默认关闭
   * @return
   * 影响的记录数
   */
  def executeUpdate(sql: String, params: Seq[Any] = null, connection: Connection = null, commit: Boolean = true, closeConnection: Boolean = true): Long = {
    val conn = if (connection == null) this.getConnection else connection
    var retVal: Long = 0L
    var stat: PreparedStatement = null
    tryWithFinally {
      conn.setAutoCommit(false)
      stat = conn.prepareStatement(sql)

      // 设置值参数
      if (params != null && params.nonEmpty) {
        var i: Int = 1
        params.foreach(param => {
          stat.setObject(i, param)
          i += 1
        })
      }
      retVal = stat.executeUpdate
      if (commit) conn.commit()
      this.logger.info(s"executeUpdate success. keyNum: ${keyNum} count: $retVal")
      retVal
    } {
      this.release(sql, conn, stat, null, closeConnection)
    }(this.logger, s"${this.sqlBuriedPoint(sql)}",
      s"executeUpdate failed. keyNum：${keyNum}\n${this.sqlBuriedPoint(sql)}", finallyCatchLog)
  }

  /**
   * 执行批量更新操作
   *
   * @param sql
   * 待执行的sql语句
   * @param paramsList
   * sql的参数列表
   * @param connection
   * 传递已有的数据库连接，可满足跨api的同一事务提交的需求
   * @param commit
   * 是否自动提交事务，默认为自动提交
   * @param closeConnection
   * 是否关闭connection，默认关闭
   * @return
   * 影响的记录数
   */
  def executeBatch(sql: String, paramsList: Seq[Seq[Any]] = null, connection: Connection = null, commit: Boolean = true, closeConnection: Boolean = true): Array[Int] = {
    val conn = if (connection == null) this.getConnection else connection
    var stat: PreparedStatement = null

    var batch = 0
    var count = 0
    tryWithFinally {
      conn.setAutoCommit(false)
      stat = conn.prepareStatement(sql)
      if (paramsList != null && paramsList.nonEmpty) {
        paramsList.foreach(params => {
          var i = 1
          params.foreach(param => {
            stat.setObject(i, param)
            i += 1
          })
          batch += 1
          stat.addBatch()
          if (batch % FireJdbcConf.batchSize(keyNum) == 0) {
            stat.executeBatch()
            stat.clearBatch()
          }
        })
      }
      // 执行批量更新
      val retVal = stat.executeBatch
      if (commit) conn.commit()
      count = retVal.sum
      this.logger.info(s"executeBatch success. keyNum: ${keyNum} count: $count")
      retVal
    } {
      this.release(sql, conn, stat, null, closeConnection)
    }(this.logger, s"${this.sqlBuriedPoint(sql)}",
      s"executeBatch failed. keyNum：${keyNum}\n${this.sqlBuriedPoint(sql)}", finallyCatchLog)
  }

  /**
   * 执行查询操作，以JavaBean方式返回结果集
   *
   * @param sql
   * 查询语句
   * @param params
   * sql执行参数
   * @param clazz
   * JavaBean类型
   * @param connection
   * 传递已有的数据库连接，可满足跨api的同一事务提交的需求
   */
  def executeQuery[T <: Object : ClassTag](sql: String, params: Seq[Any] = null, clazz: Class[T], connection: Connection = null): List[T] = {
    val listBuffer = ListBuffer[T]()

    this.executeQueryCall(sql, params, rs => {
      listBuffer ++= DBUtils.dbResultSet2Bean(rs, clazz)
      listBuffer.size
    }, connection)

    listBuffer.toList
  }

  /**
   * 执行查询操作
   *
   * @param sql
   * 查询语句
   * @param params
   * sql执行参数
   * @param callback
   * 查询回调
   * @param connection
   * 传递已有的数据库连接，可满足跨api的同一事务提交的需求
   */
  def executeQueryCall(sql: String, params: Seq[Any] = null, callback: ResultSet => Int = null, connection: Connection = null): Unit = {
    val conn = if (connection == null) this.getConnection else connection
    var stat: PreparedStatement = null
    var rs: ResultSet = null
    var count: Long = 0

    tryWithFinally {
      stat = conn.prepareStatement(sql)
      if (params != null && params.nonEmpty) {
        var i = 1
        params.foreach(param => {
          stat.setObject(i, param)
          i += 1
        })
      }
      rs = stat.executeQuery

      if (rs != null && callback != null) {
        count = callback(rs)
      }
      this.logger.info(s"executeQueryCall success. keyNum: ${keyNum} count: $count")
    } {
      this.release(sql, conn, stat, rs)
    }(this.logger, s"${this.sqlBuriedPoint(sql, false)}",
      s"executeQueryCall failed. keyNum：${keyNum}\n${this.sqlBuriedPoint(sql, false)}", finallyCatchLog)
  }

  /**
   * 释放jdbc资源的工具类
   *
   * @param sql
   * 对应的sql语句
   * @param conn
   * 数据库连接
   * @param rs
   * 查询结果集
   * @param stat
   * jdbc statement
   */
  def release(sql: String, conn: Connection, stat: Statement, rs: ResultSet, closeConnection: Boolean = true): Unit = {
    try {
      if (rs != null) rs.close()
    } catch {
      case e: SQLException => {
        this.logger.error(s"close jdbc ResultSet failed. keyNum: ${keyNum}", e)
        throw e
      }
    } finally {
      try {
        if (stat != null) stat.close()
      } catch {
        case e: SQLException => {
          this.logger.error(s"close jdbc statement failed. keyNum: ${keyNum}", e)
          throw e
        }
      } finally {
        try {
          if (conn != null && closeConnection) conn.close()
        } catch {
          case e: SQLException => {
            this.logger.error(s"close jdbc connection failed. keyNum: ${keyNum}", e)
            throw e
          }
        }
      }
    }
  }

  /**
   * 工具方法，截取给定的SQL语句
   */
  @Internal
  private[this] def sqlBuriedPoint(sql: String, sink: Boolean = true): String = {
    DatasourceManager.addSql(this.dbType, this.url, this.username, sql, sink)
    StringsUtils.substring(sql, 0, this.logSqlLength)
  }

}


/**
 * jdbc最基本的配置信息，如果配置文件中有，则会覆盖代码中的配置
 *
 * @param url
 * 数据库的url
 * @param driverClass
 * jdbc驱动名称
 * @param username
 * 数据库用户名
 * @param password
 * 数据库密码
 */
case class JdbcConf(url: String, driverClass: String, username: String, password: String)

/**
 * 用于单例构建伴生类JdbcConnector的实例对象
 * 每个JdbcConnector实例使用keyNum作为标识，并且与每个关系型数据库一一对应
 */
object JdbcConnector extends ConnectorFactory[JdbcConnector] with JdbcFunctions {

  /**
   * 约定创建connector子类实例的方法
   */
  override protected def create(conf: Any = null, keyNum: Int = 1): JdbcConnector = {
    requireNonEmpty(keyNum)
    val connector = new JdbcConnector(conf.asInstanceOf[JdbcConf], keyNum)
    logger.debug(s"创建JdbcConnector实例成功. keyNum=$keyNum")
    connector
  }
}