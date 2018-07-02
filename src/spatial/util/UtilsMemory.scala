package spatial.util

import argon._
import argon.node.Enabled
import forge.tags._
import utils.implicits.collections._
import poly.ISL
import spatial.data._
import spatial.lang._
import spatial.node._

trait UtilsMemory { this: UtilsControl with UtilsHierarchy =>
  implicit class SymMemories(a: Sym[_]) {
    // Weird scalac issue is preventing x.isInstanceOf[C[_,_]]?

    def isLocalMem: Boolean = a match {
      case _: LocalMem[_,_] => true
      case _ => false
    }
    def isRemoteMem: Boolean = a match {
      case _: RemoteMem[_,_] => true
      case _: Reg[_] => a.isArgOut || a.isArgIn || a.isHostIO
      case _ => false
    }
    def isMem: Boolean = isLocalMem || isRemoteMem
    def isDenseAlias: Boolean = a.op.exists{
      case _: MemDenseAlias[_,_,_] => true
      case _ => false
    }
    def isSparseAlias: Boolean = a.op.exists{
      case _: MemSparseAlias[_,_,_,_] => true
      case _ => false
    }

    def isReg: Boolean = a.isInstanceOf[Reg[_]]
    def isArgIn: Boolean = a.isReg && a.op.exists{ _.isInstanceOf[ArgInNew[_]] }
    def isArgOut: Boolean = a.isReg && a.op.exists{ _.isInstanceOf[ArgOutNew[_]] }
    def isHostIO: Boolean = a.isReg && a.op.exists{ _.isInstanceOf[HostIONew[_]] }

    def isDRAM: Boolean = a match {
      case _:DRAM[_,_] => true
      case _ => false
    }

    def isSRAM: Boolean = a match {
      case _: SRAM[_,_] => true
      case _ => false
    }
    def isRegFile: Boolean = a match {
      case _: RegFile[_,_] => true
      case _ => false
    }
    def isFIFO: Boolean = a.isInstanceOf[FIFO[_]]
    def isLIFO: Boolean = a.isInstanceOf[LIFO[_]]

    def isLUT: Boolean = a match {
      case _: LUT[_,_] => true
      case _ => false
    }

    def isStreamIn: Boolean = a.isInstanceOf[StreamIn[_]]
    def isStreamOut: Boolean = a.isInstanceOf[StreamOut[_]]
    def isInternalStream: Boolean = (a.isStreamIn || a.isStreamOut) && a.parent != Ctrl.Host

    def isStatusReader: Boolean = StatusReader.unapply(a).isDefined
    def isReader: Boolean = Reader.unapply(a).isDefined
    def isWriter: Boolean = Writer.unapply(a).isDefined

    def banks: Seq[Seq[Idx]] = {
      a match {
        case Op(SRAMBankedRead(_,bank,_,_)) => bank
        case Op(SRAMBankedWrite(_,_,bank,_,_)) => bank
        case _ => Seq(Seq())
      }
    }

    def isDirectlyBanked: Boolean = {
      if (a.banks.toList.flatten.isEmpty) false
      else if (a.banks.head.head.isConst) true
      else false
    }

    /** Returns the sequence of enables associated with this symbol. */
    @stateful def enables: Set[Bit] = a match {
      case Op(d:Enabled[_]) => d.ens
      case _ => Set.empty
    }

    /** Returns true if an execution of access a may occur before one of access b. */
    @stateful def mayPrecede(b: Sym[_]): Boolean = {
      val (ctrl,dist) = LCAWithDataflowDistance(b.parent, a.parent)
      dist <= 0 || (dist > 0 && ctrl.willRunMultiple)
    }

    /** Returns true if an execution of access a may occur after one of access b. */
    @stateful def mayFollow(b: Sym[_]): Boolean = {
      val (ctrl,dist) = LCAWithDataflowDistance(b.parent, a.parent)
      dist >= 0 || (dist < 0) && ctrl.willRunMultiple
    }

    /** Returns true if access b must happen every time the body of controller ctrl is run
      * This case holds when all of the enables of ctrl are true
      * and when b is not contained in a switch within ctrl
      *
      * NOTE: Usable only before unrolling (so enables will not yet include boundary conditions)
      */
    @stateful def mustOccurWithin(ctrl: Ctrl): Boolean = {
      val parents = a.ancestors(ctrl)
      val enables = (a +: parents.flatMap(_.s)).flatMap(_.enables)
      !parents.exists(_.isSwitch) && enables.forall{case Const(b) => b.value; case _ => false }
     }

    /** Returns true if access a must always come after access b, relative to some observer access p
      * NOTE: Usable only before unrolling
      *
      * For orderings:
      *   0. b p a - false
      *   1. a b p - false
      *   2. p a b - false
      *   3. b a p - true if b does not occur within a switch
      *   4. a p b - true if in a loop and b does not occur within a switch
      *   5. p b a - true if b does not occur within a switch
      */
    @stateful def mustFollow(b: Sym[_], p: Sym[_]): Boolean = {
      val (ctrlA,distA) = LCAWithDataflowDistance(a, p) // Positive for a * p, negative otherwise
      val (ctrlB,distB) = LCAWithDataflowDistance(b, p) // Positive for b * p, negative otherwise
      val ctrlAB = LCA(a,b)
      if      (distA > 0 && distB > 0) { distA < distB && a.mustOccurWithin(ctrlAB) }   // b a p
      else if (distA > 0 && distB < 0) { ctrlA.willRunMultiple && a.mustOccurWithin(ctrlAB) } // a p b
      else if (distA < 0 && distB < 0) { distA < distB && ctrlA.willRunMultiple && a.mustOccurWithin(ctrlAB) } // p b a
      else false
    }

    def hasInitialValues: Boolean = a match {
      case Op(RegNew(_)) => true
      case Op(RegFileNew(_,inits)) => inits.isDefined
      case Op(LUTNew(_,_)) => true
      case _ => false
    }
  }


  /** Returns the statically defined rank (number of dimensions) of the given memory. */
  def rankOf(mem: Sym[_]): Seq[Int] = mem match {
    case Op(m: MemAlloc[_,_])   => m.rank
    case Op(m: MemAlias[_,_,_]) => m.rank
    case _ => throw new Exception(s"Could not statically determine the rank of $mem")
  }

  /** Returns the statically defined underlying rank (number of dimensions) of the given memory. */
  def rawRankOf(mem: Sym[_]): Seq[Int] = mem match {
    case Op(m: MemAlloc[_,_])   => m.rank
    case Op(m: MemAlias[_,_,_]) => m.rawRank
    case _ => throw new Exception(s"Could not statically determine the rank of $mem")
  }

  /** Returns the statically defined staged dimensions (symbols) of the given memory. */
  def dimsOf(mem: Sym[_]): Seq[I32] = mem match {
    case Op(m: MemAlloc[_,_]) => m.dims
    case _ => throw new Exception(s"Could not statically determine the dimensions of $mem")
  }

  def sizeOf(mem: Sym[_]): I32 = mem match {
    case Op(m: MemAlloc[_,_]) if m.dims.size == 1 => m.dims.head
    case _ => throw new Exception(s"Could not get static size of $mem")
  }

  /** Returns constant values of the dimensions of the given memory. */
  def constDimsOf(mem: Sym[_]): Seq[Int] = {
    val dims = dimsOf(mem)
    if (dims.forall{case Expect(c) => true; case _ => false}) {
      dims.collect{case Expect(c) => c.toInt }
    }
    else {
      throw new Exception(s"Could not get constant dimensions of $mem")
    }
  }

  def readWidths(mem: Sym[_]): Set[Int] = mem.readers.map{
    case Op(read: BankedAccessor[_,_]) => read.width
    case _ => 1
  }

  def writeWidths(mem: Sym[_]): Set[Int] = mem.writers.map{
    case Op(write: BankedAccessor[_,_]) => write.width
    case _ => 1
  }

  def accessWidth(access: Sym[_]): Int = access match {
    case Op(RegFileShiftIn(_,data,_,_,_)) => 1
    case Op(RegFileBankedShiftIn(_,data,_,_,_)) => data.length
    case Op(RegFileShiftInVector(_,_,_,_,_,len)) => len
    case Op(FIFOBankedDeq(_, ens)) => ens.length
    case Op(FIFOBankedEnq(_, _, ens)) => ens.length
    case Op(LIFOBankedPop(_, ens)) => ens.length
    case Op(LIFOBankedPush(_, _, ens)) => ens.length
    case Op(ba: BankedAccessor[_,_]) => ba.width
    case Op(RegWrite(_,_,_)) => 1
    case Op(RegRead(_)) => 1
    case _ => -1
  }

  def shiftAxis(access: Sym[_]): Option[Int] = access match {
    case Op(op: RegFileShiftIn[_,_])       => Some(op.axis)
    case Op(op: RegFileBankedShiftIn[_,_]) => Some(op.axis)
    case Op(op: RegFileShiftInVector[_,_]) => Some(op.axis)
    case _ => None
  }

  /** Returns iterators between controller containing access (inclusive) and controller
    * containing mem (exclusive). Iterators are ordered outermost to innermost.
    */
  @stateful def accessIterators(access: Sym[_], mem: Sym[_]): Seq[Idx] = {
    // Use the parent "master" controller when checking for access iterators if the access and
    // memory are in different sub-controllers.
    // This is to account for memories defined, e.g. in the map (block 0) of a MemReduce
    // with the access defined in the second block.
    //
    // CASE 1: Direct hierarchy
    // Foreach(-1,-1)
    //   Foreach(0,0)
    //     *Alloc
    //     Reduce(-1,-1)
    //       Reduce(0,0)
    //         *Access
    // Want: Reduce(-1,-1) Reduce(0,0)
    // access.scopes(stop = mem.scope) = Reduce(-1,-1) Reduce(0,0)
    // access.scopes(stop = mem.scope.master) = Foreach(0,0) Reduce(-1,-1) Reduce(0,0)
    //
    // CASE 2: Access across subcontrollers
    // Foreach(-1,-1)
    //   Foreach(0,0)
    //     MemReduce(-1,-1)
    //       MemReduce(0,0)  -- MemReduce(0)
    //         *Alloc
    //       MemReduce(1,0)  -- MemReduce(1)
    //         *Access
    // Want: MemReduce(1,0) [STOP]
    // access.scopes(stop = mem.scope) = Accel Foreach(-1,-1) Foreach(0,0) MemReduce(-1) MemReduce(1)
    // access.scopes(stop = mem.scope.master) = MemReduce(1)
    val memoryIters = mem.scopes.filterNot(_.stage == -1).flatMap(scopeIters)
    val accessIters = access.scopes.filterNot(_.stage == -1).flatMap(scopeIters)

    accessIters diff memoryIters
  }

  /** Returns two sets of writers which may be visible to the given reader.
    * The first set contains all writers which always occur before the reader.
    * The second set contains writers which may occur after the reader (but be seen, e.g. in the
    * second iteration of a loop).
    *
    * A write MAY be seen by a reader if it may precede the reader and address spaces intersect.
    */
  @stateful def precedingWrites(read: AccessMatrix, writes: Set[AccessMatrix])(implicit isl: ISL): (Set[AccessMatrix], Set[AccessMatrix]) = {
    val preceding = writes.filter{write =>
      val intersects = read intersects write
      val mayPrecede = write.access.mayPrecede(read.access)

      intersects && mayPrecede
    }
    dbgs(s"  Preceding writes for ${read.access} {${read.unroll.mkString(",")}}: ")
    preceding.foreach{write => dbgs(s"    ${write.access} {${write.unroll.mkString(",")}}")}
    preceding.partition{write => !write.access.mayFollow(read.access) }
  }

  /** Returns true if the given write is entirely overwritten by a subsequent write PRIOR to the given read
    * (Occurs when another write w MUST follow this write and w contains ALL of the addresses in given write
    */
  @stateful def isKilled(write: AccessMatrix, others: Set[AccessMatrix], read: AccessMatrix)(implicit isl: ISL): Boolean = {
    others.exists{w => w.access.mustFollow(write.access, read.access) && w.isSuperset(write) }
  }

  @stateful def reachingWrites(reads: Set[AccessMatrix], writes: Set[AccessMatrix], isGlobal: Boolean)(implicit isl: ISL): Set[AccessMatrix] = {
    // TODO[5]: Hack, return all writers for global/unread memories for now
    if (isGlobal || reads.isEmpty) return writes

    var remainingWrites: Set[AccessMatrix] = writes
    var reachingWrites: Set[AccessMatrix] = Set.empty

    reads.foreach{read =>
      val (before, after) = precedingWrites(read, remainingWrites)

      val reachingBefore = before.filterNot{wr => isKilled(wr, before - wr, read) }
      val reachingAfter  = after.filterNot{wr => isKilled(wr, (after - wr) ++ before, read) }
      val reaching = reachingBefore ++ reachingAfter
      remainingWrites --= reaching
      reachingWrites ++= reaching
    }
    reachingWrites
  }

  @rig def flatIndex(indices: Seq[I32], dims: Seq[I32]): I32 = {
    val strides = List.tabulate(dims.length){d => dims.drop(d+1).prodTree }
    indices.zip(strides).map{case (a,b) => a.to[I32] * b }.sumTree
  }
}
