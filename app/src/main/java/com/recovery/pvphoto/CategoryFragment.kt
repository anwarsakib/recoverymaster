package com.recovery.pvphoto

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One fragment powers all three tabs: PHOTO and VIDEO render a 3-column grid,
 * DOC renders a list. Contains the scan flow, the age filters and recovery.
 */
class CategoryFragment : Fragment(R.layout.fragment_media) {

    companion object {
        private const val ARG_KIND = "kind"
        fun newInstance(kind: Kind): CategoryFragment =
            CategoryFragment().apply {
                arguments = Bundle().apply { putString(ARG_KIND, kind.name) }
            }
    }

    private val kind: Kind by lazy {
        Kind.valueOf(requireArguments().getString(ARG_KIND) ?: "PHOTO")
    }

    private val all = ArrayList<RecoverItem>()
    private val adapter: MediaAdapter by lazy {
        MediaAdapter(kind) { showPreview(it) }
    }

    private lateinit var recycler: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: View
    private lateinit var emptyView: View
    private lateinit var txtNotice: TextView
    private var scanning = false
    private var shownOnce = false
    private var scanRequested = false

    private val trashLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            toast(getString(R.string.toast_restore_ok))
            startScan()
        } else {
            toast(getString(R.string.toast_restore_cancel))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.recycler)
        swipe = view.findViewById(R.id.swipe)
        progress = view.findViewById(R.id.progress)
        emptyView = view.findViewById(R.id.emptyView)
        txtNotice = view.findViewById(R.id.txtNotice)

        recycler.adapter = adapter
        if (kind == Kind.DOC) {
            recycler.layoutManager = LinearLayoutManager(requireContext())
        } else {
            recycler.layoutManager = GridLayoutManager(requireContext(), 3)
        }

        swipe.setOnRefreshListener { startScan() }
        view.findViewById<MaterialButton>(R.id.btnScan).setOnClickListener { startScan() }

        view.findViewById<ChipGroup>(R.id.chipGroup).setOnCheckedStateChangeListener { _, _ ->
            applyFilter()
        }

        // MainActivity may request a scan before the view existed (e.g. onCreate).
        if (scanRequested) {
            scanRequested = false
            startScan()
        }
    }

    /** Called by MainActivity when this tab becomes visible. */
    fun maybeScan(force: Boolean = false) {
        shownOnce = true
        val wantScan = force || all.isEmpty()
        if (wantScan) scanRequested = true
        if (view == null || !isAdded) return // deferred to onViewCreated
        if (wantScan) {
            scanRequested = false
            startScan()
        }
    }

    override fun onResume() {
        super.onResume()
        if (shownOnce && !scanning && view != null && all.isEmpty()) {
            scanRequested = false
            startScan()
        }
    }

    private fun startScan() {
        if (scanning || !isAdded) return
        if (view == null) { // view not ready yet — remember and scan in onViewCreated
            scanRequested = true
            return
        }
        scanning = true
        progress.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        swipe.isRefreshing = true

        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val res = Scanner.scan(appCtx, kind)
            withContext(Dispatchers.Main) {
                scanning = false
                progress.visibility = View.GONE
                swipe.isRefreshing = false
                all.clear()
                all.addAll(res)
                txtNotice.visibility =
                    if (res.size >= Scanner.MAX_ITEMS) View.VISIBLE else View.GONE
                applyFilter()
            }
        }
    }

    private fun selectedAgeMillis(): Long {
        val group = requireView().findViewById<ChipGroup>(R.id.chipGroup)
        return when (group.checkedChipId) {
            R.id.chip_1y -> 365L * 24 * 60 * 60 * 1000
            R.id.chip_3y -> 3L * 365 * 24 * 60 * 60 * 1000
            R.id.chip_5y -> 5L * 365 * 24 * 60 * 60 * 1000
            else -> 0L
        }
    }

    private fun applyFilter() {
        if (!isAdded) return
        val ageMs = selectedAgeMillis()
        val filtered = if (ageMs == 0L) all else {
            val now = System.currentTimeMillis()
            all.filter { now - it.dateModified * 1000 >= ageMs }
        }
        adapter.submit(filtered)

        val isEmpty = filtered.isEmpty()
        emptyView.visibility = if (isEmpty && !scanning) View.VISIBLE else View.GONE
        if (isEmpty) {
            val deep = PermissionUtils.hasDeepScanAccess(requireContext())
            requireView().findViewById<TextView>(R.id.txtEmptyHint).visibility =
                if (deep) View.GONE else View.VISIBLE
        }
    }

    private fun showPreview(item: RecoverItem) {
        val dlg = PreviewDialog.newInstance(item)
        dlg.onRecover = { target ->
            handleRecover(target)
            dlg.dismiss()
        }
        dlg.show(parentFragmentManager, "preview")
    }

    private fun handleRecover(item: RecoverItem) {
        if (!isAdded) return
        if (item.source == Scanner.SOURCE_TRASH && item.uri != null && Build.VERSION.SDK_INT >= 30) {
            val pi = RecoveryEngine.buildRestoreIntent(requireContext(), item)
            if (pi != null) {
                trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } else {
                toast(getString(R.string.toast_trash_fail))
            }
        } else {
            toast(getString(R.string.toast_recovering))
            val appCtx = requireContext().applicationContext
            RecoveryEngine.recoverFile(appCtx, item) { ok, msg ->
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (ok) {
                        toast(getString(R.string.toast_recovered_ok, msg))
                        all.removeAll { it === item || (it.path != null && it.path == item.path) }
                        applyFilter()
                    } else {
                        toast(getString(R.string.toast_recovered_fail, msg))
                    }
                }
            }
        }
    }

    private fun toast(msg: String) {
        context?.let { Toast.makeText(it, msg, Toast.LENGTH_LONG).show() }
    }
}
