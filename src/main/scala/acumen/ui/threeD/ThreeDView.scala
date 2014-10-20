package acumen.ui.threeD

import java.awt.event._
import java.awt.{Color, Component, Graphics}
import java.io._
import javax.swing.JPanel

import acumen.CId
import acumen.Errors._
import acumen.ui.{App, Files, Progress3d}
import com.threed.jpct._

import scala.actors._
import scala.collection.mutable.Map
import scala.math._
import scala.swing.Publisher

/* 3D visualization panel */
class ThreeDView extends JPanel {

  var alive = true
  // Set to true after everything finishes loading:
  var initialized = false

  Config.maxPolysVisible = 100000

  Config.useRotationPivotFrom3DS = true

  Config.useMultipleThreads = true

  val world = new World()  // create a new world

  var camera = new Camera

  var cameraPos = new SimpleVector()

  //Config.useMultipleThreads = true

  // Add texture for the axis
  val coAxises = new coAxis

  val axises = coAxises.cylinders

  var objects = scala.collection.mutable.Map[List[_], Object3D]()

  // initialize the Map to define the distance between each letter
  val letterDistance = {
    val characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz1234567890".toCharArray
    val distance = Array(0.03, 0.12, 0.09, 0.11, 0.12, 0.12, 0.09, 0.13, 0.17, 0.07, // A,B,C,D,E,F,G,H,I,J
                          0.1,  0.1,  0.1,  0.1,  0.1,  0.1,  0.1, 0.08, 0.08, 0.08, // K,L,M,N,O,P,Q,R,S,T
                         0.14, 0.08, 0.08, 0.08, 0.07, 0.08, 0.04,  0.1,  0.1,  0.1, // U,V,W,X,Y,Z,a,b,c,d
                          0.1, 0.07, 0.07,  0.1, 0.13,  0.1,  0.1,  0.1,  0.1,  0.1, // e,f,g,h,i,j,k,l,m,n
                          0.1,  0.1,  0.1,  0.1,  0.1, 0.07, 0.11, 0.08, 0.07, 0.07, // o,p,q,r,s,t,u,v,w,x
                         0.07, 0.08, 0.35,  0.1,  0.1,  0.1,  0.1,  0.1,  0.1,  0.1, // y,z,1,2,3,4,5,6,7,8
                          0.1,  0.1)
    (characters zip distance).toMap
  }

  // initialize the Map to define symbol character path (for creating 3D symbol character)
  val symbolPath = {
    val symbols = ",./<>?;:\'\"+-*|!@#$%^&()[]{}=".toCharArray
    val paths = Array("comma","dot","div","lessthan","biggerthan","questionMark","semicolon","colon",
      "quote","doubleQuote","plus","sub","mul","or","exclamation","at","sharp","dollar",
      "percent","powerMark","and","leftBra","rightBra","leftSBra","rightSBra","leftBraces",
      "rightBraces","equal")
    (symbols zip paths).toMap
  }

  val axisArray = Array(new Object3D(1))

  val defaultCamPos = new SimpleVector(3, -3, 10)

  var newMouseX = 1     // mouse position x before dragging
  var newMouseY = 1     // mouse position y before dragging
  var lastMouseX = 1    // mouse position x after dragging
  var lastMouseY = 1    // mouse position y after dragging
  var dragging = false  // flag for checking the mouse action
  var cameraLastAlpha = 0.0 // used for record the camera rotation
  var cameraLastTheta = 0.0 // used for record the camera rotation
  var cameraDirection = -1  // to make sure the camera rotate forward or backward

  letThereBeLight()  // create light sources for the scene

  addComponentListener(new ComponentAdapter {
    override def componentResized(e: ComponentEvent) = {
      val c = e.getSource.asInstanceOf[Component]
      initBuffer(c.getWidth, c.getHeight)
    }
  })

  addMouseListener(new MouseAdapter {
    override def mousePressed(e: MouseEvent) = {
      if (!dragging) {
        lastMouseX = e.getX
        lastMouseY = e.getY
        val cameraInitPos = camera.getPosition
        val radius = if (cameraInitPos.length() == 0) 0.01
                     else cameraInitPos.length()
        cameraLastAlpha = if (-cameraInitPos.x != 0) atan2(-cameraInitPos.z, -cameraInitPos.x)
                           else if (-cameraInitPos.z > 0) Pi/2
                           else -Pi/2
        cameraLastTheta = -cameraInitPos.y / radius
      }
      dragging = true
    }
    override def mouseReleased(e: MouseEvent) = {
      dragging = false
    }
  })

  addMouseWheelListener(new MouseAdapter {
    override def mouseWheelMoved(e: MouseWheelEvent) = {
      //Returns the number of notches the mouse wheel was rotated.
      // If the mouse wheel rotated towards the user (down) the value is positive.
      // If the mouse wheel rotated away from the user (up) the value is negative.
      val zoomSpeed = e.getWheelRotation
      if (zoomSpeed >= 0) {    // zoom out
        zoomout()
      } else {    // zoom in
        zoomin()
      }
      camera.lookAt(new SimpleVector(0, 0, 0))
      repaint()
    }
  })

  addMouseMotionListener(new MouseAdapter {
    override def mouseDragged(e: MouseEvent) = {
      if (dragging) {
        newMouseX = e.getX
        newMouseY = e.getY
        // to decrease the burden of calculations
        if (abs(newMouseX - lastMouseX) > 5 || abs(newMouseY - lastMouseY) > 5) {
          // Initialize the camera coordinate
          val cameraInitPos = camera.getPosition
          val deltaTheta = cameraDirection * (newMouseY - lastMouseY) * Pi / 500
          val deltaAlpha = (lastMouseX - newMouseX) * Pi / 750
          // translate jPCT coordinate to sphere coordinate
          val initX = -cameraInitPos.x
          val initY = -cameraInitPos.z
          val initZ = -cameraInitPos.y
          val radius = if (cameraInitPos.length() == 0) 0.01
                       else cameraInitPos.length()
          // get initial theta and alpha
          val initialAlpha = if (initX != 0) atan2(initY, initX)
                             else if (initY > 0) Pi/2
                             else -Pi/2
          val initialTheta = acos(initZ / radius)
          var alpha = initialAlpha + deltaAlpha
          var theta = initialTheta + deltaTheta
          alpha = normalizeAngle(alpha)
          if (initialTheta > 0 && theta < 0) {
            theta = -theta
            alpha = alpha + Pi
            cameraDirection = -1 * cameraDirection
          } else if (initialTheta < Pi && theta > Pi) {
            theta = 2 * Pi - theta
            alpha = alpha + Pi
            cameraDirection = -1 * cameraDirection
          }
          val newX = radius * sin(theta) * cos(alpha)
          val newY = radius * sin(theta) * sin(alpha)
          val newZ = radius * cos(theta)
          moveCamera(-newX, -newZ, -newY)
          lastMouseX = newMouseX
          lastMouseY = newMouseY
          cameraLastAlpha = alpha
          cameraLastTheta = theta
          camera.lookAt(new SimpleVector(0, 0, 0))
          repaint()
        }
      }
    }
  })

  def normalizeAngle (angle: Double): Double = {
    var newAngle = angle
    while (newAngle <= -Pi) newAngle += 2 * Pi
    while (newAngle > Pi) newAngle -= 2 * Pi
    newAngle
  }

  def moveCamera(dx: Double, dy: Double, dz: Double) = {
    val newPosition = new SimpleVector(dx, dy, dz)
    camera.setPosition(newPosition)
  }

  def axisOn() = {
    if (!axisArray.contains(axises(0))) {
      axisArray(0) = axises(0)
      world.addObjects(axises)
      axises(0).build()
      axises(1).build()
      axises(2).build()
    }
    this.repaint()
  }

  def axisOff() = {
    if (axisArray.contains(axises(0))) {
      world.removeObject(axises(0))
      world.removeObject(axises(1))
      world.removeObject(axises(2))
      axisArray(0) = null
      this.repaint()
    }
  }

  // create a new buffer to draw on:
  var buffer: FrameBuffer = null

  def initBuffer(bufferWidth: Int, bufferHeight: Int) = {
    buffer = new FrameBuffer(bufferWidth, bufferHeight, FrameBuffer.SAMPLINGMODE_OGSS)
  }

  def init() = {
    // add the main box
    val mainbox = drawBox(1, 1, 1)
    new setGlass(new Color(75, 75, 75), mainbox, 0)
    world.addObject(mainbox)
    lookAt(mainbox) // camera faces towards the object
    initialized = true
    axisOn()
    world.buildAllObjects()
    this.repaint()
  }

  override def paint(g: Graphics) = {
    buffer.clear(Color.LIGHT_GRAY) // erase the previous frame
    // render the world onto the buffer:
    world.renderScene(buffer)
    world.draw(buffer)
    buffer.update()
    buffer.display(g)
  }

  // point the camera toward the given object
  def lookAt(obj: Object3D) = {
    camera = world.getCamera  // grab a handle to the camera
    defaultView()
    camera.lookAt(obj.getTransformedCenter)  // look toward the object
  }

  // create some light sources for the scene
  def letThereBeLight() = {
    // Set the overall brightness of the world:
    world.setAmbientLight(-200, -200, -200)
    // Create main light sources:
    world.addLight(new SimpleVector(15.076f, -7.904f, 0f), 18, 18, 18)
    world.addLight(new SimpleVector(-15.076f, -7.904f, 0f), 18, 18, 18)
    world.addLight(new SimpleVector(0, -5f, 0), 2, 2, 2)
  }

  def defaultView() = {
    cameraPos.set(defaultCamPos)
    camera.setPosition(cameraPos)
    camera.setFOVLimits(0.01f, 3.0f)
    camera.setFOV(0.55f)
  }

  def reset() = {
    world.removeAllObjects()
    axisArray(0) = null
    world.newCamera()
    defaultView()
    init()
  }

  def zoomin() = {
    camera.increaseFOV(0.1f)
  }

  def zoomout() = {
    camera.decreaseFOV(0.1f)
  }

  def drawBox(length: Double, width: Double, height: Double): Object3D = {
    val box = new Object3D(12)

    val upperLeftFront=new SimpleVector(-width/2, -height/2, -length/2)
    val upperRightFront=new SimpleVector(width/2, -height/2, -length/2)
    val lowerLeftFront=new SimpleVector(-width/2, height/2, -length/2)
    val lowerRightFront=new SimpleVector(width/2, height/2, -length/2)

    val upperLeftBack = new SimpleVector(-width/2, -height/2, length/2)
    val upperRightBack = new SimpleVector(width/2, -height/2, length/2)
    val lowerLeftBack = new SimpleVector(-width/2, height/2, length/2)
    val lowerRightBack = new SimpleVector(width/2, height/2, length/2)

    // Front
    box.addTriangle(upperLeftFront,0,0, lowerLeftFront,0,1, upperRightFront,1,0)
    box.addTriangle(upperRightFront,1,0, lowerLeftFront,0,1, lowerRightFront,1,1)

    // Back
    box.addTriangle(upperLeftBack,0,0, upperRightBack,1,0, lowerLeftBack,0,1)
    box.addTriangle(upperRightBack,1,0, lowerRightBack,1,1, lowerLeftBack,0,1)

    // Upper
    box.addTriangle(upperLeftBack,0,0, upperLeftFront,0,1, upperRightBack,1,0)
    box.addTriangle(upperRightBack,1,0, upperLeftFront,0,1, upperRightFront,1,1)

    // Lower
    box.addTriangle(lowerLeftBack,0,0, lowerRightBack,1,0, lowerLeftFront,0,1)
    box.addTriangle(lowerRightBack,1,0, lowerRightFront,1,1, lowerLeftFront,0,1)

    // Left
    box.addTriangle(upperLeftFront,0,0, upperLeftBack,1,0, lowerLeftFront,0,1)
    box.addTriangle(upperLeftBack,1,0, lowerLeftBack,1,1, lowerLeftFront,0,1)

    // Right
    box.addTriangle(upperRightFront,0,0, lowerRightFront,0,1, upperRightBack,1,0)
    box.addTriangle(upperRightBack,1,0, lowerRightFront, 0,1, lowerRightBack,1,1)

    box
  }

  /** uses a vertex controller to rescale  **/

  def setBoxSize(scaleLength: Float, scaleWidth: Float, scaleHeight: Float, planeMesh: Mesh) = {
    val demoControl = new ResizerBox(scaleWidth,scaleHeight,scaleLength)
    planeMesh.setVertexController(demoControl, IVertexController.PRESERVE_SOURCE_MESH)
    planeMesh.applyVertexController()
    planeMesh.removeVertexController()
  }

  def setCylConeSize(radiusFactor: Float, heightFactor: Float, planeMesh: Mesh) = {
    val demoControl = new ResizerCylCone(radiusFactor, heightFactor)
    planeMesh.setVertexController(demoControl, IVertexController.PRESERVE_SOURCE_MESH)
    planeMesh.applyVertexController()
    planeMesh.removeVertexController()
  }
}

/* Timer for 3D-visualization, sends message to 3D renderer to coordinate animation */
class ScalaTimer(receiver: _3DDisplay, endTime: Double,
                      playSpeed: Double) extends Publisher with Actor {
  var pause = true
  var destroy = false
  var sleepTime = 0.0
  var extraTime = 0.0
  var initSpeed = 0.0

  if (receiver.totalFrames > 0)
    sleepTime = endTime * 1000 / receiver.totalFrames

  initSpeed = sleepTime
  sleepTime /= playSpeed
  extraTime = ((sleepTime - sleepTime.toLong) * 1000000).toInt // To nano sec

  def act() {
    loopWhile(!destroy) {
      if (destroy)
        exit()
      if (pause)
      /* Tell the receiver to show the next frame */
        receiver ! "go"
      /* Millisecond and Nanosecond */
      Thread.sleep(sleepTime.toLong, extraTime.toInt)
    }
  }
}

/* 3D Render */
class _3DDisplay(app: ThreeDView, slider: Slider3D,
                     _3DDateBuffer: scala.collection.mutable.Map[CId, scala.collection.mutable.Map[Int, scala.collection.mutable.Buffer[List[_]]]],
                     lastFrame1: Double, endTime: Double,
                     _3DView: List[(Array[Double], Array[Double])]) extends Publisher with Actor {
  /* Default directory where all the OBJ files are */
  private val _3DBasePath = Files._3DDir.getAbsolutePath
  var currentFrame = 0 // FrameNumber
  var totalFrames = 2
  var lastFrame = 2.0
  var pause = false
  var destroy = false
  lastFrame = lastFrame1
  totalFrames = lastFrame.toInt
  val startFrameNumber = 2

  def stop() = {
    if (!app.objects.nonEmpty)
      app.world.removeAllObjects()
  }

  def bufferFrame(list: List[_]): Int = {
    list.last match {
      case time: Int => time
      case _ => throw ShouldNeverHappen()
    }
  }

  def bufferPosition(list: List[_]): Array[Double] = {
    list(1) match {
      case p: Array[Double] => p
      case _ => throw ShouldNeverHappen()
    }
  }

  def bufferAngle(list: List[_]): Array[Double] = {
    list(4) match {
      case p: Array[Double] => p
      case _ => throw ShouldNeverHappen()
    }
  }

  def bufferType(list: List[_]): String = {
    list(0) match {
      case p: String => p
      case _ => throw ShouldNeverHappen()
    }
  }

  def bufferColor(list: List[_]): List[Double] = {
    list(3) match {
      case p: Array[Double] => p.toList
      case _ => throw ShouldNeverHappen()
    }
  }

  def bufferSize(list: List[_]): List[Double] = {
    list(2) match {
      case p: Array[Double] => p.toList
      case _ => throw ShouldNeverHappen()
    }
  }

  def bufferString(list: List[_]): String = {
    list(5) match {
      case s: String => s
      case _ => throw ShouldNeverHappen()
    }
  }

  // Return the first frame number of the object
  def firstFrame(buffer: scala.collection.mutable.Buffer[List[_]]): Int = {
    buffer.head.last match {
      case first: Int => first
      case _ => throw ShouldNeverHappen()
    }
  }

  def lastFrame(buffer: scala.collection.mutable.Buffer[List[_]]): Int = {
    buffer.last.last match {
      case last: Int => last
      case _ => throw ShouldNeverHappen()
    }
  }

  def checkResizeable(newSize: List[Double]): Boolean = {
    var flag = true
    for (i <- 0 until newSize.length) {
      if (newSize(i).isNaN || newSize(i).isInfinite)
        flag = false
    }
    flag
  }

  /**
   * Moving and rotating the object
   */
  def transformObject(id: List[_], objects: scala.collection.mutable.Map[List[_], Object3D],
                      buffer: scala.collection.mutable.Buffer[List[_]],
                      currentFrame: Int) {

    var objID = 1
    var opaque = false
    /* Find the corresponding index of the object */
    val index = currentFrame - bufferFrame(buffer.head)
    /* Get the 3D information of the object at that frame	*/
    val (tempPosition, tempAngle, tempColor, tempSize, tempType) =
      if (index >= 0 && index < buffer.size)
        (bufferPosition(buffer(index)) , bufferAngle(buffer(index)), bufferColor(buffer(index)),
        bufferSize(buffer(index)), bufferType(buffer(index)))
      else (Array(0.0,0.0,0.0), Array(0.0,0.0,0.0), List(0.0,0.0,0.0), List(0.0), " ")
    val (tempContent, tempPath) =
    if (index >= 0 && index < buffer.size)
      (if (tempType == "Text") bufferString(buffer(index)) else " ",
       if (tempType == "OBJ") bufferString(buffer(index)) else " ")
    else (" ", " ")
    if ((buffer(index)(5) == "transparent") && (index >= 0 && index < buffer.size))
        opaque = true
    // get the object ID
    objID = objects(id).getID
    // get the object need to transform
    var transObject = view.getObject(objID)
    // we don't need to care about first frame, since all the objects are fresh
    if (index >= 1) {
      // reset the type and size for the object, matching the type of object first
      tempType match {
        case "Box" =>
          val lastTempType = bufferType(buffer(index - 1))
          val lastTempSize = bufferSize(buffer(index - 1))
          // the type has been changed, we need to delete the old object and create a one
          if (lastTempType != tempType) {
            // change the object in
            view.removeObject(objID)
            // Since some object need to scale, we never allow the initial size become 0
            val sizeToSetX = if (tempSize(1) == 0) 0.001 else tempSize(1)
            val sizeToSetY = if (tempSize(0) == 0) 0.001 else tempSize(0)
            val sizeToSetZ = if (tempSize(2) == 0) 0.001 else tempSize(2)
            objects(id) = app.drawBox(abs(sizeToSetY), abs(sizeToSetX), abs(sizeToSetZ))
            transObject = objects(id)
            objID = objects(id).getID // renew the object ID
            view.addObject(transObject)
            transObject.setShadingMode(Object3D.SHADING_FAKED_FLAT)
          } else if (lastTempSize != tempSize && checkResizeable(tempSize) && checkResizeable(lastTempSize)) {
            // just need change the size
            val (widthFactor, lengthFactor, heightFactor) =
              (if (lastTempSize(0) == 0 && tempSize(0) == 0) 1
               else if (lastTempSize(0) == 0 && tempSize(0) != 0) abs(tempSize(0) / 0.001)
               else if (lastTempSize(0) != 0 && tempSize(0) == 0) abs(0.001 / lastTempSize(0))
               else abs(tempSize(0) / lastTempSize(0)),
               if (lastTempSize(1) == 0 && tempSize(1) == 0) 1
               else if (lastTempSize(1) == 0 && tempSize(1) != 0) abs(tempSize(1) / 0.001)
               else if (lastTempSize(1) != 0 && tempSize(1) == 0) abs(0.001 / lastTempSize(1))
               else abs(tempSize(1) / lastTempSize(1)),
               if (lastTempSize(2) == 0 && tempSize(2) == 0) 1
               else if (lastTempSize(2) == 0 && tempSize(2) != 0) abs(tempSize(2) / 0.001)
               else if (lastTempSize(2) != 0 && tempSize(2) == 0) abs(0.001 / lastTempSize(2))
               else abs(tempSize(2) / lastTempSize(2)))
            val boxMesh = transObject.getMesh
            app.setBoxSize(lengthFactor.toFloat, widthFactor.toFloat, heightFactor.toFloat, boxMesh)
          }
        case "Cylinder" => // we don't need to care about first frame, since all the objects are fresh
          val lastTempType = bufferType(buffer(index - 1))
          val lastTempSize = bufferSize(buffer(index - 1))
          // the type has been changed, we need to delete the old object and create a one
          if (lastTempType != tempType) {
            // change the object in
            view.removeObject(objID)
            val sizeToSetR = if (tempSize(0) == 0) 0.001 else tempSize(0)
            val sizeToSetS = if (tempSize(1) == 0) 0.001 else tempSize(1)
            objects(id) = Primitives.getCylinder(20, abs(sizeToSetR.toFloat), abs(sizeToSetS / (2 * sizeToSetR)).toFloat)
            transObject = objects(id)
            objID = objects(id).getID // renew the object ID
            view.addObject(transObject)
            transObject.setShadingMode(Object3D.SHADING_FAKED_FLAT)
          } else if (lastTempSize != tempSize && checkResizeable(tempSize) && checkResizeable(lastTempSize)) {
            // just need change the size
            val (radiusFactor, heightFactor) =
              (if (lastTempSize(0) == 0 && tempSize(0) == 0) 1
               else if (lastTempSize(0) == 0 && tempSize(0) != 0) abs(tempSize(0) / 0.001)
               else if (lastTempSize(0) != 0 && tempSize(0) == 0) abs(0.001 / lastTempSize(0))
               else abs(tempSize(0) / lastTempSize(0)),
               if (lastTempSize(1) == 0 && tempSize(1) == 0) 1
               else if (lastTempSize(1) == 0 && tempSize(1) != 0) abs(tempSize(1) / 0.001)
               else if (lastTempSize(1) != 0 && tempSize(1) == 0) abs(0.001 / lastTempSize(1))
               else abs(tempSize(1) / lastTempSize(1)))
            val boxMesh = transObject.getMesh
            app.setCylConeSize(radiusFactor.toFloat, heightFactor.toFloat, boxMesh)
          }
        case "Cone" => // we don't need to care about first frame, since all the objects are fresh
          val lastTempType = bufferType(buffer(index - 1))
          val lastTempSize = bufferSize(buffer(index - 1))
          // the type has been changed, we need to delete the old object and create a one
          if (lastTempType != tempType) {
            // change the object in
            view.removeObject(objID)
            val sizeToSetR = if (tempSize(0) == 0) 0.001 else tempSize(0)
            val sizeToSetS = if (tempSize(1) == 0) 0.001 else tempSize(1)
            objects(id) = Primitives.getCone(20, abs(sizeToSetR.toFloat), abs(sizeToSetS / (sizeToSetR * 2)).toFloat)
            transObject = objects(id)
            objID = objects(id).getID // renew the object ID
            view.addObject(transObject)
          } else if (lastTempSize != tempSize && checkResizeable(tempSize) && checkResizeable(lastTempSize)) {
            // just need change the size
            val (radiusFactor, heightFactor) =
              (if (lastTempSize(0) == 0 && tempSize(0) == 0) 1
               else if (lastTempSize(0) == 0 && tempSize(0) != 0) abs(tempSize(0) / 0.001)
               else if (lastTempSize(0) != 0 && tempSize(0) == 0) abs(0.001 / lastTempSize(0))
               else abs(tempSize(0) / lastTempSize(0)),
               if (lastTempSize(1) == 0 && tempSize(1) == 0) 1
               else if (lastTempSize(1) == 0 && tempSize(1) != 0) abs(tempSize(1) / 0.001)
               else if (lastTempSize(1) != 0 && tempSize(1) == 0) abs(0.001 / lastTempSize(1))
               else abs(tempSize(1) / lastTempSize(1)))
            val boxMesh = transObject.getMesh
            app.setCylConeSize(radiusFactor.toFloat, heightFactor.toFloat, boxMesh)
          }
        case "Sphere" => // we don't need to care about first frame, since all the objects are fresh
          val lastTempType = bufferType(buffer(index - 1))
          val lastTempSize = bufferSize(buffer(index - 1))
          // the type has been changed, we need to delete the old object and create a one
          if (lastTempType != tempType) {
            // change the object in
            view.removeObject(objID)
            val sizeToSetR = if (tempSize(0) == 0) 0.001 else tempSize(0)
            objects(id) = Primitives.getSphere(10, abs(sizeToSetR.toFloat))
            transObject = objects(id)
            objID = objects(id).getID // renew the object ID
            view.addObject(transObject)
          } else if (lastTempSize != tempSize && checkResizeable(tempSize) && checkResizeable(lastTempSize)) {
            // just need change the size
            val radiusFactor =
              if (lastTempSize(0) == 0 && tempSize(0) != 0) abs(tempSize(0) / 0.001)
              else if (lastTempSize(0) == 0 && tempSize(0) == 0) 1
              else if (lastTempSize(0) != 0 && tempSize(0) == 0) abs(0.001 / lastTempSize(0))
              else abs(tempSize(0) / lastTempSize(0))
            transObject.scale(radiusFactor.toFloat)
          }
        case "Text" =>
          val lastTempType = bufferType(buffer(index - 1))
          val lastTempSize = bufferSize(buffer(index - 1))
          val lastTempContent = bufferString(buffer(index-1))
          // the type has been changed, we need to delete the old object and create a one
          if ((lastTempType != tempType || lastTempContent != tempContent) && !tempContent.isEmpty) {
            // change the object in
            view.removeObject(objID)
            val sizeToSetR = if (tempSize(0) == 0) 0.001 else tempSize(0)
            objects(id) = buildText(tempContent, sizeToSetR)
            transObject = objects(id)
            objID = objects(id).getID // renew the object ID
            view.addObject(transObject)
          } else if (lastTempSize != tempSize && checkResizeable(tempSize) && checkResizeable(lastTempSize)) {
            // just need change the size
            val radiusFactor =
              if (lastTempSize(0) == 0 && tempSize(0) != 0) abs(tempSize(0) / 0.001)
              else if (lastTempSize(0) == 0 && tempSize(0) == 0) 1
              else if (lastTempSize(0) != 0 && tempSize(0) == 0) abs(0.001 / lastTempSize(0))
              else abs(tempSize(0) / lastTempSize(0))
            val boxMesh = transObject.getMesh
            app.setBoxSize(radiusFactor.toFloat, radiusFactor.toFloat, radiusFactor.toFloat, boxMesh)
          }
        case "OBJ" =>
          val lastTempType = bufferType(buffer(index - 1))
          val lastTempSize = bufferSize(buffer(index - 1))
          val lastTempPath = bufferString(buffer(index -1))
          // the type has been changed, we need to delete the old object and create a one
          if ((lastTempType != tempType || tempPath != lastTempPath) && !tempPath.isEmpty) {
            // change the object in
            view.removeObject(objID)
            val sizeToSetR = if (tempSize(0) == 0) 0.001 else tempSize(0) / 50
            objects(id) = loadObj(tempPath, sizeToSetR)
            transObject = objects(id)
            objID = objects(id).getID // renew the object ID
            view.addObject(transObject)
          } else if (lastTempSize != tempSize && checkResizeable(tempSize) && checkResizeable(lastTempSize)) {
            // just need change the size
            val radiusFactor =
              if (lastTempSize(0) == 0 && tempSize(0) != 0) abs(tempSize(0) / 0.001)
              else if (lastTempSize(0) == 0 && tempSize(0) == 0) 1
              else if (lastTempSize(0) != 0 && tempSize(0) == 0) abs(0.001 / lastTempSize(0))
              else abs(tempSize(0) / lastTempSize(0))
            val boxMesh = transObject.getMesh
            app.setBoxSize(radiusFactor.toFloat, radiusFactor.toFloat, radiusFactor.toFloat, boxMesh)
          }
        case _ => throw ShouldNeverHappen()
      }
    }

    if (transObject != null) {
      // reset the color for the object
      setColor(transObject, tempColor)
      // rotate the object
      if (checkResizeable(tempAngle.toList)) {
        rotateObject(transObject, tempAngle, tempType)
      }
      // calculate the transVector for the object and translate it
      val tempTransVector = new SimpleVector(-tempPosition(0), -tempPosition(2), -tempPosition(1))
      val transVector = tempTransVector.calcSub(transObject.getTransformedCenter)
      transObject.translate(transVector)

      transObject.build()
      if (tempType == "Text") {
        transObject.setRotationPivot(new SimpleVector(0,0,0))
        transObject.setCenter(new SimpleVector(0,0,0))
      }
    }
  }

  def renderCurrentFrame() = {
    for ((id, map) <- _3DDateBuffer) {
      // acumen objects
      for ((objectNumber, buffer) <- map) {
        // 3d objects within
        if (firstFrame(buffer) <= currentFrame && lastFrame(buffer) >= currentFrame) {
          if (!app.objects.contains(List(id, objectNumber))) {
            matchingObject(List(id, objectNumber), buffer, currentFrame)
          } else {
            transformObject(List(id, objectNumber), app.objects, buffer, currentFrame)
          }
        } else {
          if (app.objects.contains(List(id, objectNumber))) {
            deleteObj(List(id, objectNumber))
            view.removeObject(app.objects.getOrElse(List(id, objectNumber), null)) // remove the object from the view
          }
        }
      }
    }
  }

  // Main execution loop
  var view = app.world

  def act() {
    loopWhile(!destroy) {
      if (destroy)
        exit()
      react {
        case "go" =>
          renderCurrentFrame()
          app.repaint()
          if (currentFrame == totalFrames) {
            // Animation is over
            emitProgress(100)
            destroy = true
            pause = true
          }
          if (totalFrames > 0)
            emitProgress(currentFrame * 100 / totalFrames)
          if (currentFrame < totalFrames)
            currentFrame += 1
      }
    }
  }

  // Reactions to the mouse events
  reactions += {
    case e: scala.swing.event.MouseDragged =>
      currentFrame = slider.bar.value * totalFrames / 100
      emitProgress(slider.bar.value)
      //publish(Playing3d())
      if (currentFrame < 2)
        currentFrame = startFrameNumber
      if (currentFrame > totalFrames)
        currentFrame = totalFrames
      if (pause)
        renderCurrentFrame()

  }

  /**
   * Delete an 3D-object from scene
   */
  def deleteObj(c: List[_]) {
    if (app.objects.contains(c)) {
      app.objects.remove(c)
    }
  }

  // build the 3D text by adding .3ds object
  def buildText(text: String, size: Double): Object3D = {
    val allChar = text.toCharArray
    val objectsArray = new scala.collection.mutable.ArrayBuffer[Object3D]
    var firstLetter = true
    var distanceToShift = 0.0
    for (i <- 0 until allChar.length) {
      if ((allChar(i).isLetterOrDigit || app.symbolPath.contains(allChar(i))) && !firstLetter) {
        objectsArray += loadLetter(allChar(i), size)
        // calculate the shift distance from last letter, firstly, we need to get the last letter index
        var j = 1 // the count for blank space
        while (!allChar(i - j).isLetterOrDigit && !app.symbolPath.contains(allChar(i - j)) && j <= i) {
          j += 1
        }
        // get the width of last letter
        val tempDistance = objectsArray.apply(i - j).getMesh.getBoundingBox()(1) - objectsArray.apply(i - j).getMesh.getBoundingBox()(0)
        if (allChar(i-j).isLetterOrDigit)
          distanceToShift += tempDistance + app.letterDistance(allChar(i-j)) * size
        else
          distanceToShift += tempDistance + 0.2 * size
        objectsArray.apply(i).translate(-distanceToShift.toFloat, 0, 0)
        objectsArray.apply(i).translateMesh()
        objectsArray.apply(i).setTranslationMatrix(new Matrix())
      } else if (firstLetter && (allChar(i).isLetterOrDigit || app.symbolPath.contains(allChar(i)))){
        objectsArray += loadLetter(allChar(i), size)
        firstLetter = false
      } else {
        objectsArray += loadLetter(allChar(i), size)
        firstLetter = false
        if (i != 0) {
          var j = 1     // the count for blank space
          while (!allChar(i - j).isLetterOrDigit && !app.symbolPath.contains(allChar(i - j)) && j <= i) {
            j += 1
          }
          distanceToShift += 0.2 * j
        }
      }
    }
    val stringObject = Object3D.mergeAll(objectsArray.toArray)
    stringObject
  }

  // load text .3ds file in
  def loadLetter(path: Char, size: Double): Object3D = {
    val objFileloader =
    if (path.isUpper)         // read a uppercase character
      Loader.load3DS(getClass.getResourceAsStream("Uppercase_Characters" + File.separator + path.toString + ".3ds"), size.toFloat)(0)
    else if (path.isLower)    // read a lowercase character
      Loader.load3DS(getClass.getResourceAsStream("Lowercase_Characters" + File.separator + path.toString + ".3ds"), size.toFloat)(0)
    else if (path.isDigit)    // read a digit
      Loader.load3DS(getClass.getResourceAsStream("Digit_Characters" + File.separator + path.toString + ".3ds"), size.toFloat)(0)
    else if (app.symbolPath.contains(path))    // read a digit
      Loader.load3DS(getClass.getResourceAsStream("Symbol_Characters" + File.separator + app.symbolPath(path) + ".3ds"), size.toFloat)(0)
    else new Object3D(1)
    objFileloader
  }

  // Load .obj file
  def loadObj(path: String, size: Double): Object3D = {
    //read in the geometry information from the data file
    val _3DFolder = new File(_3DBasePath + File.separator)
    val listOfFiles = _3DFolder.listFiles()
    val objectFileBase = (_3DBasePath + File.separator + path).split("\\.")(0)
    var objectTexture: Texture = null
    val texturePath = objectFileBase + ".png"
    var MTLPath:String = null
    for (i <- 0 until listOfFiles.length) {
      if (listOfFiles(i).getPath == texturePath)
        objectTexture = new Texture(texturePath)
      if (listOfFiles(i).getPath == objectFileBase + ".mtl")
        MTLPath = listOfFiles(i).getPath
    }
    val objFileloader = Loader.loadOBJ(_3DBasePath + File.separator + path, MTLPath, size.toFloat)(0)
    if (objectTexture != null) {
      TextureManager.getInstance().addTexture(MTLPath, objectTexture)
      objFileloader.setTexture(MTLPath)
    }
    objFileloader
  }

  def matchingObject(c: List[_], buffer: scala.collection.mutable.Buffer[List[_]],
                     currentFrame: Int) = {
    var opaque = false
    /* Find the corresponding index of the object */
    val index = currentFrame - bufferFrame(buffer.head)
    /* Get the 3D information of the object at that frame	*/
    val (position, angle, color, size, name) =
      if (index >= 0 && index < buffer.size)
        (bufferPosition(buffer(index)) , bufferAngle(buffer(index)), bufferColor(buffer(index)),
          bufferSize(buffer(index)), bufferType(buffer(index)))
      else (Array(0.0,0.0,0.0), Array(0.0,0.0,0.0), List(0.0,0.0,0.0), List(0.0), " ")
    val (text, path) =
      if (index >= 0 && index < buffer.size)
        (if (name == "Text") bufferString(buffer(index)) else " ",
          if (name == "OBJ") bufferString(buffer(index)) else " ")
      else (" ", " ")
    if ((buffer(index)(5) == "transparent") && (index >= 0 && index < buffer.size))
      opaque = true

    val newObject = name match {
      case "Box" =>
        // Since some object need to scale, we never allow the initial size become 0
        val sizeToSetX = if (size(1) == 0) 0.001 else size(1)
        val sizeToSetY = if (size(0) == 0) 0.001 else size(0)
        val sizeToSetZ = if (size(2) == 0) 0.001 else size(2)
        app.drawBox(abs(sizeToSetX), abs(sizeToSetY), abs(sizeToSetZ))
      case "Cylinder" =>
        val sizeToSetR = if (size(0) == 0) 0.001 else size(0)
        val sizeToSetS = if (size(1) == 0) 0.001 else size(1)
        Primitives.getCylinder(20, abs(sizeToSetR).toFloat, abs(sizeToSetS / (sizeToSetR * 2)).toFloat)
      case "Cone" =>
        val sizeToSetR = if (size(0) == 0) 0.001 else size(0)
        val sizeToSetS = if (size(1) == 0) 0.001 else size(1)
        Primitives.getCone(20, abs(sizeToSetR.toFloat), abs(sizeToSetS / (sizeToSetR * 2)).toFloat)
      case "Sphere" =>
        val sizeToSetR = if (size(0) == 0) 0.001 else size(0)
        Primitives.getSphere(20, abs(sizeToSetR.toFloat))
      case "Text" =>
        val sizeToSetR = if (size(0) == 0) 0.001 else size(0)
        if (!text.isEmpty)  // model err, do nothing
          buildText(text, sizeToSetR)
        else
          null
      case "OBJ" =>
        val sizeToSetR = if (size(0) == 0) 0.001 else size(0) / 50
        if (!path.isEmpty)  // model err, do nothing
          loadObj(path, sizeToSetR)
        else
          null
      case _ => throw ShouldNeverHappen()
    }

    if (newObject != null) {
      // set color to the object
      setColor(newObject, color)
      if (name == "Box" || name == "Cylinder")
        newObject.setShadingMode(Object3D.SHADING_FAKED_FLAT)
      // rotate the object
      rotateObject(newObject, angle, name)

      // calculate the transVector for the object and translate object
      val tempTransVector = new SimpleVector(-position(0), -position(2), -position(1))
      val transVector = tempTransVector.calcSub(newObject.getTransformedCenter)
      newObject.translate(transVector)

      app.objects -= c
      app.objects += c.toList -> newObject
      view.addObject(newObject)
      newObject.build()
      if (name == "Text") {
        newObject.setRotationPivot(new SimpleVector(0,0,0))
        newObject.setCenter(new SimpleVector(0,0,0))
      }
    }
  }

  def setColor(objectToSet: Object3D, colorList: List[Double]) = {
    val colorToSet = Array(1.0, 1.0, 1.0)
    colorToSet(0) = colorList(0) * 255
    colorToSet(1) = colorList(1) * 255
    colorToSet(2) = colorList(2) * 255
    if (colorToSet(0) > 255)
      colorToSet(0) = 255
    if (colorToSet(1) > 255)
      colorToSet(1) = 255
    if (colorToSet(2) > 255)
      colorToSet(2) = 255
    objectToSet.setAdditionalColor(new Color(colorToSet(0).toInt, colorToSet(1).toInt, colorToSet(2).toInt))
  }

  def rotateObject(rotateObject: Object3D, angle: Array[Double], objectType: String) = {
    // Once we added the object, we should also move the object to the position at that time
    val tranObjectRotMatrixX = new Matrix()
    val tranObjectRotMatrixY = new Matrix()
    val tranObjectRotMatrixZ = new Matrix()
    val tranObjectRotTempMat = new Matrix()
    val tranObjectRotMatrix = new Matrix()
    // to make the coordinate as same as Java3D
    if (objectType == "Cylinder" || objectType == "Cone")
      tranObjectRotMatrix.rotateX((-Pi / 2).toFloat)
    else if (objectType == "OBJ") {
      tranObjectRotMatrix.rotateX((-Pi / 2).toFloat)
      tranObjectRotMatrix.rotateY(Pi.toFloat)
    }
    tranObjectRotMatrixZ.rotateZ(-angle(1).toFloat)
    tranObjectRotMatrixY.rotateY(-angle(2).toFloat)
    tranObjectRotMatrixX.rotateX(angle(0).toFloat)
    tranObjectRotTempMat.matMul(tranObjectRotMatrixX)
    tranObjectRotTempMat.matMul(tranObjectRotMatrixZ)
    tranObjectRotTempMat.matMul(tranObjectRotMatrixY)
    tranObjectRotMatrix.matMul(tranObjectRotTempMat)
    rotateObject.setRotationMatrix(tranObjectRotMatrix)
  }

  // Update the slider value
  private def emitProgress(p: Int) = App.actor ! Progress3d(p)

}

// Transparent box
class setGlass(color: Color, objectA: Object3D, transparancy: Int) {
  objectA.setTransparencyMode(Object3D.TRANSPARENCY_MODE_DEFAULT) //TRANSPARENCY_MODE_DEFAULT
  objectA.setTransparency(transparancy) // the transparency level. 0 is the highest possible transparency
  objectA.setAdditionalColor(color) // the color of the object3D
  //objectA.setCulling(false) //Disables back side culling for the current object
}

// vertax controller classes

class ResizerBox(xFactor: Float, yFactor: Float, zFactor: Float) extends GenericVertexController {

  val XFactor = xFactor
  val YFactor = yFactor
  val ZFactor = zFactor

  def apply() {
    val s = getSourceMesh
    val d = getDestinationMesh

    for (i <- 0 until s.length) {
      d(i).x = s(i).x * XFactor
      d(i).z = s(i).z * ZFactor
      d(i).y = s(i).y * YFactor
    }
  }
}

class ResizerCylCone(radiusFactor: Float, heightFactor: Float) extends GenericVertexController {

  val RadiusFactor = radiusFactor
  val HeightFactor = heightFactor

  def apply() {
    val s = getSourceMesh
    val d = getDestinationMesh

    for (i <- 0 until s.length) {
      d(i).x = s(i).x * RadiusFactor
      d(i).z = s(i).z * RadiusFactor
      d(i).y = s(i).y * HeightFactor
    }
  }
}

//Axis
class coAxis {
  val cylinders: Array[Object3D] = new Array[Object3D](3)
  for (x <- 0 until cylinders.length) {
    cylinders(x) = Primitives.getCylinder(50, 0.01f, 400f)
    cylinders(x).build()
  }
  new setGlass(Color.BLUE, cylinders(0), -1)  //Z
  new setGlass(Color.RED, cylinders(1), -1)   //X
  new setGlass(Color.GREEN, cylinders(2), -1) //Y

  cylinders(0).translate(0f, -3f, 0f) // if use 30f then use y = -3(-2.5)
  cylinders(1).rotateZ(0.5f * -Pi.toFloat)
  cylinders(1).translate(-3f, 0f, 0f)
  cylinders(2).rotateX(-0.5f * Pi.toFloat)
  cylinders(2).translate(0f, 0f, -3f)
}