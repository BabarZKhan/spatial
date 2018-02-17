package pcc.compiler

import pcc.core._
import pcc.data.FlowRules
import pcc.poly.ISL

import pcc.rewrites.RewriteRules
import pcc.traversal._
import pcc.traversal.analysis._
import pcc.traversal.transform._
import pcc.traversal.codegen.dot._

trait StaticTraversals extends Compiler {
  val rewrites = new RewriteRules {}
  val flows = new FlowRules {}

  val isPIR = false
  val isArchModel = false

  def runPasses[R](block: Block[R]): Unit = {
    implicit val isl: ISL = ISL()
    isl.startup()

    lazy val printer = IRPrinter(state)
    lazy val pipeInserter = PipeInserter(state)
    lazy val accessAnalyzer = AccessAnalyzer(state)
    lazy val memoryAnalyzer = MemoryAnalyzer(state)

    lazy val globalAllocation = GlobalAllocation(state)
    lazy val irDotCodegen = IRDotCodegen(state)
    lazy val puDotCodegen = PUDotCodegen(state)
    lazy val archDotCodegen = ArchDotCodegen(state)

    if (isPIR && !isArchModel) {
      block ==>
        printer ==>
        puDotCodegen ==>
        irDotCodegen
    }
    else if (isArchModel) {
      block ==>
        printer ==>
        irDotCodegen ==>
        archDotCodegen
    }
    else {
      block ==>
        printer ==>
        pipeInserter ==>
        printer ==>
        accessAnalyzer ==>
        printer ==>
        memoryAnalyzer ==>
        globalAllocation ==>
        printer ==>
        puDotCodegen ==>
        irDotCodegen
    }
  }

}
