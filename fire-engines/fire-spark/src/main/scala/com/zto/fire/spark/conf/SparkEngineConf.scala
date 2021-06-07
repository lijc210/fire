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

package com.zto.fire.spark.conf

import com.zto.fire.core.conf.EngineConf
import com.zto.fire.spark.util.SparkUtils
import org.apache.spark.SparkEnv

/**
 * 获取Spark引擎的所有配置信息
 *
 * @author ChengLong
 * @since 2.0.0
 * @create 2021-03-02 10:57
 */
private[fire] class SparkEngineConf extends EngineConf {

  /**
   * 获取引擎的所有配置信息
   */
  override def getEngineConf: Map[String, String] = {
    if (SparkUtils.isExecutor) {
      SparkEnv.get.conf.getAll.toMap
    } else {
      Map.empty[String, String]
    }
  }
}