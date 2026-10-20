package com.retro.player

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.*

class Song(val title: String, val uri: Uri)

class MainActivity : Activity() {
    private val songs = ArrayList<Song>()
    private var mp: MediaPlayer? = null
    private var idx = -1
    private var shuffle = false
    private val h = Handler(Looper.getMainLooper())
    private lateinit var titleV: TextView
    private lateinit var timeV: TextView
    private lateinit var seek: SeekBar
    private lateinit var playB: Button

    private val tick = object : Runnable {
        override fun run() {
            mp?.let {
                if (!seek.isPressed) { seek.max = it.duration; seek.progress = it.currentPosition }
                timeV.text = fmt(it.currentPosition) + " / " + fmt(it.duration)
            }
            h.postDelayed(this, 500)
        }
    }

    private fun fmt(ms: Int) = String.format("%02d:%02d", ms / 60000, ms / 1000 % 60)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        titleV = findViewById(R.id.title); timeV = findViewById(R.id.time)
        seek = findViewById(R.id.seek); playB = findViewById(R.id.play)
        playB.setOnClickListener { toggle() }
        findViewById<Button>(R.id.next).setOnClickListener { next() }
        findViewById<Button>(R.id.prev).setOnClickListener { play(if (idx > 0) idx - 1 else songs.size - 1) }
        findViewById<Button>(R.id.shuf).setOnClickListener {
            shuffle = !shuffle; (it as Button).text = if (shuffle) "SHUF:ON" else "SHUF:OFF"
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) mp?.seekTo(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                   else Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(perm), 1)
        else load()
        h.post(tick)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) load()
        else Toast.makeText(this, "Storage permission needed", Toast.LENGTH_LONG).show()
    }

    private fun load() {
        songs.clear()
        val c = contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST),
            MediaStore.Audio.Media.IS_MUSIC + "!=0", null, MediaStore.Audio.Media.TITLE)
        c?.use {
            while (it.moveToNext()) songs.add(Song(it.getString(2) + " - " + it.getString(1),
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.getLong(0))))
        }
        val list = findViewById<ListView>(R.id.list)
        list.adapter = object : ArrayAdapter<Song>(this, 0, songs) {
            override fun getView(pos: Int, v: View?, p: ViewGroup): View {
                val t = (v as? TextView) ?: TextView(context)
                t.text = (pos + 1).toString().padStart(2, '0') + " " + songs[pos].title
                t.setTextColor(if (pos == idx) Color.WHITE else Color.parseColor("#33FF33"))
                t.typeface = android.graphics.Typeface.MONOSPACE
                t.setPadding(16, 20, 16, 20); t.isSingleLine = true
                return t
            }
        }
        list.setOnItemClickListener { _, _, pos, _ -> play(pos) }
        if (songs.isEmpty()) titleV.text = "NO MUSIC FOUND"
    }

    private fun play(i: Int) {
        if (songs.isEmpty()) return
        idx = i
        mp?.release()
        mp = MediaPlayer().apply {
            setDataSource(this@MainActivity, songs[i].uri)
            prepare(); start()
            setOnCompletionListener { next() }
        }
        titleV.text = songs[i].title.uppercase()
        playB.text = "PAUSE"
        (findViewById<ListView>(R.id.list).adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun toggle() {
        val m = mp ?: return play(0)
        if (m.isPlaying) { m.pause(); playB.text = "PLAY" } else { m.start(); playB.text = "PAUSE" }
    }

    private fun next() {
        if (songs.isEmpty()) return
        play(if (shuffle) (0 until songs.size).random() else (idx + 1) % songs.size)
    }

    override fun onDestroy() { h.removeCallbacks(tick); mp?.release(); super.onDestroy() }
}
