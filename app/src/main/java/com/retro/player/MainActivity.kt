package com.retro.player

import android.Manifest
import android.app.Activity
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
import android.widget.*

class Song(val title: String, val uri: Uri) { val key: String = uri.toString() }

class MainActivity : Activity(), VinylView.Listener {
    private val all = ArrayList<Song>()
    private val shown = ArrayList<Song>()
    private var cur: Song? = null
    private var tab = 0
    private val likes = HashSet<String>()
    private var svc: PlayerService? = null
    private lateinit var prefs: SharedPreferences
    private val h = Handler(Looper.getMainLooper())
    private lateinit var vinyl: VinylView
    private lateinit var titleV: TextView
    private lateinit var timeV: TextView
    private lateinit var emptyV: TextView
    private lateinit var seek: SeekBar
    private lateinit var playB: Button
    private lateinit var likeB: Button
    private lateinit var listV: ListView
    private lateinit var tabs: List<Button>
    private val green = Color.parseColor("#33FF33")

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            val s = (b as PlayerService.LocalBinder).service
            svc = s
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
    private fun audioPerm() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                              else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun granted(p: String) = Build.VERSION.SDK_INT < 23 || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("retro", MODE_PRIVATE)
        likes.addAll(prefs.getStringSet("likes", null) ?: emptySet())
        vinyl = findViewById(R.id.vinyl); vinyl.listener = this
        titleV = findViewById(R.id.title); timeV = findViewById(R.id.time); emptyV = findViewById(R.id.empty)
        seek = findViewById(R.id.seek); playB = findViewById(R.id.play); likeB = findViewById(R.id.like)
        listV = findViewById(R.id.list)
        tabs = listOf(R.id.tab0, R.id.tab1, R.id.tab2).map { findViewById<Button>(it) }
        tabs.forEachIndexed { i, t -> t.setOnClickListener { tab = i; refresh() } }

        playB.setOnClickListener {
            val s = svc ?: return@setOnClickListener
            if (s.current == null) onDrop() else s.toggle()
        }
        findViewById<Button>(R.id.next).setOnClickListener { svc?.next() }
        findViewById<Button>(R.id.prev).setOnClickListener { svc?.prev() }
        findViewById<Button>(R.id.shuf).setOnClickListener {
            val on = !(svc?.shuffle ?: false)
            svc?.shuffle = on; (it as Button).text = if (on) "SHUF:ON" else "SHUF:OFF"
        }
        likeB.setOnClickListener { cur?.let { toggleLike(it) } }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) svc?.seekTo(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        listV.adapter = object : ArrayAdapter<Song>(this, 0, shown) {
            override fun getView(pos: Int, v: View?, parent: ViewGroup): View {
                val s = shown[pos]
                val t = (v as? TextView) ?: TextView(context)
                t.text = (if (likes.contains(s.key)) "<3 " else "   ") + s.title + (if (tab == 2) "  x" + count(s) else "")
                t.setTextColor(if (s.key == cur?.key) Color.WHITE else green)
                t.typeface = Typeface.MONOSPACE
                t.setPadding(16, 20, 16, 20); t.isSingleLine = true
                return t
            }
        }
        listV.setOnItemClickListener { _, _, pos, _ -> svc?.play(ArrayList(shown), pos) }
        listV.setOnItemLongClickListener { _, _, pos, _ -> toggleLike(shown[pos]); true }

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

    /** Mirror the service state (also changed from notification / lock screen) into the UI. */
    private fun sync() {
        val s = svc
        cur = s?.current
        titleV.text = cur?.title?.uppercase() ?: if (all.isEmpty()) "NO MUSIC FOUND" else "NO TRACK"
        playB.text = if (s?.playing == true) "PAUSE" else "PLAY"
        vinyl.setNeedle(s?.playing == true)
        refresh()
    }

    private fun refresh() {
        shown.clear()
        when (tab) {
            0 -> shown.addAll(all)
            1 -> shown.addAll(all.filter { likes.contains(it.key) })
            else -> shown.addAll(all.filter { count(it) > 0 }.sortedByDescending { count(it) }.take(50))
        }
        tabs.forEachIndexed { i, t ->
            t.setBackgroundColor(if (i == tab) green else Color.TRANSPARENT)
            t.setTextColor(if (i == tab) Color.BLACK else green)
        }
        emptyV.visibility = if (shown.isEmpty() && tab != 0) View.VISIBLE else View.GONE
        (listV.adapter as BaseAdapter).notifyDataSetChanged()
        likeB.text = if (cur != null && likes.contains(cur!!.key)) "<3 LIKED" else "<3 LIKE"
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
        else if (shown.isNotEmpty()) s.play(ArrayList(shown), 0)
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
