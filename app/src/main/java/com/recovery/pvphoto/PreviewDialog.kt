package com.recovery.pvphoto

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import com.bumptech.glide.Glide
import java.io.File

/**
 * Full-screen preview: photo shows instantly, video plays in place,
 * documents offer "open with viewer". The Recover button below sends the
 * item back to the fragment which performs the actual restore.
 */
class PreviewDialog : DialogFragment() {

    companion object {
        fun newInstance(item: RecoverItem): PreviewDialog =
            PreviewDialog().apply { arguments = item.toBundle() }
    }

    var onRecover: ((RecoverItem) -> Unit)? = null

    private val item: RecoverItem by lazy { recoverItemFromBundle(requireArguments()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_preview, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val img = view.findViewById<ImageView>(R.id.imgPreview)
        val video = view.findViewById<VideoView>(R.id.videoPreview)
        val docBlock = view.findViewById<View>(R.id.docBlock)
        val videoError = view.findViewById<TextView>(R.id.txtVideoError)

        view.findViewById<TextView>(R.id.txtName).text = item.name
        view.findViewById<TextView>(R.id.txtInfo).text =
            "${formatDate(item.dateModified)} · ${formatSize(item.size)}"
        view.findViewById<TextView>(R.id.txtSource).text =
            if (item.source == Scanner.SOURCE_TRASH) getString(R.string.source_trash)
            else getString(R.string.source_disk)

        when (item.kind) {
            Kind.PHOTO -> {
                img.visibility = View.VISIBLE
                val model: Any? = item.uri ?: item.file
                Glide.with(this).load(model).fitCenter().into(img)
            }
            Kind.VIDEO -> {
                video.visibility = View.VISIBLE
                val uri: Uri = item.uri ?: Uri.fromFile(item.file ?: File("/dev/null"))
                video.setVideoURI(uri)
                video.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    video.start()
                }
                video.setOnErrorListener { _, _, _ ->
                    videoError.visibility = View.VISIBLE
                    true
                }
            }
            Kind.DOC -> {
                docBlock.visibility = View.VISIBLE
                view.findViewById<View>(R.id.btnOpen).visibility = View.VISIBLE
            }
        }

        view.findViewById<View>(R.id.btnClose).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btnOpen).setOnClickListener { openWithViewer() }
        view.findViewById<View>(R.id.btnRecover).setOnClickListener {
            onRecover?.invoke(item)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun openWithViewer() {
        val ctx = context ?: return
        Toast.makeText(ctx, ctx.getString(R.string.toast_recovering), Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val dir = File(ctx.cacheDir, "preview")
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, item.name)
                val ins: java.io.InputStream? = if (item.uri != null) {
                    ctx.contentResolver.openInputStream(item.uri)
                } else {
                    item.file?.inputStream()
                }
                ins?.use { input -> target.outputStream().use { input.copyTo(it) } }
                val shared = FileProvider.getUriForFile(
                    ctx, ctx.packageName + ".fileprovider", target
                )
                activity?.runOnUiThread {
                    try {
                        val i = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(shared, item.mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(i)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(ctx, R.string.toast_no_app, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (t: Throwable) {
                activity?.runOnUiThread {
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.toast_recovered_fail, t.message ?: "error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }
}
