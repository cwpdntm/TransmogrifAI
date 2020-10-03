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

package com.salesforce.op

import com.salesforce.op.features.OPFeature
import com.salesforce.op.filters.{FeatureDistribution, FilteredRawData, RawFeatureFilter, Summary}
import com.salesforce.op.readers.Reader
import com.salesforce.op.stages.OPStage
import com.salesforce.op.stages.impl.feature.TimePeriod
import com.salesforce.op.stages.impl.preparators.CorrelationType
import com.salesforce.op.stages.impl.selector.ModelSelector
import com.salesforce.op.utils.reflection.ReflectionUtils
import com.salesforce.op.utils.spark.{JobGroupUtil, OpStep}
import com.salesforce.op.utils.spark.RichDataset._
import com.salesforce.op.utils.stages.FitStagesUtil
import com.salesforce.op.utils.stages.FitStagesUtil.{CutDAG, FittedDAG, Layer, StagesDAG}
import enumeratum.{Enum, EnumEntry}
import org.apache.spark.annotation.Experimental
import org.apache.spark.ml.{Estimator, Transformer}
import org.apache.spark.sql.{DataFrame, SparkSession}

import scala.collection.mutable.{MutableList => MList}
import scala.util.{Failure, Success, Try}


/**
 * Workflow for TransmogrifAI. Takes the final features that the user wants to generate as inputs and
 * constructs the full DAG needed to generate them from those features lineage. Then fits any estimators in the
 * pipeline dag to create a sequence of transformations that are saved in a workflow model.
 *
 * @param uid unique id for the workflow
 */
class OpWorkflow(val uid: String = UID[OpWorkflow]) extends OpWorkflowCore {

  // raw feature filter stage which can be used in place of a reader
  private[op] var rawFeatureFilter: Option[RawFeatureFilter[_]] = None

  // result feature retention policy
  private[op] var resultFeaturePolicy: ResultFeatureRetention = ResultFeatureRetention.Strict

  /**
   * Set stage and reader parameters from OpWorkflowParams object for run
   *
   * @param newParams new parameter values
   * @return this workflow
   */
  final def setParameters(newParams: OpParams): this.type = {
    parameters = newParams
    if (stages.nonEmpty) setStageParameters(stages)
    this
  }

  /**
   * This is used to set the stages of the workflow.
   *
   * By setting the final features the stages used to
   * generate them can be traced back through the parent features and origin stages.
   * The input is a tuple of features to support leaf feature generation (multiple endpoints in feature generation).
   *
   * @param features Final features generated by the workflow
   */
  def setResultFeatures(features: OPFeature*): this.type = {
    val featuresArr = features.toArray
    resultFeatures = featuresArr
    rawFeatures = featuresArr.flatMap(_.rawFeatures).distinct.sortBy(_.name)
    checkUnmatchedFeatures()
    setStagesDAG(features = featuresArr)
    validateStages()

    if (log.isDebugEnabled) {
      log.debug(s"\nDependency graphs resolved into a stage sequence of:\n{}",
        getStages().map(s =>
          s" ${s.uid}[${s.getInputFeatures().map(_.name).mkString(",")}] --> ${s.getOutputFeatureName}"
        ).mkString("\n")
      )
      log.debug("*" * 80)
      log.debug("Result features:")
      resultFeatures.foreach(feature => log.debug(s"${feature.name}:\n${feature.prettyParentStages}"))
      log.debug("*" * 80)
    }
    this
  }


  /**
   * Will set the blocklisted features variable and if list is non-empty it will
   * @param features list of features to blocklist
   * @param distributions feature distributions calculated in raw feature filter
   */
  private[op] def setBlocklist(features: Array[OPFeature], distributions: Seq[FeatureDistribution]): Unit = {
    // TODO: Figure out a way to keep track of raw features that aren't explicitly blocklisted, but can't be used
    // TODO: because they're inputs into an explicitly blocklisted feature. Eg. "height" in ModelInsightsTest

    def finalResultFeaturesCheck(resultFeatures: Array[OPFeature], blocklisted: List[OPFeature]): Unit = {
      if (resultFeaturePolicy == ResultFeatureRetention.Strict) {
        resultFeatures.foreach{ f => if (blocklisted.contains(f)) {
          throw new IllegalArgumentException(s"Blocklist of features (${blocklisted.map(_.name).mkString(", ")})" +
            s" from RawFeatureFilter contained the result feature ${f.name}") } }
      } else if (resultFeaturePolicy == ResultFeatureRetention.AtLeastOne) {
        if (resultFeatures.forall(blocklisted.contains)) throw new IllegalArgumentException(s"Blocklist of features" +
          s" (${blocklisted.map(_.name).mkString(", ")}) from RawFeatureFilter removed all result features")
      } else throw new IllegalArgumentException(s"result feature retention policy $resultFeaturePolicy not supported")
    }

    blocklistedFeatures = features
    if (blocklistedFeatures.nonEmpty) {
      val allBlocklisted: MList[OPFeature] = MList(getBlocklist(): _*)
      val allUpdated: MList[OPFeature] = MList.empty

      val initialResultFeatures = getResultFeatures()
      finalResultFeaturesCheck(initialResultFeatures, allBlocklisted.toList)

      val initialStages = getStages() // ordered by DAG so dont need to recompute DAG
      // for each stage remove anything blocklisted from the inputs and update any changed input features
      initialStages.foreach { stg =>
        val inFeatures = stg.getInputFeatures()
        val blocklistRemoved = inFeatures
          .filterNot { f => allBlocklisted.exists(bl => bl.sameOrigin(f)) }
          .map { f =>
            if (f.isRaw) f.withDistributions(distributions.collect { case d if d.name == f.name => d }) else f
          }
        val inputsChanged = blocklistRemoved.map{ f => allUpdated.find(u => u.sameOrigin(f)).getOrElse(f) }
        val oldOutput = stg.getOutput()
        Try(stg.setInputFeatureArray(inputsChanged).setOutputFeatureName(oldOutput.name).getOutput()) match {
          case Success(out) => allUpdated += out
          case Failure(e) =>
            log.info(s"Issue updating inputs for stage $stg: $e")
            allBlocklisted += oldOutput
            finalResultFeaturesCheck(initialResultFeatures, allBlocklisted.toList)
        }
      }

      // Update the whole DAG with the blocklisted features expunged
      val updatedResultFeatures = initialResultFeatures
        .filterNot(allBlocklisted.contains)
        .map{ f => allUpdated.find(u => u.sameOrigin(f)).getOrElse(f) }
      setResultFeatures(updatedResultFeatures: _*)
    }
  }


  protected[op] def setBlocklistMapKeys(mapKeys: Map[String, Set[String]]): Unit = {
    blocklistedMapKeys = mapKeys
  }

  /**
   * Set parameters from stage params map unless param is set in code.
   * Note: Will NOT override parameter values that have been
   * set previously in code OR with a previous set of parameters.
   */
  private def setStageParameters(stages: Array[OPStage]): Unit = {
    val stageIds = stages.flatMap(s => Seq(s.getClass.getSimpleName, s.uid)).toSet
    val unmatchedStages = parameters.stageParams.keySet.filter(stageIds.contains)
    if (unmatchedStages.nonEmpty) log.error(s"Parameter settings with stage ids: $unmatchedStages had no matching" +
      s"stages in this workflow. Ids for the stages in this workflow are: ${stageIds.mkString(", ")}")
    for {
      (stageName, stageParams) <- parameters.stageParams
      stage <- stages.filter(s => s.getClass.getSimpleName == stageName || s.uid == stageName)
      (k, v) <- stageParams
    } {
      val setStage =
        Try {
          stage.set(stage.getParam(k), v)
        } orElse {
          Try { ReflectionUtils.reflectSetterMethod(stage, k, Seq(v)) }
        }
      if (setStage.isFailure) log.error(
        s"Setting parameter $k with value $v for stage $stage with params ${stage.params.toList} failed with an error",
        setStage.failed.get
      )
      else log.info(s"Set parameter $k to value $v for stage $stage")
    }
  }

  /**
   * Uses input features to reconstruct the DAG of stages needed to generate them
   *
   * @param features final features passed into setInput
   */
  private def setStagesDAG(features: Array[OPFeature]): OpWorkflow.this.type = {
    // Unique stages layered by distance
    val uniqueStagesLayered = FitStagesUtil.computeDAG(features)

    if (log.isDebugEnabled) {
      val total = uniqueStagesLayered.map(_.length).sum
      val stages = for {
        layer <- uniqueStagesLayered
        (stage, distance) <- layer
      } yield s"$stage with distance $distance with output ${stage.getOutput().name}"

      log.debug("*" * 80)
      log.debug(s"Setting $total parent stages (sorted by distance desc):\n{}", stages.mkString("\n"))
      log.debug("*" * 80)
    }

    val uniqueStages: Array[OPStage] = uniqueStagesLayered.flatMap(_.map(_._1))

    setStageParameters(uniqueStages)
    setStages(uniqueStages)
  }

  /**
   * Used to generate dataframe from reader and raw features list
   *
   * @return Dataframe with all the features generated + persisted
   */
  protected def generateRawData()(implicit spark: SparkSession): DataFrame = {
    JobGroupUtil.withJobGroup(OpStep.DataReadingAndFiltering) {
      (reader, rawFeatureFilter) match {
        case (None, None) => throw new IllegalArgumentException(
          "Data reader must be set either directly on the workflow or through the RawFeatureFilter")
        case (Some(r), None) =>
          checkReadersAndFeatures()
          r.generateDataFrame(rawFeatures, parameters).persist()
        case (rd, Some(rf)) =>
          rd match {
            case None => setReader(rf.trainingReader)
            case Some(r) => if (r != rf.trainingReader) log.warn(
              "Workflow data reader and RawFeatureFilter training reader do not match! " +
                "The RawFeatureFilter training reader will be used to generate the data for training")
          }
          checkReadersAndFeatures()

          val FilteredRawData(cleanedData, featuresToDrop, mapKeysToDrop, rawFeatureFilterResults) =
            rf.generateFilteredRaw(rawFeatures, parameters)

          setRawFeatureFilterResults(rawFeatureFilterResults)
          setBlocklist(featuresToDrop, rawFeatureFilterResults.rawFeatureDistributions)
          setBlocklistMapKeys(mapKeysToDrop)
          cleanedData
      }
    }
  }

  /**
   * Transform function for testing chained transformations
   *
   * @param in DataFrame
   * @return transformed DataFrame
   */
  private[op] def transform(in: DataFrame, persistEveryKStages: Int = OpWorkflowModel.PersistEveryKStages)
    (implicit sc: SparkSession): DataFrame = {
    val transformers = fitStages(in, stages, persistEveryKStages).map(_.asInstanceOf[Transformer])
    FitStagesUtil.applySparkTransformations(in, transformers, persistEveryKStages)
  }

  /**
   * Check if all the stages of the workflow are serializable
   *
   * @return Failure if not serializable
   */
  private[op] def checkSerializable(): Try[Unit] = Try {
    val failures = stages.map(s => s.uid -> s.checkSerializable).collect { case (stageId, Failure(e)) => stageId -> e }

    if (failures.nonEmpty) throw new IllegalArgumentException(
      s"All stages must be serializable. Failed stages: ${failures.map(_._1).mkString(",")}",
      failures.head._2
    )
  }

  /**
   * Check if all the stages of the workflow have uid argument in their constructors
   * (required for workflow save/load to work)
   *
   * @return Failure if there is at least one stage without a uid argument in constructor
   */
  private[op] def checkCtorUIDs(): Try[Unit] = checkCtorArgs(arg = "uid")

  /**
   * Check if all the stages of the workflow have a specified argument 'arg' in their constructors
   * (required for workflow save/load to work)
   *
   * @param arg ctor argument to check
   * @return Failure if there is at least one stage without a 'arg' argument in constructor
   */
  private[op] def checkCtorArgs(arg: String): Try[Unit] = Try {
    val failures =
      stages.map(s => s.uid -> ReflectionUtils.bestCtorWithArgs(s)._2.map(_._1))
        .collect { case (stageId, args) if !args.contains(arg) => stageId }

    if (failures.nonEmpty) throw new IllegalArgumentException(
      s"All stages must be have $arg as their ctor argument. Failed stages: ${failures.mkString(",")}"
    )
  }

  /**
   * Check if all the stages of the workflow have uid argument in their constructors
   * (required for workflow save/load to work)
   *
   * @return Failure if there is at least one stage without a uid argument in constructor
   */
  private[op] def checkDistinctUIDs(): Try[Unit] = Try {
    if (stages.map(_.uid).distinct.length != stages.length) throw new IllegalArgumentException(
      "All stages must be distinct instances with distinct uids for saving"
    )
  }

  /**
   * Validate all the workflow stages
   *
   * @throws IllegalArgumentException
   */
  private[op] def validateStages(): Unit = {
    val res = for {
      _ <- checkCtorUIDs()
      _ <- checkSerializable()
      _ <- checkDistinctUIDs()
    } yield ()
    if (res.isFailure) throw res.failed.get
  }

  /**
   * Fit all of the estimators in the pipeline and return a pipeline model of only transformers. Uses data loaded
   * as specified by the data reader to generate the initial data set.
   *
   * @param persistEveryKStages persist data in transforms every k stages for performance improvement
   * @return a fitted pipeline model
   */
  def train(persistEveryKStages: Int = OpWorkflowModel.PersistEveryKStages)
    (implicit spark: SparkSession): OpWorkflowModel = {

    val rawData = generateRawData()
    // Update features with fitted stages
    val fittedStages = fitStages(data = rawData, stagesToFit = stages, persistEveryKStages)
    val newResultFeatures = resultFeatures.map(_.copyWithNewStages(fittedStages))

    val model =
      new OpWorkflowModel(uid, getParameters())
        .setStages(fittedStages)
        .setFeatures(newResultFeatures)
        .setParameters(getParameters())
        .setBlocklist(getBlocklist())
        .setBlocklistMapKeys(getBlocklistMapKeys())
        .setRawFeatureFilterResults(getRawFeatureFilterResults())

    reader.map(model.setReader).getOrElse(model)
  }

  /**
   * Fit the estimators to return a sequence of only transformers
   * Modified version of Spark 2.x Pipeline
   *
   * @param data                dataframe to fit on
   * @param stagesToFit         stages that need to be converted to transformers
   * @param persistEveryKStages persist data in transforms every k stages for performance improvement
   * @return fitted transformers
   */
  protected def fitStages(data: DataFrame, stagesToFit: Array[OPStage], persistEveryKStages: Int)
    (implicit spark: SparkSession): Array[OPStage] = {

    // TODO may want to make workflow take an optional reserve fraction
    val splitters = stagesToFit.collect { case s: ModelSelector[_, _] => s.splitter }.flatten
    val splitter = splitters.reduceOption { (a, b) =>
      if (a.getReserveTestFraction > b.getReserveTestFraction) a else b
    }
    val (train, test) = splitter.map(_.split(data)).getOrElse((data, spark.emptyDataFrame))
    val hasTest = !test.isEmpty

    val dag = FitStagesUtil.computeDAG(resultFeatures)
      .map(_.filter(s => stagesToFit.contains(s._1)))
      .filter(_.nonEmpty)

    // doing regular workflow fit without workflow level CV
    if (!isWorkflowCV) {
      // The cross-validation job group is handled in the appropriate Estimator
      JobGroupUtil.withJobGroup(OpStep.FeatureEngineering) {
        FitStagesUtil.fitAndTransformDAG(
          dag = dag,
          train = train,
          test = test,
          hasTest = hasTest,
          persistEveryKStages = persistEveryKStages
        ).transformers
      }
    } else {
      // doing workflow level CV/TS
      // Extract Model Selector and Split the DAG into
      val CutDAG(modelSelectorOpt, before, during, after) = FitStagesUtil.cutDAG(dag)

      log.info("Applying initial DAG before CV/TS. Stages: {}", before.flatMap(_.map(_._1.stageName)).mkString(", "))
      val FittedDAG(beforeTrain, beforeTest, beforeTransformers) =
        JobGroupUtil.withJobGroup(OpStep.FeatureEngineering) {
          FitStagesUtil.fitAndTransformDAG(
            dag = before,
            train = train,
            test = test,
            hasTest = hasTest,
            persistEveryKStages = persistEveryKStages
          )
        }

      // Break up catalyst (cause it chokes) by converting into rdd, persisting it and then back to dataframe
      val (trainRDD, testRDD) = (beforeTrain.rdd.persist(), beforeTest.rdd.persist())
      val (trainFixed, testFixed) = (
        spark.createDataFrame(trainRDD, beforeTrain.schema),
        spark.createDataFrame(testRDD, beforeTest.schema)
      )

      modelSelectorOpt match {
        case None => beforeTransformers
        case Some((modelSelector, distance)) =>
          // estimate best model
          log.info("Estimate best Model with CV/TS. Stages included in CV are: {}, {}",
            during.flatMap(_.map(_._1.stageName)).mkString(", "), modelSelector.uid: Any
          )
          JobGroupUtil.withJobGroup(OpStep.CrossValidation) {
            modelSelector.findBestEstimator(trainFixed, Option(during))
          }
          val remainingDAG: StagesDAG = (during :+ (Array(modelSelector -> distance): Layer)) ++ after

          log.info("Applying DAG after CV/TS. Stages: {}", remainingDAG.flatMap(_.map(_._1.stageName)).mkString(", "))
          val fitted = JobGroupUtil.withJobGroup(OpStep.FeatureEngineering) {
            FitStagesUtil.fitAndTransformDAG(
              dag = remainingDAG,
              train = trainFixed,
              test = testFixed,
              hasTest = hasTest,
              persistEveryKStages = persistEveryKStages,
              fittedTransformers = beforeTransformers
            ).transformers
          }
          trainRDD.unpersist()
          testRDD.unpersist()
          fitted
      }
    }
  }

  /**
   * Replaces any estimators in this workflow with their corresponding fit models from the OpWorkflowModel
   * passed in. Note that the Stages UIDs must EXACTLY correspond in order to be replaced so the same features
   * and stages must be used in both the fitted OpWorkflowModel and this OpWorkflow.
   * Any estimators that are not part of the OpWorkflowModel passed in will be trained when .train()
   * is called on this OpWorkflow.
   *
   * @param model model containing fitted stages to be used in this workflow
   * @return an OpWorkflow containing all of the stages from this model plus any new stages
   *         needed to generate the features not included in the fitted model
   */
  def withModelStages(model: OpWorkflowModel): this.type = {
    val newResultFeatures =
      (resultFeatures ++ model.getResultFeatures()).map(_.copyWithNewStages(model.getStages()))
    setResultFeatures(newResultFeatures: _*)
  }

  /**
   * Load a previously trained workflow model from path
   *
   * @param path to the trained workflow model
   * @param localDir local folder to copy and unpack stored model to for loading
   * @return workflow model
   */
  def loadModel(path: String, localDir: String = WorkflowFileReader.localDir): OpWorkflowModel = {
    new OpWorkflowModelReader(Some(this)).load(path, localDir)
  }

  /**
   * Returns a dataframe containing all the columns generated up to and including the feature input
   *
   * @param feature input feature to compute up to
   * @param persistEveryKStages persist data in transforms every k stages for performance improvement
   * @return Dataframe containing columns corresponding to all of the features generated up to the feature given
   */
  def computeDataUpTo(feature: OPFeature, persistEveryKStages: Int = OpWorkflowModel.PersistEveryKStages)
    (implicit spark: SparkSession): DataFrame = {
    if (findOriginStageId(feature).isEmpty) {
      log.warn("Could not find origin stage for feature in workflow!! Defaulting to generate raw features.")
      generateRawData()
    } else {
      val rawData = generateRawData()
      val stagesToFit = FitStagesUtil.computeDAG(Array(feature)).flatMap(_.map(_._1))
      val fittedStages = fitStages(rawData, stagesToFit, persistEveryKStages)
      val updatedFeature = feature.copyWithNewStages(fittedStages)
      val dag = FitStagesUtil.computeDAG(Array(updatedFeature))
      applyTransformationsDAG(rawData, dag, persistEveryKStages)
    }
  }

  /**
   * Add a raw features filter to the workflow to look at fill rates and distributions of raw features and exclude
   * features that do not meet specifications from modeling DAG
   *
   * @param trainingReader     training reader to use in filter if not supplied will fall back to reader specified for
   *                           workflow (note that this reader will take precedence over readers directly input to the
   *                           workflow if both are supplied)
   * @param scoringReader      scoring reader to use in filter if not supplied will do the checks possible with only
   *                           training data available
   * @param bins               number of bins to use in estimating feature distributions
   * @param minFillRate        minimum non-null fraction of instances that a feature should contain
   * @param maxFillDifference  maximum absolute difference in fill rate between scoring and training data for a feature
   * @param maxFillRatioDiff   maximum difference in fill ratio (symmetric) between scoring and training data for
   *                           a feature
   * @param maxJSDivergence    maximum Jensen-Shannon divergence between the training and scoring distributions
   *                           for a feature
   * @param protectedFeatures  list of features that should never be removed (features that are used to create them will
   *                           also be protected)
   * @param protectedJSFeatures features that are protected from removal by JS divergence check
   * @param textBinsFormula    formula to compute the text features bin size.
   *                           Input arguments are [[Summary]] and number of bins to use in computing
   *                           feature distributions (histograms for numerics, hashes for strings).
   *                           Output is the bins for the text features.
   * @param timePeriod         Time period used to apply circulate date transformation for date features, if not
   *                           specified will use numeric feature transformation
   * @param minScoringRows     Minimum row threshold for scoring set comparisons to be used in checks. If the scoring
   *                           set size is below this threshold, then only training data checks will be used
   * @tparam T Type of the data read in
   */
  @Experimental
  // scalastyle:off parameter.number
  def withRawFeatureFilter[T](
    trainingReader: Option[Reader[T]],
    scoringReader: Option[Reader[T]],
    bins: Int = 100,
    minFillRate: Double = 0.001,
    maxFillDifference: Double = 0.90,
    maxFillRatioDiff: Double = 20.0,
    maxJSDivergence: Double = 0.90,
    maxCorrelation: Double = 0.95,
    correlationType: CorrelationType = CorrelationType.Pearson,
    protectedFeatures: Array[OPFeature] = Array.empty,
    protectedJSFeatures: Array[OPFeature] = Array.empty,
    textBinsFormula: (Summary, Int) => Int = RawFeatureFilter.textBinsFormula,
    timePeriod: Option[TimePeriod] = None,
    minScoringRows: Int = RawFeatureFilter.minScoringRowsDefault,
    resultFeatureRetentionPolicy: ResultFeatureRetention = ResultFeatureRetention.Strict
  ): this.type = {
    resultFeaturePolicy = resultFeatureRetentionPolicy
    val training = trainingReader.orElse(reader).map(_.asInstanceOf[Reader[T]])
    require(training.nonEmpty, "Reader for training data must be provided either in withRawFeatureFilter or directly" +
      "as the reader for the workflow")
    val protectedRawFeatures = protectedFeatures.flatMap(_.rawFeatures).map(_.name).toSet
    val protectedRawJSFeatures = protectedJSFeatures.flatMap(_.rawFeatures).map(_.name).toSet
    rawFeatureFilter = Option {
      new RawFeatureFilter(
        trainingReader = training.get,
        scoringReader = scoringReader,
        bins = bins,
        minFill = minFillRate,
        maxFillDifference = maxFillDifference,
        maxFillRatioDiff = maxFillRatioDiff,
        maxJSDivergence = maxJSDivergence,
        maxCorrelation = maxCorrelation,
        correlationType = correlationType,
        protectedFeatures = protectedRawFeatures,
        jsDivergenceProtectedFeatures = protectedRawJSFeatures,
        textBinsFormula = textBinsFormula,
        timePeriod = timePeriod,
        minScoringRows = minScoringRows)
    }
    this
  }
  // scalastyle:on

}

/**
 * Methods of vectorizing text (eg. to be chosen by statistics computed in SmartTextVectorizer)
 */
sealed trait ResultFeatureRetention extends EnumEntry with Serializable

object ResultFeatureRetention extends Enum[ResultFeatureRetention] {
  val values = findValues
  case object Strict extends ResultFeatureRetention
  case object AtLeastOne extends ResultFeatureRetention
}
