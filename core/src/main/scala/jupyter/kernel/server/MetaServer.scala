package jupyter
package kernel
package server

import java.util.concurrent.ExecutorService

import com.typesafe.scalalogging.slf4j.LazyLogging
import jupyter.kernel.stream.Streams
import stream.zmq.{ ZMQStreams, ZMQKernel }

import argonaut._, Argonaut.{ EitherDecodeJson => _, EitherEncodeJson => _, _ }
import protocol.{ Meta => MetaProtocol, _ }, Formats._

import scalaz.{ -\/, \/, \/- }
import scalaz.concurrent.{ Strategy, Task }
import scalaz.stream.{ async, Process }

object MetaServer extends LazyLogging {
  def handler(
    launchKernel: Streams => Unit,
    kernelId: String,
    baseMsg: Message
  )(implicit
    pool: ExecutorService
  ): Process[Task, Message] =
    baseMsg.decode match {
      case -\/(err) =>
        Process.halt

      case \/-(msg) =>
        (msg.header.msg_type,  msg.content) match {
          case ("meta_kernel_start_request", startRequest: MetaProtocol.MetaKernelStartRequest) =>
            val c =
              for {
                connection <- \/.fromTryCatchNonFatal(ZMQKernel.newConnection())
                streams <- \/.fromTryCatchNonFatal(ZMQStreams(connection, isServer = false, identity = Some(kernelId)))
                _ <- \/.fromTryCatchNonFatal(launchKernel(streams))
              } yield connection

            Process.emit(
              msg.reply(
                "meta_kernel_start_reply",
                MetaProtocol.MetaKernelStartReply(
                  c.leftMap(_.getMessage).toEither
                )
              )
            )

          case _ =>
            logger error s"Unrecognized message: $msg"
            Process.empty
        }
    }


  def apply(
    streams: Streams,
    launchKernel: Streams => Unit,
    kernelId: String
  )(implicit
    es: ExecutorService
  ): Task[Unit] = {

    implicit val strategy = Strategy.Executor

    val reqQueue = async.boundedQueue[Message]()

    val process: (String \/ Message) => Task[Unit] = {
      case -\/(err) =>
        logger debug s"Error while decoding message: $err"
        Task.now(())
      case \/-(msg) =>
        handler(launchKernel, kernelId, msg).evalMap(reqQueue.enqueueOne).run
    }

    Task.gatherUnordered(Seq(
      reqQueue.dequeue.to(streams.requestSink).run,
      streams.requestMessages.evalMap(process).run
    )).map(_ => ())
  }
}
