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

package org.apache.spark.deploy.yarn

import java.io.{File, FileOutputStream, OutputStreamWriter}
import java.net.URL
import java.util.Properties
import java.util.concurrent.TimeUnit

import scala.collection.JavaConversions._
import scala.collection.mutable

import com.google.common.base.Charsets.UTF_8
import com.google.common.io.ByteStreams
import com.google.common.io.Files
import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.apache.hadoop.yarn.server.MiniYARNCluster
import org.scalatest.{BeforeAndAfterAll, Matchers}

import org.apache.spark._
import org.apache.spark.scheduler.cluster.ExecutorInfo
import org.apache.spark.scheduler.{SparkListener, SparkListenerApplicationStart,
  SparkListenerExecutorAdded}
import org.apache.spark.util.Utils

/**
 * Integration tests for YARN; these tests use a mini Yarn cluster to run Spark-on-YARN
 * applications, and require the Spark assembly to be built before they can be successfully
 * run.
  * YARN集成测试;这些测试使用迷你Yarn群集来运行Spark-on-YARN应用程序,并且要求Spark程序集可以在其成功运行之前被构建。
 */
class YarnClusterSuite extends SparkFunSuite with BeforeAndAfterAll with Matchers with Logging {

  // log4j configuration for the YARN containers, so that their output is collected
  // by YARN instead of trying to overwrite unit-tests.log.
  //log4j配置为YARN容器,以便它们的输出由YARN收集,而不是尝试覆盖unit-tests.log
  private val LOG4J_CONF = """
    |log4j.rootCategory=DEBUG, console
    |log4j.appender.console=org.apache.log4j.ConsoleAppender
    |log4j.appender.console.target=System.err
    |log4j.appender.console.layout=org.apache.log4j.PatternLayout
    |log4j.appender.console.layout.ConversionPattern=%d{yy/MM/dd HH:mm:ss} %p %c{1}: %m%n
    """.stripMargin

  private val TEST_PYFILE = """
    |import mod1, mod2
    |import sys
    |from operator import add
    |
    |from pyspark import SparkConf , SparkContext
    |if __name__ == "__main__":
    |    if len(sys.argv) != 2:
    |        print >> sys.stderr, "Usage: test.py [result file]"
    |        exit(-1)
    |    sc = SparkContext(conf=SparkConf())
    |    status = open(sys.argv[1],'w')
    |    result = "failure"
    |    rdd = sc.parallelize(range(10)).map(lambda x: x * mod1.func() * mod2.func())
    |    cnt = rdd.count()
    |    if cnt == 10:
    |        result = "success"
    |    status.write(result)
    |    status.close()
    |    sc.stop()
    """.stripMargin

  private val TEST_PYMODULE = """
    |def func():
    |    return 42
    """.stripMargin

  private var yarnCluster: MiniYARNCluster = _
  private var tempDir: File = _
  private var fakeSparkJar: File = _
  private var hadoopConfDir: File = _
  private var logConfDir: File = _

  override def beforeAll() {
    super.beforeAll()

    tempDir = Utils.createTempDir()
    logConfDir = new File(tempDir, "log4j")
    logConfDir.mkdir()
    System.setProperty("SPARK_YARN_MODE", "true")
    //文件目录位置
    val logConfFile = new File(logConfDir, "log4j.properties")
    //写文件,LOG4J_CONF文件内容
    Files.write(LOG4J_CONF, logConfFile, UTF_8)

    yarnCluster = new MiniYARNCluster(getClass().getName(), 1, 1, 1)
    yarnCluster.init(new YarnConfiguration())
    yarnCluster.start()

    // There's a race in MiniYARNCluster in which start() may return before the RM has updated
    //
    // its address in the configuration. You can see this in the logs by noticing that when
    // MiniYARNCluster prints the address, it still has port "0" assigned, although later the
    // test works sometimes:
    //MiniYARNCluster中存在竞争，其中start()可能在RM更新其配置中的地址之前返回。
    //您可以在日志中看到这一点,注意到当MiniYARNCluster打印地址时,它仍然分配了端口“0”,尽管稍后测试工作有时：
    //
    //    INFO MiniYARNCluster: MiniYARN ResourceManager address: blah:0
    //
    // That log message prints the contents of the RM_ADDRESS config variable. If you check it
    // later on, it looks something like this:
    //
    //    INFO YarnClusterSuite: RM address in configuration is blah:42631
    //
    // This hack loops for a bit waiting for the port to change, and fails the test if it hasn't
    // done so in a timely manner (defined to be 10 seconds).
    //这个hack循环等待端口改变,如果没有及时(定义为10秒),则测试失败。
    val config = yarnCluster.getConfig()
    val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10)
    while (config.get(YarnConfiguration.RM_ADDRESS).split(":")(1) == "0") {
      if (System.currentTimeMillis() > deadline) {
        throw new IllegalStateException("Timed out waiting for RM to come up.")
      }
      logDebug("RM address still not set in configuration, waiting...")
      TimeUnit.MILLISECONDS.sleep(100)
    }

    logInfo(s"RM address in configuration is ${config.get(YarnConfiguration.RM_ADDRESS)}")

    fakeSparkJar = File.createTempFile("sparkJar", null, tempDir)
    hadoopConfDir = new File(tempDir, Client.LOCALIZED_CONF_DIR)
    assert(hadoopConfDir.mkdir())
    File.createTempFile("token", ".txt", hadoopConfDir)
  }

  override def afterAll() {
    yarnCluster.stop()
    System.clearProperty("SPARK_YARN_MODE")
    super.afterAll()
  }
  //在yarn客户端模式下运行Spark
  ignore("run Spark in yarn-client mode") {
    testBasicYarnApp(true)
  }
  //在yarn群集模式下运行Spark
  ignore("run Spark in yarn-cluster mode") {
    testBasicYarnApp(false)
  }
  //在yarn群集模式下运行Spark失败
  ignore("run Spark in yarn-cluster mode unsuccessfully") {
    // Don't provide arguments so the driver will fail.
    //不提供参数，因此驱动程序将失败。
    val exception = intercept[SparkException] {
      runSpark(false, mainClassName(YarnClusterDriver.getClass))
      fail("Spark application should have failed.")
    }
  }
  //以yarn客户端模式运行Python应用程序
  ignore("run Python application in yarn-client mode") {
    testPySpark(true)
  }

  ignore("run Python application in yarn-cluster mode") {
    testPySpark(false)
  }
  //用户类路径首先在客户端模式
  ignore("user class path first in client mode") {
    testUseClassPathFirst(true)
  }
  //用户类路径首先在群集模式
  ignore("user class path first in cluster mode") {
    testUseClassPathFirst(false)
  }

  private def testBasicYarnApp(clientMode: Boolean): Unit = {
    var result = File.createTempFile("result", null, tempDir)
    runSpark(clientMode, mainClassName(YarnClusterDriver.getClass),
      appArgs = Seq(result.getAbsolutePath()))
    checkResult(result)
  }

  private def testPySpark(clientMode: Boolean): Unit = {
    val primaryPyFile = new File(tempDir, "test.py")
    Files.write(TEST_PYFILE, primaryPyFile, UTF_8)

    val moduleDir =
      if (clientMode) {
        // In client-mode, .py files added with --py-files are not visible in the driver.
        // This is something that the launcher library would have to handle.
        tempDir
      } else {
        val subdir = new File(tempDir, "pyModules")
        subdir.mkdir()
        subdir
      }
    val pyModule = new File(moduleDir, "mod1.py")
    Files.write(TEST_PYMODULE, pyModule, UTF_8)

    val mod2Archive = TestUtils.createJarWithFiles(Map("mod2.py" -> TEST_PYMODULE), moduleDir)
    val pyFiles = Seq(pyModule.getAbsolutePath(), mod2Archive.getPath()).mkString(",")
    val result = File.createTempFile("result", null, tempDir)

    runSpark(clientMode, primaryPyFile.getAbsolutePath(),
      sparkArgs = Seq("--py-files", pyFiles),
      appArgs = Seq(result.getAbsolutePath()))
    checkResult(result)
  }
  //测试首先使用类路径
  private def testUseClassPathFirst(clientMode: Boolean): Unit = {
    // Create a jar file that contains a different version of "test.resource".
    //创建一个包含不同版本的“测试资源”的JAR文件
    val originalJar = TestUtils.createJarWithFiles(Map("test.resource" -> "ORIGINAL"), tempDir)
    val userJar = TestUtils.createJarWithFiles(Map("test.resource" -> "OVERRIDDEN"), tempDir)
    val driverResult = File.createTempFile("driver", null, tempDir)
    val executorResult = File.createTempFile("executor", null, tempDir)
    runSpark(clientMode, mainClassName(YarnClasspathTest.getClass),
      appArgs = Seq(driverResult.getAbsolutePath(), executorResult.getAbsolutePath()),
      extraClassPath = Seq(originalJar.getPath()),
      extraJars = Seq("local:" + userJar.getPath()),
      extraConf = Map(
        "spark.driver.userClassPathFirst" -> "true",
        "spark.executor.userClassPathFirst" -> "true"))
    checkResult(driverResult, "OVERRIDDEN")
    checkResult(executorResult, "OVERRIDDEN")
  }

  private def runSpark(
      clientMode: Boolean,
      klass: String,
      appArgs: Seq[String] = Nil,
      sparkArgs: Seq[String] = Nil,
      extraClassPath: Seq[String] = Nil,
      extraJars: Seq[String] = Nil,
      extraConf: Map[String, String] = Map()): Unit = {
    val master = if (clientMode) "yarn-client" else "yarn-cluster"
    val props = new Properties()

    props.setProperty("spark.yarn.jar", "local:" + fakeSparkJar.getAbsolutePath())

    val childClasspath = logConfDir.getAbsolutePath() +
      File.pathSeparator +
      //System.getenv()和System.getProperties()的区别
      //System.getenv() 返回系统环境变量值 设置系统环境变量：当前登录用户主目录下的".bashrc"文件中可以设置系统环境变量
      //System.getProperties() 返回Java进程变量值 通过命令行参数的"-D"选项
      sys.props("java.class.path") +
      File.pathSeparator +
      extraClassPath.mkString(File.pathSeparator)
    props.setProperty("spark.driver.extraClassPath", "")
    props.setProperty("spark.executor.extraClassPath", childClasspath)

    // SPARK-4267: make sure java options are propagated correctly.
    props.setProperty("spark.driver.extraJavaOptions", "-Dfoo=\"one two three\"")
    props.setProperty("spark.executor.extraJavaOptions", "-Dfoo=\"one two three\"")

    yarnCluster.getConfig().foreach { e =>{
      println("key:"+e.getKey()+"\t value:"+e.getValue())
      props.setProperty("spark.hadoop." + e.getKey(), e.getValue())
    }

    }
    //System.getenv()和System.getProperties()的区别
    //System.getenv() 返回系统环境变量值 设置系统环境变量：当前登录用户主目录下的".bashrc"文件中可以设置系统环境变量
    //System.getProperties() 返回Java进程变量值 通过命令行参数的"-D"选项
    sys.props.foreach { case (k, v) =>
      if (k.startsWith("spark.")) {
        props.setProperty(k, v)
      }
    }

    extraConf.foreach { case (k, v) => props.setProperty(k, v) }

    val propsFile = File.createTempFile("spark", ".properties", tempDir)
    val writer = new OutputStreamWriter(new FileOutputStream(propsFile), UTF_8)
    props.store(writer, "Spark properties.")
    writer.close()

    val extraJarArgs = if (!extraJars.isEmpty()) Seq("--jars", extraJars.mkString(",")) else Nil
    val mainArgs =
      if (klass.endsWith(".py")) {
        Seq(klass)
      } else {
        Seq("--class", klass, fakeSparkJar.getAbsolutePath())
      }
    //System.getenv()和System.getProperties()的区别
    //System.getenv() 返回系统环境变量值 设置系统环境变量：当前登录用户主目录下的".bashrc"文件中可以设置系统环境变量
    //System.getProperties() 返回Java进程变量值 通过命令行参数的"-D"选项
    sys.env.getOrElse("spark.test.home", "/software/spark2.1/")
    val argv =
      Seq(
        //设置环境变量SparkHome
        //System.getenv()和System.getProperties()的区别
        //System.getenv() 返回系统环境变量值 设置系统环境变量：当前登录用户主目录下的".bashrc"文件中可以设置系统环境变量
        //System.getProperties() 返回Java进程变量值 通过命令行参数的"-D"选项
        new File(sys.props("spark.test.home"), "/bin/spark-submit").getAbsolutePath(),
        "--master", master,
        "--num-executors", "1",
        "--properties-file", propsFile.getAbsolutePath()) ++
      extraJarArgs ++
      sparkArgs ++
      mainArgs ++
      appArgs

    Utils.executeAndGetOutput(argv,
      extraEnvironment = Map("YARN_CONF_DIR" -> hadoopConfDir.getAbsolutePath()))
  }

  /**
   * This is a workaround for an issue with yarn-cluster mode: the Client class will not provide
   * any sort of error when the job process finishes successfully, but the job itself fails. So
   * the tests enforce that something is written to a file after everything is ok to indicate
   * that the job succeeded.
    * 这是针对yarn群集模式出现问题的解决方法：当作业进程成功完成时,Client类不会提供任何类型的错误,
    * 但作业本身失败,所以这些测试在一切都可以指示工作成功之后,强制将某些东西写入文件。
   */
  private def checkResult(result: File): Unit = {
    checkResult(result, "success")
  }

  private def checkResult(result: File, expected: String): Unit = {
    var resultString = Files.toString(result, UTF_8)
    resultString should be (expected)
  }

  private def mainClassName(klass: Class[_]): String = {
    //stripSuffix去掉<string>字串中结尾的字符
    klass.getName().stripSuffix("$")
  }

}

private[spark] class SaveExecutorInfo extends SparkListener {
  val addedExecutorInfos = mutable.Map[String, ExecutorInfo]()
  var driverLogs: Option[collection.Map[String, String]] = None

  override def onExecutorAdded(executor: SparkListenerExecutorAdded) {
    addedExecutorInfos(executor.executorId) = executor.executorInfo
  }

  override def onApplicationStart(appStart: SparkListenerApplicationStart): Unit = {
    driverLogs = appStart.driverLogs
  }
}

private object YarnClusterDriver extends Logging with Matchers {

  val WAIT_TIMEOUT_MILLIS = 10000

  def main(args: Array[String]): Unit = {
//    args(0)="/home/liush"
    if (args.length != 1) {
      // scalastyle:off println
      System.err.println(
        s"""
        |Invalid command line: ${args.mkString(" ")}
        |
        |Usage: YarnClusterDriver [result file]
        """.stripMargin)
      // scalastyle:on println
      System.exit(1)
    }

    val sc = new SparkContext(new SparkConf()
      .set("spark.extraListeners", classOf[SaveExecutorInfo].getName)
      .setAppName("yarn \"test app\" 'with quotes' and \\back\\slashes and $dollarSigns"))
    val conf = sc.getConf
    val status = new File(args(0))
    var result = "failure"
    try {
      val data = sc.parallelize(1 to 4, 4).collect().toSet
      sc.listenerBus.waitUntilEmpty(WAIT_TIMEOUT_MILLIS)
      data should be (Set(1, 2, 3, 4))
      result = "success"
    } finally {
      sc.stop()
      Files.write(result, status, UTF_8)
    }

    // verify log urls are present
    val listeners = sc.listenerBus.findListenersByClass[SaveExecutorInfo]
    assert(listeners.size === 1)
    val listener = listeners(0)
    val executorInfos = listener.addedExecutorInfos.values
    assert(executorInfos.nonEmpty)
    executorInfos.foreach { info =>
      assert(info.logUrlMap.nonEmpty)
    }

    // If we are running in yarn-cluster mode, verify that driver logs links and present and are
    // in the expected format.
    //如果我们在yarn群集模式下运行,请验证驱动程序是否记录链接并显示并且处于预期的格式。
    if (conf.get("spark.master") == "yarn-cluster") {
      assert(listener.driverLogs.nonEmpty)
      val driverLogs = listener.driverLogs.get
      assert(driverLogs.size === 2)
      assert(driverLogs.containsKey("stderr"))
      assert(driverLogs.containsKey("stdout"))
      val urlStr = driverLogs("stderr")
      // Ensure that this is a valid URL, else this will throw an exception
      //保这是一个有效的URL,否则这将抛出异常
      new URL(urlStr)
      val containerId = YarnSparkHadoopUtil.get.getContainerId
      val user = Utils.getCurrentUserName()
      assert(urlStr.endsWith(s"/node/containerlogs/$containerId/$user/stderr?start=-4096"))
    }
  }

}

private object YarnClasspathTest extends Logging {

  var exitCode = 0

  def error(m: String, ex: Throwable = null): Unit = {
    logError(m, ex)
    // scalastyle:off println
    System.out.println(m)
    if (ex != null) {
      ex.printStackTrace(System.out)
    }
    // scalastyle:on println
  }

  def main(args: Array[String]): Unit = {
    if (args.length != 2) {
      error(
        s"""
        |Invalid command line: ${args.mkString(" ")}
        |
        |Usage: YarnClasspathTest [driver result file] [executor result file]
        """.stripMargin)
      // scalastyle:on println
    }

    readResource(args(0))
    val sc = new SparkContext(new SparkConf())
    try {
      sc.parallelize(Seq(1)).foreach { x => readResource(args(1)) }
    } finally {
      sc.stop()
    }
    System.exit(exitCode)
  }

  private def readResource(resultPath: String): Unit = {
    var result = "failure"
    try {
      //Thread.currentThread().getContextClassLoader,可以获取当前线程的引用,getContextClassLoader用来获取线程的上下文类加载器
      val ccl = Thread.currentThread().getContextClassLoader()
      val resource = ccl.getResourceAsStream("test.resource")
      val bytes = ByteStreams.toByteArray(resource)
      result = new String(bytes, 0, bytes.length, UTF_8)
    } catch {
      case t: Throwable =>
        error(s"loading test.resource to $resultPath", t)
        // set the exit code if not yet set
        exitCode = 2
    } finally {
      Files.write(result, new File(resultPath), UTF_8)
    }
  }

}
