package com.o7solutions.task

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.o7solutions.task.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var assistiveTouchManager: AssistiveTouchManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        assistiveTouchManager = AssistiveTouchManager(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
//            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
//            insets
//        }

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        binding.fab.setOnClickListener { view ->
            showPopupMenu(view)
        }

        // Check for overlay permission
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//            if (!Settings.canDrawOverlays(this)) {
//                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
//                intent.data = Uri.parse("package:$packageName")
//                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
//            } else {
//                assistiveTouchManager.startAssistiveTouch()
//            }
//        } else {
//            // For devices below Android M, permission is granted at install time
//            assistiveTouchManager.startAssistiveTouch()
//        }
    }

    // This method handles the result of the permission request
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    // Permission granted, start the assistive touch service
                    assistiveTouchManager.startAssistiveTouch()
                } else {
                    // Permission denied, show a toast message
                    Toast.makeText(
                        this,
                        "Overlay permission denied. Unable to create collages.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showPopupMenu(view: View) {
        val popupMenu = PopupMenu(this, view) // 'this' is context, 'view' is anchor
        popupMenu.menuInflater.inflate(R.menu.nav_menu, popupMenu.menu) // Inflate your new menu

        popupMenu.setOnMenuItemClickListener { item ->
            val navController = findNavController(R.id.nav_host_fragment_content_main)
            when (item.itemId) {
                R.id.three -> {
                    navController.navigate(R.id.threeFragment2)
                    navController.popBackStack()
                    true
                }
                R.id.two -> {
                    navController.navigate(R.id.twoFragment)
                    navController.popBackStack()
                    true
                }
                R.id.four -> {
                    navController.navigate(R.id.blankFragment)
                    navController.popBackStack()
                    true
                }
                else -> false
            }
        }
        popupMenu.show() // Display the pop-up menu
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1001
    }
}