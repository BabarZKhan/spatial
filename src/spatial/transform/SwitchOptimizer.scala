package spatial.transform

import argon._
import argon.transform.MutateTransformer
import emul.{FALSE, TRUE}
import spatial.node._
import spatial.lang._
import spatial.traversal.AccelTraversal
import spatial.util.shouldMotionFromConditional

case class SwitchOptimizer(IR: State) extends MutateTransformer with AccelTraversal {

  override def transform[A:Type](lhs: Sym[A], rhs: Op[A])(implicit ctx: SrcCtx): Sym[A] = rhs match {
    case AccelScope(_) => inAccel{ super.transform(lhs,rhs) }

    case SwitchCase(_) =>
      dbgs(s"$lhs = $rhs")
      super.transform(lhs,rhs)

    case Switch(selects,body) =>
      val scs = f(selects).zip(body.stms).filter{case (Const(FALSE),_) => false; case _ => true }
      val sels = scs.map(_._1)
      val stms = scs.map(_._2).map(_.asInstanceOf[A])
      val cases = stms.collect{case Op(op:SwitchCase[_]) => op.asInstanceOf[SwitchCase[A]]  }
      val trueConds = sels.zipWithIndex.collect{case (Const(TRUE),i) => i }

      dbgs(s"Optimizing Switch with selects/cases: ")
      scs.zipWithIndex.foreach{case ((sel,cond),i) =>
        dbgs(s"  #$i [sel]: ${stm(sel)}")
        dbgs(s"  #$i [cas]: ${stm(cond)}")
      }

      if (trueConds.length == 1) {
        val cas = cases.apply(trueConds.head).body
        inlineBlock(cas)
      }
      else if (cases.length == 1) {
        inlineBlock(cases.head.body)
      }
      else {
        if (trueConds.length > 1 || sels.isEmpty) {
          if (trueConds.isEmpty) warn(ctx, "Switch has no enabled cases")
          else  warn(ctx, "Switch has multiple enabled cases")
          warn(ctx)
        }
        val canMotion = cases.forall{c => shouldMotionFromConditional(c.body.stms,inHw) }

        Type[A] match {
          case Bits(b) if canMotion =>
            implicit val bA: Bits[A] = b
            val vals = cases.map{cas => inlineBlock(cas.body).unbox }

            if (sels.length == 2) mux[A](sels.head, vals.head, vals.last)
            else oneHotMux[A](sels, vals)

          case _ =>
            Switch.op_switch[A](sels, stms.map{s => () => { visit(s); f(s) } })
        }
      }


    case _ => super.transform(lhs,rhs)
  }

}
