package com.o7solutions.task

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
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
import com.o7solutions.task.databinding.FragmentTwoBinding
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

class TwoFragment : Fragment() {

    private lateinit var previewViews: List<PreviewView>
    private lateinit var imageOverlays: List<ImageView>
    private lateinit var dustbinIcons: List<ImageView>
    private lateinit var captureButton: Button

    private lateinit var db: DatabaseDB
    private var imageCapture: ImageCapture? = null

    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    // Updated to handle both camera and gallery images
    private val imageSources = mutableListOf<ImageSource>() // Track source of each image
    private val capturedBitmaps = mutableListOf<Bitmap>()
    private val galleryUris = mutableListOf<Uri?>()

    private lateinit var binding: FragmentTwoBinding

    private var currentCaptureIndex = 0
    private val maxCaptures = 2
    private var isRetakeMode = false
    private var retakeFlag = false
    private var retakeIndex = -1
    private var setImageIndex = 0

    // Enum to track image source
    enum class ImageSource {
        CAMERA,
        GALLERY,
        EMPTY
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentTwoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        db = DatabaseDB.getInstance(requireContext())

        // Initialize lists
        initializeLists()

        // Set up gallery button listeners
        binding.addImageBtn1.setOnClickListener {
            openGallery(0)
        }

        binding.addImageBtn2.setOnClickListener {
            openGallery(1)
        }

        previewViews = listOf(
            view.findViewById(R.id.preview1),
            view.findViewById(R.id.preview2)
        )
        imageOverlays = listOf(
            view.findViewById(R.id.overlay1),
            view.findViewById(R.id.overlay2)
        )

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
                val nextCameraIndex = getNextCameraSlotIndex()
                if (nextCameraIndex != -1) {
                    currentCaptureIndex = nextCameraIndex
                    captureImage()
                } else {
                    resetCapture()
                }
            }
        }

        binding.switchButton.setOnClickListener {
            switchCamera()
        }

        // Set up long press listeners for image overlays
        setupLongPressListeners()

        // Set up dustbin icon click listeners
        setupDustbinClickListeners()

        updateUI()
        startCamera()
    }

    private fun initializeLists() {
        // Initialize with empty slots
        repeat(maxCaptures) {
            imageSources.add(ImageSource.EMPTY)
            galleryUris.add(null)
        }
    }

    private fun openGallery(index: Int) {
        if (retakeFlag && !isRetakeMode) {
            Toast.makeText(requireContext(), "Please complete current retake before selecting another photo", Toast.LENGTH_SHORT).show()
            return
        }

        setImageIndex = index
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        imagePickerLauncher.launch(intent)
    }

    private fun getNextCameraSlotIndex(): Int {
        for (i in 0 until maxCaptures) {
            if (imageSources[i] == ImageSource.EMPTY) {
                return i
            }
        }
        return -1 // All slots filled
    }

    private fun getTotalImageCount(): Int {
        return imageSources.count { it != ImageSource.EMPTY }
    }

    private fun setupLongPressListeners() {
        imageOverlays.forEachIndexed { index, imageView ->
            imageView.setOnLongClickListener {
                if (retakeFlag) {
                    Toast.makeText(requireContext(), "Please complete current retake before selecting another photo", Toast.LENGTH_SHORT).show()
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
        isRetakeMode = true
        retakeIndex = index
        retakeFlag = true

        hideAllDustbinIcons()

        // Clear the image at this index
        clearImageAtIndex(index)

        // Show the preview for the specific index
        previewViews.forEachIndexed { i, previewView ->
            previewView.visibility = if (i == index) View.VISIBLE else View.GONE
        }

        setupCameraForRetake()
        updateUI()

        Toast.makeText(requireContext(), "Retaking photo ", Toast.LENGTH_SHORT).show()
    }

    private fun clearImageAtIndex(index: Int) {
        // Clear bitmap if it's a camera image
        if (imageSources[index] == ImageSource.CAMERA && index < capturedBitmaps.size) {
            capturedBitmaps[index].recycle()
        }

        // Clear gallery URI
        if (index < galleryUris.size) {
            galleryUris[index] = null
        }

        // Reset source
        imageSources[index] = ImageSource.EMPTY

        // Clear overlay
        imageOverlays[index].visibility = View.GONE
        imageOverlays[index].setImageBitmap(null)
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

                        // Store the bitmap at the retake index
                        while (capturedBitmaps.size <= retakeIndex) {
                            capturedBitmaps.add(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                        }
                        capturedBitmaps[retakeIndex] = bitmap

                        // Update source tracking
                        imageSources[retakeIndex] = ImageSource.CAMERA

                        // Update the overlay
                        imageOverlays[retakeIndex].setImageBitmap(bitmap)
                        imageOverlays[retakeIndex].visibility = View.VISIBLE

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

        hideAllDustbinIcons()

        // Update current capture index for next camera capture
        currentCaptureIndex = getNextCameraSlotIndex()

        if (getTotalImageCount() >= maxCaptures) {
            previewViews.forEach { it.visibility = View.GONE }
            binding.saveButton.visibility = View.VISIBLE
            binding.saveButton.setOnClickListener {
                createAndSaveCollage()
            }
        } else if (currentCaptureIndex != -1) {
            setupCameraForNormalMode()
        }
    }

    private fun switchCamera() {
        try {
            // Toggle between front and back camera
            currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            // Get camera provider and restart camera with new selector
            val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()

                    // Setup camera components
                    val preview = Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .build()

                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setJpegQuality(95)
                        .build()

                    // Unbind all previous use cases
                    cameraProvider.unbindAll()

                    // Determine which preview to use
                    val activeIndex = if (isRetakeMode) retakeIndex else currentCaptureIndex

                    if (activeIndex >= 0 && activeIndex < previewViews.size) {
                        // Bind camera to lifecycle with new selector
                        preview.setSurfaceProvider(previewViews[activeIndex].surfaceProvider)
                        cameraProvider.bindToLifecycle(this, currentCameraSelector, preview, imageCapture)

                        // Show feedback to user
                        val cameraType = if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                            "Front"
                        } else {
                            "Back"
                        }
//                        Toast.makeText(requireContext(), "Switched to $cameraType Camera", Toast.LENGTH_SHORT).show()
                    }

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

        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Camera switch failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setJpegQuality(95)
            .build()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()

            val activeIndex = if (isRetakeMode) retakeIndex else currentCaptureIndex

            if (activeIndex >= 0 && activeIndex < previewViews.size) {
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

                        // Ensure lists are large enough
                        while (capturedBitmaps.size <= currentCaptureIndex) {
                            capturedBitmaps.add(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                        }

                        capturedBitmaps[currentCaptureIndex] = bitmap
                        imageSources[currentCaptureIndex] = ImageSource.CAMERA

                        imageOverlays[currentCaptureIndex].setImageBitmap(bitmap)
                        imageOverlays[currentCaptureIndex].visibility = View.VISIBLE

                        // Find next available camera slot
                        val nextIndex = getNextCameraSlotIndex()
                        if (nextIndex != -1) {
                            currentCaptureIndex = nextIndex
                            switchToNextPreview()
                            binding.saveButton.visibility = View.GONE
                        } else {
                            // All slots filled
                            previewViews.forEach { it.visibility = View.GONE }
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
        if (currentCaptureIndex < maxCaptures && currentCaptureIndex >= 0) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
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
        try {
            // Get all final bitmaps (both camera and gallery)
            val finalBitmaps = mutableListOf<Bitmap>()

            for (i in 0 until maxCaptures) {
                when (imageSources[i]) {
                    ImageSource.CAMERA -> {
                        if (i < capturedBitmaps.size) {
                            finalBitmaps.add(capturedBitmaps[i])
                        }
                    }
                    ImageSource.GALLERY -> {
                        galleryUris[i]?.let { uri ->
                            val bitmap = getBitmapFromUri(uri)
                            bitmap?.let { finalBitmaps.add(it) }
                        }
                    }
                    ImageSource.EMPTY -> {
                        // Skip empty slots
                    }
                }
            }

            if (finalBitmaps.isEmpty()) {
                Toast.makeText(requireContext(), "No images to create collage", Toast.LENGTH_SHORT).show()
                return
            }

            // Use the first image dimensions as reference
            val firstImage = finalBitmaps[0]
            val imageWidth = firstImage.width
            val imageHeight = firstImage.height

            // Create side-by-side collage for 2 images
            val collageWidth = imageWidth * 2
            val collageHeight = imageHeight

            val result = Bitmap.createBitmap(collageWidth, collageHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)

            // Draw images side by side
            finalBitmaps.forEachIndexed { index, bitmap ->
                val xPosition = (index * imageWidth).toFloat()
                val yPosition = 0f

                // Scale bitmap to fit if needed
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, imageWidth, imageHeight, true)
                canvas.drawBitmap(scaledBitmap, xPosition, yPosition, null)

                // Clean up scaled bitmap if it's different from original
                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }
            }

            saveBitmapToStorage(result)
            result.recycle()

            // Clean up gallery bitmaps that were created
            finalBitmaps.forEachIndexed { index, bitmap ->
                if (imageSources[index] == ImageSource.GALLERY) {
                    bitmap.recycle()
                }
            }

        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to create collage: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = requireContext().contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            Log.e("TwoFragment", "Error loading bitmap from URI: ${e.message}")
            null
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

                    Toast.makeText(requireContext(), "Photo collage saved to Pictures/Collages!", Toast.LENGTH_LONG).show()
                    findNavController().popBackStack()
                }
            } ?: run {
                Toast.makeText(requireContext(), "Failed to create file.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to save collage: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI() {
        val totalImages = getTotalImageCount()
        val nextCameraIndex = getNextCameraSlotIndex()

        when {
            isRetakeMode -> {
                captureButton.text = "Retake Photo"
                captureButton.visibility = View.VISIBLE
            }
            nextCameraIndex != -1 -> {
                captureButton.text = "Capture Photo"
                captureButton.visibility = View.VISIBLE
            }
            totalImages >= maxCaptures -> {
                captureButton.text = "Start Over"
                captureButton.visibility = View.VISIBLE
            }
            else -> {
                captureButton.visibility = View.GONE
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

        // Show save button if all slots are filled
        if (totalImages >= maxCaptures) {
            binding.saveButton.visibility = View.VISIBLE
        } else {
            binding.saveButton.visibility = View.GONE
        }
    }

    private fun resetCapture() {
        currentCaptureIndex = 0
        isRetakeMode = false
        retakeIndex = -1
        retakeFlag = false

        hideAllDustbinIcons()

        // Clean up camera bitmaps
        capturedBitmaps.forEach { it.recycle() }
        capturedBitmaps.clear()

        // Reset all tracking lists
        imageSources.clear()
        galleryUris.clear()
        initializeLists()

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

    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = result.data?.data
                if (uri != null) {
                    val targetIndex = setImageIndex

                    // Store the URI and update source tracking
                    galleryUris[targetIndex] = uri
                    imageSources[targetIndex] = ImageSource.GALLERY

                    // Load and display the image
                    Glide.with(requireContext())
                        .load(uri)
                        .into(imageOverlays[targetIndex])

                    // Show the overlay
                    imageOverlays[targetIndex].visibility = View.VISIBLE

                    setImageIndex = 0

                    // Handle retake mode specifically
                    if (isRetakeMode && targetIndex == retakeIndex) {
                        // Complete the retake process
                        exitRetakeMode()
                    } else {
                        // Update current capture index for camera
                        currentCaptureIndex = getNextCameraSlotIndex()

                        // Check if we have all images needed
                        val totalImages = getTotalImageCount()
                        if (totalImages >= maxCaptures) {
                            // Hide all previews and show save button
                            previewViews.forEach { it.visibility = View.GONE }
                            binding.saveButton.visibility = View.VISIBLE
                            binding.saveButton.setOnClickListener {
                                createAndSaveCollage()
                            }
                        } else if (currentCaptureIndex != -1) {
                            // Continue with camera for remaining slots
                            switchToNextPreview()
                        }
                    }

                    updateUI()
                } else {
                    Log.e("ImagePicker", "Failed to get image URI")
                }
            }
        }

    fun printLogs() {
        Log.d("Current capture index:",currentCaptureIndex.toString())
        Log.d("Retake mode",retakeIndex.toString())
        Log.d("Retake Flag",retakeFlag.toString())
    }

    private fun createTempFile(): File {
        val dir = requireContext().cacheDir
        return File.createTempFile("temp_image", ".jpg", dir)
    }
}