package acumen
package interpreters
package reference
package experimental

import java.io.File
import java.io.InputStreamReader
import scala.math._
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FunSuite

import Errors._
import util.Filters._
import util.Names._
import util.Canonical._
import util.Transform
import acumen.testutil.TestUtil.{
  assertEqualTrace
}

class InterpreterTest extends InterpreterTestBase with ShouldMatchers {

  override def suiteName = "Parallel Cont. Reference InterpreterTest"

  def interpreter = interpreters.reference.experimental.Interpreter

  def run(in: InputStreamReader) : Unit = {
    val ast = Parser.run(Parser.prog, in)
    val tr = Transform.transform(ast)
    for (_ <- (interpreter run tr).ctrace) ()
  }

  // This semantics changes the result of nearly every model, 
  // so there is no point in running the regression test.
  //testExamples({f => f == "examples/0_Demos/02_Passive_walking.acm" || 
  //              f.startsWith("examples/A_Ping_Pong/")})
  testShouldRun
  
  def getError(file:String) : Option[AcumenError] = {
    try { run(file) ; None }
    catch { case e:AcumenError => Some(e) }
  }

  test("Error1") {
    val err = ClassNotDefined(cmain)
    getError("data/ShouldCrash/Error1.acm") should be (Some(err))
  }
  test("Error2") {
    val err = VariableNotDeclared(name("y"))
    getError("data/ShouldCrash/Error2.acm") should be (Some(err))
  }
  test("Error3") {
    val err = VariableNotDeclared(name("x"))
    getError("data/ShouldCrash/Error3.acm") should be (Some(err))
  }
  test("Error4") {
    val err = UnknownOperator("f")
    getError("data/ShouldCrash/Error4.acm") should be (Some(err))
  }
  test("Error5") {
    val err = NotAnObject(VLit(GInt(1)))
    getError("data/ShouldCrash/Error5.acm") should be (Some(err))
  }
  test("Error6") {
    val err = NotAnObject(VLit(GInt(1)))
    getError("data/ShouldCrash/Error6.acm") should be (Some(err))
  }
  test("Error7") {
    val err = AccessDenied(CId(), CId(1), Nil)
    getError("data/ShouldCrash/Error7.acm") should be (Some(err))
  }
  ignore("Error8 ") {
    val err = AccessDenied(CId(0,0,1), CId(1), List(CId(1,1),CId(0,1)))
    getError("data/ShouldCrash/Error8.acm") should be (Some(err))
  }
  test("Error9") {
    val err = NotAChildOf(CId(0,0,1), CId(0,1))
    getError("data/ShouldCrash/Error9.acm") should be (Some(err))
  }
  test("Error10 ") {
    val err = ClassNotDefined(ClassName("B"))
    getError("data/ShouldCrash/Error10.acm") should be (Some(err))
  }
  test("Error11 ") {
    val err = ClassDefinedTwice(ClassName("A"))
    getError("data/ShouldCrash/Error11.acm") should be (Some(err))
  }
  test("ACUMEN-348") {
    val err = DuplicateDiscreteAssingment(Name("period",0))
    getError("data/ShouldCrash/ACUMEN-348.acm") should be (Some(err))
  }

  /* tests that match theoretical values against the interpreter's values */
  type VarHistory = Stream[Tuple2[Double,Double]] 

  def oneVar(id:CId, x:String, h: Interpreter.History) : VarHistory = 
    h map (st => 
      (getTime(st), getObjectField(id, Name(x, 0), st)) match {
        case (t, VLit(GDouble(d))) => (t,d)
        case _  => throw VariableNotDeclared(Name(x,0))
      }
    )

  def matches(s:VarHistory, f:Double => Double) : Boolean = 
    if (s.isEmpty) true
    else {
      val (t,x) = s.head
      if (abs(f(t) - x) > 1e-2) false
      else matches(s.tail, f)
    }

  def parse(p:String, st:String) = 
    (Desugarer().run(Parser.run(Parser.prog, p)), 
     Parser.run(Parser.store, st))

  ignore("Gravity1d") {
    val progTxt =
      """
      class Simulator(time, timeStep, endTime, stepType) end
      class Ball(x, x', x'') 
        self.x'' = -9.8
      end
      class Main(mode, simulation) end
      """

    val storeTxt = 
      """
      #0 { className = Main,
           parent = none, 
           nextChild = 0 }

      #0.1 { className = Simulator,
             parent = #0, 
             time = 0.0, 
             timeStep = 0.001, 
             endTime = 2.0, 
             resultType = @Discrete,
             nextChild = 0 }

      #0.2 { className = Ball,
             parent = #0,
             x = 0.0,
             x' = 0.0,
             x'' = 0.0,
             nextChild = 0 }
      """

    val (prog, store) = parse(progTxt, storeTxt)

    val h = Interpreter.loop(prog, store)
    val xs = oneVar(CId(2), "x", onlyAfterContinuous(h))

    def expected(t:Double) = - (9.8 / 2.0) * t * t 
    assert(matches(xs, expected))
  }

  ignore("Gravity2d") {
    val progTxt =
      """
      class Simulator(time, timeStep, endTime, stepType) end
      class Ball(x, x', x'', y,y',y'') 
        self.y'' = -9.8;
        self.x'' = 0
      end
      class Main(mode, simulation) end
      """

    val storeTxt = 
      """
      #0 { className = Main,
           parent = none,
           nextChild = 0 }

      #0.2 { className = Simulator,
             parent = #0, 
             time = 0.0, 
             timeStep = 0.001, 
             endTime = 2.0, 
             resultType = @Discrete,
             nextChild = 0 }

      #0.3 { className = Ball,
             parent = #0,
             x   = 0.0, 
             x'  = 1.0, 
             x'' = 0.0,
             y   = 0.0,
             y'  = 2.0,
             y'' = 0.0,
             nextChild = 0 }
      """

    val (prog, store) = parse(progTxt, storeTxt)

    val h = onlyAfterContinuous(Interpreter.loop(prog, store))
    val xs = oneVar(CId(3), "x", h)
    val ys = oneVar(CId(3), "y", h)

    def expectedy(t:Double) = - (9.8 / 2.0) * t * t + 2.0 * t
    def expectedx(t:Double) = t
    assert(matches(xs, expectedx))
    assert(matches(ys, expectedy))
  }
  
  test("continuous assignments are independent") {
    val head = "class Main(simulator)\n  private x := 0; t := 0; t' := 1 end "
    val cond = "if t < simulator.timeStep x = 1 else x = -1 end;"
    val time = "t' = 1;"
    val tail = "end"
    val timeFirst = Parser.run(Parser.prog, head ++ time ++ cond ++ tail)
    val condFirst = Parser.run(Parser.prog, head ++ cond ++ time ++ tail)
    assertEqualTrace(timeFirst, condFirst, interpreter)
  }
  
}