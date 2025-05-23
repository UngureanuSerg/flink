/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.api

import org.apache.flink.annotation.PublicEvolving
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.table.connector.source.abilities.SupportsSourceWatermark
import org.apache.flink.table.expressions.{ApiExpressionUtils, Expression, TableSymbol, TimePointUnit}
import org.apache.flink.table.expressions.ApiExpressionUtils.{unresolvedCall, unresolvedRef, valueLiteral}
import org.apache.flink.table.functions._
import org.apache.flink.table.functions.BuiltInFunctionDefinitions.{DISTINCT, RANGE_TO}
import org.apache.flink.table.types.DataType
import org.apache.flink.types.Row

import java.lang.{Boolean => JBoolean, Byte => JByte, Double => JDouble, Float => JFloat, Integer => JInteger, Long => JLong, Short => JShort}
import java.math.{BigDecimal => JBigDecimal}
import java.sql.{Date, Time, Timestamp}
import java.time.{LocalDate, LocalDateTime, LocalTime}
import java.util.{List => JList, Map => JMap}

import scala.language.experimental.macros
import scala.language.implicitConversions

/**
 * Implicit conversions from Scala literals to [[Expression]] and from [[Expression]] to
 * [[ImplicitExpressionOperations]].
 *
 * @deprecated
 *   All Flink Scala APIs are deprecated and will be removed in a future Flink major version. You
 *   can still build your application in Scala, but you should move to the Java version of either
 *   the DataStream and/or Table API.
 * @see
 *   <a href="https://s.apache.org/flip-265">FLIP-265 Deprecate and remove Scala API support</a>
 */
@Deprecated
@PublicEvolving
trait ImplicitExpressionConversions {

  // ----------------------------------------------------------------------------------------------
  // Implicit values
  // ----------------------------------------------------------------------------------------------

  /**
   * Offset constant to be used in the `preceding` clause of unbounded [[Over]] windows. Use this
   * constant for a time interval. Unbounded over windows start with the first row of a partition.
   */
  implicit val UNBOUNDED_ROW: Expression = lit(OverWindowRange.UNBOUNDED_ROW)

  /**
   * Offset constant to be used in the `preceding` clause of unbounded [[Over]] windows. Use this
   * constant for a row-count interval. Unbounded over windows start with the first row of a
   * partition.
   */
  implicit val UNBOUNDED_RANGE: Expression = lit(OverWindowRange.UNBOUNDED_RANGE)

  /**
   * Offset constant to be used in the `following` clause of [[Over]] windows. Use this for setting
   * the upper bound of the window to the current row.
   */
  implicit val CURRENT_ROW: Expression = lit(OverWindowRange.CURRENT_ROW)

  /**
   * Offset constant to be used in the `following` clause of [[Over]] windows. Use this for setting
   * the upper bound of the window to the sort key of the current row, i.e., all rows with the same
   * sort key as the current row are included in the window.
   */
  implicit val CURRENT_RANGE: Expression = lit(OverWindowRange.CURRENT_RANGE)

  // ----------------------------------------------------------------------------------------------
  // Implicit conversions
  // ----------------------------------------------------------------------------------------------

  implicit class WithOperations(e: Expression) extends ImplicitExpressionOperations {
    def expr: Expression = e
  }

  implicit class UnresolvedFieldExpression(s: Symbol) extends ImplicitExpressionOperations {
    def expr: Expression = unresolvedRef(s.name)
  }

  implicit class AnyWithOperations[T](e: T)(implicit toExpr: T => Expression)
    extends ImplicitExpressionOperations {
    def expr: Expression = toExpr(e)
  }

  implicit class LiteralLongExpression(l: Long) extends ImplicitExpressionOperations {
    def expr: Expression = valueLiteral(l)
  }

  implicit class LiteralByteExpression(b: Byte) extends ImplicitExpressionOperations {
    def expr: Expression = valueLiteral(b)
  }

  implicit class LiteralShortExpression(s: Short) extends ImplicitExpressionOperations {
    def expr: Expression = valueLiteral(s)
  }

  implicit class LiteralIntExpression(i: Int) extends ImplicitExpressionOperations {
    def expr: Expression = valueLiteral(i)
  }

  implicit class LiteralFloatExpression(f: Float) extends ImplicitExpressionOperations {
    def expr: Expression = valueLiteral(f)
  }

  implicit class LiteralDoubleExpression(d: Double) extends ImplicitExpressionOperations {
    def expr: Expression = valueLiteral(d)
  }

  implicit class LiteralStringExpression(str: String) extends ImplicitExpressionOperations {
    def expr: Expression = valueLiteral(str)
  }

  implicit class LiteralBooleanExpression(bool: Boolean) extends ImplicitExpressionOperations {
    def expr: Expression = valueLiteral(bool)
  }

  implicit class LiteralJavaDecimalExpression(javaDecimal: JBigDecimal)
    extends ImplicitExpressionOperations {
    def expr: Expression = valueLiteral(javaDecimal)
  }

  implicit class LiteralScalaDecimalExpression(scalaDecimal: BigDecimal)
    extends ImplicitExpressionOperations {
    def expr: Expression = valueLiteral(scalaDecimal.bigDecimal)
  }

  implicit class LiteralSqlDateExpression(sqlDate: Date) extends ImplicitExpressionOperations {
    def expr: Expression = valueLiteral(sqlDate)
  }

  implicit class LiteralSqlTimeExpression(sqlTime: Time) extends ImplicitExpressionOperations {
    def expr: Expression = valueLiteral(sqlTime)
  }

  implicit class LiteralSqlTimestampExpression(sqlTimestamp: Timestamp)
    extends ImplicitExpressionOperations {
    def expr: Expression = valueLiteral(sqlTimestamp)
  }

  implicit class ScalarFunctionCall(val s: ScalarFunction) {

    /** Calls a scalar function for the given parameters. */
    def apply(params: Expression*): Expression = {
      unresolvedCall(s, params.map(ApiExpressionUtils.objectToExpression): _*)
    }
  }

  implicit class TableFunctionCall(val t: TableFunction[_]) {

    /** Calls a table function for the given parameters. */
    def apply(params: Expression*): Expression = {
      unresolvedCall(t, params.map(ApiExpressionUtils.objectToExpression): _*)
    }
  }

  implicit class ImperativeAggregateFunctionCall[T: TypeInformation, ACC: TypeInformation](
      val a: ImperativeAggregateFunction[T, ACC]) {

    private def createFunctionDefinition(): FunctionDefinition = {
      val resultTypeInfo: TypeInformation[T] = UserDefinedFunctionHelper
        .getReturnTypeOfAggregateFunction(a, implicitly[TypeInformation[T]])

      val accTypeInfo: TypeInformation[ACC] = UserDefinedFunctionHelper
        .getAccumulatorTypeOfAggregateFunction(a, implicitly[TypeInformation[ACC]])

      a match {
        case af: AggregateFunction[_, _] =>
          new AggregateFunctionDefinition(af.getClass.getName, af, resultTypeInfo, accTypeInfo)
        case taf: TableAggregateFunction[_, _] =>
          new TableAggregateFunctionDefinition(
            taf.getClass.getName,
            taf,
            resultTypeInfo,
            accTypeInfo)
      }
    }

    /** Calls an aggregate function for the given parameters. */
    def apply(params: Expression*): Expression = {
      unresolvedCall(
        createFunctionDefinition(),
        params.map(ApiExpressionUtils.objectToExpression): _*)
    }

    /** Calculates the aggregate results only for distinct values. */
    def distinct(params: Expression*): Expression = {
      unresolvedCall(DISTINCT, apply(params: _*))
    }
  }

  /**
   * Extends Scala's StringContext with a method for creating an unresolved reference via string
   * interpolation.
   */
  implicit class FieldExpression(val sc: StringContext) {

    /**
     * Creates an unresolved reference to a table's column.
     *
     * Example:
     * {{{
     * tab.select($"key", $"value")
     * }}}
     */
    def $(args: Any*): Expression = unresolvedRef(sc.s(args: _*))
  }

  implicit def tableSymbolToExpression(sym: TableSymbol): Expression =
    valueLiteral(sym)

  implicit def symbol2FieldExpression(sym: Symbol): Expression =
    unresolvedRef(sym.name)

  implicit def scalaRange2RangeExpression(range: Range.Inclusive): Expression = {
    val startExpression = valueLiteral(range.start)
    val endExpression = valueLiteral(range.end)
    unresolvedCall(RANGE_TO, startExpression, endExpression)
  }

  implicit def byte2Literal(b: Byte): Expression = valueLiteral(b)

  implicit def byte2Literal(b: JByte): Expression = valueLiteral(b)

  implicit def short2Literal(s: Short): Expression = valueLiteral(s)

  implicit def short2Literal(s: JShort): Expression = valueLiteral(s)

  implicit def int2Literal(i: Int): Expression = valueLiteral(i)

  implicit def int2Literal(i: JInteger): Expression = valueLiteral(i)

  implicit def long2Literal(l: Long): Expression = valueLiteral(l)

  implicit def long2Literal(l: JLong): Expression = valueLiteral(l)

  implicit def double2Literal(d: Double): Expression = valueLiteral(d)

  implicit def double2Literal(d: JDouble): Expression = valueLiteral(d)

  implicit def float2Literal(d: Float): Expression = valueLiteral(d)

  implicit def float2Literal(d: JFloat): Expression = valueLiteral(d)

  implicit def string2Literal(str: String): Expression = valueLiteral(str)

  implicit def boolean2Literal(bool: Boolean): Expression = valueLiteral(bool)

  implicit def boolean2Literal(bool: JBoolean): Expression = valueLiteral(bool)

  implicit def javaDec2Literal(javaDec: JBigDecimal): Expression = valueLiteral(javaDec)

  implicit def scalaDec2Literal(scalaDec: BigDecimal): Expression =
    valueLiteral(scalaDec.bigDecimal)

  implicit def sqlDate2Literal(sqlDate: Date): Expression = valueLiteral(sqlDate)

  implicit def sqlTime2Literal(sqlTime: Time): Expression = valueLiteral(sqlTime)

  implicit def sqlTimestamp2Literal(sqlTimestamp: Timestamp): Expression =
    valueLiteral(sqlTimestamp)

  implicit def localDate2Literal(localDate: LocalDate): Expression = valueLiteral(localDate)

  implicit def localTime2Literal(localTime: LocalTime): Expression = valueLiteral(localTime)

  implicit def localDateTime2Literal(localDateTime: LocalDateTime): Expression =
    valueLiteral(localDateTime)

  implicit def javaList2ArrayConstructor(jList: JList[_]): Expression = {
    ApiExpressionUtils.objectToExpression(jList)
  }

  implicit def seq2ArrayConstructor(seq: Seq[_]): Expression = {
    ApiExpressionUtils.objectToExpression(seq)
  }

  implicit def array2ArrayConstructor(array: Array[_]): Expression = {
    ApiExpressionUtils.objectToExpression(array)
  }

  implicit def javaMap2MapConstructor(map: JMap[_, _]): Expression = {
    ApiExpressionUtils.objectToExpression(map)
  }

  implicit def map2MapConstructor(map: Map[_, _]): Expression = {
    ApiExpressionUtils.objectToExpression(map)
  }

  implicit def row2RowConstructor(rowObject: Row): Expression = {
    ApiExpressionUtils.objectToExpression(rowObject)
  }

  // ----------------------------------------------------------------------------------------------
  // Function calls
  // ----------------------------------------------------------------------------------------------

  /**
   * A call to a function that will be looked up in a catalog. There are two kinds of functions:
   *
   *   - System functions - which are identified with one part names
   *   - Catalog functions - which are identified always with three parts names (catalog, database,
   *     function)
   *
   * Moreover each function can either be a temporary function or permanent one (which is stored in
   * a catalog).
   *
   * Based on those two properties, the resolution order for looking up a function based on the
   * provided path is as follows:
   *
   *   - Temporary system function
   *   - System function
   *   - Temporary catalog function
   *   - Catalog function
   *
   * @see
   *   TableEnvironment#useCatalog(String)
   * @see
   *   TableEnvironment#useDatabase(String)
   * @see
   *   TableEnvironment#createTemporaryFunction
   * @see
   *   TableEnvironment#createTemporarySystemFunction
   */
  def call(path: String, params: Expression*): Expression = Expressions.call(path, params: _*)

  /**
   * A call to an unregistered, inline function. For functions that have been registered before and
   * are identified by a name, use [[call(String, Object...)]].
   */
  def call(function: UserDefinedFunction, params: Expression*): Expression =
    Expressions.call(function, params: _*)

  /**
   * A call to an unregistered, inline function. For functions that have been registered before and
   * are identified by a name, use [[call(String, Object...)]].
   */
  def call(function: Class[_ <: UserDefinedFunction], params: Expression*): Expression =
    Expressions.call(function, params: _*)

  /**
   * A call to a SQL expression.
   *
   * The given string is parsed and translated into an [[Expression]] during planning. Only the
   * translated expression is evaluated during runtime.
   *
   * Note: Currently, calls are limited to simple scalar expressions. Calls to aggregate or
   * table-valued functions are not supported. Sub-queries are also not allowed.
   */
  def callSql(sqlExpression: String): Expression = Expressions.callSql(sqlExpression)

  // ----------------------------------------------------------------------------------------------
  // Implicit expressions in prefix notation
  // ----------------------------------------------------------------------------------------------

  /**
   * Creates an unresolved reference to a table's column.
   *
   * For example:
   *
   * ```
   * tab.select($("key"), $("value"))
   * ```
   *
   * This method is useful in cases where the field name is calculated and the recommended way of
   * using string interpolation like `$"key"` would be inconvenient.
   */
  def $(name: String): Expression = Expressions.$(name)

  /**
   * Creates an unresolved reference to a table's column.
   *
   * For example:
   *
   * ```
   * tab.select(col("key"), col("value"))
   * ```
   *
   * This method is a synonym of [[$(String)]] for cases where a method name containing a dollar
   * sign would be inconvenient.
   */
  def col(name: String): Expression = Expressions.col(name)

  /**
   * Creates a SQL literal.
   *
   * The data type is derived from the object's class and its value.
   *
   * For example:
   *
   *   - `lit(12)`` leads to `INT`
   *   - `lit("abc")`` leads to `CHAR(3)`
   *   - `lit(new java.math.BigDecimal("123.45"))` leads to `DECIMAL(5, 2)`
   *
   * See [[org.apache.flink.table.types.utils.ValueDataTypeConverter]] for a list of supported
   * literal values.
   */
  def lit(v: Any): Expression = Expressions.lit(v)

  /**
   * Creates a SQL literal of a given [[DataType]].
   *
   * The method [[lit(Object)]] is preferred as it extracts the [[DataType]] automatically. The
   * class of `v` must be supported according to the
   * [[org.apache.flink.table.types.logical.LogicalType#supportsInputConversion(Class)]].
   */
  def lit(v: Any, dataType: DataType): Expression = Expressions.lit(v, dataType)

  /** Returns negative numeric. */
  def negative(v: Expression): Expression = {
    Expressions.negative(v)
  }

  /**
   * Returns the current SQL date in local time zone, the return type of this expression is
   * [[DataTypes.DATE]].
   */
  def currentDate(): Expression = {
    Expressions.currentDate()
  }

  /**
   * Returns the current SQL time in local time zone, the return type of this expression is
   * [[DataTypes.TIME]].
   */
  def currentTime(): Expression = {
    Expressions.currentTime()
  }

  /**
   * Returns the current SQL timestamp in local time zone, the return type of this expression is
   * [[DataTypes.TIMESTAMP_LTZ()]].
   */
  def currentTimestamp(): Expression = {
    Expressions.currentTimestamp()
  }

  /**
   * Returns the current watermark for the given rowtime attribute, or `NULL` if no common watermark
   * of all upstream operations is available at the current operation in the pipeline.
   *
   * The function returns the watermark with the same type as the rowtime attribute, but with an
   * adjusted precision of 3. For example, if the rowtime attribute is
   * [[DataTypes.TIMESTAMP_LTZ(int) TIMESTAMP_LTZ(9)]], the function will return
   * [[DataTypes.TIMESTAMP_LTZ(int) TIMESTAMP_LTZ(3)]].
   *
   * If no watermark has been emitted yet, the function will return `NULL`. Users must take care of
   * this when comparing against it, e.g. in order to filter out late data you can use
   *
   * {{{
   * WHERE CURRENT_WATERMARK(ts) IS NULL OR ts > CURRENT_WATERMARK(ts)
   * }}}
   */
  def currentWatermark(rowtimeAttribute: Expression): Expression = {
    Expressions.currentWatermark(rowtimeAttribute)
  }

  /** Return the current database, the return type of this expression is [[DataTypes.STRING()]]. */
  def currentDatabase(): Expression = Expressions.currentDatabase()

  /**
   * Returns the current SQL time in local time zone, the return type of this expression is
   * [[DataTypes.TIME]], this is a synonym for [[ImplicitExpressionConversions.currentTime()]].
   */
  def localTime(): Expression = {
    Expressions.localTime()
  }

  /**
   * Returns the current SQL timestamp in local time zone, the return type of this expression is
   * [[DataTypes.TIMESTAMP]].
   */
  def localTimestamp(): Expression = {
    Expressions.localTimestamp()
  }

  /**
   * Converts the date string with format 'yyyy-MM-dd' to a date value of [[DataTypes.DATE]] type.
   */
  def toDate(dateStr: Expression): Expression = {
    Expressions.toDate(dateStr)
  }

  /**
   * Converts the date string with the specified format to a date value of [[DataTypes.DATE]] type.
   */
  def toDate(dateStr: Expression, format: Expression): Expression = {
    Expressions.toDate(dateStr, format)
  }

  /**
   * Converts the date time string with format 'yyyy-MM-dd HH:mm:ss' under the 'UTC+0' time zone to
   * a timestamp value of [[DataTypes.TIMESTAMP]].
   */
  def toTimestamp(timestampStr: Expression): Expression = {
    Expressions.toTimestamp(timestampStr)
  }

  /**
   * Converts the date time string with the specified format under the 'UTC+0' time zone to a
   * timestamp value of [[DataTypes.TIMESTAMP]].
   */
  def toTimestamp(timestampStr: Expression, format: Expression): Expression = {
    Expressions.toTimestamp(timestampStr, format)
  }

  /**
   * Determines whether two anchored time intervals overlap. Time point and temporal are transformed
   * into a range defined by two time points (start, end). The function evaluates <code>leftEnd >=
   * rightStart && rightEnd >= leftStart</code>.
   *
   * It evaluates: leftEnd >= rightStart && rightEnd >= leftStart
   *
   * e.g. temporalOverlaps("2:55:00".toTime, 1.hour, "3:30:00".toTime, 2.hour) leads to true
   */
  def temporalOverlaps(
      leftTimePoint: Expression,
      leftTemporal: Expression,
      rightTimePoint: Expression,
      rightTemporal: Expression): Expression = {
    Expressions.temporalOverlaps(leftTimePoint, leftTemporal, rightTimePoint, rightTemporal)
  }

  /**
   * Formats a timestamp as a string using a specified format. The format must be compatible with
   * MySQL's date formatting syntax as used by the date_parse function.
   *
   * For example dataFormat('time, "%Y, %d %M") results in strings formatted as "2017, 05 May".
   *
   * @param timestamp
   *   The timestamp to format as string.
   * @param format
   *   The format of the string.
   * @return
   *   The formatted timestamp as string.
   */
  def dateFormat(timestamp: Expression, format: Expression): Expression = {
    Expressions.dateFormat(timestamp, format)
  }

  /**
   * Returns the (signed) number of [[TimePointUnit]] between timePoint1 and timePoint2.
   *
   * For example, timestampDiff(TimePointUnit.DAY, '2016-06-15'.toDate, '2016-06-18'.toDate leads to
   * 3.
   *
   * @param timePointUnit
   *   The unit to compute diff.
   * @param timePoint1
   *   The first point in time.
   * @param timePoint2
   *   The second point in time.
   * @return
   *   The number of intervals as integer value.
   */
  def timestampDiff(
      timePointUnit: TimePointUnit,
      timePoint1: Expression,
      timePoint2: Expression): Expression = {
    Expressions.timestampDiff(timePointUnit, timePoint1, timePoint2)
  }

  /**
   * Converts a datetime dateStr (with default ISO timestamp format 'yyyy-MM-dd HH:mm:ss') from time
   * zone tzFrom to time zone tzTo. The format of time zone should be either an abbreviation such as
   * "PST", a full name such as "America/Los_Angeles", or a custom ID such as "GMT-08:00". E.g.,
   * convertTz('1970-01-01 00:00:00', 'UTC', 'America/Los_Angeles') returns '1969-12-31 16:00:00'.
   *
   * @param dateStr
   *   dateStr the date time string
   * @param tzFrom
   *   tzFrom the original time zone
   * @param tzTo
   *   tzTo the target time zone
   * @return
   *   The formatted timestamp as string.
   */
  def convertTz(dateStr: Expression, tzFrom: Expression, tzTo: Expression): ApiExpression = {
    Expressions.convertTz(dateStr, tzFrom, tzTo)
  }

  /**
   * Convert unix timestamp (seconds since '1970-01-01 00:00:00' UTC) to datetime string in the
   * "yyyy-MM-dd HH:mm:ss" format.
   */
  def fromUnixtime(unixtime: Expression): Expression = Expressions.fromUnixtime(unixtime)

  /**
   * Convert unix timestamp (seconds since '1970-01-01 00:00:00' UTC) to datetime string in the
   * given format.
   */
  def fromUnixtime(unixtime: Expression, format: Expression): Expression =
    Expressions.fromUnixtime(unixtime, format)

  /**
   * Gets the current unix timestamp in seconds. This function is not deterministic which means the
   * value would be recalculated for each record.
   */
  def unixTimestamp(): Expression = Expressions.unixTimestamp()

  /**
   * Converts the given date time string with format 'yyyy-MM-dd HH:mm:ss' to unix timestamp (in
   * seconds), using the time zone specified in the table config.
   */
  def unixTimestamp(timestampStr: Expression): Expression = {
    Expressions.unixTimestamp(timestampStr)
  }

  /**
   * Converts the given date time string with the specified format to unix timestamp (in seconds),
   * using the specified timezone in table config.
   */
  def unixTimestamp(timestampStr: Expression, format: Expression): Expression = {
    Expressions.unixTimestamp(timestampStr, format)
  }

  /** Creates an array of literals. */
  def array(head: Expression, tail: Expression*): Expression = {
    Expressions.array(head, tail: _*)
  }

  /** Creates a row of expressions. */
  def row(head: Expression, tail: Expression*): Expression = {
    Expressions.row(head, tail: _*)
  }

  /** Creates a map of expressions. */
  def map(key: Expression, value: Expression, tail: Expression*): Expression = {
    Expressions.map(key, value, tail: _*)
  }

  /** Creates a map from an array of keys and an array of values. */
  def mapFromArrays(key: Expression, value: Expression): Expression = {
    Expressions.mapFromArrays(key, value)
  }

  /** Returns a value that is closer than any other value to pi. */
  def pi(): Expression = {
    Expressions.pi()
  }

  /** Returns a value that is closer than any other value to e. */
  def e(): Expression = {
    Expressions.e()
  }

  /** Returns a pseudorandom double value between 0.0 (inclusive) and 1.0 (exclusive). */
  def rand(): Expression = {
    Expressions.rand()
  }

  /**
   * Returns a pseudorandom double value between 0.0 (inclusive) and 1.0 (exclusive) with a initial
   * seed. Two rand() functions will return identical sequences of numbers if they have same initial
   * seed.
   */
  def rand(seed: Expression): Expression = {
    Expressions.rand(seed)
  }

  /**
   * Returns a pseudorandom integer value between 0.0 (inclusive) and the specified value
   * (exclusive).
   */
  def randInteger(bound: Expression): Expression = {
    Expressions.randInteger(bound)
  }

  /**
   * Returns a pseudorandom integer value between 0.0 (inclusive) and the specified value
   * (exclusive) with a initial seed. Two randInteger() functions will return identical sequences of
   * numbers if they have same initial seed and same bound.
   */
  def randInteger(seed: Expression, bound: Expression): Expression = {
    Expressions.randInteger(seed, bound)
  }

  /**
   * Returns the string that results from concatenating the arguments. Returns NULL if any argument
   * is NULL.
   */
  def concat(string: Expression, strings: Expression*): Expression = {
    Expressions.concat(string, strings: _*)
  }

  /** Calculates the arc tangent of a given coordinate. */
  def atan2(y: Expression, x: Expression): Expression = {
    Expressions.atan2(y, x)
  }

  /**
   * Returns the string that results from concatenating the arguments and separator. Returns NULL If
   * the separator is NULL.
   *
   * Note: This function does not skip empty strings. However, it does skip any NULL values after
   * the separator argument.
   * @deprecated
   *   use [[ImplicitExpressionConversions.concatWs()]]
   */
  @deprecated
  def concat_ws(separator: Expression, string: Expression, strings: Expression*): Expression = {
    concatWs(separator, string, strings: _*)
  }

  /**
   * Returns the string that results from concatenating the arguments and separator. Returns NULL If
   * the separator is NULL.
   *
   * Note: this user-defined function does not skip empty strings. However, it does skip any NULL
   * values after the separator argument.
   */
  def concatWs(separator: Expression, string: Expression, strings: Expression*): Expression = {
    Expressions.concatWs(separator, string, strings: _*)
  }

  /**
   * Returns an UUID (Universally Unique Identifier) string (e.g.,
   * "3d3c68f7-f608-473f-b60c-b0c44ad4cc4e") according to RFC 4122 type 4 (pseudo randomly
   * generated) UUID. The UUID is generated using a cryptographically strong pseudo random number
   * generator.
   */
  def uuid(): Expression = {
    Expressions.uuid()
  }

  /**
   * Returns a null literal value of a given data type.
   *
   * e.g. nullOf(DataTypes.INT())
   */
  def nullOf(dataType: DataType): Expression = {
    Expressions.nullOf(dataType)
  }

  /**
   * @deprecated
   *   This method will be removed in future versions as it uses the old type system. It is
   *   recommended to use [[nullOf(DataType)]] instead which uses the new type system based on
   *   [[DataTypes]]. Please make sure to use either the old or the new type system consistently to
   *   avoid unintended behavior. See the website documentation for more information.
   */
  def nullOf(typeInfo: TypeInformation[_]): Expression = {
    Expressions.nullOf(typeInfo)
  }

  /** Calculates the logarithm of the given value. */
  def log(value: Expression): Expression = {
    Expressions.log(value)
  }

  /** Calculates the logarithm of the given value to the given base. */
  def log(base: Expression, value: Expression): Expression = {
    Expressions.log(base, value)
  }

  /**
   * Source watermark declaration for [[Schema]].
   *
   * This is a marker function that doesn't have concrete runtime implementation. It can only be
   * used as a single expression in [[Schema.Builder#watermark(String, Expression)]]. The
   * declaration will be pushed down into a table source that implements the
   * [[SupportsSourceWatermark]] interface. The source will emit system-defined watermarks
   * afterwards.
   *
   * Please check the documentation whether the connector supports source watermarks.
   */
  def sourceWatermark(): Expression = {
    Expressions.sourceWatermark()
  }

  /**
   * Ternary conditional operator that decides which of two other expressions should be evaluated
   * based on a evaluated boolean condition.
   *
   * e.g. ifThenElse(42 > 5, "A", "B") leads to "A"
   *
   * @param condition
   *   boolean condition
   * @param ifTrue
   *   expression to be evaluated if condition holds
   * @param ifFalse
   *   expression to be evaluated if condition does not hold
   */
  def ifThenElse(condition: Expression, ifTrue: Expression, ifFalse: Expression): Expression = {
    Expressions.ifThenElse(condition, ifTrue, ifFalse)
  }

  /**
   * Returns the first argument that is not NULL.
   *
   * If all arguments are NULL, it returns NULL as well. The return type is the least restrictive,
   * common type of all of its arguments. The return type is nullable if all arguments are nullable
   * as well.
   *
   * Examples:
   * {{{
   * // Returns "default"
   * coalesce(null, "default")
   *
   * // Returns the first non-null value among f0 and f1, or "default" if f0 and f1 are both null
   * coalesce($"f0", $"f1", "default")
   * }}}
   *
   * @param args
   *   the input expressions.
   */
  def coalesce(args: Expression*): Expression = {
    Expressions.coalesce(args: _*)
  }

  /**
   * Creates an expression that selects a range of columns. It can be used wherever an array of
   * expression is accepted such as function calls, projections, or groupings.
   *
   * A range can either be index-based or name-based. Indices start at 1 and boundaries are
   * inclusive.
   *
   * e.g. withColumns('b to 'c) or withColumns('*)
   */
  def withColumns(head: Expression, tail: Expression*): Expression = {
    Expressions.withColumns(head, tail: _*)
  }

  /**
   * Creates an expression that selects all columns except for the given range of columns. It can be
   * used wherever an array of expression is accepted such as function calls, projections, or
   * groupings.
   *
   * A range can either be index-based or name-based. Indices start at 1 and boundaries are
   * inclusive.
   *
   * e.g. withoutColumns('b to 'c) or withoutColumns('c)
   */
  def withoutColumns(head: Expression, tail: Expression*): Expression = {
    Expressions.withoutColumns(head, tail: _*)
  }

  /** Boolean AND in three-valued logic. */
  def and(predicate0: Expression, predicate1: Expression, predicates: Expression*): Expression = {
    Expressions.and(predicate0, predicate1, predicates: _*)
  }

  /** Boolean OR in three-valued logic. */
  def or(predicate0: Expression, predicate1: Expression, predicates: Expression*): Expression = {
    Expressions.or(predicate0, predicate1, predicates: _*)
  }

  /**
   * Inverts a given boolean expression.
   *
   * This method supports a three-valued logic by preserving <code>NULL</code>. This means if the
   * input expression is <code>NULL</code>, the result will also be <code>NULL</code>.
   *
   * The resulting type is nullable if and only if the input type is nullable.
   *
   * Examples:
   *
   * {{{
   * not(lit(true)) // false
   * not(lit(false)) // true
   * not(lit(null, DataTypes.BOOLEAN())) // null
   * }}}
   */
  def not(expression: Expression): Expression = Expressions.not(expression)

  /**
   * Serializes a value into JSON.
   *
   * This function returns a JSON string containing the serialized value. If the value is `null`,
   * the function returns `null`.
   *
   * Examples:
   * {{{
   * // null
   * jsonString(nullOf(DataTypes.INT()))
   *
   * jsonString(1)                   // "1"
   * jsonString(true)                // "true"
   * jsonString("Hello, World!")     // "\"Hello, World!\""
   * jsonString(Arrays.asList(1, 2)) // "[1,2]"
   * }}}
   */
  def jsonString(value: Expression): Expression = {
    Expressions.jsonString(value)
  }

  /**
   * Builds a JSON object string from a list of key-value pairs.
   *
   * `keyValues` is an even-numbered list of alternating key/value pairs. Note that keys must be
   * string literals, values may be arbitrary expressions.
   *
   * This function returns a JSON string. The [[JsonOnNull onNull]] behavior defines how to treat
   * `NULL` values.
   *
   * Values which are created from another JSON construction function call (`jsonObject`,
   * `jsonArray`) are inserted directly rather than as a string. This allows building nested JSON
   * structures.
   *
   * Examples:
   * {{{
   * // {}
   * jsonObject(JsonOnNull.NULL)
   * // {"K1":"V1","K2":"V2"}
   * jsonObject(JsonOnNull.NULL, "K1", "V1", "K2", "V2")
   *
   * // Expressions as values
   * jsonObject(JsonOnNull.NULL, "orderNo", $("orderId"))
   *
   * // ON NULL
   * jsonObject(JsonOnNull.NULL, "K1", nullOf(DataTypes.STRING()))   // "{\"K1\":null}"
   * jsonObject(JsonOnNull.ABSENT, "K1", nullOf(DataTypes.STRING())) // '{}'
   *
   * // {"K1":{"K2":"V"}}
   * jsonObject(JsonOnNull.NULL, "K1", jsonObject(JsonOnNull.NULL, "K2", "V"))
   * }}}
   *
   * @see
   *   #jsonObject
   */
  def jsonObject(onNull: JsonOnNull, keyValues: Expression*): Expression = {
    Expressions.jsonObject(onNull, keyValues: _*)
  }

  /**
   * Builds a JSON object string by aggregating key-value expressions into a single JSON object.
   *
   * The key expression must return a non-nullable character string. Value expressions can be
   * arbitrary, including other JSON functions. If a value is `NULL`, the [[JsonOnNull onNull]]
   * behavior defines what to do.
   *
   * Note that keys must be unique. If a key occurs multiple times, an error will be thrown.
   *
   * This function is currently not supported in `OVER` windows.
   *
   * Examples:
   * {{{
   * // "{\"Apple\":2,\"Banana\":17,\"Orange\":0}"
   * orders.select(jsonObjectAgg(JsonOnNull.NULL, $("product"), $("cnt")))
   * }}}
   *
   * @see
   *   #jsonObject
   */
  def jsonObjectAgg(onNull: JsonOnNull, keyExpr: Expression, valueExpr: Expression): Expression = {
    Expressions.jsonObjectAgg(onNull, keyExpr, valueExpr)
  }

  /**
   * Builds a JSON array string from a list of values.
   *
   * This function returns a JSON string. The values can be arbitrary expressions. The
   * [[JsonOnNull onNull]] behavior defines how to treat `NULL` values.
   *
   * Elements which are created from another JSON construction function call (`jsonObject`,
   * `jsonArray`) are inserted directly rather than as a string. This allows building nested JSON
   * structures.
   *
   * Examples:
   *
   * {{{
   * // "[]"
   * jsonArray(JsonOnNull.NULL)
   * // "[1,\"2\"]"
   * jsonArray(JsonOnNull.NULL, 1, "2")
   *
   * // Expressions as values
   * jsonArray(JsonOnNull.NULL, $("orderId"))
   *
   * // ON NULL
   * jsonArray(JsonOnNull.NULL, nullOf(DataTypes.STRING()))   // "[null]"
   * jsonArray(JsonOnNull.ABSENT, nullOf(DataTypes.STRING())) // "[]"
   *
   * // "[[1]]"
   * jsonArray(JsonOnNull.NULL, jsonArray(JsonOnNull.NULL, 1))
   * }}}
   *
   * @see
   *   #jsonObject
   */
  def jsonArray(onNull: JsonOnNull, values: Expression*): Expression = {
    Expressions.jsonArray(onNull, values: _*)
  }

  /**
   * Builds a JSON object string by aggregating items into an array.
   *
   * Item expressions can be arbitrary, including other JSON functions. If a value is `NULL`,
   * [[JsonOnNull onNull]] behavior defines what to do.
   *
   * This function is currently not supported in `OVER` windows, unbounded session windows, or hop
   * windows.
   *
   * Examples:
   * {{{
   * // "[\"Apple\",\"Banana\",\"Orange\"]"
   * orders.select(jsonArrayAgg(JsonOnNull.NULL, $("product")))
   * }}}
   *
   * @see
   *   #jsonObject
   */
  def jsonArrayAgg(onNull: JsonOnNull, itemExpr: Expression): Expression = {
    Expressions.jsonArrayAgg(onNull, itemExpr)
  }
}
