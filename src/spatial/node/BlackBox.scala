package spatial.node

import argon._
import forge.tags._
import argon.node.{Primitive, StructAlloc}
import spatial.lang._

abstract class BlackBox[R:Type] extends Control[R]

/** Black box which must be expanded early in compiler (after initial analyses). */
abstract class EarlyBlackBox[R:Type] extends BlackBox[R] {
  override def cchains = Nil
  override def iters = Nil
  override def bodies = Nil
  @rig def lower(old:Sym[R]): R
}

@op case class GEMMBox[T:Num](
  cchain: CounterChain,
  y:     SRAM2[T],
  a:     SRAM2[T],
  b:     SRAM2[T],
  c:     T,
  alpha: T,
  beta:  T,
  i:     I32,
  j:     I32,
  mt:    I32,
  nt:    I32,
  iters: Seq[I32]
) extends BlackBox[Void] {
  override def cchains = Seq(cchain -> iters)
  override def bodies = Nil
  override def effects: Effects = Effects.Writes(y)
}


//@op case class GEMVBox() extends BlackBox
//@op case class CONVBox() extends BlackBox
//@op case class SHIFTBox(validAfter: Int) extends BlackBox

//@op case class VerilogBlackBox[A:Bits](ins: Seq[A])(implicit val tV: Vec[A]) extends Primitive[Vec[A]] {
@op case class VerilogBlackbox[A:Struct,B:Struct](in: Bits[A]) extends Primitive[B] {
  override def effects = Effects.Unique
}

@op case class VerilogCtrlBlackbox[A:StreamStruct,B:StreamStruct](in: Bits[A]) extends EnControl[B] {
  override def iters: Seq[I32] = Seq()
  var ens = Set()
  override def cchains = Seq()
  override def bodies = Seq()
  override def effects = Effects.Unique andAlso Effects.Mutable
}


