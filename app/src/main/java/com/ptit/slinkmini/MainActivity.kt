package com.ptit.slinkmini

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

    // ====== Cấu hình mặc định gửi kèm request (mô phỏng app gốc) ======
    // Đọc từ strings.xml của app gốc
    private val appVersionName = "2.3.6"
    private val appVersionCode = "80064"

    private val scanLauncher = registerForActivityResult(ScanContract()) { r ->
        r.contents?.let { doCheckIn(it) }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { doUploadImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        setContentView(buildUi())
        handleIntent(intent)
        refreshUi()
        if (session.isLoggedIn()) preparePartition()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    // ==================== Deep link SSO ====================
    private fun handleIntent(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme == "ptit.auth" && data.host == "SignIn") {
            val code      = data.getQueryParameter("code")
            val state     = data.getQueryParameter("state")
            val error     = data.getQueryParameter("error")
            val errorDesc = data.getQueryParameter("error_description")

            if (error != null) {
                status.text = "SSO lỗi: $error\n${errorDesc.orEmpty()}"; return
            }
            if (code.isNullOrBlank()) {
                status.text = "Không nhận được code từ SSO"; return
            }
            if (state != session.pendingState) {
                status.text = "State mismatch — thử đăng nhập lại"; return
            }
            exchangeCodeForToken(code)
        }
    }

    // ==================== UI ====================
    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "PTIT S-Link Mini"; textSize = 22f
            setPadding(0, 0, 0, 24)
        })

        // --- Login ---
        loginBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        loginBox.addView(Button(this).apply {
            text = "Đăng nhập qua SSO (PTIT Keycloak)"
            setOnClickListener { startSso() }
        })
        loginBox.addView(Button(this).apply {
            text = "Đã có token — mở Settings"
            setOnClickListener { showSettings() }
        })
        root.addView(loginBox)

        // --- Home ---
        homeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        homeBox.addView(TextView(this).apply {
            text = "Sẵn sàng điểm danh"; textSize = 16f
            setPadding(0, 0, 0, 16)
        })
        homeBox.addView(Button(this).apply {
            text = "Quét QR điểm danh"
            setOnClickListener { startScan() }
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
        homeBox.addView(Button(this).apply {
            text = "📷 Upload ảnh"
            setOnClickListener { pickImageLauncher.launch("image/*") }
        })
        uploadResult = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, 8, 0, 8)
        }
        homeBox.addView(uploadResult)
        homeBox.addView(Button(this).apply {
            text = "Copy kết quả upload"
            setOnClickListener {
                val t = uploadResult.text.toString()
                if (t.isNotBlank()) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("upload", t))
                    Toast.makeText(this@MainActivity, "Đã copy", Toast.LENGTH_SHORT).show()
                }
            }
        })
        homeBox.addView(Button(this).apply {
            text = "Settings"
            setOnClickListener { showSettings() }
        })
        homeBox.addView(Button(this).apply {
            text = "Đăng xuất"
            setOnClickListener {
                session.clear(); refreshUi()
                status.text = "Đã đăng xuất"
            }
        })
        root.addView(homeBox)

        // --- Settings ---
        settingsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        tokenIn = EditText(this).apply {
            hint = "Paste access_token"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        partIn = EditText(this).apply { hint = "x-data-partition-code (tùy chọn)" }
        settingsBox.addView(tokenIn)
        settingsBox.addView(partIn)
        settingsBox.addView(Button(this).apply {
            text = "Lưu"
            setOnClickListener {
                val t = tokenIn.text.toString().trim()
                if (t.isNotBlank()) session.accessToken = t
                session.partitionCode = partIn.text.toString().trim()
                status.text = "Đã lưu"
                refreshUi()
                if (session.isLoggedIn()) preparePartition()
            }
        })
        settingsBox.addView(Button(this).apply {
            text = "Quay lại"
            setOnClickListener { refreshUi() }
        })
        root.addView(settingsBox)

        status = TextView(this).apply {
            textSize = 12f
            setPadding(0, 20, 0, 0)
            setTextIsSelectable(true)
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
        loginBox.visibility = View.GONE
        homeBox.visibility  = View.GONE
        settingsBox.visibility = View.VISIBLE
        tokenIn.setText("")
        partIn.setText(session.partitionCode)
    }

    // ==================== SSO PKCE ====================
    private fun startSso() {
        val verifier  = Pkce.newCodeVerifier()
        val challenge = Pkce.challenge(verifier)
        val state     = Pkce.newState()
        session.pendingVerifier = verifier
        session.pendingState    = state

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
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            status.text = "Không mở được browser: ${e.message}"
        }
    }

    private fun exchangeCodeForToken(code: String) {
        val verifier = session.pendingVerifier
        if (verifier.isBlank()) {
            status.text = "Không có code_verifier — thử lại"; return
        }
        status.text = "Đang đổi code lấy token…"
        val form = mapOf(
            "grant_type"    to "authorization_code",
            "client_id"     to Config.CLIENT_ID,
            "code"          to code,
            "redirect_uri"  to Config.REDIRECT_URI,
            "code_verifier" to verifier
        )
        Api.postForm(Config.TOKEN_ENDPOINT, form) { ok, body ->
            runOnUiThread {
                if (!ok) {
                    status.text = "Đổi code thất bại: $body"; return@runOnUiThread
                }
                try {
                    val j = JSONObject(body)
                    session.accessToken  = j.optString("access_token")
                    session.refreshToken = j.optString("refresh_token")
                    session.idToken      = j.optString("id_token")
                    session.expiresAt = System.currentTimeMillis() +
                            j.optLong("expires_in", 300) * 1000
                    session.pendingVerifier = ""
                    session.pendingState    = ""
                    status.text = "Đăng nhập OK"
                    refreshUi()
                    preparePartition()
                } catch (e: Exception) {
                    status.text = "Parse token lỗi: ${e.message}\n$body"
                }
            }
        }
    }

    private fun refreshAccessToken(onDone: (Boolean) -> Unit) {
        val rt = session.refreshToken
        if (rt.isBlank()) { onDone(false); return }
        val form = mapOf(
            "grant_type"    to "refresh_token",
            "client_id"     to Config.CLIENT_ID,
            "refresh_token" to rt
        )
        Api.postForm(Config.TOKEN_ENDPOINT, form) { ok, body ->
            runOnUiThread {
                if (!ok) { onDone(false); return@runOnUiThread }
                try {
                    val j = JSONObject(body)
                    session.accessToken  = j.optString("access_token", session.accessToken)
                    session.refreshToken = j.optString("refresh_token", session.refreshToken)
                    session.expiresAt = System.currentTimeMillis() +
                            j.optLong("expires_in", 300) * 1000
                    onDone(true)
                } catch (_: Exception) { onDone(false) }
            }
        }
    }

    // ==================== Partition ====================
    private fun preparePartition() {
        val fromJwt = partitionFromJwt()
        if (fromJwt.isNotBlank()) { session.partitionCode = fromJwt; return }
        getWithAuth("/core/data-partition/user/many/me") { ok, body ->
            if (ok) {
                try {
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

    // ==================== Headers có version ====================
    private fun authHeaders(): Map<String, String> {
        val h = mutableMapOf(
            "Authorization"   to "Bearer ${session.accessToken}",
            "Content-Type"    to "application/json",
            "Accept"          to "application/json",
            "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8",

            // ==== Shotgun version headers ====
            "X-App-Version"    to appVersionName,
            "X-Client-Version" to appVersionName,
            "X-Version"        to appVersionName,
            "App-Version"      to appVersionName,
            "X-App-VersionCode" to appVersionCode,
            "X-Client-VersionCode" to appVersionCode,
            "X-App-Platform"   to "android",
            "X-Platform"       to "android",
            "X-Client-Id"      to Config.CLIENT_ID,
            "User-Agent"       to "PTIT-S-Link/$appVersionName (Android; $appVersionCode)"
        )
        if (session.partitionCode.isNotBlank())
            h["x-data-partition-code"] = session.partitionCode
        return h
    }

    // ==================== Auth-aware requests ====================
    private fun getWithAuth(path: String, onResult: (Boolean, String) -> Unit) =
        requestWithAuth("GET", path, null, onResult)

    private fun postWithAuth(path: String, body: String, onResult: (Boolean, String) -> Unit) =
        requestWithAuth("POST", path, body, onResult)

    private fun requestWithAuth(
        method: String, path: String, body: String?,
        onResult: (Boolean, String) -> Unit
    ) {
        val url = Config.API_URL.trimEnd('/') + "/" + path.trimStart('/')
        val headers = authHeaders()
        val cb: (Boolean, String) -> Unit = { ok, resp ->
            if (!ok && (resp.contains("401") || resp.contains("TokenInvalid", true))) {
                refreshAccessToken { refreshed ->
                    if (refreshed) requestWithAuth(method, path, body, onResult)
                    else onResult(false, resp)
                }
            } else onResult(ok, resp)
        }
        when (method) {
            "GET"  -> Api.get(url, headers, cb)
            "POST" -> Api.postJson(url, body ?: "{}", headers, cb)
        }
    }

    // ==================== QR scan ====================
    private fun startScan() {
        val o = ScanOptions()
        o.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        o.setPrompt("Đưa QR điểm danh vào khung")
        o.setBeepEnabled(false)
        o.setOrientationLocked(false)
        scanLauncher.launch(o)
    }

    private fun doCheckIn(qr: String) {
        val parsed = QrParser.parse(qr)
        if (parsed == null) { status.text = "QR không hợp lệ:\n$qr"; return }

        status.text = "Đang xử lý: ${parsed.module}|${parsed.action}"

        when {
            // Sự kiện
            parsed.module == "SU_KIEN" && parsed.action == "CHECK_IN" -> {
                try {
                    val j = JSONObject(parsed.data)
                    val ma = j.optString("maDiemDanh")
                    if (ma.isBlank()) {
                        status.text = "QR sự kiện thiếu maDiemDanh:\n${parsed.data}"; return
                    }
                    val body = JSONObject()
                        .put("ma", ma)
                        .put("loaiQR", "Tham gia")
                        .toString()
                    status.text = "Đang check-in sự kiện…\nma=$ma"
                    postWithAuth("/slink/sv-su-kien/qr", body) { ok, resp ->
                        runOnUiThread {
                            if (ok) status.text = "✅ Check-in sự kiện thành công\n$resp"
                            else status.text = "❌ Lỗi: $resp\n\nQR: $qr"
                        }
                    }
                } catch (e: Exception) {
                    status.text = "Parse JSON QR thất bại: ${e.message}\n${parsed.data}"
                }
            }

            // Lớp
            parsed.action.contains("DIEM_DANH", true) -> {
                val otp = parsed.otp.orEmpty().trim()
                if (otp.isBlank()) {
                    status.text = "QR điểm danh lớp không có otp:\n$qr"; return
                }
                val maNhomQr = extractMaNhomQr(parsed.data)
                val body = JSONObject()
                    .put("otp", otp)
                    .put("maNhomQr", maNhomQr)
                    .toString()
                status.text = "Đang điểm danh lớp…\notp=$otp\nmaNhomQr=$maNhomQr"
                postWithAuth("/qldt/diem-danh/user/qr", body) { ok, resp ->
                    runOnUiThread {
                        if (ok) status.text = "✅ Điểm danh lớp thành công\n$resp"
                        else status.text = "❌ Lỗi: $resp\n\nQR: $qr"
                    }
                }
            }

            else -> status.text = "Chưa hỗ trợ module: ${parsed.module}|${parsed.action}\n$qr"
        }
    }

    private fun extractMaNhomQr(data: String): String {
        try {
            val o = JSONObject(data)
            for (k in listOf("maNhomQr", "ma_nhom_qr", "nhomQr", "ma")) {
                if (o.has(k)) return o.getString(k)
            }
        } catch (_: Exception) {}
        try {
            val raw = String(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
            val o = JSONObject(raw)
            if (o.has("maNhomQr")) return o.getString("maNhomQr")
        } catch (_: Exception) {}
        return data
    }

    // ==================== Upload ảnh ====================
    private fun doUploadImage(uri: Uri) {
        uploadResult.text = "Đang đọc ảnh…"
        try {
            val resolver = contentResolver
            // Lấy tên file và mime
            var displayName = "image.jpg"
            var mime = resolver.getType(uri) ?: "image/jpeg"
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) displayName = c.getString(idx) ?: displayName
                }
            }
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                uploadResult.text = "Không đọc được file ảnh"; return
            }
            uploadResult.text = "Đang upload ${bytes.size / 1024} KB…"

            val url = Config.API_URL.trimEnd('/') + "/slink/file"
            // Header không có Content-Type vì multipart tự set
            val headers = authHeaders().toMutableMap().apply { remove("Content-Type") }

            Api.uploadFile(url, bytes, displayName, mime, headers,
                fieldName = "file"
            ) { ok, resp ->
                runOnUiThread {
                    if (ok) uploadResult.text = "✅ Upload OK\n$resp"
                    else uploadResult.text = "❌ Upload lỗi\n$resp"
                }
            }
        } catch (e: Exception) {
            uploadResult.text = "Lỗi đọc/upload: ${e.message}"
        }
    }
}
