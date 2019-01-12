package spatial.codegen.chiselgen

import argon._
import spatial.lang._
import spatial.node._
import spatial.metadata.access._
import spatial.metadata.control._
import spatial.metadata.memory._
import spatial.metadata.retiming._
import spatial.metadata.types._
import spatial.util.spatialConfig

trait ChiselGenDRAM extends ChiselGenCommon {
  var requesters = scala.collection.mutable.HashMap[Sym[_], Int]()
  var loadStreams = scala.collection.mutable.HashMap[Sym[_], Int]()
  var storeStreams = scala.collection.mutable.HashMap[Sym[_], Int]()
  var gatherStreams = scala.collection.mutable.HashMap[Sym[_], Int]()
  var scatterStreams = scala.collection.mutable.HashMap[Sym[_], Int]()

  override protected def gen(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case DRAMHostNew(_,_) =>
      hostDrams += (lhs -> hostDrams.size)
      lhs.loadStreams.foreach{f => 
        forceEmit(src"val ${f.addrStream} = top.io.memStreams.loads(${loadStreams.size}).cmd // StreamOut")
        forceEmit(src"val ${f.dataStream} = top.io.memStreams.loads(${loadStreams.size}).data // StreamIn")
        RemoteMemories += f.addrStream; RemoteMemories += f.dataStream
        loadStreams += (f -> loadStreams.size)
      }
      lhs.storeStreams.foreach{f => 
        forceEmit(src"val ${f.addrStream} = top.io.memStreams.stores(${storeStreams.size}).cmd // StreamOut")
        forceEmit(src"val ${f.dataStream} = top.io.memStreams.stores(${storeStreams.size}).data // StreamOut")
        forceEmit(src"val ${f.ackStream}  = top.io.memStreams.stores(${storeStreams.size}).wresp // StreamIn")
        RemoteMemories += f.addrStream; RemoteMemories += f.dataStream; RemoteMemories += f.ackStream
        storeStreams += (f -> storeStreams.size)
      }
      lhs.gatherStreams.foreach{f => 
        forceEmit(src"val ${f.addrStream} = top.io.memStreams.gathers(${gatherStreams.size}).cmd // StreamOut")
        forceEmit(src"val ${f.dataStream} = top.io.memStreams.gathers(${gatherStreams.size}).data // StreamIn")
        RemoteMemories += f.addrStream; RemoteMemories += f.dataStream
        gatherStreams += (f -> gatherStreams.size)
      }
      lhs.scatterStreams.foreach{f => 
        forceEmit(src"val ${f.addrStream} = top.io.memStreams.scatters(${scatterStreams.size}).cmd // StreamOut")
        forceEmit(src"val ${f.ackStream} = top.io.memStreams.scatters(${scatterStreams.size}).wresp // StreamOut")
        RemoteMemories += f.addrStream; RemoteMemories += f.ackStream
        scatterStreams += (f -> scatterStreams.size)
      }

      forceEmit(src"val $lhs = Wire(new FixedPoint(true, 64, 0))")
      forceEmit(src"$lhs.r := top.io.argIns(api.${argHandle(lhs)}_ptr)")

    case DRAMAccelNew(dim) =>
      val reqCount = lhs.consumers.collect {
        case w@Op(_: DRAMAlloc[_,_] | _: DRAMDealloc[_,_]) => w
      }.size
      createMemObject(lhs){
        emit(src"""val m = Module(new DRAMAllocator(${dim}, $reqCount)); m.io <> DontCare""")
      }
      val id = accelDrams.size
      emit(src"top.io.heap.req($id) := $lhs.m.io.heapReq")
      emit(src"$lhs.m.io.heapResp := top.io.heap.resp($id)")
      accelDrams += (lhs -> id)

    case DRAMAlloc(dram, dims) =>
      dram match {
        case _@Op(DRAMAccelNew(_)) =>
          val id = requesters.size
          val parent = lhs.parent.s.get
          val invEnable = src"""${DL(src"${parent}.datapathEn & ${parent}.iiDone", lhs.fullDelay, true)}"""
          emit(src"${dram}.m.io.appReq($id).valid := $invEnable")
          emit(src"${dram}.m.io.appReq($id).bits.allocDealloc := true.B")
          val d = dims.map{ quote(_) + ".r" }.mkString(src"List[UInt](", ",", ")")
          emit(src"${dram}.m.io.appReq($id).bits.dims.zip($d).foreach { case (l, r) => l := r }")
          requesters += (lhs -> id)
        case _ =>
      }

    case DRAMIsAlloc(dram) =>
      dram match {
        case _@Op(DRAMAccelNew(_)) =>
          emit(src"val $lhs = $dram.m.io.isAlloc")
        case _@Op(DRAMHostNew(_,_)) =>
          emit(src"val $lhs = true.B")
        case _ =>
      }

    case DRAMDealloc(dram) =>
      dram match {
        case _@Op(DRAMAccelNew(_)) =>
          val id = requesters.size
          val parent = lhs.parent.s.get
          val invEnable = src"""${DL(src"${parent}.datapathEn & ${parent}.iiDone", lhs.fullDelay, true)}"""
          emit(src"${dram}.m.io.appReq($id).valid := $invEnable")
          emit(src"${dram}.m.io.appReq($id).bits.allocDealloc := false.B")
          requesters += (lhs -> id)
        case _ =>
      }

    case DRAMAddress(dram) =>
      dram match {
        case _@Op(DRAMAccelNew(_)) =>
          emit(src"val $lhs = ${dram}.m.io.addr")
        case _@Op(DRAMHostNew(_,_)) =>
          emit(src"val $lhs = $dram")
        case _ =>
      }

    case _ => super.gen(lhs, rhs)
  }

  override def emitPostMain(): Unit = {

    inGen(out, s"IOModule.$ext") {
      emit("// Heap")
      emit(src"val io_numAllocators = scala.math.max(1, ${accelDrams.size})")
    }

    inGen(out, "Instantiator.scala") {
      emit(src"// Heap")
      emit(src"val numAllocators = ${accelDrams.size}")
    }
  
    super.emitPostMain()
  }
}
