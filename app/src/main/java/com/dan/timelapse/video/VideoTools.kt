package com.dan.timelapse.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

class VideoTools {
    companion object {
        private const val BUFFER_SIZE = 5 * 1024 * 1024 //1 MB

        fun countFrames(context: Context, uri: Uri): Int {
            var counter = 1
            var extractor: MediaExtractor? = null

            try {
                extractor = MediaExtractor()
                extractor.setDataSource(context, uri, null)

                for (trackIndex in 0 until extractor.trackCount) {
                    val trackFormat = extractor.getTrackFormat(trackIndex)
                    val trackMime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!trackMime.startsWith("video/")) continue
                    extractor.selectTrack(trackIndex)
                    while (extractor.advance()) {
                        counter++
                    }
                    break
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                extractor?.release()
            }

            return counter
        }

        fun fastRemuxVideoSpeed(inputFilePath: String, outputFilePath: String, sourceFps: Int, targetFps: Int) {
            if (sourceFps == targetFps) return
            
            val speedFactor = targetFps.toFloat() / sourceFps.toFloat()
            
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(inputFilePath)
            
            val muxer = android.media.MediaMuxer(outputFilePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = HashMap<Int, Int>()
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val muxerTrackIndex = muxer.addTrack(format)
                trackMap[i] = muxerTrackIndex
            }
            
            muxer.start()
            
            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024 * 2)
            val bufferInfo = android.media.MediaCodec.BufferInfo()
            
            for (i in 0 until extractor.trackCount) {
                extractor.selectTrack(i)
            }
            
            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break
                
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                
                bufferInfo.size = size
                bufferInfo.offset = 0
                bufferInfo.flags = extractor.sampleFlags
                
                bufferInfo.presentationTimeUs = (extractor.sampleTime / speedFactor).toLong()
                
                muxer.writeSampleData(trackMap[trackIndex]!!, buffer, bufferInfo)
                extractor.advance()
            }
            
            muxer.stop()
            muxer.release()
            extractor.release()
        }

        fun changeFps(tmpFile: String, fps: Int) {
            val copyTmpFile = "$tmpFile.tmp"
            File(tmpFile).renameTo(File(copyTmpFile))
            var success = false
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()
            var extractor: MediaExtractor? = null

            try {
                extractor = MediaExtractor()
                extractor.setDataSource(copyTmpFile)
                val trackFormat = extractor.getTrackFormat(0)
                extractor.selectTrack(0)

                val muxer = MediaMuxer(tmpFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val destTrackIndex = muxer.addTrack(trackFormat)
                muxer.start()

                var outputIndex = 0L

                while(true) {
                    val readSize = extractor.readSampleData(buffer, 0)
                    if (readSize <= 0) break

                    bufferInfo.set(
                        0,
                        readSize,
                        outputIndex * 1000000L / fps.toLong(),
                        if (0 != (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC)) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(destTrackIndex, buffer, bufferInfo)

                    if (!extractor.advance()) break
                    outputIndex++
                }

                muxer.stop()
                success = true
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                extractor?.release()
            }

            if (!success) {
                File(tmpFile).delete()
            }

            File(copyTmpFile).delete()
        }

        fun muxTimeScaledAudio(context: Context, sourceVideoUri: Uri, renderedVideoFile: File, speedFactor: Float, trimStartMs: Long = 0L, trimEndMs: Long = Long.MAX_VALUE): Boolean {
            var extractor: MediaExtractor? = null
            var videoExtractor: MediaExtractor? = null
            var muxer: MediaMuxer? = null
            
            try {
                extractor = MediaExtractor()
                extractor.setDataSource(context, sourceVideoUri, null)

                var audioTrackIndex = -1
                var audioFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        audioFormat = format
                        break
                    }
                }

                if (audioTrackIndex < 0 || audioFormat == null) return false

                extractor.selectTrack(audioTrackIndex)
                
                // THE FIX: Clamp audio to trimmed bounds
                if (trimStartMs > 0) {
                    extractor.seekTo(trimStartMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }
                val trimEndUs = if (trimEndMs < Long.MAX_VALUE) trimEndMs * 1000 else Long.MAX_VALUE
                
                val tempMuxedFile = File(renderedVideoFile.absolutePath.replace(".mp4", "_with_audio.mp4"))
                muxer = MediaMuxer(tempMuxedFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                videoExtractor = MediaExtractor()
                videoExtractor.setDataSource(renderedVideoFile.absolutePath)
                var videoTrackIndex = -1
                for (i in 0 until videoExtractor.trackCount) {
                    val format = videoExtractor.getTrackFormat(i)
                    if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                        videoExtractor.selectTrack(i)
                        videoTrackIndex = muxer.addTrack(format)
                        break
                    }
                }

                val newAudioTrackIndex = muxer.addTrack(audioFormat)
                muxer.start()

                val buffer = ByteBuffer.allocate(BUFFER_SIZE)
                val bufferInfo = MediaCodec.BufferInfo()
                
                var videoDone = false
                var audioDone = false
                var lastVideoPtsUs = 0L  // Track video's final PTS so we can cap audio
                
                while (!videoDone || !audioDone) {
                    val videoTime = if (videoDone) Long.MAX_VALUE else videoExtractor.sampleTime
                    val audioTime = if (audioDone) Long.MAX_VALUE else (extractor.sampleTime / speedFactor).toLong()

                    if (!videoDone && (audioDone || videoTime <= audioTime)) {
                        bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                        if (bufferInfo.size < 0) {
                            videoDone = true
                        } else {
                            bufferInfo.presentationTimeUs = videoTime
                            bufferInfo.flags = videoExtractor.sampleFlags
                            muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                            lastVideoPtsUs = videoTime
                            videoExtractor.advance()
                        }
                    } else if (!audioDone && extractor.sampleTime <= trimEndUs) {
                        // Cap audio to video length — prevents A/V desync and MediaPlayer crashes
                        // when audio extends past the final video frame. We allow a ~1s slack
                        // since audio frames are chunky (~20ms typical for AAC).
                        if (videoDone && audioTime > lastVideoPtsUs + 1_000_000L) {
                            audioDone = true
                            continue
                        }
                        bufferInfo.size = extractor.readSampleData(buffer, 0)
                        if (bufferInfo.size < 0) {
                            audioDone = true
                        } else {
                            bufferInfo.presentationTimeUs = audioTime
                            bufferInfo.flags = extractor.sampleFlags
                            muxer.writeSampleData(newAudioTrackIndex, buffer, bufferInfo)
                            extractor.advance()
                        }
                    } else {
                        audioDone = true
                    }
                }

                muxer.stop()
                muxer.release()
                
                if (tempMuxedFile.exists() && tempMuxedFile.length() > 0) {
                    renderedVideoFile.delete()
                    tempMuxedFile.renameTo(renderedVideoFile)
                    return true
                }

            } catch (e: Exception) {
                android.util.Log.e("VideoTools", "Time-scaled audio muxing failed", e)
            } finally {
                extractor?.release()
                videoExtractor?.release()
            }
            return false
        }
    }
}