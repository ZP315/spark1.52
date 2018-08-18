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

import java.text.SimpleDateFormat
import java.util.Date
import java.io.EOFException

import scala.collection.immutable.Map
import scala.reflect.ClassTag
import scala.collection.mutable.ListBuffer

import org.apache.hadoop.conf.{Configurable, Configuration}
import org.apache.hadoop.mapred.FileSplit
import org.apache.hadoop.mapred.InputFormat
import org.apache.hadoop.mapred.InputSplit
import org.apache.hadoop.mapred.JobConf
import org.apache.hadoop.mapred.RecordReader
import org.apache.hadoop.mapred.Reporter
import org.apache.hadoop.mapred.JobID
import org.apache.hadoop.mapred.TaskAttemptID
import org.apache.hadoop.mapred.TaskID
import org.apache.hadoop.mapred.lib.CombineFileSplit
import org.apache.hadoop.util.ReflectionUtils

import org.apache.spark._
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.executor.DataReadMethod
import org.apache.spark.rdd.HadoopRDD.HadoopMapPartitionsWithSplitRDD
import org.apache.spark.util.{SerializableConfiguration, ShutdownHookManager, NextIterator, Utils}
import org.apache.spark.scheduler.{HostTaskLocation, HDFSCacheTaskLocation}
import org.apache.spark.storage.StorageLevel

/**
 * A Spark split class that wraps around a Hadoop InputSplit.
  * 一个包围Hadoop InputSplit的Spark拆分类
 */
private[spark] class HadoopPartition(rddId: Int, idx: Int, @transient s: InputSplit)
  extends Partition {

  val inputSplit = new SerializableWritable[InputSplit](s)

  override def hashCode(): Int = 41 * (41 + rddId) + idx

  override val index: Int = idx

  /**
   * Get any environment variables that should be added to the users environment when running pipes
    * 获取在运行管道时应该添加到用户环境的任何环境变量
   * @return a Map with the environment variables and corresponding values, it could be empty
    *         一个带有环境变量和相应值的Map，它可以是空的
   */
  def getPipeEnvVars(): Map[String, String] = {
    val envVars: Map[String, String] = if (inputSplit.value.isInstanceOf[FileSplit]) {
      val is: FileSplit = inputSplit.value.asInstanceOf[FileSplit]
      // map_input_file is deprecated in favor of mapreduce_map_input_file but set both
      // since its not removed yet
      //map_input_file已被弃用,赞成使用mapreduce_map_input_file,但由于尚未删除
      Map("map_input_file" -> is.getPath().toString(),
        "mapreduce_map_input_file" -> is.getPath().toString())
    } else {
      Map()
    }
    envVars
  }
}

/**
 * :: DeveloperApi ::
 * An RDD that provides core functionality for reading data stored in Hadoop (e.g., files in HDFS,
 * sources in HBase, or S3), using the older MapReduce API (`org.apache.hadoop.mapred`).
 * 这个RDD提供读取存储到Hadoop数据,使用旧的MapReduce API
 * Note: Instantiating this class directly is not recommended, please use
 * 注意:实例化这个类不推荐直接使用
 * [[org.apache.spark.SparkContext.hadoopRDD()]]
 *
 * @param sc The SparkContext to associate the RDD with.
 * @param broadcastedConf A general Hadoop Configuration, or a subclass of it. If the enclosed
 *     variabe references an instance of JobConf, then that JobConf will be used for the Hadoop job.
 *     Otherwise, a new JobConf will be created on each slave using the enclosed Configuration.
 * @param initLocalJobConfFuncOpt Optional closure used to initialize any JobConf that HadoopRDD
 *     creates.
 * @param inputFormatClass Storage format of the data to be read. * 
 * @param keyClass Class of the key associated with the inputFormatClass.
 * @param valueClass Class of the value associated with the inputFormatClass.
 * @param minPartitions Minimum number of HadoopRDD partitions (Hadoop Splits) to generate.
 */
@DeveloperApi
class HadoopRDD[K, V](
    @transient sc: SparkContext,
    broadcastedConf: Broadcast[SerializableConfiguration],
    initLocalJobConfFuncOpt: Option[JobConf => Unit],
    inputFormatClass: Class[_ <: InputFormat[K, V]],//读取的数据的存储格式
    keyClass: Class[K],
    valueClass: Class[V],
    minPartitions: Int)//生成最小分区数
    //deps是一个空的Nil list,原因是它是从外部文件生成的,没有父rdd
    //列表结尾为Nil
  extends RDD[(K, V)](sc, Nil) with Logging {

  if (initLocalJobConfFuncOpt.isDefined) {
    sc.clean(initLocalJobConfFuncOpt.get)//清理
  }

  def this(
      sc: SparkContext,
      conf: JobConf,
      inputFormatClass: Class[_ <: InputFormat[K, V]],
      keyClass: Class[K],
      valueClass: Class[V],
      minPartitions: Int) = {
    this(
      sc,
      sc.broadcast(new SerializableConfiguration(conf))
        .asInstanceOf[Broadcast[SerializableConfiguration]],
      None /* initLocalJobConfFuncOpt */,
      inputFormatClass,
      keyClass,
      valueClass,
      minPartitions)
  }

  protected val jobConfCacheKey = "rdd_%d_job_conf".format(id)

  protected val inputFormatCacheKey = "rdd_%d_input_format".format(id)

  // used to build JobTracker ID
  //用于构建JobTracker ID
  private val createTime = new Date()//创建一个Job跟踪ID

  private val shouldCloneJobConf = sc.conf.getBoolean("spark.hadoop.cloneConf", false)

  // Returns a JobConf that will be used on slaves to obtain input splits for Hadoop reads.
  //返回一个jobconf将用于从节点获得输入Hadoop读取
  protected def getJobConf(): JobConf = {
    //从广播变量中获取jobconfig
    val conf: Configuration = broadcastedConf.value.value
    if (shouldCloneJobConf) {
      // Hadoop Configuration objects are not thread-safe, which may lead to various problems if
      // one job modifies a configuration while another reads it (SPARK-2546).  This problem occurs
      // somewhat rarely because most jobs treat the configuration as though it's immutable.  One
      // solution, implemented here, is to clone the Configuration object.  Unfortunately, this
      // clone can be very expensive.  To avoid unexpected performance regressions for workloads and
      // Hadoop versions that do not suffer from these thread-safety issues, this cloning is
      // disabled by default.
      //Hadoop配置对象不是线程安全的，如果一个作业修改配置而另一个作业修改（SPARK-2546），则可能会导致各种问题。 这个问题很少发生，
      // 因为大多数作业将配置视为不可变的。 这里实现的一个解决方案是克隆Configuration对象。
      // 不幸的是，这个克隆可能非常昂贵。 为了避免不承担这些线程安全问题的工作负载和Hadoop版本的意外性能回归，默认情况下禁用此克隆。
      HadoopRDD.CONFIGURATION_INSTANTIATION_LOCK.synchronized {
        logDebug("Cloning Hadoop Configuration")
        val newJobConf = new JobConf(conf)
        if (!conf.isInstanceOf[JobConf]) {
          initLocalJobConfFuncOpt.map(f => f(newJobConf))
        }
        newJobConf
      }
    } else {
      if (conf.isInstanceOf[JobConf]) {
        logDebug("Re-using user-broadcasted JobConf")
        conf.asInstanceOf[JobConf]
      } else if (HadoopRDD.containsCachedMetadata(jobConfCacheKey)) {
        logDebug("Re-using cached JobConf")
        HadoopRDD.getCachedMetadata(jobConfCacheKey).asInstanceOf[JobConf]
      } else {
        // Create a JobConf that will be cached and used across this RDD's getJobConf() calls in the
        // local process. The local cache is accessed through HadoopRDD.putCachedMetadata().
        // The caching helps minimize GC, since a JobConf can contain ~10KB of temporary objects.
        // Synchronize to prevent ConcurrentModificationException (SPARK-1097, HADOOP-10456).
        //创建一个JobConf，将在本地进程中的RDD的getJobConf（）调用中缓存并使用,本地缓存通过HadoopRDD.putCachedMetadata（）访问,
        // 缓存有助于最小化GC，因为JobConf可以包含〜10KB的临时对象,
        // 同步以防止ConcurrentModificationException（SPARK-1097，HADOOP-10456）
        HadoopRDD.CONFIGURATION_INSTANTIATION_LOCK.synchronized {
          logDebug("Creating new JobConf and caching it for later re-use")
          val newJobConf = new JobConf(conf)
          initLocalJobConfFuncOpt.map(f => f(newJobConf))
          HadoopRDD.putCachedMetadata(jobConfCacheKey, newJobConf)
          newJobConf
        }
      }
    }
  }

  protected def getInputFormat(conf: JobConf): InputFormat[K, V] = {
    if (HadoopRDD.containsCachedMetadata(inputFormatCacheKey)) {//是否缓存包含
      return HadoopRDD.getCachedMetadata(inputFormatCacheKey).asInstanceOf[InputFormat[K, V]]
    }
    // Once an InputFormat for this RDD is created, cache it so that only one reflection call is
    // done in each local process.
    //通过调用反射创建InputFormat对象在本地进程中完成
    val newInputFormat = ReflectionUtils.newInstance(inputFormatClass.asInstanceOf[Class[_]], conf)
      .asInstanceOf[InputFormat[K, V]]
    if (newInputFormat.isInstanceOf[Configurable]) {
      newInputFormat.asInstanceOf[Configurable].setConf(conf)
    }
    //缓存它
    HadoopRDD.putCachedMetadata(inputFormatCacheKey, newInputFormat)
    newInputFormat
  }

  override def getPartitions: Array[Partition] = {
    val jobConf = getJobConf()
    // add the credentials here as this can be called before SparkContext initialized
    //调用SparkContext初始化之前在这里添加凭据(credentials)
    SparkHadoopUtil.get.addCredentials(jobConf)
    val inputFormat = getInputFormat(jobConf)
    if (inputFormat.isInstanceOf[Configurable]) {
      inputFormat.asInstanceOf[Configurable].setConf(jobConf)
    }
    //决定 分区个数
    val inputSplits = inputFormat.getSplits(jobConf, minPartitions)
    val array = new Array[Partition](inputSplits.size)
    for (i <- 0 until inputSplits.size) {
      array(i) = new HadoopPartition(id, i, inputSplits(i))
    }
    array
  }

  override def compute(theSplit: Partition, context: TaskContext): InterruptibleIterator[(K, V)] = {
    val iter = new NextIterator[(K, V)] {

      val split = theSplit.asInstanceOf[HadoopPartition]
      logInfo("Input split: " + split.inputSplit)
      //从broadcast中获取JobConf,
      val jobConf = getJobConf()
      //创建taskMetrics用于计算字节读取的测量信息
      val inputMetrics = context.taskMetrics.getInputMetricsForReadMethod(DataReadMethod.Hadoop)

      // Find a function that will return the FileSystem bytes read by this thread. Do this before
      // creating RecordReader, because RecordReader's constructor might read some bytes
      //bytesReadCallback用于获取当前线程从文件系统读取的字节数
      val bytesReadCallback = inputMetrics.bytesReadCallback.orElse {
        split.inputSplit.value match {
          case _: FileSplit | _: CombineFileSplit =>
            SparkHadoopUtil.get.getFSBytesReadOnThreadCallback()
          case _ => None
        }
      }
      //从文件系统读取的字节数
      inputMetrics.setBytesReadCallback(bytesReadCallback)
      //RecordReader读取数据之前创建bytesReadCallback
      var reader: RecordReader[K, V] = null
      //获取inputFormat
      val inputFormat = getInputFormat(jobConf)
      //使用addLocalConfiguration给jobconf添加hadoop任务相关配置
      HadoopRDD.addLocalConfiguration(new SimpleDateFormat("yyyyMMddHHmm").format(createTime),
        context.stageId, theSplit.index, context.attemptNumber, jobConf)
      //创建RecordReader,
      reader = inputFormat.getRecordReader(split.inputSplit.value, jobConf, Reporter.NULL)

      // Register an on-task-completion callback to close the input stream.
      //注册一个完成任务完成的回调,关闭输入流
      context.addTaskCompletionListener{ context => closeIfNeeded() }
      //得到LongWriteable
      val key: K = reader.createKey()
      //得到Text
      val value: V = reader.createValue()
      //每读取一些记录后使用bytesReadCallback更新InputMetrics的byteRead字段
      override def getNext(): (K, V) = {
        try {
          finished = !reader.next(key, value)
        } catch {
          case eof: EOFException =>
            finished = true
        }
        if (!finished) {
          inputMetrics.incRecordsRead(1)
        }
        (key, value)
      }

      override def close() {
        if (reader != null) {
          // Close the reader and release it. Note: it's very important that we don't close the
          // reader more than once, since that exposes us to MAPREDUCE-5918 when running against
          // Hadoop 1.x and older Hadoop 2.x releases. That bug can lead to non-deterministic
          // corruption issues when reading compressed input.
          //关闭阅读器并释放它,注意：非常重要的是，我们不要多次关闭该游戏，因为当您对...运行时暴露于MAPREDUCE-5918
          // Hadoop 1.x和更旧的Hadoop 2.x版本。 那个bug可能导致非确定性
          //读取压缩输入时出现损坏问题
          try {
            reader.close()
          } catch {
            case e: Exception =>
              if (!ShutdownHookManager.inShutdown()) {
                logWarning("Exception in RecordReader.close()", e)
              }
          } finally {
            reader = null
          }
          if (bytesReadCallback.isDefined) {
            inputMetrics.updateBytesRead()
          } else if (split.inputSplit.value.isInstanceOf[FileSplit] ||
                     split.inputSplit.value.isInstanceOf[CombineFileSplit]) {
            // If we can't get the bytes read from the FS stats, fall back to the split size,
            // which may be inaccurate.
            try {
              inputMetrics.incBytesRead(split.inputSplit.value.getLength)
            } catch {
              case e: java.io.IOException =>
                logWarning("Unable to get input size to set InputMetrics for task", e)
            }
          }
        }
      }
    }
    //将NextIterator封装为InterruptibleIterator,InterruptibleIterator只是对NextIterator的代理
    new InterruptibleIterator[(K, V)](context, iter)
  }

  /** Maps over a partition, providing the InputSplit that was used as the base of the partition. */
  @DeveloperApi
  def mapPartitionsWithInputSplit[U: ClassTag](
      f: (InputSplit, Iterator[(K, V)]) => Iterator[U],
      preservesPartitioning: Boolean = false): RDD[U] = {
    new HadoopMapPartitionsWithSplitRDD(this, f, preservesPartitioning)
  }
//返回某些 RDD具有位置优先,返回对应 partition 关联的 block 所在的节点 host
  override def getPreferredLocations(split: Partition): Seq[String] = {
    val hsplit = split.asInstanceOf[HadoopPartition].inputSplit.value
    val locs: Option[Seq[String]] = HadoopRDD.SPLIT_INFO_REFLECTIONS match {
      case Some(c) =>
        try {
          val lsplit = c.inputSplitWithLocationInfo.cast(hsplit)
          val infos = c.getLocationInfo.invoke(lsplit).asInstanceOf[Array[AnyRef]]
          Some(HadoopRDD.convertSplitLocationInfo(infos))
        } catch {
          case e: Exception =>
            logDebug("Failed to use InputSplitWithLocations.", e)
            None
        }
      case None => None
    }
    locs.getOrElse(hsplit.getLocations.filter(_ != "localhost"))
  }

  override def checkpoint() {
    // Do nothing. Hadoop RDD should not be checkpointed.
  }
  //this.type表示当前对象(this)的类型,this指代当前的对象
  override def persist(storageLevel: StorageLevel): this.type = {
    if (storageLevel.deserialized) {
      logWarning("Caching NewHadoopRDDs as deserialized objects usually leads to undesired" +
        " behavior because Hadoop's RecordReader reuses the same Writable object for all records." +
        " Use a map transformation to make copies of the records.")
    }
    super.persist(storageLevel)
  }

  def getConf: Configuration = getJobConf()
}

private[spark] object HadoopRDD extends Logging {
  /**
   * Configuration's constructor is not threadsafe (see SPARK-1097 and HADOOP-10456).
    * 配置构造函数不是线程安全的（参见SPARK-1097和HADOOP-10456）。
   * Therefore, we synchronize on this lock before calling new JobConf() or new Configuration().
   * 因此,在调用new JobConf() or new Configuration()之前,我们在这个锁上进行同步
   */
  val CONFIGURATION_INSTANTIATION_LOCK = new Object()

  /** 
   *  Update the input bytes read metric each time this number of records has been read 
   *  更新输入字节读测量每一次读取记录数
   *  */
  val RECORDS_BETWEEN_BYTES_READ_METRIC_UPDATES = 256

  /**
   * The three methods below are helpers for accessing the local map, a property of the SparkEnv of
   * the local process.
   * 下面的三种方法是访问本地Map的助手,对本地进程的SparkEnv属性
   */
  def getCachedMetadata(key: String): Any = SparkEnv.get.hadoopJobMetadata.get(key)

  def containsCachedMetadata(key: String): Boolean = SparkEnv.get.hadoopJobMetadata.containsKey(key)

  private def putCachedMetadata(key: String, value: Any): Unit =
    SparkEnv.get.hadoopJobMetadata.put(key, value)

  /** 
   *  Add Hadoop configuration specific to a single partition and attempt.
   *  添加Hadoop配置特定于单个分区和尝试次数
   *   */
  def addLocalConfiguration(jobTrackerId: String, jobId: Int, splitId: Int, attemptId: Int,
                            conf: JobConf) {
    val jobID = new JobID(jobTrackerId, jobId)
    val taId = new TaskAttemptID(new TaskID(jobID, true, splitId), attemptId)

    conf.set("mapred.tip.id", taId.getTaskID.toString)
    conf.set("mapred.task.id", taId.toString)
    conf.setBoolean("mapred.task.is.map", true)
    conf.setInt("mapred.task.partition", splitId)
    conf.set("mapred.job.id", jobID.toString)
  }

  /**
   * Analogous to [[org.apache.spark.rdd.MapPartitionsRDD]], but passes in an InputSplit to
   * the given function rather than the index of the partition.
   */
  private[spark] class HadoopMapPartitionsWithSplitRDD[U: ClassTag, T: ClassTag](
      prev: RDD[T],
      f: (InputSplit, Iterator[T]) => Iterator[U],
      preservesPartitioning: Boolean = false)
    extends RDD[U](prev) {

    override val partitioner = if (preservesPartitioning) firstParent[T].partitioner else None

    override def getPartitions: Array[Partition] = firstParent[T].partitions

    override def compute(split: Partition, context: TaskContext): Iterator[U] = {
      val partition = split.asInstanceOf[HadoopPartition]
      val inputSplit = partition.inputSplit.value
      f(inputSplit, firstParent[T].iterator(split, context))
    }
  }

  private[spark] class SplitInfoReflections {
    val inputSplitWithLocationInfo =
      Utils.classForName("org.apache.hadoop.mapred.InputSplitWithLocationInfo")
    val getLocationInfo = inputSplitWithLocationInfo.getMethod("getLocationInfo")
    val newInputSplit = Utils.classForName("org.apache.hadoop.mapreduce.InputSplit")
    val newGetLocationInfo = newInputSplit.getMethod("getLocationInfo")
    val splitLocationInfo = Utils.classForName("org.apache.hadoop.mapred.SplitLocationInfo")
    val isInMemory = splitLocationInfo.getMethod("isInMemory")
    val getLocation = splitLocationInfo.getMethod("getLocation")
  }

  private[spark] val SPLIT_INFO_REFLECTIONS: Option[SplitInfoReflections] = try {
    Some(new SplitInfoReflections)
  } catch {
    case e: Exception =>
      logDebug("SplitLocationInfo and other new Hadoop classes are " +
          "unavailable. Using the older Hadoop location info code.", e)
      None
  }

  private[spark] def convertSplitLocationInfo(infos: Array[AnyRef]): Seq[String] = {
    val out = ListBuffer[String]()
    infos.foreach { loc => {
      val locationStr = HadoopRDD.SPLIT_INFO_REFLECTIONS.get.
        getLocation.invoke(loc).asInstanceOf[String]
      if (locationStr != "localhost") {
        if (HadoopRDD.SPLIT_INFO_REFLECTIONS.get.isInMemory.
                invoke(loc).asInstanceOf[Boolean]) {
          logDebug("Partition " + locationStr + " is cached by Hadoop.")
          out += new HDFSCacheTaskLocation(locationStr).toString
        } else {
          out += new HostTaskLocation(locationStr).toString
        }
      }
    }}
    out.seq
  }
}
