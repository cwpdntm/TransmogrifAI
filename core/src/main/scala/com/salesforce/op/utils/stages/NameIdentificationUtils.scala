/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.salesforce.op.utils.stages

import com.salesforce.op.features.types.{NameStats, Text}
import com.salesforce.op.stages.impl.feature.TextTokenizer
import com.salesforce.op.utils.json.JsonLike
import com.twitter.algebird.Operators._
import com.twitter.algebird.macros.caseclass._
import com.twitter.algebird.{AveragedGroup, AveragedValue, Moments, Monoid}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.internal.Logging
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{Column, Dataset}
import org.apache.spark.util.SparkUtils.{averageBoolCol, extractDouble}

import scala.io.Source
import scala.util.Try
import scala.util.matching.Regex

/**
 * Provides shared helper functions and variables (namely, broadcast dictionaries) for name identification
 * and name to gender transformation.
 * @tparam T     the FeatureType (subtype of Text) to operate over
 */
private[op] trait NameIdentificationFun[T <: Text] extends Logging {
  import com.salesforce.op.utils.stages.NameIdentificationUtils._

  def preProcess(s: T#Value): Seq[String] = {
    TextTokenizer.tokenize(Text(s)).tokens.toArray
  }

  def guardChecks(dataset: Dataset[T#Value], column: Column, timeout: Int = 1000): Boolean = {
    val spark = dataset.sparkSession
    import spark.implicits._

    val total = dataset.rdd.countApprox(timeout = timeout).getFinalValue().mean
    val numUnique = extractDouble(dataset.select(approx_count_distinct(column).as[Double]))
    val checks = List(
      // check that in at least 3/4 of the texts there are no more than 10 tokens
      averageBoolCol(dataset.select(
        (size(split(column, "\\s+")) < 10).alias(column.toString).as[Boolean]
      ), column) > 0.75,
      // check that at least 3/4 of the texts are longer than 3 characters
      averageBoolCol(dataset.select(
        (length(column) > 3).alias(column.toString).as[Boolean]
      ), column) > 0.75,
      // check that the standard deviation of the text length is greater than a small number
      total < 10 ||
        extractDouble(dataset.select(stddev(length(column)).as[Double])) > 0.05,
      // check that the number of unique entries is at least 10
      total < 100 || numUnique > 10
    )
    checks.forall(identity)
  }

  def dictCheck(tokens: Seq[String], dict: Broadcast[NameDictionary]): Double = {
    tokens.map({ token: String => if (dict.value.value contains token) 1 else 0}).sum.toDouble / tokens.length
  }

  def getNameFromCustomIndex(tokens: Seq[String], index: Int): String = {
    if (tokens.length != 1) {
      // Mod to accept -1 as valid index
      tokens((index + tokens.length) % tokens.length)
    } else tokens.head
  }

  import NameStats.GenderStrings._
  def identifyGender(tokens: Seq[String], index: Int, dict: Broadcast[GenderDictionary]): String = {
    val nameToCheckGenderOf = if (tokens.length != 1) {
      // Mod to accept -1 as valid index
      tokens((index + tokens.length) % tokens.length)
    } else tokens.head
    dict.value.value.get(nameToCheckGenderOf).map(
      probMale => if (probMale >= 0.5) Male else Female
    ).getOrElse(GenderNA)
  }

  def computeGuardCheckQuantities(s: T#Value, tokens: Seq[String]): GuardCheckStats = {
    // TODO: Make params out of these numbers
    GuardCheckStats(
      countBelowMaxNumTokens = if (tokens.length > 10) 1 else 0,
      countAboveMinCharLength = if (s.getOrElse("").length > 3) 1 else 0,
      approxMomentsOfNumTokens = Moments(tokens.length),
      approxNumUnique = 1
    )
  }

  def computeResultsByStrategy(
    s: T#Value,
    tokens: Seq[String],
    nameDict: Broadcast[NameDictionary],
    genderDict: Broadcast[GenderDictionary]
  ): Map[String, GenderStats] = {
    NameDetectStrategies map { strategy: NameDetectStrategy =>
      val genderResult: String = strategy match {
        case NameDetectStrategy.ByIndex(index) => identifyGender(tokens, index, genderDict)
        case _ =>
          sys.error("Not yet implemented")
          "Not yet implemented"
      }
      strategy.toString -> GenderStats(
        if (genderResult == Male) 1 else 0,
        if (genderResult == Female) 1 else 0,
        if (genderResult == GenderNA) 1 else 0
      )
    } toMap
  }

  def computeResults(
    s: T#Value,
    nameDict: Broadcast[NameDictionary],
    genderDict: Broadcast[GenderDictionary]
  ): NameDetectStats = {
    val tokens = preProcess(s)
    NameDetectStats(
      computeGuardCheckQuantities(s, tokens),
      AveragedValue(dictCheck(tokens, nameDict)),
      computeResultsByStrategy(s, tokens, nameDict, genderDict)
    )
  }
}

/**
 * Defines static values for name identification:
 * - Dictionary filenames and how to read them in
 * - Which parts of a string to check for first name (used in transforming from name to gender)
 *
 * Name and gender data are maintained by and taken from this repository:
 *  https://github.com/MWYang/InternationalNames
 * which itself sources data from:
 *  https://ec.europa.eu/jrc/en/language-technologies/jrc-names (currently unused)
 *  https://github.com/OpenGenderTracking/globalnamedata
 *  https://github.com/first20hours/google-10000-english
 */
private[op] object NameIdentificationUtils {
  case class NameDictionary
  (
    // Use the following line to use the smaller but less noisy gender dictionary as a source for names
    // value: Set[String] = GenderDictionary().value.keySet
    value: Set[String] = {
      val nameDictionary = collection.mutable.Set.empty[String]
      val dictionaryPath = "/Names_JRC_Combined.txt"
      val stream = getClass.getResourceAsStream(dictionaryPath)
      val buffer = Source.fromInputStream(stream)
      for {name <- buffer.getLines} {
        nameDictionary += name
      }
      buffer.close
      nameDictionary.toSet[String]
    }
  )

  case class GenderDictionary
  (
    value: Map[String, Double] = {
      val genderDictionary = collection.mutable.Map.empty[String, Double]
      val dictionaryPath = "/GenderDictionary_USandUK.csv"
      val stream = getClass.getResourceAsStream(dictionaryPath)
      val buffer = Source.fromInputStream(stream)
      // TODO: Also make use of frequency information in this dictionary
      for {row <- buffer.getLines.drop(1)} {
        val cols = row.split(",").map(_.trim)
        val name = cols(0).toLowerCase().replace("\\P{L}", "")
        val probMale = Try {
          cols(6).toDouble
        }.toOption
        probMale match {
          case Some(prob) => genderDictionary += (name -> prob)
          case None =>
        }
      }
      buffer.close
      genderDictionary.toMap[String, Double]
    }
  )

  val NameDetectStrategies: Seq[NameDetectStrategy] = Seq(
    NameDetectStrategy.ByIndex(0), NameDetectStrategy.ByIndex(-1)
  )
}

private[op] case class GuardCheckStats
(
  countBelowMaxNumTokens: Int = 0,
  countAboveMinCharLength: Int = 0,
  approxMomentsOfNumTokens: Moments = Moments(0.0),
  approxNumUnique: Int = 0
)

private[op] case class GenderStats(numMale: Int = 0, numFemale: Int = 0, numOther: Int = 0)

// TODO: Make proper documentation
// Defines the monoid accumulator for detecting names
private[op] case class NameDetectStats
(
  guardCheckQuantities: GuardCheckStats,
  dictCheckResult: AveragedValue,
  genderResultsByStrategy: Map[String, GenderStats]
) extends JsonLike

private[op] object NameDetectStats {
  def monoid: Monoid[NameDetectStats] = new Monoid[NameDetectStats] {
    override def plus(l: NameDetectStats, r: NameDetectStats): NameDetectStats = l + r
    override def zero: NameDetectStats = NameDetectStats.empty
  }

  def empty: NameDetectStats = {
    NameDetectStats(GuardCheckStats(), AveragedGroup.zero, Map.empty[String, GenderStats])
  }
}

import enumeratum._
private[op] sealed class NameDetectStrategy extends EnumEntry
case object NameDetectStrategy extends Enum[NameDetectStrategy] {
  val values: Seq[NameDetectStrategy] = findValues
  case class ByIndex(index: Int) extends NameDetectStrategy {
    override def toString: String = f"ByIndex($index)"
  }
  case class ByRegex(pattern: Regex) extends NameDetectStrategy
  case class FindSalutation() extends NameDetectStrategy
}
