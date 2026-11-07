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

    private val appVersionName = "2.3.6"
    private val appVersionCode = "80064"

    private enum class PendingFlow { NONE, CAMERA_THEN_PHOTO, IMAGE_QR_SELF, MANUAL_THEN_PHOTO }
    private var pendingFlow = PendingFlow.NONE
    private var pendingQr: String? = null
    private var pendingCode: String? = null

    // ========== Launchers ==========
    private val scanLauncher = registerForActivityResult(ScanContract()) { r ->
        val content = r.contents
        when (pendingFlow) {
            PendingFlow.CAMERA_THEN_PHOTO -> {
                if (content == null) {
                    pendingFlow = PendingFlow.NONE
                    pendingQr = null
                } else {
                    pendingQr = content
                    status.text = "✅ Đã quét QR\nBước 2/2: Chọn ảnh xác thực"
                    pickImageLauncher.launch("image/*")
                }
            }
            PendingFlow.NONE -> {
                if (content != null) doCheckIn(content)
            }
            else -> { /* không xảy ra */ }
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val flow = pendingFlow
        val qr = pendingQr
        val code = pendingCode
        pendingFlow = PendingFlow.NONE
        pendingQr = null
        pendingCode = null

        when (flow) {
            PendingFlow.NONE -> {
                if (uri != null) doUploadOnly(uri)
            }
            PendingFlow.CAMERA_THEN_PHOTO -> {
                if (uri == null || qr == null) {
                    status.text = "Đã huỷ"
                } else {
                    val parsed = QrParser.parse(qr)
                    if (parsed == null) {
                        status.text = "QR không hợp lệ"; return@registerForActivityResult
                    }
                    if (parsed.module != "SU_KIEN" || parsed.action != "CHECK_IN") {
                        status.text = "Chỉ hỗ trợ SU_KIEN|CHECK_IN cho flow có ảnh"
                        return@registerForActivityResult
                    }
                    val ma = try { JSONObject(parsed.data).optString("maDiemDanh") }
                              catch (_: Exception) { "" }
                    if (ma.isBlank()) {
                        status.text = "QR thiếu maDiemDanh"; return@registerForActivityResult
                    }
                    uploadAndCheckin(uri, ma)
                }
            }
            PendingFlow.IMAGE_QR_SELF -> {
                if (uri == null) { status.text = "Đã huỷ" }
                else decodeQrThenUploadAndCheckin(uri)
            }
            PendingFlow.MANUAL_THEN_PHOTO -> {
                if (uri == null || code == null) { status.text = "Đã huỷ" }
                else uploadAndCheckin(uri, code)
            }
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
            val code      = data.getQueryParameter("code")
            val state     = data.getQueryParameter("state")
            val error     = data.getQueryParameter("error")
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
        homeBox.addView(TextView(this).apply {
            text = "Điểm danh kèm ảnh"; textSize = 16f; setPadding(0, 0, 0, 8)
        })
        homeBox.addView(Button(this).apply {
            text = "📷 Quét QR bằng camera"
            setOnClickListener { flowCamera() }
        })
        homeBox.addView(Button(this).apply {
            text = "🖼️ Quét QR từ ảnh"
            setOnClickListener { flowImageQr() }
        })
        homeBox.addView(Button(this).apply {
            text = "⌨️ Nhập mã điểm danh"
            setOnClickListener { flowManual() }
        })

        homeBox.addView(TextView(this).apply {
            text = "Điểm danh không ảnh"; textSize = 16f; setPadding(0, 24, 0, 8)
        })
        homeBox.addView(Button(this).apply {
            text = "Quét QR (không ảnh)"
            setOnClickListener { pendingFlow = PendingFlow.NONE; startScan() }
        })
        manualQr = EditText(this).apply { hint = "Dán chuỗi QR (dự phòng)" }
        homeBox.addView(manualQr)
        homeBox.addView(Button(this).apply {
            text = "Điểm danh từ chuỗi QR"
            setOnClickListener {
                val s = manualQr.text.toString().trim()
                if (s.isNotEmpty()) doCheckIn(s)
            }
        })

        homeBox.addView(TextView(this).apply {
            text = "Tiện ích"; textSize = 16f; setPadding(0, 24, 0, 8)
        })
        homeBox.addView(Button(this).apply {
            text = "📤 Upload ảnh (không điểm danh)"
            setOnClickListener { pendingFlow = PendingFlow.NONE; pickImageLauncher.launch("image/*") }
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
            text = "Settings"; setOnClickListener { showSettings() }
        })
        homeBox.addView(Button(this).apply {
            text = "Đăng xuất"
            setOnClickListener {
                session.clear(); refreshUi(); status.text = "Đã đăng xuất"
            }
        })
        root.addView(homeBox)

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

    private fun refreshUi() {
        val logged = session.isLoggedIn()
        loginBox.visibility    = if (logged) View.GONE else View.VISIBLE
        homeBox.visibility     = if (logged) View.VISIBLE else View.GONE
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

    // ========== 3 FLOW CHÍNH ==========

    /** Flow 1: Quét QR bằng camera → upload ảnh → điểm danh. */
    private fun flowCamera() {
        pendingFlow = PendingFlow.CAMERA_THEN_PHOTO
        pendingQr = null
        status.text = "Bước 1/2: Quét QR sự kiện"
        startScan()
    }

    /** Flow 2: Chọn ảnh có QR → decode QR → upload chính ảnh đó → điểm danh. */
    private fun flowImageQr() {
        pendingFlow = PendingFlow.IMAGE_QR_SELF
        pendingQr = null
        status.text = "Chọn ảnh có chứa QR sự kiện"
        pickImageLauncher.launch("image/*")
    }

    /** Flow 3: Nhập mã điểm danh → upload ảnh xác thực → điểm danh. */
    private fun flowManual() {
        val input = EditText(this).apply {
            hint = "Mã điểm danh (VD: 153591)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Nhập mã điểm danh")
            .setMessage("Nhập mã điểm danh hiển thị tại sự kiện. Sau đó chọn ảnh xác thực.")
            .setView(input)
            .setPositiveButton("Tiếp tục") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isBlank()) {
                    status.text = "Chưa nhập mã"; return@setPositiveButton
                }
                pendingCode = code
                pendingFlow = PendingFlow.MANUAL_THEN_PHOTO
                status.text = "Đã nhập mã: $code\nBước 2/2: Chọn ảnh xác thực"
                pickImageLauncher.launch("image/*")
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    // ========== UPLOAD + ĐIỂM DANH ==========

    /** Đọc ảnh → decode QR từ bitmap → upload → checkin. Dùng cho flow 2. */
    private fun decodeQrThenUploadAndCheckin(uri: Uri) {
        status.text = "Đang đọc ảnh và giải mã QR…"
        try {
            val resolver = contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) { status.text = "Không đọc được ảnh"; return }

            val decodedQr = decodeQrFromBytes(bytes)
            if (decodedQr == null) {
                status.text = "❌ Không tìm thấy QR trong ảnh.\n" +
                        "Thử ảnh chụp QR rõ nét hơn, hoặc dùng flow 'Nhập mã điểm danh'."
                return
            }

            val parsed = QrParser.parse(decodedQr)
            if (parsed == null || parsed.module != "SU_KIEN" || parsed.action != "CHECK_IN") {
                status.text = "QR trong ảnh không phải sự kiện CHECK_IN:\n$decodedQr"
                return
            }
            val ma = try { JSONObject(parsed.data).optString("maDiemDanh") }
                     catch (_: Exception) { "" }
            if (ma.isBlank()) { status.text = "QR thiếu maDiemDanh:\n$decodedQr"; return }

            status.text = "✅ Đã giải mã QR\nma=$ma\nBước 1/2: Đang upload ảnh…"
            uploadBytesAndCheckin(resolver, uri, bytes, ma)
        } catch (e: Exception) {
            status.text = "Lỗi: ${e.message}"
        }
    }

    /** Upload ảnh từ Uri rồi gọi checkin với ma đã biết. */
    private fun uploadAndCheckin(uri: Uri, ma: String) {
        status.text = "Bước 1/2: Đang đọc ảnh…"
        try {
            val resolver = contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) { status.text = "Không đọc được ảnh"; return }
            uploadBytesAndCheckin(resolver, uri, bytes, ma)
        } catch (e: Exception) { status.text = "Lỗi đọc ảnh: ${e.message}" }
    }

    private fun uploadBytesAndCheckin(
        resolver: android.content.ContentResolver, uri: Uri, bytes: ByteArray, ma: String
    ) {
        var displayName = "image.jpg"
        val mime = resolver.getType(uri) ?: "image/jpeg"
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) displayName = c.getString(idx) ?: displayName
            }
        }

        status.text = "Bước 1/2: Upload ảnh (${bytes.size / 1024} KB)…"
        val url = Config.API_URL.trimEnd('/') + "/slink/file"
        val headers = authHeaders().toMutableMap().apply { remove("Content-Type") }

        Api.uploadFile(url, bytes, displayName, mime, headers, "file") { ok, resp ->
            runOnUiThread {
                if (!ok) { status.text = "❌ Upload thất bại\n$resp"; return@runOnUiThread }
                val fileId = try {
                    JSONObject(resp).getJSONObject("data").getJSONObject("file").getString("_id")
                } catch (_: Exception) { "" }
                if (fileId.isBlank()) {
                    status.text = "Upload OK nhưng không lấy được fileId\n$resp"
                    return@runOnUiThread
                }
                session.lastUploadedFileId = fileId
                status.text = "Upload OK (fileId=$fileId)\nBước 2/2: Đang điểm danh…"

                val body = JSONObject()
                    .put("ma", ma)
                    .put("loaiQR", "Tham gia")
                    .put("fileId", fileId)
                    .toString()
                postWithAuth("/slink/sv-su-kien/qr", body) { ok2, resp2 ->
                    runOnUiThread {
                        if (ok2) {
                            status.text = "✅ Điểm danh thành công\n$resp2"
                            session.lastUploadedFileId = ""
                        } else {
                            status.text = "❌ Lỗi điểm danh: $resp2\nma=$ma\nfileId=$fileId"
                        }
                    }
                }
            }
        }
    }

    // ========== Decode QR từ bitmap ==========
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

    // ========== Quét QR không ảnh ==========
    private fun startScan() {
        val o = ScanOptions()
        o.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        o.setPrompt("Đưa QR điểm danh vào khung")
        o.setBeepEnabled(false); o.setOrientationLocked(false)
        scanLauncher.launch(o)
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
                    val body = JSONObject().put("ma", ma).put("loaiQR", "Tham gia").toString()
                    status.text = "Đang check-in sự kiện (không ảnh)…\nma=$ma"
                    postWithAuth("/slink/sv-su-kien/qr", body) { ok, resp ->
                        runOnUiThread {
                            if (ok) status.text = "✅ Check-in thành công\n$resp"
                            else status.text = "❌ Lỗi: $resp\n\nQR: $qr"
                        }
                    }
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
}
