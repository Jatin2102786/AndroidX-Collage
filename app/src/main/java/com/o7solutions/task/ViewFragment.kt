package com.o7solutions.task

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.o7solutions.task.databinding.FragmentViewBinding
import com.o7solutions.task.others.MyApplication
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ViewFragment : Fragment() {

    private var param1: String? = null
    private var param2: String? = null
    private lateinit var binding: FragmentViewBinding
    private var imageUri = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let {
            imageUri = it.getString("uri", "") ?: ""
            param1 = it.getString(ARG_PARAM1)
            param2 = it.getString(ARG_PARAM2)
        }

        Log.d("ViewFragment", "Received imageUri: $imageUri")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentViewBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Glide.with(requireContext())
            .load(imageUri)
            .into(binding.imageView)

        binding.uploadButton.setOnClickListener {
            binding.progressBar.visibility = View.VISIBLE
            val uri = Uri.parse(imageUri)

            viewLifecycleOwner.lifecycleScope.launch {
                uploadImageToSupabase(uri)
            }
        }

        binding.shareButton.setOnClickListener {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, Uri.parse(imageUri))
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val shareIntent = Intent.createChooser(sendIntent, "Share Image")
            startActivity(shareIntent)
        }
    }

    private suspend fun uploadImageToSupabase(uri: Uri) {
        try {
            Log.d("Upload", "Starting upload for URI: $uri")

            // Convert URI to File
            val file = withContext(Dispatchers.IO) {
                uriToFile(uri)
            }

            if (file == null) {
                Log.e("Upload", "Failed to create file from URI")
                showError("Failed to prepare file for upload")
                binding.progressBar.visibility = View.GONE
                return
            }

            Log.d("Upload", "File created successfully:")
            Log.d("Upload", "- Path: ${file.absolutePath}")
            Log.d("Upload", "- Exists: ${file.exists()}")
            Log.d("Upload", "- Size: ${file.length()} bytes")
            Log.d("Upload", "- Can read: ${file.canRead()}")

            if (!file.exists() || file.length() == 0L) {
                Log.e("Upload", "File is empty or doesn't exist")
                showError("File is empty or corrupted")
                binding.progressBar.visibility = View.GONE
                return
            }

            // Upload to Supabase
            withContext(Dispatchers.IO) {
                try {
                    val filePath = "uploads/${System.currentTimeMillis()}.jpg"

                    Log.d("Upload", "Uploading to Supabase with path: $filePath")

                    // Upload file to Supabase Storage
                    MyApplication.supabase.storage.from("Images")
                        .upload(filePath, file.readBytes()) {
                            upsert = true
                        }

                    // Get public URL
                    val publicUrl = MyApplication.supabase.storage.from("Images").publicUrl(filePath)

                    Log.d("Upload", "Upload successful!")
                    Log.d("Upload", "Public URL: $publicUrl")

                    // Switch back to main thread for UI updates
                    withContext(Dispatchers.Main) {
                        showImageDialog(requireContext(), encodeAsBitmap(publicUrl, 500, 500))
                        showSuccess("File uploaded successfully!")
                    }

                    // Clean up temporary file
                    file.delete()

                } catch (e: Exception) {
                    Log.e("Upload", "Supabase upload error", e)
                    withContext(Dispatchers.Main) {
                        showError("Upload failed: ${e.message}")
                        binding.progressBar.visibility = View.GONE
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("Upload", "General upload error", e)
            showError("Upload failed: ${e.message}")
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun uriToFile(uri: Uri): File? {
        return try {
            Log.d("Upload", "Converting URI to file: $uri")

            val inputStream = requireContext().contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e("Upload", "Cannot open input stream for URI")
                return null
            }

            // Create file with a unique name
            val fileName = "temp_image_${System.currentTimeMillis()}.jpg"
            val file = File(requireContext().cacheDir, fileName)

            Log.d("Upload", "Creating temp file: ${file.absolutePath}")

            inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                    }

                    Log.d("Upload", "Copied $totalBytes bytes to temp file")
                }
            }

            if (file.exists() && file.length() > 0) {
                Log.d("Upload", "File created successfully: ${file.absolutePath}, size: ${file.length()}")
                file
            } else {
                Log.e("Upload", "File creation failed or file is empty")
                null
            }

        } catch (e: Exception) {
            Log.e("Upload", "Error converting URI to file", e)
            null
        }
    }

    private fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun showSuccess(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    @Throws(WriterException::class)
    fun encodeAsBitmap(str: String, width: Int, height: Int): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix: BitMatrix = writer.encode(str, BarcodeFormat.QR_CODE, width, height)

        val w = bitMatrix.width
        val h = bitMatrix.height
        val pixels = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
            }
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    fun showImageDialog(context: Context, bitmap: Bitmap) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_image_view, null)
        val imageView = dialogView.findViewById<ImageView>(R.id.imageView)
        imageView.setImageBitmap(bitmap)

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("OK") { dialogInterface, _ ->
                dialogInterface.dismiss()
            }
            .create()

        binding.progressBar.visibility = View.GONE
        dialog.show()
    }

    companion object {
        private const val ARG_PARAM1 = "param1"
        private const val ARG_PARAM2 = "param2"

        @JvmStatic
        fun newInstance(param1: String, param2: String, uri: String) =
            ViewFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PARAM1, param1)
                    putString(ARG_PARAM2, param2)
                    putString("uri", uri)
                }
            }
    }
}