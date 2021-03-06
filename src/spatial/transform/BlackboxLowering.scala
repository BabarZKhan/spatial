package spatial.transform

import argon._
import argon.node._
import argon.transform.MutateTransformer
import spatial.node._
import spatial.lang._
import spatial.traversal.AccelTraversal
import spatial.metadata.memory._
import spatial.util.spatialConfig

case class BlackboxLowering(IR: State, lowerTransfers: Boolean) extends MutateTransformer with AccelTraversal {

  def lowerSLA[S,I,F](op: FixSLA[S,I,F]): Fix[S,I,F] = {
    implicit val S: BOOL[S] = op.a.fmt.S
    implicit val I: INT[I] = op.a.fmt.I
    implicit val F: INT[F] = op.a.fmt.F
    expandSLA[S,I,F](op.a, op.b)
  }
  def expandSLA[S:BOOL,I:INT,F:INT](a:Fix[S,I,F], b:Fix[TRUE,_16,_0]): Fix[S,I,F] = (a,b) match {
    case (_, Const(y)) => f(a) << f(b)
    case _ if spatialConfig.enablePIR => f(a) << f(b)
    case _ =>
      val x: Reg[Fix[S,I,F]] = Reg[Fix[S,I,F]]
      Foreach(abs(b.to[I32]) by 1){i =>
        x := mux(i === 0, mux(b > 0, a << 1, a >> 1),
          mux(b > 0, x << 1, x >> 1))
      }
      mux(b === 0.to[Fix[TRUE,_16,_0]], a, x.value)
  }

  def lowerSRA[S,I,F](op: FixSRA[S,I,F]): Fix[S,I,F] = {
    implicit val S: BOOL[S] = op.a.fmt.S
    implicit val I: INT[I] = op.a.fmt.I
    implicit val F: INT[F] = op.a.fmt.F
    expandSRA[S,I,F](op.a, op.b)
  }
  def expandSRA[S:BOOL,I:INT,F:INT](a:Fix[S,I,F], b:Fix[TRUE,_16,_0]): Fix[S,I,F] = (a,b) match {
    case (_, Const(y)) => f(a) >> f(b)
    case _ if spatialConfig.enablePIR => f(a) >> f(b)
    case _ =>
      val x: Reg[Fix[S,I,F]] = Reg[Fix[S,I,F]]
      Foreach(abs(b.to[I32]) by 1){i =>
        x := mux(i === 0, mux(b > 0, a >> 1, a << 1),
          mux(b > 0, x >> 1, a << 1))

      }
      mux(b === 0.to[Fix[TRUE,_16,_0]], a, x.value)
  }

  def lowerSRU[S,I,F](op: FixSRU[S,I,F]): Fix[S,I,F] = {
    implicit val S: BOOL[S] = op.a.fmt.S
    implicit val I: INT[I] = op.a.fmt.I
    implicit val F: INT[F] = op.a.fmt.F
    expandSRU[S,I,F](op.a, op.b)
  }
  def expandSRU[S:BOOL,I:INT,F:INT](a:Fix[S,I,F], b:Fix[TRUE,_16,_0]): Fix[S,I,F] = (a,b) match {
    case (_, Const(y)) => f(a) >>> f(b)
    case _ if spatialConfig.enablePIR => f(a) >>> f(b)
    case _ =>
      val x: Reg[Fix[S,I,F]] = Reg[Fix[S,I,F]]
      Foreach(abs(b.to[I32]) by 1){i =>
        x := mux(i === 0, mux(b > 0, a >>> 1, a << 1),
          mux(b > 0, x >>> 1, x << 1))
      }
      mux(b === 0.to[Fix[TRUE,_16,_0]], a, x.value)
  }

  override def transform[A:Type](lhs: Sym[A], rhs: Op[A])(implicit ctx: SrcCtx): Sym[A] = (rhs match {
    case _:AccelScope => inAccel{ super.transform(lhs,rhs) }
    case _:BlackboxImpl[_,_,_] => inBox{ super.transform(lhs,rhs) }
    case op: DenseTransfer[_,_,_] if lowerTransfers => op.lower(lhs)
    case op: SparseTransfer[_,_] if lowerTransfers => op.lower(lhs)
    case op: FrameTransmit[_,_,_] if lowerTransfers => op.lower(lhs)
    case op: FixSLA[_,_,_] if inHw => lowerSLA(op)
    case op: FixSRA[_,_,_] if inHw => lowerSRA(op)
    case op: FixSRU[_,_,_] if inHw => lowerSRU(op)
    case _ => super.transform(lhs, rhs)
  }).asInstanceOf[Sym[A]]

}
