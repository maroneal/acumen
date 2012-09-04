package acumen
package interpreters
package enclosure

import util.Canonical._
import Types._

/**
 * Proxy for the enclosure-based solver.
 */
object Interpreter extends acumen.Interpreter {

  type Store = AffineEnclosure

  //TODO Get this from the Simulator object
  implicit val rnd = Rounding(10)

  val emptyStore: CStore = Map.empty

  //TODO Implement "repr" for enclosure interpreter
  def repr(s: Store): CStore = emptyStore

  //TODO Implement "fromCStore" for enclosure interpreter
  def fromCStore(cs: CStore, root: CId): Store =
    AffineEnclosure(Box.empty, Box.empty)

  //TODO Implement "init" for enclosure interpreter
  def init(prog: Prog): (Prog, Store) =
    (prog, AffineEnclosure(Box.empty, Box.empty))

  //TODO Implement "step" for enclosure interpreter
  def step(p: Prog, st: Store): Option[Store] =
    Some(AffineEnclosure(Box.empty, Box.empty))

  // Simulator object
  def magicClassTxt =
    """
    class Simulator(
    time, timeStep, endTime, stepType, lastCreatedId,
    minTimeStep, maxTimeStep 
    ) end
    """
  def initStoreTxt =
    """#0.0 { className = Simulator, parent = #0, time = 0.0, timeStep = 0.01, 
              endTime = 10.0, stepType = @Discrete, nextChild = 0,
						  seed1 = 0, seed2 = 0 }"""

  lazy val magicClass = Parser.run(Parser.classDef, magicClassTxt)
  lazy val initStore = Parser.run(Parser.store, initStoreTxt)

}











