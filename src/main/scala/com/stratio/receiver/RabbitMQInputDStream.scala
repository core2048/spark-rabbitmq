/**
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.receiver

import java.util

import scala.util._

import com.rabbitmq.client._
import org.apache.spark.Logging
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream.ReceiverInputDStream
import org.apache.spark.streaming.receiver.Receiver

private[receiver]
class RabbitMQInputDStream(
                            @transient ssc_ : StreamingContext,
                            rabbitMQQueueName: Option[String],
                            rabbitMQHost: String,
                            exchangeName: Option[String],
                            routingKeys: Seq[String],
                            storageLevel: StorageLevel,
                            vhost: String,
                            username: String,
                            password: String
                            ) extends ReceiverInputDStream[String](ssc_) with Logging {

  override def getReceiver(): Receiver[String] = {

    new RabbitMQReceiver(rabbitMQQueueName,
      rabbitMQHost,
      exchangeName,
      routingKeys,
      storageLevel,
      vhost,
      username,
      password)
  }
}

private[receiver]
class RabbitMQReceiver(rabbitMQQueueName: Option[String],
                       rabbitMQHost: String,
                       exchangeName: Option[String],
                       routingKeys: Seq[String],
                       storageLevel: StorageLevel,
                       vhost: String,
                       username: String,
                       password: String)
  extends Receiver[String](storageLevel) with Logging {

  val DirectExchangeType: String = "direct"
  val TopicExchangeType: String = "topic"
  val DefaultRabbitMQPort = 5672

      
  def onStart() {
    implicit val akkaSystem = akka.actor.ActorSystem()
    getConnectionAndChannel match {
      case Success((connection: Connection, channel: Channel)) => log.info("onStart, Connecting.."); receive(connection, channel)
      case Failure(f) => log.error("Could not connect"); restart("Could not connect", f)
    }
  }

  def onStop() {
    // There is nothing much to do as the thread calling receive()
    // is designed to stop by itself isStopped() returns false
    log.info("onStop, doing nothing.. relaxing...")
  }

  /** Create a socket connection and receive data until receiver is stopped */
  private def receive(connection: Connection, channel: Channel) {

    try {
      val queueName = !routingKeys.isEmpty match {
        case true => {
          log.info("declaring topic queue")
          channel.exchangeDeclare(exchangeName.get, TopicExchangeType, true)
          val queueName = channel.queueDeclare("sparkQueue", true, false, false, new util.HashMap(0)).getQueue()
  
          for (routingKey: String <- routingKeys) {
            log.info("binding to routing key " + routingKey)
            channel.queueBind(queueName, exchangeName.get, routingKey)
          }
          queueName
        }
        case false => {
          log.info("declaring direct queue")
          channel.queueDeclare(rabbitMQQueueName.get, true, false, false, new util.HashMap(0))
          rabbitMQQueueName.get
        }
      }
  
      log.info("RabbitMQ Input waiting for messages")
      val consumer: QueueingConsumer = new QueueingConsumer(channel)
      log.info("start consuming data")
      channel.basicConsume(queueName, true, consumer)
  
      while (true) {
        log.info("wating for data")
        val delivery: QueueingConsumer.Delivery = consumer.nextDelivery()
        log.info("storing data")
        store(new String(delivery.getBody))
      }
    } catch {
      case unknown : Throwable => log.error("Got this unknown exception: " + unknown)
    } 
    finally {
      log.info("it has been stopped")
      try { channel.close } catch {case _: Throwable => log.error("error on close channel, ignoring")}
      try { connection.close} catch {case _: Throwable => log.error("error on close connection, ignoring")}
      restart("Trying to connect again")
    }
  }

  private def getConnectionAndChannel: Try[(Connection, Channel)] = {
    log.info("rabbit host addresses are :" + rabbitMQHost)
    for ( addr <- Address.parseAddresses(rabbitMQHost) ) {
      log.info("address " + addr.toString())
    }

    log.info("creating new connection and channel")
    for {
      connection: Connection <- Try(getConnectionFactory.newConnection(Address.parseAddresses(rabbitMQHost)))
      channel: Channel <- Try(connection.createChannel)
    } yield {
      log.info("created new connection and channel")
      (connection, channel)
    }
  }

  private def getConnectionFactory: ConnectionFactory = {
    val factory: ConnectionFactory = new ConnectionFactory
    log.info("vhost username password #" + vhost + "# #"+username+"# #"+password+"#")
    factory.setVirtualHost(vhost)
    factory.setUsername(username)
    factory.setPassword(password)
    factory
  }
}