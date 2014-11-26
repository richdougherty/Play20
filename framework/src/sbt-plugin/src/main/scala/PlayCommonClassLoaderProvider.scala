package play

import java.io.File
import java.net.URLClassLoader

class PlayCommonClassLoaderProvider {

  private var commonClassLoaderCache: ClassLoader = null

  def getOrElseUseClasspath(commonClasspath: Seq[File]): ClassLoader = {
    if (commonClassLoaderCache == null) {
      commonClassLoaderCache = createCommonClassLoaderFromClasspath(commonClasspath)
    }
    commonClassLoaderCache
  }

  private def createCommonClassLoaderFromClasspath(commonClasspath: Seq[File]): ClassLoader = {
    new URLClassLoader(commonClasspath.map(_.toURI.toURL).to[Array], null /* important here, don't depend of the sbt classLoader! */ ) {
      override def toString = "Common ClassLoader: " + getURLs.map(_.toString).mkString(",")
    }
  }

}