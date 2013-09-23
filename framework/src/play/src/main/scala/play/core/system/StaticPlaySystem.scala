package play.core.system

import akka.actor.ActorSystem
import play.api.PlaySystem
import scala.concurrent.ExecutionContext

object StaticPlaySystem extends PlaySystem {

  private[play] lazy val delegate = DefaultPlaySystem()

  def internalExecutionContext = delegate.internalExecutionContext
  def defaultActorSystem = delegate.defaultActorSystem
  def defaultExecutionContext = delegate.defaultExecutionContext

}