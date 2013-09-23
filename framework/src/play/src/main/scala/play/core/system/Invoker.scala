package play.core

import akka.actor._
import com.typesafe.config._
import play.api.{ Logger, Play }
import play.core.system.StaticPlaySystem
import scala.concurrent.ExecutionContext

/**
 * provides Play's internal actor system and the corresponding actor instances
 */
private[play] object Invoker {

  def system: ActorSystem = StaticPlaySystem.defaultActorSystem
  val executionContext: ExecutionContext = StaticPlaySystem.defaultExecutionContext

}
