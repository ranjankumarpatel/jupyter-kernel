package jupyter
package kernel
package server

import java.util.UUID
import java.util.concurrent.{ ConcurrentHashMap, ExecutorService }

import argonaut.{ Json, Parse }

import scala.collection.mutable

import com.typesafe.scalalogging.slf4j.LazyLogging

import interpreter.{ InterpreterHandler, Interpreter }
import jupyter.api._
import jupyter.kernel.stream.Streams
import protocol._, Formats._, jupyter.kernel.protocol.Output.ConnectReply

import scalaz.concurrent.{ Strategy, Task }
import scalaz.stream.async

import scalaz.{ -\/, \/, \/- }

object InterpreterServer extends LazyLogging {

  def apply(
    streams: Streams,
    connectReply: ConnectReply,
    interpreter: Interpreter
  )(implicit
    es: ExecutorService
  ): Task[Unit] = {

    implicit val strategy = Strategy.Executor

    val reqQueue = async.boundedQueue[Message]()
    val contQueue = async.boundedQueue[Message]()
    val pubQueue = async.boundedQueue[Message]()
    val stdinQueue = async.boundedQueue[Message]()

    val targetHandlers = new ConcurrentHashMap[String, CommChannelMessage => Unit]

    class CommImpl(val id: String) extends Comm[ParsedMessage[_]] {

      def target: Option[String] = ???
      private var target0 = Option.empty[String]

      def received(msg: CommChannelMessage) = {

        msg match {
          case CommOpen(target, _) =>
            target0 = Some(target).filter(_.nonEmpty)

            for  {
              t <- target0
              h <- Option(targetHandlers.get(t))
            }
              messageHandlers = messageHandlers :+ h

          case _ =>
        }

        messageHandlers.foreach(_(msg))
      }

      def send(msg: CommChannelMessage)(implicit t: ParsedMessage[_]) = {
        def parse(s: String): Json =
          Parse.parse(s).leftMap(err => throw new IllegalArgumentException(s"Malformed JSON: $s ($err)")).merge

        pubQueue.enqueueOne(msg match {
          case CommOpen(target, data) =>
            t.pub("comm_open", InputOutput.CommOpen(id, target, parse(data)))
          case CommMessage(data) =>
            t.pub("comm_msg", InputOutput.CommMsg(id, parse(data)))
          case CommClose(data) =>
            t.pub("comm_close", InputOutput.CommClose(id, parse(data)))
        }).run

        sentMessageHandlers.foreach(_(msg))
      }

      var sentMessageHandlers = Seq.empty[CommChannelMessage => Unit]
      var messageHandlers = Seq.empty[CommChannelMessage => Unit]

      def onMessage(f: CommChannelMessage => Unit) =
        messageHandlers = messageHandlers :+ f
      def onSentMessage(f: CommChannelMessage => Unit) =
        sentMessageHandlers = sentMessageHandlers :+ f
    }

    object CommImpl {
      val comms = new mutable.HashMap[String, CommImpl]
      def apply(id: String) = comms.getOrElseUpdate(id, new CommImpl(id))
    }

    interpreter.publish(new Publish[ParsedMessage[_]] {
      def stdout(text: String)(implicit t: ParsedMessage[_]) =
        pubQueue.enqueueOne(t.pub("stream", Output.Stream(name = "stdout", text = text))).run
      def stderr(text: String)(implicit t: ParsedMessage[_]) =
        pubQueue.enqueueOne(t.pub("stream", Output.Stream(name = "stderr", text = text))).run
      def display(source: String, items: (String, String)*)(implicit t: ParsedMessage[_]) =
        pubQueue.enqueueOne(t.pub("display_data", Output.DisplayData(source = source, data = items.toMap, metadata = Map.empty))).run

      def comm(id: String) = CommImpl(id)

      def commHandler(target: String)(handler: CommChannelMessage => Unit) =
        targetHandlers.put(target, handler)
    })

    val process: (String \/ Message) => Task[Unit] = {
      case -\/(err) =>
        logger debug s"Error while decoding message: $err"
        Task.now(())
      case \/-(msg) =>
        InterpreterHandler(interpreter, connectReply, CommImpl(_).received(_), msg).evalMap {
          case \/-((Channel.Requests, m)) =>
            reqQueue enqueueOne m
          case \/-((Channel.Control, m)) =>
            contQueue enqueueOne m
          case \/-((Channel.Publish, m)) =>
            pubQueue enqueueOne m
          case \/-((Channel.Input, m)) =>
            stdinQueue enqueueOne m
          case -\/(err) =>
            logger debug s"Error while handling message: $err"
            Task.now(())
        }.run
    }

    Task.gatherUnordered(Seq(
      {
        pubQueue enqueueOne {
          ParsedMessage(
            "status".getBytes("UTF-8") :: Nil,
            Header(
              msg_id = UUID.randomUUID().toString,
              username = "scala_kernel",
              session = UUID.randomUUID().toString,
              msg_type = "status",
              version = Protocol.versionStrOpt
            ),
            None,
            Map.empty,
            Output.Status(ExecutionState.starting)
          ).toMessage
        }
      },
      reqQueue.dequeue.to(streams.requestSink).run,
      contQueue.dequeue.to(streams.controlSink).run,
      pubQueue.dequeue.to(streams.publishSink).run,
      stdinQueue.dequeue.to(streams.inputSink).run,
      streams.requestMessages.evalMap(process).run,
      streams.controlMessages.evalMap(process).run
    )).map(_ => ())
  }

}
