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

package org.apache.spark.scheduler

import scala.collection.mutable

import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}

import org.apache.spark.{LocalSparkContext, SparkContext, SparkFunSuite}
import org.apache.spark.scheduler.cluster.ExecutorInfo

/**
 * Unit tests for SparkListener that require a local cluster.
 * 对于sparklistener需要本地集群单元测试
 */
class SparkListenerWithClusterSuite extends SparkFunSuite with LocalSparkContext
  with BeforeAndAfter with BeforeAndAfterAll {

  /** 
   *  Length of time to wait while draining listener events.
   *  等待监听事件的时间长度
   *   */
  val WAIT_TIMEOUT_MILLIS = 10000

  before {
    //sc = new SparkContext("local-cluster[2,1,1024]", "SparkListenerSuite")
    sc = new SparkContext("local[*]", "SparkListenerSuite")
  }

  ignore("SparkListener sends executor added message") {//发送执行者添加消息
    val listener = new SaveExecutorInfo
    sc.addSparkListener(listener)

    // This test will check if the number of executors received by "SparkListener" is same as the
    // number of all executors, so we need to wait until all executors are up
    //此测试将检查“SparkListener”接收到的执行程序的数量是否相同,所有执行者的数量，所以我们需要等到所有的执行者都开始了
    sc.jobProgressListener.waitUntilExecutorsUp(2, 10000)

    val rdd1 = sc.parallelize(1 to 100, 4)
    val rdd2 = rdd1.map(_.toString)
    rdd2.setName("Target RDD")
    rdd2.count()

    sc.listenerBus.waitUntilEmpty(WAIT_TIMEOUT_MILLIS)
    assert(listener.addedExecutorInfo.size == 2)
    assert(listener.addedExecutorInfo("0").totalCores == 1)
    assert(listener.addedExecutorInfo("1").totalCores == 1)
  }

  private class SaveExecutorInfo extends SparkListener {
    val addedExecutorInfo = mutable.Map[String, ExecutorInfo]()

    override def onExecutorAdded(executor: SparkListenerExecutorAdded) {
      addedExecutorInfo(executor.executorId) = executor.executorInfo
    }
  }
}
