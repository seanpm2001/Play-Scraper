package org.nlogo

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.{ List => JList, Map => JMap }

import play.core.StaticApplication
import play.api.{ Mode, Play, Configuration }
import play.api.mvc.{ RequestHeader, EssentialAction }
import play.api.libs.iteratee.Iteratee
import play.api.DefaultApplication
import play.core.classloader.{ ApplicationClassLoaderProvider, DelegatingClassLoader }

import scala.util.{ Success, Failure }
import scala.util.Random
import scala.collection.JavaConversions._
import scala.collection.immutable.HashMap
import scala.concurrent.ExecutionContext.Implicits.global

object StartServer {
  def simpleGetRequest(_path: String, _accept: String = "*/*"): RequestHeader = {
    new RequestHeader {
      def headers = new play.api.mvc.Headers {
        import play.api.http.HeaderNames._
        val data = Seq[(String, Seq[String])](ACCEPT -> Seq(_accept))
      }
      def id = Random.nextInt
      def method = "GET"
      def path = _path
      def queryString = Map[String, Seq[String]]()
      def remoteAddress = "0.0.0.0"
      def secure = false
      def tags = Map[String, String]()
      def uri = _path
      def version = "HTTP/1.1"
    }
  }

  def apply(baseDirectory: File, targetDirectory: File, loader: ClassLoader, routesToScrape: JList[String], additionalConfig: JMap[String, String]): Unit = {
    val app = new DefaultApplication(baseDirectory, loader, None, Mode.Prod) {
      override def configuration = super.configuration ++ Configuration.from(HashMap(additionalConfig.toSeq: _*))
    }
    Play.start(app)
    val routes = app.routes.get

    def renderPage(requestedPath: String): Unit = {
      println("RENDERING")
      println(requestedPath)
      val path = additionalConfig.toMap.get("application.context")
        .map(context => s"$context$requestedPath".replaceAll("//", "/"))
        .getOrElse(requestedPath)
      val req = simpleGetRequest(path)
      val action = routes.routes(req).asInstanceOf[EssentialAction]
      action(req).run.onComplete {
        case Success(res) =>
          val consumer = Iteratee.getChunks[Array[Byte]]
          Iteratee.flatten(res.body(consumer)).run.onComplete
          {
            case Success(body) =>
              val text = new String(body.reduceLeft(_ ++ _), "UTF-8")
              writeToFile(targetDirectory.getPath, path,
                (writer: OutputStreamWriter) => writer.write(text, 0, text.length))
            case Failure(f) =>
              println(s"FAILURE getting body of $path")
          }
        case Failure(f) =>
          println(s"FAILURE retrieving $path")
          println(f)
      }
    }

    routesToScrape.foreach(renderPage)
  }

  private def writeToFile(parentDirPath: String, path: String, withFile: OutputStreamWriter => Unit): Unit = {
    def toFile(parentDirPath: String, path: String): File =
      path.span(_ != '/') match {
        case (filename, "") =>
          val file = new File(parentDirPath, filename)
          if (file.isDirectory) new File(file.getPath, "index.html") else file
        case (filename, rest) =>
          val dir = new File(parentDirPath + File.separatorChar + filename)
          dir.mkdir
          toFile(dir.getPath, rest.drop(1))
      }
    val file = toFile(parentDirPath, path.drop(1))
    val fileOutputStream = new FileOutputStream(file)
    val writer = new OutputStreamWriter(fileOutputStream, "UTF-8")
    withFile(writer)
    writer.flush()
    writer.close()
    fileOutputStream.close()
  }


  def pathForAsset(assetName: String): String = {
    val assetRouterClass = getClass.getClassLoader.loadClass("controllers.ReverseAssets")
    val assetRouterInstance = getClass.getClassLoader.loadClass("controllers.routes").getField("Assets").get(null)
    assetRouterClass.getDeclaredMethod("at", classOf[String]).invoke(assetRouterInstance, assetName).asInstanceOf[play.api.mvc.Call].url
  }
}
