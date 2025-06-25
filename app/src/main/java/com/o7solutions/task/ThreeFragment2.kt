package com.o7solutions.task

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.o7solutions.task.database.DatabaseDB
import com.o7solutions.task.database.ImageEntity
import com.o7solutions.task.databinding.FragmentThree2Binding
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer

class ThreeFragment2 : Fragment() {

    private lateinit var previewViews: List<PreviewView>
    private lateinit var imageOverlays: List<ImageView>
    private lateinit var dustbinIcons: List<ImageView>
    private lateinit var captureButton: Button
    private lateinit var db : DatabaseDB

    private var imageCapture: ImageCapture? = null
    private val capturedBitmaps = mutableListOf<Bitmap?>()
    private val galleryUris = mutableListOf<Uri?>()
    private lateinit var binding: FragmentThree2Binding

    var retakeFlag = false
    private var currentCaptureIndex = 0
    private val maxCaptures = 3
    private var isRetakeMode = false
    private var retakeIndex = -1
    private var setImageIndex = 0

    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var isFrontCamera = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentThree2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        db = DatabaseDB.getInstance(requireContext())

        binding.switchButton.setOnClickListener {
            switchCamera()
        }

        // Initialize lists with nulls
        repeat(maxCaptures) {
            capturedBitmaps.add(null)
            galleryUris.add(null)
        }

        previewViews = listOf(
            view.findViewById(R.id.preview1),
            view.findViewById(R.id.preview2),
            view.findViewById(R.id.preview3)
        )
        imageOverlays = listOf(
            view.findViewById(R.id.overlay1),
            view.findViewById(R.id.overlay2),
            view.findViewById(R.id.overlay3)
        )

        dustbinIcons = listOf(
            view.findViewById(R.id.dustbin1),
            view.findViewById(R.id.dustbin2),
            view.findViewById(R.id.dustbin3)
        )

        // Gallery selection buttons
        binding.addImageBtn1.setOnClickListener {
            selectFromGallery(0)
        }

        binding.addImageBtn2.setOnClickListener {
            selectFromGallery(1)
        }

        // Add third button if exists
        binding.addImageBtn3?.setOnClickListener {
            selectFromGallery(2)
        }

        captureButton = view.findViewById(R.id.captureButton)

        // Set scale type for all preview views
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

        setupLongPressListeners()
        setupDustbinClickListeners()

        updateUI()
        startCamera()
    }

    private fun selectFromGallery(index: Int) {
        setImageIndex = index
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        imagePickerLauncher.launch(intent)
    }

    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = result.data?.data
                if (uri != null) {
                    handleGalleryImageSelection(uri, setImageIndex)
                } else {
                    Log.e("ImagePicker", "Failed to get image URI")
                    Toast.makeText(requireContext(), "Failed to select image", Toast.LENGTH_SHORT).show()
                }
            }
        }

    private fun handleGalleryImageSelection(uri: Uri, index: Int) {
        try {
            // Store the URI
            galleryUris[index] = uri

            // Convert URI to Bitmap
            val bitmap = getBitmapFromUri(uri)
            if (bitmap != null) {
                // Store the bitmap
                capturedBitmaps[index] = bitmap

                // Display the image
                Glide.with(requireContext())
                    .load(uri)
                    .into(imageOverlays[index])

                imageOverlays[index].visibility = View.VISIBLE

                // If we're in retake mode, exit it
                if (isRetakeMode && index == retakeIndex) {
                    exitRetakeMode()
                    Toast.makeText(requireContext(), "Photo ${index + 1} retaken from gallery", Toast.LENGTH_SHORT).show()
                } else {
                    // Update capture index if this fills a gap
                    updateCaptureProgress()
                    Toast.makeText(requireContext(), "Image ${index + 1} selected from gallery", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "Failed to load selected image", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error loading image: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("GallerySelection", "Error loading image", e)
        }

        updateUI()
    }

    private fun getBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = requireContext().contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            bitmap
        } catch (e: Exception) {
            Log.e("BitmapConversion", "Error converting URI to Bitmap", e)
            null
        }
    }

    private fun updateCaptureProgress() {
        // Count how many images we have (camera + gallery)
        val totalImages = getTotalImageCount()

        // Update current capture index to next empty slot
        currentCaptureIndex = 0
        for (i in 0 until maxCaptures) {
            if (capturedBitmaps[i] == null) {
                currentCaptureIndex = i
                break
            } else if (i == maxCaptures - 1) {
                currentCaptureIndex = maxCaptures // All slots filled
            }
        }

        // Show save button if all slots are filled
        if (totalImages >= maxCaptures) {
            previewViews.forEach { it.visibility = View.GONE }
            binding.saveButton.visibility = View.VISIBLE
            binding.saveButton.setOnClickListener {
                createAndSaveCollage()
            }
        } else if (currentCaptureIndex < maxCaptures) {
            // Continue with camera for remaining slots
            switchToNextPreview()
        }
    }

    private fun getTotalImageCount(): Int {
        return capturedBitmaps.count { it != null }
    }

    private fun setupLongPressListeners() {
        imageOverlays.forEachIndexed { index, imageView ->
            imageView.setOnLongClickListener {
                if (retakeFlag) {
                    Toast.makeText(requireContext(), "Please complete current retake before selecting another photo", Toast.LENGTH_SHORT).show()
                    false
                } else if (capturedBitmaps[index] != null) {
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
                hideDustbinIcon(index)
                startRetakeMode(index)
            }
        }
    }

    private fun showDustbinIcon(index: Int) {
        dustbinIcons.forEach { it.visibility = View.GONE }
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
        Log.d("RetakeMode", "Starting retake for index: $index")

        isRetakeMode = true
        retakeIndex = index
        retakeFlag = true

        hideAllDustbinIcons()

        // Clean up existing data for this index
        capturedBitmaps[index]?.recycle()
        capturedBitmaps[index] = null
        galleryUris[index] = null

        // Hide the overlay for the image being retaken
        imageOverlays[index].visibility = View.GONE
        imageOverlays[index].setImageBitmap(null)

        // Show only the preview for the retake index
        previewViews.forEachIndexed { i, previewView ->
            previewView.visibility = if (i == retakeIndex) View.VISIBLE else View.GONE
        }

        // Update UI first
        updateUI()

        // Setup camera for retake with delay to ensure UI is ready
        previewViews[retakeIndex].post {
            setupCameraForRetake()
        }

        Toast.makeText(requireContext(), "Choose camera or gallery to retake photo ${index + 1}", Toast.LENGTH_SHORT).show()
    }

    private fun setupCameraForRetake() {
        Log.d("RetakeMode", "Setting up camera for retake at index: $retakeIndex")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Create new instances for retake
                val preview = Preview.Builder()
                    .setTargetResolution(Size(1920, 1080))
                    .build()

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetResolution(Size(1920, 1080))
                    .setJpegQuality(95)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Unbind all previous use cases first
                cameraProvider.unbindAll()

                // Ensure the preview view is ready
                if (retakeIndex >= 0 && retakeIndex < previewViews.size) {
                    val targetPreview = previewViews[retakeIndex]

                    // Ensure preview is visible
                    targetPreview.visibility = View.VISIBLE

                    Log.d("RetakeMode", "Preview visibility: ${targetPreview.visibility}")

                    // Set surface provider and bind camera
                    preview.setSurfaceProvider(targetPreview.surfaceProvider)
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)

                    Log.d("CameraRetake", "Camera bound successfully for retake at index $retakeIndex")
                } else {
                    Log.e("CameraRetake", "Invalid retake index: $retakeIndex")
                }

            } catch (e: Exception) {
                Log.e("CameraRetake", "Camera initialization failed", e)
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

                        capturedBitmaps[retakeIndex] = bitmap
                        galleryUris[retakeIndex] = null // Clear gallery URI since this is now a camera image

                        imageOverlays[retakeIndex].setImageBitmap(bitmap)
                        imageOverlays[retakeIndex].visibility = View.VISIBLE

                        exitRetakeMode()

                        Toast.makeText(requireContext(), "Photo ${retakeIndex + 1} retaken successfully", Toast.LENGTH_SHORT).show()
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
        Log.d("RetakeMode", "Exiting retake mode")

        isRetakeMode = false
        retakeIndex = -1
        retakeFlag = false

        hideAllDustbinIcons()

        // Update capture progress
        updateCaptureProgress()

        // Hide all preview views if all slots are filled
        if (currentCaptureIndex >= maxCaptures) {
            previewViews.forEach { it.visibility = View.GONE }
            binding.saveButton.visibility = View.VISIBLE
            binding.saveButton.setOnClickListener {
                createAndSaveCollage()
            }
        } else {
            // Setup camera for remaining empty slots with proper delay
            view?.post {
                setupCameraForNormalMode()
            }
        }

        updateUI()
    }

    private fun setupCameraForNormalMode() {
        Log.d("Camera", "Setting up camera for normal mode")

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
        val preview = Preview.Builder()
            .setTargetResolution(Size(1920, 1080))
            .build()

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetResolution(Size(1920, 1080))
            .setJpegQuality(95)
            .build()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            // Unbind all use cases first
            cameraProvider.unbindAll()

            val activeIndex = if (isRetakeMode) retakeIndex else currentCaptureIndex

            if (activeIndex >= 0 && activeIndex < previewViews.size) {
                val targetPreview = previewViews[activeIndex]

                // Ensure preview is visible before binding
                targetPreview.visibility = View.VISIBLE

                preview.setSurfaceProvider(targetPreview.surfaceProvider)
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)

                Log.d("CameraSetup", "Camera bound successfully for index $activeIndex")
            } else {
                Log.e("CameraSetup", "Invalid active index: $activeIndex")
            }
        } catch (e: Exception) {
            Log.e("CameraSetup", "Camera binding failed", e)
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

                        capturedBitmaps[currentCaptureIndex] = bitmap
                        galleryUris[currentCaptureIndex] = null // Clear gallery URI since this is a camera image

                        imageOverlays[currentCaptureIndex].setImageBitmap(bitmap)
                        imageOverlays[currentCaptureIndex].visibility = View.VISIBLE

                        currentCaptureIndex++

                        if (currentCaptureIndex < maxCaptures) {
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
        // Find next empty slot
        for (i in currentCaptureIndex until maxCaptures) {
            if (capturedBitmaps[i] == null) {
                currentCaptureIndex = i
                break
            }
        }

        if (currentCaptureIndex < maxCaptures) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder()
                        .setTargetResolution(Size(1920, 1080))
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

    private fun switchCamera() {
        // Toggle camera selector
        currentCameraSelector = if (isFrontCamera) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        isFrontCamera = !isFrontCamera

        // Restart camera with new selector
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .setTargetResolution(Size(1920, 1080))
                    .build()

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetResolution(Size(1920, 1080))
                    .setJpegQuality(95)
                    .build()

                cameraProvider.unbindAll()

                val activeIndex = if (isRetakeMode) retakeIndex else currentCaptureIndex

                if (activeIndex >= 0 && activeIndex < previewViews.size) {
                    val targetPreview = previewViews[activeIndex]
                    targetPreview.visibility = View.VISIBLE

                    preview.setSurfaceProvider(targetPreview.surfaceProvider)
                    cameraProvider.bindToLifecycle(this, currentCameraSelector, preview, imageCapture)
                }

                val cameraType = if (isFrontCamera) "Front" else "Back"
//                Toast.makeText(requireContext(), "Switched to $cameraType Camera", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to switch camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun createAndSaveCollage() {
        val validBitmaps = capturedBitmaps.filterNotNull()
        if (validBitmaps.isEmpty()) {
            Toast.makeText(requireContext(), "No images to create collage", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val targetWidth = 1920
            val targetHeight = 1080

            val result = Bitmap.createBitmap(targetWidth, targetHeight * validBitmaps.size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)

            validBitmaps.forEachIndexed { index, originalBitmap ->
                val scaledBitmap = Bitmap.createScaledBitmap(
                    originalBitmap,
                    targetWidth,
                    targetHeight,
                    true
                )

                canvas.drawBitmap(scaledBitmap, 0f, (index * targetHeight).toFloat(), null)
                if (scaledBitmap != originalBitmap) {
                    scaledBitmap.recycle()
                }
            }

            saveBitmapToStorage(result)
            result.recycle()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to create collage: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBitmapToStorage(bitmap: Bitmap) {
        val filename = "collage_3x_${System.currentTimeMillis()}.jpg"
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
                    db.databaseDao().insertImage(ImageEntity(name = filename, path = it.toString(), timeStamp = System.currentTimeMillis()))
                    Toast.makeText(requireContext(), "Collage saved to Pictures/Collages!", Toast.LENGTH_LONG).show()
                }
                findNavController().popBackStack()
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
                captureButton.text = "Take Photo ${retakeIndex + 1}"
                captureButton.visibility = View.VISIBLE

                // Show only the preview for retake index during retake mode
                previewViews.forEachIndexed { index, previewView ->
                    previewView.visibility = if (index == retakeIndex) View.VISIBLE else View.GONE
                }

                // Ensure gallery buttons are visible during retake
                resetGalleryButtonsVisibility()
            }
            currentCaptureIndex == 0 -> {
                captureButton.text = "Capture Photo 1"
                previewViews.forEachIndexed { index, previewView ->
                    previewView.visibility = if (index == 0) View.VISIBLE else View.GONE
                }
                resetGalleryButtonsVisibility()
            }
            currentCaptureIndex in 1 until maxCaptures -> {
                captureButton.text = "Capture Photo ${currentCaptureIndex + 1}"
                previewViews.forEachIndexed { index, previewView ->
                    previewView.visibility = if (index == currentCaptureIndex) View.VISIBLE else View.GONE
                }
                resetGalleryButtonsVisibility()
            }
            currentCaptureIndex >= maxCaptures -> {
                captureButton.text = "Start Over"
                previewViews.forEach { it.visibility = View.GONE }
                resetGalleryButtonsVisibility()
            }
        }
    }

    private fun resetGalleryButtonsVisibility() {
        binding.addImageBtn1.visibility = View.VISIBLE
        binding.addImageBtn2.visibility = View.VISIBLE
        binding.addImageBtn3?.visibility = View.VISIBLE
    }

    private fun resetCapture() {
        currentCaptureIndex = 0
        isRetakeMode = false
        retakeIndex = -1
        retakeFlag = false

        hideAllDustbinIcons()

        // Clean up all bitmaps and URIs
        capturedBitmaps.forEachIndexed { index, bitmap ->
            bitmap?.recycle()
            capturedBitmaps[index] = null
            galleryUris[index] = null
        }

        imageOverlays.forEach {
            it.setImageBitmap(null)
            it.visibility = View.GONE
        }

        binding.saveButton.visibility = View.GONE
        updateUI()
        startCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        capturedBitmaps.forEach { it?.recycle() }
        capturedBitmaps.clear()
        galleryUris.clear()
    }
}