package spatial.codegen.scalagen

import argon._
import spatial.codegen.naming.NamedCodegen

case class ScalaGenSpatial(IR: State) extends ScalaCodegen
  with ScalaGenArray
  with ScalaGenBit
  with ScalaGenFixPt
  with ScalaGenFltPt
  with ScalaGenIfThenElse
  with ScalaGenStructs
  with ScalaGenText
  with ScalaGenVoid
  with ScalaGenVar
  with ScalaGenDebugging
  with ScalaGenLIFO
  with ScalaGenController
  with ScalaGenCounter
  with ScalaGenDRAM
  with ScalaGenFIFO
  with ScalaGenReg
  with ScalaGenSeries
  with ScalaGenSRAM
  with ScalaGenVec
  with ScalaGenStream
  with ScalaGenRegFile
  with ScalaGenStateMachine
  with ScalaGenFileIO
  with ScalaGenDelays
  with ScalaGenLUTs
  with ScalaGenSwitch
  with NamedCodegen {

  override def copyDependencies(out: String): Unit = {
    dependencies ::= FileDep("scalagen", "Makefile", "../")
    dependencies ::= FileDep("scalagen", "run.sh", "../")
    dependencies ::= FileDep("scalagen", "build.sbt", "../")
    dependencies ::= FileDep("scalagen/project", "build.properties", "../project/")
    super.copyDependencies(out)
  }
}