package com.o7solutions.task

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.o7solutions.task.database.DatabaseDB
import com.o7solutions.task.database.ImageEntity
import com.o7solutions.task.databinding.FragmentTwoBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"
private const val TAG = "TwoFragment"
private const val MAX_CAPTURES = 2
private const val JPEG_QUALITY = 95

class TwoFragment : Fragment() {
    private var param1: String? = null
    private var param2: String? = null

    private lateinit var previewViews: List<PreviewView>
    private lateinit var imageOverlays: List<ImageView>
    private lateinit var dustbinIcons: List<ImageView>
    private lateinit var captureButton: Button

    private lateinit var db: DatabaseDB
    private var imageCapture: ImageCapture? = null
    private val capturedBitmaps = mutableListOf<Bitmap?>()
    private val galleryUris = mutableListOf<Uri?>()
    private lateinit var binding: FragmentTwoBinding

    // State management variables
    private var isRetakeMode = false
    private var retakeIndex = -1
    private var isProcessingCapture = false
    private var setImageIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            param1 = it.getString(ARG_PARAM1)
            param2 = it.getString(ARG_PARAM2)
        }

        // Initialize lists with null values
        initializeImageLists()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentTwoBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews(view)
        setupClickListeners()
        setupDatabase()

        updateUI()
        startCamera()
    }

    private fun initializeImageLists() {
        // Initialize with null values for MAX_CAPTURES slots
        capturedBitmaps.clear()
        galleryUris.clear()
        repeat(MAX_CAPTURES) {
            capturedBitmaps.add(null)
            galleryUris.add(null)
        }
    }

    private fun setupViews(view: View) {
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

        previewViews.forEach { previewView ->
            previewView.scaleType = PreviewView.ScaleType.FILL_START
        }
    }

    private fun setupClickListeners() {
        // Gallery selection buttons
        binding.addImageBtn1.setOnClickListener {
            selectImageFromGallery(0)
        }

        binding.addImageBtn2.setOnClickListener {
            selectImageFromGallery(1)
        }

        // Capture button
        captureButton.setOnClickListener {
            when {
                isRetakeMode -> retakeImage()
                getTotalImageCount() >= MAX_CAPTURES -> resetCapture()
                else -> captureImage()
            }
        }

        // Image overlay click listeners
        binding.overlay1.setOnClickListener {
            Log.d(TAG, "Overlay1 clicked")
        }

        setupLongPressListeners()
        setupDustbinClickListeners()
    }

    private fun setupDatabase() {
        db = DatabaseDB.getInstance(requireContext())
    }

    private fun selectImageFromGallery(index: Int) {
//        if (isRetakeMode) {
//            showToast("Please complete current retake before selecting another photo")
//            return
//        }

        setImageIndex = index
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        imagePickerLauncher.launch(intent)
    }

    // State management functions
    private fun hasValidImageAtIndex(index: Int): Boolean {
        if (index < 0 || index >= MAX_CAPTURES) return false

        val hasCameraImage = index < capturedBitmaps.size &&
                capturedBitmaps[index] != null &&
                !capturedBitmaps[index]!!.isRecycled
        val hasGalleryImage = index < galleryUris.size && galleryUris[index] != null

        return hasCameraImage || hasGalleryImage
    }

    private fun getTotalImageCount(): Int {
        return (0 until MAX_CAPTURES).count { index ->
            hasValidImageAtIndex(index)
        }
    }

    private fun getNextAvailableIndex(): Int {
        return (0 until MAX_CAPTURES).firstOrNull { index ->
            !hasValidImageAtIndex(index)
        } ?: -1
    }

    private fun clearImageAtIndex(index: Int) {
        if (index < 0 || index >= MAX_CAPTURES) return

        // Clear camera bitmap
        if (index < capturedBitmaps.size && capturedBitmaps[index] != null) {
            capturedBitmaps[index]?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            capturedBitmaps[index] = null
        }

        // Clear gallery URI
        if (index < galleryUris.size) {
            galleryUris[index] = null
        }

        // Clear UI
        if (index < imageOverlays.size) {
            imageOverlays[index].setImageBitmap(null)
            imageOverlays[index].visibility = View.GONE
        }
    }

    private fun setImageAtIndex(index: Int, bitmap: Bitmap) {
        if (index < 0 || index >= MAX_CAPTURES) return

        // Ensure lists are large enough
        while (capturedBitmaps.size <= index) {
            capturedBitmaps.add(null)
        }

        // Clear any existing image at this index first
        clearImageAtIndex(index)

        // Set new image
        capturedBitmaps[index] = bitmap
        imageOverlays[index].setImageBitmap(bitmap)
        imageOverlays[index].visibility = View.VISIBLE
    }

    private fun setGalleryImageAtIndex(index: Int, uri: Uri) {
        if (index < 0 || index >= MAX_CAPTURES) return

        // Ensure lists are large enough
        while (galleryUris.size <= index) {
            galleryUris.add(null)
        }

        // Clear any existing camera image at this index
        if (index < capturedBitmaps.size && capturedBitmaps[index] != null) {
            capturedBitmaps[index]?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            capturedBitmaps[index] = null
        }

        // Set gallery image
        galleryUris[index] = uri

        // Load and display the image
        Glide.with(requireContext())
            .load(uri)
            .into(imageOverlays[index])

        imageOverlays[index].visibility = View.VISIBLE
    }

    // Retake mode functions
    private fun enterRetakeMode(index: Int) {
        if (index < 0 || index >= MAX_CAPTURES) {
            Log.e(TAG, "Invalid retake index: $index")
            return
        }

        if (!hasValidImageAtIndex(index)) {
            Log.e(TAG, "No image at index $index to retake")
            return
        }

        isRetakeMode = true
        retakeIndex = index

        hideAllDustbinIcons()
        clearImageAtIndex(index)

        updateUI()
        setupCameraForIndex(index)

        showToast("Retaking photo ${index + 1}")
    }

    private fun exitRetakeMode() {
        isRetakeMode = false
        retakeIndex = -1

        hideAllDustbinIcons()
        updateUI()

        val totalImages = getTotalImageCount()
        if (totalImages >= MAX_CAPTURES) {
            showAllCapturedImages()
            showSaveButton()
        } else {
            val nextIndex = getNextAvailableIndex()
            if (nextIndex >= 0) {
                setupCameraForIndex(nextIndex)
            }
        }
    }

    private fun showAllCapturedImages() {
        previewViews.forEach { it.visibility = View.GONE }
    }

    private fun showSaveButton() {
        binding.saveButton.visibility = View.VISIBLE
        binding.saveButton.setOnClickListener {
            createAndSaveCollage()
        }
    }

    // Long press and dustbin functionality
    private fun setupLongPressListeners() {
        imageOverlays.forEachIndexed { index, imageView ->
            imageView.setOnLongClickListener {
                if (isRetakeMode) {
                    showToast("Please complete current retake before selecting another photo")
                    false
                } else if (hasValidImageAtIndex(index)) {
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
                enterRetakeMode(index)
            }
        }
    }

    private fun showDustbinIcon(index: Int) {
        hideAllDustbinIcons()
        if (index < dustbinIcons.size) {
            dustbinIcons[index].visibility = View.VISIBLE
            showToast("Tap dustbin to retake photo ${index + 1}")
        }
    }

    private fun hideDustbinIcon(index: Int) {
        if (index < dustbinIcons.size) {
            dustbinIcons[index].visibility = View.GONE
        }
    }

    private fun hideAllDustbinIcons() {
        dustbinIcons.forEach { it.visibility = View.GONE }
    }

    // Camera functionality
    private fun startCamera() {
        val nextIndex = getNextAvailableIndex()
        if (nextIndex >= 0) {
            setupCameraForIndex(nextIndex)
        }
    }

    private fun setupCameraForIndex(index: Int) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                bindCameraToPreview(cameraProvider, index)
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization failed", e)
                showToast("Camera initialization failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraToPreview(cameraProvider: ProcessCameraProvider, previewIndex: Int) {
        if (previewIndex < 0 || previewIndex >= previewViews.size) {
            Log.e(TAG, "Invalid preview index: $previewIndex")
            return
        }

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setJpegQuality(JPEG_QUALITY)
            .build()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            preview.setSurfaceProvider(previewViews[previewIndex].surfaceProvider)
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
            showToast("Camera binding failed: ${e.message}")
        }
    }

    // Image capture functions
    private fun captureImage() {
        val imageCapture = this.imageCapture ?: run {
            Log.e(TAG, "ImageCapture is null")
            showToast("Camera not ready")
            return
        }

        if (isProcessingCapture) {
            Log.w(TAG, "Already processing capture")
            return
        }

        val targetIndex = if (isRetakeMode) retakeIndex else getNextAvailableIndex()
        if (targetIndex < 0) {
            Log.e(TAG, "No available slot for capture")
            return
        }

        isProcessingCapture = true
        updateUI()

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val bitmap = convertImageProxyToBitmap(imageProxy)
                        setImageAtIndex(targetIndex, bitmap)

                        if (isRetakeMode) {
                            exitRetakeMode()
                        } else {
                            handleNormalCaptureComplete()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Image processing failed", e)
                        showToast("Image processing failed: ${e.message}")
                    } finally {
                        imageProxy.close()
                        isProcessingCapture = false
                        updateUI()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    isProcessingCapture = false
                    Log.e(TAG, "Capture failed", exception)
                    showToast("Capture failed: ${exception.message}")
                    updateUI()
                }
            }
        )
    }

    private fun retakeImage() {
        captureImage() // Uses the same logic but with retake mode state
    }

    private fun handleNormalCaptureComplete() {
        val totalImages = getTotalImageCount()
        if (totalImages >= MAX_CAPTURES) {
            showAllCapturedImages()
            showSaveButton()
        } else {
            val nextIndex = getNextAvailableIndex()
            if (nextIndex >= 0) {
                setupCameraForIndex(nextIndex)
            }
        }
    }

    // UI update functions
    private fun updateUI() {
        updateCaptureButton()
        updatePreviewVisibility()
    }

    private fun updateCaptureButton() {
        val totalImages = getTotalImageCount()

        captureButton.text = when {
            isRetakeMode -> "Retake Photo ${retakeIndex + 1}"
            totalImages >= MAX_CAPTURES -> "Start Over"
            else -> {
                val nextIndex = getNextAvailableIndex()
                if (nextIndex >= 0) "Capture Photo ${nextIndex + 1}" else "Start Over"
            }
        }

        captureButton.isEnabled = !isProcessingCapture
    }

    private fun updatePreviewVisibility() {
        val activeIndex = when {
            isRetakeMode -> retakeIndex
            getTotalImageCount() >= MAX_CAPTURES -> -1 // Hide all previews
            else -> getNextAvailableIndex()
        }

        previewViews.forEachIndexed { index, previewView ->
            previewView.visibility = if (index == activeIndex) View.VISIBLE else View.GONE
        }
    }

    // Image processing functions
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

    // Collage creation
    private fun createAndSaveCollage() {
        try {
            val validImages = mutableListOf<Bitmap>()

            // Collect all valid images
            for (i in 0 until MAX_CAPTURES) {
                when {
                    // Camera image
                    i < capturedBitmaps.size && capturedBitmaps[i] != null && !capturedBitmaps[i]!!.isRecycled -> {
                        validImages.add(capturedBitmaps[i]!!)
                    }
                    // Gallery image
                    i < galleryUris.size && galleryUris[i] != null -> {
                        loadBitmapFromUri(galleryUris[i]!!)?.let { bitmap ->
                            validImages.add(bitmap)
                        }
                    }
                }
            }

            if (validImages.isEmpty()) {
                showToast("No images to create collage")
                return
            }

            val collage = createCollageBitmap(validImages)
            saveBitmapToStorage(collage)
            collage.recycle()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create collage", e)
            showToast("Failed to create collage: ${e.message}")
        }
    }

    private fun createCollageBitmap(images: List<Bitmap>): Bitmap {
        if (images.isEmpty()) throw IllegalArgumentException("No images provided")

        val firstImage = images[0]
        val imageWidth = firstImage.width
        val imageHeight = firstImage.height

        val collageWidth = imageWidth * images.size
        val collageHeight = imageHeight

        val result = Bitmap.createBitmap(collageWidth, collageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        images.forEachIndexed { index, bitmap ->
            val scaledBitmap = if (bitmap.width != imageWidth || bitmap.height != imageHeight) {
                Bitmap.createScaledBitmap(bitmap, imageWidth, imageHeight, true)
            } else {
                bitmap
            }

            val xPosition = (index * imageWidth).toFloat()
            canvas.drawBitmap(scaledBitmap, xPosition, 0f, null)

            // Clean up scaled bitmap if it's different from original
            if (scaledBitmap != bitmap && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
        }

        return result
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap from URI: $uri", e)
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
            uri?.let { savedUri ->
                resolver.openOutputStream(savedUri)?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)

                    // Save to database
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            db.databaseDao().insertImage(
                                ImageEntity(
                                    name = filename,
                                    path = savedUri.toString(),
                                    timeStamp = System.currentTimeMillis()
                                )
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save to database", e)
                        }
                    }

                    showToast("2-Photo collage saved to Pictures/Collages!")
                }
            } ?: run {
                showToast("Failed to create file")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save collage", e)
            showToast("Failed to save collage: ${e.message}")
        }
    }

    // Reset functionality
    private fun resetCapture() {
        isRetakeMode = false
        retakeIndex = -1
        isProcessingCapture = false

        hideAllDustbinIcons()

        // Clean up bitmaps
        capturedBitmaps.forEach { bitmap ->
            bitmap?.let {
                if (!it.isRecycled) {
                    it.recycle()
                }
            }
        }

        // Reset lists
        initializeImageLists()

        // Clear UI
        imageOverlays.forEach {
            it.setImageBitmap(null)
            it.visibility = View.GONE
        }

        binding.saveButton.visibility = View.GONE
        updateUI()
        startCamera()
    }

    // Gallery picker result handler
    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = result.data?.data
                if (uri != null) {
                    setGalleryImageAtIndex(setImageIndex, uri)

                    // Check if we have all images needed
                    val totalImages = getTotalImageCount()
                    if (totalImages >= MAX_CAPTURES) {
                        showAllCapturedImages()
                        showSaveButton()
                    } else {
                        val nextIndex = getNextAvailableIndex()
                        if (nextIndex >= 0) {
                            setupCameraForIndex(nextIndex)
                        }
                    }
                    exitRetakeMode()

                    updateUI()
                } else {
                    Log.e(TAG, "Failed to get image URI from gallery")
                    showToast("Failed to select image from gallery")
                }
            }
            setImageIndex = 0 // Reset
        }

    // Utility functions
    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    // Lifecycle management
    override fun onDestroy() {
        super.onDestroy()
        capturedBitmaps.forEach { bitmap ->
            bitmap?.let {
                if (!it.isRecycled) {
                    it.recycle()
                }
            }
        }
        capturedBitmaps.clear()
        galleryUris.clear()
    }

    companion object {
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