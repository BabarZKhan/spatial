package argon

import java.io.PrintStream

import utils.process.Subprocess
import utils.{Args, Testbench}

import scala.concurrent.{Await, Future, duration}

trait DSLTest extends Testbench with Compiler with Args { test =>
  //-------------------//
  // Testing Arguments //
  //-------------------//

  def compileArgs: Args = NoArgs
  def runtimeArgs: Args
  lazy val DATA = sys.env("TEST_DATA_HOME")

  //-------------------//
  //      Backends     //
  //-------------------//

  def backends: Seq[Backend]
  def property(str: String): Option[String] = sys.props.get(str)
  def checkFlag(str: String): Boolean = property(str).exists(v => v.trim.toLowerCase == "true")
  lazy val DISABLED: Seq[Backend] = Seq(IGNORE_TEST)

  def commandLine: Boolean = checkFlag("ci")

  /** A backend which can compile and run a given application.
    *
    * @param name The name of the backend
    * @param args The compiler command line arguments to target this backend
    * @param make Command for compiling the generated code in the top-level generated directory
    * @param run  Command for running the generated code in the top-level generated directory
    */
  abstract class Backend(
    val name: String,
    val args: String,
    val make: String,
    val run:  String
  ){ backend =>
    val makeTimeout: Long = 3000 // Timeout for compiling, in seconds
    val runTimeout: Long  = 3000 // Timeout for running, in seconds
    var prev: String = ""

    def shouldRun: Boolean
    override def toString: String = name
    def makeArgs: Seq[String] = make.split(" ").map(_.trim).filter(_.nonEmpty)
    def runArgs: Seq[String] = run.split(" ").map(_.trim).filter(_.nonEmpty)

    def parseMakeError(line: String): Result = {
      if (line.contains("error")) Error(line)
      else Unknown
    }
    def parseRunError(line: String): Result = {
      if (line.contains("PASS: 1") || line.contains("PASS: true")) Pass
      else if (line.contains("PASS: 0") || line.contains("PASS: false")) Fail
      else Unknown
    }

    final def runMake(): Result = {
      command("make", makeArgs, backend.makeTimeout, backend.parseMakeError)
    }
    final def runApp(): Result = {
      var result: Result = Unknown
      runtimeArgs.cmds.foreach { args =>
        result = result orElse command("run", runArgs :+ args, backend.runTimeout, backend.parseRunError)
      }
      result orElse Pass
    }
    final def compile(): Iterator[() => Result] = {
      import scala.concurrent.ExecutionContext.Implicits.global   // implicit execution context for Futures

      val name = test.name.replace("_", "/")
      val stageArgs = test.compileArgs.cmds
      stageArgs.iterator.map{cmd => () => {
        try {
          val backArgs = backend.args.split(" ").map(_.trim).filterNot(_.isEmpty)
          val stageArgs = cmd.split(" ").map(_.trim).filterNot(_.isEmpty)
          val args = backArgs ++ stageArgs ++ Seq("-v", "--test")
          val f = Future{ scala.concurrent.blocking {
            init(args)
            IR.config.genDir = s"${IR.config.cwd}/gen/$backend/$name/"
            IR.config.logDir = s"${IR.config.cwd}/logs/$backend/$name/"
            IR.config.repDir = s"${IR.config.cwd}/reports/$backend/$name/"
            compileProgram(args)
          }}
          Await.result(f, duration.Duration(backend.makeTimeout, "sec"))
          complete(None)
          Unknown
        }
        catch {
          case t: Throwable =>
            val failure = handleException(t)
            complete(failure)
            Error(t)
        }
      }}
    }

    final def command(pass: String, args: Seq[String], timeout: Long, parse: String => Result): Result = {
      import scala.concurrent.ExecutionContext.Implicits.global   // implicit execution context for Futures

      val cmdLog = new PrintStream(IR.config.logDir + s"/$pass.log")
      var cause: Result = Unknown
      Console.out.println(s"Backend $pass in ${IR.config.logDir}/$pass.log")
      Console.out.println(args.mkString(" "))
      val cmd = new Subprocess(args:_*)({case (lline,_) =>
        val line = lline.replaceAll("[<>]","").replaceAll("&gt","").replaceAll("&lt","")
        val err = parse(line)
        cause = cause.orElse(err)
        cmdLog.println(line)
        prev = line
        None
      })

      try {
        val f = Future{ scala.concurrent.blocking{ cmd.block(IR.config.genDir) } }
        val code = Await.result(f, duration.Duration(timeout, "sec"))
        val lines = cmd.stdout()
        val errs  = cmd.errors()
        lines.foreach{ll => val l = ll.replaceAll("[<>]","").replaceAll("&gt","").replaceAll("&lt",""); parse(l); cmdLog.println(l) } // replaceAll to prevent JUnit crash
        errs.foreach{ee => val e = ee.replaceAll("[<>]","").replaceAll("&gt","").replaceAll("&lt",""); parse(e); cmdLog.println(e) } // replaceAll to prevent JUnit crash
        if (code != 0) cause = cause.orElse(Error(s"Non-zero exit code during backend $pass: $code.\n${errs.take(4).mkString("\n")}"))
        if (pass == "make" && code == 0) cause = Unknown // Don't report an error for zero exit codes in make phase
      }
      catch {
        case e: Throwable =>
          cmd.kill()
          cause = cause.orElse(Error(e))
      }
      finally {
        cmdLog.close()
      }
      cause
    }

    def runBackend(): Unit = {
      s"${test.name}" should s"compile, run, and verify for backend $name" in {
        var result: Result = Unknown
        val designs = compile()
        while (designs.hasNext && result.continues) {
          val generate = designs.next()
          result = result.orElse{
            generate() ==>
              runMake() ==>
              runApp()
          }
        }
        result.resolve()
      }
    }
  }

  class IllegalExample(args: String, errors: Int) extends Backend(
    name = "IllegalExample",
    args = args,
    make = "",
    run  = ""
  ) {
    def shouldRun: Boolean = true
    override def runBackend(): Unit = {
      s"${test.name}" should s"have $errors compiler errors" in {
        compile().foreach{err =>
          err()
          IR.hadErrors shouldBe true
          IR.errors shouldBe errors
        }
      }
    }
  }

  object IGNORE_TEST extends Backend(
    name = "Ignore",
    args = "",
    make = "",
    run  = ""
  ) {
    def shouldRun: Boolean = true
    override def runBackend(): Unit = ignore should "compile, run, and verify" in { () }
  }


  private val tests = backends.filter(_.shouldRun)
  if (commandLine) {
    // If running from a non-testing script, run the standard compiler flow
    val args = sys.env.get("TEST_ARGS").map(_.split(" ")).getOrElse(Array.empty)
    System.out.println(s"Running standard compilation flow for test with args: ${args.mkString(" ")}")
    name should "compile" in { compile(args); sys.exit(0) }
  }
  else if (tests.isEmpty) {
    ignore should "...nothing? (No backends enabled. Enable using -D<backend>=true)" in { () }
  }
  else {
    // Otherwise run all the backend tests (if there are any enabled)
    tests.foreach{backend => backend.runBackend() }
  }

}
