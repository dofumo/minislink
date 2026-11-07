package com.ptit.slinkmini

object Config {
    // Đã xác nhận từ strings.xml + doc QR check-in
    const val API_URL      = "https://gwdu.ptit.edu.vn"
    const val SSO_URL      = "https://gwdu.ptit.edu.vn/sso/realms/ptit"
    const val CLIENT_ID    = "ptit-connect"        // = ${SUB_NAME}-connect
    const val REDIRECT_URI = "ptit.auth://SignIn"
    const val SCOPE        = "openid profile"

    const val AUTH_ENDPOINT  = "$SSO_URL/protocol/openid-connect/auth"
    const val TOKEN_ENDPOINT = "$SSO_URL/protocol/openid-connect/token"
}
