package templates

import chisel3._
import chisel3.util._
import chisel3.internal.sourceinfo._
import types._
import fringe._
// import emul._
import scala.collection.immutable.ListMap
import scala.math._

sealed trait DeviceTarget
object Default extends DeviceTarget
object Zynq extends DeviceTarget
object ZCU extends DeviceTarget
object DE1 extends DeviceTarget // Do not use this one
object de1soc extends DeviceTarget
object AWS_F1 extends DeviceTarget

object ops {



  implicit class ArrayOps[T](val b:Array[types.FixedPoint]) {
    def raw = chisel3.util.Cat(b.map{_.raw})
    def FP(s: Boolean, d: Int, f: Int): FixedPoint = {
      chisel3.util.Cat(b.map{_.raw}).FP(s, d, f)
    }
  }
  implicit class SeqIntOps[T](val b:Seq[Int]) {
    def getOr1(idx: Int): Int = if (b.size > idx) b(idx) else 1
  }
  implicit class SeqBoolOps[T](val b:Seq[Bool]) {
    def or = if (b.size == 0) false.B else b.reduce{_||_}
    def and = if (b.size == 0) true.B else b.reduce{_&&_}
  }
  implicit class ArrayBoolOps[T](val b:Array[Bool]) {
    def or = if (b.size == 0) false.B else b.reduce{_||_}
    def and = if (b.size == 0) true.B else b.reduce{_&&_}
    def raw = chisel3.util.Cat(b.map{_.raw})
    def FP(s: Boolean, d: Int, f: Int): FixedPoint = {
      chisel3.util.Cat(b.map{_.raw}).FP(s, d, f)
    }
  }

  implicit class IndexedSeqOps[T](val b:scala.collection.immutable.IndexedSeq[types.FixedPoint]) {
    def raw = chisel3.util.Cat(b.map{_.raw})
    def FP(s: Boolean, d: Int, f: Int): FixedPoint = {
      chisel3.util.Cat(b.map{_.raw}).FP(s, d, f)
    }
  }

  implicit class VecOps[T](val b:chisel3.core.Vec[types.FixedPoint]) {
    def raw = chisel3.util.Cat(b.map{_.raw})
    def FP(s: Boolean, d: Int, f: Int): FixedPoint = {
      chisel3.util.Cat(b.map{_.raw}).FP(s, d, f)
    }

  }

  implicit class BoolOps(val b:Bool) {
    def toSeq: Seq[Bool] = Seq(b)

    def D(delay: Int, retime_released: Bool): Bool = {
//      Mux(retime_released, chisel3.util.ShiftRegister(b, delay, false.B, true.B), false.B)
      Mux(retime_released, Utils.getRetimed(b, delay), false.B)
    }
    def D(delay: Double, retime_released: Bool): Bool = {
      b.D(delay.toInt, retime_released)
    }
    def D(delay: Double): Bool = {
      b.D(delay.toInt, true.B)
    }
    def reverse: Bool = {
      b
    }
    
    // Stream version
    def DS(delay: Int, retime_released: Bool, flow: Bool): Bool = {
//      Mux(retime_released, chisel3.util.ShiftRegister(b, delay, false.B, true.B), false.B)
      Mux(retime_released, Utils.getRetimed(b, delay, flow), false.B)
    }
    def DS(delay: Double, retime_released: Bool, flow: Bool): Bool = {
      b.DS(delay.toInt, retime_released, flow)
    }
    def DS(delay: Double, flow: Bool): Bool = {
      b.DS(delay.toInt, true.B, flow)
    }
    

  }
  
  // implicit class DspRealOps(val b:DspReal) {
  //   def raw = //     b.node/   }
  //   def number = {
  //     b.node
  //   }
  //   def r = //     b.node
  // }

  implicit class UIntOps(val b:UInt) {
    def toSeq: Seq[UInt] = Seq(b)
    // Define number so that we can be compatible with FixedPoint type
    def number = {
      b
    }
    def raw = b
    def rd = b
    def r = b
    def msb = {
      b(b.getWidth-1)
    }

    // override def connect (rawop: Data)(implicit sourceInfo: SourceInfo, connectionCompileOptions: chisel3.core.CompileOptions): Unit = {
    //   rawop match {
    //     case op: FixedPoint =>
    //       b := op.number
    //     case op: UInt =>
    //       b := op
    //   }
    // }

    def < (c: FixedPoint): Bool = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) < c
    }

    def ^ (c: FixedPoint): FixedPoint = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) ^ c
    }

    def <= (c: FixedPoint): Bool = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) <= c
    }

    def > (c: FixedPoint): Bool = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) > c
    }

    def >= (c: FixedPoint): Bool = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) >= c
    }

    def === (c: FixedPoint): Bool = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) === c      
    }

    def =/= (c: FixedPoint): Bool = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) =/= c      
    }

    def - (c: FixedPoint): FixedPoint = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) - c      
    }

    def <-> (c: FixedPoint): FixedPoint = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) <-> c
    }

    def + (c: FixedPoint): FixedPoint = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) + c      
    }

    def <+> (c: FixedPoint): FixedPoint = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) <+> c      
    }

    def *-* (c: FixedPoint): FixedPoint = {this.*-*(c, None, true.B)}
    def *-* (c: SInt): SInt = {this.*-*(c, None, true.B)}
    def *-* (c: UInt): UInt = {this.*-*(c, None, true.B)}

    def *-* (c: FixedPoint, delay: Option[Double], flow: Bool): FixedPoint = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b).*-*(c, delay, flow)
    }

    def *-* (c: UInt, delay: Option[Double], flow: Bool): UInt = { // TODO: Find better way to capture UInt / UInt, since implicit resolves won't make it this far
      if (Utils.retime) {
        if (delay.isDefined) FringeGlobals.bigIP.multiply(b, c, delay.get.toInt, flow)
        else FringeGlobals.bigIP.multiply(b, c, (Utils.fixmul_latency * b.getWidth).toInt, flow)
      } else if (Utils.regression_testing == "1") {
        Utils.getRetimed(b*c, delay.getOrElse(0.0).toInt)
      } else {
        Utils.target match {
          case AWS_F1 => b*c // Raghu's box
          case Zynq => b*c // Raghu's box
          case ZCU => b*c // Raghu's box
          case DE1 => b*c // Raghu's box
          case `de1soc` => b*c // Raghu's box
          case Default => b*c
        }
      }
    }

    def *-* (c: SInt, delay: Option[Double], flow: Bool): SInt = { // TODO: Find better way to capture UInt / UInt, since implicit resolves won't make it this far
      if (Utils.retime) {
        if (delay.isDefined) FringeGlobals.bigIP.multiply(b.asSInt, c, delay.get.toInt, flow)
        else FringeGlobals.bigIP.multiply(b.asSInt, c, (Utils.fixmul_latency * b.getWidth).toInt, flow)
      } else if (Utils.regression_testing == "1") {
        Utils.getRetimed(b.asSInt*c, delay.getOrElse(0.0).toInt)
      } else {
        Utils.target match {
          case AWS_F1 => b.asSInt*c // Raghu's box
          case Zynq => b.asSInt*c // Raghu's box
          case ZCU => b.asSInt*c // Raghu's box
          case DE1 => b.asSInt*c // Raghu's box
          case `de1soc` => b.asSInt*c // Raghu's box
          case Default => b.asSInt*c
        }
      }
    }

    def /-/ (c: FixedPoint, delay: Option[Double], flow: Bool): FixedPoint = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b)./-/(c, delay, flow)
    }

    def /-/ (c: UInt, delay: Option[Double], flow: Bool): UInt = { // TODO: Find better way to capture UInt / UInt, since implicit resolves won't make it this far
      if (Utils.retime) {
        if (delay.isDefined) FringeGlobals.bigIP.divide(b, c, delay.get.toInt, flow) 
        else FringeGlobals.bigIP.divide(b, c, (Utils.fixdiv_latency * b.getWidth).toInt, flow) 
      } else if (Utils.regression_testing == "1") {
        Utils.getRetimed(b/c, delay.getOrElse(0.0).toInt)
      } else {
       Utils.target match {
         case AWS_F1 => b/c // Raghu's box
         case Zynq => FringeGlobals.bigIP.divide(b, c, (Utils.fixdiv_latency * b.getWidth).toInt, flow) 
        case ZCU => FringeGlobals.bigIP.divide(b, c, (Utils.fixdiv_latency * b.getWidth).toInt, flow) 
         case DE1 => b/c // Raghu's box
        case `de1soc` => b/c // Raghu's box
         case Default => b/c
       }
     }
    }

    def /-/ (c: SInt, delay: Option[Double], flow: Bool): SInt = { // TODO: Find better way to capture UInt / UInt, since implicit resolves won't make it this far
      if (Utils.retime) {
        if (delay.isDefined) FringeGlobals.bigIP.divide(b.asSInt, c, delay.get.toInt, flow) 
        else FringeGlobals.bigIP.divide(b.asSInt, c, (Utils.fixdiv_latency * b.getWidth).toInt, flow) 
      } else if (Utils.regression_testing == "1") {
        Utils.getRetimed(b.asSInt/c, delay.getOrElse(0.0).toInt)
      } else {
       Utils.target match {
         case AWS_F1 => b.asSInt/c // Raghu's box
         case Zynq => b.asSInt/c // Raghu's box
        case ZCU => b.asSInt/c // Raghu's box
         case DE1 => b.asSInt/c // Raghu's box
         case `de1soc` => b.asSInt/c // Raghu's box
         case Default => b.asSInt/c
       }
     }
    }

    def % (c: FixedPoint): FixedPoint = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) %-% c      
    }

    def %-% (c: FixedPoint): FixedPoint = {this.%-%(c, None, true.B)}
    def %-% (c: FixedPoint, delay: Option[Double], flow: Bool): FixedPoint = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b).%-%(c,delay, flow)
    }
    def %-% (c: UInt): UInt = {b.%-%(c,None,true.B)} // TODO: Find better way to capture UInt / UInt, since implicit resolves won't make it this far
    def %-% (c: UInt, delay: Option[Double], flow: Bool): UInt = { // TODO: Find better way to capture UInt / UInt, since implicit resolves won't make it this far
      if (Utils.retime) {
        if (delay.isDefined) FringeGlobals.bigIP.mod(b, c, delay.get.toInt, flow)
        else FringeGlobals.bigIP.mod(b, c, (Utils.fixmod_latency * b.getWidth).toInt, flow)
      } else if (Utils.regression_testing == "1") {
        Utils.getRetimed(b%c, delay.getOrElse(0.0).toInt)
      } else {
        Utils.target match {
          case AWS_F1 => b%c // Raghu's box
          case Zynq => b%c // Raghu's box
          case ZCU => b%c // Raghu's box
          case DE1 => b%c // Raghu's box
          case `de1soc` => b%c // Raghu's box
          case Default => b%c
        }
      }
    }

    def %-% (c: SInt): SInt = {b.%-%(c, None, true.B)}
    def %-% (c: SInt, delay: Option[Double], flow: Bool): SInt = { // TODO: Find better way to capture UInt / UInt, since implicit resolves won't make it this far
      if (Utils.retime) {
        if (delay.isDefined) FringeGlobals.bigIP.mod(b.asSInt, c, delay.get.toInt, flow)
        else FringeGlobals.bigIP.mod(b.asSInt, c, (Utils.fixmod_latency * b.getWidth).toInt, flow)
      } else if (Utils.regression_testing == "1") {
        Utils.getRetimed(b.asSInt%c, delay.getOrElse(0.0).toInt)
      } else {
        Utils.target match {
          case AWS_F1 => b.asSInt%c // Raghu's box
          case Zynq => b.asSInt%c // Raghu's box
          case ZCU => b.asSInt%c // Raghu's box
          case DE1 => b.asSInt%c // Raghu's box
          case `de1soc` => b.asSInt%c // Raghu's box
          case Default => b.asSInt%c
        }
      }
    }

    def FP(s: Boolean, d: Int, f: Int): FixedPoint = {
      Utils.FixedPoint(s, d, f, b)
    }

    def cast(c: FixedPoint, sign_extend: scala.Boolean = false): Unit = {
      c.r := Utils.FixedPoint(c.s,c.d,c.f,b, sign_extend).r
    }
    def cast(c: => UInt): Unit = {
      c.r := b.r
    }

  }

  implicit class SIntOps(val b:SInt) {
    def toSeq: Seq[SInt] = Seq(b)
    // Define number so that we can be compatible with FixedPoint type
    def number = {
      b.asUInt
    }
    def raw = b.asUInt
    def rd = b.asUInt
    def r = b.asUInt
    def msb = {
      b(b.getWidth-1)
    }

    // override def connect (rawop: Data)(implicit sourceInfo: SourceInfo, connectionCompileOptions: chisel3.core.CompileOptions): Unit = {
    //   rawop match {
    //     case op: FixedPoint =>
    //       b := op.number
    //     case op: UInt =>
    //       b := op
    //   }
    // }

    def < (c: FixedPoint): Bool = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) < c
    }

    def ^ (c: FixedPoint): FixedPoint = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) ^ c
    }

    def <= (c: FixedPoint): Bool = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) <= c
    }

    def > (c: FixedPoint): Bool = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) > c
    }

    def >= (c: FixedPoint): Bool = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) >= c
    }

    def === (c: FixedPoint): Bool = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) === c      
    }

    def =/= (c: FixedPoint): Bool = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) =/= c      
    }

    def - (c: FixedPoint): FixedPoint = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) - c      
    }

    def <-> (c: FixedPoint): FixedPoint = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) <-> c
    }

    def + (c: FixedPoint): FixedPoint = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) + c      
    }

    def <+> (c: FixedPoint): FixedPoint = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b) <+> c      
    }

    def *-* (c: FixedPoint): FixedPoint = {this.*-*(c, None,true.B)}
    def *-* (c: SInt): SInt = {this.*-*(c, None,true.B)}
    def *-* (c: UInt): SInt = {this.*-*(c, None,true.B)}

    def *-* (c: FixedPoint, delay: Option[Double], flow: Bool): FixedPoint = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b).*-*(c,delay,flow)      
    }

    def *-* (c: UInt, delay: Option[Double], flow: Bool): SInt = { // TODO: Find better way to capture UInt / UInt, since implicit resolves won't make it this far
      if (Utils.retime) {
        if (delay.isDefined) FringeGlobals.bigIP.multiply(b, c.asSInt, delay.get.toInt, flow)
        else FringeGlobals.bigIP.multiply(b, c.asSInt, (Utils.fixmul_latency * b.getWidth).toInt, flow)
      } else if (Utils.regression_testing == "1") {
        Utils.getRetimed(b*c.asSInt, delay.getOrElse(0.0).toInt)
      } else {
        Utils.target match {
          case AWS_F1 => b*c.asSInt // Raghu's box
          case Zynq => b*c.asSInt // Raghu's box
          case ZCU => b*c.asSInt // Raghu's box
          case DE1 => b*c.asSInt // Raghu's box
          case `de1soc` => b*c.asSInt // Raghu's box
          case Default => b*c.asSInt
        }
      }
    }

    def *-* (c: SInt, delay: Option[Double], flow: Bool): SInt = { // TODO: Find better way to capture UInt / UInt, since implicit resolves won't make it this far
      if (Utils.retime) {
        if (delay.isDefined) FringeGlobals.bigIP.multiply(b, c, delay.get.toInt, flow)
        else FringeGlobals.bigIP.multiply(b, c, (Utils.fixmul_latency * b.getWidth).toInt, flow)
      } else if (Utils.regression_testing == "1") {
        Utils.getRetimed(b*c, delay.getOrElse(0.0).toInt)
      } else {
        Utils.target match {
          case AWS_F1 => b*c // Raghu's box
          case Zynq => b*c // Raghu's box
          case ZCU => b*c // Raghu's box
          case DE1 => b*c // Raghu's box
          case `de1soc` => b*c // Raghu's box
          case Default => b*c
        }
      }
    }

    def /-/ (c: FixedPoint): FixedPoint = {this./-/(c,None, true.B)}

    def /-/ (c: FixedPoint, delay: Option[Double], flow: Bool): FixedPoint = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b)./-/(c, delay, flow)
    }

    def /-/ (c: UInt, delay: Option[Double], flow: Bool): SInt = { // TODO: Find better way to capture UInt / UInt, since implicit resolves won't make it this far
      if (Utils.retime) {
        if (delay.isDefined) FringeGlobals.bigIP.divide(b, c.asSInt, delay.get.toInt, flow)
        else FringeGlobals.bigIP.divide(b, c.asSInt, (Utils.fixdiv_latency * b.getWidth).toInt, flow) // Raghu's box. Divide latency set to 16.
      } else if (Utils.regression_testing == "1") {
        Utils.getRetimed(b/c.asSInt, delay.getOrElse(0.0).toInt)
      } else {
       Utils.target match {
         case AWS_F1 => b/c.asSInt // Raghu's box
         case Zynq => b/c.asSInt // Raghu's box
        case ZCU => b/c.asSInt // Raghu's box
         case DE1 => b/c.asSInt // Raghu's box
         case `de1soc` => b/c.asSInt // Raghu's box
         case Default => b/c.asSInt
       }
     }
    }

    def /-/ (c: SInt, delay: Option[Double], flow: Bool): SInt = { // TODO: Find better way to capture UInt / UInt, since implicit resolves won't make it this far
      if (Utils.retime) {
        if (delay.isDefined) FringeGlobals.bigIP.divide(b, c, delay.get.toInt, flow) // Raghu's box. Divide latency set to 16.
        else FringeGlobals.bigIP.divide(b, c, (Utils.fixdiv_latency * b.getWidth).toInt, flow) // Raghu's box. Divide latency set to 16.
      } else if (Utils.regression_testing == "1") {
        Utils.getRetimed(b/c, delay.getOrElse(0.0).toInt)
      } else {
       Utils.target match {
         case AWS_F1 => b/c // Raghu's box
         case Zynq => b/c // Raghu's box
        case ZCU => b/c // Raghu's box
         case DE1 => b/c // Raghu's box
         case `de1soc` => b/c // Raghu's box
         case Default => b/c
       }
     }
    }

    def %-% (c: FixedPoint): FixedPoint = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b).%-%(c,None,true.B)
    }

    def %-% (c: FixedPoint, delay: Option[Double], flow: Bool): FixedPoint = {
      Utils.FixedPoint(c.s, b.getWidth max c.d, c.f, b).%-%(c,delay, flow)
    }

    def %-% (c: UInt, delay: Option[Double], flow: Bool): SInt = { // TODO: Find better way to capture UInt / UInt, since implicit resolves won't make it this far
      if (Utils.retime) {
        if (delay.isDefined) FringeGlobals.bigIP.mod(b, c.asSInt, delay.get.toInt, flow)
        else FringeGlobals.bigIP.mod(b, c.asSInt, (Utils.fixmod_latency * b.getWidth).toInt, flow)
      } else if (Utils.regression_testing == "1") {
        Utils.getRetimed(b%c.asSInt, delay.getOrElse(0.0).toInt)
      } else {
        Utils.target match {
          case AWS_F1 => b%c.asSInt // Raghu's box
          case Zynq => b%c.asSInt // Raghu's box
          case ZCU => b%c.asSInt // Raghu's box
          case DE1 => b%c.asSInt // Raghu's box
          case `de1soc` => b%c.asSInt // Raghu's box
          case Default => b%c.asSInt
        }
      }
    }

    def %-% (c: SInt, delay: Option[Double], flow: Bool): SInt = { // TODO: Find better way to capture UInt / UInt, since implicit resolves won't make it this far
      if (Utils.retime) {
        if (delay.isDefined) FringeGlobals.bigIP.mod(b, c, delay.get.toInt, flow)
        else FringeGlobals.bigIP.mod(b, c, (Utils.fixmod_latency * b.getWidth).toInt, flow)
      } else if (Utils.regression_testing == "1") {
        Utils.getRetimed(b%c, delay.getOrElse(0.0).toInt)
      } else {
        Utils.target match {
          case AWS_F1 => b%c // Raghu's box
          case Zynq => b%c // Raghu's box
          case ZCU => b%c // Raghu's box
          case DE1 => b%c // Raghu's box
          case `de1soc` => b%c // Raghu's box
          case Default => b%c
        }
      }
    }

    def FP(s: Boolean, d: Int, f: Int): FixedPoint = {
      Utils.FixedPoint(s, d, f, b)
    }
    def FlP(m: Int, e: Int): FloatingPoint = {
      Utils.FloatPoint(m, e, b)
    }

    def cast(c: FixedPoint, sign_extend: scala.Boolean = false): Unit = {
      c.r := Utils.FixedPoint(c.s,c.d,c.f,b, sign_extend).r
    }


  }


  implicit class IntOps(val b: Int) {
    def FP(s: Boolean, d: Int, f: Int): FixedPoint = {
      Utils.FixedPoint(s, d, f, b)
    }
    def FP(s: Int, d: Int, f: Int): FixedPoint = {
      Utils.FixedPoint(s, d, f, b, true)
    }
    def FlP(m: Int, e: Int): FloatingPoint = {
      Utils.FloatPoint(m, e, b)
    }
    def *-*(x: Int): Int = {b*x}
    def /-/(x: Int): Int = {b/x}
    def %-%(x: Int): Int = {b%x}
    def *-*(x: Double): Double = {b*x}
    def /-/(x: Double): Double = {b/x}
    def %-%(x: Double): Double = {b%x}
    def *-*(x: Long): Long = {b*x}
    def /-/(x: Long): Long = {b/x}
    def %-%(x: Long): Long = {b%x}
    def indices[T](func: Int => T): Seq[T] = {
      (0 until b).map{ i => func(i) }.toSeq
    }
  }

  implicit class DoubleOps(val b: Double) {
    def FP(s: Boolean, d: Int, f: Int): FixedPoint = {
      Utils.FixedPoint(s, d, f, b)
    }
    def FP(s: Int, d: Int, f: Int): FixedPoint = {
      Utils.FixedPoint(s, d, f, b, true)
    }
    def FlP(m: Int, e: Int): FloatingPoint = {
      Utils.FloatPoint(m, e, b)
    }
    def *-*(x: Double): Double = {b*x}
    def /-/(x: Double): Double = {b/x}
    def %-%(x: Double): Double = {b%x}
    def *-*(x: Int): Double = {b*x}
    def /-/(x: Int): Double = {b/x}
    def %-%(x: Int): Double = {b%x}
    def *-*(x: Long): Double = {b*x}
    def /-/(x: Long): Double = {b/x}
    def %-%(x: Long): Double = {b%x}
  }
}

object Utils {


/** Info that comes from the compiler:
  *
  *            |--------------|--------------|
  *            |   Buffer 0   |   Buffer 1   |
  *            |--------------|--------------|
  * bufferPort         0              1           The buffer port (None for access outside pipeline)
  *                 |x x x|        |x x x|
  *
  *                /       \      /       \
  *
  *              |x x x|x x|     |x x x|x x x|
  * muxPort         0    1          0     1       The ID for the given time multiplexed vector
  *
  *              |( ) O|O O|    |(   )|( ) O|
  * muxOfs        0   2 0 1        0    0  2      Start offset into the time multiplexed vector
  *
  */

  /* List of bank addresses, for direct accesses */
  import scala.collection.immutable.ListMap
  type Banks = List[Int]
  def Banks(xs: Int*) = List(xs:_*)
  /* Map from (muxPort, muxOfs, castgroup) to (width of muxPort, isShift) */
  type XMap = ListMap[(Int, Int, Int), (Int, Option[Int])]
  implicit class XMapOps(x: XMap) {
    def muxAddrs: Seq[(Int,Int,Int)] = x.keys.toSeq
    def accessPars: Seq[Int] = x.sortByMuxPortAndOfs.values.map(_._1).toSeq
    def shiftAxis: Option[Int] = x.values.head._2
    def sortByMuxPortAndOfs: XMap = XMap(x.toSeq.sortBy(r => (r._1._1, r._1._2, r._1._2))) // Order the map by (muxPort, muxOfs)
    def sortByMuxPortAndCombine: XMap = XMap(x.toSeq.groupBy(_._1._1).map{case (muxP, entries) => (muxP, 0, 0) -> (entries.sortBy(x => (x._1._1, x._1._2, x._1._3)).map(_._2._1).sum, entries.head._2._2)}.toSeq.sortBy(r => (r._1._1, r._1._2, r._1._3))) // Combine entries so that every muxOfs = 0, then sort
    def accessParsBelowMuxPort(mport: Int, mofs: Int, castgrp: Int): Seq[Int] = x.sortByMuxPortAndOfs.filter{p => p._1._1 < mport | (p._1._1 == mport & p._1._2 < mofs) | (p._1._1 == mport & p._1._2 == mofs & p._1._3 < castgrp)}.accessPars
    def merge(y: XMap): XMap = {
      if (y.nonEmpty) {
        ListMap( (x ++ ListMap(y.map{case (k,v) => 
                                val base = x.toList.length
                                (({base + k._1}, 0, 0) -> v)
                              }.toArray:_*)).toArray:_*)
      } else x
    }
  }
  def XMap(xs:((Int, Int, Int), (Int, Option[Int]))*) = ListMap[(Int,Int,Int),(Int,Option[Int])](xs.map{x => (x._1 -> x._2)}:_*)
  // Example: val a = XMap((0,0) -> 2, (0,2) -> 3, (1,0) -> 4)
  def XMap(xs: => Seq[((Int,Int,Int), (Int,Option[Int]))]) = ListMap[(Int,Int,Int),(Int,Option[Int])](xs.map{case(k,v) => (k -> v)}:_*)
  /* Map from muxPort to (Banks, isShift) */
  type DMap = ListMap[(Int,Int,Int), (List[Banks],Option[Int])]
  implicit class DMapOps(x: DMap) {
    def muxAddrs: Seq[(Int,Int,Int)] = x.keys.toSeq
    def accessPars: Seq[Int] = x.sortByMuxPortAndOfs.values.map(_._1.length).toSeq
    def shiftAxis: Option[Int] = x.values.head._2
    def sortByMuxPortAndOfs: DMap = DMap(x.toSeq.sortBy(r => (r._1._1, r._1._2, r._1._3)))
    def sortByMuxPortAndCombine: DMap = DMap(x.toSeq.groupBy(_._1._1).map{case (muxP, entries) => (muxP, 0, 0) -> (entries.sortBy(x => (x._1._1, x._1._2, x._1._3)).map(_._2._1).flatten.toList, entries.head._2._2)}.toSeq.sortBy(r => (r._1._1, r._1._2, r._1._3))) // Combine entries so that every muxOfs = 0, then sort
    def accessParsBelowMuxPort(mport: Int, mofs: Int, castgrp: Int): Seq[Int] = x.sortByMuxPortAndOfs.filter{p => p._1._1 < mport | (p._1._1 == mport & p._1._2 < mofs) | (p._1._1 == mport & p._1._2 == mofs & p._1._3 < castgrp)}.accessPars
  }
  def DMap(xs:((Int,Int,Int),(List[Banks], Option[Int]))*) = ListMap[(Int,Int,Int), (List[Banks],Option[Int])](xs.map{x => (x._1 -> x._2)}:_*)
  // Example: val b = DMap((0,0) -> List(Banks(0,0), Banks(0,1)), (0,2) -> List(Banks(0,2),Banks(0,3)), (1,0) -> List(Banks(0,0),Banks(1,0)))
  def DMap(xs: => Seq[((Int,Int,Int), (List[Banks],Option[Int]))]) = ListMap[(Int,Int,Int),(List[Banks],Option[Int])](xs.map{case(k,v) => (k -> v)}:_*)
  type NBufXMap = ListMap[Int, XMap]
  def NBufXMap(xs:(Int, XMap)*) = ListMap[Int,XMap](xs:_*)
  def NBufXMap(xs: => Seq[(Int, XMap)]) = ListMap[Int,XMap](xs:_*)
  implicit class NBufXMapOps(x: NBufXMap) {
    def mergeXMaps: XMap = {
      ListMap(x.sortByBufferPort.map{case (buf,map) => 
        val base = x.filter(_._1 < buf).values.toList.flatten.map(_._1).length
        map.map{case (muxport, par) => (({muxport._1 + base}, muxport._2, muxport._3) -> par)} 
      }.flatten.toArray:_*) 
    }
    def accessPars: Seq[Int] = x.mergeXMaps.accessPars
    def accessParsBelowBufferPort(f: Int): Seq[Int] = x.sortByBufferPort.filter(_._1 < f).mergeXMaps.accessPars
    def sortByBufferPort: NBufXMap = NBufXMap(x.toSeq.sortBy(_._1))
  }
  type NBufDMap = ListMap[Int, DMap]
  def NBufDMap(xs:(Int,DMap)*) = ListMap[Int, DMap](xs:_*)
  def NBufDMap(xs: => Seq[(Int, DMap)]) = ListMap[Int,DMap](xs:_*)
  implicit class NBufDMapOps(x: NBufDMap) {
    def mergeDMaps: DMap = {
      ListMap(x.sortByBufferPort.map{case (buf,map) => 
        val base = x.filter(_._1 < buf).values.toList.flatten.map(_._1).length
        map.map{case (muxport, banks) => (({muxport._1 + base}, muxport._2, muxport._3) -> banks)} 
      }.flatten.toArray:_*)
    }
    def accessPars: Seq[Int] = x.mergeDMaps.accessPars
    def accessParsBelowBufferPort(f: Int): Seq[Int] = x.sortByBufferPort.filter(_._1 < f).mergeDMaps.accessPars
    def sortByBufferPort: NBufDMap = NBufDMap(x.toSeq.sortBy(_._1))
  }

  var regression_testing = scala.util.Properties.envOrElse("RUNNING_REGRESSION", "0")

  // These properties should be set inside IOModule
  var target: DeviceTarget = Default
  var fixmul_latency = 0.03125
  var fixdiv_latency = 0.03125
  var fixadd_latency = 0.1875
  var fixsub_latency = 0.625
  var fixmod_latency = 0.5
  var fixeql_latency = 1
  var sramload_latency = 0
  var sramstore_latency = 0
  var tight_control = false
  var SramThreshold = 0 // Threshold between turning Mem1D into register array vs real memory
  var mux_latency = 1
  var retime = false

  val delay_per_numIter = List(
              fixsub_latency*32 + fixdiv_latency*32 + fixadd_latency*32,
              fixmul_latency*32 + fixdiv_latency*32 + fixadd_latency*32,
              fixsub_latency*32 + fixmod_latency*32 + fixeql_latency + mux_latency + fixadd_latency*32,
              fixmul_latency*32 + fixmod_latency*32 + fixeql_latency + mux_latency + fixadd_latency*32
    ).max

  def singleCycleDivide(num: SInt, den: SInt): SInt = {
    num / den
  }
  def singleCycleModulo(num: SInt, den: SInt): SInt = {
    num % den
  }
  def singleCycleDivide(num: UInt, den: UInt): UInt = {
    num / den
  }
  def singleCycleModulo(num: UInt, den: UInt): UInt = {
    num % den
  }
  def sqrt(num: FloatingPoint): FloatingPoint = {
    val m = num.m
    val e = num.e
    val result = Wire(new FloatingPoint(m, e))
    val fma = Module(new DivSqrtRecFN_small(m,e,0))
    fma.io.a := num.r
    fma.io.inValid := true.B // TODO: What should this be?
    fma.io.sqrtOp := true.B // TODO: What should this be?
    fma.io.roundingMode := 0.U(3.W) // TODO: What should this be?
    fma.io.detectTininess := true.B // TODO: What should this be?
    result.r := fNFromRecFN(m, e, fma.io.out)
    result
  }

  def fadd(num1: FloatingPoint, num2: FloatingPoint, latency: Int): FloatingPoint = {
      val m = num1.m
      val e = num1.e
      val result = Wire(new FloatingPoint(m, e))
      result.r := FringeGlobals.bigIP.fadd(num1.r, num2.r, m, e, latency)
      result
  }

  def fabs(num: FloatingPoint): FloatingPoint = {
    val m = num.m
    val e = num.e
    val result = Wire(new FloatingPoint(m, e))
    result.r := FringeGlobals.bigIP.fabs(num.r, num.m, num.e)
    result
  }

  def fexp(num: FloatingPoint): FloatingPoint = {
    val m = num.m
    val e = num.e
    val result = Wire(new FloatingPoint(m, e))
//    val fma = Module(new DivSqrtRecFN_small(m,e,0))
//    fma.io.a := num.r
//    fma.io.inValid := true.B // TODO: What should this be?
//    fma.io.sqrtOp := true.B // TODO: What should this be?
//    fma.io.roundingMode := 0.U(3.W) // TODO: What should this be?
//    fma.io.detectTininess := true.B // TODO: What should this be?
//    result.r := fNFromRecFN(m, e, fma.io.out)
    result.r := FringeGlobals.bigIP.fexp(num.r, num.m, num.e)
    result
  }

  def tanh(num: FloatingPoint): FloatingPoint = {
    val m = num.m
    val e = num.e

    val one = Utils.FloatPoint(m, e, 1)
    val two = Utils.FloatPoint(m, e, 2)

    val t2 = two *-* num
    val exp2t = fexp(t2)
    val out = (exp2t - one) /-/ (exp2t + one)
    out
  }

  def sigmoid(num: FloatingPoint): FloatingPoint = {
    val m = num.m
    val e = num.e
    val one = Utils.FloatPoint(m, e, 1)

    val result = Wire(new FloatingPoint(m, e))
    result := frec(fexp(-num) + one)
    result
  }

  def flog(num: FloatingPoint): FloatingPoint = {
    val m = num.m
    val e = num.e
    val result = Wire(new FloatingPoint(m, e))
    result.r := FringeGlobals.bigIP.flog(num.r, num.m, num.e)
    result
  }

  def fsqrt(num: FloatingPoint): FloatingPoint = {
    val m = num.m
    val e = num.e
    val result = Wire(new FloatingPoint(m, e))
    result.r := FringeGlobals.bigIP.fsqrt(num.r, num.m, num.e)
    result
  }
  
  def frec(num: FloatingPoint): FloatingPoint = {
    val m = num.m
    val e = num.e
    val result = Wire(new FloatingPoint(m, e))
    result.r := FringeGlobals.bigIP.frec(num.r, num.m, num.e)
    result
  }
  
  def frsqrt(num: FloatingPoint): FloatingPoint = {
    val m = num.m
    val e = num.e
    val result = Wire(new FloatingPoint(m, e))
    result.r := FringeGlobals.bigIP.frsqrt(num.r, num.m, num.e)
    result
  }

  def fltaccum(num: FloatingPoint, en: Bool, last: Bool): FloatingPoint = {
    val m = num.m
    val e = num.e
    val result = Wire(new FloatingPoint(m, e))
    result.r := FringeGlobals.bigIP.fltaccum(num.r, en, last, num.m, num.e)
    result
  }

  def fix2flt(a: UInt, s: Boolean, d: Int, f: Int, m: Int, e: Int): UInt = {
    FringeGlobals.bigIP.fix2flt(a,s,d,f,m,e)
  }
  def fix2fix(a: UInt, s: Boolean, d: Int, f: Int): UInt = {
    FringeGlobals.bigIP.fix2fix(a,s,d,f)
  }
  def flt2fix(a: UInt, mw: Int, e: Int, sign: Boolean, dec: Int, frac: Int): UInt = {
    FringeGlobals.bigIP.flt2fix(a,mw,e,sign,dec,frac)
  }
  def flt2flt(a: UInt, mwa: Int, ea: Int, mw_out: Int, e_out: Int): UInt = {
    FringeGlobals.bigIP.flt2flt(a,mwa,ea,mw_out,e_out)
  }

  // def getDoubleBits(num: Double) = java.lang.Double.doubleToRawIntBits(num)
  def delay[T <: chisel3.core.Data](sig: T, length: Int):T = {
    if (length == 0) {
      sig
    } else {
      val regs = (0 until length).map { i => RegInit(0.U) } // TODO: Make this type T
      sig match {
        case s:Bool => 
          regs(0) := Mux(s, 1.U, 0.U)
          (length-1 until 0 by -1).map { i => 
            regs(i) := regs(i-1)
          }
          (regs(length-1) === 1.U).asInstanceOf[T]
        case s:UInt => 
          regs(0) := s
          (length-1 until 0 by -1).map { i => 
            regs(i) := regs(i-1)
          }
          (regs(length-1)).asInstanceOf[T]
        case s:FixedPoint =>
          regs(0) := s.r
          (length-1 until 0 by -1).map { i => 
            regs(i) := regs(i-1)
          }
          (regs(length-1)).asInstanceOf[T]
      }
    }
  }

  def frand(seed: Int, m: Int, e: Int): FloatingPoint = {
      val size = m+e

      val flt_rng = Module(new PRNG(seed, size))
      val result = Wire(new FloatingPoint(m, e))
      flt_rng.io.en := true.B
      result.r := flt_rng.io.output
      result
  }

  def fixrand(seed: Int, bits: Int, en: Bool): FixedPoint = {
    val prng = Module(new PRNG(seed, bits))
    val result = Wire(new FixedPoint(false, bits, 0))
    prng.io.en := en
    result := prng.io.output
    result
  }

  def risingEdge(sig:Bool): Bool = {
    sig & Utils.delay(~sig,1)
  }

  def streamCatchDone(in_done: Bool, ready: Bool, retime: Int, rr: Bool, reset: Bool): Bool = {
    import ops._
    if (retime.toInt > 0) {
      val done_catch = Module(new SRFF())
      val sr = Module(new RetimeWrapperWithReset(1, retime - 1, 0))
      sr.io.in := done_catch.io.output.data & ready
      sr.io.flow := ready
      done_catch.io.input.asyn_reset := reset
      done_catch.io.input.set := in_done.toBool & ready
      val out = sr.io.out
      val out_overlap = done_catch.io.output.data
      done_catch.io.input.reset := out & out_overlap & ready
      sr.io.rst := out(0) & out_overlap & ready
      out(0) & out_overlap & ready    
    } else {
      in_done & ready
    }
  }

  // Helper for making fixedpt when you know the value at creation time
  def FixedPoint[T](s: Int, d: Int, f: Int, init: T, sign_extend: scala.Boolean): types.FixedPoint = {
    FixedPoint(s > 0, d, f, init, sign_extend)
  }
  def FixedPoint[T](s: Boolean, d: Int, f: Int, init: T, sign_extend: scala.Boolean = true): types.FixedPoint = {
    init match {
      case i: Double => 
        val rawnum = (i * scala.math.pow(2,f)).toLong
        val cst = Wire(new types.FixedPoint(s, d, f, Some(BigInt(rawnum))))
        cst.raw := rawnum.S((d+f+1).W).asUInt()
        cst
      case i: Bool => 
        val lit = if (i.litArg.isDefined) Some(i.litArg.get.num) else None
        val cst = Wire(new types.FixedPoint(s, d, f, lit))
        cst.r := i
        cst
      case i: UInt => 
        val lit = if (i.litArg.isDefined) Some(i.litArg.get.num) else None
        val cst = Wire(new types.FixedPoint(s, d, f, lit))
        val tmp = Wire(new types.FixedPoint(s, i.getWidth, 0))
        tmp.r := i
        tmp.cast(cst, sign_extend = sign_extend)
        cst
      case i: SInt => 
        val lit = if (i.litArg.isDefined) Some(i.litArg.get.num) else None
        val cst = Wire(new types.FixedPoint(s, d, f, lit))
        cst.r := FixedPoint(s,d,f, i.asUInt).r
        cst
      case i: FixedPoint => 
        val cst = Wire(new types.FixedPoint(s, d, f, i.litVal))
        cst.raw := i.raw
        cst
      case i: Int => 
        val cst = Wire(new types.FixedPoint(s, d, f, Some(BigInt(i))))
        cst.raw := (i * scala.math.pow(2,f)).toLong.S((d+f+1).W).asUInt()
        cst
    }
  }

  def getFloatBits(num: Float) = java.lang.Float.floatToRawIntBits(num)

  def FloatPoint[T](m: Int, e: Int, init: T): FloatingPoint = {
    val cst = Wire(new FloatingPoint(m, e))
  //   val fmt = emul.FltFormat(m - 1, e)
  //   init match {
  //     case i: Int    => cst.raw := emul.FloatPoint(i, fmt).rawBitsAsInt.U
  //     case i: Float  => cst.raw := emul.FloatPoint(i, fmt).rawBitsAsInt.S.asUInt
  //     case i: Double => cst.raw := emul.FloatPoint(i, fmt).rawBitsAsInt.S.asUInt
  //     case i: Bool   => cst.r := mux(i, getFloatBits(1f).U, getFloatBits(0f).U)
  //     // case i: UInt => 
  //     // case i: SInt =>
  //   }
    cst
  }

  // def Cat[T1 <: chisel3.core.Data, T2 <: chisel3.core.Data](x1: T1, x2: T2): UInt = {
  //   val raw_x1 = x1 match {
  //     case x:uint => x
  //     case x:FixedPoint => x.raw
  //   }
  //   val raw_x2 = x2 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }

  //   util.Cat(raw_x1,raw_x2)
  // }

  // def Cat[T1 <: chisel3.core.Data, T2 <: chisel3.core.Data, T3 <: chisel3.core.Data](x1: T1, x2: T2, x3: T3): UInt = {
  //   val raw_x1 = x1 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }
  //   val raw_x2 = x2 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }
  //   val raw_x3 = x3 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }

  //   util.Cat(raw_x1,raw_x2,raw_x3)
  // }
  // def Cat[T1 <: chisel3.core.Data, T2 <: chisel3.core.Data, T3 <: chisel3.core.Data, T4 <: chisel3.core.Data](x1: T1, x2: T2, x3: T3, x4: T4): UInt = {
  //   val raw_x1 = x1 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }
  //   val raw_x2 = x2 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }
  //   val raw_x3 = x3 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }
  //   val raw_x4 = x4 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }

  //   util.Cat(raw_x1,raw_x2,raw_x3,raw_x4)
  // }
  // def Cat[T1 <: chisel3.core.Data, T2 <: chisel3.core.Data, T3 <: chisel3.core.Data, T4 <: chisel3.core.Data, T5 <: chisel3.core.Data](x1: T1, x2: T2, x3: T3, x4: T4, x5: T5): UInt = {
  //   val raw_x1 = x1 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }
  //   val raw_x2 = x2 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }
  //   val raw_x3 = x3 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }
  //   val raw_x4 = x4 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }
  //   val raw_x5 = x5 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }

  //   util.Cat(raw_x1,raw_x2,raw_x3,raw_x4,raw_x5)
  // }
  // def Cat[T1 <: chisel3.core.Data, T2 <: chisel3.core.Data, T3 <: chisel3.core.Data, T4 <: chisel3.core.Data, T5 <: chisel3.core.Data, T6 <: chisel3.core.Data](x1: T1, x2: T2, x3: T3, x4: T4, x5: T5, x6: T6): UInt = {
  //   val raw_x1 = x1 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }
  //   val raw_x2 = x2 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }
  //   val raw_x3 = x3 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }
  //   val raw_x4 = x4 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }
  //   val raw_x5 = x5 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }
  //   val raw_x6 = x6 match {
  //     case x:UInt => x
  //     case x:FixedPoint => x.raw
  //   }

  //   util.Cat(raw_x1,raw_x2,raw_x3,raw_x4,raw_x5,raw_x6)
  // }

  def mux[T1 <: chisel3.core.Data, T2 <: chisel3.core.Data](cond: T1, op1: T2, op2: T2): T2 = {
    val bool_cond = cond match {
      case x:Bool => x
      case x:UInt => x(0)
    }
    Mux(bool_cond, op1, op2)
  }


  def floor(a: UInt): UInt = { a }
  def ceil(a: UInt): UInt = { a }
  def floor(a: FixedPoint): FixedPoint = { a.floor() }
  def ceil(a: FixedPoint): FixedPoint = { a.ceil() }

  def min[T <: chisel3.core.Data](a: T, b: T): T = {
    (a,b) match {
      case (aa:UInt,bb:UInt) => Mux(aa < bb, a, b)
      case (_,_) => a // TODO: implement for other types
    }
  }

  def max[T <: chisel3.core.Data](a: T, b: T): T = {
    (a,b) match {
      case (aa:UInt,bb:UInt) => Mux(aa > bb, a, b)
      case (_,_) => a // TODO: implement for other types
    }
  }

  def log2Up[T](raw:T): Int = {
    raw match {
      case n: Int => if (n < 0) {1 max log2Ceil(1 max {1+scala.math.abs(n)})} else {1 max log2Ceil(1 max n)}
      case n: scala.math.BigInt => if (n < 0) {1 max log2Ceil(1.asInstanceOf[scala.math.BigInt] max {1.asInstanceOf[scala.math.BigInt]+n.abs})} 
                                   else {1 max log2Ceil(1.asInstanceOf[scala.math.BigInt] max n)}
      case n: Double => log2Up(n.toInt)
    }
  }

  def getFF[T<: chisel3.core.Data](sig: T, en: UInt) = {
    val ff = Module(new fringe.FringeFF(sig))
    ff.io.init := 0.U(sig.getWidth.W).asTypeOf(sig)
    ff.io.in := sig
    ff.io.enable := en
    ff.io.out
  }

  def getRetimed[T<:chisel3.core.Data](sig: T, delay: Int, en: Bool = true.B, init: Long = 0): T = {
    if (delay == 0) {
      sig
    }
    else {
      if (regression_testing == "1") { // Major hack until someone helps me include the sv file in Driver (https://groups.google.com/forum/#!topic/chisel-users/_wawG_guQgE)
        chisel3.util.ShiftRegister(sig, delay, en)
      } else {
        val sr = Module(new RetimeWrapper(sig.getWidth, delay, init))
        sr.io.in := sig.asUInt
        sr.io.flow := en
        sr.io.out.asTypeOf(sig)
      }
    }
  }

  def FixFMA(mul1: FixedPoint, mul2: FixedPoint, add: FixedPoint, delay: Int, flow: Bool): FixedPoint = {
    if (delay == 0) {
      mul1 *-* mul2 + add
    }
    else {
      // TODO: Use IP 
      mul1.*-*(mul2, Some(delay), flow) + Utils.getRetimed(add, delay, flow)
      // sig.cloneType.fromBits(sr.io.out)
    }
  }

  def vecWidthConvert[T<:chisel3.core.Data](vec: Vec[T], newW: Int) = {
    assert(vec.getWidth % newW == 0)
    val newV = vec.getWidth / newW
    vec.asTypeOf(Vec(newV, Bits(newW.W)))
  }

  class PrintStackTraceException extends Exception
  def printStackTrace = {
    try { throw new PrintStackTraceException }
    catch {
      case ste: PrintStackTraceException => ste.printStackTrace
    }
  }
  // def toFix[T <: chisel3.core.Data](a: T): FixedPoint = {
  //   a match {
  //     case aa: FixedPoint => Mux(aa > bb, a, b)
  //     case a => a // TODO: implement for other types
  //   }
  // }
}

