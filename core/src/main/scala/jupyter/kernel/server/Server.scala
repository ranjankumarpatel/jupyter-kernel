package jupyter
package kernel
package server

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.lang.management.ManagementFactory
import java.net.{ InetAddress, ServerSocket }
import java.util.concurrent.ExecutorService

import argonaut._, Argonaut._

import com.typesafe.scalalogging.slf4j.LazyLogging

import jupyter.kernel.stream.{ StreamKernel, Streams }
import jupyter.kernel.stream.zmq.ZMQStreams
import jupyter.kernel.protocol.{ Connection, Output, Formats }, Formats._
import jupyter.kernel.interpreter.InterpreterKernel

import scalaz._, Scalaz._
import scalaz.concurrent.Task

object Server extends LazyLogging {

  case class Options(
    connectionFile: String = "",
    eraseConnectionFile: Boolean = false,
    quiet: Boolean = false
  )

  def newConnectionFile(connFile: File): Connection = {
    def randomPort(): Int = {
      val s = new ServerSocket(0)
      try s.getLocalPort
      finally s.close()
    }

    val ip = {
      val s = InetAddress.getLocalHost.toString
      val idx = s.lastIndexOf('/')
      if (idx < 0)
        s
      else
        s.substring(idx + 1)
    }

    val c = Connection(
      ip = ip,
      transport = "tcp",
      stdin_port = randomPort(),
      control_port = randomPort(),
      hb_port = randomPort(),
      shell_port = randomPort(),
      iopub_port = randomPort(),
      key = UUID.randomUUID().toString,
      signature_scheme = Some("hmac-sha256")
    )

    Files.write(connFile.toPath, c.asJson.spaces2.getBytes) // default charset

    c
  }

  private def pid() = ManagementFactory.getRuntimeMXBean.getName.takeWhile(_ != '@').toInt

  def launch(
    kernel: Kernel,
    streams: Streams,
    connection: Connection,
    classLoader: Option[ClassLoader]
  )(implicit es: ExecutorService): Throwable \/ Task[Unit] =
    kernel match {
      case k: InterpreterKernel =>
        for {
          interpreter <- k()
        } yield
          InterpreterServer(
            streams,
            Output.ConnectReply(
              shell_port=connection.shell_port,
              iopub_port=connection.iopub_port,
              stdin_port=connection.stdin_port,
              hb_port=connection.hb_port
            ),
            interpreter
          )

      case k: StreamKernel =>
        for {
          kernelStreams <- k()
        } yield Streams.connect(streams, kernelStreams)

      case other =>
        -\/(new Exception(s"Unhandled kernel type: $other"))
    }

  def apply(
    kernel: Kernel,
    kernelId: String,
    options: Server.Options = Server.Options(),
    classLoaderOption: Option[ClassLoader] = None
  )(implicit es: ExecutorService): Throwable \/ (File, Task[Unit]) =
    for {
      homeDir <- {
        Option(System.getProperty("user.home")).filterNot(_.isEmpty).orElse(sys.env.get("HOME").filterNot(_.isEmpty)).toRightDisjunction {
          new Exception(s"Cannot get user home dir, set one in the HOME environment variable")
        }
      }
      connFile = {
        Some(options.connectionFile).filter(_.nonEmpty).getOrElse(s"jupyter-kernel_${pid()}.json") match {
          case path if path.contains(File.separatorChar) =>
            new File(path)
          case secure =>
            new File(homeDir, s".ipython/profile_default/secure/$secure")
        }
      }
      _ <- {
        logger info s"Connection file: ${connFile.getAbsolutePath}"
        \/-(())
      }
      connection <- {
        if (options.eraseConnectionFile || !connFile.exists()) {
          logger.info(s"Creating ipython connection file ${connFile.getAbsolutePath}")
          connFile.getParentFile.mkdirs()
          \/-(newConnectionFile(connFile))
        } else {
          val s = io.Source.fromFile(connFile)
          try {
            s.mkString.decodeEither[Connection].leftMap { err =>
              logger.error(s"Loading connection file: $err")
              new Exception(s"Error while loading connection file: $err")
            }
          } finally s.close() // For Windows: closes the underlying connection file as early as possible (instead
                              // of waiting for GC to make it happen later), so that we don't hinder it be overwritten,
                              // e.g. if the kernel is restarted.
        }
      }
      streams <- \/.fromTryCatchNonFatal(ZMQStreams(connection, isServer = false, identity = Some(kernelId))).leftMap { err =>
        new Exception(s"Unable to open connection: $err", err)
      }
      _ <- {
        if (!options.quiet) Console.err.println(s"Launching kernel")
        \/-(())
      }
      t <- {
        launch(kernel, streams, connection, classLoaderOption)
      }.leftMap(err => new Exception(s"Launching kernel: $err", err))
    } yield (connFile, t)
}
