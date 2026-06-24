package com.sendrealm.sdk

class SendrealmConfig {
    var baseUrl: String? = null
        private set

    var externalUserId: String? = null
        private set

    var userEmail: String? = null
        private set

    var platform: String = "android"
        private set

    var autoRequestPermission: Boolean = true
        private set

    var forceRefreshRegistrationToken: Boolean = false
        private set

    fun setBaseUrl(baseUrl: String?): SendrealmConfig {
        this.baseUrl = baseUrl?.trimEnd('/')
        return this
    }

    fun setExternalUserId(externalUserId: String?): SendrealmConfig {
        this.externalUserId = externalUserId
        return this
    }

    fun setUserEmail(userEmail: String?): SendrealmConfig {
        this.userEmail = userEmail
        return this
    }

    fun setPlatform(platform: String): SendrealmConfig {
        this.platform = platform.ifBlank { "android" }
        return this
    }

    fun setAutoRequestPermission(autoRequestPermission: Boolean): SendrealmConfig {
        this.autoRequestPermission = autoRequestPermission
        return this
    }

    fun setForceRefreshRegistrationToken(forceRefreshRegistrationToken: Boolean): SendrealmConfig {
        this.forceRefreshRegistrationToken = forceRefreshRegistrationToken
        return this
    }
}
