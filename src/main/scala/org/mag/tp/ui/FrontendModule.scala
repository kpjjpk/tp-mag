package org.mag.tp.ui

import com.softwaremill.macwire.wire
import com.softwaremill.tagging.{ @@ => @@ }
import com.softwaremill.tagging.Tagger

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props

trait FrontendModule {
  def createFrontendActor(): ActorRef @@ FrontendActor = {
    system.actorOf(Props(wire[FrontendActor])).taggedWith[FrontendActor]
  }

  val loggers = Seq[ActorRef]()

  def system: ActorSystem
}
