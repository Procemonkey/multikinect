package edu.bu.vip.multikinect.sync;

import java.io.IOException;
import java.util.List;

import org.ejml.data.DenseMatrix64F;
import org.ejml.equation.Equation;
import org.ejml.simple.SimpleMatrix;
import org.ejml.simple.SimpleSVD;

import edu.bu.vip.multikinect.Protos.Frame;

public class CoordinateTransform {

  private static final int FRAME_WINDOW = 10;

  public static class Transform {
    private final DenseMatrix64F transform;
    private final DenseMatrix64F rotation;
    private final DenseMatrix64F translation;
    private final double error;
    private final DenseMatrix64F xData;
    private final DenseMatrix64F yData;

    public Transform(DenseMatrix64F transform, DenseMatrix64F rotation, DenseMatrix64F translation,
        double error, DenseMatrix64F xData, DenseMatrix64F yData) {
      this.transform = transform;
      this.rotation = rotation;
      this.translation = translation;
      this.error = error;
      this.xData = xData;
      this.yData = yData;
    }

    public DenseMatrix64F getTransform() {
      return transform;
    }

    public DenseMatrix64F getRotation() {
      return rotation;
    }

    public DenseMatrix64F getTranslation() {
      return translation;
    }

    public double getError() {
      return error;
    }

    public DenseMatrix64F getxData() {
      return xData;
    }

    public DenseMatrix64F getyData() {
      return yData;
    }
  }

  public static Transform calculateTransform(String fileA, String fileB) throws IOException {

    // Find the min change between two frames in each stream
    List<Frame> minA = FrameReader.readAllFrames(fileA);
    List<Frame> minB = FrameReader.readAllFrames(fileB);

    Transform minRT = null;
    for (int i = 0; i < minA.size() - FRAME_WINDOW; i++) {

      for (int j = 0; j < minB.size() - FRAME_WINDOW; j++) {

        SimpleMatrix combinedA = null;
        SimpleMatrix combinedB = null;
        boolean calculate = true;
        for (int k = 0; k < FRAME_WINDOW; k++) {
          Frame frameA = minA.get(i + k);
          Frame frameB = minB.get(j + k);

          if (frameA.getSkeletonsCount() != frameB.getSkeletonsCount()) {
            calculate = false;
            break;
          } else if (frameA.getSkeletonsCount() > 0) {
            boolean[] jointMask = FrameUtils.joinJointMasks(FrameUtils.jointMasks(frameA),
                FrameUtils.jointMasks(frameB));

            double[] dataA = FrameUtils.jointMatrix(frameA, jointMask);
            DenseMatrix64F denseA = new DenseMatrix64F(dataA.length / 3, 3, true, dataA);
            double[] dataB = FrameUtils.jointMatrix(frameB, jointMask);
            DenseMatrix64F denseB = new DenseMatrix64F(dataB.length / 3, 3, true, dataB);

            if (combinedA == null) {
              combinedA = new SimpleMatrix(denseA);
              combinedB = new SimpleMatrix(denseB);
            } else {
              combinedA = combinedA.combine(combinedA.numRows(), 0, new SimpleMatrix(denseA));
              combinedB = combinedB.combine(combinedB.numRows(), 0, new SimpleMatrix(denseB));
            }
          }
        }

        if (calculate) {
          Transform transform = calculateTransform(combinedA.getMatrix(), combinedB.getMatrix());

          if (minRT == null || transform.getError() < minRT.getError()) {
            minRT = transform;
          }
        }
      }
    }

    return minRT;
  }

  public static Transform calculateTransform(DenseMatrix64F denseA, DenseMatrix64F denseB) {
    DenseMatrix64F meanMatA = calculateMeanMatrix(denseA);
    DenseMatrix64F meanMatB = calculateMeanMatrix(denseB);

    Equation eq = new Equation();
    eq.alias(denseA, "X", denseB, "Y");
    eq.alias(meanMatA, "Xm", meanMatB, "Ym");
    eq.alias(denseA.numRows, "rowsX", denseB.numRows, "rowsY");
    // eq.process("Xm = mean(X,1)");
    eq.process("X1 = X - ones(rowsX,1)*Xm");
    // eq.process("Ym = mean(Y,1)");
    eq.process("Y1 = Y - ones(rowsY,1)*Ym");
    eq.process("XtY = (X1')*Y1");
    // eq.process("[U,S,V] = svd(XtY)");
    SimpleMatrix xTY = new SimpleMatrix(eq.lookupMatrix("XtY"));
    SimpleSVD<?> svd = xTY.svd();
    eq.alias(svd.getU(), "U", svd.getV(), "V", svd.getW(), "S");
    eq.process("R = U*(V')");
    eq.process("T = Ym - Xm*R");
    eq.process("Yf = X*R + ones(rowsX,1)*T");
    eq.process("dY = Y - Yf");
    eq.process("R = R'");
    eq.process("T = T'");
    eq.process("RT = [R, T; [0,0,0,1]]");
    // eq.process("Err = norm(dY,'fro')");
    DenseMatrix64F rotation = eq.lookupMatrix("RT");
    SimpleMatrix dY = new SimpleMatrix(eq.lookupMatrix("dY"));

    // Error is the average absolute distance between each pair of points
    final double numberOfPoints = denseA.numRows;
    double sum = 0;
    for (int i = 0; i < dY.numRows(); i++) {
      double dx = dY.get(i, 0);
      double dy = dY.get(i, 1);
      double dz = dY.get(i, 2);
      sum +=  Math.sqrt(dx * dx  + dy * dy + dz * dz);
    }
    final double error = sum / numberOfPoints;

    return new Transform(rotation, eq.lookupMatrix("R"), eq.lookupMatrix("T"), error, denseA,
        denseB);
  }

  private static DenseMatrix64F calculateMeanMatrix(DenseMatrix64F mat) {
    SimpleMatrix simple = new SimpleMatrix(mat);
    double[] mean = {simple.extractVector(false, 0).elementSum() / mat.numRows,
        simple.extractVector(false, 1).elementSum() / mat.numRows,
        simple.extractVector(false, 2).elementSum() / mat.numRows,};
    DenseMatrix64F meanMat = new DenseMatrix64F(1, 3, true, mean);
    return meanMat;
  }

  public static double calculateError(String fileA, String fileB, DenseMatrix64F transform,
      int frameOffset) throws IOException {
    int globalFrame = 0;

    FrameReader readerA = new FrameReader(fileA);
    FrameReader readerB = new FrameReader(fileB);
    while (globalFrame + frameOffset < 0) {
      readerA.getNext();
      globalFrame++;
    }

    double error = 0;
    int numberOfPoints = 0;
    while (readerA.hasNext() && readerB.hasNext()) {
      Frame frameA = readerA.getNext();
      Frame frameB = readerB.getNext();

      if (frameA.getSkeletonsCount() != frameB.getSkeletonsCount()
          || frameA.getSkeletonsCount() == 0) {
        continue;
      }

      boolean[] jointMask =
          FrameUtils.joinJointMasks(FrameUtils.jointMasks(frameA), FrameUtils.jointMasks(frameB));

      double[] dataA = FrameUtils.jointMatrix(frameA, jointMask);
      DenseMatrix64F denseA = new DenseMatrix64F(dataA.length / 3, 3, true, dataA);
      double[] dataB = FrameUtils.jointMatrix(frameB, jointMask);
      DenseMatrix64F denseB = new DenseMatrix64F(dataB.length / 3, 3, true, dataB);

      Equation eq = new Equation();
      eq.alias(denseA, "X", denseB, "Y", transform, "T", denseA.numRows, "rowsX");
      eq.process("Xt = T * [X, ones(rowsX, 1)]'");
      eq.process("Err = [Y, ones(rowsX, 1)] - Xt'");
      SimpleMatrix matrix = new SimpleMatrix(eq.lookupMatrix("Err"));
      double norm = matrix.normF();
      error += norm * norm;
      numberOfPoints += matrix.numRows();
    }

    error = error / numberOfPoints;
    return error;
  }
}