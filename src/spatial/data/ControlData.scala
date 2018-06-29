package spatial.data

import argon._
import forge.tags._
import spatial.lang._
import spatial.util._
import spatial.internal.spatialConfig

/** Control node schedule */
sealed abstract class CtrlSchedule
case object Sequenced extends CtrlSchedule
case object Pipelined extends CtrlSchedule
case object Streaming extends CtrlSchedule
case object ForkJoin extends CtrlSchedule
case object Fork extends CtrlSchedule

/** Control node level. */
sealed abstract class CtrlLevel
case object Inner extends CtrlLevel { override def toString = "InnerControl" }
case object Outer extends CtrlLevel { override def toString = "OuterControl" }

/** Control node looping. */
sealed abstract class CtrlLooping
case object Single extends CtrlLooping
case object Looped extends CtrlLooping

/** A controller's level in the control hierarchy. Flag marks whether this is an outer controller.
  *
  * Getter:  sym.rawLevel
  * Setter:  sym.rawLevel = (CtrlLevel)
  * Default: undefined
  */
case class ControlLevel(level: CtrlLevel) extends Data[ControlLevel](SetBy.Flow.Self)

/** A controller, counter, or counterchain's owning controller.
  *
  * Getter:  sym.owner
  * Setter:  sym.owner = (Sym[_])
  * Default: undefined
  */
case class CounterOwner(owner: Sym[_]) extends Data[CounterOwner](SetBy.Flow.Consumer)


/** The control schedule determined by the compiler.
  *
  * Option:  sym.getRawSchedule
  * Getter:  sym.rawSchedule
  * Setter:  sym.rawSchedule = (Sched)
  * Default: undefined
  */
case class ControlSchedule(sched: CtrlSchedule) extends Data[ControlSchedule](SetBy.Flow.Self)


/** The control schedule annotated by the user, if any.
  *
  * Option:  sym.getUserSchedule
  * Getter:  sym.userSchedule
  * Setter:  sym.userSchedule = (Sched)
  * Default: undefined
  */
case class UserScheduleDirective(sched: CtrlSchedule) extends Data[UserScheduleDirective](SetBy.User)


/** Metadata holding a list of children within a controller.
  *
  * Note that the type of a child is Ctrl.Node, not Ctrl, since a child cannot be the Host.
  *
  * Getter:  sym.rawChildren
  * Setter:  sym.rawChildren = (Seq[Ctrl.Node])
  * Default: Nil
  */
case class Children(children: Seq[Ctrl.Node]) extends Data[Children](SetBy.Flow.Self)

/** The controller (Ctrl) parent of a symbol within the controller hierarchy.
  *
  * Operations defined outside Accel always have the Host as their parent.
  * If the parent is not the Host, the id corresponds to the direct parent controller.
  * MemReduce, for example, has several isolated blocks which represent the logical accumulation
  * into the accumulator. The id in Ctrl will give the index into the logical stage for every
  * true future stage (e.g. the accumulation stage) and will be -1 for pseudo-stages.
  *
  * Getter:  sym.parent
  * Setter:  sym.parent = (Ctrl)
  * Default: Host
  */
case class ParentCtrl(parent: Ctrl) extends Data[ParentCtrl](SetBy.Flow.Consumer)

/** The scope (Ctrl) parent of a symbol within the controller hierarchy.
  *
  * The controller id corresponds to the logical stage index of the parent controller.
  * MemReduce, for example, has several isolated blocks which represent the logical accumulation
  * into the accumulator. The id in Controller will always give the index into the logical stage.
  *
  * Getter:  sym.scope
  * Setter:  sym.scope = (Ctrl)
  * Default: Host
  */
case class ScopeCtrl(scope: Scope) extends Data[ScopeCtrl](SetBy.Flow.Consumer)



/** The block ID (Blk) a symbol is defined within the IR.
  *
  * If the parent is not the Host, the id is the raw index into the parent controller's
  * list of .blocks where this symbol is defined.
  * MemReduce, for example, has several isolated blocks which represent the logical accumulation
  * into the accumulator. Blk will give the index into the specific block.
  *
  * Getter:  sym.blk
  * Setter:  sym.blk = (Blk)
  * Default: Host
  */
case class DefiningBlk(blk: Blk) extends Data[DefiningBlk](SetBy.Flow.Consumer)


/** The counter associated with a loop iterator. Only defined for loop iterators.
  *
  * Option:  sym.getCounter
  * Getter:  sym.counter
  * Setter:  sym.counter = (Counter)
  * Default: undefined
  */
case class IndexCounter(ctr: Counter[_]) extends Data[IndexCounter](SetBy.Flow.Consumer)


/** Latency of a given inner pipe body - used for control signal generation.
  *
  * Getter:  sym.bodyLatency
  * Setter:  sym.bodyLatency = (Seq[Double])
  * Default: Nil
  */
case class BodyLatency(latency: Seq[Double]) extends Data[BodyLatency](SetBy.Analysis.Self)


/** Initiation interval of a given controller - used for control signal generation.
  *
  * Getter:  sym.II
  * Setter:  sym.II = (Double)
  * Default: 1.0
  */
case class InitiationInterval(interval: Double) extends Data[InitiationInterval](SetBy.Analysis.Self)


/** User-defined initiation interval of a given controller.
  *
  * Option:  sym.userII
  * Setter:  sym.userII = (Option[Double])
  * Default: None
  */
case class UserII(interval: Double) extends Data[UserII](SetBy.User)


/** Memories which are written in a given controller.
  *
  * Getter: sym.writtenMems
  * Setter: sym.writtenMems = (Set[ Sym[_] ])
  * Default: empty set
  */
case class WrittenMems(mems: Set[Sym[_]]) extends Data[WrittenMems](SetBy.Flow.Self)

/** Memories which are read in a given controller.
  *
  * Getter: sym.readMems
  * Setter: sym.readMems = (Set[ Sym[_] ])
  * Default: empty set
  */
case class ReadMems(mems: Set[Sym[_]]) extends Data[ReadMems](SetBy.Flow.Self)


trait ControlData {
  implicit class ControlDataOps(s: Sym[_]) {
    /** Returns an Option of the control level (Inner or Outer) metadata. None if undefined. */
    def getRawLevel: Option[CtrlLevel] = metadata[ControlLevel](s).map(_.level)
    /** Returns the control level (Inner or Outer) metadata. Use .level for most purposes. */
    def rawLevel: CtrlLevel = getRawLevel.getOrElse{throw new Exception(s"No control level defined for $s") }
    def rawLevel_=(level: CtrlLevel): Unit = metadata.add(s, ControlLevel(level))

    def isRawOuter: Boolean = rawLevel == Outer
    def isRawInner: Boolean = rawLevel == Inner

    def getRawSchedule: Option[CtrlSchedule] = metadata[ControlSchedule](s).map(_.sched)
    def rawSchedule: CtrlSchedule = getRawSchedule.getOrElse{ throw new Exception(s"Undefined schedule for $s") }
    def rawSchedule_=(sched: CtrlSchedule): Unit = metadata.add(s, ControlSchedule(sched))

    def isRawSeq: Boolean = rawSchedule == Sequenced
    def isRawPipe: Boolean = rawSchedule == Pipelined
    def isRawStream: Boolean = rawSchedule == Streaming
    def isForkJoin: Boolean = rawSchedule == ForkJoin
    def isFork: Boolean = rawSchedule == Fork

    def getUserSchedule: Option[CtrlSchedule] = metadata[UserScheduleDirective](s).map(_.sched)
    def userSchedule: CtrlSchedule = getUserSchedule.getOrElse{throw new Exception(s"Undefined user schedule for $s") }
    def userSchedule_=(sched: CtrlSchedule): Unit = metadata.add(s, UserScheduleDirective(sched))

    def writtenMems: Set[Sym[_]] = metadata[WrittenMems](s).map(_.mems).getOrElse(Set.empty)
    def writtenMems_=(mems: Set[Sym[_]]): Unit = metadata.add(s, WrittenMems(mems))

    def readMems: Set[Sym[_]] = metadata[ReadMems](s).map(_.mems).getOrElse(Set.empty)
    def readMems_=(mems: Set[Sym[_]]): Unit = metadata.add(s, ReadMems(mems))

    // --- Control Hierarchy --- //

    def getOwner: Option[Sym[_]] = metadata[CounterOwner](s).map(_.owner)
    def owner: Sym[_] = getOwner.getOrElse{throw new Exception(s"Undefined counter owner for $s") }
    def owner_=(own: Sym[_]): Unit = metadata.add(s, CounterOwner(own))

    def rawParent: Ctrl = metadata[ParentCtrl](s).map(_.parent).getOrElse(Ctrl.Host)
    def rawParent_=(p: Ctrl): Unit = metadata.add(s, ParentCtrl(p))

    def rawChildren: Seq[Ctrl.Node] = {
      if (!s.isControl) throw new Exception(s"Cannot get children of non-controller.")
      metadata[Children](s).map(_.children).getOrElse(Nil)
    }
    def rawChildren_=(cs: Seq[Ctrl.Node]): Unit = metadata.add(s, Children(cs))

    // --- Scope Hierarchy --- //

    def rawScope: Scope = metadata[ScopeCtrl](s).map(_.scope).getOrElse(Scope.Host)
    def rawScope_=(scope: Scope): Unit = metadata.add(s, ScopeCtrl(scope))

    // --- IR Hierarchy --- //

    def blk: Blk = metadata[DefiningBlk](s).map(_.blk).getOrElse(Blk.Host)
    def blk_=(b: Blk): Unit = metadata.add(s, DefiningBlk(b))

    def bodyLatency: Seq[Double] = metadata[BodyLatency](s).map(_.latency).getOrElse(Nil)
    def bodyLatency_=(latencies: Seq[Double]): Unit = metadata.add(s, BodyLatency(latencies))
    def bodyLatency_=(latency: Double): Unit = metadata.add(s, BodyLatency(Seq(latency)))

    @stateful def latencySum: Double = if (spatialConfig.enableRetiming) s.bodyLatency.sum else 0.0

    def II: Double = metadata[InitiationInterval](s).map(_.interval).getOrElse(1.0)
    def II_=(interval: Double): Unit = metadata.add(s, InitiationInterval(interval))

    def userII: Option[Double] = metadata[UserII](s).map(_.interval)
    def userII_=(interval: Option[Double]): Unit = interval.foreach{ii => metadata.add(s, UserII(ii)) }
  }

  implicit class IndexCounterOps[A](i: Num[A]) {
    def getCounter: Option[Counter[A]] = metadata[IndexCounter](i).map(_.ctr.asInstanceOf[Counter[A]])
    def counter: Counter[A] = getCounter.getOrElse{throw new Exception(s"No counter associated with $i") }
    def counter_=(ctr: Counter[_]): Unit = metadata.add(i, IndexCounter(ctr))
  }
}



