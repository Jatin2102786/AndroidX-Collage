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

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        binding.fab.setOnClickListener { view ->
//            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
//                .setAction("Action", null)
//                .setAnchorView(R.id.fab).show()

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
//        }

//        assistiveTouchManager.startAssistiveTouch()

    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.nav_menu,menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        when (item.itemId){
            R.id.two -> navController.navigate(R.id.twoFragment)
            R.id.three -> navController.navigate(R.id.threeFragment2)
            R.id.four -> navController.navigate(R.id.blankFragment)
        }
        return super.onOptionsItemSelected(item)
    }



    private fun showPopupMenu(view: View) {
        val popupMenu = PopupMenu(this, view) // 'this' is context, 'view' is anchor
        popupMenu.menuInflater.inflate(R.menu.nav_menu, popupMenu.menu) // Inflate your new menu

        popupMenu.setOnMenuItemClickListener { item ->

            val navController = findNavController(R.id.nav_host_fragment_content_main)
            when (item.itemId) {
                R.id.three -> {
                    navController.navigate(R.id.threeFragment2)
                    true
                }
                R.id.two -> {
                    navController.navigate(R.id.twoFragment)
//                    Toast.makeText(this, "Option 2 clicked", Toast.LENGTH_SHORT).show()
                    // Add your action for Option 2 here
                    true
                }
                R.id.four -> {
                    navController.navigate(R.id.blankFragment)
//                    Toast.makeText(this, "Option 3 clicked", Toast.LENGTH_SHORT).show()
                    // Add your action for Option 3 here
                    true
                }
                else -> false
            }
        }
        popupMenu.show() // Display the pop-up menu
    }
    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1001
    }
}