package com.example.apkcopier

import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class AppItem(
    val name: String,
    val packageName: String,
    val versionName: String,
    val baseApkPath: String,
    val splitApkPaths: List<String>,
    val totalSizeBytes: Long,
    val isSystemApp: Boolean,
    val icon: Drawable
) {
    val isSplitApk: Boolean
        get() = splitApkPaths.isNotEmpty()
}

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var cbSystemApps: CheckBox
    private lateinit var tvCount: TextView

    private var allApps: List<AppItem> = emptyList()
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        etSearch = findViewById(R.id.etSearch)
        cbSystemApps = findViewById(R.id.cbSystemApps)
        tvCount = findViewById(R.id.tvCount)

        adapter = AppAdapter(
            onCopyClick = { app -> copyAppToDownloads(app) },
            onShareClick = { app -> shareAppFile(app) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        recyclerView.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        cbSystemApps.setOnCheckedChangeListener { _, _ ->
            filterApps()
        }

        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }

            val list = packages.mapNotNull { appInfo ->
                val basePath = appInfo.publicSourceDir ?: return@mapNotNull null
                val baseFile = File(basePath)
                if (!baseFile.exists()) return@mapNotNull null

                val splitPaths = appInfo.splitPublicSourceDirs
                    ?.mapNotNull { path -> File(path).takeIf { it.exists() }?.absolutePath }
                    ?: emptyList()

                val totalBytes = baseFile.length() + splitPaths.sumOf { File(it).length() }
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val label = pm.getApplicationLabel(appInfo).toString()

                val version = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(appInfo.packageName, PackageManager.PackageInfoFlags.of(0)).versionName ?: "1.0"
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(appInfo.packageName, 0).versionName ?: "1.0"
                    }
                } catch (_: Exception) {
                    "1.0"
                }

                AppItem(
                    name = label,
                    packageName = appInfo.packageName,
                    versionName = version,
                    baseApkPath = basePath,
                    splitApkPaths = splitPaths,
                    totalSizeBytes = totalBytes,
                    isSystemApp = isSystem,
                    icon = pm.getApplicationIcon(appInfo)
                )
            }.sortedBy { it.name.lowercase(Locale.getDefault()) }

            withContext(Dispatchers.Main) {
                allApps = list
                filterApps()
            }
        }
    }

    private fun filterApps() {
        val query = etSearch.text.toString().trim().lowercase(Locale.getDefault())
        val showSystem = cbSystemApps.isChecked

        val filtered = allApps.filter { app ->
            val matchSystem = showSystem || !app.isSystemApp
            val matchQuery = query.isEmpty() ||
                    app.name.lowercase(Locale.getDefault()).contains(query) ||
                    app.packageName.lowercase(Locale.getDefault()).contains(query)
            matchSystem && matchQuery
        }

        tvCount.text = "Ditemukan: ${filtered.size} aplikasi"
        adapter.submitList(filtered)
    }

    private fun buildOutputFileName(app: AppItem): String {
        val safeName = app.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return if (app.isSplitApk) {
            "${safeName}_v${app.versionName}_split.zip"
        } else {
            "${safeName}_v${app.versionName}.apk"
        }
    }

    private fun writeAppPackage(app: AppItem, outputStream: OutputStream) {
        if (!app.isSplitApk) {
            FileInputStream(File(app.baseApkPath)).use { input ->
                input.copyTo(outputStream)
            }
            return
        }

        ZipOutputStream(BufferedOutputStream(outputStream)).use { zos ->
            zos.setLevel(Deflater.NO_COMPRESSION)
            val addedNames = mutableSetOf<String>()

            fun addFileToZip(file: File, entryName: String) {
                if (!file.exists() || !addedNames.add(entryName)) return
                zos.putNextEntry(ZipEntry(entryName))
                FileInputStream(file).use { input ->
                    input.copyTo(zos)
                }
                zos.closeEntry()
            }

            addFileToZip(File(app.baseApkPath), "base.apk")
            app.splitApkPaths.forEach { splitPath ->
                val splitFile = File(splitPath)
                addFileToZip(splitFile, splitFile.name)
            }
        }
    }

    private fun copyAppToDownloads(app: AppItem) {
        Toast.makeText(this, "Menyalin ${app.name}...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileName = buildOutputFileName(app)
                val mimeType = if (app.isSplitApk) {
                    "application/zip"
                } else {
                    "application/vnd.android.package-archive"
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/APK_Backup")
                    }

                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw Exception("Gagal membuat file tujuan")

                    contentResolver.openOutputStream(uri)?.use { output ->
                        writeAppPackage(app, output)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val downloadDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "APK_Backup"
                    )
                    if (!downloadDir.exists()) downloadDir.mkdirs()

                    val destFile = File(downloadDir, fileName)
                    FileOutputStream(destFile).use { output ->
                        writeAppPackage(app, output)
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Tersimpan di Download/APK_Backup/$fileName",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Gagal menyalin: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun shareAppFile(app: AppItem) {
        Toast.makeText(this, "Menyiapkan ${app.name}...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val shareDir = File(cacheDir, "shared_apks")
                if (shareDir.exists()) {
                    shareDir.listFiles()?.forEach { it.delete() }
                } else {
                    shareDir.mkdirs()
                }

                val tempFile = File(shareDir, buildOutputFileName(app))
                FileOutputStream(tempFile).use { output ->
                    writeAppPackage(app, output)
                }

                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${packageName}.fileprovider",
                    tempFile
                )

                val mimeType = if (app.isSplitApk) {
                    "application/zip"
                } else {
                    "application/vnd.android.package-archive"
                }

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                withContext(Dispatchers.Main) {
                    startActivity(Intent.createChooser(shareIntent, "Kirim ${app.name} via"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Gagal membagikan: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}

class AppAdapter(
    private val onCopyClick: (AppItem) -> Unit,
    private val onShareClick: (AppItem) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    private var items: List<AppItem> = emptyList()

    fun submitList(newItems: List<AppItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
        val tvAppName: TextView = view.findViewById(R.id.tvAppName)
        val tvPackageInfo: TextView = view.findViewById(R.id.tvPackageInfo)
        val btnCopy: Button = view.findViewById(R.id.btnCopy)
        val btnShare: Button = view.findViewById(R.id.btnShare)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = items[position]
        val sizeMb = String.format(Locale.US, "%.1f MB", app.totalSizeBytes / (1024.0 * 1024.0))
        val formatLabel = if (app.isSplitApk) {
            "Split (${app.splitApkPaths.size + 1} file)"
        } else {
            "APK"
        }

        holder.ivIcon.setImageDrawable(app.icon)
        holder.tvAppName.text = app.name
        holder.tvPackageInfo.text = "$sizeMb • $formatLabel • ${app.packageName}"
        holder.btnCopy.setOnClickListener { onCopyClick(app) }
        holder.btnShare.setOnClickListener { onShareClick(app) }
    }

    override fun getItemCount(): Int = items.size
}
