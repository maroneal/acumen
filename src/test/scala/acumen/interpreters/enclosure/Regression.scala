package acumen
package interpreters
package enclosure

import org.scalacheck.Properties
import scala.util.parsing.input.StreamReader
import acumen.interpreters.enclosure.solver.PicardSolver

object Regression extends Properties("Regression") {

  import TestingContext._
  import util.System._

  val FILE_SUFFIX_RESULT = ".result"

  // FIXME: fail with error message when no files are loaded
  property("consistency") = {
    val models = readFiles("src/test/resources/acumen/interpreters/enclosure/models/", FILE_SUFFIX_MODEL)
    val results = readFiles("src/test/resources/acumen/interpreters/enclosure/expected/", FILE_SUFFIX_RESULT)

    val resultKeySet = results.keySet
    models.forall {
      case (name, model) =>
        try {
          val ast = Parser.run(Parser.prog, model)
          val des = Desugarer.run(ast)
          val enclosures = Interpreter.run(des).res
          results.get(name) match {
            case None =>
              println(name + ".result is missing for comparison with result:\n" + enclosures)
              false
            case Some(result) =>
              val success = enclosures.toString == result
              if (!success) throw (new Error(name))
              success
          }
        } catch {
          case err =>
            val name = err.getMessage 
            println(name + ".acm INCONSISTENT")
            false
        }
    }
  }

}
