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

import org.apache.spark.{LocalSparkContext, SparkConf, SparkContext, SparkException, SparkFunSuite}
import org.apache.spark.util.{SerializableBuffer, AkkaUtils}

class CoarseGrainedSchedulerBackendSuite extends SparkFunSuite with LocalSparkContext {
  //序列化任务大于Akka框架大小

  ignore("serialized task larger than akka frame size") {
    val conf = new SparkConf
    //以MB为单位的driver和executor之间通信信息的大小,设置值越大,driver可以接受越大的计算结果
    conf.set("spark.akka.frameSize", "1")
    //设置并发数
    conf.set("spark.default.parallelism", "1")
    //sc = new SparkContext("local-cluster[2, 1, 1024]", "test", conf)
    sc = new SparkContext("local[*]", "test", conf)
    //获得Akka传递值大小 1048576默认10M
    val frameSize = AkkaUtils.maxFrameSizeBytes(sc.conf)
   //创建一个序列化缓存

   //ByteBuffer.allocate在能够读和写之前,必须有一个缓冲区,用静态方法 allocate() 来分配缓冲区
    //allocate 分配20M
   val buffer = new SerializableBuffer(java.nio.ByteBuffer.allocate(2 * frameSize))

   val larger = sc.parallelize(Seq(buffer))
  val thrown = intercept[SparkException] {
     larger.collect()
   }
   //抛出异常:使用大的值广播变量
   assert(thrown.getMessage.contains("using broadcast variables for large values"))
   val smaller = sc.parallelize(1 to 4).collect()
   assert(smaller.size === 4)/**/
  }

}
