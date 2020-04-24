package sttp.tapir.server

import java.io.File
import java.nio.ByteBuffer
import java.util.Date

import io.vertx.core.Handler
import io.vertx.core.http.HttpMethod
import io.vertx.lang.scala.VertxExecutionContext
import io.vertx.scala.ext.web.handler.BodyHandler
import io.vertx.scala.ext.web.{Route, Router, RoutingContext}
import sttp.model.Part
import sttp.tapir.EndpointInput.PathCapture
import sttp.tapir._
import sttp.tapir.internal._
import sttp.tapir.server.internal._

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Random, Success, Try}

package object vertx {

  private [vertx] implicit class RichContextHandler(rc: RoutingContext) {
    implicit val executionContext: VertxExecutionContext = VertxExecutionContext(rc.vertx.getOrCreateContext)
  }

  implicit class VertxEndpoint[I, E, O, D](e: Endpoint[I, E, O, D]) {

    def asRoute(logic: I => Future[Either[E, O]])
               (implicit serverOptions: VertxServerOptions): Router => Route = { router =>
        attach(router, endpointToRoute(e), None).handler(logicAsHandlerWithError(logic))
    }

    def asRouteRecoverErrors(logic: I => Future[O])
                            (implicit serverOptions: VertxServerOptions, eIsThrowable: E <:< Throwable, eClassTag: ClassTag[E]): Router => Route = { router =>
      val ect = implicitly[ClassTag[E]]
      attach(router, endpointToRoute(e), Some(ect)).handler(logicAsHandlerNoError(logic, ect))
    }

    def attach(router: Router, route: (Option[HttpMethod], String), eClassTag: Option[ClassTag[E]])
              (implicit serverOptions: VertxServerOptions): Route = {
      val attachedRoute =
        route match {
          case (Some(method), path) => router.route(method, path)
          case (None, path) => router.route(path)
        }
      attachGlobalHandlers(attachedRoute, eClassTag)
    }

    private def attachGlobalHandlers(route: Route, ect: Option[ClassTag[E]])
                               (implicit serverOptions: VertxServerOptions): Route = {
      route.failureHandler(rc => tryEncodeError(rc, rc.failure, ect))
      val usesBody = e.input.asVectorOfBasicInputs().exists {
        case _: EndpointIO.Body[_, _] => true
        case _: EndpointIO.StreamBodyWrapper[_, _] => true
        case _ => false
      }
      if (usesBody) {
        route.handler(BodyHandler.create(true))
      }
      route
    }

    private def logicAsHandler[A](logicResponseHandler: (Params, RoutingContext) => Unit, ect: Option[ClassTag[E]])
                                 (implicit serverOptions: VertxServerOptions): Handler[RoutingContext] = { rc =>
      val response = rc.response
      decodeBody(DecodeInputs(e.input, new VertxDecodeInputsContext(rc)), rc) match {
        case values: DecodeInputsResult.Values =>
          InputValues(e.input, values) match {
            case InputValuesResult.Value(params, _) =>
              logicResponseHandler(params, rc)
            case InputValuesResult.Failure(_, failure) =>
              tryEncodeError(rc, failure, ect)
          }
        case DecodeInputsResult.Failure(input, failure) =>
          val decodeFailureCtx = DecodeFailureContext(input, failure)
          serverOptions.decodeFailureHandler(decodeFailureCtx) match { // TODO: do not use default, but create a VertxServerOptions instead
            case DecodeFailureHandling.NoMatch =>
              // serverOptions.logRequestHandling.decodeFailureNotHandled(e, decodeFailureCtx)(ctx.log) // TODO: ServerOptions (logging)
              response.setStatusCode(404).end()
            case DecodeFailureHandling.RespondWithResponse(output, value) =>
              // serverOptions.logRequestHandling.decodeFailureHandled(e, decodeFailureCtx, value)(ctx.log)  // TODO: ServerOptions (logging)
              VertxOutputEncoders.apply(output, value)(rc)
          }
      }
    }

    private def logicAsHandlerNoError(logic: I => Future[O], ect: ClassTag[E])
                                     (implicit serverOptions: VertxServerOptions): Handler[RoutingContext] =
      logicAsHandler({ (params, rc) =>
        Try(logic(params.asAny.asInstanceOf[I])) match {
          case Success(output) =>
            output.onComplete {
              case Success(result) =>
                VertxOutputEncoders.apply[O](e.output, result)(rc)
              case Failure(cause) =>
                tryEncodeError(rc, cause, Some(ect))
            }(rc.executionContext)
          case Failure(cause) =>
            tryEncodeError(rc, cause, Some(ect))
        }
      }, Some(ect))

    private def logicAsHandlerWithError(logic: I => Future[Either[E, O]])
                                       (implicit serverOptions: VertxServerOptions): Handler[RoutingContext] =
      logicAsHandler({ (params, rc) =>
        Try(logic(params.asAny.asInstanceOf[I])) match {
          case Success(output) =>
            output.onComplete {
              case Success(result) => result match {
                case Left(failure) =>
                  encodeError(rc, failure)
                case Right(result) =>
                  VertxOutputEncoders.apply[O](e.output, result)(rc)
              }
              case Failure(cause) => tryEncodeError(rc, cause, None)
            }(rc.executionContext)
          case Failure(cause) => tryEncodeError(rc, cause, None)
        }
      }, None)

    private def tryEncodeError(rc: RoutingContext, error: Any, ect: Option[ClassTag[E]])
                              (implicit serverOptions: VertxServerOptions): Unit =
      (error, ect) match {
        case (exception: Throwable, Some(ct)) if ct.runtimeClass.isInstance(exception) =>
          encodeError(rc, error.asInstanceOf[E])
        case _ => rc.response.setStatusCode(500).end()
      }

    private def encodeError(rc: RoutingContext, error: E): Unit = {
      try {
        VertxOutputEncoders.apply[E](e.errorOutput, error, isError = true)(rc)
      } catch {
        case _: Throwable => rc.response.setStatusCode(500).end()
      }
    }
  }


  private def endpointToRoute(endpoint: Endpoint[_,_,_,_]): (Option[HttpMethod], String) = {
    var idxUsed = 0
    val p = endpoint.input
      .asVectorOfBasicInputs()
      .collect {
        case segment: EndpointInput.FixedPath[_] => segment.show
        case PathCapture(Some(name), _, _)       => s"/:$name"
        case PathCapture(_, _, _)                =>
          idxUsed += 1
          s"/:param$idxUsed"
        case EndpointInput.PathsCapture(_, _)    => "/*"
      }
      .mkString
    (MethodMapping.sttpToVertx(endpoint.httpMethod), if (p.isEmpty) "/*" else p)
  }

  def decodeBody(result: DecodeInputsResult, rc: RoutingContext): DecodeInputsResult = {
    result match {
      case values: DecodeInputsResult.Values =>
        values.bodyInput match {
          case None => values
          case Some(bodyInput@EndpointIO.Body(bodyType, codec, _)) =>
            codec.decode(extractRawBody(bodyType, rc)) match {
              case DecodeResult.Value(body) => values.setBodyInputValue(body)
              case failure: DecodeResult.Failure => DecodeInputsResult.Failure(bodyInput, failure): DecodeInputsResult
        }
      }
      case failure: DecodeInputsResult.Failure => failure
    }
  }

  def extractRawBody[B](bodyType: RawBodyType[B], rc: RoutingContext): Any =
    bodyType match {
      case RawBodyType.StringBody(defaultCharset) => rc.getBodyAsString(defaultCharset.toString).get
      case RawBodyType.ByteArrayBody => rc.getBodyAsString.get.getBytes()
      case RawBodyType.ByteBufferBody => rc.getBody.get.getByteBuf.nioBuffer()
      case RawBodyType.InputStreamBody => throw new UnsupportedOperationException("Input streams are blocking, thus incompatible with Vert.x") // TODO: executeBlocking ?
      case RawBodyType.FileBody =>
        rc.fileUploads().toList match {
          case List(upload) =>
            new File(upload.uploadedFileName())
          case List() if rc.getBody.isDefined => // README: really weird, but there's a test that sends the body as String, and expects a File
            val filePath = s"/tmp/${new Date().getTime}-${Random.nextLong()}"// FIXME: configurable upload directory
            try  {
              rc.vertx().fileSystem().createFileBlocking(filePath)
              rc.vertx().fileSystem().writeFileBlocking(filePath, rc.getBody.get)
              new File(filePath)
            } catch {
              case e: Throwable =>
                e.printStackTrace()
                new File("")
            }
          case _ => throw new IllegalArgumentException("Cannot expect a file to be returned without sending body or file upload")
        }
      case RawBodyType.MultipartBody(partTypes, _) =>
        partTypes.map { case (partName, rawBodyType) =>
          Part(partName, extractPart(partName, rawBodyType, rc))
        }
    }

  private def extractPart(name: String, bodyType: RawBodyType[_], rc: RoutingContext): Any = {
    val formAttributes = rc.request().formAttributes()
    val param = formAttributes.get(name)
    bodyType match {
      case RawBodyType.StringBody(charset) => new String(param.get.getBytes(charset))
      case RawBodyType.ByteArrayBody => param.get.getBytes
      case RawBodyType.ByteBufferBody => ByteBuffer.wrap(param.get.getBytes)
      case RawBodyType.InputStreamBody => throw new IllegalArgumentException("Cannot create a multipart as an InputStream")
      case RawBodyType.FileBody =>
        val f = rc.fileUploads.find(_.name == name).get
        new File(f.uploadedFileName())
      case RawBodyType.MultipartBody(partTypes, _) =>
        partTypes.map { case (partName, rawBodyType) =>
          Part(partName, extractPart(partName, rawBodyType, rc))
        }
    }
  }


}
