package spatial.metadata

import argon._

package object retiming {

  implicit class RetimingOps(s: Sym[_]) {
    def reduceCycle: Seq[Sym[_]] = metadata[ReduceCycle](s).map(_.x).getOrElse(Nil)
    def reduceCycle_=(cycle: Seq[Sym[_]]): Unit = metadata.add(s, ReduceCycle(cycle))

    def fullDelay: Double = metadata[FullDelay](s).map(_.latency).getOrElse(0.0)
    def fullDelay_=(d: Double): Unit = metadata.add(s, FullDelay(d))
  }

}
