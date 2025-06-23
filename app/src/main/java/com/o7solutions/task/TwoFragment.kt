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

class TwoFragment : Fragment() {
    private var param1: String? = null
    private var param2: String? = null

    private lateinit var previewViews: List<PreviewView>
    private lateinit var imageOverlays: List<ImageView>
    private lateinit var dustbinIcons: List<ImageView>
    private lateinit var captureButton: Button

    private lateinit var db: DatabaseDB
    private var imageCapture: ImageCapture? = null
    private val capturedBitmaps = mutableListOf<Bitmap>()
    private val galleryUris = mutableListOf<Uri?>(null, null) // Track gallery URIs
    private lateinit var binding: FragmentTwoBinding

    private var currentCaptureIndex = 0
    private val maxCaptures = 2
    private var isRetakeMode = false
    private var retakeFlag = false
    private var setImageIndex = 0
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

        binding.addImageBtn1.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            imagePickerLauncher.launch(intent)
            setImageIndex = 1
        }

        binding.addImageBtn2.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            imagePickerLauncher.launch(intent)
            setImageIndex = 2
        }



        db = DatabaseDB.getInstance(requireContext())
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
                    Toast.makeText(
                        requireContext(),
                        "Please complete current retake before selecting another photo",
                        Toast.LENGTH_SHORT
                    ).show()
                    false
                } else if (hasImageAtIndex(index)) {
                    showDustbinIcon(index)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun hasImageAtIndex(index: Int): Boolean {
        return (capturedBitmaps.size > index) || (galleryUris.size > index && galleryUris[index] != null)
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

        Toast.makeText(
            requireContext(),
            "Tap dustbin to retake photo ${index + 1}",
            Toast.LENGTH_SHORT
        ).show()
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

        // Clear the existing image at this index
        if (capturedBitmaps.size > index) {
            capturedBitmaps[index].recycle()
            capturedBitmaps.removeAt(index)
        }

        // Clear gallery URI at this index
        if (galleryUris.size > index) {
            galleryUris[index] = null
        }

        // Hide the overlay for the image being retaken
        imageOverlays[index].visibility = View.GONE
        imageOverlays[index].setImageBitmap(null)

        // Show the preview for the specific index
        previewViews.forEachIndexed { i, previewView ->
            previewView.visibility = if (i == index) View.VISIBLE else View.GONE
        }

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
                Toast.makeText(
                    requireContext(),
                    "Camera initialization failed: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
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

                        // Replace/add the bitmap at the retake index
                        while (capturedBitmaps.size <= retakeIndex) {
                            capturedBitmaps.add(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                        }
                        capturedBitmaps[retakeIndex] = bitmap

                        // Update the overlay
                        imageOverlays[retakeIndex].setImageBitmap(bitmap)
                        imageOverlays[retakeIndex].visibility = View.VISIBLE

                        exitRetakeMode()
                        updateUI()
                    } catch (e: Exception) {
                        Toast.makeText(
                            requireContext(),
                            "Image processing failed: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    } finally {
                        captureButton.isEnabled = true
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    captureButton.isEnabled = true
                    Toast.makeText(
                        requireContext(),
                        "Capture failed: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
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

        // Count total images (camera + gallery)
        val totalImages = getTotalImageCount()

        if (totalImages >= maxCaptures) {
            previewViews.forEach { it.visibility = View.GONE }
            binding.saveButton.visibility = View.VISIBLE
            binding.saveButton.setOnClickListener {
                createAndSaveCollage()
            }
        } else {
            setupCameraForNormalMode()
        }
    }

    private fun getTotalImageCount(): Int {
        var count = 0
        for (i in 0 until maxCaptures) {
            if ((capturedBitmaps.size > i) || (galleryUris.size > i && galleryUris[i] != null)) {
                count++
            }
        }
        return count
    }

    private fun setupCameraForNormalMode() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                setupCamera(cameraProvider)
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Camera initialization failed: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
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
                Toast.makeText(
                    requireContext(),
                    "Camera initialization failed: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun setupCamera(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setJpegQuality(95)
            .build()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()

            val activeIndex = if (isRetakeMode) retakeIndex else getNextAvailableIndex()

            if (activeIndex >= 0 && activeIndex < previewViews.size) {
                preview.setSurfaceProvider(previewViews[activeIndex].surfaceProvider)
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            }
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Camera binding failed: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun getNextAvailableIndex(): Int {
        for (i in 0 until maxCaptures) {
            if (!hasImageAtIndex(i)) {
                return i
            }
        }
        return -1
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

                        val targetIndex = getNextAvailableIndex()
                        if (targetIndex >= 0) {
                            // Ensure lists are large enough
                            while (capturedBitmaps.size <= targetIndex) {
                                capturedBitmaps.add(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                            }

                            capturedBitmaps[targetIndex] = bitmap
                            imageOverlays[targetIndex].setImageBitmap(bitmap)
                            imageOverlays[targetIndex].visibility = View.VISIBLE

                            currentCaptureIndex = targetIndex + 1

                            val totalImages = getTotalImageCount()
                            if (totalImages < maxCaptures) {
                                switchToNextPreview()
                                binding.saveButton.visibility = View.GONE
                            } else {
                                binding.saveButton.visibility = View.VISIBLE
                                binding.saveButton.setOnClickListener {
                                    createAndSaveCollage()
                                }
                            }
                        }

                        updateUI()
                    } catch (e: Exception) {
                        Toast.makeText(
                            requireContext(),
                            "Image processing failed: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    } finally {
                        captureButton.isEnabled = true
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    captureButton.isEnabled = true
                    Toast.makeText(
                        requireContext(),
                        "Capture failed: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    updateUI()
                }
            }
        )
    }

    private fun switchToNextPreview() {
        val nextIndex = getNextAvailableIndex()
        if (nextIndex >= 0 && nextIndex < maxCaptures) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                        .build()
                    preview.setSurfaceProvider(previewViews[nextIndex].surfaceProvider)

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                } catch (e: Exception) {
                    Toast.makeText(
                        requireContext(),
                        "Failed to switch preview: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
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
            val collageBitmaps = mutableListOf<Bitmap>()

            // Collect all images (camera and gallery)
            for (i in 0 until maxCaptures) {
                when {
                    capturedBitmaps.size > i -> {
                        collageBitmaps.add(capturedBitmaps[i])
                    }
                    galleryUris.size > i && galleryUris[i] != null -> {
                        val bitmap = loadBitmapFromUri(galleryUris[i]!!)
                        bitmap?.let { collageBitmaps.add(it) }
                    }
                }
            }

            if (collageBitmaps.isEmpty()) return

            // Use the first image dimensions as reference
            val firstImage = collageBitmaps[0]
            val imageWidth = firstImage.width
            val imageHeight = firstImage.height

            // Create collage with images side by side
            val collageWidth = imageWidth * collageBitmaps.size
            val collageHeight = imageHeight

            val result = Bitmap.createBitmap(collageWidth, collageHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)

            collageBitmaps.forEachIndexed { index, bitmap ->
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, imageWidth, imageHeight, true)
                val xPosition = (index * imageWidth).toFloat()
                canvas.drawBitmap(scaledBitmap, xPosition, 0f, null)

                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }
            }

            saveBitmapToStorage(result)
            result.recycle()
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Failed to create collage: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            Log.e("TwoFragment", "Failed to load bitmap from URI: ${e.message}")
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
                    db.databaseDao().insertImage(
                        ImageEntity(
                            name = filename,
                            path = it.toString(),
                            timeStamp = System.currentTimeMillis()
                        )
                    )
                    Toast.makeText(
                        requireContext(),
                        "2-Photo collage saved to Pictures/Collages!",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } ?: run {
                Toast.makeText(requireContext(), "Failed to create file.", Toast.LENGTH_SHORT)
                    .show()
            }
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Failed to save collage: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateUI() {
        val totalImages = getTotalImageCount()
        val nextIndex = getNextAvailableIndex()

        when {
            isRetakeMode -> {
                captureButton.text = "Retake Photo ${retakeIndex + 1}"
            }
            nextIndex == 0 -> {
                captureButton.text = "Capture Photo 1"
            }
            nextIndex == 1 -> {
                captureButton.text = "Capture Photo 2"
            }
            totalImages >= maxCaptures -> {
                captureButton.text = "Start Over"
            }
            else -> {
                captureButton.text = "Capture Photo ${nextIndex + 1}"
            }
        }

        // Show/hide preview views based on current state
        if (isRetakeMode) {
            previewViews.forEachIndexed { index, previewView ->
                previewView.visibility = if (index == retakeIndex) View.VISIBLE else View.GONE
            }
        } else {
            previewViews.forEachIndexed { index, previewView ->
                previewView.visibility = if (index == nextIndex && nextIndex >= 0) View.VISIBLE else View.GONE
            }
        }
    }

    private fun resetCapture() {
        currentCaptureIndex = 0
        isRetakeMode = false
        retakeIndex = -1
        retakeFlag = false

        hideAllDustbinIcons()

        capturedBitmaps.forEach { it.recycle() }
        capturedBitmaps.clear()

        // Clear gallery URIs
        galleryUris.clear()
        galleryUris.addAll(listOf(null, null))

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
        capturedBitmaps.forEach { it.recycle() }
        capturedBitmaps.clear()
    }

    private fun createTempFile(): File {
        val dir = requireContext().cacheDir
        return File.createTempFile("temp_image", ".jpg", dir)
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

    val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = result.data?.data
                if (uri != null) {
                    val targetIndex = setImageIndex - 1 // Convert to 0-based index

                    // Ensure galleryUris list is large enough
                    while (galleryUris.size <= targetIndex) {
                        galleryUris.add(null)
                    }

                    // Store the URI
                    galleryUris[targetIndex] = uri

                    // Load and display the image
                    Glide.with(requireContext())
                        .load(uri)
                        .into(imageOverlays[targetIndex])

                    // Show the overlay
                    imageOverlays[targetIndex].visibility = View.VISIBLE

                    setImageIndex = 0

                    // Check if we have all images needed
                    val totalImages = getTotalImageCount()
                    if (totalImages >= maxCaptures) {
                        // Hide all previews and show save button
                        previewViews.forEach { it.visibility = View.GONE }
                        binding.saveButton.visibility = View.VISIBLE
                        binding.saveButton.setOnClickListener {
                            createAndSaveCollage()
                        }
                    } else {
                        // Continue with camera for remaining slots
                        switchToNextPreview()
                    }

                    updateUI()
                } else {
                    Log.e("ImagePicker", "Failed to get image URI")
                }
            }
        }
}