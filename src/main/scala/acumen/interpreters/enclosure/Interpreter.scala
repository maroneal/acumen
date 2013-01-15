package acumen
package interpreters
package enclosure

import util.Canonical._
import Types._
import ui.interpreter._
import acumen.interpreters.Common.classDef
import acumen.interpreters.enclosure.tree._
import acumen.interpreters.enclosure.tree.HybridSystem
import acumen.interpreters.enclosure.pwl.EncloseHybrid

trait EnclosureInterpreterCallbacks extends InterpreterCallbacks {
  def log(msg: String): Unit
  var endTime: Double = 0.0
  def sendResult(data: Iterable[UnivariateAffineEnclosure]): Unit = {}
}

case class EnclosureRes(res: Seq[UnivariateAffineEnclosure]) extends InterpreterRes {
  def print = println(res)
  def printLast = println(res.last)
}

/**
 * Proxy for the enclosure-based solver.
 */
class Interpreter
  extends acumen.RecursiveInterpreter
  with Checker
  with Extract
  with Solver
  with EncloseHybrid {

  def newInterpreterModel = new EnclosureModel

  val defaultInterpreterCallbacks = new EnclosureInterpreterCallbacks {
    override def log(msg: String) = println(msg)
  }

  val noAdjustParms = (p: Parameters) => p

  def run(des: Prog) = runInterpreter(des, defaultInterpreterCallbacks)

  def runInterpreter(des: Prog, cb0: InterpreterCallbacks) =
    runInterpreter(des, cb0, noAdjustParms)

  var localizing = true

  //FIXME do this properly
  def runInterpreter(des: Prog,
    cb0: InterpreterCallbacks,
    adjustParms: Parameters => Parameters) = {
    val cb = cb0.asInstanceOf[EnclosureInterpreterCallbacks]
    if (des.defs.size > 1) sys.error("Multiple classes are not currently supported by the enclosure interperter!")
    val main = classDef(ClassName("Main"), des)

    // checking that main class embeds a hybrid automaton
    if (!embedsAutomaton(main)) sys.error("Syntax not supported by the enclosure interpreter!")

    val ps0 = parameters(main)
    val ps = adjustParms(ps0)
    implicit val rnd = Rounding(ps.bigDecimalDigits)
    val (hs, uss) = extract(main)

    cb.endTime = ps.endTime

    val res = if (localizing) {

      encloseHybrid(
        ps,
        hs,
        ps.simulationTime,
        emptyState(hs) + (uss.mode -> Some(uss.initialCondition)),
        cb)

    } else {

      solver(
        hs,
        ps.simulationTime,
        Set(uss),
        ps.initialPicardPadding,
        ps.picardImprovements,
        ps.maxPicardIterations,
        ps.splittingDegree,
        ps.maxEventTreeSize,
        ps.minTimeStep,
        ps.maxTimeStep,
        ps.minComputationImprovement,
        cb)
    }

    EnclosureRes(res)
  }

}

object Interpreter extends Interpreter

object InterpreterNonLocalizing extends Interpreter {
  localizing = false
}
