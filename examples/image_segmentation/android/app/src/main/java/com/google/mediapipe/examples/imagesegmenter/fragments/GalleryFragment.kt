/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.imagesegmenter.fragments

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.mediapipe.examples.imagesegmenter.ImageSegmenterHelper
import com.google.mediapipe.examples.imagesegmenter.MainViewModel
import com.google.mediapipe.examples.imagesegmenter.databinding.FragmentGalleryBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class GalleryFragment : Fragment(), ImageSegmenterHelper.SegmenterListener {
    enum class MediaType {
        IMAGE, VIDEO, UNKNOWN
    }

    private var _fragmentGalleryBinding: FragmentGalleryBinding? = null
    private val fragmentGalleryBinding
        get() = _fragmentGalleryBinding!!
    private val viewModel: MainViewModel by activityViewModels()
    private var imageSegmenterHelper: ImageSegmenterHelper? = null

    /** Blocking ML operations are performed using this executor */
    private var backgroundExecutor: ScheduledExecutorService? = null

    private val getContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            // Handle the returned Uri
            uri?.let { mediaUri ->
                when (val mediaType = loadMediaType(mediaUri)) {
                    MediaType.IMAGE -> runSegmentationOnImage(mediaUri)
                    MediaType.VIDEO -> runSegmentationOnVideo(mediaUri)
                    MediaType.UNKNOWN -> {
                        updateDisplayView(mediaType)
                        Toast.makeText(
                            requireContext(),
                            "Unsupported data type.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentGalleryBinding =
            FragmentGalleryBinding.inflate(inflater, container, false)

        return fragmentGalleryBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fragmentGalleryBinding.fabGetContent.setOnClickListener {
            getContent.launch(arrayOf("image/*", "video/*"))
            updateDisplayView(MediaType.UNKNOWN)
        }
        initBottomSheetControls()
    }

    override fun onPause() {
        stopAllTasks()
        super.onPause()
    }

    private fun initBottomSheetControls() {
        // When clicked, change the underlying hardware used for inference. Current options are CPU
        // GPU, and NNAPI
        fragmentGalleryBinding.bottomSheetLayout.spinnerDelegate.setSelection(
            viewModel.currentDelegate, false
        )
        fragmentGalleryBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long
                ) {

                    viewModel.setDelegate(p2)
                    stopAllTasks()
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }
    }

    private fun stopAllTasks() {
        with(fragmentGalleryBinding) {
            if (videoView.isPlaying) {
                fragmentGalleryBinding.videoView.stopPlayback()
            }

            // clear overlay view
            overlayView.clear()
            progress.visibility = View.GONE
            updateDisplayView(MediaType.UNKNOWN)
        }

        // shut down background tasks
        backgroundExecutor?.shutdownNow()
        backgroundExecutor = null

        // clear Image Segmenter
        imageSegmenterHelper?.clearListener()
        imageSegmenterHelper?.clearImageSegmenter()
        imageSegmenterHelper = null
    }

    // Load and display the image.
    private fun runSegmentationOnImage(uri: Uri) {
        fragmentGalleryBinding.overlayView.setRunningMode(RunningMode.IMAGE)
        setUiEnabled(false)
        updateDisplayView(MediaType.IMAGE)

        // display image on UI
        fragmentGalleryBinding.imageResult.setImageBitmap(uri.toBitmap())

        imageSegmenterHelper = ImageSegmenterHelper(
            context = requireContext(),
            runningMode = RunningMode.IMAGE,
            currentDelegate = viewModel.currentDelegate,
            imageSegmenterListener = this
        )

        backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
        // Run image segmentation on the input image
        backgroundExecutor?.execute {
            val mpImage = BitmapImageBuilder(uri.toBitmap()).build()
            imageSegmenterHelper?.segments(mpImage)
        }
    }

    // Load and display the video.
    private fun runSegmentationOnVideo(uri: Uri) {
        fragmentGalleryBinding.overlayView.setRunningMode(RunningMode.VIDEO)
        setUiEnabled(false)
        updateDisplayView(MediaType.VIDEO)

        // prepare video before start
        with(fragmentGalleryBinding.videoView) {
            setVideoURI(uri)
            // mute the audio
            setOnPreparedListener { it.setVolume(0f, 0f) }
            requestFocus()
        }
        fragmentGalleryBinding.videoView.visibility = View.GONE
        fragmentGalleryBinding.progress.visibility = View.VISIBLE

        imageSegmenterHelper = ImageSegmenterHelper(
            context = requireContext(),
            runningMode = RunningMode.VIDEO,
            currentDelegate = viewModel.currentDelegate,
            imageSegmenterListener = this
        )

        // If ImageSegmenterHelper is closed after creation,
        // it means that ImageSegmenterHelper is corrupted.
        if (imageSegmenterHelper?.isClosed() == true) return

        // Load frames from the video.
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val videoLengthMs =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLong()

        // Note: We need to read width/height from frame instead of getting the width/height
        // of the video directly because MediaRetriever returns frames that are smaller than the
        // actual dimension of the video file.
        val firstFrame = retriever.getFrameAtTime(0)
        val width = firstFrame?.width
        val height = firstFrame?.height

        // If the video is invalid, returns a null
        if ((videoLengthMs == null) || (width == null) || (height == null)) return

        // Next, we'll get one frame every frameInterval ms
        val numberOfFrameToRead = videoLengthMs.div(VIDEO_INTERVAL_MS)
        var frameIndex = 0
        backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
        val mpImages: MutableList<MPImage> = mutableListOf()
        backgroundExecutor?.execute {
            for (i in 0..numberOfFrameToRead) {
                val timestampMs = i * VIDEO_INTERVAL_MS // ms

                retriever
                    .getFrameAtTime(
                        timestampMs * 1000, // convert from ms to micro-s
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                    ?.let { frame ->
                        // Convert the video frame to ARGB_8888 which is required by the MediaPipe
                        val argb8888Frame =
                            if (frame.config == Bitmap.Config.ARGB_8888) frame
                            else frame.copy(Bitmap.Config.ARGB_8888, false)

                        // Convert the input Bitmap object to an MPImage object to run inference
                        val mpImage = BitmapImageBuilder(argb8888Frame).build()
                        mpImages.add(mpImage)
                    }
            }
            retriever.release()
            displayVideoResult()

            backgroundExecutor?.scheduleAtFixedRate({
                // run segmentation on each frames.
                imageSegmenterHelper?.segmentsVideoFile(mpImages[frameIndex])
                frameIndex++
                if (frameIndex >= numberOfFrameToRead.toInt()) {
                    backgroundExecutor?.shutdown()
                }
            }, 0, VIDEO_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }


    // Setup and display the video.
    private fun displayVideoResult() {
        activity?.runOnUiThread {
            fragmentGalleryBinding.videoView.visibility = View.VISIBLE
            fragmentGalleryBinding.progress.visibility = View.GONE
            fragmentGalleryBinding.videoView.start()
        }
    }

    private fun updateDisplayView(mediaType: MediaType) {
        fragmentGalleryBinding.imageResult.visibility =
            if (mediaType == MediaType.IMAGE) View.VISIBLE else View.GONE
        fragmentGalleryBinding.videoView.visibility =
            if (mediaType == MediaType.VIDEO) View.VISIBLE else View.GONE
        fragmentGalleryBinding.tvPlaceholder.visibility =
            if (mediaType == MediaType.UNKNOWN) View.VISIBLE else View.GONE
    }

    // Check the type of media that user selected.
    private fun loadMediaType(uri: Uri): MediaType {
        val mimeType = context?.contentResolver?.getType(uri)
        mimeType?.let {
            if (mimeType.startsWith("image")) return MediaType.IMAGE
            if (mimeType.startsWith("video")) return MediaType.VIDEO
        }

        return MediaType.UNKNOWN
    }

    private fun setUiEnabled(enabled: Boolean) {
        fragmentGalleryBinding.fabGetContent.isEnabled = enabled
        fragmentGalleryBinding.bottomSheetLayout.spinnerDelegate.isEnabled =
            enabled
    }

    private fun segmentationError() {
        activity?.runOnUiThread {
            stopAllTasks()
            setUiEnabled(true)
            updateDisplayView(MediaType.UNKNOWN)
        }
    }

    // convert Uri to bitmap image.
    private fun Uri.toBitmap(): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(
                requireActivity().contentResolver, this
            )
            ImageDecoder.decodeBitmap(source)
        } else {
            MediaStore.Images.Media.getBitmap(
                requireActivity().contentResolver, this
            )
        }.copy(Bitmap.Config.ARGB_8888, true)
    }

    override fun onError(error: String, errorCode: Int) {
        segmentationError()
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            if (errorCode == ImageSegmenterHelper.GPU_ERROR) {
                fragmentGalleryBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    ImageSegmenterHelper.DELEGATE_CPU,
                    false
                )
            }
        }
    }

    override fun onResults(resultBundle: ImageSegmenterHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_fragmentGalleryBinding != null) {
                setUiEnabled(true)
                fragmentGalleryBinding.bottomSheetLayout.inferenceTimeVal.text =
                    String.format("%d ms", resultBundle.inferenceTime)
                fragmentGalleryBinding.overlayView.setResults(
                    resultBundle.results
                )
                fragmentGalleryBinding.overlayView.invalidate()
            }
        }
    }

    companion object {
        private const val TAG = "GalleryFragment"

        // Value used to get frames at specific intervals for inference (e.g. every 300ms)
        private const val VIDEO_INTERVAL_MS = 300L
    }
}