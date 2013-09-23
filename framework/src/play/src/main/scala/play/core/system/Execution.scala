package play.core

import play.core.system.StaticPlaySystem
import scala.concurrent.ExecutionContext

private[play] object Execution {

  def internalContext: ExecutionContext = StaticPlaySystem.internalExecutionContext

  object Implicits {

    implicit def internalContext = Execution.internalContext

  }
}
