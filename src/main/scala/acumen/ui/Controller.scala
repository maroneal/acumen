package acumen
package ui

import Errors._

import scala.actors._
import scala.collection.JavaConversions._
import scala.swing._
import scala.swing.event._
import util.Canonical._
import util.Conversions._
import java.io.File
import scala.util.control.Breaks._

import interpreter._
import interpreter.{InterpreterCntrl => IC}

import conformance.TestModel

case object SendInit

sealed abstract class AppEvent
case class Progress(percent: Int) extends AppEvent
case class ConsoleMsg(instr: Logger.Instruction) extends AppEvent

// (actor) messages from the UI
sealed abstract class AppActions
case class Init(t:String, cdir: File, i:InterpreterCntrl) extends AppActions
case object Play extends AppActions
case object Stop extends AppActions
case object Pause extends AppActions
case object Step extends AppActions

//Testing
case class CheckConformance(testCases: TestModel, var2Test: String, Tau: Double, Epsilon: Double) extends AppActions

class Controller extends DaemonActor {
  import App._

  //*************************

  /* ---- state ---- */

  private var state : State = Stopped

  var model : InterpreterModel = null
  var threeDData = new threeD.ThreeDData;
  var producer : Actor = null

  /* ------ application logic --------- */

  private def setState(s: State) = {
    //println("State Now: " + s)
    if (state != s) {
      state = s
      //println("Acumen Actor State: " + actor.getState)
      actor ! state
      if (state == Stopped && producer != null) {
        unlink(producer)
        producer = null
      }
    }
  }

  protected[ui] def updateProgress(s: GStore) = {
    val time = getTime(s)
    val endTime = getEndTime(s)
    ui.statusZone.setProgress((time * 100 / endTime).toInt)
  }

  /* ------ actor logic ------------ */

  // The state to be in after receiving a chunk
  var newState : State = Stopped

  def act() {
    Supervisor.watch(this, "Controller", {restart})
    trapExit = true
    setState(Stopped)
    loop {
      react {
        //
        // messages from UI
        //
        case Init(progText, currentDir, interpreter) => 
          //println("INIT")
          App.ui.modelFinished = false
          model = interpreter.newInterpreterModel
          producer = interpreter.init(progText, currentDir, this)
          link(producer)
          setState(Starting)
          Thread.sleep(100) // Hack to give the console time to update
                            // before starting the interpreter -- kevina
                            // note: 100ms may be overkill
          producer.start()
        case Play  =>
          newState = Resuming
          //println("PLAY")
          if (producer == null) {
            //println("No Producer Requesting Init")
            //println("Acumen.actor state: " + actor.getState)
            actor ! SendInit
          } else {
            //println("Producer State: " + producer.getState)
            setState(Resuming)
            Thread.sleep(100) // hack see case Init
            producer ! IC.GoOn
          }
        case Pause => 
          //println("PAUSE")
          if (producer != null)
            producer ! IC.Flush
          newState = Paused
          //println("^^^PAUSE^^^\n")
        case Step  => 
          //println("STEP")
          newState = Paused
          if (producer == null) {
            actor ! SendInit
          } else {
            setState(Resuming)
            Thread.sleep(10) // hack see case Init, using 10 ms to
                             // avoid excessive delay when single
                             // stepping
            producer ! IC.Step
          }
        case Stop  => 
          //println("STOP")
          if (producer == null) {
            //println("No producer.")
            setState(Stopped)
          } else {
            //println("Sending Stop")
            //println("Producer State: " + producer.getState)
            producer ! IC.Stop
            newState = Stopped
          }

        case CheckConformance (tests, var2Test, tau, epsilon)  => 
          println("Check Conformance " + var2Test + " \t " + tau + "\t" + epsilon)
          //FIXME: should it be checked?
          if (producer == null) {
            if (tests != null && tests.getNumOfTests() > 0 && !var2Test.equals("")) {
                var i = 0;
                var varCol = -1
                var timeCol = -1
                while (i < model.getTraceModel.getColumnCount()) {
                  if (model.getTraceModel.getColumnName(i).endsWith("."+var2Test)) {
                      varCol = i
                  }
                  else if (model.getTraceModel.getColumnName(i).endsWith("Simulator).time")) {
                      timeCol = i
                  }
                  i += 1
                }
            
                while (i < model.getTraceModel.getRowCount()) {
                    var obsVal = model.getTraceModel.getValueAt(i, varCol).toString().toDouble;
                    var testValInterval = tests.getTestValueFor(model.getTraceModel.getValueAt(i, timeCol).toString().toDouble, tau).toString()
                    var lohi : Array[String] = testValInterval.split("\\.\\.")
                    var loVal = lohi(0).replaceAll("\\[", "").toDouble
                    var hiVal = lohi(1).replaceAll("\\]", "").toDouble
                    if (obsVal < (loVal - epsilon) || obsVal > (hiVal + epsilon)) {
                        println("------ Non-Conformant Behaviour @ " + model.getTraceModel.getValueAt(i, timeCol) + " - Observed val: " + obsVal + "\t Accepted range" + testValInterval)
                    }
                    i += 1;
                }
              }

          } else {
          }
                    
        //
        // messages from Producer
        //
        case IC.Chunk((t, dead), d) if sender == producer =>
          if (newState == Resuming) producer ! IC.GoOn
          if (d != null) flush(t, dead, d)
          setState(newState)
        case IC.Chunk(_, _) => // ignore chunks from supposedly dead producers
        case IC.Done(msgs, md, endTime) =>
          App.ui.modelFinished = true
          setState(Stopped)
          msgs.foreach{msg => Logger.log(msg)}
          Logger.hypothesisReport(md, 0, endTime)
        case Exit(_,ue:UncaughtException) =>
          Logger.error(ue.cause)
          setState(Stopped)
        case Exit(_,_) => 
          setState(Stopped)
        case msg =>
          println("Unknown Message Received by Controller: " + msg)
      }
    }
  }

  // FIXME: The CStore Interpreter init gives back an initial CStore
  //  what is the purpose of it, and in the refactoring from AppModel
  //  am I still handling the saturation correctly, especially with
  //  the 3D code
  var n = 0
  def flush(t: Tag, dead: Boolean, d: TraceData) : Unit = {
    if (d.isEmpty)
      return
    model.addData(t, dead, d)
    // FIXME: This is still sick
    App.ui.plotView.plotPanel.plotter ! plot.Refresh
      
    Swing.onEDT {
      // d.isInstanceOf[Iterable[GStore]] will not work due to type
      // erasure, must check the first element for its type
      if (d.nonEmpty && d.head.isInstanceOf[GStore]) {
        val _3DSampleInterval = 1; // should no longer be needed due
                                   // to ability to reduce rows outputted
        for (cs <- d.asInstanceOf[Iterable[GStore]]) {
          if (n == 0 || n > _3DSampleInterval) {
            n = 1
          } else
            n += 1
        }
      }
    }
  }
}

