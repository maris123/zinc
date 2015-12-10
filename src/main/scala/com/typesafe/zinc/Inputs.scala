/**
 * Copyright (C) 2012 Typesafe, Inc. <http://www.typesafe.com>
 */

package com.typesafe.zinc

import java.io.File
import java.util.{ List => JList, Map => JMap }
import sbt.internal.inc.{ Analysis, IC, Locate }
import sbt.io.Path._
import scala.collection.JavaConverters._
import xsbti.compile.{ CompileOrder, IncOptions, IncOptionsUtil }
import xsbti.Maybe

/**
 * All inputs for a compile run.
 */
case class Inputs(
  classpath: Seq[File],
  sources: Seq[File],
  classesDirectory: File,
  scalacOptions: Seq[String],
  javacOptions: Seq[String],
  cacheFile: File,
  analysisMap: Map[File, Analysis],
  forceClean: Boolean,
  definesClass: File => String => Boolean,
  javaOnly: Boolean,
  compileOrder: CompileOrder,
  incOptions: IncOptions
)

object Inputs {
  /**
   * Create inputs based on command-line settings.
   */
  def apply(settings: Settings): Inputs = {
    import settings._
    inputs(
      classpath,
      sources,
      classesDirectory,
      scalacOptions,
      javacOptions,
      analysis.cache,
      analysis.cacheMap,
      analysis.forceClean,
      javaOnly,
      compileOrder,
      incOptions)
  }

  /**
   * Create normalised and defaulted Inputs.
   */
  def inputs(
    classpath: Seq[File],
    sources: Seq[File],
    classesDirectory: File,
    scalacOptions: Seq[String],
    javacOptions: Seq[String],
    analysisCache: Option[File],
    analysisCacheMap: Map[File, File],
    forceClean: Boolean,
    javaOnly: Boolean,
    compileOrder: CompileOrder,
    incOptions: IncOptions): Inputs =
  {
    val normalise: File => File = { _.getAbsoluteFile }
    val cp               = classpath map normalise
    val srcs             = sources map normalise
    val classes          = normalise(classesDirectory)
    val cacheFile        = normalise(analysisCache.getOrElse(defaultCacheLocation(classesDirectory)))
    val upstreamAnalysis = analysisCacheMap map { case (k, v) => (normalise(k), normalise(v)) }
    val analysisMap      = (cp map { file => (file, analysisFor(file, classes, upstreamAnalysis)) }).toMap
    new Inputs(
      cp, srcs, classes, scalacOptions, javacOptions, cacheFile, analysisMap, forceClean, Locate.definesClass,
      javaOnly, compileOrder, incOptions
    )
  }

  /**
   * Java API for creating Inputs.
   */
  def create(
    classpath: JList[File],
    sources: JList[File],
    classesDirectory: File,
    scalacOptions: JList[String],
    javacOptions: JList[String],
    analysisCache: File,
    analysisMap: JMap[File, File],
    compileOrder: String,
    incOptions: IncOptions): Inputs =
  inputs(
    classpath.asScala,
    sources.asScala,
    classesDirectory,
    scalacOptions.asScala,
    javacOptions.asScala,
    Option(analysisCache),
    analysisMap.asScala.toMap,
    forceClean = false,
    javaOnly = false,
    Settings.compileOrder(compileOrder),
    incOptions
  )

  /**
   * By default the cache location is relative to the classes directory (for example, target/classes/../cache/classes).
   */
  def defaultCacheLocation(classesDir: File) = {
    classesDir.getParentFile / "cache" / classesDir.getName
  }

  /**
   * Get the possible cache location for a classpath entry. Checks the upstream analysis map
   * for the cache location, otherwise uses the default location for output directories.
   */
  def cacheFor(file: File, exclude: File, mapped: Map[File, File]): Option[File] = {
    mapped.get(file) orElse {
      if (file.isDirectory && file != exclude) Some(defaultCacheLocation(file)) else None
    }
  }

  /**
   * Get the analysis for a compile run, based on a classpath entry.
   * If not cached in memory, reads from the cache file.
   */
  def analysisFor(file: File, exclude: File, mapped: Map[File, File]): Analysis = {
    cacheFor(file, exclude, mapped) map Compiler.analysis getOrElse Analysis.Empty
  }

  /**
   * By default the backup location is relative to the classes directory (for example, target/classes/../backup/classes).
   */
  def defaultBackupLocation(classesDir: File) = {
    classesDir.getParentFile / "backup" / classesDir.getName
  }

  /**
   * Verify inputs and update if necessary.
   * Currently checks that the cache file is writable.
   */
  def verify(inputs: Inputs): Inputs = {
    inputs.copy(cacheFile = verifyCacheFile(inputs.cacheFile, inputs.classesDirectory))
  }

  /**
   * Check that the cache file is writable.
   * If not writable then the fallback is within the zinc cache directory.
   *
   */
  def verifyCacheFile(cacheFile: File, classesDir: File): File = {
    if (Util.checkWritable(cacheFile)) cacheFile
    else Setup.zincCacheDir / "analysis-cache" / Util.pathHash(classesDir)
  }

  /**
   * Debug output for inputs.
   */
  def debug(inputs: Inputs, log: xsbti.Logger): Unit = {
    show(inputs, s => log.debug(sbt.util.Logger.f0(s)))
  }

  /**
   * Debug output for inputs.
   */
  def show(inputs: Inputs, output: String => Unit): Unit = {
    import inputs._

    val incOpts = Seq(
      "transitive step"        -> incOptions.transitiveStep,
      "recompile all fraction" -> incOptions.recompileAllFraction,
      "debug relations"        -> incOptions.relationsDebug,
      "debug api"              -> incOptions.apiDebug,
      "api dump"               -> incOptions.apiDumpDirectory,
      "api diff context size"  -> incOptions.apiDiffContextSize,
      "classfile manager type" -> incOptions.classfileManagerType,
      "recompile on macro def" -> incOptions.recompileOnMacroDef,
      "name hashing"           -> incOptions.nameHashing
    )

    val values = Seq(
      "classpath"                    -> classpath,
      "sources"                      -> sources,
      "output directory"             -> classesDirectory,
      "scalac options"               -> scalacOptions,
      "javac options"                -> javacOptions,
      "cache file"                   -> cacheFile,
      "analysis map"                 -> analysisMap,
      "force clean"                  -> forceClean,
      "java only"                    -> javaOnly,
      "compile order"                -> compileOrder,
      "incremental compiler options" -> incOpts)

    Util.show(("Inputs", values), output)
  }
}
