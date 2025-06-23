package com.o7solutions.task

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.o7solutions.task.database.DatabaseDB
import com.o7solutions.task.database.ImageEntity
import com.o7solutions.task.databinding.FragmentTwoBinding
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass.
 * Use the [TwoFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class TwoFragment : Fragment() {
    // TODO: Rename and change types of parameters
    private var param1: String? = null
    private var param2: String? = null

    private lateinit var previewViews: List<PreviewView>
    private lateinit var imageOverlays: List<ImageView>
    private lateinit var dustbinIcons: List<ImageView> // Add dustbin icons list
    private lateinit var captureButton: Button

    private lateinit var db: DatabaseDB
    private var imageCapture: ImageCapture? = null
    private val capturedBitmaps = mutableListOf<Bitmap>()
    private lateinit var binding: FragmentTwoBinding

    private var currentCaptureIndex = 0
    private val maxCaptures = 2
    private var isRetakeMode = false
    private var retakeFlag = false
    private var retakeIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            param1 = it.getString(ARG_PARAM1)
            param2 = it.getString(ARG_PARAM2)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentTwoBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        db = DatabaseDB.getInstance(requireContext())
        previewViews = listOf(
            view.findViewById(R.id.preview1),
            view.findViewById(R.id.preview2)
        )
        imageOverlays = listOf(
            view.findViewById(R.id.overlay1),
            view.findViewById(R.id.overlay2)
        )

        // Initialize dustbin icons - you need to add these ImageViews to your layout
        dustbinIcons = listOf(
            view.findViewById(R.id.dustbin1),
            view.findViewById(R.id.dustbin2)
        )

        captureButton = view.findViewById(R.id.captureButton)

        // Set scale type for preview views
        previewViews.forEach { previewView ->
            previewView.scaleType = PreviewView.ScaleType.FILL_START
        }

        captureButton.setOnClickListener {
            if (isRetakeMode) {
                retakeImage()
            } else {
                if (currentCaptureIndex < maxCaptures) {
                    captureImage()
                } else {
                    resetCapture()
                }
            }
        }

        // Set up long press listeners for image overlays
        setupLongPressListeners()

        // Set up dustbin icon click listeners
        setupDustbinClickListeners()

        binding.overlay1.setOnClickListener {
            Log.d("Two Fragment", "onViewCreated: Overlay1")
        }

        updateUI()
        startCamera()
    }

    private fun setupLongPressListeners() {
        imageOverlays.forEachIndexed { index, imageView ->
            imageView.setOnLongClickListener {
                if (retakeFlag) {
                    Toast.makeText(requireContext(), "Please complete current retake before selecting another photo", Toast.LENGTH_SHORT).show()
                    false
                } else if (capturedBitmaps.size > index) {
                    showDustbinIcon(index)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun setupDustbinClickListeners() {
        dustbinIcons.forEachIndexed { index, dustbinIcon ->
            dustbinIcon.setOnClickListener {
                // Hide the dustbin icon
                hideDustbinIcon(index)
                // Start retake mode for this image
                startRetakeMode(index)
            }
        }
    }

    private fun showDustbinIcon(index: Int) {
        // Hide all other dustbin icons first
        dustbinIcons.forEach { it.visibility = View.GONE }

        // Show the dustbin icon for the selected image
        dustbinIcons[index].visibility = View.VISIBLE

        Toast.makeText(requireContext(), "Tap dustbin to retake photo ${index + 1}", Toast.LENGTH_SHORT).show()
    }

    private fun hideDustbinIcon(index: Int) {
        dustbinIcons[index].visibility = View.GONE
    }

    private fun hideAllDustbinIcons() {
        dustbinIcons.forEach { it.visibility = View.GONE }
    }

    private fun startRetakeMode(index: Int) {
        isRetakeMode = true
        retakeIndex = index
        retakeFlag = true

        // Hide all dustbin icons
        hideAllDustbinIcons()

        // Recycle the old bitmap to free memory
        if (capturedBitmaps.size > index) {
            capturedBitmaps[index].recycle()
        }

        // Hide the overlay for the image being retaken
        imageOverlays[index].visibility = View.GONE
        imageOverlays[index].setImageBitmap(null)

        // Show the preview for the specific index
        previewViews.forEachIndexed { i, previewView ->
            previewView.visibility = if (i == index) View.VISIBLE else View.GONE
        }

        // Update camera to show preview for the retake index
        setupCameraForRetake()

        updateUI()

        Toast.makeText(requireContext(), "Retaking photo ${index + 1}", Toast.LENGTH_SHORT).show()
    }

    private fun setupCameraForRetake() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                setupCamera(cameraProvider)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Camera initialization failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun retakeImage() {
        val imageCapture = imageCapture ?: return

        captureButton.isEnabled = false

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val bitmap = convertImageProxyToBitmap(imageProxy)
                        imageProxy.close()

                        // Replace the bitmap at the retake index
                        if (capturedBitmaps.size > retakeIndex) {
                            capturedBitmaps[retakeIndex] = bitmap
                        } else {
                            // This should not happen, but handle gracefully
                            capturedBitmaps.add(bitmap)
                        }

                        // Update the overlay
                        imageOverlays[retakeIndex].setImageBitmap(bitmap)
                        imageOverlays[retakeIndex].visibility = View.VISIBLE

                        // Exit retake mode
                        exitRetakeMode()

                        updateUI()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Image processing failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        captureButton.isEnabled = true
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    captureButton.isEnabled = true
                    Toast.makeText(requireContext(), "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                    updateUI()
                }
            }
        )
    }

    private fun exitRetakeMode() {
        isRetakeMode = false
        retakeIndex = -1
        retakeFlag = false

        // Hide all dustbin icons
        hideAllDustbinIcons()

        // If all images are captured, show the "Start Over" state
        if (currentCaptureIndex >= maxCaptures) {
            // Hide all preview views
            previewViews.forEach { it.visibility = View.GONE }
        } else {
            // Continue with normal capture flow
            setupCameraForNormalMode()
        }
    }

    private fun setupCameraForNormalMode() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                setupCamera(cameraProvider)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Camera initialization failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                setupCamera(cameraProvider)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Camera initialization failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun setupCamera(cameraProvider: ProcessCameraProvider) {
        // Configure preview to match the preview view's aspect ratio
        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9) // Match common preview aspect ratio
            .build()

        // Configure image capture to match preview aspect ratio
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(AspectRatio.RATIO_16_9) // Same as preview
            .setJpegQuality(95)
            .build()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()

            val activeIndex = if (isRetakeMode) retakeIndex else currentCaptureIndex

            if (activeIndex < previewViews.size) {
                // Set preview to current active preview view
                preview.setSurfaceProvider(previewViews[activeIndex].surfaceProvider)
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Camera binding failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun captureImage() {
        val imageCapture = imageCapture ?: return

        captureButton.isEnabled = false
        updateUI()

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val bitmap = convertImageProxyToBitmap(imageProxy)
                        imageProxy.close()

                        capturedBitmaps.add(bitmap)
                        imageOverlays[currentCaptureIndex].setImageBitmap(bitmap)
                        imageOverlays[currentCaptureIndex].visibility = View.VISIBLE

                        currentCaptureIndex++

                        if (currentCaptureIndex < maxCaptures) {
                            // Switch to next preview view
                            switchToNextPreview()
                            binding.saveButton.visibility = View.GONE

                        } else {
                            binding.saveButton.visibility = View.VISIBLE
                            binding.saveButton.setOnClickListener {
                                createAndSaveCollage()
                            }
                        }

                        updateUI()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Image processing failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        captureButton.isEnabled = true
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    captureButton.isEnabled = true
                    Toast.makeText(requireContext(), "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                    updateUI()
                }
            }
        )
    }

    private fun switchToNextPreview() {
        if (currentCaptureIndex < maxCaptures) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                        .build()
                    preview.setSurfaceProvider(previewViews[currentCaptureIndex].surfaceProvider)

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Failed to switch preview: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }, ContextCompat.getMainExecutor(requireContext()))
        }
    }

    private fun convertImageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        return when (imageProxy.format) {
            ImageFormat.JPEG -> {
                val buffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            ImageFormat.YUV_420_888 -> {
                yuv420ToBitmap(imageProxy)
            }
            else -> {
                throw IllegalArgumentException("Unsupported image format: ${imageProxy.format}")
            }
        }
    }

    private fun yuv420ToBitmap(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun createAndSaveCollage() {
        if (capturedBitmaps.isEmpty()) return

        try {
            // Use the actual dimensions of the captured images
            // This maintains the exact aspect ratio of what was visible in preview
            val firstImage = capturedBitmaps[0]
            val imageWidth = firstImage.width
            val imageHeight = firstImage.height

            // Create collage with two images side by side
            val collageWidth = imageWidth * 2
            val collageHeight = imageHeight

            // Create final collage bitmap
            val result = Bitmap.createBitmap(collageWidth, collageHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)

            capturedBitmaps.forEachIndexed { index, bitmap ->
                // Draw each bitmap at its natural size, side by side
                val xPosition = (index * imageWidth).toFloat()
                canvas.drawBitmap(bitmap, xPosition, 0f, null)
            }

            saveBitmapToStorage(result)

            // Clean up
            result.recycle()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to create collage: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBitmapToStorage(bitmap: Bitmap) {
        val filename = "collage_2x_${System.currentTimeMillis()}.jpg"
        val resolver = requireContext().contentResolver

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Collages")
        }

        try {
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                resolver.openOutputStream(it)?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                    db.databaseDao().insertImage(ImageEntity(name= filename, path = it.toString(), timeStamp = System.currentTimeMillis()))
                    Toast.makeText(requireContext(), "2-Photo collage saved to Pictures/Collages!", Toast.LENGTH_LONG).show()
                }
            } ?: run {
                Toast.makeText(requireContext(), "Failed to create file.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to save collage: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI() {
        when {
            isRetakeMode -> {
                captureButton.text = "Retake Photo ${retakeIndex + 1}"
            }
            currentCaptureIndex == 0 -> {
                captureButton.text = "Capture Photo 1"
            }
            currentCaptureIndex == 1 -> {
                captureButton.text = "Capture Photo 2"
            }
            currentCaptureIndex >= maxCaptures -> {
                captureButton.text = "Start Over"

            }
        }

        // Show/hide preview views based on current state
        if (isRetakeMode) {
            previewViews.forEachIndexed { index, previewView ->
                previewView.visibility = if (index == retakeIndex) View.VISIBLE else View.GONE
            }
        } else {
            previewViews.forEachIndexed { index, previewView ->
                previewView.visibility = if (index == currentCaptureIndex && currentCaptureIndex < maxCaptures)
                    View.VISIBLE else View.GONE
            }
        }
    }

    private fun resetCapture() {
        currentCaptureIndex = 0
        isRetakeMode = false
        retakeIndex = -1

        retakeFlag = false
        // Hide all dustbin icons
        hideAllDustbinIcons()

        capturedBitmaps.forEach { it.recycle() }
        capturedBitmaps.clear()

        imageOverlays.forEach {
            it.setImageBitmap(null)
            it.visibility = View.GONE
        }

        updateUI()
        startCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up bitmaps to prevent memory leaks
        capturedBitmaps.forEach { it.recycle() }
        capturedBitmaps.clear()
    }

    private fun createTempFile(): File {
        val dir = requireContext().cacheDir
        return File.createTempFile("temp_image", ".jpg", dir)
    }

    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param param1 Parameter 1.
         * @param param2 Parameter 2.
         * @return A new instance of fragment TwoFragment.
         */
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(param1: String, param2: String) =
            TwoFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PARAM1, param1)
                    putString(ARG_PARAM2, param2)
                }
            }
    }
}