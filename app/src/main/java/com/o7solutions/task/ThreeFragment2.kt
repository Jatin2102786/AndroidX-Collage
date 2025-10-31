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
import java.io.InputStream

class ThreeFragment2 : Fragment() {

    private lateinit var previewViews: List<PreviewView>
    private lateinit var imageOverlays: List<ImageView>
    private lateinit var dustbinIcons: List<ImageView>
    private lateinit var captureButton: Button
    private lateinit var db : DatabaseDB

    private var imageCapture: ImageCapture? = null
    // Changed to track source of each image more explicitly
    private val imageSources = mutableListOf<ImageSource>()
    private val capturedBitmaps = mutableListOf<Bitmap?>()
    private val galleryUris = mutableListOf<Uri?>()
    private lateinit var binding: FragmentThree2Binding

    private var currentCaptureIndex = 0
    private val maxCaptures = 3
    private var isRetakeMode = false
    private var retakeIndex = -1
    // Flag to prevent concurrent retake attempts
    private var retakeInProgress = false
    private var setImageIndex = 0 // Used for gallery selection

    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    // Enum to track image source for each slot
    enum class ImageSource {
        CAMERA,
        GALLERY,
        EMPTY
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentThree2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        db = DatabaseDB.getInstance(requireContext())

        // Initialize lists with empty slots
        initializeLists()

        binding.switchButton.setOnClickListener {
            switchCamera()
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
                val nextCameraIndex = getNextEmptyCameraSlotIndex()
                if (nextCameraIndex != -1) {
                    currentCaptureIndex = nextCameraIndex
                    captureImage()
                } else {
                    resetCapture() // All slots filled, capture button becomes "Start Over"
                }
            }
        }

        setupLongPressListeners()
        setupDustbinClickListeners()

        updateUI()
        startCamera()
    }

    private fun initializeLists() {
        // Initialize with empty slots
        repeat(maxCaptures) {
            imageSources.add(ImageSource.EMPTY)
            capturedBitmaps.add(null)
            galleryUris.add(null)
        }
    }

    private fun selectFromGallery(index: Int) {
        // Prevent gallery selection if a retake is in progress
        if (retakeInProgress && !isRetakeMode) {
            Toast.makeText(requireContext(), "Please complete current retake or camera capture before selecting another photo", Toast.LENGTH_SHORT).show()
            return
        }

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
            // Convert URI to Bitmap for display and potential collage creation
            val bitmap = getBitmapFromUri(uri)
            if (bitmap != null) {
                // Recycle existing bitmap if replacing
                capturedBitmaps[index]?.recycle()
                capturedBitmaps[index] = bitmap
                galleryUris[index] = uri // Store URI for traceability if needed
                imageSources[index] = ImageSource.GALLERY

                // Display the image
                Glide.with(requireContext())
                    .load(uri)
                    .into(imageOverlays[index])

                imageOverlays[index].visibility = View.VISIBLE

                if (isRetakeMode && index == retakeIndex) {
                    exitRetakeMode()
                    Toast.makeText(requireContext(), "Photo  retaken from gallery", Toast.LENGTH_SHORT).show()
                } else {
                    updateCaptureProgress()
                    Toast.makeText(requireContext(), "Image  selected from gallery", Toast.LENGTH_SHORT).show()
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

    private fun getNextEmptyCameraSlotIndex(): Int {
        for (i in 0 until maxCaptures) {
            if (imageSources[i] == ImageSource.EMPTY) {
                return i
            }
        }
        return -1 // All slots filled
    }

    private fun updateCaptureProgress() {
        // Find the next available slot for camera capture
        currentCaptureIndex = getNextEmptyCameraSlotIndex()

        val totalImages = getTotalImageCount()

        // If all slots are filled, hide previews and show save button
        if (totalImages >= maxCaptures) {
            previewViews.forEach { it.visibility = View.GONE }
            binding.saveButton.visibility = View.VISIBLE
            binding.saveButton.setOnClickListener {
                createAndSaveCollage()
            }
        } else if (currentCaptureIndex != -1) {
            // Continue with camera for remaining slots if not in retake mode
            if (!isRetakeMode) {
                switchToNextPreview()
            }
        }
        updateUI()
    }

    private fun getTotalImageCount(): Int {
        return imageSources.count { it != ImageSource.EMPTY }
    }

    private fun setupLongPressListeners() {
        imageOverlays.forEachIndexed { index, imageView ->
            imageView.setOnLongClickListener {
                // Prevent long press if a retake is already in progress or a capture is ongoing
                if (retakeInProgress) {
                    Toast.makeText(requireContext(), "Please complete current action before selecting another photo", Toast.LENGTH_SHORT).show()
                    false
                } else if (imageSources[index] != ImageSource.EMPTY) {
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
        // Hide all others and show only for the selected one
        dustbinIcons.forEach { it.visibility = View.GONE }
        dustbinIcons[index].visibility = View.VISIBLE
        Toast.makeText(requireContext(), "Tap dustbin to retake photo", Toast.LENGTH_SHORT).show()
    }

    private fun hideDustbinIcon(index: Int) {
        dustbinIcons[index].visibility = View.GONE
    }

    private fun hideAllDustbinIcons() {
        dustbinIcons.forEach { it.visibility = View.GONE }
    }

    private fun startRetakeMode(index: Int) {
        Log.d("RetakeMode", "Starting retake")

        isRetakeMode = true
        retakeIndex = index
        retakeInProgress = true // Set flag to true

        hideAllDustbinIcons()

        // Clean up existing data for this index
        capturedBitmaps[index]?.recycle()
        capturedBitmaps[index] = null
        galleryUris[index] = null
        imageSources[index] = ImageSource.EMPTY

        // Hide the overlay for the image being retaken
        imageOverlays[index].visibility = View.GONE
        imageOverlays[index].setImageBitmap(null)

        // Show only the preview for the retake index
        previewViews.forEachIndexed { i, previewView ->
            previewView.visibility = if (i == retakeIndex) View.VISIBLE else View.GONE
        }

        updateUI() // Update UI immediately to reflect retake mode

        // Setup camera for retake with a small delay to ensure UI is ready for surface provider
        previewViews[retakeIndex].post {
            setupCameraForRetake()
        }

        Toast.makeText(requireContext(), "Choose camera or gallery to retake photo ", Toast.LENGTH_SHORT).show()
    }

    private fun setupCameraForRetake() {
        Log.d("RetakeMode", "Setting up camera for retake")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .setTargetResolution(Size(1920, 1080)) // Consistent target resolution
                    .build()

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetResolution(Size(1920, 1080))
                    .setJpegQuality(95)
                    .build()

                // Unbind all previous use cases first
                cameraProvider.unbindAll()

                // Ensure the preview view is ready
                if (retakeIndex >= 0 && retakeIndex < previewViews.size) {
                    val targetPreview = previewViews[retakeIndex]

                    targetPreview.visibility = View.VISIBLE // Ensure it's visible

                    preview.setSurfaceProvider(targetPreview.surfaceProvider)
                    cameraProvider.bindToLifecycle(this, currentCameraSelector, preview, imageCapture) // Use currentCameraSelector
                    Log.d("CameraRetake", "Camera bound successfully for retake")
                } else {
                    Log.e("CameraRetake", "Invalid retake")
                }

            } catch (e: Exception) {
                Log.e("CameraRetake", "Camera initialization failed", e)
                Toast.makeText(requireContext(), "Camera initialization failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun retakeImage() {
        val imageCapture = imageCapture ?: return

        captureButton.isEnabled = false // Disable button during capture

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val bitmap = convertImageProxyToBitmap(imageProxy)
                        imageProxy.close()

                        // Recycle old bitmap if replacing (should be null from startRetakeMode, but good practice)
                        capturedBitmaps[retakeIndex]?.recycle()
                        capturedBitmaps[retakeIndex] = bitmap
                        galleryUris[retakeIndex] = null // Clear gallery URI
                        imageSources[retakeIndex] = ImageSource.CAMERA // Mark as camera source

                        imageOverlays[retakeIndex].setImageBitmap(bitmap)
                        imageOverlays[retakeIndex].visibility = View.VISIBLE

                        exitRetakeMode()
                        Toast.makeText(requireContext(), "Photo retake successful", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Image processing failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    } finally {
                        captureButton.isEnabled = true // Re-enable button
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    captureButton.isEnabled = true // Re-enable button
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
        retakeInProgress = false // Reset flag

        hideAllDustbinIcons()

        updateCaptureProgress() // Determine next camera slot or show save button

        // If not all slots are filled and there's a next camera slot, set up camera for normal mode
        if (getTotalImageCount() < maxCaptures && currentCaptureIndex != -1) {
            view?.post {
                setupCameraForNormalMode()
            }
        }
        updateUI() // Final UI update
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

        try {
            cameraProvider.unbindAll()

            val activeIndex = if (isRetakeMode) retakeIndex else currentCaptureIndex

            if (activeIndex >= 0 && activeIndex < previewViews.size) {
                val targetPreview = previewViews[activeIndex]

                targetPreview.visibility = View.VISIBLE // Ensure it's visible

                preview.setSurfaceProvider(targetPreview.surfaceProvider)
                cameraProvider.bindToLifecycle(this, currentCameraSelector, preview, imageCapture) // Use currentCameraSelector
                Log.d("CameraSetup", "Camera bound successfully for index $activeIndex")
            } else {
                Log.e("CameraSetup", "Invalid active index: $activeIndex. No preview to bind.")
                // If all slots are filled or no valid index, hide all previews
                previewViews.forEach { it.visibility = View.GONE }
            }
        } catch (e: Exception) {
            Log.e("CameraSetup", "Camera binding failed", e)
            Toast.makeText(requireContext(), "Camera binding failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun captureImage() {
        val imageCapture = imageCapture ?: return

        captureButton.isEnabled = false // Disable button during capture
        retakeInProgress = true // Set flag to true

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val bitmap = convertImageProxyToBitmap(imageProxy)
                        imageProxy.close()

                        // Recycle old bitmap if replacing
                        capturedBitmaps[currentCaptureIndex]?.recycle()
                        capturedBitmaps[currentCaptureIndex] = bitmap
                        galleryUris[currentCaptureIndex] = null // Clear gallery URI
                        imageSources[currentCaptureIndex] = ImageSource.CAMERA // Mark as camera source

                        imageOverlays[currentCaptureIndex].setImageBitmap(bitmap)
                        imageOverlays[currentCaptureIndex].visibility = View.VISIBLE

                        updateCaptureProgress() // Update index and UI state
                        Toast.makeText(requireContext(), "Photo captured!", Toast.LENGTH_SHORT).show()

                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Image processing failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        captureButton.isEnabled = true // Re-enable button
                        retakeInProgress = false // Reset flag
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    captureButton.isEnabled = true // Re-enable button
                    retakeInProgress = false // Reset flag
                    Toast.makeText(requireContext(), "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                    updateUI()
                }
            }
        )
    }

    private fun switchToNextPreview() {
        if (currentCaptureIndex < maxCaptures && currentCaptureIndex >= 0) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder()
                        .setTargetResolution(Size(1920, 1080))
                        .build()
                    preview.setSurfaceProvider(previewViews[currentCaptureIndex].surfaceProvider)

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(this, currentCameraSelector, preview, imageCapture) // Use currentCameraSelector
                    Log.d("SwitchPreview", "Switched to preview ")

                    // Ensure only the current preview is visible
                    previewViews.forEachIndexed { index, previewView ->
                        previewView.visibility = if (index == currentCaptureIndex) View.VISIBLE else View.GONE
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Failed to switch preview: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }, ContextCompat.getMainExecutor(requireContext()))
        } else {
            // All slots are filled, hide all previews
            previewViews.forEach { it.visibility = View.GONE }
            Log.d("SwitchPreview", "All slots filled or invalid index, hiding all previews.")
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
        currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

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

                val cameraType = if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) "Front" else "Back"
                Toast.makeText(requireContext(), "Switched to $cameraType Camera", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to switch camera: ${e.message}", Toast.LENGTH_SHORT).show()
                // Revert camera selector on failure
                currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun createAndSaveCollage() {
        val finalBitmaps = mutableListOf<Bitmap>()

        // Collect all valid bitmaps, handling recycled ones gracefully
        for (i in 0 until maxCaptures) {
            capturedBitmaps[i]?.let {
                if (!it.isRecycled) {
                    finalBitmaps.add(it)
                }
            }
        }

        if (finalBitmaps.isEmpty()) {
            Toast.makeText(requireContext(), "No images to create collage", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Use a fixed target width for the collage, height will be sum of individual image heights
            val targetWidth = 1920 // Example width, can be adjusted
            val targetHeight = 1080 // Example height, can be adjusted for 3 vertical images

            // Calculate total height of the collage (3 images stacked vertically)
            val collageHeight = targetHeight * maxCaptures

            val result = Bitmap.createBitmap(targetWidth, collageHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)

            finalBitmaps.forEachIndexed { index, originalBitmap ->
                val scaledBitmap = Bitmap.createScaledBitmap(
                    originalBitmap,
                    targetWidth,
                    targetHeight,
                    true
                )

                // Draw each scaled bitmap below the previous one
                canvas.drawBitmap(scaledBitmap, 0f, (index * targetHeight).toFloat(), null)
                // Recycle the scaled bitmap if it's different from the original
                if (scaledBitmap != originalBitmap) {
                    scaledBitmap.recycle()
                }
            }

            saveBitmapToStorage(result)
            result.recycle() // Recycle the final collage bitmap

            // No need to recycle capturedBitmaps here as they are recycled in resetCapture or onDestroy
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to create collage: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            Log.e("CollageCreation", "Error creating collage", e)
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
                    Toast.makeText(requireContext(), "Photo collage saved to Pictures/Collages!", Toast.LENGTH_LONG).show()
                }
                findNavController().popBackStack() // Navigate back after saving
            } ?: run {
                Toast.makeText(requireContext(), "Failed to create file.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to save collage: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("SaveCollage", "Error saving collage", e)
        }
    }

    private fun updateUI() {
        val totalImages = getTotalImageCount()
        val nextCameraIndex = getNextEmptyCameraSlotIndex()

        // Update capture button text and visibility
        when {
            isRetakeMode -> {
                captureButton.text = "Take Photo"
                captureButton.visibility = View.VISIBLE
            }
            nextCameraIndex != -1 -> {
                captureButton.text = "Capture Photo"
                captureButton.visibility = View.VISIBLE
            }
            totalImages >= maxCaptures -> {
                captureButton.text = "Start Over" // All slots filled, option to reset
                captureButton.visibility = View.VISIBLE
            }
            else -> {
                // Should ideally not happen if maxCaptures > 0 and not all filled
                captureButton.visibility = View.GONE
            }
        }

        // Manage visibility of preview views and overlay images
        previewViews.forEachIndexed { index, previewView ->
            if (isRetakeMode) {
                // In retake mode, only show the preview for the retake index
                previewView.visibility = if (index == retakeIndex) View.VISIBLE else View.GONE
                imageOverlays[index].visibility = if (index == retakeIndex) View.GONE else View.VISIBLE // Keep others visible
            } else if (totalImages >= maxCaptures) {
                // All slots filled, hide all previews and show all overlays
                previewView.visibility = View.GONE
                imageOverlays[index].visibility = View.VISIBLE
            } else {
                // Normal capture mode, show preview for the current capture index, hide others
                previewView.visibility = if (index == currentCaptureIndex) View.VISIBLE else View.GONE
                // Hide overlay for the current capture slot if it's empty, show for filled slots
                imageOverlays[index].visibility = if (imageSources[index] != ImageSource.EMPTY) View.VISIBLE else View.GONE
            }
        }

        // Show save button only when all images are captured/selected
        binding.saveButton.visibility = if (totalImages >= maxCaptures) View.VISIBLE else View.GONE
    }

    private fun resetCapture() {
        currentCaptureIndex = 0
        isRetakeMode = false
        retakeIndex = -1
        retakeInProgress = false

        hideAllDustbinIcons()

        // Recycle and clear all bitmaps and URIs
        capturedBitmaps.forEachIndexed { index, bitmap ->
            bitmap?.recycle() // Recycle if not null
            capturedBitmaps[index] = null
            galleryUris[index] = null
            imageSources[index] = ImageSource.EMPTY
        }

        imageOverlays.forEach {
            it.setImageBitmap(null)
            it.visibility = View.GONE
        }

        binding.saveButton.visibility = View.GONE
        updateUI() // Update UI after reset
        startCamera() // Restart camera for the first slot
    }

    override fun onDestroy() {
        super.onDestroy()
        // Recycle all bitmaps to prevent memory leaks
        capturedBitmaps.forEach { it?.recycle() }
        capturedBitmaps.clear()
        galleryUris.clear()
        imageSources.clear()
    }
}