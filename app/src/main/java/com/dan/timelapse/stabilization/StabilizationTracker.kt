package com.dan.timelapse.stabilization

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Frame-to-frame motion estimator.
 *
 * Estimates against a downscaled copy of the frame (960 px wide by default).
 * That is 3-4x cheaper than full resolution, rejects sensor noise better, and
 * costs nothing in accuracy because the result is lifted back to full-res
 * sub-pixel coordinates.
 *
 * Every estimate is gated:
 *  - forward-backward optical flow agreement (kills bad tracks),
 *  - minimum inlier count and ratio (kills textureless / occluded frames),
 *  - a sanity limit on translation and rotation (kills wild fits).
 * A frame that fails is reported invalid; the path then holds instead of
 * injecting invented motion.
 */
class StabilizationTracker(private val analysisWidth: Int = 960) {

    data class Estimate(
        val delta: Affine,
        val valid: Boolean,
        val inlierRatio: Double,
        val inliers: Int,
        val trackedPoints: Int
    )

    private var prevGray: Mat? = null
    private var prevPoints: MatOfPoint2f? = null
    private var analysisCols = 0
    private var analysisRows = 0

    fun reset() {
        prevGray?.release()
        prevGray = null
        prevPoints?.release()
        prevPoints = null
        analysisCols = 0
        analysisRows = 0
    }

    /**
     * @param frame a processed frame (rotation and crop already applied)
     * @param mask optional analysis-sized mask; features are only taken inside it
     */
    fun analyze(frame: Mat, mask: Mat?): Estimate {
        if (frame.empty() || frame.cols() < 16 || frame.rows() < 16) {
            return Estimate(Affine.IDENTITY, false, 0.0, 0, 0)
        }

        val needsDownscale = frame.cols() > analysisWidth
        val scale = if (needsDownscale) analysisWidth.toDouble() / frame.cols() else 1.0
        val gray = Mat()
        if (needsDownscale) {
            val small = Mat()
            val w = analysisWidth
            val h = (frame.rows() * scale).toInt().coerceAtLeast(16)
            Imgproc.resize(frame, small, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
            small.release()
            analysisCols = w
            analysisRows = h
        } else {
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_RGB2GRAY)
            analysisCols = frame.cols()
            analysisRows = frame.rows()
        }

        val previousGray = prevGray
        val previousPoints = prevPoints
        if (previousGray == null || previousPoints == null || previousPoints.empty()) {
            seed(gray, mask)
            gray.release()
            return Estimate(Affine.IDENTITY, false, 0.0, 0, 0)
        }

        val nextPoints = MatOfPoint2f()
        val backPoints = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()
        val statusBack = MatOfByte()
        val errBack = MatOfFloat()
        var estimate = Estimate(Affine.IDENTITY, false, 0.0, 0, 0)

        try {
            val win = 21
            val criteria = TermCriteria(
                TermCriteria.EPS or TermCriteria.MAX_ITER,
                30,
                0.01
            )
            Video.calcOpticalFlowPyrLK(
                previousGray, gray, previousPoints, nextPoints,
                status, err, Size(win.toDouble(), win.toDouble()), 3, criteria, 0, 1e-4
            )
            Video.calcOpticalFlowPyrLK(
                gray, previousGray, nextPoints, backPoints,
                statusBack, errBack, Size(win.toDouble(), win.toDouble()), 3, criteria, 0, 1e-4
            )

            val st = status.toArray()
            val stBack = statusBack.toArray()
            val prev = previousPoints.toArray()
            val next = nextPoints.toArray()
            val back = backPoints.toArray()

            val goodPrev = ArrayList<org.opencv.core.Point>(prev.size)
            val goodNext = ArrayList<org.opencv.core.Point>(prev.size)
            val maxFbError = 1.0
            for (i in prev.indices) {
                if (i >= st.size || i >= stBack.size || st[i].toInt() != 1 || stBack[i].toInt() != 1) continue
                val dx = prev[i].x - back[i].x
                val dy = prev[i].y - back[i].y
                if (hypot(dx, dy) > maxFbError) continue
                goodPrev.add(prev[i])
                goodNext.add(next[i])
            }

            if (goodPrev.size >= 12) {
                val prevMat = MatOfPoint2f(*goodPrev.toTypedArray())
                val nextMat = MatOfPoint2f(*goodNext.toTypedArray())
                val inliers = Mat()
                try {
                    val matrix = Calib3d.estimateAffinePartial2D(
                        nextMat, prevMat, inliers, Calib3d.RANSAC, 1.0
                    )
                    if (!matrix.empty()) {
                        val m00 = matrix.get(0, 0)[0]
                        val m10 = matrix.get(1, 0)[0]
                        val rawDx = matrix.get(0, 2)[0]
                        val rawDy = matrix.get(1, 2)[0]
                        val angle = kotlin.math.atan2(m10, m00)
                        val inlierCount = Core.countNonZero(inliers)
                        val ratio = inlierCount.toDouble() / goodPrev.size

                        val dx = rawDx / scale
                        val dy = rawDy / scale
                        val maxShift = frame.cols() * 0.15
                        val sane = abs(dx) <= maxShift &&
                            abs(dy) <= maxShift &&
                            abs(angle) <= Math.toRadians(5.0)
                        val valid = sane && inlierCount >= 10 && ratio >= 0.30

                        estimate = Estimate(
                            delta = Affine.fromRaw(dx, dy, angle),
                            valid = valid,
                            inlierRatio = ratio,
                            inliers = inlierCount,
                            trackedPoints = goodPrev.size
                        )
                    }
                    matrix.release()
                } finally {
                    prevMat.release()
                    nextMat.release()
                    inliers.release()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("Stabilization", "Motion estimate failed", e)
            estimate = Estimate(Affine.IDENTITY, false, 0.0, 0, 0)
        } finally {
            nextPoints.release()
            backPoints.release()
            status.release()
            err.release()
            statusBack.release()
            errBack.release()
        }

        seed(gray, mask)
        // This Mat is per-frame; without an explicit release the whole clip's worth of
        // native memory (≈1.5 MB × frames) is only reclaimed by the finalizer, which
        // can exhaust native memory on a long 4K render.
        gray.release()
        return estimate
    }

    /** Detect features on the current frame for the next iteration. */
    private fun seed(gray: Mat, mask: Mat?) {
        val points = MatOfPoint()
        var resizedMask: Mat? = null
        try {
            // The mask arrives in FRAME coordinates; analysis runs on a downscaled grey
            // image. Resize it instead of silently dropping it (the previous behaviour
            // disabled mask exclusion at every width above the analysis width).
            val usableMask = if (mask != null && !mask.empty()) {
                if (mask.cols() == gray.cols() && mask.rows() == gray.rows()) {
                    mask
                } else {
                    resizedMask = Mat()
                    Imgproc.resize(
                        mask, resizedMask,
                        Size(gray.cols().toDouble(), gray.rows().toDouble()),
                        0.0, 0.0, Imgproc.INTER_NEAREST
                    )
                    resizedMask
                }
            } else {
                Mat()
            }
            // minDistance is in analysis pixels; 12 at 960px is ~24 at 1920.
            Imgproc.goodFeaturesToTrack(gray, points, 400, 0.01, 12.0, usableMask)

            val points2f = MatOfPoint2f()
            if (!points.empty()) {
                points.convertTo(points2f, CvType.CV_32FC2)
                prevPoints?.release()
                prevPoints = points2f
            } else {
                points2f.release()
                prevPoints?.release()
                prevPoints = MatOfPoint2f()
            }

            prevGray?.release()
            prevGray = gray.clone()
        } finally {
            points.release()
            resizedMask?.release()
        }
    }

    val analysisSize: Pair<Int, Int>
        get() = Pair(analysisCols, analysisRows)
}
