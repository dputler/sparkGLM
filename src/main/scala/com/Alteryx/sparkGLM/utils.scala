package com.Alteryx.sparkGLM

import breeze.linalg._
import breeze.numerics._

import edu.berkeley.cs.amplab.mlmatrix.{NormalEquations, RowPartitionedMatrix}
import edu.berkeley.cs.amplab.mlmatrix.util.Utils
import edu.berkeley.cs.amplab.mlmatrix.NormalEquations._

import org.apache.spark.sql.{DataFrame, Column}
import org.apache.spark.sql.functions.{col, lit}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.DoubleType

object utils {

  //
  // DataFrame utility functions
  //

  def matchCols (estDF: DataFrame, scoreDF: DataFrame): DataFrame = {
    val missingCols: Array[Column] = estDF.columns.diff(scoreDF.columns).map { field =>
      lit(0).cast(DoubleType).as(field)
    }
    scoreDF.select(missingCols :+ col("*"):_*)
  }

  // HOLDING ON TO THIS FOR NOW, IT DEPENDS IF THINGS MAKE IT ACROSS FROM mlmatrix
  def dataFrameToMatrix(df: DataFrame): RDD[DenseMatrix[Double]] = {
    val matrixRDD = df.map(x => x.toSeq.toArray).map(y => y.map(z => z.asInstanceOf[Double]))
    RowPartitionedMatrix.arrayToMatrix(matrixRDD)
  }

  // Convert a single partitioned DataFrame into a DenseMatrix
  def dfToDenseMatrix(df: DataFrame): DenseMatrix[Double] = {
    require(df.rdd.partitions.size == 1,
      "The DataFrame must be in a single partition")
    require(df.dtypes.forall(_._2 == "DoubleType"),
      "The provided DataFrame must contain all 'DoubleType' columns")
    val dataArray = dataFrameToMatrix(df).collect
    dataArray(0)
  }


  //
  // Modelling support methods
  //

  // From ml-matrix, the reducer function to sum together the partitons of X'X
  // and X'y
  def reduceNormal(
    a: (DenseMatrix[Double], DenseMatrix[Double]),
    b: (DenseMatrix[Double], DenseMatrix[Double])): (DenseMatrix[Double], DenseMatrix[Double]) = {
    a._1 :+= b._1
    a._2 :+= b._2
    a
  }

  // A method to multiple a left-hand side matrix by a diagonal matrix
  // without actually creating the diagnol matrix that may not fit into memory
  def leftMultDiag(
    left: DenseMatrix[Double],
    dcol: DenseMatrix[Double]): DenseMatrix[Double] = {
      require(dcol.cols == 1,
        "The argument dcol must contain only a single column")
      val nCols = left.cols - 1
      val dcolArray = dcol.toArray
      val newLeft = new DenseMatrix(left.rows, left.cols, left.toArray)
      for (i <- 0 to nCols) {
        newLeft(::, i) :*= dcolArray(i)
      }
      newLeft
  }
  // A case class for a minimal weighted least squares fit object
  case class WLSObj(coefs: DenseMatrix[Double], diagDesign: DenseVector[Double])

  // A method to do a weighted least squares fit to data in a single partition
  def wlsSingle (
      X: DenseMatrix[Double],
      y: DenseMatrix[Double],
      w: DenseMatrix[Double]): WLSObj = {
    val XtW = leftMultDiag(X.t, w)
    val XtWXi = inv(XtW * X)
    val XtWy = XtW * y
    val coefs = XtWXi * XtWy
    val diagDesign = sqrt(diag(XtWXi))
    new WLSObj(coefs = coefs, diagDesign = diagDesign)
  }

  // A method to get the coponents needed to do WLS on RowPartionedMatrix objects
  def wlsComponents (
      X: RowPartitionedMatrix,
      y: RowPartitionedMatrix,
      w: RowPartitionedMatrix): (DenseMatrix[Double], DenseMatrix[Double]) = {
    val Xyw = X.rdd.zip(y.rdd).zip(w.rdd).map {
      case((a, b), c) => (a.mat, b.mat, c.mat)
    }
    val XtWX_XtWy = Xyw.map { part =>
      (leftMultDiag(part._1.t, part._3) * part._1,
        leftMultDiag(part._1.t, part._3) * part._2)
    }

    val treeBranchingFactor = X.rdd.context.getConf.getInt("spark.mlmatrix.treeBranchingFactor", 2).toInt
    val depth = math.ceil(math.log(XtWX_XtWy.partitions.size)/math.log(treeBranchingFactor)).toInt
    val reduced = edu.berkeley.cs.amplab.mlmatrix.util.Utils.treeReduce(XtWX_XtWy, utils.reduceNormal, depth=depth)

    reduced
  }

  // A method to do a weighted least squares fit to data in multiple partitions
  def wlsMultiple (
      X: RowPartitionedMatrix,
      y: RowPartitionedMatrix,
      w: RowPartitionedMatrix): WLSObj = {
    val components = wlsComponents(X, y, w)
    val XtWXi = inv(components._1)
    val coefs = XtWXi * components._2
    val diagDesign = sqrt(diag(XtWXi))
    new WLSObj(coefs = coefs, diagDesign = diagDesign)
  }


  //
  // Printing support methods
  //

  // A method to round a number to a desired number of decimal places
  def roundDigits(num: Double, digits: Int): Double = {
    val top = (scala.math.round(num*scala.math.pow(10, digits))).toLong
    top/scala.math.pow(10, digits)
  }

  // A method to reduce Doubles to a desired number of significant digits for
  // print purposes. Based on Pyrolistical response to:
  // http://stackoverflow.com/questions/202302/rounding-to-an-arbitrary-number-of-significant-digits
  def sigDigits(num: Double, digits: Int): Double = {
    if (num == 0) {
      0.0
    } else {
      val absNum = scala.math.abs(num)
      val d = scala.math.ceil(scala.math.log10(absNum))
      val power = (digits - d).toInt
      val magnitude = scala.math.pow(10, power)
      val shifted = (scala.math.round(absNum*magnitude)).toLong
      if (num > 0) {
        shifted/magnitude
      } else {
        -1.0*shifted/magnitude
      }
    }
  }

  //
  // Miscellaneous functions
  //
    def sign(num: Double): Double = {
      val theSign = {
        if (num < 0.0) {
          -1.0
        }
        else if (num > 0.0) {
          1.0
        }
        else {
          0.0
        }
      }
      theSign
    }

    def vecSigns(vec: DenseMatrix[Double]): DenseMatrix[Double] = {
      require(vec.cols == 1, "The provided DenseMatrix can only have a single column")
      val vecArray = vec.toArray
      val signs = vecArray.map(x => sign(x))
      new DenseMatrix(rows = signs.size, cols = 1, signs)
    }

    def repValue(value: Double, nrow: Int): DenseMatrix[Double] = {
      require(nrow > 0, "The number of rows must be strictly greater than 0")
      DenseVector.fill(nrow){value}.toDenseMatrix.t
    }
}
