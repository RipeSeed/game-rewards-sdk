package com.gamerewards.demo

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.gamerewards.GameRewards
import com.gamerewards.GameRewardsConfig

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GameRewards.init(
            this,
            GameRewardsConfig(
                clientId = "demo",
                baseUrl = BuildConfig.BASE_URL,
                cloudProjectNumber = BuildConfig.CLOUD_PROJECT_NUMBER,
                devFakeProof = BuildConfig.DEV_FAKE_PROOF,
            ),
        )
        val userId = EditText(this).apply { hint = "User id"; setText("u-123"); isSingleLine = true }
        status = TextView(this)
        val play = Button(this).apply { text = "Play & Earn" }
        play.setOnClickListener {
            status.text = "Opening…"
            GameRewards.open(this, userId.text.toString().trim()) { status.text = it }
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(userId)
            addView(play)
            addView(status)
        }
        setContentView(FrameLayout(this).apply { fitsSystemWindows = true; addView(column) })
    }

    override fun onResume() {
        super.onResume()
        status.text = "Server: ${BuildConfig.BASE_URL}" + if (BuildConfig.DEV_FAKE_PROOF) " (fake proofs)" else ""
    }
}
