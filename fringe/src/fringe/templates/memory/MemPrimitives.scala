package fringe.templates.memory

import chisel3._
import chisel3.util._
import fringe._
import fringe.Ledger._
import fringe.templates.counters.{CompactingCounter, CompactingIncDincCtr, IncDincCtr, SingleSCounterCheap}
import fringe.templates.math.Math
import fringe.utils._
import fringe.utils.HVec
import fringe.utils.{getRetimed}
import fringe.utils.implicits._
import emul.ResidualGenerator._

import _root_.utils.math._

abstract class MemPrimitive(val p: MemParams) extends Module {
  val io = p.iface match {
    case StandardInterfaceType => IO(new StandardInterface(p))
    case ShiftRegFileInterfaceType => IO(new ShiftRegFileInterface(p))
    case FIFOInterfaceType => IO(new FIFOInterface(p))
  } 

  p.iface match {
    case StandardInterfaceType => io.asInstanceOf[StandardInterface] <> DontCare
    case ShiftRegFileInterfaceType => io.asInstanceOf[ShiftRegFileInterface] <> DontCare
    case FIFOInterfaceType => io.asInstanceOf[FIFOInterface] <> DontCare    
  }

  def connectBufW(p: W_Port, lane: Int, mask: Bool): Unit = {
    io.wPort(lane).banks    :=  p.banks
    io.wPort(lane).ofs      :=  p.ofs
    io.wPort(lane).data     :=  p.data
    io.wPort(lane).reset    :=  p.reset
    io.wPort(lane).init     :=  p.init
    io.wPort(lane).shiftEn  :=  p.shiftEn.map(_ && mask)
    io.wPort(lane).en       :=  p.en.map(_ && mask)
  }

  def connectBufR(p: R_Port, lane: Int, mask: Bool): Unit = {
    io.rPort(lane).banks        := p.banks
    io.rPort(lane).ofs          := p.ofs
    io.rPort(lane).en           := p.en.map(_ & mask)
    io.rPort(lane).backpressure := p.backpressure
  }

  override def desiredName = p.myName
}


class BankedSRAM(p: MemParams) extends MemPrimitive(p) {
  def this(logicalDims: List[Int], bitWidth: Int, banks: List[Int], blocks: List[Int], neighborhood: List[Int],
           WMapping: List[Access], RMapping: List[Access],
           bankingMode: BankingMode, inits: Option[List[Double]], syncMem: Boolean, fracBits: Int, numActives: Int, myName: String = "sram") = this(MemParams(StandardInterfaceType, logicalDims,bitWidth,banks,blocks,neighborhood,WMapping,RMapping,bankingMode,inits,syncMem,fracBits, numActives = numActives, myName = myName))
  def this(tuple: (List[Int], Int, List[Int], List[Int], List[Int], List[Access], List[Access],
    BankingMode)) = this(MemParams(StandardInterfaceType,tuple._1,tuple._2,tuple._3,tuple._4,tuple._5,tuple._6,tuple._7,tuple._8))

  // Get info on physical dims
  // TODO: Upcast dims to evenly bank
  val numMems = p.Ns.product
  val bankDim = math.ceil(p.volume.toDouble / numMems.toDouble).toInt
  // Create list of (mem: Mem1D, coords: List[Int] <coordinates of bank>)
  val m = (0 until numMems).map{ i =>
    val mem = Module(new Mem1D(bankDim, p.bitWidth, p.syncMem))
    mem.io <> DontCare
    val coords = p.Ns.zipWithIndex.map{ case (b,j) =>
      i % p.Ns.drop(j).product / p.Ns.drop(j+1).product
    }
    (mem,coords)
  }

  // Handle Writes
  m.foreach{ mem =>
    // See which W ports can see this mem
    val connected: Seq[(W_Port, Seq[Int])] = p.WMapping.zip(io.wPort).collect{case (access, port) if canSee(access.coreBroadcastVisibleBanks, mem._2, p.Ns) => (port, lanesThatCanSee(access.coreBroadcastVisibleBanks, mem._2, p.Ns))}

    if (connected.nonEmpty) {
      val (ens, datas, ofs) = connected.map{case (port, lanes) => 
        val lane_enables:    Seq[Bool]          = lanes.map(port.en)
        val visible_in_lane: Seq[Seq[Seq[Int]]] = lanes.map(port.visibleBanks).map(_.zipWithIndex.map{case(r,j) => r.expand(p.Ns(j))})
        val banks_for_lane:  Seq[Seq[UInt]]     = lanes.map(port.banks.grouped(p.Ns.size).toSeq)
        val bank_matches:    Seq[Bool]          = banks_for_lane.zip(visible_in_lane).map{case (wireBanks, visBanks) => (wireBanks, mem._2, visBanks).zipped.map{case (a,b,c) => if (c.size == 1) true.B else {a === b.U}}.reduce{_&&_}}
        val ens:             Seq[Bool]          = lane_enables.zip(bank_matches).map{case (a,b) => a && b}
        val datas:           Seq[UInt]          = lanes.map(port.data)
        val ofs:             Seq[UInt]          = lanes.map(port.ofs)
        (ens,datas,ofs)
      }.reduce[(Seq[Bool], Seq[UInt], Seq[UInt])]{case 
        (
         a: (Seq[Bool], Seq[UInt], Seq[UInt]),
         b: (Seq[Bool], Seq[UInt], Seq[UInt])
        ) => (a._1 ++ b._1, a._2 ++ b._2, a._3 ++ b._3)}

      val finalChoice = fatMux("PriorityMux", ens, ens, datas, ofs)
      mem._1.io.w.ofs.head := finalChoice(2)
      mem._1.io.w.data.head := finalChoice(1)
      mem._1.io.w.en.head := finalChoice(0)
    }
  }


  // Handle Reads
  m.foreach{ mem =>
    val connected: Seq[(R_Port, Seq[Int])] = p.RMapping.zip(io.rPort).collect{case (access, port) if canSee(access.coreBroadcastVisibleBanks, mem._2, p.Ns) => (port, lanesThatCanSee(access.coreBroadcastVisibleBanks, mem._2, p.Ns))}

    if (connected.nonEmpty) {
      val (rawEns, ofs, backpressures) = connected.map{case (port, lanes) => 
        val lane_enables:    Seq[Bool]          = lanes.map(port.en)
        val visible_in_lane: Seq[Seq[Seq[Int]]] = lanes.map(port.visibleBanks).map(_.zipWithIndex.map{case(r,j) => r.expand(p.Ns(j))})
        val banks_for_lane:  Seq[Seq[UInt]]     = lanes.map(port.banks.grouped(p.Ns.size).toSeq)
        val bank_matches:    Seq[Bool]          = banks_for_lane.zip(visible_in_lane).map{case (wireBanks, visBanks) => (wireBanks, mem._2, visBanks).zipped.map{case (a,b,c) => if (c.size == 1) true.B else {a === b.U}}.reduce{_&&_}}
        val ens:             Seq[Bool]          = lane_enables.zip(bank_matches).map{case (a,b) => a && b}
        val ofs:             Seq[UInt]          = lanes.map(port.ofs)
        val backpressure:    Seq[Bool]          = Seq.fill(lanes.size){port.backpressure}
        (ens,ofs,backpressure)
      }.reduce[(Seq[Bool], Seq[UInt], Seq[Bool])]{case 
        (
         a: (Seq[Bool], Seq[UInt], Seq[Bool]),
         b: (Seq[Bool], Seq[UInt], Seq[Bool])
        ) => (a._1 ++ b._1, a._2 ++ b._2, a._3 ++ b._3)}

      val ens =
        if (globals.target.cheapSRAMs) rawEns // TODO: Figure out how to properly use sticky selects for dual ported...
        else {
          val stickyEns = Module(new StickySelects(rawEns.size, false)) // Fixes bug exposed by ScatterGatherSRAM app
          stickyEns.io.ins.zip(rawEns).foreach{case (a,b) => a := b}
          stickyEns.io.outs.map(_.toBool)
        }

      // Unmask write port if any of the above match
      val finalChoice = fatMux("PriorityMux", ens, ens, backpressures, ofs)
      mem._1.io.r.ofs.head := finalChoice(2)
      mem._1.io.r.backpressure := finalChoice(1)
      mem._1.io.r.en.head := finalChoice(0)
    }
  }

  // Connect read data to output
  p.RMapping.zipWithIndex.foreach{ case (rm, k) => 
    val port = io.rPort(k)
    port.output.zipWithIndex.foreach{case (out, lane) => 
      if (rm.broadcast(lane) > 0) { // Go spelunking for wire that makes true connection
        val castgrp = rm.castgroup(lane)
        out := (p.RMapping.flatMap(_.castgroup), p.RMapping.flatMap(_.broadcast), io.rPort.flatMap(_.output)).zipped.toList.zip(p.RMapping.flatMap{r => List.fill(r.castgroup.size)(r.muxPort)}).collect{case ((cg, b, o),mp) if b == 0 && cg == castgrp && mp == rm.muxPort =>  o}.head
      }
      else {
        val visBanksForLane = port.visibleBanks(lane).zipWithIndex.map{case(r,j) => r.expand(p.Ns(j))}
        val visibleMems = m.collect{case (m, ba) if ba.zip(visBanksForLane).forall{case (real, possible) => possible.contains(real)} => (m, ba)}
        val datas = visibleMems.map(_._1.io.output)
        val bankMatches = if (visibleMems.size == 1) Seq(true.B) else visibleMems.map(_._2).map{ba => port.banks.grouped(p.Ns.size).toSeq(lane).zip(ba).map{case (a,b) => a === b.U}.reduce{_&&_} }
        val en = port.en(lane)
        val sel = bankMatches.map{be => getRetimed(be & en, globals.target.sramload_latency, port.backpressure)}
        out := chisel3.util.PriorityMux(sel, datas)
      }
    }
  }

}


class BankedSRAMDualRead(p: MemParams) extends MemPrimitive(p) {
  def this(logicalDims: List[Int], bitWidth: Int, banks: List[Int], blocks: List[Int], neighborhood: List[Int],
           WMapping: List[Access], RMapping: List[Access],
           bankingMode: BankingMode, inits: Option[List[Double]], syncMem: Boolean, fracBits: Int, numActives: Int, myName: String = "sram") = this(MemParams(StandardInterfaceType, logicalDims,bitWidth,banks,blocks,neighborhood,WMapping,RMapping,bankingMode,inits,syncMem,fracBits, numActives = numActives, myName = myName))
  def this(tuple: (List[Int], Int, List[Int], List[Int], List[Int], List[Access], List[Access],
    BankingMode)) = this(MemParams(StandardInterfaceType,tuple._1,tuple._2,tuple._3,tuple._4,tuple._5,tuple._6,tuple._7,tuple._8))

  // Get info on physical dims
  // TODO: Upcast dims to evenly bank
  val numMems = p.Ns.product
  val flatBankAddrWidth = chisel3.util.log2Up(numMems)
  val bankDim = math.ceil(p.volume.toDouble / numMems.toDouble).toInt
  // Create list of (mem: Mem1D, coords: List[Int] <coordinates of bank>)
  val m = (0 until numMems).map{ i =>
    val mem = Module(new Mem1DDualRead(bankDim, p.bitWidth, p.syncMem))
    mem.io <> DontCare
    val coords = p.Ns.zipWithIndex.map{ case (b,j) =>
      i % p.Ns.drop(j).product / p.Ns.drop(j+1).product
    }
    (mem,coords)
  }
  val hierToFlat: Map[Seq[Int], Int] = m.zipWithIndex.map{case ((_,hier),i) => hier -> i}.toMap

  // Handle Writes
  m.foreach{ mem =>
    // See which W ports can see this mem
    val connected: Seq[(W_Port, Seq[Int])] = p.WMapping.zip(io.wPort).collect{case (access, port) if canSee(access.coreBroadcastVisibleBanks, mem._2, p.Ns) => (port, lanesThatCanSee(access.coreBroadcastVisibleBanks, mem._2, p.Ns))}

    if (connected.nonEmpty) {
      val (ens, datas, ofs) = connected.map{case (port, lanes) =>
        val lane_enables:    Seq[Bool]          = lanes.map(port.en)
        val visible_in_lane: Seq[Seq[Seq[Int]]] = lanes.map(port.visibleBanks).map(_.zipWithIndex.map{case(r,j) => r.expand(p.Ns(j))})
        val banks_for_lane:  Seq[Seq[UInt]]     = lanes.map(port.banks.grouped(p.Ns.size).toSeq)
        val bank_matches:    Seq[Bool]          = banks_for_lane.zip(visible_in_lane).map{case (wireBanks, visBanks) => (wireBanks, mem._2, visBanks).zipped.map{case (a,b,c) => if (c.size == 1) true.B else {a === b.U}}.reduce{_&&_}}
        val ens:             Seq[Bool]          = lane_enables.zip(bank_matches).map{case (a,b) => a && b}
        val datas:           Seq[UInt]          = lanes.map(port.data)
        val ofs:             Seq[UInt]          = lanes.map(port.ofs)
        (ens,datas,ofs)
      }.reduce[(Seq[Bool], Seq[UInt], Seq[UInt])]{case
        (
         a: (Seq[Bool], Seq[UInt], Seq[UInt]),
         b: (Seq[Bool], Seq[UInt], Seq[UInt])
        ) => (a._1 ++ b._1, a._2 ++ b._2, a._3 ++ b._3)}

      val finalChoice = fatMux("PriorityMux", ens, ens, datas, ofs)
      mem._1.io.w.ofs.head := finalChoice(2)
      mem._1.io.w.data.head := finalChoice(1)
      mem._1.io.w.en.head := finalChoice(0)
    }
  }

  // Handle Reads
  m.foreach{ mem =>
    val connected: Seq[(R_Port, Seq[Int])] = p.RMapping.zip(io.rPort).collect{case (access, port) if canSee(access.coreBroadcastVisibleBanks, mem._2, p.Ns) => (port, lanesThatCanSee(access.coreBroadcastVisibleBanks, mem._2, p.Ns))}

    if (connected.nonEmpty) {
      val (rawEns, ofs, backpressures) = connected.map{case (port, lanes) =>
        val lane_enables:    Seq[Bool]          = lanes.map(port.en)
        val visible_in_lane: Seq[Seq[Seq[Int]]] = lanes.map(port.visibleBanks).map(_.zipWithIndex.map{case(r,j) => r.expand(p.Ns(j))})
        val banks_for_lane:  Seq[Seq[UInt]]     = lanes.map(port.banks.grouped(p.Ns.size).toSeq)
        val bank_matches:    Seq[Bool]          = banks_for_lane.zip(visible_in_lane).map{case (wireBanks, visBanks) => (wireBanks, mem._2, visBanks).zipped.map{case (a,b,c) => if (c.size == 1) true.B else {a === b.U}}.reduce{_&&_}}
        val ens:             Seq[Bool]          = lane_enables.zip(bank_matches).map{case (a,b) => a && b}
        val ofs:             Seq[UInt]          = lanes.map(port.ofs)
        val backpressure:    Seq[Bool]          = Seq.fill(lanes.size){port.backpressure}
        (ens,ofs,backpressure)
      }.reduce[(Seq[Bool], Seq[UInt], Seq[Bool])]{case
        (
         a: (Seq[Bool], Seq[UInt], Seq[Bool]),
         b: (Seq[Bool], Seq[UInt], Seq[Bool])
        ) => (a._1 ++ b._1, a._2 ++ b._2, a._3 ++ b._3)}

      val ens =
        if (true /*globals.target.cheapSRAMs*/) rawEns // TODO: Figure out how to properly use sticky selects for dual ported...
        else {
          val stickyEns = Module(new StickySelects(rawEns.size, true)) // Fixes bug exposed by ScatterGatherSRAM app
          stickyEns.io.ins.zip(rawEns).foreach{case (a,b) => a := b}
          stickyEns.io.outs.map(_.toBool)
        }

      // Unmask write port if any of the above match
      if (ens.length == 1) {
        mem._1.io.r0.ofs.head := ofs.head
        mem._1.io.r0.backpressure := backpressures.head
        mem._1.io.r0.en.head := ens.head
      } else {
        val finalChoice0 = fatMux("PriorityMux", ens.reverse.tail.reverse, ens.reverse.tail.reverse, backpressures.reverse.tail.reverse, ofs.reverse.tail.reverse)
        mem._1.io.r0.ofs.head := finalChoice0(2)
        mem._1.io.r0.backpressure := finalChoice0(1)
        mem._1.io.r0.en.head := finalChoice0(0)
        val finalChoice1 = fatMux("PriorityMux", ens.tail.reverse, ens.tail.reverse, backpressures.tail.reverse, ofs.tail.reverse)
        mem._1.io.r1.ofs.head := finalChoice1(2)
        mem._1.io.r1.backpressure := finalChoice1(1)
        mem._1.io.r1.en.head := finalChoice1(0)
      }
    }
  }

  // Connect read data to output
  // laneBitvecs is a Sequence with one entry per read port lane: <bank address, bank.port0 output, bank.port1 output, read enable, banks (flattend addr) visible for lane, isPsuedo (i.e. broadcast sniffer)>
  val laneBitvecs: Seq[(UInt, UInt, UInt, Bool, List[scala.Int], scala.Boolean)] = p.RMapping.zipWithIndex.flatMap{ case (rm, k) =>
    val port = io.rPort(k)
    // First identify the bank each lane is asking for
    port.output.zipWithIndex.map{case (out, lane) =>
      val visBanksForLane = port.visibleBanks(lane).zipWithIndex.map{case(r,j) => r.expand(p.Ns(j))}
      val visibleMems = m.collect{case (m, ba) if ba.zip(visBanksForLane).forall{case (real, possible) => possible.contains(real)} => (m, ba)}
      val bankMatches = if (visibleMems.size == 1) Seq(true.B) else visibleMems.map(_._2).map{ba => port.banks.grouped(p.Ns.size).toSeq(lane).zip(ba).map{case (a,b) => a === b.U}.reduce{_&&_} }
      val en = port.en(lane)
      val sel = bankMatches.map{be => getRetimed(be & en, globals.target.sramload_latency, port.backpressure)}
      (Mux1H(sel, combs(visBanksForLane.map(_.toList)).map(hierToFlat).sorted.map(_.U(flatBankAddrWidth.W))),
       chisel3.util.PriorityMux(sel, visibleMems.map(_._1.io.output0)),
       chisel3.util.PriorityMux(sel, visibleMems.map(_._1.io.output1)),
       {
         if (true /*globals.target.cheapSRAMs*/) getRetimed(en, globals.target.sramload_latency, port.backpressure)
         else List.tabulate(globals.target.sramload_latency + 1){i => getRetimed(en, i, port.backpressure)}.reduce{_||_} // hacky way to capture sticky selects memory without a real sticky select module
       },
       combs(visBanksForLane.map(_.toList)).map(hierToFlat).sorted,
       rm.broadcast(lane) > 0
      )
    }
  }
  p.RMapping.zipWithIndex.foreach{ case (rm, k) =>
    val port = io.rPort(k)
    val base = io.rPort.take(k).map(_.output.size).sum
    // Then, decide if we want the data coming from port0 or port1
    port.output.zipWithIndex.foreach{case (out, lane) =>
      if (rm.broadcast(lane) > 0) { // Go spelunking for wire that makes true connection
        val castgrp = rm.castgroup(lane)
        out := (p.RMapping.flatMap(_.castgroup), p.RMapping.flatMap(_.broadcast), io.rPort.flatMap(_.output)).zipped.toList.zip(p.RMapping.flatMap{r => List.fill(r.castgroup.size)(r.muxPort)}).collect{case ((cg, b, o),mp) if b == 0 && cg == castgrp && mp == rm.muxPort =>  o}.head
      }
      else {
        val conflictsBelowIdx = laneBitvecs.take(base + lane).zipWithIndex.collect{case (vb,i) if (vb._5 intersect laneBitvecs(base+lane)._5).nonEmpty && !vb._6 => i}
        val conflictsAboveIdx = laneBitvecs.takeRight(laneBitvecs.size - base - lane - 1).zipWithIndex.collect{case (vb,i) if (vb._5 intersect laneBitvecs(base+lane)._5).nonEmpty && !vb._6 => i + laneBitvecs.size + 1}
        val takeUpper = if (conflictsBelowIdx.isEmpty) false.B
                        else if (conflictsAboveIdx.isEmpty) true.B
                        else conflictsBelowIdx.map(laneBitvecs).map{fba => fba._4 && (fba._1 === laneBitvecs(base + lane)._1)}.reduce{_||_}
        out := Mux(takeUpper, laneBitvecs(base + lane)._3, laneBitvecs(base + lane)._2)
      }
    }
  }


}

class FF(p: MemParams) extends MemPrimitive(p) {
  def this(logicalDims: List[Int], bitWidth: Int, banks: List[Int], blocks: List[Int], neighborhood: List[Int],
           WMapping: List[Access], RMapping: List[Access],
           bankingMode: BankingMode, inits: Option[List[Double]], syncMem: Boolean, fracBits: Int, numActives: Int, myName: String = "FF") = this(MemParams(StandardInterfaceType, logicalDims,bitWidth,banks,blocks,neighborhood,WMapping,RMapping,bankingMode,inits,syncMem,fracBits, numActives = numActives, myName = myName))
  def this(tuple: (List[Int], Int, List[Int], List[Int], List[Int], List[Access], List[Access],
    BankingMode)) = this(MemParams(StandardInterfaceType,tuple._1,tuple._2,tuple._3,tuple._4,tuple._5,tuple._6,tuple._7,tuple._8))

  def this(tuple: (Int, List[Access])) = this(List(1), tuple._1,List(1), List(1), List(1), tuple._2, List(AccessHelper.singular(32)), BankedMemory, None, false, 0, 1)
  def this(bitWidth: Int) = this(List(1), bitWidth,List(1), List(1), List(1), List(AccessHelper.singular(bitWidth)), List(AccessHelper.singular(bitWidth)), BankedMemory, None, false, 0, 1)
  def this(bitWidth: Int, WMapping: List[Access], RMapping: List[Access], inits: Option[List[Double]], fracBits: Int, numActives: Int, myName: String) = this(List(1), bitWidth,List(1), List(1), List(1), WMapping, RMapping, BankedMemory, inits, false, fracBits, numActives = numActives, myName)

  val init = 
    if (p.inits.isDefined) {
      if (p.bitWidth == 1) {if (p.inits.get.head == 0.0) false.B else true.B}
      else                 (p.inits.get.head*scala.math.pow(2,p.fracBits)).toLong.S(p.bitWidth.W).asUInt
    }
    else io.wPort(0).init

  val ff = RegInit(init)
  val anyReset: Bool = io.wPort.map{_.reset}.toList.reduce{_|_} | io.reset
  val anyEnable: Bool = io.wPort.flatMap{_.en}.toList.reduce{_|_}
  val wr_data: UInt = chisel3.util.PriorityMux(io.wPort.flatMap{_.en}.toList, io.wPort.flatMap{_.data}.toList)
  ff := Mux(anyReset, init, Mux(anyEnable, wr_data, ff))
  io.rPort.foreach(_.output.head := ff)
}

class FIFOReg(p: MemParams) extends MemPrimitive(p) {
  // Compatibility with standard mem codegen
  def this(logicalDims: List[Int], bitWidth: Int,
           banks: List[Int], blocks: List[Int], neighborhood: List[Int],
           WMapping: List[Access], RMapping: List[Access],
           bankingMode: BankingMode, init: Option[List[Double]], syncMem: Boolean, fracBits: Int, numActives: Int, myName: String = "FIFOReg") = this(MemParams(FIFOInterfaceType, logicalDims, bitWidth, banks, blocks, neighborhood, WMapping, RMapping, bankingMode, init, syncMem, fracBits, numActives = numActives, myName = myName))
  // def this(tuple: (Int, XMap)) = this(List(1), tuple._1,List(1), List(1), tuple._2, XMap((0,0,0) -> (1, None)), DMap(), DMap(), BankedMemory, None, false, 0, 2)
  // def this(bitWidth: Int) = this(List(1), bitWidth,List(1), List(1), XMap((0,0,0) -> (1, None)), XMap((0,0,0) -> (1, None)), DMap(), DMap(), BankedMemory, None, false, 0, 2)
  // def this(bitWidth: Int, xBarWMux: XMap, xBarRMux: XMap, inits: Option[List[Double]], fracBits: Int, numActives: Int) = this(List(1), bitWidth,List(1), List(1), xBarWMux, xBarRMux, DMap(), DMap(), BankedMemory, inits, false, fracBits, numActives)

  val init = 
    if (p.inits.isDefined) {
      if (p.bitWidth == 1) {if (p.inits.get.head == 0.0) false.B else true.B}
      else                 (p.inits.get.head*scala.math.pow(2,p.fracBits)).toLong.S(p.bitWidth.W).asUInt
    }
    else io.wPort(0).init

  val ff = RegInit(init)

  val anyWrite: Bool = io.wPort.flatMap{_.en}.toList.reduce{_|_}
  val anyRead: Bool = io.rPort.flatMap{_.en}.toList.reduce{_|_}
  val wr_data: UInt = chisel3.util.PriorityMux(io.wPort.flatMap{_.en}.toList, io.wPort.flatMap{_.data}.toList)
  ff := Mux(io.reset, init, Mux(anyWrite, wr_data, ff))
  io.rPort.flatMap(_.output).foreach(_ := ff)

  val isValid = Module(new SRFF())
  isValid.io.input.set := anyWrite
  isValid.io.input.reset := anyRead
  isValid.io.input.asyn_reset := false.B

  // Check if there is data
  io.asInstanceOf[FIFOInterface].active.map(_.out).zip(io.asInstanceOf[FIFOInterface].active.map(_.in)).foreach{case (o,i) => o := i}
  io.asInstanceOf[FIFOInterface].empty := ~isValid.io.output
  io.asInstanceOf[FIFOInterface].full := isValid.io.output
  io.asInstanceOf[FIFOInterface].almostEmpty := false.B
  io.asInstanceOf[FIFOInterface].almostFull := false.B
  io.asInstanceOf[FIFOInterface].numel := Mux(isValid.io.output, 1.U, 0.U)

}

class FIFO(p: MemParams) extends MemPrimitive(p) {
  def this(logicalDims: List[Int], bitWidth: Int,
           banks: List[Int], WMapping: List[Access], RMapping: List[Access],
           inits: Option[List[Double]], syncMem: Boolean, fracBits: Int, numActives: Int) = this(MemParams(FIFOInterfaceType,logicalDims, bitWidth, banks, List(1), List(1), WMapping, RMapping, BankedMemory, inits, syncMem, fracBits, numActives = numActives, myName = "FIFO"))

  def this(tuple: (List[Int], Int, List[Int], List[Access], List[Access], Int)) = this(tuple._1, tuple._2, tuple._3, tuple._4, tuple._5,  None, false, 0, tuple._6)
  def this(logicalDims: List[Int], bitWidth: Int,
           banks: List[Int], blocks: List[Int], neighborhood: List[Int],
           WMapping: List[Access], RMapping: List[Access],
           bankingMode: BankingMode, init: Option[List[Double]], syncMem: Boolean, fracBits: Int, numActives: Int, myName: String = "FIFO") = this(MemParams(FIFOInterfaceType,logicalDims, bitWidth, banks, blocks, neighborhood, WMapping, RMapping, bankingMode, init, syncMem, fracBits, numActives = numActives, myName = myName))

  // Create bank counters
  val headCtr = Module(new CompactingCounter(p.WMapping.map(_.par).sum, p.volume, p.elsWidth)); headCtr.io <> DontCare
  val tailCtr = Module(new CompactingCounter(p.RMapping.map(_.par).sum, p.volume, p.elsWidth)); tailCtr.io <> DontCare
  (0 until p.WMapping.map(_.par).sum).foreach{i => headCtr.io.input.enables.zip(io.wPort.flatMap(_.en)).foreach{case (l,r) => l := r}}
  (0 until p.RMapping.map(_.par).sum).foreach{i => tailCtr.io.input.enables.zip(io.rPort.flatMap(_.en)).foreach{case (l,r) => l := r}}
  headCtr.io.input.reset := reset
  tailCtr.io.input.reset := reset
  headCtr.io.input.dir := true.B
  tailCtr.io.input.dir := true.B
//  chisel3.core.dontTouch(io)

  // Create numel counter
  val elements = Module(new CompactingIncDincCtr(p.WMapping.map(_.par).sum, p.RMapping.map(_.par).sum, p.widestW, p.widestR, p.volume, p.elsWidth))
  elements.io <> DontCare
  elements.io.input.inc_en.zip(io.wPort.flatMap(_.en)).foreach{case(l,r) => l := r}
  elements.io.input.dinc_en.zip(io.rPort.flatMap(_.en)).foreach{case(l,r) => l := r}

  // Create physical mems
  val m = (0 until p.numBanks).map{ i => val x = Module(new Mem1D(p.volume/p.numBanks, p.bitWidth)); x.io <> DontCare; x}

  // Create compacting network

  val enqCompactor = Module(new CompactingEnqNetwork(p.WMapping.map(_.par).toList, p.numBanks, p.elsWidth, p.bitWidth))
  enqCompactor.io <> DontCare
  enqCompactor.io.headCnt := headCtr.io.output.count
  (0 until p.WMapping.size).foreach{i =>
    enqCompactor.io.in.map(_.data).zip(io.wPort.flatMap(_.data)).foreach{case (l,r) => l := r}
    enqCompactor.io.in.map(_.en).zip(io.wPort.flatMap(_.en)).foreach{case(l,r) => l := r}
  }

  // Connect compacting network to banks
  val active_w_bank = Math.singleCycleModulo(headCtr.io.output.count, p.numBanks.S(p.elsWidth.W))
  val active_w_addr = Math.singleCycleDivide(headCtr.io.output.count, p.numBanks.S(p.elsWidth.W))
  (0 until p.numBanks).foreach{i =>
    val addr = Mux(i.S(p.elsWidth.W) < active_w_bank, active_w_addr + 1.S(p.elsWidth.W), active_w_addr)
    m(i).io.w.ofs.head := addr.asUInt
    m(i).io.w.data.head := enqCompactor.io.out(i).data
    m(i).io.w.en.head   := enqCompactor.io.out(i).en
  }

  // Create dequeue compacting network
  val deqCompactor = Module(new CompactingDeqNetwork(p.RMapping.map(_.par), p.numBanks, p.elsWidth, p.bitWidth))
  deqCompactor.io <> DontCare
  deqCompactor.io.tailCnt := tailCtr.io.output.count
  val active_r_bank = Math.singleCycleModulo(tailCtr.io.output.count, p.numBanks.S(p.elsWidth.W))
  val active_r_addr = Math.singleCycleDivide(tailCtr.io.output.count, p.numBanks.S(p.elsWidth.W))
  (0 until p.numBanks).foreach{i =>
    val addr = Mux(i.S(p.elsWidth.W) < active_r_bank, active_r_addr + 1.S(p.elsWidth.W), active_r_addr)
    m(i).io.r.ofs.head := addr.asUInt
    deqCompactor.io.input.data(i) := m(i).io.output
  }
  deqCompactor.io.input.deq.zip(io.rPort.flatMap(_.en)).foreach{case (l,r) => l := r}
  io.rPort.flatMap(_.output).zip(deqCompactor.io.output).foreach{case (o,d) => o := d}
//    p.output.zip(deqCompactor.io.output).foreach{case (a,b) => a := b}
//  }

  // Check if there is data
  io.asInstanceOf[FIFOInterface].active.map(_.out).zip(io.asInstanceOf[FIFOInterface].active.map(_.in)).foreach{case (o,i) => o := i}
  io.asInstanceOf[FIFOInterface].empty := elements.io.output.empty
  io.asInstanceOf[FIFOInterface].full := elements.io.output.full
  io.asInstanceOf[FIFOInterface].almostEmpty := elements.io.output.almostEmpty
  io.asInstanceOf[FIFOInterface].almostFull := elements.io.output.almostFull
  io.asInstanceOf[FIFOInterface].numel := elements.io.output.numel.asUInt


}

class LIFO(p: MemParams) extends MemPrimitive(p) {

  def this(logicalDims: List[Int], bitWidth: Int,
           banks: List[Int], WMapping: List[Access], RMapping: List[Access],
           inits: Option[List[Double]], syncMem: Boolean, fracBits: Int, numActives: Int) = this(MemParams(FIFOInterfaceType,logicalDims, bitWidth, banks, List(1), List(1), WMapping, RMapping, BankedMemory, inits, syncMem, fracBits, numActives = numActives, myName = "FIFO"))

  def this(tuple: (List[Int], Int, List[Int], List[Access], List[Access], Int)) = this(tuple._1, tuple._2, tuple._3, tuple._4, tuple._5,  None, false, 0, tuple._6)
  def this(logicalDims: List[Int], bitWidth: Int,
           banks: List[Int], blocks: List[Int], neighborhood: List[Int],
           WMapping: List[Access], RMapping: List[Access],
           bankingMode: BankingMode, init: Option[List[Double]], syncMem: Boolean, fracBits: Int, numActives: Int, myName: String = "FIFO") = this(MemParams(FIFOInterfaceType,logicalDims, bitWidth, banks, List(1), List(1), WMapping, RMapping, bankingMode, init, syncMem, fracBits, numActives = numActives, myName = myName))

  val pW = p.WMapping.map(_.par).max
  val pR = p.RMapping.map(_.par).max
  val par = scala.math.max(pW, pR) // TODO: Update this template because this was from old style

  // Register for tracking number of elements in FILO
  val elements = Module(new IncDincCtr(pW,pR, p.volume))
  elements.io.input.inc_en := io.wPort.flatMap(_.en).toList.reduce{_|_}
  elements.io.input.dinc_en := io.rPort.flatMap(_.en).toList.reduce{_|_}

  // Create physical mems
  val m = (0 until par).map{ i => val x = Module(new Mem1D(p.volume/par, p.bitWidth)); x.io <> DontCare; x}

  // Create head and reader sub counters
  val sa_width = 2 + _root_.utils.math.log2Up(par)
  val subAccessor = Module(new SingleSCounterCheap(1,0,par,pW,-pR,sa_width))
  subAccessor.io <> DontCare
  subAccessor.io.input.enable := io.wPort.flatMap(_.en).toList.reduce{_|_} | io.rPort.flatMap(_.en).toList.reduce{_|_}
  subAccessor.io.input.dir := io.wPort.flatMap(_.en).toList.reduce{_|_}
  subAccessor.io.input.reset := reset
  subAccessor.io.setup.saturate := false.B
  val subAccessor_prev = Mux(subAccessor.io.output.count(0) - pR.S(sa_width.W) < 0.S(sa_width.W), (par-pR).S(sa_width.W), subAccessor.io.output.count(0) - pR.S(sa_width.W))

  // Create head and reader counters
  val a_width = 2 + _root_.utils.math.log2Up(p.volume/par)
  val accessor = Module(new SingleSCounterCheap(1, 0, p.volume/par, 1, -1, a_width))
  accessor.io <> DontCare
  accessor.io.input.enable := (io.wPort.flatMap(_.en).toList.reduce{_|_} & subAccessor.io.output.done) | (io.rPort.flatMap(_.en).toList.reduce{_|_} & subAccessor_prev === 0.S(sa_width.W))
  accessor.io.input.dir := io.wPort.flatMap(_.en).toList.reduce{_|_}
  accessor.io.input.reset := reset
  accessor.io.setup.saturate := false.B

  // Connect pusher
  if (pW == pR) {
    m.zipWithIndex.foreach { case (mem, i) =>
      val ens = io.wPort.collect{case n if n.en.size > i => n.en(i)}
      val datas = io.wPort.collect{case n if n.data.size > i => n.data(i)}

      // Make connections to memory
      mem.io.w.ofs.head := accessor.io.output.count(0).asUInt
      mem.io.w.data.head := PriorityMux(ens, datas)
      mem.io.w.en.head := ens.or
    }
  } else {
    (0 until pW).foreach { w_i =>
      val ens = io.wPort.collect{case n if n.en.size > w_i => n.en(w_i)}
      val datas = io.wPort.collect{case n if n.data.size > w_i => n.data(w_i)}
      (0 until (par / pW)).foreach { i =>
        // Make connections to memory
        m(w_i + i*pW).io.w.ofs.head := accessor.io.output.count(0).asUInt
        m(w_i + i*pW).io.w.data.head := PriorityMux(ens, datas)
        m(w_i + i*pW).io.w.en.head := ens.or & (subAccessor.io.output.count(0) === (i*pW).S(sa_width.W))
      }
    }
  }

  // Connect popper
  if (pW == pR) {
    m.zipWithIndex.foreach { case (mem, i) =>
      mem.io.r.ofs.head := (accessor.io.output.count(0) - 1.S(a_width.W)).asUInt
      mem.io.r.en.head := io.rPort.flatMap(_.en).toList.reduce{_|_}
      io.rPort(0).output(i) := mem.io.output
    }
  } else {
    (0 until pR).foreach { r_i =>
      val rSel = Wire(Vec( par/pR, Bool()))
      val rData = Wire(Vec( par/pR, UInt(p.bitWidth.W)))
      (0 until (par / pR)).foreach { i =>
        m(r_i + i*pR).io.r.ofs.head := (accessor.io.output.count(0) - 1.S(sa_width.W)).asUInt
        m(r_i + i*pR).io.r.en.head := io.rPort.flatMap(_.en).toList.reduce{_|_} & (subAccessor_prev === (i*pR).S(sa_width.W))
        rSel(i) := subAccessor_prev === i.S
        rData(i) := m(r_i + i*pR).io.output
      }
      io.rPort(0).output(pR - 1 - r_i) := chisel3.util.PriorityMux(rSel, rData)
    }
  }

  // Check if there is data
  io.asInstanceOf[FIFOInterface].active.map(_.out).zip(io.asInstanceOf[FIFOInterface].active.map(_.in)).foreach{case (o,i) => o := i}
  io.asInstanceOf[FIFOInterface].empty := elements.io.output.empty
  io.asInstanceOf[FIFOInterface].full := elements.io.output.full
  io.asInstanceOf[FIFOInterface].almostEmpty := elements.io.output.almostEmpty
  io.asInstanceOf[FIFOInterface].almostFull := elements.io.output.almostFull
  io.asInstanceOf[FIFOInterface].numel := elements.io.output.numel.asUInt

}

class ShiftRegFile(p: MemParams) extends MemPrimitive(p) {
  def this(logicalDims: List[Int], bitWidth: Int, banks: List[Int], blocks: List[Int], neighborhood: List[Int],
           WMapping: List[Access], RMapping: List[Access],
           inits: Option[List[Double]], syncMem: Boolean, fracBits: Int, numActives: Int, myName: String) = this(MemParams(ShiftRegFileInterfaceType, logicalDims,bitWidth,banks,blocks,neighborhood,WMapping,RMapping,BankedMemory,inits,syncMem,fracBits, false, numActives, myName))
  def this(logicalDims: List[Int], bitWidth: Int, banks: List[Int], blocks: List[Int], neighborhood: List[Int],
           WMapping: List[Access], RMapping: List[Access],
           bankingMode: BankingMode, inits: Option[List[Double]], syncMem: Boolean, fracBits: Int, numActives: Int, myName: String) = this(MemParams(ShiftRegFileInterfaceType, logicalDims,bitWidth,banks,blocks,neighborhood,WMapping,RMapping,bankingMode,inits,syncMem,fracBits, false, numActives, myName))
  def this(logicalDims: List[Int], bitWidth: Int, banks: List[Int], blocks: List[Int], neighborhood: List[Int],
           WMapping: List[Access], RMapping: List[Access],
           inits: Option[List[Double]], syncMem: Boolean, fracBits: Int, isBuf: Boolean, numActives: Int, myName: String) = this(MemParams(ShiftRegFileInterfaceType, logicalDims,bitWidth,banks,blocks,neighborhood,WMapping,RMapping,BankedMemory,inits,syncMem,fracBits, isBuf, numActives, myName))

  // Create list of (mem: Mem1D, coords: List[Int] <coordinates of bank>)
  val m = (0 until p.volume).map{ i =>
    val coords = p.Ds.zipWithIndex.map{ case (b,j) =>
      i % p.Ds.drop(j).product / p.Ds.drop(j+1).product
    }
    val initval = if (p.inits.isDefined) (p.inits.get.apply(i)*scala.math.pow(2,p.fracBits)).toLong.U(p.bitWidth.W) else 0.U(p.bitWidth.W)
    val mem = RegInit(initval)
    io.asInstanceOf[ShiftRegFileInterface].dump_out(i) := mem
    (mem,coords,initval, i)
  }

  def stripCoord(l: List[Int], x: Int): List[Int] = {l.take(x) ++ l.drop(x+1)}
  def stripCoord(l: HVec[UInt], x: Int): HVec[UInt] = {HVec(l.take(x) ++ l.drop(x+1))}
  def decrementAxisCoord(l: List[Int], x: Int): List[Int] = {l.take(x) ++ List(l(x) - 1) ++ l.drop(x+1)}

  // Handle Writes
  m.foreach { mem =>
    val initval = mem._3
    val flatCoord = mem._4
    // See which W ports can see this mem
    val connectedNormals: Seq[(W_Port, Seq[Int])] = p.WMapping.zipWithIndex.collect {
      case (x, i) if x.shiftAxis.isEmpty && canSee(p.WMapping(i).coreBroadcastVisibleBanks, mem._2, p.Ns) => (io.wPort(i), lanesThatCanSee(p.WMapping(i).coreBroadcastVisibleBanks, mem._2, p.Ns))
    }
    val connectedShifters: Seq[(W_Port, Seq[Int], Int)] = p.WMapping.zipWithIndex.collect {
      case (x, i) if x.shiftAxis.isDefined && canSee(p.WMapping(i).coreBroadcastVisibleBanks.map { case (rg, i) => (rg.patch(x.shiftAxis.get, Nil, 1), i) }, mem._2.patch(x.shiftAxis.get, Nil, 1), p.Ns.patch(x.shiftAxis.get, Nil, 1)) =>
        (io.wPort(i), lanesThatCanSee(p.WMapping(i).coreBroadcastVisibleBanks.map { case (rg, i) => (rg.patch(x.shiftAxis.get, Nil, 1), i) }, mem._2.patch(x.shiftAxis.get, Nil, 1), p.Ns.patch(x.shiftAxis.get, Nil, 1)), x.shiftAxis.get)
    }

    val (normalEns, normalDatas) = if (connectedNormals.nonEmpty) {
      connectedNormals.map { case (port, lanes) =>
        val lane_enables: Seq[Bool] = lanes.map(port.en)
        val visible_in_lane: Seq[Seq[Seq[Int]]] = lanes.map(port.visibleBanks).map(_.zipWithIndex.map { case (r, j) => r.expand(p.Ds(j)) })
        val banks_for_lane: Seq[Seq[UInt]] = lanes.map(port.banks.grouped(p.Ns.size).toSeq)
        val bank_matches: Seq[Bool] = banks_for_lane.zip(visible_in_lane).map { case (wireBanks, visBanks) => (wireBanks, mem._2, visBanks).zipped.map { case (a, b, c) => if (c.size == 1) true.B else {
          a === b.U
        }
        }.reduce {
          _ && _
        }
        }
        val ens: Seq[Bool] = lane_enables.zip(bank_matches).map { case (a, b) => a && b }
        val datas: Seq[UInt] = lanes.map(port.data)
        (ens, datas)
      }.reduce[(Seq[Bool], Seq[UInt])] { case
        (
          a: (Seq[Bool], Seq[UInt]),
          b: (Seq[Bool], Seq[UInt])
          ) => (a._1 ++ b._1, a._2 ++ b._2)
      }
    } else (Seq(), Seq())
    val (shiftEns, shiftDatas) = if (connectedShifters.nonEmpty) {
      connectedShifters.map { case (port, lanes, axis) =>
        val lane_enables: Seq[Bool] = lanes.map(port.shiftEn)
        val visible_in_lane: Seq[Seq[Seq[Int]]] = lanes.map(port.visibleBanks).map(_.zipWithIndex.patch(axis, Nil, 1).map { case (r, j) => r.expand(p.Ds(j)) })
        val banks_for_lane: Seq[Seq[UInt]] = lanes.map(port.banks.grouped(p.Ns.size).map(_.patch(axis, Nil, 1)).toSeq)
        val bank_matches: Seq[Bool] = banks_for_lane.zip(visible_in_lane).map { case (wireBanks, visBanks) => val matches = (wireBanks, mem._2, visBanks).zipped.map { case (a, b, c) => if (c.size == 1) true.B else {
          a === b.U
        }
        };
          if (matches.isEmpty) true.B else matches.reduce {
            _ && _
          }
        }
        val ens: Seq[Bool] = lane_enables.zip(bank_matches).map { case (a, b) => a && b }
        val datas: Seq[UInt] = if (mem._2(axis) == 0) lanes.map(port.data) else m.collect { case (m, coords, _, _) if coords(axis) == mem._2(axis) - 1 && coords.patch(axis, Nil, 1) == mem._2.patch(axis, Nil, 1) => m } // Pray there is only one lane connected to this line
        (ens, datas)
      }.reduce[(Seq[Bool], Seq[UInt])] { case
        (
          a: (Seq[Bool], Seq[UInt]),
          b: (Seq[Bool], Seq[UInt])
          ) => (a._1 ++ b._1, a._2 ++ b._2)
      }
    } else (Seq(), Seq())

    if (shiftEns.nonEmpty || normalEns.nonEmpty) {
      val finalChoice = fatMux("PriorityMux", normalEns ++ shiftEns, normalDatas ++ shiftDatas)
      if (p.isBuf) {
        mem._1 := Mux(io.asInstanceOf[ShiftRegFileInterface].dump_en,
          io.asInstanceOf[ShiftRegFileInterface].dump_in(flatCoord),
          Mux(io.reset,
            initval,
            Mux((normalEns ++ shiftEns).reduce {
              _ || _
            },
              finalChoice(0),
              mem._1
            )
          )
        )
      }
      else mem._1 := Mux(io.reset, initval, Mux((normalEns ++ shiftEns).reduce {
        _ || _
      }, finalChoice(0), mem._1))
    }
    else if (p.isBuf) {
      mem._1 := Mux(io.asInstanceOf[ShiftRegFileInterface].dump_en,
        io.asInstanceOf[ShiftRegFileInterface].dump_in(flatCoord),
        Mux(io.reset,
          initval,
          mem._1
        )
      )
    }
    else mem._1 := mem._1
  }

  // Connect read data to output
  p.RMapping.zipWithIndex.foreach{ case (rm, k) => 
    val port = io.rPort(k)
    port.output.zipWithIndex.foreach{case (out, lane) => 
      if (rm.broadcast(lane) > 0) { // Go spelunking for wire that makes true connection
        val castgrp = rm.castgroup(lane)
        out := (p.RMapping.flatMap(_.castgroup), p.RMapping.flatMap(_.broadcast), io.rPort.flatMap(_.output)).zipped.collect{case (cg, b, o) if b == 0 && cg == castgrp => o}.head
      }
      else {
        val visBanksForLane = port.visibleBanks(lane).zipWithIndex.map{case(r,j) => r.expand(p.Ns(j))}
        val visibleMems = m.collect{case (m, ba,_,_) if ba.zip(visBanksForLane).forall{case (real, possible) => possible.contains(real)} => (m, ba)}
        val datas = visibleMems.map(_._1)
        val bankMatches = if (visibleMems.size == 1) Seq(true.B) else visibleMems.map(_._2).map{ba => port.banks.grouped(p.Ns.size).toSeq(lane).zip(ba).map{case (a,b) => a === b.U}.reduce{_&&_} }
        val en = port.en(lane)
        val sel = bankMatches.map{be => be & en}
        out := chisel3.util.PriorityMux(sel, datas)
      }
    }
  }
}

class LUT(p: MemParams) extends MemPrimitive(p) {
  def this(logicalDims: List[Int], bitWidth: Int, banks: List[Int], blocks: List[Int], neighborhood: List[Int],
           WMapping: List[Access], RMapping: List[Access],
           inits: Option[List[Double]], syncMem: Boolean, fracBits: Int, numActives: Int, myName: String) = this(MemParams(StandardInterfaceType, logicalDims,bitWidth,banks,blocks,neighborhood,WMapping,RMapping,BankedMemory,inits,syncMem,fracBits, false, numActives, myName))
  def this(logicalDims: List[Int], bitWidth: Int, banks: List[Int], blocks: List[Int], neighborhood: List[Int],
           WMapping: List[Access], RMapping: List[Access],
           bankingMode: BankingMode, inits: Option[List[Double]], syncMem: Boolean, fracBits: Int, numActives: Int, myName: String) = this(MemParams(StandardInterfaceType, logicalDims,bitWidth,banks,blocks,neighborhood,WMapping,RMapping,bankingMode,inits,syncMem,fracBits, false, numActives, myName))
  // def this(tuple: (List[Int], Int, List[Access], List[Access])) = this(tuple._1,tuple._2,tuple._3,tuple._4,None, false, 0, false, 1, "LUT")
  // def this(tuple: (List[Int], Int, List[Access], List[Access], Option[List[Double]], Boolean, Int)) = this(tuple._1,tuple._2,tuple._3,tuple._4,tuple._5,tuple._6, tuple._7, false, 1, "LUT")


  // Create list of (mem: Mem1D, coords: List[Int] <coordinates of bank>)
  val m = (0 until p.volume).map{ i =>
    val coords = p.Ds.zipWithIndex.map{ case (b,j) =>
      i % p.Ds.drop(j).product / p.Ds.drop(j+1).product
    }
    val initval = if (p.inits.isDefined) (p.inits.get.apply(i)*scala.math.pow(2,p.fracBits)).toLong.S((p.bitWidth+1).W).asUInt.apply(p.bitWidth,0) else 0.U(p.bitWidth.W)
    val mem = RegInit(initval)
    (mem,coords)
  }

  // Connect read data to output
  p.RMapping.zipWithIndex.foreach{ case (rm, k) => 
    val port = io.rPort(k)
    port.output.zipWithIndex.foreach{case (out, lane) => 
      if (rm.broadcast(lane) > 0) { // Go spelunking for wire that makes true connection
        val castgrp = rm.castgroup(lane)
        out := (p.RMapping.flatMap(_.castgroup), p.RMapping.flatMap(_.broadcast), io.rPort.flatMap(_.output)).zipped.collect{case (cg, b, o) if b == 0 && cg == castgrp => o}.head
      }
      else {
        val visBanksForLane = port.visibleBanks(lane).zipWithIndex.map{case(r,j) => r.expand(p.Ns(j))}
        val visibleMems = m.collect{case (m, ba) if ba.zip(visBanksForLane).forall{case (real, possible) => possible.contains(real)} => (m, ba)}
        val datas = visibleMems.map(_._1)
        val bankMatches = if (visibleMems.size == 1) Seq(true.B) else visibleMems.map(_._2).map{ba => port.banks.grouped(p.Ns.size).toSeq(lane).zip(ba).map{case (a,b) => a === b.U}.reduce{_&&_} }
        val en = port.en(lane)
        val sel = bankMatches.map{be => be & en}
        out := chisel3.util.PriorityMux(sel, datas)
      }
    }
  }
}


// Backing memory for SRAM
class Mem1D(val size: Int, bitWidth: Int, syncMem: Boolean = false) extends Module { // Unbanked, inner 1D mem
  def this(size: Int) = this(size, 32)

  val addrWidth = _root_.utils.math.log2Up(size)

  val io = IO( new Bundle {
    val r = Input(new R_Port(1, addrWidth, List(1), bitWidth, List(List(ResidualGenerator(1,0,1)))))
    val w = Input(new W_Port(1, addrWidth, List(1), bitWidth, List(List(ResidualGenerator(1,0,1)))))
    val output = Output(UInt(bitWidth.W))
  })

  // We can do better than MaxJ by forcing mems to be single-ported since
  //   we know how to properly schedule reads and writes
  val wInBound = io.w.ofs.head <= size.U
  val rInBound = io.r.ofs.head <= size.U

  if (syncMem) {
    if (size <= globals.target.SramThreshold) {
      val m = (0 until size).map{ i =>
        val reg = RegInit(0.U(bitWidth.W))
        reg := Mux(io.w.en.head & wInBound & (io.w.ofs.head === i.U(addrWidth.W)), io.w.data.head, reg)
        i.U(addrWidth.W) -> reg
      }
      val radder = getRetimed(io.r.ofs.head,1,io.r.backpressure)
      io.output := getRetimed(MuxLookup(radder, 0.U(bitWidth.W), m), 1, io.r.backpressure)
    } else {
      val m = Module(new SRAM(UInt(bitWidth.W), size, "Generic")) // TODO: Change to BRAM or URAM once we get SRAMVerilogAWS_BRAM/URAM.v
      if (size >= 2) m.io.raddr     := getRetimed(io.r.ofs.head, 1, io.r.backpressure)
      else           m.io.raddr     := 0.U
      m.io.waddr     := io.w.ofs.head
      m.io.wen       := io.w.en.head & wInBound
      m.io.wdata     := io.w.data.head
      m.io.backpressure      := io.r.backpressure
      io.output := m.io.rdata
    }
  } else {
    if (size <= globals.target.SramThreshold) {
      val m = (0 until size).map{ i =>
        val reg = RegInit(0.U(bitWidth.W))
        reg := Mux(io.w.en.head & (io.w.ofs.head === i.U(addrWidth.W)), io.w.data.head, reg)
        i.U(addrWidth.W) -> reg
      }
      io.output := MuxLookup(io.r.ofs.head, 0.U(bitWidth.W), m)
    } else {
      val m = Mem(size, UInt(bitWidth.W) /*, seqRead = true deprecated? */)
      when (io.w.en.head & wInBound) {m(io.w.ofs.head) := io.w.data.head}
      io.output := m(io.r.ofs.head)
    }
  }
}


class Mem1DDualRead(val size: Int, bitWidth: Int, syncMem: Boolean = false) extends Module { // Unbanked, inner 1D mem
  def this(size: Int) = this(size, 32)

  val addrWidth = _root_.utils.math.log2Up(size)

  val io = IO( new Bundle {
    val r0 = Input(new R_Port(1, addrWidth, List(1), bitWidth, List(List(ResidualGenerator(1,0,1)))))
    val r1 = Input(new R_Port(1, addrWidth, List(1), bitWidth, List(List(ResidualGenerator(1,0,1)))))
    val w = Input(new W_Port(1, addrWidth, List(1), bitWidth, List(List(ResidualGenerator(1,0,1)))))
    val output0 = Output(UInt(bitWidth.W))
    val output1 = Output(UInt(bitWidth.W))
  })

  // We can do better than MaxJ by forcing mems to be single-ported since
  //   we know how to properly schedule reads and writes
  val wInBound = io.w.ofs.head <= size.U
  val r0InBound = io.r0.ofs.head <= size.U
  val r1InBound = io.r1.ofs.head <= size.U

  if (syncMem) {
    val m = Module(new SRAMDualRead(UInt(bitWidth.W), size, "Generic")) // TODO: Change to BRAM or URAM once we get SRAMVerilogAWS_BRAM/URAM.v
    if (size >= 2) m.io.raddr0     := getRetimed(io.r0.ofs.head, 1, io.r0.backpressure)
    else           m.io.raddr0     := 0.U
    if (size >= 2) m.io.raddr1     := getRetimed(io.r1.ofs.head, 1, io.r1.backpressure)
    else           m.io.raddr1     := 0.U
    m.io.waddr     := io.w.ofs.head
    m.io.wen       := io.w.en.head & wInBound
    m.io.wdata     := io.w.data.head
    m.io.backpressure0      := io.r0.backpressure
    m.io.backpressure1      := io.r1.backpressure
    io.output0 := m.io.rdata0
    io.output1 := m.io.rdata1
  } else {
    Console.println(s"[WARNING] Dual Read Port SRAM without syncMem flag is NOT recommended (it uses double the memory since it uses the chisel3 memory template)")
    val m0 = Mem(size, UInt(bitWidth.W) /*, seqRead = true deprecated? */)
    val m1 = Mem(size, UInt(bitWidth.W) /*, seqRead = true deprecated? */)
    when (io.w.en.head & wInBound) {
      m0(io.w.ofs.head) := io.w.data.head
      m1(io.w.ofs.head) := io.w.data.head
    }
    io.output0 := m0(io.r0.ofs.head)
    io.output1 := m1(io.r1.ofs.head)
  }
}


class enqPort(val w: Int) extends Bundle {
  val data = UInt(w.W)
  val en = Bool()

  override def cloneType = new enqPort(w).asInstanceOf[this.type] // See chisel3 bug 358
}

class Compactor(val ports: List[Int], val banks: Int, val width: Int, val bitWidth: Int = 32) extends Module {
  val io = IO( new Bundle {
      val numEnabled =Input(UInt(width.W))
      val in = Vec(1 max ports.sum, Input(new enqPort(bitWidth)))
      val out = Vec(1 max banks, Output(new enqPort(bitWidth)))
    })

    val enBelow = List.tabulate[UInt](ports.sum){j => (0 until j).map{ k => Mux(io.in(k).en, 1.U(16.W), 0.U(16.W)) }.foldLeft(0.U(16.W)){_+_}}
    (0 until banks).foreach{i =>
      // Identify first lane between i and ports.sum that is enabled
      val takeThisLane = List.tabulate(ports.sum){j =>
        if (j < i) false.B
        else {
          io.in(j).en && enBelow(j) === i.U
        }
      }
      io.out(i).data := chisel3.util.PriorityMux(takeThisLane, io.in.map(_.data))
      io.out(i).en := takeThisLane.foldLeft(false.B){_||_}
    }
}

/* This consists of an innermost compactor, surrounded by a router.  The compactor
   takes all of the enq ports in, has as many priority muxes as required for the largest
   enq port bundle, and outputs the compacted enq port bundle.  The shifter takes this 
   compacted bundle and shifts it so that they get connected to the correct fifo banks
   outside of the module
*/
class CompactingEnqNetwork(val ports: List[Int], val banks: Int, val width: Int, val bitWidth: Int = 32) extends Module {
  val io = IO( new Bundle {
      val headCnt = Input(SInt(width.W))
      val in = Vec(1 max ports.sum, Input(new enqPort(bitWidth)))
      val out = Vec(1 max banks, Output(new enqPort(bitWidth)))
    })

  val numEnabled = io.in.map{i => Mux(i.en, 1.U(width.W), 0.U(width.W))}.reduce{_+_}
  val num_compactors = if (ports.isEmpty) 1 else ports.sum

  // Compactor
  val compactor = Module(new Compactor(ports, banks, width, bitWidth))
  compactor.io.in := io.in
  compactor.io.numEnabled := numEnabled

  // Router
  val current_base_bank = Math.singleCycleModulo(io.headCnt, banks.S(width.W))
  val upper = current_base_bank + numEnabled.asSInt - banks.S(width.W)
  val num_straddling = Mux(upper < 0.S(width.W), 0.S(width.W), upper)
  val num_straight = numEnabled.asSInt - num_straddling
  val outs = (0 until banks).map{ i =>
    val lane_enable = Mux(i.S(width.W) < num_straddling | (i.S(width.W) >= current_base_bank & i.S(width.W) < current_base_bank + numEnabled.asSInt), true.B, false.B)
    val id_from_base = Mux(i.S(width.W) < num_straddling, i.S(width.W) + num_straight, i.S(width.W) - current_base_bank)
    val port_vals = (0 until banks).map{ i =>
      i.U(width.W) -> compactor.io.out(i).data
    }
    val lane_data = chisel3.util.MuxLookup(id_from_base.asUInt, 0.U(bitWidth.W), port_vals)
    (lane_data,lane_enable)
  }

  (0 until banks).foreach{i => 
    if (banks == 0) io.out(i).data := 0.U else io.out(i).data := outs(i)._1
    if (banks == 0) io.out(i).en := false.B else io.out(i).en := outs(i)._2
  }
}

class CompactingDeqNetwork(val ports: List[Int], val banks: Int, val width: Int, val bitWidth: Int = 32) extends Module {
  val io = IO( new Bundle {
      val tailCnt = Input(SInt(width.W))
      val input = new Bundle{
        val data = Vec(1 max banks, Input(UInt(bitWidth.W)))
        val deq = Vec(1 max ports.sum, Input(Bool()))
      }
      val output = Vec({if (ports.isEmpty) 1 else ports.sum}, Output(UInt(bitWidth.W)))
    })
  io.output := DontCare
  // Compactor
  val numEnabled = io.input.deq.map{i => Mux(i, 1.U(width.W), 0.U(width.W))}.reduce{_+_}

  // Router
  val current_base_bank = Math.singleCycleModulo(io.tailCnt, banks.S(width.W))
  val upper = current_base_bank + numEnabled.asSInt - banks.S(width.W)
  val num_straddling = Mux(upper < 0.S(width.W), 0.S(width.W), upper)
  val num_straight = numEnabled.asSInt - num_straddling
  // TODO: Probably has a bug if you have more than one dequeuer
  (0 until {if (ports.isEmpty) 1 else ports.sum}).foreach{ i =>
    val id_from_base = Mux(i.S(width.W) < num_straddling, i.S(width.W) + num_straight, Math.singleCycleModulo(i.S(width.W) + current_base_bank, banks.S(width.W)))
    val ens_below = if (i>0) (0 until i).map{j => Mux(io.input.deq(j), 1.U(width.W), 0.U(width.W)) }.reduce{_+_} else 0.U(width.W)
    val proper_bank = Math.singleCycleModulo(current_base_bank.asUInt + ens_below, banks.U(width.W))
    val port_vals = (0 until banks).map{ j => 
      j.U(width.W) -> io.input.data(j)
    }
    io.output(i) := chisel3.util.MuxLookup(proper_bank.asUInt, 0.U(bitWidth.W), port_vals)
  }

}
