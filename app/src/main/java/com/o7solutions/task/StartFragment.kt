package com.o7solutions.task

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.o7solutions.task.databinding.FragmentStartBinding

class StartFragment : Fragment() {

    private var param1: String? = null
    private var param2: String? = null
    private lateinit var binding: FragmentStartBinding

    // Launcher for requesting camera permission
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission is granted, proceed to homeFragment
            navigateToHomeFragment()
        } else {
            // Permission is denied, inform the user
            Toast.makeText(
                requireContext(),
                "Camera permission denied. Cannot access camera features.",
                Toast.LENGTH_LONG
            ).show()
            // Optionally, you might want to show a dialog explaining why permission is needed
            // or disable features that require camera.
        }
    }

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
        binding = FragmentStartBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.startButton.setOnClickListener {
            checkAndRequestCameraPermission()
        }


//        Glide.with(requireContext())
//            .load("https://plus.unsplash.com/premium_photo-1664474619075-644dd191935f?fm=jpg&q=60&w=3000&ixlib=rb-4.1.0&ixid=M3wxMjA3fDB8MHxzZWFyY2h8MXx8aW1hZ2V8ZW58MHx8MHx8fDA%3D")
//            .into(binding.collage)

        binding.savedImages.setOnClickListener {
            findNavController().navigate(R.id.savedListFragment)
        }
    }

    private fun checkAndRequestCameraPermission() {
        when {
            // Check if the permission is already granted
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                // You can use the API that requires the permission.
                navigateToHomeFragment()
            }
            // Check if we should show a rationale for the permission
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                // In an educational UI, explain to the user why your app needs this
                // permission for a specific feature.
                // For example, show a Snackbar or AlertDialog
                Snackbar.make(
                    binding.root, // Use a suitable view for Snackbar
                    "Camera access is needed to take pictures for collages.",
                    Snackbar.LENGTH_INDEFINITE
                ).setAction("Grant") {
                    // Request the permission again after showing rationale
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                }.show()
            }
            else -> {
                // Directly request the permission.
                // The registered ActivityResultCallback (requestPermissionLauncher)
                // handles the result of the request.
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun navigateToHomeFragment() {
        findNavController().navigate(R.id.homeFragment)
    }

    companion object {
        private const val ARG_PARAM1 = "param1"
        private const val ARG_PARAM2 = "param2"

        @JvmStatic
        fun newInstance(param1: String, param2: String) =
            StartFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PARAM1, param1)
                    putString(ARG_PARAM2, param2)
                }
            }
    }
}