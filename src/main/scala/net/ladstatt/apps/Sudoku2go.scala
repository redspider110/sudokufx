package net.ladstatt.apps

import scala.concurrent._
import ExecutionContext.Implicits.global
import javafx.animation._


import javafx.scene._
import javafx.util.Duration

import javafx.scene.effect._
import javafx.geometry.Insets
import java.io.{FilenameFilter, ByteArrayInputStream, File}
import scala.collection.mutable.{ListBuffer, ArrayBuffer}
import scala.util.Try
import org.opencv.core._
import org.opencv.highgui.{VideoCapture, Highgui}
import org.opencv.imgproc.Imgproc
import javafx.application.{Platform, Application}
import javafx.beans.value.{WritableValue, ChangeListener, ObservableValue}
import javafx.collections.ListChangeListener
import javafx.event.Event
import javafx.event.EventHandler
import javafx.scene.control._
import javafx.scene.image.{Image, ImageView}
import javafx.scene.layout.{StackPane, VBox, HBox, BorderPane}
import javafx.stage.Stage
import javafx.util.Callback
import scala.collection.JavaConversions._
import javafx.geometry.{Pos, Orientation}
import javafx.scene.paint.Color
import javafx.scene.shape.{Polyline, Rectangle, Polygon}
import org.opencv.features2d.{DescriptorMatcher, DescriptorExtractor, FeatureDetector}
import scala.concurrent.Future
import javafx.scene.text.Font
import javafx.animation.Animation.Status
import scala.util.Failure
import scala.Some
import scala.util.Success
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Failure
import scala.Some
import scala.util.Success
import javax.imageio.ImageIO
import javafx.embed.swing.SwingFXUtils
import javafx.concurrent.{Task, Service}

/**
 * For a discussion of the concepts of this application see http://ladstatt.blogspot.com/
 */
trait Utils {

  // see https://gist.github.com/tixxit/1246894
  def levensthein[A](a: Iterable[A], b: Iterable[A]): Int = {
    import scala.math.min
    ((0 to b.size).toList /: a)((prev, x) =>
      (prev zip prev.tail zip b).scanLeft(prev.head + 1) {
        case (h, ((d, v), y)) => min(min(h + 1, v + 1), d + (if (x == y) 0 else 1))
      }) last
  }

  val runOnMac = {
    System.getProperty("os.name").toLowerCase match {
      case "mac os x" => true
      case _ => false
    }
  }

  def option[A, X](o: Option[A])(none: => X, some: => A => X): X = {
    o match {
      case None => none
      case Some(a) => some(a)
    }
  }

  def nullable[A, X](o: X)(isnull: => A, notnull: => X => A): A = {
    if (o == null) {
      isnull
    } else {
      notnull(o)
    }
  }

  /**
   * function to measure execution time of first function, optionally executing a display function,
   * returning the time in microseconds
   */
  def time[A](a: => A, display: Long => Unit = s => ()): A = {
    val now = System.nanoTime
    val result = a
    val micros = (System.nanoTime - now) / 1000000
    display(micros)
    result
  }

}

trait OpenCVUtils extends Utils {

  // some type aliases to get a more typesafe -ish API
  type DescriptorMatcherType = Int
  type FeatureDetectorType = Int
  type DescriptorExtractorType = Int

  /**
   * Returns position and value for a template for a given image
   *
   * @param haystack
   * @param needle
   * @param matchMethod
   * @return
   */
  def matchTemplate(haystack: Mat, needle: Mat, matchMethod: Int = Imgproc.TM_SQDIFF): (Double, Point) = {
    def norm(mat: Mat) = adaptiveThreshold(dilate(blur(mat)), 255, 9)
    val normedHayStack = norm(haystack)
    val normedNeedle = norm(needle)
    val width = haystack.cols - needle.cols + 1
    val height = haystack.rows - needle.rows + 1
    val resultImage = new Mat(width, height, CvType.CV_32FC1)
    Imgproc.matchTemplate(normedHayStack, normedNeedle, resultImage, matchMethod)
    val minMaxResult = Core.minMaxLoc(resultImage)
    val matchLoc =
      if (matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED)
        minMaxResult.minLoc
      else minMaxResult.maxLoc
    (minMaxResult.maxVal, matchLoc)
  }

  def updateImageView(imageView: ImageView)(mat: Mat): Unit = {
    toImage(mat) match {
      case Success(image) => imageView.setImage(image)
      case Failure(e) => sys.error(e.getMessage)
    }
  }

  /**
   * stretches image to make feature extraction's life easier
   */
  def warp(input: Mat, srcCorners: Seq[Point], destCorners: Seq[Point]): Mat = {
    val pDest = {
      val m = new MatOfPoint2f
      m.fromList(destCorners)
      m
    }

    val pSrc = {
      val m = new MatOfPoint2f
      m.fromList(srcCorners)
      m
    }

    val transformationMatrix = Imgproc.getPerspectiveTransform(pSrc, pDest)

    val dest = new Mat()
    Imgproc.warpPerspective(input, dest, transformationMatrix, input.size())
    dest
  }

  def mkDescriptor(mat: Mat, featureDetectorType: FeatureDetectorType,
                   descriptorExtractorType: DescriptorExtractorType): Mat = {

    val detector = FeatureDetector.create(featureDetectorType)
    val kp = new MatOfKeyPoint()
    detector.detect(mat, kp)
    val descriptors = new Mat

    val extractor = DescriptorExtractor.create(descriptorExtractorType)
    extractor.compute(mat, kp, descriptors)
    descriptors
  }


  /**
   * Contains a contour, bounding box and the raw data for a number.
   *
   * @param contourArea
   * @param approxCurve
   * @param boundingRect
   * @param boundingBoxMat
   */
  case class Contour(contourArea: Double,
                     approxCurve: MatOfPoint2f,
                     boundingRect: Rect,
                     boundingBoxMat: Mat,
                     cellMat: Mat,
                     poly: Polygon) {

    lazy val boundingBox = {
      val r = new Rectangle(boundingRect.x, boundingRect.y, boundingRect.width, boundingRect.height)
      r.setStroke(Color.BROWN)
      r.setFill(Color.BISQUE)
      r.setOpacity(0.5)
      r
    }

    lazy val resizedNumberData = {
      val size = new Size(25, 50)
      val resizedNumberData = new Mat(size, CvType.CV_8UC1)
      Imgproc.resize(boundingBoxMat, resizedNumberData, size)
      resizedNumberData
    }

    def boundingArea = boundingRect.area

    def contains(p: Point): Boolean = boundingRect.contains(p)
  }


  def mkPolygon(approxCurve: MatOfPoint2f) = {
    val p = new Polygon(approxCurve.toArray.toList.map(p => List(p.x, p.y)).flatten: _*)
    p.setStroke(Color.FIREBRICK)
    p.setFill(Color.GREEN)
    p.setOpacity(1)
    p
  }


  def coreFindContours(input: Mat): Seq[MatOfPoint] = {
    val contours = new java.util.ArrayList[MatOfPoint]()
    Imgproc.findContours(input, contours, new Mat, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
    contours
  }


  def findContourWithMaxArea(input: Mat, original: Mat): Contour = {
    val contours = coreFindContours(input)
    val contourMetrics = (for (i <- 0 until contours.size) yield {
      val curve = new MatOfPoint2f
      curve.fromArray(contours(i).toList: _*)
      val boundingRect = Imgproc.boundingRect(contours(i))
      val contourArea = Imgproc.contourArea(curve)
      val arcLength = Imgproc.arcLength(curve, true)
      val approxCurve = new MatOfPoint2f
      Imgproc.approxPolyDP(curve, approxCurve, 0.02 * arcLength, true)
      (contourArea, approxCurve, boundingRect)
    })

    val (maxContourArea, maxApproxCurve, boundingRect) = contourMetrics.filter {
      case (contourArea, approxCurve, bRect) => approxCurve.size == new Size(1, 4)
    }.toSeq.sortWith((a, b) => a._1 > b._1).head
    val contourMat = original.submat(new Range(boundingRect.y, boundingRect.y + boundingRect.height), new Range(boundingRect.x, boundingRect.x + boundingRect.width))
    Contour(maxContourArea, maxApproxCurve, boundingRect, contourMat, original, mkPolygon(maxApproxCurve))

  }


  def findCellContour(input: Mat, original: Mat, center: Point, minArea: Double, maxArea: Double): Option[Contour] = {
    val contours = coreFindContours(input)
    (for (i <- 0 until contours.size if ({
      val boundingRect = Imgproc.boundingRect(contours(i))
      val area = boundingRect.area
      boundingRect.contains(center) && (minArea < area) && (area < maxArea)
    })) yield {
      val curve = new MatOfPoint2f
      curve.fromArray(contours(i).toList: _*)
      val boundingRect = Imgproc.boundingRect(contours(i))
      val contourArea = Imgproc.contourArea(curve)
      val arcLength = Imgproc.arcLength(curve, true)
      val approxCurve = new MatOfPoint2f
      Imgproc.approxPolyDP(curve, approxCurve, 0.02 * arcLength, true)
      val contourMat = original.submat(new Range(boundingRect.y, boundingRect.y + boundingRect.height), new Range(boundingRect.x, boundingRect.x + boundingRect.width))
      Contour(contourArea, approxCurve, boundingRect, contourMat, original, mkPolygon(approxCurve))
    }).sortWith((a, b) => a.contourArea > b.contourArea).headOption
  }

  /**
   * sort points in following order:
   * topleft, topright, bottomright, bottomleft
   */
  def rearrangeCorners(points: MatOfPoint2f): Seq[Point] = {
    val sortBySum = points.toList.sortWith((l, r) => (l.x + l.y) < (r.x + r.y))
    val sortByDifference = points.toList.sortWith((l, r) => (l.y - l.x) < (r.y - r.x))
    val (topleft, bottomright) = (sortBySum.head, sortBySum.reverse.head)
    val (topright, bottomleft) = (sortByDifference.head, sortByDifference.reverse.head)
    Seq(topleft, topright, bottomright, bottomleft)
  }

  def adaptiveThreshold(input: Mat, maxValue: Double = 255, blockSize: Int = 5, c: Double = 2, adaptiveMethod: Int = Imgproc.ADAPTIVE_THRESH_MEAN_C): Mat = {
    val thresholded = new Mat()
    Imgproc.adaptiveThreshold(input, thresholded, maxValue, adaptiveMethod, Imgproc.THRESH_BINARY, blockSize, c)
    thresholded
  }

  def threshold(input: Mat) = {
    val output = new Mat
    Imgproc.threshold(input, output, 30, 255, Imgproc.THRESH_BINARY)
    output
  }

  def bitwiseNot(input: Mat): Mat = {
    val output = new Mat
    Core.bitwise_not(input, output)
    output
  }

  def mkKernel(size: Int, kernelData: ArrayBuffer[Byte]) = {
    val kernel = new Mat(size, size, CvType.CV_8U)
    kernel.put(0, 0, kernelData.toArray)
    kernel
  }

  def dilate(input: Mat): Mat = {
    val output = new Mat
    Imgproc.dilate(input, output, mkKernel(3, ArrayBuffer[Byte](0, 1, 0, 1, 1, 1, 0, 1, 0)))
    output
  }

  def erode(input: Mat): Mat = {
    val output = new Mat
    Imgproc.erode(input, output, mkKernel(3, ArrayBuffer[Byte](0, 1, 0, 1, 1, 1, 0, 1, 0)))
    output
  }

  def blur(input: Mat): Mat = {
    val dest = new Mat()
    Imgproc.GaussianBlur(input, dest, new Size(11, 11), 0)
    dest
  }

  def toGray(input: Mat): Mat = new Mat(input.size, CvType.CV_8UC1)

  def loadNativeLibs() = {
    val nativeLibName = if (runOnMac) "/opt/local/share/OpenCV/java/libopencv_java244.dylib" else "c:/openCV/build/java/x64/opencv_java244.dll"
    System.load(new File(nativeLibName).getAbsolutePath())
  }

  def readImage(file: File, cvType: Int): Mat = {
    Highgui.imread(file.getAbsolutePath(), cvType)
  }

  def filter2D(kernel: Mat)(input: Mat): Mat = {
    val out = new Mat
    Imgproc.filter2D(input, out, -1, kernel)
    out
  }

  def toImage(mat: Mat): Try[Image] =
    try {
      val memory = new MatOfByte
      Highgui.imencode(".png", mat, memory)
      Success(new Image(new ByteArrayInputStream(memory.toArray())))
    } catch {
      case e: Throwable => Failure(e)
    }

}

trait JfxUtils {

  def mkSlider(min: Int, max: Int, initialValue: Int, orientation: Orientation): Slider = {
    require(min <= initialValue)
    require(initialValue <= max)
    val slider = new Slider()
    slider.setMin(min)
    slider.setMax(max)
    slider.setValue(initialValue)
    slider.setShowTickLabels(true)
    slider.setShowTickMarks(true)
    slider.setBlockIncrement(1)
    slider.setOrientation(orientation)
    slider
  }

  def mkChangeListener[T](onChangeAction: (ObservableValue[_ <: T], T, T) => Unit): ChangeListener[T] = {
    new ChangeListener[T]() {
      override def changed(observable: ObservableValue[_ <: T], oldValue: T, newValue: T) = {
        onChangeAction(observable, oldValue, newValue)
      }
    }
  }

  def mkListChangeListener[E](onChangedAction: ListChangeListener.Change[_ <: E] => Unit) = new ListChangeListener[E] {
    def onChanged(changeItem: ListChangeListener.Change[_ <: E]): Unit = {
      onChangedAction(changeItem)
    }
  }

  def mkCellFactoryCallback[T](listCellGenerator: ListView[T] => ListCell[T]) = new Callback[ListView[T], ListCell[T]]() {
    override def call(list: ListView[T]): ListCell[T] = listCellGenerator(list)
  }

  def mkEventHandler[E <: Event](f: E => Unit) = new EventHandler[E] {
    def handle(e: E) = f(e)
  }

  def mkTask[X](callFn: => X): Task[X] = new Task[X] {
    override def call(): X = callFn
  }

}

object Sudoku2go {


  def main(args: Array[String]): Unit = {
    Application.launch(classOf[Sudoku2go], args: _*)
  }

}

trait Sudokuaner extends OpenCVUtils with JfxUtils {

  val cellSize = 60
  val degree = 9
  val sudokuSize = cellSize * degree

  case class DetectionResult(nrDetections: Int, distance: Int,
                             descriptorMatcher: DescriptorMatcherType,
                             descriptorExtractor: DescriptorExtractorType,
                             featureDetector: FeatureDetectorType) {
    override def toString = s"($nrDetections, $distance, $descriptorMatcher, $descriptorExtractor, $featureDetector)"
  }

  /**
   * for a given mat, returns the contour with the max area, whilst the contour has 4 sides
   *
   * (if you happen to look at a sudoku, it is likely to get the whole sudoku frame with this trick)
   */
  def findMaxArea(input: Mat): (Double, Seq[Point]) = {
    val maxContour = findContourWithMaxArea(input, input)
    (maxContour.contourArea, rearrangeCorners(maxContour.approxCurve))
  }

  def equalizeHist(input: Mat): Mat = {
    val output = new Mat
    Imgproc.equalizeHist(input, output)
    output
  }

  /**
   * Returns a Mat derived from the input with several operations applied.
   *
   * @param input
   * @return
   */
  def preprocess2(input: Mat): Mat = {
    val equalized = equalizeHist(input)
    val blurred = blur(equalized)
    val thresholded = threshold(blurred)
    val inverted = bitwiseNot(thresholded)
    inverted
  }

  def preprocess(input: Mat): Mat = {
    val blurred = blur(input)
    val thresholdApplied = adaptiveThreshold(blurred)
    val inverted = bitwiseNot(thresholdApplied)
    val dilated = dilate(inverted)
    dilated
  }


  abstract sealed trait SCell

  case object EmptyCell extends SCell {
    override def toString = " "
  }

  /**
   * The image processing produces 81 instances of this class
   */
  case class SudokuCell(column: Int,
                        row: Int,
                        border: Polygon,
                        value: Int,
                        contour: Contour,
                        cellData: Mat) extends SCell {
    override def toString = value.toString
  }

  case class SolvedCell(column: Int, row: Int, value: Int, someCell: Option[Mat]) extends SCell {

    def mkRepresentation(cellWidth: Float, cellHeight: Float) =
      option(someCell)(mkLabel(cellWidth, cellHeight), cell => mkImage(cell, cellWidth, cellHeight))

    def mkImage(cell: Mat, cellWidth: Float, cellHeight: Float): Node = {
      toImage(cell) match {
        case Success(image) => {
          println("h:%s, w: %s /// %s / %s".format(image.getHeight, image.getWidth, cellHeight, cellWidth))

          val iv = new ImageView(image)
          val x = column * cell.size.width
          val y = row * cell.size.height
          iv.setTranslateX(x)
          iv.setTranslateY(y)
          iv
        }
        case Failure(e) => mkLabel(cellWidth, cellHeight)
      }
    }

    def mkLabel(cellWidth: Float, cellHeight: Float): Node = {
      val l = new Label(value.toString)
      val x = column * cellWidth + cellWidth / 3
      val y = row * cellHeight + cellHeight / 6
      l.setTranslateX(x)
      l.setTranslateY(y)
      l.setFont(Font.font("Verdana", cellHeight * 2 / 3))
      l.setTextFill(Color.BLACK)
      l
    }
  }

  def loadLibImages(path: File): Map[Int, Set[File]] = {
    (for (i <- 1 to 9) yield {
      i -> new File(path, "%s/".format(i)).list(new FilenameFilter {
        def accept(dir: File, name: String): Boolean = name.endsWith(".png")
      }).map(x => new File(path, "%s/%s".format(i, x))).toSet
    }).toMap
  }

  /**
   * Creates a map of descriptors
   *
   * @param path  base path containing the subdirectories ranging from 1 to 9 which contain training data respectively
   * @param featureDetectorType optional parameter to specify the feature detector used, defaults to SIFT
   * @param descriptorExtractorType optional parameter to specify the descriptorExtractor used, defaults to SIFT
   * @return
   */
  def mkComparisonLibrary(path: File,
                          featureDetectorType: FeatureDetectorType = FeatureDetector.SIFT,
                          descriptorExtractorType: DescriptorExtractorType = DescriptorExtractor.SIFT): Map[Int, Set[Mat]] = {
    (for ((i, fileSet) <- loadLibImages(path)) yield i -> (fileSet.toSeq.map(f =>
      mkDescriptor(mat = preprocess(readImage(f, CvType.CV_8UC1)),
        featureDetectorType = FeatureDetector.SIFT,
        descriptorExtractorType = DescriptorExtractor.SIFT
      ))).toSet).toMap
  }

  def mkTemplateLibrary(path: File): Map[Int, Set[Mat]] = {
    for ((i, fileSet) <- loadLibImages(path)) yield i -> fileSet.map(readImage(_, CvType.CV_8UC1))
  }

  /**
   * write sudoku matrix from cells (in obfuscated mode ;-) )
   *
   * @param cells
   */
  def toString(cells: Seq[SCell]): String = {

    def toStringList(cells: Seq[SCell]): List[String] = {
      cells match {
        case Nil => Nil
        case x => cells.take(9).mkString("|", "|", "|") :: toStringList(cells.drop(9))
      }
    }


    (((for (i <- 1 to 9) yield ("-" * 9).toList.mkString("|", "|", "|")).toList zip
      toStringList(cells)).map(x => List(x._1, x._2)).flatten ++ List(("-" * 9).toList.mkString("|", "|", "|"))).mkString("\n")
  }

  def filterKnownCells(cells: Seq[SCell]): Seq[SCell] = {
    cells.filter(_ match {
      case s: SudokuCell => true
      case _ => false
    })
  }

  def mkCorners(mat: Mat): Seq[Point] = {
    val (width, height) = (mat.size.width, mat.size.height)
    List(new Point(0, 0), new Point(width, 0), new Point(width, height), new Point(0, height))
  }

  def warp(input: Mat): (Mat, Seq[Point]) = {
    val preprocessed = preprocess(input)
    val (maxArea, srcCorners) = findMaxArea(preprocessed)
    (warp(input, srcCorners, mkCorners(input)), srcCorners)
  }


  def calcBlockSize(input: Mat, nrCells: Int): (Int, Int) = {
    val (matWidth, matHeight) = (input.size().width.toInt, input.size.height.toInt)
    val (blockWidth, blockHeight) = ((matWidth / nrCells).toInt, (matHeight / nrCells).toInt)
    (blockWidth, blockHeight)
  }


  // filter out false positives
  // use information known (size, position of digits)
  // the bounding box of the contours must fit into some rough predicate, like follows:
  // the area must be of a certain size
  // the area must not be greater than a certain size
  // the center of the image has to be part of the bounding rectangle
  def extractContour(cellRawData: Mat): Option[Contour] = {
    // only search for contours in a subrange of the original cell to get rid of possible border lines
    val (width, height) = (cellRawData.size.width, cellRawData.size.height)
    val cellData = new Mat(cellRawData, new Range((height * 0.1).toInt, (height * 0.9).toInt), new Range((width * 0.1).toInt, (width * 0.9).toInt))
    val cellArea = cellData.size().area
    val (minArea, maxArea) = (0.15 * cellArea, 0.5 * cellArea)
    val (centerX, centerY) = (cellData.size.width / 2, cellData.size.height / 2)
    findCellContour(preprocess2(cellData), preprocess2(cellData), new Point(centerX, centerY), minArea, maxArea)
  }

  /**
   * Uses a feature extraction approach to detect the numbers for a given contour.
   *
   * @param comparisonLibrary
   * @param descriptorMatcherType
   * @param featureDetectorType
   * @param descriptorExtractorType
   * @param contour
   * @return
   */
  def withFeatureExtraction(comparisonLibrary: Map[Int, Set[Mat]],
                            descriptorMatcherType: DescriptorMatcherType = DescriptorMatcher.FLANNBASED,
                            featureDetectorType: FeatureDetectorType = FeatureDetector.SIFT,
                            descriptorExtractorType: DescriptorExtractorType = DescriptorExtractor.SIFT
                             )(contour: Contour): Int = {
    val queryDescriptor = mkDescriptor(contour.resizedNumberData, featureDetectorType, descriptorExtractorType)
    val matcher = DescriptorMatcher.create(descriptorMatcherType)

    // compare them and fill up the result matrix
    val results =
      (for {(i, trainDescriptorSet) <- comparisonLibrary
            trainDescriptor <- trainDescriptorSet
            if (trainDescriptor.`type`() == queryDescriptor.`type`())
      } yield {
        val matOfDMatch = new MatOfDMatch
        matcher.`match`(queryDescriptor, trainDescriptor, matOfDMatch)
        (i, matOfDMatch.toList.map(_.distance).min)
      }).toSeq

    val guess0 = (for ((i, minDistance) <- results)
    yield (i, minDistance)).toSeq.sortWith((a, b) => a._2 < b._2).headOption.getOrElse((-1, 10f))._1
    guess0
  }

  def withTemplateMatching(templateLibrary: Map[Int, Set[Mat]])(contour: Contour): Int = {
    (for {(i, needles) <- templateLibrary
          needle <- needles} yield {
      val (quality, point) = matchTemplate(contour.resizedNumberData, needle)
      List((i, quality, point))
    }).flatten.toSeq.sortWith((a, b) => a._2 < b._2).head._1
  }


  /**
   * This function returns the warped mat and a sequence (from top left to right bottom) of cells containing information
   * for the different cells. You can provide a custom method to detect and analyze contours
   *
   * @param input
   * @param detectNumberMethod
   * @return
   */
  def mkSudoku(input: Mat,
               widthFactor: Double = 1,
               heightFactor: Double = 1,
               detectNumberMethod: Contour => Int): (Mat, Seq[Point], Seq[SCell]) = {
    val (warped, corners) = warp(input)
    val futureCells = {
      val (blockWidth, blockHeight) = calcBlockSize(warped, degree)
      (for {row <- 0 until degree
            column <- 0 until degree} yield future {
        val (xl, xr, yl, yr) = (column * blockWidth,
          (column + 1) * blockWidth,
          row * blockHeight,
          (row + 1) * blockHeight)

        val (pxl, pxr, pyl, pyr) = ((xl * widthFactor).toInt,
          (xr * widthFactor).toInt,
          (yl * heightFactor).toInt,
          (yr * heightFactor).toInt)

        val cellData = warped.submat(new Range(yl, yr), new Range(xl, xr))

        option(extractContour(cellData))(EmptyCell,
          contour => {
            SudokuCell(column = column,
              row = row,
              border = {
                val poly = new Polygon(
                  pxl + 1, pyl + 1,
                  pxr, pyl + 1,
                  pxr, pyr,
                  pxl + 1, pyr)
                poly.setStroke(Color.RED)
                poly.setFill(Color.AZURE)
                poly.setOpacity(0.5)
                poly
              },
              value = detectNumberMethod(contour),
              contour = contour.copy(poly = {
                val p = contour.poly
                val g = new DropShadow
                p.setEffect(g)
                val corrX = if (column >= degree - 2) -(cellSize * degree / 9) * 2 else 0
                val corrY = if (row >= degree - 2) -(cellSize * degree / 9) * 2 else 0
                p.setTranslateX(pxl + corrX)
                p.setTranslateY(pyl + corrY)
                p
              }),
              cellData = cellData)
          })
      })
    }
    val scaledCorners = corners.map(p => new Point(p.x * widthFactor, p.y * heightFactor))
    val cells = for (fc <- futureCells) yield Await.result(fc, 1000 millis)


    (warped, scaledCorners, cells)
  }

}


class Sudoku2go extends Application with JfxUtils with OpenCVUtils with Sudokuaner with SudokuSolver {

  override def init(): Unit = loadNativeLibs // important to have this statement on the "right" thread

  def updateSudokuCellView(stackPane: Group,
                           cellView: ImageView,
                           sudokuCell: SudokuCell): Unit = {

    // remove all except background image
    if (stackPane.getChildren.size > 1) {
      val bg = stackPane.getChildren.get(0)
      stackPane.getChildren.clear
      stackPane.getChildren.add(bg)
    }

    stackPane.getChildren.addAll(sudokuCell.border, sudokuCell.contour.poly)
    updateImageView(cellView)(sudokuCell.contour.cellMat)
    new BounceTransition(sudokuCell.contour.poly).play
  }

  def mkPolyLine(points: Seq[Point]): Polyline = {
    val line = new Polyline()
    line.setStroke(Color.BLUE)
    line.setStrokeWidth(5)
    line.setEffect(new DropShadow())
    val ps = points.map(p => List[java.lang.Double](p.x, p.y)).flatten.toList
    line.getPoints.addAll(ps ++ List(ps(0), ps(1)))
    line

  }

  def isSudokuCell(c: SCell): Boolean = {
    c match {
      case s: SudokuCell => true
      case _ => false
    }
  }

  /**
   * from a given list of cells, extract image information for every digit ranging from 1 to 9. if they are not
   * available (which may be the case but is rather uncommon) we will implement a fallback
   *
   * @param cells
   * @return
   */
  def mkDigitLibrary(cells: Seq[SCell]): Map[Int, Mat] = {
    val sudokuCells = for (c <- cells if (isSudokuCell(c))) yield c.asInstanceOf[SudokuCell]
    val byValue = sudokuCells.groupBy(s => s.value)
    val seqOfSudokuCells = for (i <- 1 to 9 if (byValue.contains(i))) yield (i, byValue(i).head.cellData)
    seqOfSudokuCells.toMap
  }

  /**
   * Given an input matrix and a detection method, calculates the original position of the sudoku and provides a solution,
   * along with the warped input
   *
   * @param input
   * @param detectionMethod
   * @param sudokuSize
   * @return
   */
  def coreCalc(input: Mat, detectionMethod: Contour => Int, sudokuSize: Int): Try[(Mat, Seq[Point], Seq[SolvedCell])] = {
    val (inputWidth, inputHeight) = (input.size.width, input.size.height)
    // val (widthFactor, heightFactor) = ((sudokuSize / inputWidth), (sudokuSize / inputHeight))
    val (widthFactor, heightFactor) = ((inputWidth / inputWidth), (inputHeight / inputHeight))
    val (warped, corners, cells) = mkSudoku(input, widthFactor, heightFactor, detectionMethod)
    toImage(input) match {
      case Success(inputImage) =>
        try {
          val knownCells = filterKnownCells(cells)
          val digitLibrary = mkDigitLibrary(knownCells)

         // println("Detected cells: %s".format(knownCells.size))
          if (knownCells.size >= 20) {

            val sudokuAsString = toSolverString(knownCells)
            val solvedString = solve(sudokuAsString)

            val allCells = toSolutionCells(digitLibrary, solvedString)
            val solutionCells = allCells.filterNot(c => knownCells.exists(x => x match {
              case SudokuCell(col, row, _, _, _, _) => ((row == c.row) && (col == c.column))
              case _ => false
            }))
            println
            println(("D %s, S %s A %s".format(knownCells.size, solutionCells.size, knownCells.size + solutionCells.size)))
            println(solvedString)
            println
            Success((warped, corners, solutionCells))
          } else {
            Failure(new RuntimeException("Couldn't detect enough digits."))
          }
        } catch {
          case e: Throwable => Failure(e)
        }
      case Failure(e) => Failure(e)
    }
  }


  def calcSudoku(input: Mat, detectionMethod: Contour => Int): Try[Group] = {
    coreCalc(input, detectionMethod, sudokuSize) match {
      case Success((warped, corners, solution)) => {

        val outputImage = toImage(warped) match {
          case Success(image) => image
          case Failure(_) => null
        }

        val outputView = new ImageView()
        outputView.imageProperty.set(outputImage)
        val centerBox = new HBox

        // using javafx to overlay (should use opencv's mat i guess)
        val outputGroup = new Group
        outputGroup.getChildren.add(outputView)
        outputGroup.getChildren.addAll(solution.map(_.mkRepresentation(cellSize, cellSize)))

        // create snapshot from solution
        // major hackativity
        val i = outputGroup.snapshot(new SnapshotParameters, null)
        val f = File.createTempFile("sudoku", "png")
        f.deleteOnExit
        ImageIO.write(SwingFXUtils.fromFXImage(i, null), "png", f)
        val solMat = readImage(f, CvType.CV_8UC1)
        val warpedSolutionMat = warp(solMat, mkCorners(solMat), corners)

        val solutionView = new ImageView
        solutionView.setBlendMode(BlendMode.COLOR_DODGE) // works well enough
        val inputGroup = new Group
        val inputView = new ImageView
        val cornerLines = mkPolyLine(corners)

        inputGroup.getChildren.addAll(inputView, solutionView, cornerLines)
        centerBox.getChildren.addAll(inputGroup)

        val cellView = new ImageView
        updateImageView(inputView)(input)
        updateImageView(solutionView)(warpedSolutionMat)
        updateImageView(outputView)(warped)

        Success(inputGroup)
      }
      case Failure(f) => Failure(f)
    }
  }

  def toSolutionCells(digitLibrary: Map[Int, Mat], solverString: String): Seq[SolvedCell] = {
    (for {
      r <- 0 to 8
      c <- 0 to 8
    } yield {
      val value = solverString.replaceAll( """\n""", "")(r * 9 + c).asDigit
      new SolvedCell(c, r, value, digitLibrary.get(value))
    })
  }


  def toSolverString(knownCells: Seq[SCell]): String = {
    val kCells = knownCells.map(c => c match {
      case SudokuCell(col, row, _, value, _, _) => Some((col, row) -> value.toString)
      case _ => None
    }).flatten.toMap

    (for (r <- 0 to 8) yield {
      (for (c <- 0 to 8) yield kCells.getOrElse((c, r), "0")).foldLeft("")((a, b) => a + b) + "\n"
    }).foldLeft("")((a, b) => a + b)
  }


  def colorSpace(input: Mat): Mat = {
    val colorTransformed = new Mat
    Imgproc.cvtColor(input, colorTransformed, Imgproc.COLOR_BGR2GRAY)
    colorTransformed
  }

  override def start(stage: Stage): Unit = {
    stage.setTitle("JavaFX OpenCV Scala Sudoku")
    val imagePath = new File("src/test/resources/kleinezeitung/examples/")
    val libraryPath = new File("src/test/resources/kleinezeitung/lib/")
    lazy val templateLibrary: Map[Int, Set[Mat]] = mkTemplateLibrary(libraryPath)

    val canvas = new BorderPane

    val imageService = new WebcamService
    imageService.setOnSucceeded(
      mkEventHandler(
        event => {
          time({
            val image2process = colorSpace(event.getSource.getValue.asInstanceOf[Mat])
            calcSudoku(image2process, withTemplateMatching(templateLibrary)) match {
              case Success(centerBox) => {
                canvas.setCenter(centerBox)
              }
              case Failure(e) => {
                Platform.runLater(
                  new Runnable() {
                    def run = {
                      toImage(image2process) match {
                        case Success(i) => {
                          val iv = new ImageView(i)
                          canvas.setCenter(iv)
                          val eL = new Label("Sudoku calculation: %s".format(e.getMessage))
                          BorderPane.setAlignment(eL, Pos.CENTER)
                          canvas.setBottom(eL)
                        }
                        case Failure(e) => {
                          canvas.setCenter(new Label(e.getMessage))
                          val eL = new Label("Sudoku calculation: %s".format(e.getMessage))
                          BorderPane.setAlignment(eL, Pos.CENTER)
                          canvas.setBottom(eL)
                        }
                      }
                    }
                  }
                )
              }
            }
            Platform.runLater(
              new Runnable() {
                def run = {
                  imageService.restart
                }
              })
          }, time =>
            println("Calculation %d ms".format(time)))
        }))

    imageService.start


    val scene = new Scene(canvas, 1400, 800)
    stage.setScene(scene)
    stage.show

  }


}


// ported and adapted from
// https://github.com/fxexperience/code/blob/master/FXExperienceControls/src/com/fxexperience/javafx/animation/CachedTimelineTransition.java
class CachedTimelineTransition(node: Node, timeline: Timeline, useCache: Boolean)
  extends Transition with JfxUtils {

  var oldCache = false
  var oldCacheHint = CacheHint.DEFAULT

  statusProperty().addListener(mkChangeListener[Status](
    (observable, oldStatus, newStatus) => newStatus match {
      case Status.RUNNING => starting
      case _ => stopping
    }
  ))

  def starting() {
    if (useCache) {
      oldCache = node.isCache()
      oldCacheHint = node.getCacheHint()
      node.setCache(true)
      node.setCacheHint(CacheHint.SPEED)
    }
  }

  def stopping() {
    if (useCache) {
      node.setCache(oldCache)
      node.setCacheHint(oldCacheHint)
    }
  }

  override def interpolate(d: Double) {
    timeline.playFrom(Duration.seconds(d))
    timeline.stop()
  }

}

// ported and adapted from https://github.com/fxexperience/code/blob/master/FXExperienceControls/src/com/fxexperience/javafx/animation/BounceTransition.java
class BounceTransition(node: Node) extends CachedTimelineTransition(node, {
  val y = node.getTranslateY
  TimelineBuilder.create()
    .keyFrames(
    new KeyFrame(Duration.millis(0), new KeyValue(node.translateYProperty().asInstanceOf[WritableValue[Any]], y + 0, Interpolator.EASE_BOTH)),
    new KeyFrame(Duration.millis(200), new KeyValue(node.translateYProperty().asInstanceOf[WritableValue[Any]], y + 0, Interpolator.EASE_BOTH)),
    new KeyFrame(Duration.millis(400), new KeyValue(node.translateYProperty().asInstanceOf[WritableValue[Any]], y + -0.30 * node.getBoundsInParent().getHeight(), Interpolator.EASE_BOTH)),
    new KeyFrame(Duration.millis(500), new KeyValue(node.translateYProperty().asInstanceOf[WritableValue[Any]], y + 0, Interpolator.EASE_BOTH)),
    new KeyFrame(Duration.millis(600), new KeyValue(node.translateYProperty().asInstanceOf[WritableValue[Any]], y + -0.15 * node.getBoundsInParent().getHeight(), Interpolator.EASE_BOTH)),
    new KeyFrame(Duration.millis(800), new KeyValue(node.translateYProperty().asInstanceOf[WritableValue[Any]], y + 0, Interpolator.EASE_BOTH)),
    new KeyFrame(Duration.millis(1000), new KeyValue(node.translateYProperty().asInstanceOf[WritableValue[Any]], y + 0, Interpolator.EASE_BOTH))
  )
    .build()
}
, false
) {
  setCycleDuration(Duration.seconds(1))
  setDelay(Duration.seconds(0.0))
}


/**
 * The following code is the first google hit for "scala sudoku solver", adapted to compile with scala 2.10
 * I hope the author ( doesn't mind me reusing the code for educational purposes.
 *
 * http://scala-programming-language.1934581.n4.nabble.com/25-lines-Sudoku-solver-in-Scala-td1987506.html
 *
 * Also don't miss the very nice essay from peter norvig on solving sudokus
 *
 * http://norvig.com/sudoku.html
 *
 */
trait SudokuSolver {

  /**
   * give this function a sudoku in the form
   *
   * 200080300
   * 060070084
   * 030500209
   * 000105408
   * 000000000
   * 402706000
   * 301007040
   * 720040060
   * 004010003
   *
   * and it will return the solved sudoku (with zeros)
   *
   * @param stringRep
   * @return
   */
  def solve(stringRep: String): String = {
    // The board is represented by an array of strings (arrays of chars),
    // held in a global variable mx. The program begins by reading 9 lines
    // of input to fill the board
    val mx: Array[Array[Char]] = stringRep.stripMargin.split("\n").map(_.trim.toArray)

    var solution = new ListBuffer[String]()

    def print = {
      mx map (carr => {
        solution.append(new String(carr))
      })
    }

    // The test for validity is performed by looping over i=0..8 and
    // testing the row, column and 3x3 square containing the given
    // coordinate
    def invalid(i: Int, x: Int, y: Int, n: Char): Boolean =
      i < 9 && (mx(y)(i) == n || mx(i)(x) == n ||
        mx(y / 3 * 3 + i / 3)(x / 3 * 3 + i % 3) == n || invalid(i + 1, x, y, n))

    // Looping over a half-closed range of consecutive integers [l..u)
    // is factored out into a higher-order function
    def fold(f: (Int, Int) => Int, accu: Int, l: Int, u: Int): Int =
      if (l == u) accu else fold(f, f(accu, l), l + 1, u)

    // The search function examines each position on the board in turn,
    // trying the numbers 1..9 in each unfilled position
    // The function is itself a higher-order fold, accumulating the value
    // accu by applying the given function f to it whenever a solution m
    // is found
    def search(x: Int, y: Int, f: (Int) => Int, accu: Int): Int = Pair(x, y) match {
      case Pair(9, y) => search(0, y + 1, f, accu) // next row
      case Pair(0, 9) => {
        f(accu)
      } // found a solution
      case Pair(x, y) => if (mx(y)(x) != '0') search(x + 1, y, f, accu)
      else
        fold((accu: Int, n: Int) =>
          if (invalid(0, x, y, (n + 48).asInstanceOf[Char])) accu
          else {
            mx(y)(x) = (n + 48).asInstanceOf[Char];
            val newaccu = search(x + 1, y, f, accu);
            mx(y)(x) = '0';
            newaccu
          }, accu, 1, 10)
    }

    // The main part of the program uses the search function to accumulate
    // the total number of solutions
    search(0, 0, i => {
      print;
      i + 1
    }, 0)

    solution.toList.mkString("\n")
    // thats all ;-)
  }

}


class WebcamService extends Service[Mat] with OpenCVUtils with JfxUtils {

  val videoCapture: VideoCapture = new VideoCapture(0)

  def takeImage: Mat = {
    val image = new Mat()
    while (videoCapture.read(image) == false) {}
    image
  }

  def sourceMat: Mat = {
    assert(videoCapture.isOpened())
    if (videoCapture.grab) {
      takeImage
    } else
      throw new RuntimeException("Couldn't grab image!")
  }

  def createTask = mkTask(sourceMat)

}