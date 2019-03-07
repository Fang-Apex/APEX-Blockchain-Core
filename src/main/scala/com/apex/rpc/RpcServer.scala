/*
 * Copyright  2018 APEX Technologies.Co.Ltd. All rights reserved.
 *
 * FileName: RpcServer.scala
 *
 * @author: shan.huang@chinapex.com: 2018-08-01 下午4:06@version: 1.0
 */

package com.apex.rpc

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.apex.common.ApexLogging
import com.apex.consensus.{WitnessInfo, WitnessList}
import com.apex.core.{Account, Block, TransactionReceipt}
import com.apex.settings.ApexSettings
import com.typesafe.config.Config
import play.api.libs.json._

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}

object RpcServer extends ApexLogging {

  implicit val timeout = Timeout(5.seconds)

  private implicit var system: ActorSystem = _
  private implicit var materializer: ActorMaterializer = _
  private implicit var dispatcher: ExecutionContextExecutor = _

  private var bindingFuture: Future[Http.ServerBinding] = _
  private var secretBindingFuture: Future[Http.ServerBinding] = _

  def run(settings: ApexSettings, config: Config, nodeRef: ActorRef) = {
    system = ActorSystem("RPC", config)
    materializer = ActorMaterializer()
    dispatcher = getDispatcher("apex.actor.rpc-dispatcher")
    system.registerOnTermination(log.info("rpc terminated"))

    val rpcSettings = settings.rpc
    val secretRPCSettings = settings.secretRpc
    val route =
      path("getblock") {
        post {
          entity(as[String]) { data =>
            Json.parse(data).validate[GetBlockByHeightCmd] match {
              case cmd: JsSuccess[GetBlockByHeightCmd] => {
                val f = (nodeRef ? cmd.get).mapTo[String]
                complete(f)
              }
              case _: JsError => {
                Json.parse(data).validate[GetBlockByIdCmd] match {
                  case cmd: JsSuccess[GetBlockByIdCmd] => {
                    val f = (nodeRef ? cmd.get).mapTo[String]
                    complete(f)
                  }
                  case _: JsError => {
                    //  log.error("", idError)
                    complete(HttpEntity(ContentTypes.`application/json`, Json.parse( """ {"succeed": false,"status": 400,"message": "Param check error"}""").toString()))
                  }
                }
              }
            }
          }
        }
      } ~
        path("getblocks") {
          post {
            entity(as[String]) { _ =>
              val f = (nodeRef ? GetBlocksCmd()).mapTo[String]
              complete(f)
            }
          }
        } ~
        path("showaccount") { // getaccount
          post {
            entity(as[String]) { data =>
              Json.parse(data).validate[GetAccountCmd] match {
                case cmd: JsSuccess[GetAccountCmd] => {
                  val f = (nodeRef ? cmd.value) .mapTo[String]
                  complete(f)
                }
                case e: JsError => {
                  println(e)
                  complete(HttpEntity(ContentTypes.`application/json`, Json.parse( """ {"succeed": false,"status": 400,"message": "Param check error"}""").toString()))
                }
              }
            }
          }
        } ~
        path("getContract") {
          post {
            entity(as[String]) { data =>
              Json.parse(data).validate[GetContractByIdCmd] match {
                case cmd: JsSuccess[GetContractByIdCmd] => {
                  val f = (nodeRef ? cmd.get).mapTo[String]
                  complete(f)
                }
                case _: JsError => {
                  //  log.error("", idError)
                  complete(HttpEntity(ContentTypes.`application/json`, Json.parse( """ {"succeed": false,"status": 400,"message": "Param check error"}""").toString()))
                }
              }
            }
          }
        } ~
        path("sendrawtransaction") {
          post {
            entity(as[String]) { data =>
              Json.parse(data).validate[SendRawTransactionCmd] match {
                case cmd: JsSuccess[SendRawTransactionCmd] => {
                  log.info("send transaction: "+Json.toJson(cmd.value.rawTx).toString())
                  val f = (nodeRef ? cmd.value).mapTo[String]
                  complete(f)
                }
                case e: JsError => {
                  println(e)
                  complete(HttpEntity(ContentTypes.`application/json`, Json.parse( """ {"succeed": false,"status": 400,"message": "Param check error"}""").toString()))
                }
              }
            }
          }
        } ~
        path("getblockheight") {
          post {
            entity(as[String]) { _ =>
              val f = (nodeRef ? GetBlockCountCmd()).mapTo[String]
              complete(f)
            }
          }
        } ~
        path("getProducers") {
          post {
            entity(as[String]) { data =>
              Json.parse(data).validate[GetProducersCmd] match {
                case cmd: JsSuccess[GetProducersCmd] => {
                  val f = (nodeRef ? cmd.get).mapTo[String]
                  complete(f)
                }
                case _: JsError => {
                  complete(HttpEntity(ContentTypes.`application/json`, Json.parse( """ {"succeed": false,"status": 400,"message": "Param check error"}""").toString()))
                }
              }
            }
          }
        } ~
        path("getProducer") {
          post {
            entity(as[String]) { data =>
              Json.parse(data).validate[GetProducerCmd] match {
                case cmd: JsSuccess[GetProducerCmd] => {
                  val f = (nodeRef ? cmd.value).mapTo[String]
                  complete(f)
                }
                case e: JsError => {
                  complete(HttpEntity(ContentTypes.`application/json`, Json.parse( """ {"succeed": false,"status": 400,"message": "Param check error"}""").toString()))
                }
              }
            }
          }
        }

    val secretRoute =
      path("getGasLimit") {
        post {
          entity(as[String]) { _ =>
            val f = (nodeRef ? GetGasLimitCmd()).mapTo[String]
            complete(f)
          }
        }
      } ~
        path("setGasLimit") {
          post {
            entity(as[String]) { data =>
              Json.parse(data).validate[SetGasLimitCmd] match {
                case cmd: JsSuccess[SetGasLimitCmd] => {
                  val f = (nodeRef ? cmd.get).mapTo[String]
                  complete(f)
                }
                case _: JsError => {
                  complete(HttpEntity(ContentTypes.`application/json`, Json.parse( """ {"succeed": false,"status": 400,"message": "Param check error"}""").toString()))
                }
              }
            }
          }
        }

    bindingFuture = Http().bindAndHandle(route, rpcSettings.host, rpcSettings.port)
    secretBindingFuture = Http().bindAndHandle(secretRoute, secretRPCSettings.host, secretRPCSettings.port)
    //    println(s"Server online at http://${rpcSettings.host}:${rpcSettings.port}/\n")
    //  StdIn.readLine() // let it run until user presses return
  }

  private def getDispatcher(id: String) = {
    if (system.dispatchers.hasDispatcher(id)) {
      system.dispatchers.lookup(id)
    }  else {
      system.dispatcher
    }
  }

//  def stop() = {
//    log.info("stopping rpc server")
//    system.terminate.foreach(_ => log.info("rpc server stopped"))
//  }

}


