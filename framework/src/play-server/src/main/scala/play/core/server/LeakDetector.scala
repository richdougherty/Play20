package play.core.server

import java.lang.ref.WeakReference

private[play] class LeakDetector {

  var control: AnyRef = new Object()
  var controlRef: WeakReference[AnyRef] = new WeakReference(control)
  var targetRef: WeakReference[AnyRef] = null

  def setTarget(target: AnyRef): Unit = {
    assert(control != null)
    assert(controlRef != null)
    assert(targetRef == null)
    println(s"Setting leak target to $target")
    targetRef = new WeakReference(target)
  }

  def checkLeak(): Boolean = {
    assert(control != null)
    assert(controlRef != null)
    assert(controlRef.get != null)
    assert(targetRef != null)
    control = null
    System.gc()
    while (controlRef.get != null) {
      Thread.sleep(5)
      System.gc()
    }
    val targetHasLeaked = targetRef.get != null
    targetRef = null
    targetHasLeaked
  }

}