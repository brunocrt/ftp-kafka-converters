package com.landoop

import java.util

import com.datamountaineer.streamreactor.connect.ftp.source.SourceRecordConverter
import com.typesafe.scalalogging.slf4j.StrictLogging
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.apache.kafka.connect.source.SourceRecord

import scala.collection.JavaConverters._
import org.joda.time.DateTime

class HorizontalMonthlyCSV extends SourceRecordConverter with StrictLogging {

  override def configure(props: util.Map[String, _]): Unit = {}

  // keep track of empty lines, and lines with partial (missing) data points
  var emptyLines = 0L
  var partialLines = 0L

  val dateFormat: DateTimeFormatter = DateTimeFormat.forPattern("dd/mm/yyyy")

  override def convert(in: SourceRecord): util.List[SourceRecord] = {
    val lines = new String(in.value.asInstanceOf[Array[Byte]])
    val allRecords = lines.split("\n").flatMap { line =>

      val tokens = Parser.fromLine(line)

      val id = tokens.head
      val date = DateTime.parse(tokens(1), dateFormat)
      val readings = tokens.drop(2)

      val minutes = 1440 / readings.length
      logger.info(s"Monthly CSV parser with 1 entry every $minutes minutes")
      val eventsList = readings.indices.flatMap { index =>
        val value: String = readings(index)
        val parsedDouble = parseDouble(value)
        if (parsedDouble.isDefined) {
          val newTime = date.plusMinutes(index * minutes).getMillis / 1000
          val event = DeviceEvent(id, newTime, parsedDouble.get)
          Option(new SourceRecord(in.sourcePartition, in.sourceOffset, in.topic, 0, event.connectSchema, event.getStructure))
        }
        else None
      }.toList

      if (eventsList.isEmpty) emptyLines += 1
      if (eventsList.length != readings.length) partialLines += 1
      if (emptyLines % 100 == 0 || partialLines % 1000 == 0)
        logger.info(
          s"""
             |Total empty lines processed:     $emptyLines
             |Lines with a missing value :     $partialLines
        """.stripMargin)

      eventsList
    }.toList
    allRecords.asJava
  }

  // @formatter:off
  def parseDouble(s: String): Option[Double] = try { Some(s.toDouble) } catch { case _ : Throwable => None }

}
