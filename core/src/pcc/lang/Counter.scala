package pcc.lang

import forge._
import pcc.core._
import pcc.node._

/** Types **/
case class Counter() extends Top[Counter] {
  override type I = Range
  override def fresh: Counter = new Counter
  override def isPrimitive: Boolean = false
}
object Counter {
  implicit val tp: Counter = (new Counter).asType

  @api def fromSeries(series: Series): Counter = Counter(series.start,series.end,series.step,series.par)

  @api def apply(start: I32, end: I32, step: I32 = I32.c(1), par: I32 = I32.c(1)): Counter = {
    stage(CounterNew(start, end, step, par))
  }
}
