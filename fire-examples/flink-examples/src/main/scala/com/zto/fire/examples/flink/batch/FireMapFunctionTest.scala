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

package com.zto.fire.examples.flink.batch

import java.lang
import java.util.UUID

import com.zto.fire._
import com.zto.fire.flink.BaseFlinkBatch
import com.zto.fire.flink.ext.function.FireMapFunction
import org.apache.flink.api.common.state.StateTtlConfig
import org.apache.flink.api.common.time.Time
import org.apache.flink.configuration.Configuration
import org.apache.flink.util.Collector
import org.apache.flink.api.scala._

/**
 * 用于演示FireMapFunction的使用，FireMapFunction比RichMapFunction功能更强大
 * 提供了多值计数器、常用API函数的便捷使用等，甚至同时支持：map、flatMap、mapPartition等操作
 * 内部对状态的api进行了封装，使用起来更简洁
 *
 * @author ChengLong 2020-4-9 15:59:19
 */
object FireMapFunctionTest extends BaseFlinkBatch {
  lazy val dataset = this.fire.createCollectionDataSet(1 to 10)
  lazy val dataset2 = this.fire.createCollectionDataSet(1 to 3)

  override def process: Unit = {
    this.testMap
    this.testMapPartition
    this.testFlatMap
  }

  /**
   * 使用FireMapFunction进行Map算子操作
   */
  private def testMap: Unit = {
    dataset.map(new FireMapFunction[Int, String]() {
      lazy val ttlConfig = StateTtlConfig.newBuilder(Time.days(1)).build()
      // 获取广播变量
      lazy val brocastValue = this.getBroadcastVariable[Int]("values")

      override def map(value: Int): String = {
        // 累加器使用详见：FlinkAccTest.scala
        this.addCounter("IntCount", 2)
        this.addCounter("LongCount", 3L)

        // 广播变量
        this.brocastValue.foreach(println)
        // 状态使用，具有懒加载的能力，根据name从缓存中获取valueState，不需要声明为成员变量或在open方法中初始化
        val valueState = this.getState[Int]("fire", ttlConfig)
        valueState.update(valueState.value())

        val listState = this.getListState[Int]("fire_list")
        listState.add(value)

        val mapState = this.getMapState[Int, Int]("fire_map", ttlConfig)
        mapState.put(value, value)
        value.toString
      }
    }).withBroadcastSet(dataset2, "values").print()
  }

  /**
   * 使用FireMapFunction进行Map算子操作
   */
  private def testMapPartition: Unit = {
    dataset.mapPartition(new FireMapFunction[Int, String]() {
      override def open(parameters: Configuration): Unit = {
        // 执行初始化操作，如创建数据库连接池，调用次数与并行度一致
      }

      override def mapPartition(values: lang.Iterable[Int], out: Collector[String]): Unit = {
        values.iterator().foreach(i => out.collect(i.toString))
      }

      override def close(): Unit = {
        // 执行清理操作，如释放数据库连接，关闭文件句柄，调用次数与并行度一致
      }

    }).print()
  }

  /**
   * 使用FireMapFunction进行FlatMap算子操作
   */
  private def testFlatMap: Unit = {
    dataset.flatMap(new FireMapFunction[Int, String] {
      override def flatMap(value: Int, out: Collector[String]): Unit = {
        out.collect(value + " - " + UUID.randomUUID().toString)
      }
    }).print()
  }

  def main(args: Array[String]): Unit = {
    this.init()
    this.stop
  }
}
