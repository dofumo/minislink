package com.ptit.slinkmini

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var session: Session
    private lateinit var status: TextView
    private lateinit var loginBox: LinearLayout
    private lateinit var homeBox: LinearLayout
    private lateinit var settingsBox: LinearLayout
    private lateinit var manualQr: EditText
    private lateinit var partIn: EditText
    private lateinit var tokenIn: EditText
    private lateinit var uploadResult: TextView

    private val appVersionName = "2.3.6"
    private val appVersionCode = "80064"

    private enum class Flow { NONE, CAMERA_SCAN, IMAGE_QR, PHOTO_CHECKIN }
    private var flow = Flow.NONE
    private var pendingPhotoMa: String = ""
    private var lastMa: String = ""
    private var lastQr: String = ""

    @Volatile private var bruteCancel = false
    private var bruteThread: Thread? = null

    private companion object {
        const val TAG = "SLINK"
        const val TAG_PROBE = "SLINK_PROBE"
        const val TAG_EVENTS = "SLINK_EVENTS"
    }

    // ========== Launchers ==========
    private val scanLauncher = registerForActivityResult(ScanContract()) { r ->
        val content = r.contents
        val mode = flow
        flow = Flow.NONE
        Log.d(TAG, "scan result: mode=$mode content=${content?.take(120)}")
        if (content == null) {
            status.text = "Đã huỷ quét QR"
            return@registerForActivityResult
        }
        when (mode) {
            Flow.CAMERA_SCAN -> {
                status.text = "✅ Đã quét QR\nĐang điểm danh…"
                doCheckIn(content)
            }
            Flow.PHOTO_CHECKIN -> processPhotoCheckinQr(content)
            else -> doCheckIn(content)
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val mode = flow
        flow = Flow.NONE
        val ma = pendingPhotoMa
        pendingPhotoMa = ""
        Log.d(TAG, "pickImage: mode=$mode uri=$uri ma=$ma")
        if (uri == null) { status.text = "Đã huỷ chọn ảnh"; return@registerForActivityResult }
        when (mode) {
            Flow.IMAGE_QR -> decodeQrThenCheckin(uri)
            Flow.PHOTO_CHECKIN -> {
                if (ma.isBlank()) { status.text = "Không có mã"; return@registerForActivityResult }
                doUploadAndCheckin(uri, ma)
            }
            else -> doUploadOnly(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        setContentView(buildUi())
        handleIntent(intent)
        refreshUi()
        if (session.isLoggedIn()) {
            preparePartition()
            if (session.partitionCode.isBlank()) {
                session.partitionCode = "PTIT"
                Log.d(TAG, "partition fallback → PTIT")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme == "ptit.auth" && data.host == "SignIn") {
            val code = data.getQueryParameter("code")
            val state = data.getQueryParameter("state")
            val error = data.getQueryParameter("error")
            val errorDesc = data.getQueryParameter("error_description")
            if (error != null) { status.text = "SSO lỗi: $error\n${errorDesc.orEmpty()}"; return }
            if (code.isNullOrBlank()) { status.text = "Không nhận được code"; return }
            if (state != session.pendingState) { status.text = "State mismatch"; return }
            exchangeCodeForToken(code)
        }
    }

    // ========== UI ==========
    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            text = "PTIT S-Link Mini (v$appVersionName/$appVersionCode)"
            textSize = 20f; setPadding(0, 0, 0, 24)
        })

        loginBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        loginBox.addView(Button(this).apply {
            text = "Đăng nhập qua SSO"; setOnClickListener { startSso() }
        })
        loginBox.addView(Button(this).apply {
            text = "Đã có token — Settings"; setOnClickListener { showSettings() }
        })
        root.addView(loginBox)

        homeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
        }

        // ===== Khối mới: Xem sự kiện =====
        homeBox.addView(sectionHeader("📅 Sự kiện"))
        homeBox.addView(Button(this).apply {
            text = "📅 Xem sự kiện đang diễn ra"
            setOnClickListener { showOngoingEvents() }
        })
        homeBox.addView(Button(this).apply {
            text = "📋 Xem sự kiện đã tham gia"
            setOnClickListener { showJoinedEvents() }
        })

        // ===== Điểm danh có ảnh =====
        homeBox.addView(sectionHeader("Điểm danh kèm ảnh xác thực"))
        homeBox.addView(Button(this).apply {
            text = "📷 Điểm danh kèm ảnh (1 nút)"
            setOnClickListener { startPhotoCheckinOneTap() }
        })

        // ===== Điểm danh không ảnh =====
        homeBox.addView(sectionHeader("Điểm danh không ảnh (nhanh)"))
        homeBox.addView(Button(this).apply {
            text = "📷 Quét QR bằng camera"
            setOnClickListener { startCameraScan() }
        })
        homeBox.addView(Button(this).apply {
            text = "⌨️ Nhập mã điểm danh"
            setOnClickListener { showManualDialog() }
        })
        homeBox.addView(Button(this).apply {
            text = "🖼️ Trích mã từ ảnh QR"
            setOnClickListener { startImageQr() }
        })
        manualQr = EditText(this).apply { hint = "Dán chuỗi QR (dự phòng)" }
        homeBox.addView(manualQr)
        homeBox.addView(Button(this).apply {
            text = "Điểm danh từ chuỗi QR đã dán"
            setOnClickListener {
                val s = manualQr.text.toString().trim()
                if (s.isNotEmpty()) doCheckIn(s)
            }
        })

        // ===== Debug =====
        homeBox.addView(sectionHeader("Debug & Test"))
        homeBox.addView(Button(this).apply {
            text = "🔬 Test check-in variants"
            setOnClickListener { runVariantsWithInput() }
        })
        homeBox.addView(Button(this).apply {
            text = "🔍 Probe API endpoints"
            setOnClickListener { probeEndpoints() }
        })
        homeBox.addView(Button(this).apply {
            text = "🔢 Bruteforce mã"
            setOnClickListener { showBruteforceDialog() }
        })

        // ===== Tiện ích =====
        homeBox.addView(sectionHeader("Tiện ích"))
        homeBox.addView(Button(this).apply {
            text = "📤 Upload ảnh (không điểm danh)"
            setOnClickListener { flow = Flow.NONE; pickImageLauncher.launch("image/*") }
        })
        uploadResult = TextView(this).apply {
            textSize = 12f; setTextIsSelectable(true); setPadding(0, 8, 0, 8)
        }
        homeBox.addView(uploadResult)
        homeBox.addView(Button(this).apply {
            text = "Copy kết quả upload"
            setOnClickListener {
                val t = uploadResult.text.toString()
                if (t.isNotBlank()) {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("result", t))
                    Toast.makeText(this@MainActivity, "Đã copy", Toast.LENGTH_SHORT).show()
                }
            }
        })

        // ===== Tài khoản =====
        homeBox.addView(sectionHeader("Tài khoản"))
        homeBox.addView(Button(this).apply {
            text = "Settings"; setOnClickListener { showSettings() }
        })
        homeBox.addView(Button(this).apply {
            text = "Đăng xuất"
            setOnClickListener {
                session.clear(); refreshUi(); status.text = "Đã đăng xuất"
            }
        })
        root.addView(homeBox)

        // ---- Settings ----
        settingsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
        }
        tokenIn = EditText(this).apply {
            hint = "Paste access_token"; inputType = InputType.TYPE_CLASS_TEXT
        }
        partIn = EditText(this).apply { hint = "x-data-partition-code (mặc định PTIT)" }
        settingsBox.addView(tokenIn); settingsBox.addView(partIn)
        settingsBox.addView(Button(this).apply {
            text = "Lưu"
            setOnClickListener {
                val t = tokenIn.text.toString().trim()
                if (t.isNotBlank()) session.accessToken = t
                session.partitionCode = partIn.text.toString().trim().ifBlank { "PTIT" }
                status.text = "Đã lưu"; refreshUi()
                if (session.isLoggedIn()) preparePartition()
            }
        })
        settingsBox.addView(Button(this).apply {
            text = "Quay lại"; setOnClickListener { refreshUi() }
        })
        root.addView(settingsBox)

        status = TextView(this).apply {
            textSize = 12f; setPadding(0, 20, 0, 0); setTextIsSelectable(true)
        }
        root.addView(status)
        return scroll
    }

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 16f; setPadding(0, 24, 0, 8)
    }

    private fun refreshUi() {
        val logged = session.isLoggedIn()
        loginBox.visibility = if (logged) View.GONE else View.VISIBLE
        homeBox.visibility = if (logged) View.VISIBLE else View.GONE
        settingsBox.visibility = View.GONE
        partIn.setText(session.partitionCode)
    }

    private fun showSettings() {
        loginBox.visibility = View.GONE; homeBox.visibility = View.GONE
        settingsBox.visibility = View.VISIBLE
        tokenIn.setText(""); partIn.setText(session.partitionCode)
    }

    // ========== SSO ==========
    private fun startSso() {
        val verifier = Pkce.newCodeVerifier()
        val challenge = Pkce.challenge(verifier)
        val state = Pkce.newState()
        session.pendingVerifier = verifier; session.pendingState = state
        val url = Uri.parse(Config.AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", Config.CLIENT_ID)
            .appendQueryParameter("redirect_uri", Config.REDIRECT_URI)
            .appendQueryParameter("scope", Config.SCOPE)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build().toString()
        status.text = "Đang mở SSO…"
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (e: Exception) { status.text = "Không mở được browser: ${e.message}" }
    }

    private fun exchangeCodeForToken(code: String) {
        val verifier = session.pendingVerifier
        if (verifier.isBlank()) { status.text = "Không có code_verifier"; return }
        val form = mapOf(
            "grant_type" to "authorization_code", "client_id" to Config.CLIENT_ID,
            "code" to code, "redirect_uri" to Config.REDIRECT_URI,
            "code_verifier" to verifier
        )
        Api.postForm(Config.TOKEN_ENDPOINT, form) { ok, body ->
            runOnUiThread {
                if (!ok) { status.text = "Đổi code lỗi: $body"; return@runOnUiThread }
                try {
                    val j = JSONObject(body)
                    session.accessToken = j.optString("access_token")
                    session.refreshToken = j.optString("refresh_token")
                    session.idToken = j.optString("id_token")
                    session.expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 300) * 1000
                    session.pendingVerifier = ""; session.pendingState = ""
                    status.text = "Đăng nhập OK"; refreshUi(); preparePartition()
                } catch (e: Exception) { status.text = "Parse token lỗi: ${e.message}" }
            }
        }
    }

    private fun refreshAccessToken(onDone: (Boolean) -> Unit) {
        val rt = session.refreshToken
        if (rt.isBlank()) { onDone(false); return }
        val form = mapOf(
            "grant_type" to "refresh_token", "client_id" to Config.CLIENT_ID,
            "refresh_token" to rt
        )
        Api.postForm(Config.TOKEN_ENDPOINT, form) { ok, body ->
            runOnUiThread {
                if (!ok) { onDone(false); return@runOnUiThread }
                try {
                    val j = JSONObject(body)
                    session.accessToken = j.optString("access_token", session.accessToken)
                    session.refreshToken = j.optString("refresh_token", session.refreshToken)
                    session.expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 300) * 1000
                    onDone(true)
                } catch (_: Exception) { onDone(false) }
            }
        }
    }

    // ========== Partition ==========
    private fun preparePartition() {
        val fromJwt = partitionFromJwt()
        if (fromJwt.isNotBlank()) { session.partitionCode = fromJwt; return }
        getWithAuth("/core/data-partition/user/many/me") { ok, body ->
            if (ok) try {
                val arr = JSONArray(body)
                if (arr.length() > 0) {
                    val o = arr.getJSONObject(0)
                    val code = o.optString("code").ifBlank {
                        o.optString("dataPartitionCode").ifBlank {
                            o.optString("ma").ifBlank { o.optString("id") }
                        }
                    }
                    if (code.isNotBlank()) session.partitionCode = code
                }
            } catch (_: Exception) {}
            if (session.partitionCode.isBlank()) session.partitionCode = "PTIT"
        }
    }

    private fun partitionFromJwt(): String = try {
        val parts = session.accessToken.split(".")
        if (parts.size < 2) "" else {
            val payload = String(android.util.Base64.decode(
                parts[1],
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            ))
            val o = JSONObject(payload)
            o.optString("partition_code").ifBlank {
                o.optString("partitionCode").ifBlank {
                    o.optString("data_partition_code").ifBlank {
                        val a = o.optJSONArray("partition_codes")
                        if (a != null && a.length() > 0) a.getString(0) else ""
                    }
                }
            }
        }
    } catch (_: Exception) { "" }

    // ========== Headers ==========
    private fun authHeaders(): Map<String, String> {
        val h = mutableMapOf(
            "Authorization" to "Bearer ${session.accessToken}",
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8",
            "X-App-Version" to appVersionName,
            "X-Client-Version" to appVersionName,
            "X-Version" to appVersionName,
            "App-Version" to appVersionName,
            "X-App-VersionCode" to appVersionCode,
            "X-Client-VersionCode" to appVersionCode,
            "X-App-Platform" to "android",
            "X-Platform" to "android",
            "X-Client-Id" to Config.CLIENT_ID,
            "User-Agent" to "PTIT-S-Link/$appVersionName (Android; $appVersionCode)"
        )
        if (session.partitionCode.isNotBlank()) h["x-data-partition-code"] = session.partitionCode
        return h
    }

    private fun getWithAuth(path: String, onResult: (Boolean, String) -> Unit) =
        requestWithAuth("GET", path, null, onResult)

    private fun postWithAuth(path: String, body: String, onResult: (Boolean, String) -> Unit) =
        requestWithAuth("POST", path, body, onResult)

    private fun requestWithAuth(method: String, path: String, body: String?,
                                onResult: (Boolean, String) -> Unit) {
        val url = Config.API_URL.trimEnd('/') + "/" + path.trimStart('/')
        Log.d(TAG, "→ $method $url body=${body?.take(200)}")
        val cb: (Boolean, String) -> Unit = { ok, resp ->
            Log.d(TAG, "← $method $url ok=$ok resp=${resp.take(400)}")
            if (!ok && (resp.contains("401") || resp.contains("TokenInvalid", true))) {
                refreshAccessToken { refreshed ->
                    if (refreshed) requestWithAuth(method, path, body, onResult)
                    else onResult(false, resp)
                }
            } else onResult(ok, resp)
        }
        if (method == "GET") Api.get(url, authHeaders(), cb)
        else Api.postJson(url, body ?: "{}", authHeaders(), cb)
    }

    private fun isVersionBlocked(resp: String): Boolean {
        val r = resp.lowercase()
        return r.contains("update phiên bản") || r.contains("update phien ban")
    }

    // ============================================================
    // ============ SỰ KIỆN ĐANG DIỄN RA (MỚI) ============
    // ============================================================

    /** Probe 14 endpoint để tìm endpoint list sự kiện. */
    private fun showOngoingEvents() {
        val endpoints = listOf(
            "/slink/su-kien/page?page=1&limit=50",
            "/slink/su-kien?page=1&limit=50",
            "/slink/su-kien/all?page=1&limit=50",
            "/slink/su-kien/list?page=1&limit=50",
            "/slink/su-kien/active?page=1&limit=50",
            "/slink/su-kien/current?page=1&limit=50",
            "/slink/su-kien/ongoing?page=1&limit=50",
            "/slink/su-kien/public?page=1&limit=50",
            "/slink/su-kien/public/page?page=1&limit=50",
            "/slink/su-kien/dang-dien-ra?page=1&limit=50",
            "/slink/su-kien/user/page?page=1&limit=50",
            "/slink/sv-su-kien/page?page=1&limit=50",
            "/slink/sv-su-kien/all?page=1&limit=50",
            "/slink/sv-su-kien/list?page=1&limit=50",
            "/slink/sv-su-kien/active?page=1&limit=50",
            "/slink/dot-cham-diem/page?page=1&limit=50",
            "/slink/dot-cham-diem/active?page=1&limit=50"
        )
        val log = StringBuilder("=== TÌM ENDPOINT SỰ KIỆN ĐANG DIỄN RA ===\n")
        status.text = log.toString()
        probeEventEndpoints(endpoints, 0, log)
    }

    private fun probeEventEndpoints(
        endpoints: List<String>, idx: Int, log: StringBuilder
    ) {
        if (idx >= endpoints.size) {
            runOnUiThread {
                log.append("\n❌ Không endpoint nào trả danh sách sự kiện.\n")
                log.append("→ Cần sniff mitmproxy app gốc khi mở tab 'Sự kiện'.\n")
                log.append("→ Hoặc dùng 'Xem sự kiện đã tham gia' (endpoint /sv-su-kien/me/page đã verified).")
                status.text = log.toString()
            }
            return
        }
        val path = endpoints[idx]
        val url = Config.API_URL.trimEnd('/') + path
        Log.d(TAG_EVENTS, "probe: GET $url")
        runOnUiThread {
            log.append("⏳ $path\n")
            status.text = log.toString()
        }
        Api.get(url, authHeaders()) { ok, resp ->
            Log.d(TAG_EVENTS, "probe: $path ok=$ok resp=${resp.take(200)}")
            val isSuccess = ok && !resp.contains("\"success\":false")
            val hasEvents = resp.contains("\"result\"") ||
                            resp.contains("\"items\"") ||
                            resp.contains("\"list\"") ||
                            resp.trimStart().startsWith("[")
            runOnUiThread {
                log.append("  ok=$ok  ${resp.take(120)}\n")
                status.text = log.toString()
            }
            if (isSuccess && hasEvents) {
                runOnUiThread {
                    log.append("\n✅ TÌM THẤY: $path\n")
                    status.text = log.toString()
                    parseAndShowEvents(resp, path)
                }
                return@get
            }
            probeEventEndpoints(endpoints, idx + 1, log)
        }
    }

    /** Parse response → hiện dialog danh sách sự kiện. */
    private fun parseAndShowEvents(resp: String, endpoint: String) {
        val events = mutableListOf<JSONObject>()
        try {
            val trimmed = resp.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) events.add(arr.getJSONObject(i))
            } else {
                val j = JSONObject(trimmed)
                val data = j.opt("data")
                when (data) {
                    is JSONObject -> {
                        val result = data.optJSONArray("result")
                            ?: data.optJSONArray("items")
                            ?: data.optJSONArray("list")
                        if (result != null) {
                            for (i in 0 until result.length()) events.add(result.getJSONObject(i))
                        }
                    }
                    is JSONArray -> {
                        for (i in 0 until data.length()) events.add(data.getJSONObject(i))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG_EVENTS, "parse fail", e)
            status.text = "Endpoint $endpoint OK nhưng parse JSON fail:\n${resp.take(2000)}"
            return
        }

        if (events.isEmpty()) {
            status.text = "Endpoint $endpoint OK nhưng không có event.\n${resp.take(1500)}"
            return
        }

        Log.d(TAG_EVENTS, "found ${events.size} events from $endpoint")
        val ongoing = events.filter { isEventOngoing(it) }
        val display = if (ongoing.isNotEmpty()) ongoing else events
        val title = if (ongoing.isNotEmpty())
            "Sự kiện đang diễn ra (${ongoing.size}/${events.size})"
        else "Tất cả sự kiện (${events.size}) — không filter được thời gian"

        showEventListDialog(display, title, endpoint)
    }

    /** Hiện dialog danh sách sự kiện, mỗi event là 1 nút. */
    private fun showEventListDialog(
        events: List<JSONObject>, title: String, endpoint: String
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }

        // Header info
        container.addView(TextView(this).apply {
            text = "Endpoint: $endpoint\n${events.size} sự kiện"
            textSize = 11f
            setPadding(0, 0, 0, 16)
        })

        for ((i, event) in events.withIndex()) {
            val name = extractEventName(event)
            val id = extractEventId(event)
            val time = extractEventTime(event)
            val codes = extractCheckInCodes(event)

            val btn = Button(this).apply {
                text = buildString {
                    append("📌 $name\n")
                    if (time.isNotBlank()) append("⏰ $time\n")
                    if (id.isNotBlank()) append("🆔 $id\n")
                    if (codes.isNotEmpty()) append("🔑 Mã: ${codes.joinToString(", ")}\n")
                }
                textSize = 12f
                setOnClickListener {
                    showEventDetails(event, codes)
                }
            }
            container.addView(btn)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Đóng", null)
            .setNeutralButton("Copy tất cả") { _, _ ->
                val all = events.joinToString("\n\n") { it.toString(2) }
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("events", all))
                Toast.makeText(this, "Đã copy ${events.size} sự kiện", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** Dialog chi tiết 1 event + nút check-in nếu có mã. */
    private fun showEventDetails(event: JSONObject, codes: List<String>) {
        val details = try { event.toString(2) } catch (_: Exception) { event.toString() }
        val ma = codes.firstOrNull()

        val builder = AlertDialog.Builder(this)
            .setTitle("Chi tiết sự kiện")
            .setMessage(details)
            .setPositiveButton("Đóng", null)
            .setNeutralButton("Copy JSON") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("event", details))
                Toast.makeText(this, "Đã copy", Toast.LENGTH_SHORT).show()
            }

        if (!ma.isNullOrBlank()) {
            builder.setNegativeButton("🔑 Điểm danh với mã $ma") { _, _ ->
                doCheckInWithMa(ma)
            }
        }
        builder.show()
    }

    // ========== Extract helpers ==========

    private fun extractEventName(e: JSONObject): String {
        for (f in listOf("ten", "tenSuKien", "tenHoatDong", "name", "title")) {
            val v = e.optString(f)
            if (v.isNotBlank()) return v
        }
        return "(không tên)"
    }

    private fun extractEventId(e: JSONObject): String {
        for (f in listOf("_id", "id", "idSuKien")) {
            val v = e.optString(f)
            if (v.isNotBlank()) return v
        }
        return ""
    }

    private fun extractEventTime(e: JSONObject): String {
        val start = findTimeField(e, listOf(
            "thoiGianBatDau", "ngayBatDau", "batDau", "thoiGianDienRa", "startTime", "startDate"
        ))
        val end = findTimeField(e, listOf(
            "thoiGianKetThuc", "ngayKetThuc", "ketThuc", "endTime", "endDate"
        ))
        return when {
            start.isNotBlank() && end.isNotBlank() -> "$start → $end"
            start.isNotBlank() -> "từ $start"
            end.isNotBlank() -> "đến $end"
            else -> ""
        }
    }

    private fun findTimeField(e: JSONObject, names: List<String>): String {
        for (f in names) {
            val v = e.optString(f)
            if (v.isNotBlank()) return v.take(19).replace("T", " ")
        }
        return ""
    }

    private fun isEventOngoing(e: JSONObject): Boolean {
        val now = System.currentTimeMillis()
        val start = parseTime(e, listOf(
            "thoiGianBatDau", "ngayBatDau", "batDau", "thoiGianDienRa", "startTime", "startDate"
        ))
        val end = parseTime(e, listOf(
            "thoiGianKetThuc", "ngayKetThuc", "ketThuc", "endTime", "endDate"
        ))
        return when {
            start > 0 && end > 0 -> now in start..end
            start > 0 -> now >= start
            else -> false
        }
    }

    private fun parseTime(e: JSONObject, names: List<String>): Long {
        for (f in names) {
            val v = e.optString(f)
            if (v.isNotBlank()) {
                val t = parseIsoTime(v)
                if (t > 0) return t
            }
        }
        return 0L
    }

    private fun parseIsoTime(s: String): Long {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val d = sdf.parse(s) ?: continue
                return d.time
            } catch (_: Exception) {}
        }
        return 0L
    }

    /**
     * Trích mã điểm danh từ event.
     * Thử nested arrays như danhSachDotChamDiem, dotChamDiem, sessions.
     * Cũng thử trực tiếp field maDiemDanh, ma, code trên event.
     */
    private fun extractCheckInCodes(e: JSONObject): List<String> {
        val codes = mutableListOf<String>()
        // Direct fields
        for (f in listOf("maDiemDanh", "ma", "code", "checkInCode")) {
            val v = e.optString(f)
            if (v.matches(Regex("\\d{4,10}"))) codes.add(v)
        }
        // Nested arrays
        for (f in listOf("danhSachDotChamDiem", "dotChamDiem", "dotChamDiems", "sessions", "checkInSessions")) {
            val arr = e.optJSONArray(f) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                for (k in listOf("maDiemDanh", "ma", "code", "checkInCode")) {
                    val v = o.optString(k)
                    if (v.matches(Regex("\\d{4,10}"))) { codes.add(v); break }
                }
            }
        }
        return codes.distinct()
    }

    // ========== Xem sự kiện đã tham gia (endpoint verified) ==========

    private fun showJoinedEvents() {
        status.text = "Đang lấy sự kiện đã tham gia…"
        getWithAuth("/slink/sv-su-kien/me/page?page=1&limit=50") { ok, resp ->
            runOnUiThread {
                if (!ok) { status.text = "Lỗi: $resp"; return@runOnUiThread }
                try {
                    val arr = JSONObject(resp).getJSONObject("data").getJSONArray("result")
                    val events = mutableListOf<JSONObject>()
                    for (i in 0 until arr.length()) events.add(arr.getJSONObject(i))
                    if (events.isEmpty()) {
                        status.text = "Chưa tham gia sự kiện nào"
                        return@runOnUiThread
                    }
                    showJoinedEventsDialog(events)
                } catch (e: Exception) {
                    status.text = "Parse lỗi: ${e.message}\n${resp.take(500)}"
                }
            }
        }
    }

    private fun showJoinedEventsDialog(events: List<JSONObject>) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        for (event in events) {
            val tenSv = event.optString("tenSv", "?")
            val idSuKien = event.optString("idSuKien", "?")
            val thoiGian = event.optString("thoiGianCheckIn", "").take(19).replace("T", " ")
            val idDot = event.optString("idDotChamDiem", "")
            val loaiQr = event.optString("loaiQR", "")

            container.addView(TextView(this).apply {
                text = "📌 $idSuKien\n" +
                       "   SV: $tenSv\n" +
                       "   Check-in: $thoiGian\n" +
                       "   Đợt: $idDot | Loại: $loaiQr"
                textSize = 12f
                setPadding(0, 12, 0, 12)
                setTextIsSelectable(true)
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Đã tham gia (${events.size})")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Đóng", null)
            .setNeutralButton("Copy") { _, _ ->
                val all = events.joinToString("\n\n") { it.toString(2) }
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("joined", all))
            }
            .show()
    }

    // ============================================================
    // ============ CÁC FLOW ĐIỂM DANH (giữ nguyên) ============
    // ============================================================

    private fun startPhotoCheckinOneTap() {
        val qrText = manualQr.text.toString().trim()
        if (qrText.isNotBlank()) processPhotoCheckinQr(qrText)
        else {
            flow = Flow.PHOTO_CHECKIN
            status.text = "Bước 1/2: Quét QR sự kiện"
            startScan()
        }
    }

    private fun processPhotoCheckinQr(qr: String) {
        val parsed = QrParser.parse(qr)
        if (parsed == null) { status.text = "QR không hợp lệ:\n$qr"; return }
        if (parsed.module != "SU_KIEN" || parsed.action != "CHECK_IN") {
            status.text = "Chỉ hỗ trợ SU_KIEN|CHECK_IN"; return
        }
        val ma = try { JSONObject(parsed.data).optString("maDiemDanh") }
                 catch (_: Exception) { "" }
        if (ma.isBlank()) { status.text = "QR thiếu maDiemDanh"; return }
        lastMa = ma; lastQr = qr; pendingPhotoMa = ma
        flow = Flow.PHOTO_CHECKIN
        status.text = "Bước 2/2: Chọn ảnh xác thực\nma=$ma"
        pickImageLauncher.launch("image/*")
    }

    private fun startCameraScan() {
        flow = Flow.CAMERA_SCAN
        status.text = "Đưa QR sự kiện vào khung"
        startScan()
    }

    private fun showManualDialog() {
        val input = EditText(this).apply {
            hint = "Mã điểm danh (VD: 153591)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Nhập mã điểm danh")
            .setMessage("Mã hiển thị tại sự kiện.")
            .setView(input)
            .setPositiveButton("Điểm danh") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isBlank()) { status.text = "Chưa nhập mã"; return@setPositiveButton }
                lastMa = code
                doCheckInWithMa(code)
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun startImageQr() {
        flow = Flow.IMAGE_QR
        status.text = "Chọn ảnh có chứa QR sự kiện"
        pickImageLauncher.launch("image/*")
    }

    private fun decodeQrThenCheckin(uri: Uri) {
        status.text = "Đang đọc ảnh và giải mã QR…"
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) { status.text = "Không đọc được ảnh"; return }
            val decodedQr = decodeQrFromBytes(bytes)
            if (decodedQr == null) {
                status.text = "❌ Không tìm thấy QR trong ảnh."
                return
            }
            doCheckIn(decodedQr)
        } catch (e: Exception) {
            status.text = "Lỗi đọc ảnh: ${e.message}"
        }
    }

    private fun decodeQrFromBytes(bytes: ByteArray): String? {
        return try {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val w = bmp.width; val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            MultiFormatReader().decode(binary).text
        } catch (_: Exception) { null }
    }

    private fun doCheckInWithMa(ma: String) {
        lastMa = ma
        val body = JSONObject()
            .put("ma", ma)
            .put("loaiQR", "Tham gia")
            .toString()
        status.text = "Đang check-in sự kiện…\nma=$ma\nPartition=${session.partitionCode}"
        postWithAuth("/slink/sv-su-kien/qr", body) { ok, resp ->
            runOnUiThread {
                if (ok && resp.contains("\"success\":true")) {
                    status.text = "✅ Điểm danh thành công\n$resp"
                } else if (isVersionBlocked(resp)) {
                    status.text = "❌ Version blocked\n$resp"
                } else {
                    status.text = "❌ Lỗi: $resp\n\nma=$ma"
                }
            }
        }
    }

    private fun doCheckIn(qr: String) {
        lastQr = qr
        val parsed = QrParser.parse(qr)
        if (parsed == null) { status.text = "QR không hợp lệ:\n$qr"; return }
        status.text = "Đang xử lý: ${parsed.module}|${parsed.action}"
        when {
            parsed.module == "SU_KIEN" && parsed.action == "CHECK_IN" -> {
                try {
                    val ma = JSONObject(parsed.data).optString("maDiemDanh")
                    if (ma.isBlank()) { status.text = "QR thiếu maDiemDanh"; return }
                    doCheckInWithMa(ma)
                } catch (e: Exception) { status.text = "Parse QR lỗi: ${e.message}" }
            }
            parsed.action.contains("DIEM_DANH", true) -> {
                val otp = parsed.otp.orEmpty().trim()
                if (otp.isBlank()) { status.text = "QR lớp không có otp"; return }
                val maNhomQr = extractMaNhomQr(parsed.data)
                val body = JSONObject().put("otp", otp).put("maNhomQr", maNhomQr).toString()
                status.text = "Đang điểm danh lớp…\notp=$otp\nmaNhomQr=$maNhomQr"
                postWithAuth("/qldt/diem-danh/user/qr", body) { ok, resp ->
                    runOnUiThread {
                        if (ok) status.text = "✅ Điểm danh lớp thành công\n$resp"
                        else status.text = "❌ Lỗi: $resp\n\nQR: $qr"
                    }
                }
            }
            else -> status.text = "Chưa hỗ trợ: ${parsed.module}|${parsed.action}"
        }
    }

    private fun doUploadAndCheckin(uri: Uri, ma: String) {
        status.text = "Bước 1/2: Đọc ảnh…\nma=$ma"
        try {
            val resolver = contentResolver
            var displayName = "image.jpg"
            val mime = resolver.getType(uri) ?: "image/jpeg"
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) displayName = c.getString(idx) ?: displayName
                }
            }
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) { status.text = "Không đọc được ảnh"; return }

            status.text = "Bước 1/2: Upload ảnh (${bytes.size / 1024} KB)…\nma=$ma"
            val url = Config.API_URL.trimEnd('/') + "/slink/file"
            val headers = authHeaders().toMutableMap().apply { remove("Content-Type") }

            Api.uploadFile(url, bytes, displayName, mime, headers, "file") { ok, resp ->
                runOnUiThread {
                    if (!ok) { status.text = "❌ Upload thất bại\n$resp"; return@runOnUiThread }
                    val fileId = extractFileId(resp)
                    if (fileId.isBlank()) {
                        status.text = "⚠️ Upload OK nhưng không có fileId\n$resp"
                        return@runOnUiThread
                    }
                    status.text = "Upload OK (fileId=$fileId)\nBước 2/2: Đang điểm danh…"
                    val body = JSONObject()
                        .put("ma", ma).put("loaiQR", "Tham gia").put("fileId", fileId).toString()
                    postWithAuth("/slink/sv-su-kien/qr", body) { ok2, resp2 ->
                        runOnUiThread {
                            if (ok2 && resp2.contains("\"success\":true"))
                                status.text = "✅ Điểm danh kèm ảnh thành công\n$resp2"
                            else
                                status.text = "❌ Lỗi: $resp2\nma=$ma\nfileId=$fileId"
                        }
                    }
                }
            }
        } catch (e: Exception) { status.text = "Lỗi: ${e.message}" }
    }

    private fun extractFileId(resp: String): String {
        return try {
            val j = JSONObject(resp)
            val fromData = j.optJSONObject("data")
            fromData?.optJSONObject("file")?.optString("_id").orEmpty().ifBlank {
                fromData?.optJSONObject("file")?.optString("id").orEmpty().ifBlank {
                    fromData?.optString("_id").orEmpty().ifBlank {
                        j.optString("fileId").ifBlank {
                            j.optJSONObject("file")?.optString("_id").orEmpty()
                        }
                    }
                }
            }
        } catch (_: Exception) { "" }
    }

    private fun startScan() {
        val o = ScanOptions()
        o.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        o.setPrompt("Đưa QR điểm danh vào khung")
        o.setBeepEnabled(false); o.setOrientationLocked(false)
        scanLauncher.launch(o)
    }

    private fun extractMaNhomQr(data: String): String {
        try {
            val o = JSONObject(data)
            for (k in listOf("maNhomQr", "ma_nhom_qr", "nhomQr", "ma"))
                if (o.has(k)) return o.getString(k)
        } catch (_: Exception) {}
        try {
            val raw = String(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
            val o = JSONObject(raw)
            if (o.has("maNhomQr")) return o.getString("maNhomQr")
        } catch (_: Exception) {}
        return data
    }

    private fun doUploadOnly(uri: Uri) {
        uploadResult.text = "Đang đọc ảnh…"
        try {
            val resolver = contentResolver
            var displayName = "image.jpg"
            val mime = resolver.getType(uri) ?: "image/jpeg"
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) displayName = c.getString(idx) ?: displayName
                }
            }
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) { uploadResult.text = "Không đọc được ảnh"; return }
            uploadResult.text = "Đang upload (${bytes.size / 1024} KB)…"
            val url = Config.API_URL.trimEnd('/') + "/slink/file"
            val headers = authHeaders().toMutableMap().apply { remove("Content-Type") }
            Api.uploadFile(url, bytes, displayName, mime, headers, "file") { ok, resp ->
                runOnUiThread {
                    if (!ok) { uploadResult.text = "❌ Upload lỗi\n$resp"; return@runOnUiThread }
                    val fileId = extractFileId(resp)
                    if (fileId.isNotBlank()) session.lastUploadedFileId = fileId
                    uploadResult.text = "✅ Upload OK" +
                        (if (fileId.isNotBlank()) " (fileId=$fileId)" else "") + "\n$resp"
                }
            }
        } catch (e: Exception) { uploadResult.text = "Lỗi: ${e.message}" }
    }

    // ========== VARIANTS TEST ==========
    private fun runVariantsWithInput() {
        val existing = lastMa
        if (existing.isNotBlank()) { testCheckInVariants(existing); return }
        val input = EditText(this).apply {
            hint = "Mã để test"; inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Test check-in variants")
            .setView(input)
            .setPositiveButton("Chạy") { _, _ ->
                val m = input.text.toString().trim()
                if (m.isNotBlank()) testCheckInVariants(m)
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun testCheckInVariants(ma: String) {
        status.text = "Đang lấy events để test variants…"
        getWithAuth("/slink/sv-su-kien/me/page?page=1&limit=1") { ok, resp ->
            runOnUiThread {
                var idSuKien = ""
                var idDotChamDiem = ""
                if (ok) try {
                    val arr = JSONObject(resp).getJSONObject("data").getJSONArray("result")
                    if (arr.length() > 0) {
                        val o = arr.getJSONObject(0)
                        idSuKien = o.optString("idSuKien")
                        idDotChamDiem = o.optString("idDotChamDiem")
                    }
                } catch (_: Exception) {}

                val variants = mutableListOf<Triple<String, String, Map<String, String>>>()
                variants.add(Triple("A_minimal",
                    JSONObject().put("ma", ma).put("loaiQR", "Tham gia").toString(),
                    mapOf("x-data-partition-code" to "PTIT")))
                variants.add(Triple("B_body_partition",
                    JSONObject().put("ma", ma).put("loaiQR", "Tham gia")
                        .put("dataPartitionCode", "PTIT").toString(),
                    mapOf("x-data-partition-code" to "PTIT")))
                if (idSuKien.isNotBlank()) variants.add(Triple("C_with_idSuKien",
                    JSONObject().put("ma", ma).put("loaiQR", "Tham gia")
                        .put("idSuKien", idSuKien).toString(),
                    mapOf("x-data-partition-code" to "PTIT")))
                if (idDotChamDiem.isNotBlank()) variants.add(Triple("D_with_idDot",
                    JSONObject().put("ma", ma).put("loaiQR", "Tham gia")
                        .put("idDotChamDiem", idDotChamDiem).toString(),
                    mapOf("x-data-partition-code" to "PTIT")))
                variants.add(Triple("I_dangky",
                    JSONObject().put("ma", ma).put("loaiQR", "Đăng ký").toString(),
                    mapOf("x-data-partition-code" to "PTIT")))
                if (idSuKien.isNotBlank()) variants.add(Triple("J_dangky_with_id",
                    JSONObject().put("ma", ma).put("loaiQR", "Đăng ký")
                        .put("idSuKien", idSuKien).toString(),
                    mapOf("x-data-partition-code" to "PTIT")))

                val log = StringBuilder().apply {
                    append("=== VARIANTS ===\nma=$ma\nidSuKien=$idSuKien\nidDot=$idDotChamDiem\n\n")
                }
                runVariants(variants, 0, log)
            }
        }
    }

    private fun runVariants(
        variants: List<Triple<String, String, Map<String, String>>>,
        idx: Int, log: StringBuilder
    ) {
        if (idx >= variants.size) {
            runOnUiThread { log.append("\n=== DONE ===\n"); status.text = log.toString() }
            return
        }
        val (name, body, overrides) = variants[idx]
        val url = Config.API_URL.trimEnd('/') + "/slink/sv-su-kien/qr"
        val headers = authHeaders().toMutableMap().apply { putAll(overrides) }
        runOnUiThread { log.append("⏳ $name\n"); status.text = log.toString() }
        Api.postJson(url, body, headers) { ok, resp ->
            val ok2 = ok && resp.contains("\"success\":true")
            runOnUiThread {
                log.append("$name  ok=$ok" + if (ok2) "  🎯" else "" + "\n")
                log.append("  ${resp.take(200)}\n\n")
                status.text = log.toString()
            }
            runVariants(variants, idx + 1, log)
        }
    }

    // ========== PROBE ==========
    private fun probeEndpoints() {
        val log = StringBuilder("=== PROBE ===\n")
        log.append("v=$appVersionName/$appVersionCode\n")
        log.append("partition=${session.partitionCode}\n\n")
        val endpoints = listOf(
            "GET" to "/slink/sv-su-kien/me/page?page=1&limit=1",
            "GET" to "/slink/sv-su-kien/me?page=1&limit=1",
            "GET" to "/slink/su-kien/user/from?page=1&limit=20",
            "GET" to "/slink/su-kien/me?page=1&limit=20"
        )
        probeNext(endpoints, 0, log)
    }

    private fun probeNext(
        endpoints: List<Pair<String, String>>, idx: Int, log: StringBuilder
    ) {
        if (idx >= endpoints.size) {
            runOnUiThread { log.append("\n=== DONE ===\n"); status.text = log.toString() }
            return
        }
        val (method, path) = endpoints[idx]
        val url = Config.API_URL.trimEnd('/') + "/" + path.trimStart('/')
        val tag = "[${idx + 1}/${endpoints.size}] $method $path"
        runOnUiThread { log.append("⏳ $tag\n"); status.text = log.toString() }
        val cb: (Boolean, String) -> Unit = { ok, resp ->
            runOnUiThread {
                log.append("────────────────────\n$tag\nok=$ok\n")
                log.append("resp:\n${resp.take(1500)}\n\n")
                status.text = log.toString()
            }
            probeNext(endpoints, idx + 1, log)
        }
        if (method == "GET") Api.get(url, authHeaders(), cb)
        else Api.postJson(url, "{}", authHeaders(), cb)
    }

    // ========== BRUTEFORCE ==========
    private fun showBruteforceDialog() {
        if (bruteThread?.isAlive == true) {
            AlertDialog.Builder(this).setTitle("Đang chạy")
                .setMessage("Dừng?")
                .setPositiveButton("Dừng") { _, _ -> bruteCancel = true }
                .setNegativeButton("Tiếp", null).show()
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val startIn = EditText(this).apply {
            hint = "Từ (150000)"; inputType = InputType.TYPE_CLASS_NUMBER
        }
        val endIn = EditText(this).apply {
            hint = "Đến (160000)"; inputType = InputType.TYPE_CLASS_NUMBER
        }
        val delayIn = EditText(this).apply {
            hint = "Delay ms (100)"; inputType = InputType.TYPE_CLASS_NUMBER
        }
        container.addView(startIn); container.addView(endIn); container.addView(delayIn)
        container.addView(TextView(this).apply {
            text = "⚠️ Bruteforce có thể bị ban. Chỉ chạy range hẹp khi có sự kiện thật."
            textSize = 11f; setPadding(0, 16, 0, 0)
        })
        AlertDialog.Builder(this).setTitle("Bruteforce")
            .setView(container)
            .setPositiveButton("Chạy") { _, _ ->
                val start = startIn.text.toString().trim().toIntOrNull() ?: 150000
                val end = endIn.text.toString().trim().toIntOrNull() ?: 160000
                val delay = delayIn.text.toString().trim().toLongOrNull() ?: 100L
                if (start < 0 || end > 999999 || start > end) {
                    status.text = "Range không hợp lệ"
                    return@setPositiveButton
                }
                startBruteforce(start, end, delay)
            }
            .setNegativeButton("Huỷ", null).show()
    }

    private fun startBruteforce(start: Int, end: Int, delayMs: Long) {
        bruteCancel = false
        val total = end - start + 1
        status.text = "Bruteforce $start..$end ($total mã)"
        val t = Thread {
            var tried = 0; var i = start
            while (i <= end && !bruteCancel) {
                val ma = i.toString().padStart(6, '0')
                val latch = java.util.concurrent.CountDownLatch(1)
                var success = false; var respCopy = "TIMEOUT"
                postWithAuth("/slink/sv-su-kien/qr",
                    JSONObject().put("ma", ma).put("loaiQR", "Tham gia").toString()) { ok, resp ->
                    success = ok; respCopy = resp; latch.countDown()
                }
                latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
                tried++
                val fm = ma; val fs = success; val fr = respCopy; val ft = tried
                runOnUiThread {
                    status.text = "[$ft/$total] ma=$fm\n" +
                        if (fs && fr.contains("\"success\":true")) "🎯 $fr"
                        else fr.take(150)
                }
                if (success && respCopy.contains("\"success\":true")) {
                    runOnUiThread { status.text = "🎯 Tìm thấy ma=$fm\n$respCopy" }
                    return@Thread
                }
                if (respCopy.contains("429") || respCopy.contains("rate limit", true)) {
                    runOnUiThread { status.text = "⚠️ Rate limit tại ma=$fm" }
                    return@Thread
                }
                try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@Thread }
                i++
            }
            runOnUiThread {
                status.text = if (bruteCancel) "Đã dừng ($tried mã)"
                              else "Xong ($tried mã, không thấy)"
            }
        }
        bruteThread = t; t.start()
    }
}
