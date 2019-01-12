package spatial.codegen.chiselgen

import argon._
import spatial.lang._
import spatial.node._

import spatial.metadata.control._
import spatial.metadata.memory._
import spatial.metadata.retiming._

trait ChiselGenInterface extends ChiselGenCommon {

  var loadsList = List[Sym[_]]()
  var storesList = List[Sym[_]]()
  var loadParMapping = List[String]()
  var storeParMapping = List[String]()

  var gathersList = List[Sym[_]]()
  var scattersList = List[Sym[_]]()
  var gatherParMapping = List[String]()
  var scatterParMapping = List[String]()

  override protected def gen(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case InputArguments() =>
    case ArgInNew(init)  => 
      argIns += (lhs -> argIns.toList.length)
      val id = argHandle(lhs)
      forceEmit(src"val $lhs = top.io.argIns(api.${id}_arg)")
    case HostIONew(init)  => 
      argIOs += (lhs -> argIOs.toList.length)
      forceEmit(src"val $lhs = top.io.argOuts(${argIOs(lhs)})")
    case ArgOutNew(init) => 
      argOuts += (lhs -> argOuts.toList.length)
      forceEmit(src"val $lhs = top.io.argOuts(top.io_numArgIOs_reg + ${argOuts(lhs)})")

    // case GetReg(reg) if (reg.isArgOut) =>
    //   argOutLoopbacks.getOrElseUpdate(argOuts(reg), argOutLoopbacks.toList.length)
    //   // emitGlobalWireMap(src"""${lhs}""",src"Wire(${newWire(reg.tp.typeArgs.head)})")
    //   emit(src"""${lhs}.r := io.argOutLoopbacks(${argOutLoopbacks(argOuts(reg))})""")

    case GetReg(reg) if reg.isHostIO =>
      emit(src"""val ${lhs} = Wire(${lhs.tp})""")
      val id = argHandle(reg)
      emit(src"""${lhs}.r := $reg.bits.r""")

    case RegRead(reg)  if reg.isArgIn =>
      emit(src"""val ${lhs} = Wire(${lhs.tp})""")
      val id = argHandle(reg)
      emit(src"""${lhs}.r := $reg.r""")

    case RegRead(reg)  if reg.isHostIO =>
      emit(src"""val ${lhs} = Wire(${lhs.tp})""")
      val id = argHandle(reg)
      emit(src"""${lhs}.r := $reg.bits.r""")

    case RegRead(reg)  if reg.isArgOut =>
      argOutLoopbacks.getOrElseUpdate(argOuts(reg), argOutLoopbacks.toList.length)
      emit(src"""val ${lhs} = Wire(${reg.tp.typeArgs.head})""")
      emit(src"""${lhs}.r := $reg.bits.echo.r""")


    case RegWrite(reg, v, en) if reg.isHostIO =>
      val isBroadcast = lhs.port.broadcast.exists(_>0)
      if (!isBroadcast) {
        val id = lhs.port.muxPort
        emit(src"val $id = $id")
        v.tp match {
          case FixPtType(s,d,f) =>
            if (s) {
              val pad = 64 - d - f
              if (pad > 0) {
                emit(src"""${reg}.data_options($id) := util.Cat(util.Fill($pad, ${v}.msb), ${v}.r)""")
              } else {
                emit(src"""${reg}.data_options($id) := ${v}.r""")
              }
            } else {
              emit(src"""${reg}.data_options($id) := ${v}.r""")
            }
          case _ =>
            emit(src"""${reg}.data_options($id) := ${v}.r""")
        }
        val enStr = if (en.isEmpty) "true.B" else en.map(quote).mkString(" & ")
        emit(src"""${reg}.en_options($id) := ${enStr} & ${DL(src"datapathEn & iiDone", lhs.fullDelay)}""")
      }

    case RegWrite(reg, v, en) if reg.isArgOut =>
      val isBroadcast = lhs.port.broadcast.exists(_>0)
      if (!isBroadcast) {
        val id = lhs.port.muxPort
        emit(src"val $id = $id")
        val padded = v.tp match {
          case FixPtType(s,d,f) if s && (64 > d + f) =>
            src"util.Cat(util.Fill(${64 - d - f}, $v.msb), $v.r)"
          case _ => src"$v.r"
        }
        emit(src"""${reg}.bits := $padded""")
        val enStr = if (en.isEmpty) "true.B" else en.map(quote).mkString(" & ")
        emit(src"""${reg}.valid := ${enStr} & ${DL(src"datapathEn & iiDone", lhs.fullDelay)}""")
      }

    case FringeDenseLoad(dram,cmdStream,dataStream) =>
      appPropertyStats += HasTileLoad
      if (cmdStream.isAligned) appPropertyStats += HasAlignedLoad
      else appPropertyStats += HasUnalignedLoad
      val par = dataStream.readers.head match { case Op(e@StreamInBankedRead(strm, ens)) => ens.length }

      val id = loadsList.length
      loadParMapping = loadParMapping :+ s"""StreamParInfo(${bitWidth(dram.tp.typeArgs.head)}, ${par}, 0)"""
      loadsList = loadsList :+ dram

    case FringeSparseLoad(dram,cmdStream,dataStream) =>
      appPropertyStats += HasGather
      val par = dataStream.readers.head match { case Op(e@StreamInBankedRead(strm, ens)) => ens.length }

      val id = gathersList.length
      gatherParMapping = gatherParMapping :+ s"""StreamParInfo(${bitWidth(dram.tp.typeArgs.head)}, ${par}, 0)"""
      gathersList = gathersList :+ dram

      //emit(src"${cmdStream}.ready := top.io.memStreams.gathers($id).ready // Not sure why the cmdStream ready used to be delayed")
      //emit(src"top.io.memStreams.gathers($id).bits.addr.zip(${cmdStream}.m).foreach{case (a,b) => a := b.r}")
      //emit(src"top.io.memStreams.gathers($id).valid :=  ${cmdStream}.valid & ${cmdStream}.ready")
//
      //// Connect the streams to their IO interface signals
      //emit(src"top.io.memStreams.gathers($id).ready := ${dataStream}.ready")
      //emit(src"""${dataStream}.zip(top.io.memStreams.gathers($id).bits).foreach{case (a,b) => a.r := ${DL("b", src"${dataStream.readers.head.fullDelay}.toInt")}}""")
      //emit(src"""${dataStream}.now_valid := top.io.memStreams.gathers($id).valid""")
      //emit(src"""${dataStream}.valid := ${DL(src"${dataStream}.now_valid", src"${dataStream.readers.head.fullDelay}.toInt", true)}""")

    case FringeDenseStore(dram,cmdStream,dataStream,ackStream) =>
      appPropertyStats += HasTileStore
      if (cmdStream.isAligned) appPropertyStats += HasAlignedStore
      else appPropertyStats += HasUnalignedStore

      // Get parallelization of datastream
      val par = dataStream.writers.head match { case Op(e@StreamOutBankedWrite(_, _, ens)) => ens.length }

      val id = storesList.length
      storeParMapping = storeParMapping :+ s"""StreamParInfo(${bitWidth(dram.tp.typeArgs.head)}, ${par}, 0)"""
      storesList = storesList :+ dram

    case FringeSparseStore(dram,cmdStream,ackStream) =>
      appPropertyStats += HasScatter
      // Get parallelization of datastream
      val par = cmdStream.writers.head match { case Op(e@StreamOutBankedWrite(_, _, ens)) => ens.length }

      val id = scattersList.length
      scatterParMapping = scatterParMapping :+ s"""StreamParInfo(${bitWidth(dram.tp.typeArgs.head)}, ${par}, 0)"""
      scattersList = scattersList :+ dram

      //// Connect IO interface signals to their streams
      //val (dataMSB, dataLSB)  = getField(cmdStream.tp.typeArgs.head, "_1")
      //val (addrMSB, addrLSB)  = getField(cmdStream.tp.typeArgs.head, "_2")
//
      //emit(src"top.io.memStreams.scatters($id).bits.zip(${cmdStream}.m).foreach{case (wport, wdata) => wport := wdata($dataMSB, $dataLSB)}")
      //emit(src"top.io.memStreams.scatters($id).valid := ${cmdStream}.valid")
      //emit(src"top.io.memStreams.scatters($id).bits.addr.zip(${cmdStream}.m).foreach{case (a,b) => a := b($addrMSB, $addrLSB)}")
      //emit(src"top.io.memStreams.scatters($id).valid :=  ${cmdStream}.valid & ${cmdStream}.ready")
      //emit(src"${cmdStream}.ready := top.io.memStreams.scatters($id).ready & top.io.memStreams.scatters($id).ready")
      //emit(src"""${ackStream}.now_valid := top.io.memStreams.scatters($id).wresp.valid""")
      //emit(src"""${ackStream}.valid := ${DL(src"${ackStream}.now_valid", src"${ackStream.readers.head.fullDelay}.toInt", true)}""")
      //emit(src"""${ackStream}.foreach{_ := top.io.memStreams.scatters($id).wresp.valid}""")
      //emit(src"""top.io.memStreams.scatters($id).wresp.ready := ${ackStream}.ready""")

    case _ => super.gen(lhs, rhs)
  }

  override def emitPostMain(): Unit = {
    inGen(out, "Instantiator.scala") {
      emit ("")
      emit ("// Scalars")
      emit (s"val numArgIns_reg = ${argIns.toList.length}")
      emit (s"val numArgOuts_reg = ${argOuts.toList.length}")
      emit (s"val numArgIOs_reg = ${argIOs.toList.length}")
      argIns.zipWithIndex.foreach { case(p,i) => emit(s"""//${quote(p._1)} = argIns($i) ( ${p._1.name.getOrElse("")} )""") }
      argOuts.zipWithIndex.foreach { case(p,i) => emit(s"""//${quote(p._1)} = argOuts($i) ( ${p._1.name.getOrElse("")} )""") }
      argIOs.zipWithIndex.foreach { case(p,i) => emit(s"""//${quote(p._1)} = argIOs($i) ( ${p._1.name.getOrElse("")} )""") }
      emit (s"val io_argOutLoopbacksMap: scala.collection.immutable.Map[Int,Int] = ${argOutLoopbacks}")
      emit ("")
      emit (s"// Memory streams")
      emit (src"""val loadStreamInfo = List(${loadParMapping.map(_.replace("FringeGlobals.",""))}) """)
      emit (src"""val storeStreamInfo = List(${storeParMapping.map(_.replace("FringeGlobals.",""))}) """)
      emit (src"""val gatherStreamInfo = List(${gatherParMapping.map(_.replace("FringeGlobals.",""))}) """)
      emit (src"""val scatterStreamInfo = List(${scatterParMapping.map(_.replace("FringeGlobals.",""))}) """)
      emit (src"""val numArgIns_mem = ${hostDrams.toList.length}""")
      emit (src"""// $loadsList $storesList $gathersList $scattersList)""")
    }

    inGen(out, s"IOModule.$ext") {
      emit ("// Scalars")
      emit (s"val io_numArgIns_reg = ${argIns.toList.length}")
      emit (s"val io_numArgOuts_reg = ${argOuts.toList.length}")
      emit (s"val io_numArgIOs_reg = ${argIOs.toList.length}")
      emit (s"val io_argOutLoopbacksMap: scala.collection.immutable.Map[Int,Int] = ${argOutLoopbacks}")
      emit ("// Memory Streams")
      emit (src"""val io_loadStreamInfo = List($loadParMapping) """)
      emit (src"""val io_storeStreamInfo = List($storeParMapping) """)
      emit (src"""val io_gatherStreamInfo = List($gatherParMapping) """)
      emit (src"""val io_scatterStreamInfo = List($scatterParMapping) """)
      emit (src"val io_numArgIns_mem = ${hostDrams.toList.length}")
      emit (src"val outArgMuxMap: scala.collection.mutable.Map[Int, Int] = scala.collection.mutable.Map[Int,Int]()")

    }

    inGen(out, "ArgAPI.scala") {
      emit("package accel")
      open("object api {")
      emit("\n// ArgIns")
      argIns.foreach{case (a, id) => emit(src"val ${argHandle(a)}_arg = $id")}
      emit("\n// DRAM Ptrs:")
      hostDrams.foreach {case (d, id) => emit(src"val ${argHandle(d)}_ptr = ${id+argIns.toList.length}")}
      emit("\n// ArgIOs")
      argIOs.foreach{case (a, id) => emit(src"val ${argHandle(a)}_arg = ${id+argIns.toList.length+hostDrams.toList.length}")}
      emit("\n// ArgOuts")
      argOuts.foreach{case (a, id) => emit(src"val ${argHandle(a)}_arg = ${id+argIns.toList.length+hostDrams.toList.length+argIOs.toList.length}")}
      emit("\n// Instrumentation Counters")
      instrumentCounters.foreach{case (s,_) => 
        val base = instrumentCounterIndex(s)
        emit(src"val ${quote(s).toUpperCase}_cycles_arg = ${argIOs.toList.length + argOuts.toList.length + base}")
        emit(src"val ${quote(s).toUpperCase}_iters_arg = ${argIOs.toList.length + argOuts.toList.length + base + 1}")
        if (hasBackPressure(s.toCtrl) || hasForwardPressure(s.toCtrl)) {
          emit(src"val ${quote(s).toUpperCase}_stalled_arg = ${argIOs.toList.length + argOuts.toList.length + base + 2}")
          emit(src"val ${quote(s).toUpperCase}_idle_arg = ${argIOs.toList.length + argOuts.toList.length + base + 3}")
        }
      }
      earlyExits.foreach{x => 
        emit(src"val ${quote(x).toUpperCase}_exit_arg = ${argOuts.toList.length + argIOs.toList.length + instrumentCounterArgs()}")
      }
      close("}")
    }
    super.emitPostMain()
  }

}
