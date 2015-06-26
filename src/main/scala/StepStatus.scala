package com.etsy.sahale


import cascading.flow.Flow
import cascading.flow.hadoop.HadoopFlowStep
import cascading.stats.{CascadingStats, FlowStepStats}
import cascading.stats.hadoop.{HadoopStepStats, HadoopSliceStats}
import cascading.tap.Tap
import cascading.util.Util

import spray.json._
import DefaultJsonProtocol._

import org.apache.log4j.Logger
import java.util.Properties

import org.apache.hadoop.mapred.JobConf

import scala.collection.mutable
import scala.collection.JavaConversions._


object StepStatus {
  val LOG: Logger = Logger.getLogger(classOf[StepStatus])
}

/**
 * Value class to store tracked per-step workflow data.
 *
 * @author Eli Reisman
 */
class StepStatus(val stepNumber: String, val stepId: String, props: Properties) {
  import com.etsy.sahale.StepStatus.LOG

  // if users want to track additional JobConf values, put the chosen keys in a CSV
  // list in flow-tracker.properties entry "user.selected.configs" at build time
  val propertiesToExtract = Seq("sahale.additional.links") ++ {
    props.getProperty("user.selected.configs", "").split("""\s*,\s*""").map { _.trim }.filter { _ != "" }.toSeq
  }

  var sources = FlowTracker.UNKNOWN
  var sink = FlowTracker.UNKNOWN
  var sourcesFields = FlowTracker.UNKNOWN
  var sinkFields = FlowTracker.UNKNOWN
  var jobId = "NO_JOB_ID"
  var mapProgress = "0.00"
  var reduceProgress = "0.00"
  var stepStatus = "NOT_LAUNCHED"
  var stepPriority = "0"
  var stepRunningTime = "0"
  var stepStartEpochMs = "0"
  var stepSubmitEpochMs = "0"
  var stepEndEpochMs = "0"
  var counters = Map[String, Map[String, Long]]()
  var hdfsBytesWritten = 0L
  var configurationProperties = Map[String, String]()

  override def toString: String = {
    toMap.toString
  }

  def toMap: Map[String, Any] = {
    Map(
      "stepnumber"                -> stepNumber,
      "sources"                   -> sources,
      "sink"                      -> sink,
      "sourcesfields"             -> sourcesFields,
      "sinkfields"                -> sinkFields,
      "jobid"                     -> jobId,
      "stepid"                    -> stepId,
      "mapprogress"               -> mapProgress,
      "reduceprogress"            -> reduceProgress,
      "stepstatus"                -> stepStatus,
      "steppriority"              -> stepPriority,
      "steprunningtime"           -> stepRunningTime,
      "step_start_epoch_ms"       -> stepStartEpochMs,
      "step_submit_epoch_ms"      -> stepSubmitEpochMs,
      "step_end_epoch_ms"         -> stepEndEpochMs,
      "counters"                  -> counters,
      "configuration_properties"  -> configurationProperties
    )
  }

  def jsonMap: String = {
    Map(
      "stepnumber"                -> toJsonString(stepNumber),
      "sources"                   -> toJsonString(sources),
      "sink"                      -> toJsonString(sink),
      "sourcesfields"             -> toJsonString(sourcesFields),
      "sinkfields"                -> toJsonString(sinkFields),
      "jobid"                     -> toJsonString(jobId),
      "stepid"                    -> toJsonString(stepId),
      "mapprogress"               -> toJsonString(mapProgress),
      "reduceprogress"            -> toJsonString(reduceProgress),
      "stepstatus"                -> toJsonString(stepStatus),
      "steppriority"              -> toJsonString(stepPriority),
      "steprunningtime"           -> toJsonString(stepRunningTime),
      "step_start_epoch_ms"       -> toJsonString(stepStartEpochMs),
      "step_submit_epoch_ms"      -> toJsonString(stepSubmitEpochMs),
      "step_end_epoch_ms"         -> toJsonString(stepEndEpochMs),
      "counters"                  -> counters.map { case(k: String, v: Map[String,Long]) => (k, v.toJson) }.toJson,
      "configuration_properties"  -> configurationProperties.toJson
    ).toJson.compactPrint
  }

  def toJsonString(s: String): JsValue = s match {
    case s: String if (s != null) => JsString(s)
    case null => JsNull
    case err => throw new DeserializationException("Error while serializing StepStatus into JSON: expected string value, got: " + err)
  }

  def setConfigurationProperties(conf: JobConf): Unit = {
    this.configurationProperties = (propertiesToExtract map { prop: String =>
      prop -> conf.get(prop, "")
    }).toMap
  }

  def setSourcesAndSink(sources: String, sourcesFields: String, sink: String, sinkFields: String): Unit = {
    this.sources = sources
    this.sourcesFields = sourcesFields
    this.sink = sink
    this.sinkFields = sinkFields
  }

  /**
   * Updates the non-static step properties.
   */
  def update(hadoopFlowStep: HadoopFlowStep): Unit = {
    val hadoopStepStats = hadoopFlowStep.getFlowStepStats.asInstanceOf[HadoopStepStats]

    jobId = hadoopStepStats.getJobID
    mapProgress = getMapProgress(hadoopStepStats)
    reduceProgress = getReduceProgress(hadoopStepStats)
    stepRunningTime = getStepRunningTime(hadoopStepStats) // checks old stepStatus value - MUST be updated first!
    stepStatus = hadoopStepStats.getStatus.toString
    stepPriority = getStepPriority(hadoopFlowStep)

    updateEpochMsFields(hadoopStepStats)
    updateStepCounters(hadoopStepStats)
  }

  def updateEpochMsFields(hss: HadoopStepStats): Unit = {
    stepStartEpochMs = hss.getStartTime.toString
    stepSubmitEpochMs = hss.getSubmitTime.toString
    stepEndEpochMs = hss.getFinishedTime.toString
  }

  def getStepPriority(hfs: HadoopFlowStep): String = {
    hfs.getSubmitPriority.toString
  }

  // Calling captureDetail every update just for this is not worth it.
  // Just be sure final update of run picks up accurate stats for this.
  def getStepRunningTime(hss: HadoopStepStats): String = {
    val forceUpdate: Boolean = (stepStatus == "RUNNING" && hss.getStatus.toString != "RUNNING")
    val diff: Long = forceUpdate match {
      case true => {
        try {
          LOG.info("Calling captureDetail() on HadoopStepStats")
          hss.captureDetail // ain't cheap, keep an eye on it
          var minStart: Long = Long.MaxValue
          var maxEnd: Long = Long.MinValue
          hss.getTaskStats.map { entry: (String, HadoopSliceStats) =>
            val start: Long = entry._2.getStartTime
            if (start < minStart) minStart = start
            val end: Long = entry._2.getFinishTime
            if (end > maxEnd) maxEnd = end
          }
          if (maxEnd - minStart < 1L) 0L else (maxEnd - minStart) / 1000L
        } catch {
          case _: Exception => 0L
        }
      }
      case _ => {
        stepRunningTime.toLong
      }
    }
    diff.toString
  }

  def updateStepCounters(hss: HadoopStepStats): Unit = {
    counters = dumpCounters(hss).foldLeft(mutable.Map[String, Map[String, Long]]()) {
      (acc, next) => acc.getOrElse(next._1, None) match {
        case None => acc(next._1) = Map(next._2 -> next._3) ; acc
        case _    => acc(next._1) = Map(next._2 -> next._3) ++ acc(next._1) ; acc
      }
    }.toMap
  }

  def dumpCounters(hss: HadoopStepStats): Iterable[(String, String, Long)] = {
    for (g <- hss.getCounterGroups ; c <- hss.getCountersFor(g)) yield {
      (cleanGroupName(g), c, hss.getCounterValue(g, c))
    }
  }

  def cleanGroupName(name: String): String = {
    name match {
      case oah: String if (oah.startsWith("org.apache.hadoop.")) => cleanGroupName(oah.substring(18))
      case ic: String if (ic.indexOf("""$""") >= 0) => cleanGroupName(ic.substring(0, ic.indexOf("""$""")))
      case _ => name
    }
  }

  def getMapProgress(hss: HadoopStepStats): String = {
    hss.getMapProgress.isNaN match {
      case true => "0.00"
      case _    => "%3.2f" format (hss.getMapProgress * 100.0)
    }
  }

  def getReduceProgress(hss: HadoopStepStats): String = {
    hss.getReduceProgress.isNaN match {
      case true => "0.00"
      case _    => "%3.2f" format (hss.getReduceProgress * 100.0)
    }
  }

  def getHdfsBytesWritten(hss: HadoopStepStats): Long = {
    hss.getCounterValue("org.apache.hadoop.mapreduce.FileSystemCounter", "HDFS_BYTES_WRITTEN")
  }
}
