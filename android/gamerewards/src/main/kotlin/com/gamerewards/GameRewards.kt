package com.gamerewards

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.window.OnBackInvokedDispatcher
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode as Code
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

data class GameRewardsConfig(
    val clientId: String,
    val baseUrl: String,
    val cloudProjectNumber: Long,
    /** Debug only: send `fake:<requestHash>` instead of a Play Integrity token. A real-mode server rejects it. */
    val devFakeProof: Boolean = false,
)

/** Call [init] once, then [open] from your button. Main thread only; [open]'s onError runs on the main thread. */
object GameRewards {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context
    private var config: GameRewardsConfig? = null
    private var provider: Task<StandardIntegrityTokenProvider>? = null
    private var busy = false

    fun init(context: Context, config: GameRewardsConfig) {
        if (config == this.config) return
        appContext = context.applicationContext
        this.config = config
        provider = null
        if (!config.devFakeProof) prepare(config) // warm up now: the first prepare takes seconds
    }

    fun open(activity: Activity, userId: String, onError: (String) -> Unit = {}) {
        val c = config ?: return onError("GameRewards.init was not called")
        if (busy) return // ponytail: a tap while launching or while the portal is up is dropped, not queued
        busy = true
        val fail = { message: String -> busy = false; onError(message) }
        val launch: (LaunchRequest, String) -> Unit = { r, proof ->
            val body = JSONObject().put("clientId", c.clientId).put("userId", userId).put("platform", "android")
                .put("nonce", r.nonce).put("ts", r.ts).put("proof", proof)
            thread(name = "GameRewards-launch") {
                val result = runCatching { postLaunch(c.baseUrl, body) }
                main.post {
                    result.onSuccess { portalUrl ->
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            activity.startActivity(Intent(activity, GameRewardsActivity::class.java).putExtra(EXTRA_URL, portalUrl))
                            idleWhenBack(activity)
                        } else {
                            busy = false
                        }
                    }.onFailure { fail(it.message ?: it.toString()) }
                }
            }
        }
        if (c.devFakeProof) LaunchRequest(c.clientId, userId).let { launch(it, "fake:${it.hash}") }
        else integrityToken(c, userId, retried = false, launch, fail)
    }

    /**
     * Clears [busy] when [host] is resumed again (the portal closed, or Android held back the start) or destroyed.
     * Not when the portal starts: taps queue on the host while the portal builds its WebView, then run after its onCreate.
     */
    private fun idleWhenBack(host: Activity) {
        val app = host.application
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) = idle(a)
            override fun onActivityDestroyed(a: Activity) = idle(a)
            private fun idle(a: Activity) {
                if (a !== host) return
                busy = false
                app.unregisterActivityLifecycleCallbacks(this)
            }
            override fun onActivityCreated(a: Activity, state: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
        })
    }

    private fun prepare(c: GameRewardsConfig): Task<StandardIntegrityTokenProvider> =
        provider ?: IntegrityManagerFactory.createStandard(appContext)
            .prepareIntegrityToken(PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(c.cloudProjectNumber).build())
            .also { task ->
                provider = task
                task.addOnFailureListener { if (provider === task) provider = null } // don't cache a failure
            }

    private fun integrityToken(
        c: GameRewardsConfig, userId: String, retried: Boolean, ok: (LaunchRequest, String) -> Unit, fail: (String) -> Unit,
    ) {
        val onFailure = OnFailureListener { e ->
            val code = (e as? StandardIntegrityException)?.errorCode
            if (!retried && code == Code.INTEGRITY_TOKEN_PROVIDER_INVALID) {
                // Google's rule: a provider can expire; prepare a new one and try once more.
                provider = null
                integrityToken(c, userId, retried = true, ok, fail)
            } else if (code in NO_PLAY) {
                // ponytail: like iOS without App Attest, launch with no proof and let the partner's onAttestFail decide
                // (play-only session or 403 bad_proof). Google's GET_INTEGRITY remediation dialog could fix -6/-15 first; add it if those show up.
                ok(LaunchRequest(c.clientId, userId), "")
            } else {
                fail("Play Integrity: ${e.message}") // transient (-3/-8/-12/-18/-100) and config (-16) stay visible
            }
        }
        prepare(c).addOnSuccessListener { p ->
            // nonce and ts are made once the provider is ready, so a slow warm-up can't make the launch stale.
            val r = LaunchRequest(c.clientId, userId)
            p.request(StandardIntegrityTokenRequest.builder().setRequestHash(r.hash).build())
                .addOnSuccessListener { ok(r, it.token()) }
                .addOnFailureListener(onFailure)
        }.addOnFailureListener(onFailure)
    }

    /** Blocking POST /v1/launch (call off the main thread). Returns portalUrl or throws with the server's error. */
    private fun postLaunch(baseUrl: String, body: JSONObject): String {
        val conn = URL(baseUrl.trimEnd('/') + "/v1/launch").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code == 200) return json.getString("portalUrl")
            val reason = if (json.isNull("reason")) "" else " (${json.getString("reason")})"
            throw IOException("${json.optString("error", "http_$code")}$reason: ${json.optString("message", text.take(200))}")
        } finally {
            conn.disconnect()
        }
    }
}

// Device can't produce a token at all (no/old/unbindable Play, or Google says treat as failed).
// ponytail: an allowlist, so an unknown future code shows an error instead of quietly downgrading a user who could earn.
private val NO_PLAY = setOf(Code.API_NOT_AVAILABLE, Code.PLAY_STORE_NOT_FOUND, Code.PLAY_SERVICES_NOT_FOUND,
    Code.CANNOT_BIND_TO_SERVICE, Code.PLAY_STORE_VERSION_OUTDATED, Code.PLAY_SERVICES_VERSION_OUTDATED,
    Code.APP_NOT_INSTALLED, Code.APP_UID_MISMATCH)

/** A fresh nonce and ts with their requestHash. Build it right before asking for the proof. */
private class LaunchRequest(clientId: String, userId: String) {
    val nonce: String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }
    val ts = System.currentTimeMillis()
    val hash = requestHash(clientId, userId, nonce, ts)
}

/** SPEC "Identifiers and hashing": lowercase hex SHA-256 of `clientId|userId|nonce|ts` (UTF-8). */
internal fun requestHash(clientId: String, userId: String, nonce: String, ts: Long): String =
    MessageDigest.getInstance("SHA-256").digest("$clientId|$userId|$nonce|$ts".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private const val EXTRA_URL = "com.gamerewards.PORTAL_URL"

/** The portal, full screen. Only the portal's origin loads inside; other http(s) links open in the browser. */
class GameRewardsActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled") // our own portal; no addJavascriptInterface bridge exists
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val portal = Uri.parse(url).origin() ?: return finish()
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        web.webViewClient = object : WebViewClient() {
            /** true = not the portal: http(s) opens in the browser, every other scheme is dropped. */
            fun leave(target: Uri): Boolean {
                if (target.origin() == portal) return false
                if (target.scheme == "https" || target.scheme == "http") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, target)) }
                }
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = leave(request.url)

            @Deprecated("API 23 only calls this overload")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = leave(Uri.parse(url))

            @TargetApi(26)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                finish() // returning false would kill the host app
                return true
            }
        }
        // Pads for the system bars, which apps targeting SDK 35+ draw under (edge-to-edge).
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        // Back walks the portal's history first, so give a one-tap exit too: the host theme's action bar gets an
        // up arrow (onNavigateUp); without one (NoActionBar and AppCompat themes) we add a Close row on top.
        val bar = actionBar
        if (bar != null) {
            bar.setDisplayHomeAsUpEnabled(true)
        } else {
            val close = Button(this, null, android.R.attr.borderlessButtonStyle).apply { text = "Close"; setOnClickListener { finish() } }
            root.addView(close, 0, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.END })
        }
        // Apps that opted into predictive back (default when targeting 36) never get onBackPressed.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
        // ponytail: process death reloads portalUrl (home screen); the play itself lives on the server.
        web.loadUrl(url)
    }

    private fun back() = if (web.canGoBack()) web.goBack() else finish()

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    @Deprecated("Back below API 33, or when the host app has not opted into predictive back")
    override fun onBackPressed() = back()

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}

/** scheme://host:port with the default port filled in; null for anything but http(s). */
private fun Uri.origin(): String? {
    val s = scheme?.lowercase()?.takeIf { it == "https" || it == "http" } ?: return null
    val h = host?.lowercase() ?: return null
    return "$s://$h:${if (port != -1) port else if (s == "https") 443 else 80}"
}
