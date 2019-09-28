package spatial.traversal.banking

import argon._
import poly.{ISL, SparseMatrix, SparseVector}
import spatial.lang._
import spatial.metadata.access._
import spatial.metadata.control._
import spatial.metadata.memory._
import spatial.metadata.types._
import utils.implicits.collections._
import utils.math._

import scala.collection.mutable.ArrayBuffer


case class FullyBanked()(implicit IR: State, isl: ISL) extends BankingStrategy {
  // TODO[4]: What should the cutoff be for starting with powers of 2 versus exact accesses?
  private val MAGIC_CUTOFF_N = 1.4
  private val k = boundVar[I32]
  private val k0 = boundVar[I32]
  private val k1 = boundVar[I32]
  //private val Bs = Seq(2, 4, 8, 16, 32, 64, 128, 256) // Now set in metadata

  // Mapping to keep track of which AccessMatrix is rewritten as which
  private val accMatrixMapping = scala.collection.mutable.HashMap[AccessMatrix, AccessMatrix]()
  // Mapping te keep track of which rewritten AccessMatrix corresponds to which SparseMatrix for proper re-bundling after computing banking
  private val sparseMatrixMapping = scala.collection.mutable.HashMap[SparseMatrix[Idx], Set[AccessMatrix]]()
  // Mapping te keep track of which read SparseMatrix slices rewritten as low rank corresponds to which original full-rank read SparseMatrix
  private val lowRankMapping = scala.collection.mutable.HashMap[SparseMatrix[Idx], Set[SparseMatrix[Idx]]]()
  // Helper for replacing sparse matrix with its original access matrix
  private def reverseAM(a: SparseMatrix[Idx]): Set[AccessMatrix] = lowRankMapping(a).map(sparseMatrixMapping).flatten.map(accMatrixMapping)
  // Cache for skipping ahead to correct banking solution for patterns/axes that have already been solved
  private val solutionCache = scala.collection.mutable.HashMap[(Set[Set[SparseMatrix[Idx]]], NStrictness, AlphaStrictness, Seq[Int]), Option[ModBanking]]()
  // Map for tracking which kinds of schemes already have a solution, used depending on what the banking effort is set to.  Tracks BankingView and RegroupDims
  private val schemesFoundCount = scala.collection.mutable.HashMap[(BankingView,RegroupDims), Int]()

  /** Returns a Map from Seq(banking schemes) to the readers for these schemes.
    * Generally, it will contain Map(Seq(flat_scheme, nested_scheme) -> all readers) but in
    * the case of dephased accesses that cannot be banked together, there will be multiple
    * entries in the map who each point to a different partition of readers
    */
  override def bankAccesses(
    mem:    Sym[_],
    rank:   Int,
    reads:  Set[Set[AccessMatrix]],
    writes: Set[Set[AccessMatrix]],
    attemptDirectives: Seq[BankingOptions],
    depth: Int
  ): Map[BankingOptions, Map[Set[Set[AccessMatrix]], Seq[Banking]]] = {

    // Generate substitution rules for each iter for each uid.  Given iter and its uid, generate a rule to replace it with a new iter and an offset to include in the access patterns c column
    def generateSubstRules(accs: Set[Set[AccessMatrix]]): scala.collection.immutable.Map[(Idx,Seq[Int]),(Idx,Int)] = {
      val toRewrite: Map[(Idx, Seq[Int]), Option[Int]] = if (mem.forceExplicitBanking) Map() else accs.flatten.map{a => dephasingIters(a,Seq.fill(a.unroll.size)(0),mem)}.flatten.toMap
      toRewrite.map {
        case ((i, addr), ofs) if (ofs.isDefined) => ((i, addr) -> (i, ofs.get))
        case ((i, addr), ofs) if (!ofs.isDefined) => ((i, addr) -> (boundVar[I32], 0))
      }.toMap
    }
    def rewriteAccesses(accs: Set[Set[AccessMatrix]], rules: Map[(Idx,Seq[Int]),(Idx,Int)]): Set[Set[AccessMatrix]] = accs.map { grp =>
      grp.map { a =>
        val aIters = accessIterators(a.access, mem)
        val keyRules: scala.collection.immutable.Map[Idx, (Idx, Int)] = aIters.zipWithIndex.collect { case (iter, i) if (rules.contains((iter, getDephasedUID(aIters, a.unroll, i)))) => (iter -> rules((iter, getDephasedUID(aIters, a.unroll, i)))) }.toMap
        if (keyRules.nonEmpty) {
          mem.addDephasedAccess(a.access);
          dbgs(s"Substituting due to dephasing: $keyRules")
        }
        val newa = a.substituteKeys(keyRules)
        accMatrixMapping += (newa -> a)
        newa
      }
    }
    def repackageGroup(grp: Seq[SparseMatrix[Idx]], dims: List[Int], isRd: Boolean): ArrayBuffer[Seq[SparseMatrix[Idx]]] = {
      val fullStrategy = Seq.tabulate(rank){i => i}
      // For hierarchical views, regroup accesses based on whether their "complements" are non-interfering
      val grpViews = grp.map{mat =>
        val t = AccessView(dims, fullStrategy, mat)
        if (isRd) lowRankMapping += (t.activeAccess -> {lowRankMapping.getOrElse(t.activeAccess, Set()) ++ Set(mat)})
        t
      }
      val regrp = ArrayBuffer[ArrayBuffer[AccessView]]()
      grpViews.zipWithIndex.foreach{case (current,i) =>
        if (regrp.isEmpty) regrp += ArrayBuffer(current)
        else {
          // Find first group where current access may interfere with ANY of the complementary dimensions
          var grpId = 0
          var placed = false
          while (grpId < regrp.size & !placed) {
            val canConflict = regrp(grpId).exists{other =>
              val diff = current.complementAccess - other.complementAccess
              val conflictingMatrix = diff.rows.zipWithIndex.forall{case (row, dim) =>
                val patternForDim = (Seq(1)*SparseMatrix[Idx](Seq(row)) === 0)
                val conflictingRow = !patternForDim.andDomain.isEmpty
                // dbgs(s"Row $dim: \n  ISL problem:\n${patternForDim.andDomain}")
                if (!conflictingRow) {
                  // dbgs(s"Found nonconflicting complementary dimension: $dim")
                }
                conflictingRow
              }
              conflictingMatrix
            }
            if (canConflict) {
              // dbgs(s"Placing in group $grpId")
              regrp(grpId) = regrp(grpId) ++ ArrayBuffer(current)
              placed = true
            }
            else if (grpId < regrp.size - 1) {
              // dbgs(s"Cannot place in group $grpId because it has no conflicts in dim $dims")
              grpId += 1
            } else {
              // dbgs(s"Making new group")
              regrp += ArrayBuffer(current)
              placed = true
            }
          }
        }
      }
      // dbgs(s"regrouped $grpViews\n\n-->\n\n$regrp")

      regrp.map{newgrp =>
        var firstInstances: Set[SparseMatrix[Idx]] = Set.empty

        /** True if the sliced matrix has any of the following:
          *   - If the access is identical to another within this group
          *   - If it is the first time we are seeing this "sliced matrix" within this group
          */
        def isUniqueSliceInGroup(i: Int): Boolean = {
          // Current (sliced matrix, complement matrix) tuple
          val current = newgrp(i)

          // Others in group (Sequence of (sliced matrix, complement matrix) tuples)
          val pairsExceptCurrent = newgrp.patch(i,Nil,1)

          val totalCollision = pairsExceptCurrent.exists{other => other.access == current.access }
          val firstTime          = !firstInstances.contains(current.activeAccess)

          totalCollision || firstTime
        }

        Seq(newgrp.zipWithIndex.collect{case (current,i) if isUniqueSliceInGroup(i) =>
          firstInstances += current.activeAccess
          current.activeAccess
        }:_*)
      }
    }

    def findSchemes(myReads: Set[Seq[SparseMatrix[Idx]]], myWrites: Set[Seq[SparseMatrix[Idx]]], hostReads: Set[AccessMatrix]): Map[BankingOptions, Map[Set[Set[AccessMatrix]], Seq[Banking]]] = {
      val effort = mem.bankingEffort
      schemesFoundCount.clear()
      def markFound(scheme: BankingOptions): Unit = {
        val count = schemesFoundCount.getOrElse((scheme.view, scheme.regroup), 0)
        schemesFoundCount += (((scheme.view, scheme.regroup) -> {count + 1}))
        dbgs(s"incrementing ${scheme.view}, ${scheme.regroup} to ${count + 1} ")
      }
      def wantScheme(scheme: BankingOptions): Boolean = {
        if (effort == 0 && (schemesFoundCount.map(_._2).sum > 0)) false
        else if (effort == 1 && (
          schemesFoundCount.filter{x => x._1._1 == scheme.view && x._1._2 == scheme.regroup}.values.sum > 0 ||
          !(scheme.regroup.dims.isEmpty || scheme.regroup.dims.size == scheme.view.rank)
        )) false
        else if (effort == 2 && (
          schemesFoundCount.filter{x => x._1._1 == scheme.view && x._1._2 == scheme.regroup}.values.sum > 1 ||
          !(scheme.regroup.dims.isEmpty || scheme.regroup.dims.size == scheme.view.rank)
        )) false
        else if (effort == 3 && (
          schemesFoundCount.filter{x => x._1._1 == scheme.view && x._1._2 == scheme.regroup}.values.sum > 1
        )) false
        else true
      }

      val myGrps = myReads ++ myWrites
      myGrps.foreach{x => x.foreach{y => lowRankMapping += (y -> Set(y))}}
      if (mem.isSingleton) {
        if (myWrites.exists(_.size > 1) && !mem.shouldIgnoreConflicts) error(ctx, s"Cannot bank ${mem.ctx} (${mem.name.getOrElse("")})")
        Map(attemptDirectives.head -> Map((myReads.map{x => x.flatMap(reverseAM).toSet} ++ Set(hostReads)) -> Seq(ModBanking.Unit(rank, Seq.tabulate(mem.stagedDims.size){i => i}))))
      }
      else {
        attemptDirectives.flatMap{case scheme@BankingOptions(view, nStricts, aStricts, regroup) =>
          if (wantScheme(scheme)) {
            dbgs(s"Finding scheme for $scheme")
            /* Example of what "rawBanking" could look like if we duplicate for dim0 and actually bank for dim1 on something that may look like:
              *   Foreach(N by 1 par 2, M by 1){ (i,j) => s(i,j) = ...}
              *   Foreach(N by 1, M by 1 par 2){ (i,j) => ... = s(i,j)}
              *   rawBanking = Seq(
              *                   Map( Set(Seq(wr0)) -> ModBanking0, Set(Seq(wr1)) -> ModBanking0 ),
              *                   Map( Set(Seq(rd0,rd1)) -> ModBanking1 )
              *                )
              *
              *   To convert to "banking," we want to take one entry from each Map and call it a new duplicate
              */
            val autoFullBank: Seq[ModBanking] = if (view.complementView.nonEmpty) view.complementView.toSeq.flatMap{axis => Seq(ModBanking.Simple(mem.stagedDims(axis).toInt + (depth-1)*mem.stride, Seq(0), mem.stride))} else Seq()
            val rawBanking: Seq[Map[Set[Set[AccessMatrix]], Option[ModBanking]]] = view.expand().map{axes =>
              lowRankMapping.clear()
              myReads.foreach{x => x.foreach{y => lowRankMapping += (y -> Set(y))}}
              val selWrGrps: Set[Set[SparseMatrix[Idx]]] = if (axes.size < rank) myWrites.flatMap{grp => repackageGroup(grp, axes, false)}.map(_.toSet) else myWrites.map(_.toSet)
              val selRdGrps: Set[Set[SparseMatrix[Idx]]] = if (axes.size < rank) {myReads.flatMap{grp => repackageGroup(grp, axes, true)}.map(_.toSet)} else {myReads.map(_.toSet)}
              val selGrps: Set[Set[SparseMatrix[Idx]]] = selWrGrps ++ {if (axes.forall(regroup.dims.contains)) Set() else selRdGrps}
              selGrps.zipWithIndex.foreach{case (grp,i) =>
                dbgs(s"Banking group #$i has (${grp.size} accesses)")
                grp.foreach{matrix => dbgss("    ", matrix.toString) }
              }
              // If only 1 acc left per group, Unit banking, otherwise search
              val axisBankingScheme: Option[ModBanking] = {
                if (solutionCache.contains((selGrps, nStricts, aStricts, axes))) dbgs(s"Cache hit on ${selGrps.flatten.size} accesses, $nStricts, $aStricts, axes $axes!  Good job! (scheme ${solutionCache.get((selGrps, nStricts, aStricts, axes))})")
                solutionCache.getOrElseUpdate((selGrps, nStricts, aStricts, axes), {
                  findBanking(selGrps, nStricts, aStricts, axes, mem.stagedDims.map(_.toInt), mem)
                })}
              if (axes.forall(regroup.dims.contains)) {
                selRdGrps.flatMap { x => x.map { a => Set(reverseAM(a)) -> axisBankingScheme } }.toMap
              } else {
                Map(selRdGrps.map { x => x.flatMap(reverseAM(_)).toSet ++ hostReads } -> axisBankingScheme)
              }
            }
            if (rawBanking.forall{m => m.toSeq.map(_._2).forall{b => b.isDefined}}) {
              val bankingIds: List[List[Int]] = combs(rawBanking.toList.map{b => List.tabulate(b.size){i => i}})
              val banking: Map[Set[Set[AccessMatrix]], Seq[ModBanking]] = bankingIds
                .map{addr => addr.zipWithIndex.map{case (i,j) => rawBanking(j).toList(i)}}
                .map{dup =>
                  // When repackaging rawBanking, make sure to only keep read groups whose accesses can be found in read groups of ALL other dimensions
                  val accs: Seq[Set[Set[AccessMatrix]]] = dup.map(_._1.filter(_.nonEmpty)).toSeq
                  val inViewAccs: Set[Set[AccessMatrix]] = accs.zipWithIndex.map{ case (dimGrp:Set[Set[AccessMatrix]],i:Int) =>
                    val others: Seq[Set[Set[AccessMatrix]]] = accs.patch(i, Nil, 1)
                    dimGrp.map{ grp:Set[AccessMatrix] => if (others.isEmpty) grp else others.map{allDimGrps => allDimGrps.flatten}.reduce(_.intersect(_)).intersect(grp)}//.map{otherDimGrp => otherDimGrp.intersect(grp)}}} //if grp.forall{ac => others.forall{dg => dg.flatten.contains(ac)}} => grp}
                  }.reduce{_++_}.filter(_.nonEmpty)
                  (inViewAccs -> (autoFullBank ++ dup.map(_._2.get)))
                }.toMap
              val dimsInStrategy = view.expand().flatten.distinct
              if (banking.forall{case (accs, banks) =>
                          val prunedGrps = (accs.map(_.map(_.matrix)) ++ myWrites).map{grp => grp.map{mat => mat.sliceDims(dimsInStrategy)}.toSeq.distinct}
                          isValidBanking(banks, prunedGrps)
                        }) {
                // dbgs(s"Dim-based (raw) banking $rawBanking")
                // dbgs(s"Duplicate-based (assembled) banking $banking")
                dbgs(s"Banking scheme ${banking.map(_._2)} accepted!")
                markFound(scheme)
                Some((scheme -> banking))
              }
              else {
                dbgs(s"Computed banking for $scheme is invalid!")
                None
              }
            } else {
              dbgs(s"Could not find valid solution for $scheme!")
              None
            }
          } else {
            dbgs(s"Because $effort effort level, skipping search for scheme $scheme")
            None
          }
        }.toMap
      }
    }

    accMatrixMapping.clear()
    sparseMatrixMapping.clear()

    // Step 1: Modify access matrices due to lockstep dephasing and compute new "actual" grps
    val readIterSubsts = generateSubstRules(reads)
    if (readIterSubsts.nonEmpty) dbgs(s"General read dephasing rules for $mem: ${readIterSubsts.mkString("\n  - ")}")
    val writeIterSubsts = generateSubstRules(writes)
    if (writeIterSubsts.nonEmpty) dbgs(s"General write dephasing rules for $mem: ${writeIterSubsts.mkString("\n  - ")}")
    val hostReads = scala.collection.mutable.Set[AccessMatrix]()
    val newReads = rewriteAccesses(reads, readIterSubsts).map{accs =>
      val mats = accs.toSeq.flatMap{x =>
                                      sparseMatrixMapping += (x.matrix -> {sparseMatrixMapping.getOrElse(x.matrix, Set()) ++ Set(x)})
                                      if (x.parent != Ctrl.Host) Some(x.matrix)
                                      else {
                                        hostReads += x
                                        None
                                      }
                                    }
      if (mem.isSingleton) mats else mats.distinct
    }
    val newWrites = rewriteAccesses(writes, writeIterSubsts).map{accs =>
      val mats = accs.toSeq.flatMap{x =>
                                      // sparseMatrixMapping += (x.matrix -> {sparseMatrixMapping.getOrElse(x.matrix, Set()) ++ Set(x)})
                                      if (x.parent != Ctrl.Host) Some(x.matrix)
                                      else None
                                    }
      if (mem.isSingleton) mats else mats.distinct
    }

    // Step 2: Find schemes for these grps
    findSchemes(newReads, newWrites, hostReads.toSet)
  }

  /** True if this is a valid banking strategy for the given sets of access matrices. */
  def isValidBanking(banking: Seq[ModBanking], grps: Set[Seq[SparseMatrix[Idx]]]): Boolean = {
    // TODO[2]: This may not be correct in all cases, need to verify!
    val banks = banking.map(_.nBanks).product
    grps.forall{a => a.toList.lengthLessThan(banks+1)}
  }

  private def computeP(n: Int, b: Int, alpha: Seq[Int], stagedDims: Seq[Int], mem: Sym[_]): Seq[Int] = stagedDims

  protected def findBanking(grps: Set[Set[SparseMatrix[Idx]]], nStricts: NStrictness, aStricts: AlphaStrictness, axes: Seq[Int], stagedDims: Seq[Int], mem: Sym[_]): Option[ModBanking] = {
    val filteredStagedDims = axes.map(mem.stagedDims.map(_.toInt))
    val N = filteredStagedDims.head // Should only have this strategy if banking hierarchically
    val numChecks = 1
    val rank = axes.length
    if (checkCyclic(N, Seq(1), grps)) Option(ModBanking.Simple(N, axes, 1))
    else None
  }

  implicit class SeqMath(a: Seq[Int]) {
    def *(b: SparseMatrix[Idx]): SparseVector[Idx] = {
      val vec = b.keys.mapping{k => b.rows.zip(a).iterator.map{case (row_i,a_i) => row_i(k)*a_i }.sum }
      val c = b.rows.zip(a).iterator.map{case (row_i,a_i) => row_i.c*a_i}.sum
      SparseVector[Idx](vec,c,Map.empty)
    }
  }

  private def checkCyclic(N: Int, alpha: Seq[Int], grps: Set[Set[SparseMatrix[Idx]]]): Boolean = grps.forall{_.forallPairs{(a0,a1) =>
    val c0 = (alpha*(a0 - a1) + (k,N)) === 0
    c0.andDomain.isEmpty
  }}

}