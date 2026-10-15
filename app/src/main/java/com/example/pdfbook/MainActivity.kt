package com.example.pdfbook

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * PDFを本のように見るビューア
 * - 縦画面: 1ページずつ / 横画面: 見開き(表紙は単独)
 * - 右開き(縦書き・漫画)/左開き の切り替え
 * - 最後に開いたPDFと読んでいたページを記憶
 */
class MainActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var dirBtn: Button
    private lateinit var info: TextView

    private val prefs by lazy { getSharedPreferences("pdfbook", MODE_PRIVATE) }
    private val lock = Any()
    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val cache = LruCache<Int, Bitmap>(6)

    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var adapter: SpreadAdapter? = null
    private var rtl = false

    @Volatile
    private var gen = 0

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            openPdf(u)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rtl = prefs.getBoolean("rtl", false)

        val openBtn = Button(this).apply {
            text = "開く"
            setOnClickListener { openDoc.launch(arrayOf("application/pdf")) }
        }
        dirBtn = Button(this).apply {
            setOnClickListener {
                rtl = !rtl
                prefs.edit().putBoolean("rtl", rtl).apply()
                updateDirLabel()
                reload()
            }
        }
        info = TextView(this).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(0, 0, 32, 0)
        }
        updateDirLabel()

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(openBtn)
            addView(dirBtn)
            addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        pager = ViewPager2(this).apply {
            offscreenPageLimit = 1
            setBackgroundColor(Color.DKGRAY)
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) = onSpread(position)
            })
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(bar)
            addView(pager, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })

        prefs.getString("last", null)?.let { s -> pager.post { openPdf(Uri.parse(s)) } }
    }

    private fun updateDirLabel() {
        dirBtn.text = if (rtl) "右開き" else "左開き"
    }

    private fun openPdf(u: Uri) {
        try {
            val newPfd = contentResolver.openFileDescriptor(u, "r") ?: return
            val newRenderer = try {
                PdfRenderer(newPfd)
            } catch (e: Exception) {
                newPfd.close()
                throw e
            }
            synchronized(lock) {
                renderer?.close()
                pfd?.close()
                renderer = newRenderer
                pfd = newPfd
            }
        } catch (e: Exception) {
            Toast.makeText(this, "PDFを開けませんでした", Toast.LENGTH_SHORT).show()
            return
        }
        uri = u
        prefs.edit().putString("last", u.toString()).apply()
        show(prefs.getInt("page:$u", 0))
    }

    /** 指定のPDFページ(0始まり)を含む見開きを表示 */
    private fun show(page: Int) {
        val twoUp = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val n = synchronized(lock) { renderer?.pageCount ?: 0 }
        gen++
        cache.evictAll()
        pager.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        pager.setPageTransformer(BookFlip(rtl, twoUp))
        val a = SpreadAdapter(n, twoUp)
        adapter = a
        pager.adapter = a
        pager.setCurrentItem(a.positionFor(page.coerceIn(0, maxOf(n - 1, 0))), false)
        onSpread(pager.currentItem)
    }

    private fun reload() {
        val a = adapter ?: return
        show(a.pagesFor(pager.currentItem).firstOrNull() ?: 0)
    }

    private fun onSpread(position: Int) {
        val a = adapter ?: return
        val pages = a.pagesFor(position)
        if (pages.isEmpty()) return
        uri?.let { prefs.edit().putInt("page:$it", pages[0]).apply() }
        info.text = "${pages.joinToString("-") { (it + 1).toString() }} / ${a.pageCount}"
    }

    private fun render(page: Int, img: ImageView, slots: Int) {
        cache.get(page)?.let { img.setImageBitmap(it); return }
        val dm = resources.displayMetrics
        val w = (if (pager.width > 0) pager.width else dm.widthPixels) / slots
        val h = if (pager.height > 0) pager.height else dm.heightPixels
        val g = gen
        exec.execute {
            val bmp: Bitmap? = synchronized(lock) {
                val r = renderer
                if (g != gen || r == null || page >= r.pageCount) {
                    null
                } else {
                    r.openPage(page).use { p ->
                        val s = min(w.toFloat() / p.width, h.toFloat() / p.height)
                        val b = Bitmap.createBitmap(
                            (p.width * s).toInt().coerceAtLeast(1),
                            (p.height * s).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        b.eraseColor(Color.WHITE)
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    }
                }
            }
            if (bmp == null) return@execute
            ui.post {
                if (g == gen) {
                    cache.put(page, bmp)
                    if (img.tag == page) img.setImageBitmap(bmp)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown()
        synchronized(lock) {
            renderer?.close()
            pfd?.close()
            renderer = null
            pfd = null
        }
    }

    inner class SpreadAdapter(val pageCount: Int, private val twoUp: Boolean) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val slots = if (twoUp) 2 else 1

        override fun getItemCount() =
            if (pageCount == 0) 0 else if (twoUp) 1 + pageCount / 2 else pageCount

        fun pagesFor(pos: Int): List<Int> = when {
            !twoUp -> listOf(pos)
            pos == 0 -> listOf(0) // 表紙は単独
            else -> listOf(2 * pos - 1, 2 * pos).filter { it < pageCount }
        }

        fun positionFor(page: Int) = if (!twoUp) page else (page + 1) / 2

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int):
                RecyclerView.ViewHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            }
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = holder.itemView as LinearLayout
            row.removeAllViews()
            val pages = pagesFor(position)
            val single = pages.size < slots
            if (single) row.addView(spacer(row), LinearLayout.LayoutParams(0, MATCH_PARENT, 0.5f))
            pages.forEachIndexed { i, p ->
                val img = ImageView(row.context).apply {
                    tag = p
                    // 見開き時は綴じ目側に寄せる
                    scaleType = when {
                        slots == 2 && !single ->
                            if ((i == 0) != rtl) ImageView.ScaleType.FIT_END
                            else ImageView.ScaleType.FIT_START
                        else -> ImageView.ScaleType.FIT_CENTER
                    }
                }
                row.addView(img, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                render(p, img, slots)
            }
            if (single) row.addView(spacer(row), LinearLayout.LayoutParams(0, MATCH_PARENT, 0.5f))
        }

        private fun spacer(parent: View) = View(parent.context)
    }
}

/**
 * 本をめくる動き。
 * めくられるページはその場に留まり、綴じ目を軸に手前へ持ち上がるように回転する。
 * 次(前)のページは下に固定されていて、めくれると現れる。
 * 横画面の見開きでは、見開き全体が中央の綴じ目を軸に回転する。
 */
private class BookFlip(private val rtl: Boolean, private val twoUp: Boolean) :
    ViewPager2.PageTransformer {

    override fun transformPage(page: View, position: Float) {
        if (position <= -1f || position >= 1f) {
            page.alpha = 0f
            return
        }
        val w = page.width.toFloat()
        val dir = if (rtl) -1f else 1f
        page.alpha = 1f
        page.cameraDistance = 12000f * page.resources.displayMetrics.density
        // 位置は論理位置。右開きでは物理方向が逆なので dir で補正し、その場に固定する
        page.translationX = -position * w * dir
        page.translationZ = -position * 10f
        if (position < 0f) {
            page.pivotX = when {
                twoUp -> w / 2f
                rtl -> w
                else -> 0f
            }
            page.pivotY = page.height / 2f
            page.rotationY = 90f * -position * dir
        } else {
            page.rotationY = 0f
        }
    }
}
