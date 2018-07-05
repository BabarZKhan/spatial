package spatial

import argon.DSLTest
import forge.SrcCtx
import spatial.lang.{Bit, Text, Void}

trait SpatialTest extends Spatial with DSLTest {
  private lazy val err = "ERROR.*Value '[0-9]+' is out of the range".r
  override def runtimeArgs: Args = NoArgs

  def assert(cond: Bit)(implicit ctx: SrcCtx): Void = spatial.dsl.assert(cond)
  def assert(cond: Bit, msg: Text)(implicit ctx: SrcCtx): Void = spatial.dsl.assert(cond, msg)

  abstract class ChiselBackend(name: String, args: String, make: String, run: String)
    extends Backend(name,args,make,run) {

    override def parseMakeError(line: String): Result = {
      if (line.contains("Placer could not place all instances")) Error(line)
      else if (err.findFirstIn(line).isDefined) Error(line)
      else super.parseMakeError(line)
    }

    override def parseRunError(line: String): Result = {
      if (line.trim.endsWith("failed.")) Error(line)    // VCS assertion failure
      else super.parseRunError(line)
    }
  }

  object Scala extends Backend(
    name = "Scala",
    args = "--sim",
    make = "make",
    run  = "bash run.sh"
  ) {
    def shouldRun: Boolean = checkFlag("test.Scala")
    override def parseRunError(line: String): Result = {
      if (line.trim.startsWith("at")) Error(prev) // Scala exception
      else if (line.trim.contains("Assertion failure")) Error(line) // Assertion failure
      else if (line.trim.contains("error")) Error(line) // Runtime/compiler error
      else super.parseRunError(line)
    }
  }

  object VCS extends ChiselBackend(
    name = "VCS",
    args = "--synth --fpga Zynq",
    make = "make vcs",
    run  = "bash scripts/regression_run.sh vcs"
  ) {
    override def shouldRun: Boolean = checkFlag("test.VCS")
    override val makeTimeout: Long = 3600
  }

  object VCS_noretime extends ChiselBackend(
    name = "VCS_noretime",
    args = "--synth --noretime",
    make = "make vcs",
    run  = "bash scripts/regression_run.sh vcs-noretime"
  ) {
    override def shouldRun: Boolean = checkFlag("test.VCS_noretime")
    override val makeTimeout: Long = 3600
  }

  object Zynq extends ChiselBackend(
    name = "Zynq",
    args = "--synth --fpga Zynq",
    make = "make zynq",
    run  = "bash scripts/scrape.sh Zynq"
  ) {
    override def shouldRun: Boolean = checkFlag("test.Zynq")
    override val makeTimeout: Long = 13000
  }

  object ZCU extends ChiselBackend(
    name = "ZCU",
    args = "--synth --fpga ZCU",
    make = "make zcu",
    run  = "bash scripts/scrape.sh ZCU"
  ) {
    override def shouldRun: Boolean = checkFlag("test.ZCU")
    override val makeTimeout: Long = 13000
  }

  object AWS extends ChiselBackend(
    name = "AWS",
    args = "--synth --fpga AWS_F1",
    make = "make aws-F1-afi",
    run  = "bash scripts/scrape.sh AWS"
  ) {
    override def shouldRun: Boolean = checkFlag("test.AWS")
    override val makeTimeout: Long = 32400
  }

  class RequireErrors(errors: Int) extends IllegalExample("--sim", errors)
  object RequireErrors {
    def apply(n: Int): Seq[Backend] = Seq(new RequireErrors(n))
  }

  override def backends: Seq[Backend] = Seq(Scala, Zynq, ZCU, VCS, AWS, VCS_noretime)

  protected def checkIR(block: argon.Block[_]): Result = Unknown

  final override def postprocess(block: argon.Block[_]): Unit = {
    import argon._
    import argon.node.AssertIf
    super.postprocess(block)

    if (config.test) {
      val stms = block.nestedStms
      val hasAssert = stms.exists{case Op(_: AssertIf) => true; case _ => false }
      if (!hasAssert) throw Indeterminate
      checkIR(block)
    }
  }

}
