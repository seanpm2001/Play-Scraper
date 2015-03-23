package org.nlogo

import java.io.File

import sbt.{ AutoPlugin, taskKey, Project, Compile }
import sbt.Keys._

import play.Play
import play.PlayReload
import play.runsupport.Reloader.CompileSuccess
import play.api.Mode
import play.api.mvc.RequestHeader
import play.api.mvc.EssentialAction
import play.api.libs.iteratee.Iteratee
import play.api.DefaultApplication
import play.core.classloader.{ ApplicationClassLoaderProvider, DelegatingClassLoader }

import scala.util.Success
import scala.util.Random
import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global

import StartServer.simpleGetRequest

object Scraper extends AutoPlugin {
  object autoImport {
    val scrapePlay = taskKey[Unit]("scrape play")
  }

  import autoImport._

  val buildLoader = Scraper.getClass.getClassLoader
  val scraperLocation = Scraper.getClass.getProtectionDomain.getCodeSource.getLocation

  override val projectSettings = Seq(
    (scrapePlay in Compile) := {
    import Play._
    val ignore = playCompileEverything.value
    println(ignore)
    // compile application
    // TODO: match on the result of this and only scrape if compilation succeeds
    PlayReload.compile(
      () => Project.runTask(playReload, state.value).map(_._2).get,
      () => Project.runTask(playReloaderClasspath, state.value).map(_._2).get,
      () => Project.runTask(streamsManager, state.value).map(_._2).get.toEither.right.toOption
    ) match {
      case CompileSuccess(sources, classpath) =>
        val fullClassPath = playDependencyClasspath.value.files.map(_.toURI.toURL) ++ classpath.map(_.toURI.toURL) :+ scraperLocation
        val commonClassLoader = playCommonClassloader.value
        var appLoader: Option[ClassLoader] = None
        val delegatingLoader = new DelegatingClassLoader(commonClassLoader, buildLoader, new ApplicationClassLoaderProvider {
          def get = {
            playReloaderClassLoader.value("reloader", fullClassPath.toArray, appLoader.get)
          }
        })
      println(fullClassPath.map(_.toString).sorted.mkString("\n"))
      val loader = playDependencyClassLoader.value("PlayDependencyClassLoader", fullClassPath.toArray, delegatingLoader)

      println(playAllAssets.value)
      val assetLoader = playAssetsClassLoader.value(loader)
      appLoader = Some(assetLoader)

      // start scraping!
      val serverStarter = assetLoader.loadClass("org.nlogo.StartServer$")
      val ssInstance = serverStarter.getFields.head.get(null)
      val ssApply = serverStarter.getDeclaredMethod("apply", classOf[java.io.File], classOf[ClassLoader])
      ssApply.invoke(ssInstance, baseDirectory.value, assetLoader)

      case other =>
        println("ReloadCompile FAILED!")
        println(other)
    }
    })
}
