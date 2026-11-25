package com.recovery.pvphoto

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private var shown = Kind.PHOTO

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updatePermissionUi()
        currentFragment()?.maybeScan(force = true)
    }

    private val allFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionUi()
        currentFragment()?.maybeScan(force = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            addAllFragments()
        } else {
            shown = Kind.valueOf(
                savedInstanceState.getString("shown", Kind.PHOTO.name)
            )
        }

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_photos -> show(Kind.PHOTO)
                R.id.nav_videos -> show(Kind.VIDEO)
                R.id.nav_files -> show(Kind.DOC)
            }
            true
        }

        findViewById<MaterialButton>(R.id.btnGrantMedia).setOnClickListener {
            permLauncher.launch(PermissionUtils.mediaPermissions())
        }
        findViewById<MaterialButton>(R.id.btnGrantAll).setOnClickListener {
            openAllFilesSettings()
        }

        updatePermissionUi()
        show(shown)
    }

    // ------------------------------------------------------------ fragments

    private fun tagOf(kind: Kind) = "frag_${kind.name}"

    private fun fragmentFor(kind: Kind): CategoryFragment {
        val fm = supportFragmentManager
        val existing = fm.findFragmentByTag(tagOf(kind)) as? CategoryFragment
        if (existing != null) return existing
        val fresh = CategoryFragment.newInstance(kind)
        fm.beginTransaction().add(R.id.fragmentContainer, fresh, tagOf(kind)).commitNow()
        return fresh
    }

    private fun addAllFragments() {
        // Create all three up front so results survive tab switches.
        fragmentFor(Kind.PHOTO)
        fragmentFor(Kind.VIDEO)
        fragmentFor(Kind.DOC)
    }

    private fun show(kind: Kind) {
        shown = kind
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        for (k in Kind.entries) {
            val f = fm.findFragmentByTag(tagOf(k)) ?: continue
            if (k == kind) tx.show(f) else tx.hide(f)
        }
        tx.commitNow()
        fragmentFor(kind).maybeScan()
    }

    private fun currentFragment(): CategoryFragment? =
        supportFragmentManager.findFragmentByTag(tagOf(shown)) as? CategoryFragment

    // ---------------------------------------------------------- permissions

    private fun openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < 30) {
            permLauncher.launch(PermissionUtils.mediaPermissions())
            return
        }
        try {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:$packageName"))
            allFilesLauncher.launch(i)
        } catch (t: Throwable) {
            try {
                allFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (t2: Throwable) {
                // Very unlikely; fall back to media permission request.
                permLauncher.launch(PermissionUtils.mediaPermissions())
            }
        }
    }

    private fun updatePermissionUi() {
        val card = findViewById<MaterialCardView>(R.id.cardPermission)
        val txtMedia = findViewById<TextView>(R.id.txtMediaState)
        val txtDeep = findViewById<TextView>(R.id.txtDeepState)
        val btnAll = findViewById<MaterialButton>(R.id.btnGrantAll)

        val mediaOk = PermissionUtils.hasMediaPermission(this)
        val deepOk = PermissionUtils.hasDeepScanAccess(this)

        txtMedia.text = getString(R.string.label_media_perm) + " " +
            getString(if (mediaOk) R.string.state_ok else R.string.state_no)

        if (Build.VERSION.SDK_INT >= 30) {
            txtDeep.visibility = View.VISIBLE
            txtDeep.text = getString(R.string.label_deep_perm) + " " +
                getString(if (deepOk) R.string.state_ok else R.string.state_no)
            btnAll.visibility = View.VISIBLE
        } else {
            txtDeep.visibility = View.GONE
            btnAll.visibility = View.GONE
        }

        card.visibility = if (mediaOk && deepOk) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("shown", shown.name)
    }
}
