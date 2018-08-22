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
package org.apache.spark.rdd

import scala.reflect.{ClassTag, classTag}

import org.apache.hadoop.io.Writable
import org.apache.hadoop.io.compress.CompressionCodec
import org.apache.hadoop.mapred.JobConf
import org.apache.hadoop.mapred.SequenceFileOutputFormat

import org.apache.spark.Logging

/**
 * Extra functions available on RDDs of (key, value) pairs to create a Hadoop SequenceFile,
  * （key，value）对的RDD可以使用额外的功能来创建Hadoop SequenceFile，
 * through an implicit conversion. Note that this can't be part of PairRDDFunctions because
 * we need more implicit parameters to convert our keys and values to Writable.
  * 通过隐式转换。请注意,这不能是PairRDDFunctions的一部分,因为我们需要更多的隐式参数来将我们的键和值转换为Writable。
 *
 */
class SequenceFileRDDFunctions[K <% Writable: ClassTag, V <% Writable : ClassTag](
    self: RDD[(K, V)],
    _keyWritableClass: Class[_ <: Writable],
    _valueWritableClass: Class[_ <: Writable])
  extends Logging
  with Serializable {

  @deprecated("It's used to provide backward compatibility for pre 1.3.0.", "1.3.0")
  def this(self: RDD[(K, V)]) {
    this(self, null, null)
  }

  private val keyWritableClass =
    if (_keyWritableClass == null) {
      // pre 1.3.0, we need to use Reflection to get the Writable class
      getWritableClass[K]()
    } else {
      _keyWritableClass
    }

  private val valueWritableClass =
    if (_valueWritableClass == null) {
      // pre 1.3.0, we need to use Reflection to get the Writable class
      getWritableClass[V]()
    } else {
      _valueWritableClass
    }

  private def getWritableClass[T <% Writable: ClassTag](): Class[_ <: Writable] = {
    val c = {
      if (classOf[Writable].isAssignableFrom(classTag[T].runtimeClass)) {
        classTag[T].runtimeClass
      } else {
        // We get the type of the Writable class by looking at the apply method which converts
        // from T to Writable. Since we have two apply methods we filter out the one which
        // is not of the form "java.lang.Object apply(java.lang.Object)"
        implicitly[T => Writable].getClass.getDeclaredMethods().filter(
            m => m.getReturnType().toString != "class java.lang.Object" &&
                 m.getName() == "apply")(0).getReturnType

      }
       // TODO: use something like WritableConverter to avoid reflection
    }
    c.asInstanceOf[Class[_ <: Writable]]
  }


  /**
   * Output the RDD as a Hadoop SequenceFile using the Writable types we infer from the RDD's key
   * and value types. If the key or value are Writable, then we use their classes directly;
   * otherwise we map primitive types such as Int and Double to IntWritable, DoubleWritable, etc,
   * byte arrays to BytesWritable, and Strings to Text. The `path` can be on any Hadoop-supported
   * file system.
    * 使用我们从RDD的键和值类型推断出的Writable类型将RDD输出为Hadoop SequenceFile,
    * 如果键或值是可写的,那么我们直接使用它们的类;
    * 否则我们将原始类型（如Int和Double）映射到IntWritable,DoubleWritable等,将字节数组映射到BytesWritable,
    * 将字符串映射到Text。 `path`可以在任何支持Hadoop的文件系统上。
   */
  def saveAsSequenceFile(
      path: String,
      codec: Option[Class[_ <: CompressionCodec]] = None): Unit = self.withScope {
    def anyToWritable[U <% Writable](u: U): Writable = u

    // TODO We cannot force the return type of `anyToWritable` be same as keyWritableClass and
    // valueWritableClass at the compile time. To implement that, we need to add type parameters to
    // SequenceFileRDDFunctions. however, SequenceFileRDDFunctions is a public class so it will be a
    // breaking change.
    val convertKey = self.keyClass != keyWritableClass
    val convertValue = self.valueClass != valueWritableClass

    logInfo("Saving as sequence file of type (" + keyWritableClass.getSimpleName + "," +
      valueWritableClass.getSimpleName + ")" )
    val format = classOf[SequenceFileOutputFormat[Writable, Writable]]
    val jobConf = new JobConf(self.context.hadoopConfiguration)
    if (!convertKey && !convertValue) {
      self.saveAsHadoopFile(path, keyWritableClass, valueWritableClass, format, jobConf, codec)
    } else if (!convertKey && convertValue) {
      self.map(x => (x._1, anyToWritable(x._2))).saveAsHadoopFile(
        path, keyWritableClass, valueWritableClass, format, jobConf, codec)
    } else if (convertKey && !convertValue) {
      self.map(x => (anyToWritable(x._1), x._2)).saveAsHadoopFile(
        path, keyWritableClass, valueWritableClass, format, jobConf, codec)
    } else if (convertKey && convertValue) {
      self.map(x => (anyToWritable(x._1), anyToWritable(x._2))).saveAsHadoopFile(
        path, keyWritableClass, valueWritableClass, format, jobConf, codec)
    }
  }
}
