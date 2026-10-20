package com.retro.player

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*

class Song(val title: String, val uri: Uri) { val key: String = uri.toString() }

class MainActivity : Activity(), VinylView.Listener {
    private val all = ArrayList<Song>()
    private val liked = ArrayList<Song>()
    private val top = ArrayList<Song>()
    private val likes = HashSet<String>()
    private var cur: Song? = null
    private var page = 0
    private var svc: PlayerService? = null
    private lateinit var prefs: SharedPreferences
    private val h = Handler(Looper.getMainLooper())
    private lateinit var vinyl: VinylView
    private lateinit var titleV: TextView
    private lateinit var timeV: TextView
    private lateinit var seek: SeekBar
    private lateinit var playB: ImageButton
    private lateinit var likeB: ImageButton
    private lateinit var shufB: ImageButton
    private lateinit var repB: ImageButton
    private lateinit var scan: View
    private lateinit var empty2: View
    private lateinit var empty3: View
    private lateinit var settingsBox: LinearLayout
    private lateinit var pages: List<View>
    private lateinit var navs: List<View>
    private lateinit var navIcons: List<ImageView>
    private lateinit var navLabels: List<TextView>
    private lateinit var adapters: List<BaseAdapter>
    private val rows = ArrayList<() -> Unit>()
    private val green = Color.parseColor("#33FF33")

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            val s = (b as PlayerService.LocalBinder).service
            svc = s
            s.shuffle = shuffleOn(); s.repeat = repeatMode()
            s.onChange = { runOnUiThread { sync() } }
            sync()
        }
        override fun onServiceDisconnected(n: ComponentName) { svc = null }
    }

    private val tick = object : Runnable {
        override fun run() {
            svc?.let {
                if (it.current != null) {
                    if (!seek.isPressed) { seek.max = it.duration; seek.progress = it.position }
                    timeV.text = fmt(it.position) + " / " + fmt(it.duration)
                }
            }
            h.postDelayed(this, 500)
        }
    }

    private fun fmt(ms: Int) = String.format("%02d:%02d", ms / 60000, ms / 1000 % 60)
    private fun count(s: Song) = prefs.getInt("pc_" + s.key, 0)
    private fun shuffleOn() = prefs.getBoolean("shuffle", false)
    private fun repeatMode() = prefs.getInt("repeat", 0)
    private fun onOff(b: Boolean) = if (b) "[ON]" else "[OFF]"
    private fun audioPerm() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                              else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun granted(p: String) = Build.VERSION.SDK_INT < 23 || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private inner class SongAdapter(val items: List<Song>, val counts: Boolean) : ArrayAdapter<Song>(this@MainActivity, 0, items) {
        override fun getView(pos: Int, v: View?, parent: ViewGroup): View {
            val s = items[pos]
            val t = (v as? TextView) ?: TextView(context)
            t.text = (if (likes.contains(s.key)) "<3 " else "   ") + s.title + (if (counts) "  x" + count(s) else "")
            t.setTextColor(if (s.key == cur?.key) Color.WHITE else green)
            t.typeface = Typeface.MONOSPACE
            t.setPadding(16, 22, 16, 22); t.isSingleLine = true
            return t
        }
    }

    private fun setupList(id: Int, items: List<Song>, counts: Boolean): BaseAdapter {
        val lv = findViewById<ListView>(id)
        val ad = SongAdapter(items, counts)
        lv.adapter = ad
        lv.setOnItemClickListener { _, _, pos, _ -> svc?.play(ArrayList(items), pos); showPage(0) }
        lv.setOnItemLongClickListener { _, _, pos, _ -> toggleLike(items[pos]); true }
        return ad
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("retro", MODE_PRIVATE)
        likes.addAll(prefs.getStringSet("likes", null) ?: emptySet())
        vinyl = findViewById(R.id.vinyl); vinyl.listener = this
        titleV = findViewById(R.id.title); timeV = findViewById(R.id.time); seek = findViewById(R.id.seek)
        playB = findViewById(R.id.b_play); likeB = findViewById(R.id.b_like)
        shufB = findViewById(R.id.b_shuf); repB = findViewById(R.id.b_rep)
        scan = findViewById(R.id.scan); empty2 = findViewById(R.id.empty2); empty3 = findViewById(R.id.empty3)
        settingsBox = findViewById(R.id.settings)
        pages = listOf(R.id.page0, R.id.page1, R.id.page2, R.id.page3, R.id.page4).map { findViewById<View>(it) }
        navs = listOf(R.id.nav0, R.id.nav1, R.id.nav2, R.id.nav3, R.id.nav4).map { findViewById<View>(it) }
        navIcons = listOf(R.id.ni0, R.id.ni1, R.id.ni2, R.id.ni3, R.id.ni4).map { findViewById<ImageView>(it) }
        navLabels = listOf(R.id.nl0, R.id.nl1, R.id.nl2, R.id.nl3, R.id.nl4).map { findViewById<TextView>(it) }
        navs.forEachIndexed { i, v -> v.setOnClickListener { showPage(i) } }

        adapters = listOf(setupList(R.id.list_all, all, false), setupList(R.id.list_liked, liked, false),
            setupList(R.id.list_top, top, true))

        playB.setOnClickListener {
            val s = svc ?: return@setOnClickListener
            if (s.current == null) onDrop() else s.toggle()
        }
        findViewById<View>(R.id.b_next).setOnClickListener { svc?.next() }
        findViewById<View>(R.id.b_prev).setOnClickListener { svc?.prev() }
        shufB.setOnClickListener { setShuffle(!shuffleOn()) }
        repB.setOnClickListener { cycleRepeat() }
        likeB.setOnClickListener { cur?.let { toggleLike(it) } }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) svc?.seekTo(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        buildSettings()
        applyLook()
        showPage(0)

        val need = ArrayList<String>()
        if (!granted(audioPerm())) need.add(audioPerm())
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        if (granted(audioPerm())) load()

        val i = Intent(this, PlayerService::class.java)
        startService(i)
        bindService(i, conn, BIND_AUTO_CREATE)
        h.post(tick)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (granted(audioPerm())) load()
        else Toast.makeText(this, "Storage permission needed", Toast.LENGTH_LONG).show()
    }

    override fun onResume() { super.onResume(); sync() }

    override fun onBackPressed() { if (page != 0) showPage(0) else moveTaskToBack(true) }

    // ---------- settings page ----------
    private fun addRow(label: () -> String, tap: (() -> Unit)?) {
        val t = TextView(this)
        t.typeface = Typeface.MONOSPACE; t.textSize = 15f; t.setTextColor(green); t.setPadding(24, 30, 24, 30)
        val upd = { t.text = label() }
        upd(); rows.add(upd)
        if (tap != null) t.setOnClickListener { tap(); rows.forEach { it() } }
        settingsBox.addView(t, LinearLayout.LayoutParams(-1, -2))
        val d = View(this); d.setBackgroundColor(Color.parseColor("#1A3A1A"))
        settingsBox.addView(d, LinearLayout.LayoutParams(-1, 2))
    }

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("YES") { _, _ -> ok() }.setNegativeButton("NO", null).show()
    }

    private fun buildSettings() {
        addRow({ "SHUFFLE ......... " + onOff(shuffleOn()) }) { setShuffle(!shuffleOn()) }
        addRow({ "REPEAT .......... " + arrayOf("OFF", "ALL", "ONE")[repeatMode()] }) { cycleRepeat() }
        addRow({ "SCANLINES ....... " + onOff(prefs.getBoolean("scan", true)) }) {
            prefs.edit().putBoolean("scan", !prefs.getBoolean("scan", true)).apply(); applyLook()
        }
        addRow({ "VINYL SPEED ..... " + prefs.getInt("rpm", 33) + " RPM" }) {
            prefs.edit().putInt("rpm", if (prefs.getInt("rpm", 33) == 33) 45 else 33).apply(); applyLook()
        }
        addRow({ "KEEP SCREEN ON .. " + onOff(prefs.getBoolean("awake", false)) }) {
            prefs.edit().putBoolean("awake", !prefs.getBoolean("awake", false)).apply(); applyLook()
        }
        addRow({ "> RESET PLAY COUNTS" }) {
            confirm("Reset all play counts?") {
                val e = prefs.edit(); all.forEach { e.remove("pc_" + it.key) }; e.apply(); refresh()
            }
        }
        addRow({ "> CLEAR ALL LIKES" }) {
            confirm("Remove all likes?") { likes.clear(); prefs.edit().remove("likes").apply(); refresh() }
        }
        addRow({ "RETRO PLAYER v1.0  (" + all.size + " TRACKS)" }, null)
    }

    private fun applyLook() {
        scan.visibility = if (prefs.getBoolean("scan", true)) View.VISIBLE else View.GONE
        vinyl.speed = if (prefs.getInt("rpm", 33) == 45) 270f else 200f
        if (prefs.getBoolean("awake", false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setShuffle(on: Boolean) {
        prefs.edit().putBoolean("shuffle", on).apply(); svc?.shuffle = on; sync()
    }

    private fun cycleRepeat() {
        val r = (repeatMode() + 1) % 3
        prefs.edit().putInt("repeat", r).apply(); svc?.repeat = r
        Toast.makeText(this, "REPEAT: " + arrayOf("OFF", "ALL", "ONE")[r], Toast.LENGTH_SHORT).show()
        sync()
    }

    // ---------- pages / data ----------
    private fun showPage(i: Int) {
        page = i
        pages.forEachIndexed { k, p -> p.visibility = if (k == i) View.VISIBLE else View.GONE }
        for (k in 0..4) {
            val on = k == i
            navIcons[k].alpha = if (on) 1f else 0.4f
            navLabels[k].setTextColor(if (on) Color.WHITE else green)
            navs[k].setBackgroundColor(if (on) Color.parseColor("#102010") else Color.TRANSPARENT)
        }
    }

    private fun load() {
        all.clear()
        try {
            val c = contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST),
                MediaStore.Audio.Media.IS_MUSIC + "!=0", null, MediaStore.Audio.Media.TITLE)
            c?.use {
                while (it.moveToNext()) all.add(Song(it.getString(2) + " - " + it.getString(1),
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.getLong(0))))
            }
        } catch (e: SecurityException) { }
        sync()
    }

    /** Mirror service state (also changed from notification / lock screen) into the UI. */
    private fun sync() {
        val s = svc
        cur = s?.current
        titleV.text = cur?.title?.uppercase() ?: if (all.isEmpty()) "NO MUSIC FOUND" else "NO TRACK"
        val pl = s?.playing == true
        playB.setImageResource(if (pl) R.drawable.ic_pause else R.drawable.ic_play)
        vinyl.setNeedle(pl)
        shufB.alpha = if (shuffleOn()) 1f else 0.35f
        repB.alpha = if (repeatMode() == 0) 0.35f else 1f
        rows.forEach { it() }
        refresh()
    }

    private fun refresh() {
        liked.clear(); liked.addAll(all.filter { likes.contains(it.key) })
        top.clear(); top.addAll(all.filter { count(it) > 0 }.sortedByDescending { count(it) }.take(50))
        adapters.forEach { it.notifyDataSetChanged() }
        empty2.visibility = if (liked.isEmpty()) View.VISIBLE else View.GONE
        empty3.visibility = if (top.isEmpty()) View.VISIBLE else View.GONE
        likeB.alpha = if (cur != null && likes.contains(cur!!.key)) 1f else 0.35f
    }

    private fun toggleLike(s: Song) {
        if (!likes.remove(s.key)) likes.add(s.key)
        prefs.edit().putStringSet("likes", HashSet(likes)).apply()
        refresh()
    }

    // needle dropped on the record
    override fun onDrop() {
        val s = svc
        if (s == null) { vinyl.setNeedle(false); return }
        if (s.current != null) s.resume()
        else if (all.isNotEmpty()) s.play(ArrayList(all), 0)
        else { Toast.makeText(this, "No tracks to play", Toast.LENGTH_SHORT).show(); vinyl.setNeedle(false) }
    }

    // needle lifted off the record
    override fun onLift() { svc?.pause() }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        svc?.onChange = null
        val playing = svc?.playing == true
        unbindService(conn)
        if (!playing) stopService(Intent(this, PlayerService::class.java))
        super.onDestroy()
    }
}
