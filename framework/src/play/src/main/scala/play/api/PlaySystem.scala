package play.api

import akka.actor.ActorSystem
import scala.concurrent.ExecutionContext

trait PlaySystem {

  def internalExecutionContext: ExecutionContext
  def defaultActorSystem: ActorSystem
  def defaultExecutionContext: ExecutionContext

}