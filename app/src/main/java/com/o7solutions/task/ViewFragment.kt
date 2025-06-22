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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.o7solutions.task.databinding.FragmentViewBinding
import io.appwrite.Client
import io.appwrite.ID
import io.appwrite.exceptions.AppwriteException
import io.appwrite.models.InputFile
import io.appwrite.services.Storage
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class ViewFragment : Fragment() {

    private var param1: String? = null
    private var param2: String? = null
    private lateinit var binding: FragmentViewBinding
    private var imageUri = ""
    lateinit var storage: Storage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val client = Client(requireContext())
            .setEndpoint("https://cloud.appwrite.io/v1")
            .setProject("683ff28300274cce44da")

        storage = Storage(client)

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
            val file = uriToFile(uri)

            if (file != null && file.exists()) {
                Log.d("Upload", "File exists: ${file.absolutePath}")
                viewLifecycleOwner.lifecycleScope.launch {
                    uploadImage(file)
                }
            } else {
                Log.e("Upload", "File is null or doesn't exist")
                binding.progressBar.visibility = View.GONE
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

    private suspend fun uploadImage(file: File) {
        try {
            val response = storage.createFile(
                bucketId = "683ff2db001cc7f21dfb",
                fileId = ID.unique(),
                file = InputFile.fromFile(file)
            )

            val fileUrl =
                "https://cloud.appwrite.io/v1/storage/buckets/683ff2db001cc7f21dfb/files/${response.id}/view?project=683ff28300274cce44da&mode=admin"

            Log.d("View Fragment", fileUrl)
            showImageDialog(requireContext(), encodeAsBitmap(fileUrl, 500, 500))
            Log.d("Appwrite", "File uploaded successfully: ${response.id}")
        } catch (e: AppwriteException) {
            Log.e("Appwrite", "Upload failed: ${e.message}")
        }
    }

    private fun uriToFile(uri: Uri): File? {
        val file = File(requireContext().cacheDir, "temp_image_${System.currentTimeMillis()}.jpg")
        return try {
            requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            if (file.exists()) {
                Log.d("ImagePicker", "File created: ${file.absolutePath}")
                file
            } else {
                Log.e("ImagePicker", "File doesn't exist after copy.")
                null
            }
        } catch (e: Exception) {
            Log.e("ImagePicker", "Error converting URI to file: ${e.message}")
            null
        }
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
