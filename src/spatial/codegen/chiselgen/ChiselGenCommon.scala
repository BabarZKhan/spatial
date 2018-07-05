package spatial.codegen.chiselgen

import argon._
import argon.node._
import spatial.lang._
import spatial.node._
import spatial.metadata.bounds._
import spatial.metadata.control._
import spatial.metadata.memory._
import spatial.metadata.retiming._
import spatial.metadata.types._
import spatial.util.spatialConfig

trait ChiselGenCommon extends ChiselCodegen { 

  // Statistics counters
  var pipeChainPassMap = new scala.collection.mutable.HashMap[Sym[_], List[Sym[_]]]
  var pipeChainPassMapBug41Hack = new scala.collection.mutable.HashMap[Sym[_], Sym[_]]
  var streamCopyWatchlist = List[Sym[_]]()
  var controllerStack = scala.collection.mutable.Stack[Sym[_]]()
  var validPassMap = new scala.collection.mutable.HashMap[Sym[_], Seq[Sym[_]]] // Map from a valid bound sym to its ctrl node, for computing suffix on a valid before we enter the ctrler
  var accumsWithIIDlay = new scala.collection.mutable.ListBuffer[Sym[_]]
  var widthStats = new scala.collection.mutable.ListBuffer[Int]
  var depthStats = new scala.collection.mutable.ListBuffer[Int]
  var appPropertyStats = Set[AppProperties]()

  // Interface
  var argOuts = scala.collection.mutable.HashMap[Sym[_], Int]()
  var argIOs = scala.collection.mutable.HashMap[Sym[_], Int]()
  var argIns = scala.collection.mutable.HashMap[Sym[_], Int]()
  var argOutLoopbacks = scala.collection.mutable.HashMap[Int, Int]() // info about how to wire argouts back to argins in Fringe
  var drams = scala.collection.mutable.HashMap[Sym[_], Int]()
  /* List of break or exit nodes */
  var earlyExits: List[Sym[_]] = List()

  def latencyOption(op: String, b: Option[Int]): Double = {
    if (spatialConfig.enableRetiming) {
      if (b.isDefined) {spatialConfig.target.latencyModel.model(op)("b" -> b.get)("LatencyOf")}
      else spatialConfig.target.latencyModel.model(op)()("LatencyOf")
    } else {
      0.0
    }
  }

  var argHandleMap = scala.collection.mutable.HashMap[Sym[_], String]() // Map for tracking defs of nodes and if they get redeffed anywhere, we map it to a suffix
  def argHandle(d: Sym[_]): String = {
    if (argHandleMap.contains(d)) {
      argHandleMap(d)
    } else {
      val attempted_name = d.name.getOrElse(quote(d)).toUpperCase
      if (argHandleMap.values.toList.contains(attempted_name)) {
        val taken = argHandleMap.values.toList.filter(_.contains(attempted_name))
        val given_name = attempted_name + "_dup" + taken.length
        argHandleMap += (d -> given_name)
        given_name
      } else {
        argHandleMap += (d -> attempted_name)
        attempted_name
      }
    }
  }

  final protected def quoteAsScala(x: Sym[_]): String = {
    x.rhs match {
      case Def.Const(c) => quoteConst(x.tp, c).replaceAll("\\.F.*","").replaceAll("\\.U(.*)","").replaceAll("\\.S(.*)","").replaceAll("false.B","0").replaceAll("true.B","1")
      case Def.Node(id,_) => x match {
        case Op(SimpleStruct(fields)) => 
          var shift = 0
          fields.map{f => // Used to be .reversed but not sure now
            val x = src"(${quoteAsScala(f._2)} << $shift).toDouble"
            shift = shift + bitWidth(f._2.tp)
            x
          }.mkString(" + ")
        case _ => throw new Exception(s"Cannot quote $x as a Scala type!") 
      }
      case _ => throw new Exception(s"Cannot quote $x as a Scala type!")
    }
  }

  protected def getField(tp: Type[_],field: String): (Int,Int) = tp match {
    case x: Struct[_] =>
      val idx = x.fields.indexWhere(_._1 == field)
      val width = bitWidth(x.fields(idx)._2)
      val prec = x.fields.take(idx)
      val precBits = prec.map{case (_,bt) => bitWidth(bt)}.sum
      (precBits+width-1, precBits)
    case _ => (-1, -1)
  }

  def emitCounterChain(lhs: Sym[_]): Unit = {
    if (lhs.cchains.nonEmpty) {
      val cchain = lhs.cchains.head
      var isForever = cchain.isForever
      val w = bitWidth(cchain.counters.head.typeArgs.head)
      val counter_data = cchain.counters.map{
        case Op(CounterNew(start, end, step, par)) => 
          val (start_wire, start_constr) = start match {case Final(s) => (src"${s}.FP(true, $w, 0)", src"Some($s)"); case _ => (quote(start), "None")}
          val (end_wire, end_constr) = end match {case Final(e) => (src"${e}.FP(true, $w, 0)", src"Some($e)"); case _ => (quote(end), "None")}
          val (stride_wire, stride_constr) = step match {case Final(st) => (src"${st}.FP(true, $w, 0)", src"Some($st)"); case _ => (quote(step), "None")}
          val par_wire = {src"$par"}.split('.').take(1)(0).replaceAll("L","") // TODO: What is this doing?
          (start_wire, end_wire, stride_wire, par_wire, start_constr, end_constr, stride_constr, "Some(0)")
        case Op(ForeverNew()) => 
          isForever = true
          ("0.S", "999.S", "1.S", "1", "None", "None", "None", "Some(0)") 
      }
      emitGlobalWireMap(src"""${cchain}_done""", """Wire(Bool())""")
      emitGlobalWireMap(src"""${cchain}_en""", """Wire(Bool())""")
      emitGlobalWireMap(src"""${cchain}_resetter""", """Wire(Bool())""")
      emitGlobalModule(src"""val ${cchain}_strides = List(${counter_data.map(_._3)}) // TODO: Safe to get rid of this and connect directly?""")
      emitGlobalModule(src"""val ${cchain}_stops = List(${counter_data.map(_._2)}) // TODO: Safe to get rid of this and connect directly?""")
      emitGlobalModule(src"""val ${cchain}_starts = List(${counter_data.map{_._1}}) """)
      emitGlobalModule(src"""val ${cchain} = Module(new templates.Counter(List(${counter_data.map(_._4)}), """ + 
                       src"""List(${counter_data.map(_._5)}), List(${counter_data.map(_._6)}), List(${counter_data.map(_._7)}), """ + 
                       src"""List(${counter_data.map(_._8)}), List(${cchain.counters.map(c => bitWidth(c.typeArgs.head))})))""")

      emitt(src"""${cchain}.io.input.stops.zip(${cchain}_stops).foreach { case (port,stop) => port := stop.r.asSInt }""")
      emitt(src"""${cchain}.io.input.strides.zip(${cchain}_strides).foreach { case (port,stride) => port := stride.r.asSInt }""")
      emitt(src"""${cchain}.io.input.starts.zip(${cchain}_starts).foreach { case (port,start) => port := start.r.asSInt }""")
      emitt(src"""${cchain}.io.input.gaps.foreach { gap => gap := 0.S }""")
      emitt(src"""${cchain}.io.input.saturate := true.B""")
      emitt(src"""${cchain}.io.input.enable := ${swap(src"${cchain}", En)}""")
      emitt(src"""${swap(src"${cchain}", Done)} := ${cchain}.io.output.done""")
      emitt(src"""${cchain}.io.input.reset := ${swap(src"${cchain}", Resetter)}""")
      if (streamCopyWatchlist.contains(cchain)) emitt(src"""${cchain}.io.input.isStream := true.B""")
      else emitt(src"""${cchain}.io.input.isStream := false.B""")      
      emitt(src"""val ${cchain}_maxed = ${cchain}.io.output.saturated""")
      cchain.counters.zipWithIndex.foreach { case (c, i) =>
        val x = c.ctrPar.toInt
        if (streamCopyWatchlist.contains(cchain)) {emitGlobalWireMap(s"""${quote(c)}""", src"""Wire(Vec($x, SInt(${bitWidth(cchain.counters(i).typeArgs.head)}.W)))""")}
        else {emitGlobalWire(s"""val ${quote(c)} = (0 until $x).map{ j => Wire(SInt(${bitWidth(cchain.counters(i).typeArgs.head)}.W)) }""")}
        val base = cchain.counters.take(i).map(_.ctrPar.toInt).sum
        emitt(s"""(0 until $x).map{ j => ${quote(c)}(j) := ${quote(cchain)}.io.output.counts($base + j) }""")
      }

    }
  }

  def latencyOptionString(op: String, b: Option[Int]): String = {
    if (spatialConfig.enableRetiming) {
      val latency = latencyOption(op, b)
      if (b.isDefined) {
        s"""Some(${latency})"""
      } else {
        s"""Some(${latency})"""
      }
    } else {
      "None"      
    }
  }

  protected def enableRetimeMatch(en: Sym[_], lhs: Sym[_]): Double = { 
    // val last_def_delay = en match {
    //   case Def(And(_,_)) => latencyOption("And", None)
    //   case Def(Or(_,_)) => latencyOption("Or", None)
    //   case Def(Not(_)) => latencyOption("Not", None)
    //   case Const(_) => 0.0
    //   case Def(DelayLine(size,_)) => size.toDouble // Undo subtraction
    //   case Def(RegRead(_)) => latencyOption("RegRead", None)
    //   case Def(FixEql(a,_)) => latencyOption("FixEql", Some(bitWidth(a.tp)))
    //   case b: Bound[_] => 0.0
    //   case _ => throw new Exception(s"Node enable $en not yet handled in partial retiming")
    // }
    // if (spatialConfig.enableRetiming) symDelay(en) + last_def_delay else 0.0
    if (spatialConfig.enableRetiming) lhs.fullDelay else 0.0
  }


  def remappedEns(node: Sym[_], ens: List[Sym[_]]): String = {
    var previousLevel: Sym[_] = node
    var nextLevel: Option[Sym[_]] = Some(node.parent.s.get)
    var result = ens.map(quote)
    while (nextLevel.isDefined) {
      if (nextLevel.get.isStreamControl) {
        nextLevel.get match {
          case Op(op: UnrolledForeach) =>
            ens.foreach{ my_en_exact =>
              val my_en = my_en_exact match { case Op(DelayLine(_,node)) => node; case _ => my_en_exact}
              op.ens.foreach{ their_en =>
                if (src"${my_en}" == src"${their_en}" & !src"${my_en}".contains("true")) {
                  // Hacky way to avoid double-suffixing
                  if (!src"$my_en".contains(src"_copy${previousLevel}") && !src"$my_en".contains("(") /* hack for remapping */) {  
                    result = result.filter{a => !src"$a".contains(src"$my_en")} :+ swap(src"${my_en}_copy${previousLevel}", Blank)
                  }
                }
              }
            }
          case _ => // do nothing
        }
        nextLevel = None
      } else {
        previousLevel = nextLevel.get
        if (nextLevel.get.parent.s.isDefined) nextLevel = Some(nextLevel.get.parent.s.get)
        else nextLevel = None
      }
    }
    result.mkString("&")
  }

  def getStreamEnablers(c: Sym[_]): String = {
    // If we are inside a stream pipe, the following may be set
    // Add 1 to latency of fifo checks because SM takes one cycle to get into the done state

    if (c.hasStreamAncestor) {
      // TODO: Assumes only one stream access to the fifo (i.e. readersOf(pt).head)
      val lat = c.bodyLatency.sum
      val readsFrom = getReadStreams(c.toCtrl).map{
        case fifo @ Op(FIFONew(size)) => // In case of unaligned load, a full fifo should not necessarily halt the stream
          fifo.readers.head match {
            // case Op(FIFOBankedDeq(_,ens)) => src"(${DL(src"~$fifo.io.asInstanceOf[FIFOInterface].empty", lat+1, true)} | ~${remappedEns(fifo.readers.head,ens.flatten.toList)})"
            // case Op(FIFOBankedDeq(_,ens)) => src"(${DL(src"~$fifo.io.asInstanceOf[FIFOInterface].empty", 1, true)} | ~${remappedEns(fifo.readers.head,ens.flatten.toList)})"
            case Op(FIFOBankedDeq(_,ens)) => src"(~$fifo.io.asInstanceOf[FIFOInterface].empty | ~${remappedEns(fifo.readers.head,ens.flatten.toList)})"
            case Op(FIFOPeek(_,_)) => src""
          }
        case fifo @ Op(StreamInNew(bus)) => src"${swap(fifo, Valid)}"
      }

      val writesTo = getWriteStreams(c.toCtrl).map{
        case fifo @ Op(FIFONew(size)) => // In case of unaligned load, a full fifo should not necessarily halt the stream
          fifo.writers.head match {
            // case Op(FIFOBankedEnq(_,_,ens)) => src"(${DL(src"~$fifo.io.asInstanceOf[FIFOInterface].full", 1, true)} | ~${remappedEns(fifo.writers.head,ens.flatten.toList)})"
            case Op(FIFOBankedEnq(_,_,ens)) => src"(~$fifo.io.asInstanceOf[FIFOInterface].full | ~${remappedEns(fifo.writers.head,ens.flatten.toList)})"
          }
        case fifo @ Op(StreamOutNew(bus)) => src"${swap(fifo,Ready)}"
        // case fifo @ Op(BufferedOutNew(_, bus)) => src"" //src"~${fifo}_waitrequest"
      }

      {if ((writesTo++readsFrom).nonEmpty) "&" else ""} + (writesTo ++ readsFrom).filter(_ != "").mkString(" & ")
    } else {""}
  }

  def getStreamAdvancement(c: Sym[_]): String = { 
    (getReadStreams(c.toCtrl) ++ getWriteStreams(c.toCtrl)).collect {
      case x @ Op(StreamInNew(bus)) => src"${swap(x, Ready)} & ${swap(x, Valid)}"
      case x @ Op(StreamOutNew(bus)) => src"${swap(x, Ready)} & ${swap(x, Valid)}"
    }.mkString(" & ")
  }

  def getStreamHalfAdvancement(c: Sym[_]): String = {  // Only valid for streamins and ready for streamouts
    (getReadStreams(c.toCtrl) ++ getWriteStreams(c.toCtrl)).collect {
      case x @ Op(StreamInNew(bus)) => src"${swap(x, Valid)}"
      case x @ Op(StreamOutNew(bus)) => src"${swap(x, Ready)}"
    }.mkString(" & ")
  }

  def getNowValidLogic(c: Sym[_]): String = { // Because of retiming, the _ready for streamins and _valid for streamins needs to get factored into datapath_en
    // If we are inside a stream pipe, the following may be set
    val readiers = getReadStreams(c.toCtrl).map {
      case fifo @ Op(StreamInNew(bus)) => src"${swap(fifo, NowValid)}" //& ${fifo}_ready"
      case _ => ""
    }.mkString(" & ")
    val hasReadiers = if (readiers != "") "&" else ""
    if (spatialConfig.enableRetiming) src"${hasReadiers} ${readiers}" else " "
  }

  def getStreamReadyLogic(c: Sym[_]): String = { // Because of retiming, the _ready for streamins and _valid for streamins needs to get factored into datapath_en
    // If we are inside a stream pipe, the following may be set
    val readiers = getWriteStreams(c.toCtrl).map {
      case fifo @ Op(StreamInNew(bus)) => src"${swap(fifo, Ready)}"
      case _ => ""
    }.mkString(" & ")
    val hasReadiers = if (readiers != "") "&" else ""
    if (spatialConfig.enableRetiming) src"${hasReadiers} ${readiers}" else " "
  }

  def getFifoReadyLogic(sym: Ctrl): List[String] = {
    getWriteStreams(sym).collect{
      case fifo@Op(FIFONew(_)) if s"${fifo.tp}".contains("IssuedCmd") => src"~${fifo}.io.asInstanceOf[FIFOInterface].full"
    }.toList
  }

  def getAllReadyLogic(sym: Ctrl): List[String] = {
    getWriteStreams(sym).collect{
      case fifo@Op(StreamOutNew(bus)) => src"${swap(fifo, Ready)}"
      case fifo@Op(FIFONew(_)) if s"${fifo.tp}".contains("IssuedCmd") => src"~${fifo}.io.asInstanceOf[FIFOInterface].full"
    }.toList
  }

  def DLTrace(lhs: Sym[_]): Option[String] = lhs match {
    case Op(DelayLine(_, data)) => DLTrace(data)
    case Const(_) => Some(quote(lhs))
    case Param(_) => Some(quote(lhs))
    case _ => None
  }

  def DL[T](name: String, latency: T, isBit: Boolean = false): String = {
    val streamOuts = if (controllerStack.nonEmpty) {getAllReadyLogic(controllerStack.head.toCtrl).mkString(" && ")} else { "" }
    latency match {
      case lat: Int => 
        if (controllerStack.nonEmpty) {
          if (controllerStack.head.hasStreamAncestor & streamOuts != "") {
            if (isBit) src"(${name}).DS($latency, rr, ${streamOuts})"
            else src"Utils.getRetimed($name, $latency, ${streamOuts})"
          } else {
            if (isBit) src"(${name}).D($latency, rr)"
            else src"Utils.getRetimed($name, $latency)"          
          }
        } else {
          if (isBit) src"(${name}).D($latency, rr)"
          else src"Utils.getRetimed($name, $latency)"                    
        }
      case lat: Double => 
        if (controllerStack.nonEmpty) {
          if (controllerStack.head.hasStreamAncestor & streamOuts != "") {
            if (isBit) src"(${name}).DS(${lat.toInt}, rr, ${streamOuts})"
            else src"Utils.getRetimed($name, ${lat.toInt}, ${streamOuts})"
          } else {
            if (isBit) src"(${name}).D(${lat.toInt}, rr)"
            else src"Utils.getRetimed($name, ${lat.toInt})"
          }
        } else {
          if (isBit) src"(${name}).D(${lat.toInt}, rr)"
          else src"Utils.getRetimed($name, ${lat.toInt})"
        }
      case lat: String => 
        if (controllerStack.nonEmpty) {
          if (controllerStack.head.hasStreamAncestor & streamOuts != "") {
            if (isBit) src"(${name}).DS(${latency}.toInt, rr, ${streamOuts})"
            else src"Utils.getRetimed($name, $latency, ${streamOuts})"
          } else {
            if (isBit) src"(${name}).D(${latency}.toInt, rr)"
            else src"Utils.getRetimed($name, $latency)"
          }
        } else {
          if (isBit) src"(${name}).D(${latency}.toInt, rr)"
          else src"Utils.getRetimed($name, $latency)"
        }
    }
  }


  protected def bitWidth(tp: Type[_]): Int = tp match {
    case Bits(bT) => bT.nbits
    case _ => -1
  }
  protected def fracBits(tp: Type[_]): Int = tp match {
    case FixPtType(s,d,f) => f
    case _ => 0
  }

  final protected def appendChainPass(s: Sym[_], rawname: String): (String, Boolean) = {
    var result = rawname
    var modified = false
    if (pipeChainPassMap.contains(s)) {
      val siblings = pipeChainPassMap(s)
      var nextLevel: Option[Sym[_]] = Some(controllerStack.head)
      while (nextLevel.isDefined) {
        if (siblings.contains(nextLevel.get)) {
          if (siblings.indexOf(nextLevel.get) > 0) {result = result + s"_chain_read_${siblings.indexOf(nextLevel.get)}"}
          modified = true
          nextLevel = None
        }
        else if (pipeChainPassMapBug41Hack.contains(s) && pipeChainPassMapBug41Hack(s) == nextLevel.get) {
          result = result + s"_chain_read_${siblings.length-1}"
          modified = true
          nextLevel = None
        } else {
          nextLevel = nextLevel.get.parent.s
        }
      }
    }
    (result, modified)
  }

  final protected def getCtrSuffix(ctrl: Sym[_]): String = {
    if (ctrl.parent != Ctrl.Host) {
      if (ctrl.parent.isStreamControl) {src"_copy${ctrl}"} else {getCtrSuffix(ctrl.parent.s.get)}
    } else {
      throw new Exception(s"Could not find LCA stream schedule for a bound sym that is definitely in a stream controller.  This error should be impossibru!")
    }
  }

  final protected def appendStreamSuffix(s: Sym[_], rawname: String): (String, Boolean) = {
    if (streamCopyWatchlist.contains(s)) (rawname + getCtrSuffix(controllerStack.head), true)
    else                           (rawname,                                      false)
  }

  override protected def quote(s: Sym[_]): String = s.rhs match {
    case Def.Bound(id)  => 
      val base = super.quote(s)
      val (pipelineRemap, pmod) = appendChainPass(s, base)
      val (streamRemap, smod) = appendStreamSuffix(s, base)
      if (pmod & smod) throw new Exception(s"ERROR: Seemingly impossible bound sym that is both part of a pipeline and a stream pipe!")
      if (smod) streamRemap
      else if (pmod) pipelineRemap
      else base
    case Def.Node(_,_) => // Specifically places suffix on ctrchains
      val base = super.quote(s)
      val (streamRemap, smod) = appendStreamSuffix(s, base)
      if (smod) streamRemap
      else DLTrace(s).getOrElse(base)
    case _ => super.quote(s)
  }


  def swap(tup: (Sym[_], RemapSignal)): String = swap(tup._1, tup._2)
  def swap(lhs: Sym[_], s: RemapSignal): String = swap(src"$lhs", s)
  def swap(lhs: Ctrl, s: RemapSignal): String = swap(src"${lhs.s.get}", s)

  def swap(lhs: => String, s: RemapSignal): String = s match {
    case En           => wireMap(src"${lhs}_en")
    case Done         => wireMap(src"${lhs}_done")
    case BaseEn       => wireMap(src"${lhs}_base_en")
    case Mask         => wireMap(src"${lhs}_mask")
    case Resetter     => wireMap(src"${lhs}_resetter")
    case DatapathEn   => wireMap(src"${lhs}_datapath_en")
    case CtrTrivial   => wireMap(src"${lhs}_ctr_trivial")
    case IIDone       => wireMap(src"${lhs}_II_done")
    case RstEn        => wireMap(src"${lhs}_rst_en")
    case CtrEn        => wireMap(src"${lhs}_ctr_en")
    case Ready        => wireMap(src"${lhs}_ready")
    case Valid        => wireMap(src"${lhs}_valid")
    case NowValid     => wireMap(src"${lhs}_now_valid")
    case Inhibitor    => wireMap(src"${lhs}_inhibitor")
    case Wren         => wireMap(src"${lhs}_wren")
    case Chain        => wireMap(src"${lhs}_chain")
    case Blank        => wireMap(src"$lhs")
    case DataOptions  => wireMap(src"${lhs}_data_options")
    case ValidOptions => wireMap(src"${lhs}_valid_options")
    case ReadyOptions => wireMap(src"${lhs}_ready_options")
    case EnOptions    => wireMap(src"${lhs}_en_options")
    case RVec         => wireMap(src"${lhs}_rVec")
    case WVec         => wireMap(src"${lhs}_wVec")
    case Latency      => wireMap(src"${lhs}_latency")
    case II           => wireMap(src"${lhs}_ii")
    case SM           => wireMap(src"${lhs}_sm")
    case Inhibit      => wireMap(src"${lhs}_inhibit")
  }

}




