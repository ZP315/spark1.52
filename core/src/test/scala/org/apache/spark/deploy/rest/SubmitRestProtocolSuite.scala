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

package org.apache.spark.deploy.rest

import java.lang.Boolean

import org.json4s.jackson.JsonMethods._

import org.apache.spark.{SparkConf, SparkFunSuite}
import org.apache.spark.util.Utils

/**
 * Tests for the REST application submission protocol.
 * 其余应用程序提交协议的测试
 */
class SubmitRestProtocolSuite extends SparkFunSuite {

  test("validate") {//验证
    val request = new DummyRequest
    //丢失的一切
    intercept[SubmitRestProtocolException] { request.validate() } // missing everything
    request.clientSparkVersion = "1.2.3"
    intercept[SubmitRestProtocolException] { request.validate() } // missing name and age 失踪的姓名和年龄
    request.name = "something"
    intercept[SubmitRestProtocolException] { request.validate() } // missing only age 缺的只是年龄
    request.age = 2
    intercept[SubmitRestProtocolException] { request.validate() } // age too low 年龄太低
    request.age = 10
    request.validate() // everything is set properly 一切都设置正确
    request.clientSparkVersion = null
    //丢失Spark版本
    intercept[SubmitRestProtocolException] { request.validate() } // missing only Spark version
    request.clientSparkVersion = "1.2.3"
    request.name = null
    //缺失名称
    intercept[SubmitRestProtocolException] { request.validate() } // missing only name
    request.message = "not-setting-name"
    intercept[SubmitRestProtocolException] { request.validate() } // still missing name
  }

  test("request to and from JSON") {//从JSON请求
    val request = new DummyRequest
    intercept[SubmitRestProtocolException] { request.toJson } // implicit validation 隐式验证
    request.clientSparkVersion = "1.2.3"
    request.active = true
    request.age = 25
    request.name = "jung"
    val json = request.toJson
    assertJsonEquals(json, dummyRequestJson)
    val newRequest = SubmitRestProtocolMessage.fromJson(json, classOf[DummyRequest])
    assert(newRequest.clientSparkVersion === "1.2.3")
    assert(newRequest.clientSparkVersion === "1.2.3")
    assert(newRequest.active)
    assert(newRequest.age === 25)
    assert(newRequest.name === "jung")
    assert(newRequest.message === null)
  }

  test("response to and from JSON") {//响应来自JSON
    val response = new DummyResponse
    response.serverSparkVersion = "3.3.4"
    response.success = true
    val json = response.toJson
    assertJsonEquals(json, dummyResponseJson)
    //JSON to CreateSubmissionRequest
    val newResponse = SubmitRestProtocolMessage.fromJson(json, classOf[DummyResponse])
    assert(newResponse.serverSparkVersion === "3.3.4")
    assert(newResponse.serverSparkVersion === "3.3.4")
    assert(newResponse.success)
    assert(newResponse.message === null)
  }

  test("CreateSubmissionRequest") {//创建请求提交
    val message = new CreateSubmissionRequest
    intercept[SubmitRestProtocolException] { message.validate() }
    message.clientSparkVersion = "1.2.3"
    message.appResource = "honey-walnut-cherry.jar"
    message.mainClass = "org.apache.spark.examples.SparkPie"
    val conf = new SparkConf(false)
    conf.set("spark.app.name", "SparkPie")
    //conf.getAll.toMap获得所有属性转换成map
    message.sparkProperties = conf.getAll.toMap
    message.validate()
    // optional fields 可选字段,jars多个jar逗号分隔
    conf.set("spark.jars", "mayonnaise.jar,ketchup.jar")
    conf.set("spark.files", "fireball.png")
    conf.set("spark.driver.memory", s"${Utils.DEFAULT_DRIVER_MEM_MB}m")
    conf.set("spark.driver.cores", "180")
    conf.set("spark.driver.extraJavaOptions", " -Dslices=5 -Dcolor=mostly_red")
    conf.set("spark.driver.extraClassPath", "food-coloring.jar")
    conf.set("spark.driver.extraLibraryPath", "pickle.jar")
    conf.set("spark.driver.supervise", "false")
    conf.set("spark.executor.memory", "256m")//分配给每个executor进程总内存
     //当运行在一个独立部署集群上或者是一个粗粒度共享模式的Mesos集群上的时候,最多可以请求多少个CPU核心。默认是所有的都能用
    conf.set("spark.cores.max", "10000")
    message.sparkProperties = conf.getAll.toMap
    message.appArgs = Array("two slices", "a hint of cinnamon")
    message.environmentVariables = Map("PATH" -> "/dev/null")
    message.validate()
    // bad fields 坏字段
    var badConf = conf.clone().set("spark.driver.cores", "one hundred feet")
    message.sparkProperties = badConf.getAll.toMap
    intercept[SubmitRestProtocolException] { message.validate() }
    badConf = conf.clone().set("spark.driver.supervise", "nope, never")
    message.sparkProperties = badConf.getAll.toMap
    intercept[SubmitRestProtocolException] { message.validate() }
    badConf = conf.clone().set("spark.cores.max", "two men")
    message.sparkProperties = badConf.getAll.toMap
    intercept[SubmitRestProtocolException] { message.validate() }
    message.sparkProperties = conf.getAll.toMap
    // test JSON
    val json = message.toJson
    assertJsonEquals(json, submitDriverRequestJson)
    //JSON to CreateSubmissionRequest
    val newMessage = SubmitRestProtocolMessage.fromJson(json, classOf[CreateSubmissionRequest])
    assert(newMessage.clientSparkVersion === "1.2.3")
    assert(newMessage.appResource === "honey-walnut-cherry.jar")
    assert(newMessage.mainClass === "org.apache.spark.examples.SparkPie")
    assert(newMessage.sparkProperties("spark.app.name") === "SparkPie")
    assert(newMessage.sparkProperties("spark.jars") === "mayonnaise.jar,ketchup.jar")
    assert(newMessage.sparkProperties("spark.files") === "fireball.png")
    assert(newMessage.sparkProperties("spark.driver.memory") === s"${Utils.DEFAULT_DRIVER_MEM_MB}m")
    assert(newMessage.sparkProperties("spark.driver.cores") === "180")
    assert(newMessage.sparkProperties("spark.driver.extraJavaOptions") ===
      " -Dslices=5 -Dcolor=mostly_red")
    assert(newMessage.sparkProperties("spark.driver.extraClassPath") === "food-coloring.jar")
    assert(newMessage.sparkProperties("spark.driver.extraLibraryPath") === "pickle.jar")
    assert(newMessage.sparkProperties("spark.driver.supervise") === "false")
    assert(newMessage.sparkProperties("spark.executor.memory") === "256m")//分配给每个executor进程总内存
    assert(newMessage.sparkProperties("spark.cores.max") === "10000")
    assert(newMessage.appArgs === message.appArgs)
    assert(newMessage.sparkProperties === message.sparkProperties)
    assert(newMessage.environmentVariables === message.environmentVariables)
  }

  test("CreateSubmissionResponse") {//创建提交响应
    val message = new CreateSubmissionResponse
    intercept[SubmitRestProtocolException] { message.validate() }
    message.serverSparkVersion = "1.2.3"
    message.submissionId = "driver_123"
    message.success = true
    message.validate()
    // test JSON
    val json = message.toJson
    assertJsonEquals(json, submitDriverResponseJson)
    val newMessage = SubmitRestProtocolMessage.fromJson(json, classOf[CreateSubmissionResponse])
    assert(newMessage.serverSparkVersion === "1.2.3")
    assert(newMessage.submissionId === "driver_123")
    assert(newMessage.success)
  }

  test("KillSubmissionResponse") {//杀死提交响应
    val message = new KillSubmissionResponse
    intercept[SubmitRestProtocolException] { message.validate() }
    message.serverSparkVersion = "1.2.3"
    message.submissionId = "driver_123"
    message.success = true
    message.validate()
    // test JSON
    val json = message.toJson
    assertJsonEquals(json, killDriverResponseJson)
    val newMessage = SubmitRestProtocolMessage.fromJson(json, classOf[KillSubmissionResponse])
    assert(newMessage.serverSparkVersion === "1.2.3")
    assert(newMessage.submissionId === "driver_123")
    assert(newMessage.success)
  }

  test("SubmissionStatusResponse") {//提交状态响应
    val message = new SubmissionStatusResponse
    intercept[SubmitRestProtocolException] { message.validate() }
    message.serverSparkVersion = "1.2.3"
    message.submissionId = "driver_123"
    message.success = true
    message.validate()
    // optional fields
    message.driverState = "RUNNING"
    message.workerId = "worker_123"
    message.workerHostPort = "1.2.3.4:7780"
    // test JSON
    val json = message.toJson
    assertJsonEquals(json, driverStatusResponseJson)
    val newMessage = SubmitRestProtocolMessage.fromJson(json, classOf[SubmissionStatusResponse])
    assert(newMessage.serverSparkVersion === "1.2.3")
    assert(newMessage.submissionId === "driver_123")
    assert(newMessage.driverState === "RUNNING")
    assert(newMessage.success)
    assert(newMessage.workerId === "worker_123")
    assert(newMessage.workerHostPort === "1.2.3.4:7780")
  }

  test("ErrorResponse") {//错误响应
    val message = new ErrorResponse
    intercept[SubmitRestProtocolException] { message.validate() }
    message.serverSparkVersion = "1.2.3"
    message.message = "Field not found in submit request: X"
    message.validate()
    // test JSON
    val json = message.toJson
    assertJsonEquals(json, errorJson)
    val newMessage = SubmitRestProtocolMessage.fromJson(json, classOf[ErrorResponse])
    assert(newMessage.serverSparkVersion === "1.2.3")
    assert(newMessage.message === "Field not found in submit request: X")
  }
  //stripMargin默认是“|”作为出来连接符,在多行换行的行头前面加一个“|”符号即可,创建多行字符串三个双引号包围多行字符串
  private val dummyRequestJson =
    """
      |{
      |  "action" : "DummyRequest",
      |  "active" : true,
      |  "age" : 25,
      |  "clientSparkVersion" : "1.2.3",
      |  "name" : "jung"
      |}
    """.stripMargin

  private val dummyResponseJson =
    """
      |{
      |  "action" : "DummyResponse",
      |  "serverSparkVersion" : "3.3.4",
      |  "success": true
      |}
    """.stripMargin

  private val submitDriverRequestJson =
    s"""
      |{
      |  "action" : "CreateSubmissionRequest",
      |  "appArgs" : [ "two slices", "a hint of cinnamon" ],
      |  "appResource" : "honey-walnut-cherry.jar",
      |  "clientSparkVersion" : "1.2.3",
      |  "environmentVariables" : {
      |    "PATH" : "/dev/null"
      |  },
      |  "mainClass" : "org.apache.spark.examples.SparkPie",
      |  "sparkProperties" : {
      |    "spark.driver.extraLibraryPath" : "pickle.jar",
      |    "spark.jars" : "mayonnaise.jar,ketchup.jar",
      |    "spark.driver.supervise" : "false",
      |    "spark.app.name" : "SparkPie",
      |    "spark.cores.max" : "10000",
      |    "spark.driver.memory" : "${Utils.DEFAULT_DRIVER_MEM_MB}m",
      |    "spark.files" : "fireball.png",
      |    "spark.driver.cores" : "180",
      |    "spark.driver.extraJavaOptions" : " -Dslices=5 -Dcolor=mostly_red",
      |    "spark.executor.memory" : "256m",
      |    "spark.driver.extraClassPath" : "food-coloring.jar"
      |  }
      |}
    """.stripMargin

  private val submitDriverResponseJson =
    """
      |{
      |  "action" : "CreateSubmissionResponse",
      |  "serverSparkVersion" : "1.2.3",
      |  "submissionId" : "driver_123",
      |  "success" : true
      |}
    """.stripMargin

  private val killDriverResponseJson =
    """
      |{
      |  "action" : "KillSubmissionResponse",
      |  "serverSparkVersion" : "1.2.3",
      |  "submissionId" : "driver_123",
      |  "success" : true
      |}
    """.stripMargin

  private val driverStatusResponseJson =
    """
      |{
      |  "action" : "SubmissionStatusResponse",
      |  "driverState" : "RUNNING",
      |  "serverSparkVersion" : "1.2.3",
      |  "submissionId" : "driver_123",
      |  "success" : true,
      |  "workerHostPort" : "1.2.3.4:7780",
      |  "workerId" : "worker_123"
      |}
    """.stripMargin

  private val errorJson =
    """
      |{
      |  "action" : "ErrorResponse",
      |  "message" : "Field not found in submit request: X",
      |  "serverSparkVersion" : "1.2.3"
      |}
    """.stripMargin

  /**
    * Assert that the contents in the two JSON strings are equal after ignoring whitespace.
    * 断言两个JSON字符串中的内容在忽略空格之后是相等的。
    * */
  private def assertJsonEquals(jsonString1: String, jsonString2: String): Unit = {
    val trimmedJson1 = jsonString1.trim
    val trimmedJson2 = jsonString2.trim
    val json1 = compact(render(parse(trimmedJson1)))
    val json2 = compact(render(parse(trimmedJson2)))
    // Put this on a separate line to avoid printing comparison twice when test fails
    //将其放在单独的行上,以避免在测试失败时打印比较两次
    val equals = json1 == json2
    assert(equals, "\"[%s]\" did not equal \"[%s]\"".format(trimmedJson1, trimmedJson2))
  }
}

private class DummyResponse extends SubmitRestProtocolResponse
private class DummyRequest extends SubmitRestProtocolRequest {
  var active: Boolean = null
  var age: Integer = null
  var name: String = null
  protected override def doValidate(): Unit = {
    super.doValidate()
    assertFieldIsSet(name, "name")
    assertFieldIsSet(age, "age")
    assert(age > 5, "Not old enough!")
  }
}
