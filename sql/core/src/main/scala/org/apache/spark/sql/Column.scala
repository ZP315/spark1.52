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

package org.apache.spark.sql

import scala.language.implicitConversions

import org.apache.spark.annotation.Experimental
import org.apache.spark.Logging
import org.apache.spark.sql.functions.lit
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.analysis._
import org.apache.spark.sql.types._


private[sql] object Column {

  def apply(colName: String): Column = new Column(colName)

  def apply(expr: Expression): Column = new Column(expr)

  def unapply(col: Column): Option[Expression] = Some(col.expr)
}


/**
 * :: Experimental ::
 * A column in a [[DataFrame]].
  * [[DataFrame]]中的一列
 *
 * @groupname java_expr_ops Java-specific expression operators
 * @groupname expr_ops Expression operators
 * @groupname df_ops DataFrame functions
 * @groupname Ungrouped Support functions for DataFrames
 *
 * @since 1.3.0
 */
@Experimental
class Column(protected[sql] val expr: Expression) extends Logging {

  def this(name: String) = this(name match {
    case "*" => UnresolvedStar(None)
    case _ if name.endsWith(".*") => UnresolvedStar(Some(name.substring(0, name.length - 2)))
    case _ => UnresolvedAttribute.quotedString(name)
  })

  /** Creates a column based on the given expression.
    * 根据给定的表达式创建列*/
  implicit private def exprToColumn(newExpr: Expression): Column = new Column(newExpr)

  override def toString: String = expr.prettyString

  override def equals(that: Any): Boolean = that match {
    case that: Column => that.expr.equals(this.expr)
    case _ => false
  }

  override def hashCode: Int = this.expr.hashCode

  /**
   * Extracts a value or values from a complex type.
   * 从一个复杂类型中提取一个值或者多个值
   * The following types of extraction are supported:
    * 支持以下类型的提取:
   * - Given an Array, an integer ordinal can be used to retrieve a single value.
    * -给定一个数组,可以使用整数序数来检索单个值
   * - Given a Map, a key of the correct type can be used to retrieve an individual value.
    * -给定Map,可以使用正确类型的键来检索单个值
   * - Given a Struct, a string fieldName can be used to extract that field.
    * -给定Struct,可以使用字符串fieldName来提取该字段
   * - Given an Array of Structs, a string fieldName can be used to extract filed
   *   of every struct in that array, and return an Array of fields
    *  -给定一个Structs数组,可以使用字符串fieldName提取该数组中每个结构的字段,并返回一个字段数组
   *
   * @group expr_ops
   * @since 1.4.0
   */
  def apply(extraction: Any): Column = UnresolvedExtractValue(expr, lit(extraction).expr)

  /**
   * Unary minus, i.e. negate the expression.
   * 一元减号
   * {{{
   *   // Scala: select the amount column and negates all values.
   *   df.select( -df("amount") )
   *
   *   // Java:
   *   import static org.apache.spark.sql.functions.*;
   *   df.select( negate(col("amount") );
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def unary_- : Column = UnaryMinus(expr)

  /**
   * Inversion of boolean expression, i.e. NOT.
   * 布尔表达式的非值
   * {{{
   *   // Scala: select rows that are not active (isActive === false)
   *   df.filter( !df("isActive") )
   *
   *   // Java:
   *   import static org.apache.spark.sql.functions.*;
   *   df.filter( not(df.col("isActive")) );
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def unary_! : Column = Not(expr)

  /**
   * Equality test.
   * 相等测试
   * {{{
   *   // Scala:
   *   df.filter( df("colA") === df("colB") )
   *
   *   // Java
   *   import static org.apache.spark.sql.functions.*;
   *   df.filter( col("colA").equalTo(col("colB")) );
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def === (other: Any): Column = {
    val right = lit(other).expr
    if (this.expr == right) {
      logWarning(
        s"Constructing trivially true equals predicate, '${this.expr} = $right'. " +
          "Perhaps you need to use aliases.")
    }
    EqualTo(expr, right)
  }

  /**
   * Equality test.
    * 相等测试
   * {{{
   *   // Scala:
   *   df.filter( df("colA") === df("colB") )
   *
   *   // Java
   *   import static org.apache.spark.sql.functions.*;
   *   df.filter( col("colA").equalTo(col("colB")) );
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def equalTo(other: Any): Column = this === other

  /**
   * Inequality test.
    * 不等测试
   * {{{
   *   // Scala:
   *   df.select( df("colA") !== df("colB") )
   *   df.select( !(df("colA") === df("colB")) )
   *
   *   // Java:
   *   import static org.apache.spark.sql.functions.*;
   *   df.filter( col("colA").notEqual(col("colB")) );
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def !== (other: Any): Column = Not(EqualTo(expr, lit(other).expr))

  /**
   * Inequality test.
    * 不等测试
   * {{{
   *   // Scala:
   *   df.select( df("colA") !== df("colB") )
   *   df.select( !(df("colA") === df("colB")) )
   *
   *   // Java:
   *   import static org.apache.spark.sql.functions.*;
   *   df.filter( col("colA").notEqual(col("colB")) );
   * }}}
   *
   * @group java_expr_ops
   * @since 1.3.0
   */
  def notEqual(other: Any): Column = Not(EqualTo(expr, lit(other).expr))

  /**
   * Greater than.
   * 大于
   * {{{
   *   // Scala: The following selects people older than 21.
   *   people.select( people("age") > 21 )
   *
   *   // Java:
   *   import static org.apache.spark.sql.functions.*;
   *   people.select( people("age").gt(21) );
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def > (other: Any): Column = GreaterThan(expr, lit(other).expr)

  /**
   * Greater than.大于
   * {{{
   *   // Scala: The following selects people older than 21.
   *   people.select( people("age") > lit(21) )
   *
   *   // Java:
   *   import static org.apache.spark.sql.functions.*;
   *   people.select( people("age").gt(21) );
   * }}}
   *
   * @group java_expr_ops
   * @since 1.3.0
   */
  def gt(other: Any): Column = this > other

  /**
   * Less than.
   * 小于
   * {{{
   *   // Scala: The following selects people younger than 21.
   *   people.select( people("age") < 21 )
   *
   *   // Java:
   *   people.select( people("age").lt(21) );
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def < (other: Any): Column = LessThan(expr, lit(other).expr)

  /**
   * Less than.小于
   * {{{
   *   // Scala: The following selects people younger than 21.
   *   people.select( people("age") < 21 )
   *
   *   // Java:
   *   people.select( people("age").lt(21) );
   * }}}
   *
   * @group java_expr_ops
   * @since 1.3.0
   */
  def lt(other: Any): Column = this < other

  /**
   * Less than or equal to.
   * 小于等于
   * {{{
   *   // Scala: The following selects people age 21 or younger than 21.
   *   people.select( people("age") <= 21 )
   *
   *   // Java:
   *   people.select( people("age").leq(21) );
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def <= (other: Any): Column = LessThanOrEqual(expr, lit(other).expr)

  /**
   * Less than or equal to.
   * 小于等于
   * {{{
   *   // Scala: The following selects people age 21 or younger than 21.
   *   people.select( people("age") <= 21 )
   *
   *   // Java:
   *   people.select( people("age").leq(21) );
   * }}}
   *
   * @group java_expr_ops
   * @since 1.3.0
   */
  def leq(other: Any): Column = this <= other

  /**
   * Greater than or equal to an expression.
   * 大于等于
   * {{{
   *   // Scala: The following selects people age 21 or older than 21.
   *   people.select( people("age") >= 21 )
   *
   *   // Java:
   *   people.select( people("age").geq(21) )
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def >= (other: Any): Column = GreaterThanOrEqual(expr, lit(other).expr)

  /**
   * Greater than or equal to an expression.
   * 大于等于
   * {{{
   *   // Scala: The following selects people age 21 or older than 21.
   *   people.select( people("age") >= 21 )
   *
   *   // Java:
   *   people.select( people("age").geq(21) )
   * }}}
   *
   * @group java_expr_ops
   * @since 1.3.0
   */
  def geq(other: Any): Column = this >= other

  /**
   * Equality test that is safe for null values.
   * 对空值的安全性的相等性测试
   * @group expr_ops
   * @since 1.3.0
   */
  def <=> (other: Any): Column = EqualNullSafe(expr, lit(other).expr)

  /**
   * Equality test that is safe for null values.
   * 对空值的安全性的相等性测试
   * @group java_expr_ops
   * @since 1.3.0
   */
  def eqNullSafe(other: Any): Column = this <=> other

  /**
   * Evaluates a list of conditions and returns one of multiple possible result expressions.
   * If otherwise is not defined at the end, null is returned for unmatched conditions.
   * 评估一个条件列表,并返回多个可能结果表达式的一个,如果没有其他定义,则返回无法匹配的条件
   * {{{
   *   // Example: encoding gender string column into integer.
   *
   *   // Scala:
   *   people.select(when(people("gender") === "male", 0)
   *     .when(people("gender") === "female", 1)
   *     .otherwise(2))
   *
   *   // Java:
   *   people.select(when(col("gender").equalTo("male"), 0)
   *     .when(col("gender").equalTo("female"), 1)
   *     .otherwise(2))
   * }}}
   *
   * @group expr_ops
   * @since 1.4.0
   */
  def when(condition: Column, value: Any): Column = this.expr match {
    case CaseWhen(branches: Seq[Expression]) =>
      CaseWhen(branches ++ Seq(lit(condition).expr, lit(value).expr))
    case _ =>
      throw new IllegalArgumentException(
        "when() can only be applied on a Column previously generated by when() function")
  }

  /**
   * Evaluates a list of conditions and returns one of multiple possible result expressions.
   * If otherwise is not defined at the end, null is returned for unmatched conditions.
   * 评估一个条件列表,并返回多个可能结果表达式的一个,如果没有其他定义,则返回无法匹配的条件
   * {{{
   *   // Example: encoding gender string column into integer.
   *
   *   // Scala:
   *   people.select(when(people("gender") === "male", 0)
   *     .when(people("gender") === "female", 1)
   *     .otherwise(2))
   *
   *   // Java:
   *   people.select(when(col("gender").equalTo("male"), 0)
   *     .when(col("gender").equalTo("female"), 1)
   *     .otherwise(2))
   * }}}
   *
   * @group expr_ops
   * @since 1.4.0
   */
  def otherwise(value: Any): Column = this.expr match {
    case CaseWhen(branches: Seq[Expression]) =>
      if (branches.size % 2 == 0) {
        CaseWhen(branches :+ lit(value).expr)
      } else {
        throw new IllegalArgumentException(
          "otherwise() can only be applied once on a Column previously generated by when()")
      }
    case _ =>
      throw new IllegalArgumentException(
        "otherwise() can only be applied on a Column previously generated by when()")
  }

  /**
   * True if the current column is between the lower bound and upper bound, inclusive.
   * 包含当前列在下限和上限之间
   * @group java_expr_ops
   * @since 1.4.0
   */
  def between(lowerBound: Any, upperBound: Any): Column = {
    (this >= lowerBound) && (this <= upperBound)
  }

  /**
   * True if the current expression is NaN.
   * 如果true当前表达式为NaN
   * @group expr_ops
   * @since 1.5.0
   */
  def isNaN: Column = IsNaN(expr)

  /**
   * True if the current expression is null.
   * 如果True当前表达式为null
   * @group expr_ops
   * @since 1.3.0
   */
  def isNull: Column = IsNull(expr)

  /**
   * True if the current expression is NOT null.
   * 如果True当前表达式为NOT null
   * @group expr_ops
   * @since 1.3.0
   */
  def isNotNull: Column = IsNotNull(expr)

  /**
   * Boolean OR.
   * 布尔或
   * {{{
   *   // Scala: The following selects people that are in school or employed.
   *   people.filter( people("inSchool") || people("isEmployed") )
   *
   *   // Java:
   *   people.filter( people("inSchool").or(people("isEmployed")) );
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def || (other: Any): Column = Or(expr, lit(other).expr)

  /**
   * Boolean OR.
   * 布尔或
   * {{{
   *   // Scala: The following selects people that are in school or employed.
   *   people.filter( people("inSchool") || people("isEmployed") )
   *
   *   // Java:
   *   people.filter( people("inSchool").or(people("isEmployed")) );
   * }}}
   *
   * @group java_expr_ops
   * @since 1.3.0
   */
  def or(other: Column): Column = this || other

  /**
   * Boolean AND.
   * 布尔与
   * {{{
   *   // Scala: The following selects people that are in school and employed at the same time.
   *   people.select( people("inSchool") && people("isEmployed") )
   *
   *   // Java:
   *   people.select( people("inSchool").and(people("isEmployed")) );
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def && (other: Any): Column = And(expr, lit(other).expr)

  /**
   * Boolean AND.
   * 布尔与
   * {{{
   *   // Scala: The following selects people that are in school and employed at the same time.
   *   people.select( people("inSchool") && people("isEmployed") )
   *
   *   // Java:
   *   people.select( people("inSchool").and(people("isEmployed")) );
   * }}}
   *
   * @group java_expr_ops
   * @since 1.3.0
   */
  def and(other: Column): Column = this && other

  /**
   * Sum of this expression and another expression.
   * 此表达式和另一个表达式求和相加
   * {{{
   *   // Scala: The following selects the sum of a person's height and weight.
   *   people.select( people("height") + people("weight") )
   *
   *   // Java:
   *   people.select( people("height").plus(people("weight")) );
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def + (other: Any): Column = Add(expr, lit(other).expr)

  /**
   * Sum of this expression and another expression.
   * 此表达式和另一个表达式求和相加
   * {{{
   *   // Scala: The following selects the sum of a person's height and weight.
   *   people.select( people("height") + people("weight") )
   *
   *   // Java:
   *   people.select( people("height").plus(people("weight")) );
   * }}}
   *
   * @group java_expr_ops
   * @since 1.3.0
   */
  def plus(other: Any): Column = this + other

  /**
   * Subtraction. Subtract the other expression from this expression.
   * 此表达式和另一个表达式相减
   * {{{
   *   // Scala: The following selects the difference between people's height and their weight.
   *   people.select( people("height") - people("weight") )
   *
   *   // Java:
   *   people.select( people("height").minus(people("weight")) );
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def - (other: Any): Column = Subtract(expr, lit(other).expr)

  /**
   * Subtraction. Subtract the other expression from this expression.
   * 此表达式和另一个表达式相减
   * {{{
   *   // Scala: The following selects the difference between people's height and their weight.
   *   people.select( people("height") - people("weight") )
   *
   *   // Java:
   *   people.select( people("height").minus(people("weight")) );
   * }}}
   *
   * @group java_expr_ops
   * @since 1.3.0
   */
  def minus(other: Any): Column = this - other

  /**
   * Multiplication of this expression and another expression.
   * 此表达式和另一个表达式相乘
   * {{{
   *   // Scala: The following multiplies a person's height by their weight.
   *   people.select( people("height") * people("weight") )
   *
   *   // Java:
   *   people.select( people("height").multiply(people("weight")) );
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def * (other: Any): Column = Multiply(expr, lit(other).expr)

  /**
   * Multiplication of this expression and another expression.
   * 此表达式和另一个表达式相乘
   * {{{
   *   // Scala: The following multiplies a person's height by their weight.
   *   people.select( people("height") * people("weight") )
   *
   *   // Java:
   *   people.select( people("height").multiply(people("weight")) );
   * }}}
   *
   * @group java_expr_ops
   * @since 1.3.0
   */
  def multiply(other: Any): Column = this * other

  /**
   * Division this expression by another expression.\
   * 本表达式和另一个表达式相除
   * {{{
   *   // Scala: The following divides a person's height by their weight.
   *   people.select( people("height") / people("weight") )
   *
   *   // Java:
   *   people.select( people("height").divide(people("weight")) );
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def / (other: Any): Column = Divide(expr, lit(other).expr)

  /**
   * Division this expression by another expression.
   * 本表达式和另一个表达式相除
   * {{{
   *   // Scala: The following divides a person's height by their weight.
   *   people.select( people("height") / people("weight") )
   *
   *   // Java:
   *   people.select( people("height").divide(people("weight")) );
   * }}}
   *
   * @group java_expr_ops
   * @since 1.3.0
   */
  def divide(other: Any): Column = this / other

  /**
   * Modulo (a.k.a. remainder) expression.
   * 取余运算
   * @group expr_ops
   * @since 1.3.0
   */
  def % (other: Any): Column = Remainder(expr, lit(other).expr)

  /**
   * Modulo (a.k.a. remainder) expression.
   * 取余运算
   * @group java_expr_ops
   * @since 1.3.0
   */
  def mod(other: Any): Column = this % other

  /**
   * A boolean expression that is evaluated to true if the value of this expression is contained
   * by the evaluated values of the arguments.
   *
   * @group expr_ops
   * @since 1.3.0
   */
  @deprecated("use isin", "1.5.0")
  @scala.annotation.varargs
  def in(list: Any*): Column = isin(list : _*)

  /**
   * A boolean expression that is evaluated to true if the value of this expression is contained
   * by the evaluated values of the arguments.
   * 评价为真的布尔表达式,如果这个表达式的值包含在参数的评价值
   * @group expr_ops
   * @since 1.5.0
   */
  @scala.annotation.varargs
  def isin(list: Any*): Column = In(expr, list.map(lit(_).expr))

  /**
   * SQL like expression.
   *类似SQL的表达式
   * @group expr_ops
   * @since 1.3.0
   */
  def like(literal: String): Column = Like(expr, lit(literal).expr)

  /**
   * SQL RLIKE expression (LIKE with Regex).
   * SQL Rilke正则表达式
   * @group expr_ops
   * @since 1.3.0
   */
  def rlike(literal: String): Column = RLike(expr, lit(literal).expr)

  /**
   * An expression that gets an item at position `ordinal` out of an array,
   * 获取一个在数组位置上的一个项目的表达式
   * or gets a value by key `key` in a [[MapType]].
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def getItem(key: Any): Column = UnresolvedExtractValue(expr, Literal(key))

  /**
   * An expression that gets a field by name in a [[StructType]].
   * 在[[StructType]]中按名称获取字段的表达式
   * @group expr_ops
   * @since 1.3.0
   */
  def getField(fieldName: String): Column = UnresolvedExtractValue(expr, Literal(fieldName))

  /**
   * An expression that returns a substring.
   * substr从字符串中截取 start下标开始的指定数目的字符
   * @param startPos expression for the starting position.
   * @param len expression for the length of the substring.
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def substr(startPos: Column, len: Column): Column = Substring(expr, startPos.expr, len.expr)

  /**
   * An expression that returns a substring.
   * substr从字符串中截取 start下标开始的指定数目的字符
   * @param startPos starting position.
   * @param len length of the substring.
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def substr(startPos: Int, len: Int): Column = Substring(expr, lit(startPos).expr, lit(len).expr)

  /**
   * Contains the other element.
   * 包含其他元素
   * @group expr_ops
   * @since 1.3.0
   */
  def contains(other: Any): Column = Contains(expr, lit(other).expr)

  /**
   * String starts with.
   * 字符串的开始
   * @group expr_ops
   * @since 1.3.0
   */
  def startsWith(other: Column): Column = StartsWith(expr, lit(other).expr)

  /**
   * String starts with another string literal.
   * 在另一个字符串的字符串的开始
   * @group expr_ops
   * @since 1.3.0
   */
  def startsWith(literal: String): Column = this.startsWith(lit(literal))

  /**
   * String ends with.
   *  在另一个字符串的字符串的结束
   * @group expr_ops
   * @since 1.3.0
   */
  def endsWith(other: Column): Column = EndsWith(expr, lit(other).expr)

  /**
   * String ends with another string literal.
   * 字符串以另一个字符串文字结尾
   * @group expr_ops
   * @since 1.3.0
   */
  def endsWith(literal: String): Column = this.endsWith(lit(literal))

  /**
   * Gives the column an alias. Same as `as`.
   * 给列命名别名
   * {{{
   *   // Renames colA to colB in select output.
   *   df.select($"colA".alias("colB"))
   * }}}
   *
   * @group expr_ops
   * @since 1.4.0
   */
  def alias(alias: String): Column = as(alias)

  /**
   * Gives the column an alias.
   * 给列命名别名
   * {{{
   *   // Renames colA to colB in select output.
   *   df.select($"colA".as("colB"))
   * }}}
   *
   * If the current column has metadata associated with it, this metadata will be propagated
   * to the new column.  If this not desired, use `as` with explicitly empty metadata.
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def as(alias: String): Column = expr match {
    case ne: NamedExpression => Alias(expr, alias)(explicitMetadata = Some(ne.metadata))
    case other => Alias(other, alias)()
  }

  /**
   * (Scala-specific) Assigns the given aliases to the results of a table generating function.
   * 指定给定的别名表的生成函数的结果
   * {{{
   *   // Renames colA to colB in select output.
   *   //选择输出重命名colA 到 colB
   *   df.select(explode($"myMap").as("key" :: "value" :: Nil))
   * }}}
   *
   * @group expr_ops
   * @since 1.4.0
   */
  def as(aliases: Seq[String]): Column = MultiAlias(expr, aliases)

  /**
   * Assigns the given aliases to the results of a table generating function.
    * 将给定别名赋给表生成函数的结果
   * {{{
   *   // Renames colA to colB in select output.
   *   df.select(explode($"myMap").as("key" :: "value" :: Nil))
   * }}}
   *
   * @group expr_ops
   * @since 1.4.0
   */
  def as(aliases: Array[String]): Column = MultiAlias(expr, aliases)

  /**
   * Gives the column an alias.
   * 给列一个别名
   * {{{
   *   // Renames colA to colB in select output.
   *   df.select($"colA".as('colB))
   * }}}
   *
   * If the current column has metadata associated with it, this metadata will be propagated
   * to the new column.  If this not desired, use `as` with explicitly empty metadata.
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def as(alias: Symbol): Column = expr match {
    case ne: NamedExpression => Alias(expr, alias.name)(explicitMetadata = Some(ne.metadata))
    case other => Alias(other, alias.name)()
  }

  /**
   * Gives the column an alias with metadata.
   * 给列一个元数据的别名
   * {{{
   *   val metadata: Metadata = ...
   *   df.select($"colA".as("colB", metadata))
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def as(alias: String, metadata: Metadata): Column = {
    Alias(expr, alias)(explicitMetadata = Some(metadata))
  }

  /**
   * Casts the column to a different data type.
   * 将列转换为不同的数据类型
   * {{{
   *   // Casts colA to IntegerType.
   *   import org.apache.spark.sql.types.IntegerType
   *   df.select(df("colA").cast(IntegerType))
   *
   *   // equivalent to
   *   df.select(df("colA").cast("int"))
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def cast(to: DataType): Column = expr match {
    // Lift alias out of cast so we can support col.as("name").cast(IntegerType)
    case Alias(childExpr, name) => Alias(Cast(childExpr, to), name)()
    case _ => Cast(expr, to)
  }

  /**
   * Casts the column to a different data type, using the canonical string representation
   * of the type. The supported types are: `string`, `boolean`, `byte`, `short`, `int`, `long`,
   * `float`, `double`, `decimal`, `date`, `timestamp`.
   * 将列转换为不同的数据类型,使用类型的规范字符串表示形式
   * {{{
   *   // Casts colA to integer.
   *   //强制colA转换int类型
   *   df.select(df("colA").cast("int"))
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def cast(to: String): Column = cast(DataTypeParser.parse(to))

  /**
   * Returns an ordering used in sorting.
   * 返回排序中降序
   * {{{
   *   // Scala: sort a DataFrame by age column in descending order.
   *   df.sort(df("age").desc)
   *
   *   // Java
   *   df.sort(df.col("age").desc());
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def desc: Column = SortOrder(expr, Descending)

  /**
   * Returns an ordering used in sorting.
   * 返回排序中升序
   * {{{
   *   // Scala: sort a DataFrame by age column in ascending order.
   *   df.sort(df("age").asc)
   *
   *   // Java
   *   df.sort(df.col("age").asc());
   * }}}
   *
   * @group expr_ops
   * @since 1.3.0
   */
  def asc: Column = SortOrder(expr, Ascending)

  /**
   * Prints the expression to the console for debugging purpose.
   * 将表达式打印到控制台以进行调试的目的
   * @group df_ops
   * @since 1.3.0
   */
  def explain(extended: Boolean): Unit = {
    // scalastyle:off println
    if (extended) {
      println(expr)
    } else {
      println(expr.prettyString)
    }
    // scalastyle:on println
  }

  /**
   * Compute bitwise OR of this expression with another expression.
   * 计算这个表达式与另一位或表达
   * {{{
   *   df.select($"colA".bitwiseOR($"colB"))
   * }}}
   *
   * @group expr_ops
   * @since 1.4.0
   */
  def bitwiseOR(other: Any): Column = BitwiseOr(expr, lit(other).expr)

  /**
   * Compute bitwise AND of this expression with another expression.
    * 使用另一个表达式计算此表达式的按位AND
   * {{{
   *   df.select($"colA".bitwiseAND($"colB"))
   * }}}
   *
   * @group expr_ops
   * @since 1.4.0
   */
  def bitwiseAND(other: Any): Column = BitwiseAnd(expr, lit(other).expr)

  /**
   * Compute bitwise XOR of this expression with another expression.
    * 使用另一个表达式计算此表达式的按位XOR
   * {{{
   *   df.select($"colA".bitwiseXOR($"colB"))
   * }}}
   *
   * @group expr_ops
   * @since 1.4.0
   */
  def bitwiseXOR(other: Any): Column = BitwiseXor(expr, lit(other).expr)

  /**
   * Define a windowing column.
   * 定义窗口列
   * {{{
   *   val w = Window.partitionBy("name").orderBy("id")
   *   df.select(
   *     sum("price").over(w.rangeBetween(Long.MinValue, 2)),
   *     avg("price").over(w.rowsBetween(0, 4))
   *   )
   * }}}
   *
   * @group expr_ops
   * @since 1.4.0
   */
  def over(window: expressions.WindowSpec): Column = window.withAggregate(this)

}


/**
 * :: Experimental ::
 * A convenient class used for constructing schema.
 * 用于构造模式的方便类
 * @since 1.3.0
 */
@Experimental
class ColumnName(name: String) extends Column(name) {

  /**
   * Creates a new [[StructField]] of type boolean.
    * 创建布尔型的[StuttFiel]
   * @since 1.3.0
   */
  def boolean: StructField = StructField(name, BooleanType)

  /**
   * Creates a new [[StructField]] of type byte.
    * 创建一个byte类型的新[[StructField]]
   * @since 1.3.0
   */
  def byte: StructField = StructField(name, ByteType)

  /**
   * Creates a new [[StructField]] of type short.
    * 创建short类型的新[[StructField]]
   * @since 1.3.0
   */
  def short: StructField = StructField(name, ShortType)

  /**
   * Creates a new [[StructField]] of type int.
    * 创建int类型的新[[StructField]]
   * @since 1.3.0
   */
  def int: StructField = StructField(name, IntegerType)

  /**
   * Creates a new [[StructField]] of type long.
    * 创建long类型的新[StructField]
   * @since 1.3.0
   */
  def long: StructField = StructField(name, LongType)

  /**
   * Creates a new [[StructField]] of type float.
    * 创建一个float类型的新[StructField]
   * @since 1.3.0
   */
  def float: StructField = StructField(name, FloatType)

  /**
   * Creates a new [[StructField]] of type double.
    * 创建double类型的新[[StructField]]
   * @since 1.3.0
   */
  def double: StructField = StructField(name, DoubleType)

  /**
   * Creates a new [[StructField]] of type string.
    * 创建一个string类型的新[[StructField]]
   * @since 1.3.0
   */
  def string: StructField = StructField(name, StringType)

  /**
   * Creates a new [[StructField]] of type date.
    * 创建date类型的新[[StructField]]
   * @since 1.3.0
   */
  def date: StructField = StructField(name, DateType)

  /**
   * Creates a new [[StructField]] of type decimal.
    * 创建十进制类型的新[[StructField]]
   * @since 1.3.0
   */
  def decimal: StructField = StructField(name, DecimalType.USER_DEFAULT)

  /**
   * Creates a new [[StructField]] of type decimal.
    * 创建十进制类型的新[StructField]
   * @since 1.3.0
   */
  def decimal(precision: Int, scale: Int): StructField =
    StructField(name, DecimalType(precision, scale))

  /**
   * Creates a new [[StructField]] of type timestamp.
    * 创建一个timestamp类型的新[[StructField]]
   * @since 1.3.0
   */
  def timestamp: StructField = StructField(name, TimestampType)

  /**
   * Creates a new [[StructField]] of type binary.
    * 创建二进制类型的新[[StructField]]
   * @since 1.3.0
   */
  def binary: StructField = StructField(name, BinaryType)

  /**
   * Creates a new [[StructField]] of type array.
    * 创建一个类型为array的新[[StructField]]
   * @since 1.3.0
   */
  def array(dataType: DataType): StructField = StructField(name, ArrayType(dataType))

  /**
   * Creates a new [[StructField]] of type map.
    * 创建一个类型为map的新[[StructField]]
   * @since 1.3.0
   */
  def map(keyType: DataType, valueType: DataType): StructField =
    map(MapType(keyType, valueType))

  def map(mapType: MapType): StructField = StructField(name, mapType)

  /**
   * Creates a new [[StructField]] of type struct.
    * 创建类型结构的新[StuttFiel]
   * @since 1.3.0
   */
  def struct(fields: StructField*): StructField = struct(StructType(fields))

  /**
   * Creates a new [[StructField]] of type struct.
    * 创建struct类型的新[[StructField]]
   * @since 1.3.0
   */
  def struct(structType: StructType): StructField = StructField(name, structType)
}
