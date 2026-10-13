package com.traetools.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * 本地安全存储：GitHub token、登录名、目标仓库名与账号凭证列表。
 * 优先使用 EncryptedSharedPreferences（AES256 主密钥由 Android Keystore 保护），
 * 少数设备上 Keystore 异常时回退到普通 SharedPreferences，避免启动崩溃。
 */
class AppPrefs(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            FILE_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        context.getSharedPreferences(FILE_NAME + "_plain", Context.MODE_PRIVATE)
    }

    var githubToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var githubLogin: String?
        get() = prefs.getString(KEY_LOGIN, null)
        set(value) = prefs.edit().putString(KEY_LOGIN, value).apply()

    var repoName: String
        get() = prefs.getString(KEY_REPO, DEFAULT_REPO)?.takeIf { it.isNotBlank() } ?: DEFAULT_REPO
        set(value) = prefs.edit().putString(KEY_REPO, value).apply()

    fun loadAccounts(): MutableList<Account> = Account.listFromJson(prefs.getString(KEY_ACCOUNTS, null))

    fun saveAccounts(accounts: List<Account>) =
        prefs.edit().putString(KEY_ACCOUNTS, Account.listToJson(accounts)).apply()

    fun clearAuthorization() = prefs.edit().remove(KEY_TOKEN).remove(KEY_LOGIN).apply()

    companion object {
        private const val FILE_NAME = "trae_tools_secure"
        private const val KEY_TOKEN = "github_token"
        private const val KEY_LOGIN = "github_login"
        private const val KEY_REPO = "repo_name"
        private const val KEY_ACCOUNTS = "accounts"
        const val DEFAULT_REPO = "TraeTools"
    }
}