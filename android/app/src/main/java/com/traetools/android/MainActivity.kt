package com.traetools.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.traetools.android.data.Account
import com.traetools.android.data.AppPrefs
import com.traetools.android.databinding.ActivityMainBinding
import com.traetools.android.databinding.DialogDeviceCodeBinding
import com.traetools.android.github.DeviceCode
import com.traetools.android.github.GitHubApi
import com.traetools.android.github.GitHubDeviceFlow
import com.traetools.android.github.GitHubException
import com.traetools.android.secret.SecretNaming
import com.traetools.android.ui.AccountAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * 主界面：GitHub 设备码授权 → 登录 trae.cn 取会话 Cookie → 同步为仓库 Actions Secrets → 触发签到。
 *
 * 凭证只写入用户自己的 GitHub 仓库 Secrets，本地使用 EncryptedSharedPreferences 保存。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private lateinit var adapter: AccountAdapter
    private lateinit var accounts: MutableList<Account>
    private val logBuilder = StringBuilder()

    private var reloginIndex: Int? = null
    private var pollJob: Job? = null

    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            log("已取消 Trae 登录")
            reloginIndex = null
            return@registerForActivityResult
        }
        val data = result.data
        val session = data?.getStringExtra(WebLoginActivity.RESULT_SESSION)
        if (session.isNullOrEmpty()) {
            log("未获取到会话凭证，请重试")
            reloginIndex = null
            return@registerForActivityResult
        }
        val uid = data.getStringExtra(WebLoginActivity.RESULT_UID)
        val index = reloginIndex
        if (index == null) {
            accounts.add(Account(session = session, deviceId = randomDeviceId(), uid = uid))
            log("已添加账号（UID：${uid ?: "未识别"}）")
        } else {
            accounts.getOrNull(index)?.let { account ->
                account.session = session
                if (!uid.isNullOrEmpty()) account.uid = uid
                log("账号 ${index + 1} 已更新会话凭证")
            }
        }
        reloginIndex = null
        persistAndRefresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)
        accounts = prefs.loadAccounts()

        adapter = AccountAdapter(
            onRelogin = { index ->
                reloginIndex = index
                startTraeLogin()
            },
            onDelete = { index ->
                accounts.getOrNull(index)?.let {
                    accounts.removeAt(index)
                    log("已删除账号（UID：${it.uid ?: "未识别"}）")
                    persistAndRefresh()
                }
            },
            onDeviceChanged = { index, value ->
                accounts.getOrNull(index)?.let {
                    if (it.deviceId != value) {
                        it.deviceId = value
                        prefs.saveAccounts(accounts)
                    }
                }
            }
        )
        binding.rvAccounts.layoutManager = LinearLayoutManager(this)
        binding.rvAccounts.adapter = adapter

        binding.etRepo.setText(prefs.repoName)
        binding.etRepo.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveRepoName() }

        binding.btnGithubAuth.setOnClickListener { startDeviceFlow() }
        binding.btnGithubLogout.setOnClickListener { logoutGithub() }
        binding.btnAddAccount.setOnClickListener {
            reloginIndex = null
            startTraeLogin()
        }
        binding.btnSync.setOnClickListener { syncSecrets() }
        binding.btnDispatch.setOnClickListener { dispatchWorkflow() }

        refreshGithubStatus()
        refreshAccounts()
        log("凭证仅保存在本机与你的 GitHub 仓库 Secrets 中，App 不会上传到任何第三方服务器。")
    }

    // ---------------- GitHub 设备码授权 ----------------

    private fun startDeviceFlow() {
        if (pollJob?.isActive == true) {
            log("授权轮询进行中，请先在浏览器完成授权")
            return
        }
        lifecycleScope.launch {
            log("正在申请 GitHub 设备码…")
            val code = try {
                withContext(Dispatchers.IO) { GitHubDeviceFlow.requestCode() }
            } catch (e: Exception) {
                log("申请设备码失败：${e.message}")
                return@launch
            }
            showDeviceCodeDialog(code)
        }
    }

    private fun showDeviceCodeDialog(code: DeviceCode) {
        val dialogBinding = DialogDeviceCodeBinding.inflate(layoutInflater)
        dialogBinding.tvUserCode.text = code.userCode

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.device_dialog_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.btn_cancel, null)
            .setCancelable(false)
            .create()

        var authorized = false
        dialogBinding.btnCopyCode.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("github-device-code", code.userCode))
            log("已复制设备码，请在浏览器中粘贴授权")
        }
        dialogBinding.btnOpenBrowser.setOnClickListener { openBrowser(code.verificationUri) }
        dialog.setOnDismissListener { if (!authorized) pollJob?.cancel() }
        dialog.show()

        openBrowser(code.verificationUri)
        pollDeviceCode(code, dialog) { authorized = true }
    }

    private fun pollDeviceCode(code: DeviceCode, dialog: AlertDialog, onAuthorized: () -> Unit) {
        pollJob = lifecycleScope.launch {
            val deadline = System.currentTimeMillis() + AUTHORIZATION_TIMEOUT_MS
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(code.interval.coerceAtLeast(5) * 1000L)
                when (val poll = withContext(Dispatchers.IO) { GitHubDeviceFlow.poll(code.deviceCode) }) {
                    is GitHubDeviceFlow.Poll.Pending -> Unit
                    is GitHubDeviceFlow.Poll.Success -> {
                        onAuthorized()
                        dialog.dismiss()
                        applyGithubToken(poll.token)
                        return@launch
                    }
                    is GitHubDeviceFlow.Poll.Failure -> {
                        dialog.dismiss()
                        log("GitHub 授权失败：${poll.reason}")
                        return@launch
                    }
                }
            }
            dialog.dismiss()
            log("授权等待超时，请重新发起授权")
        }
    }

    private suspend fun applyGithubToken(token: String) {
        val login = try {
            withContext(Dispatchers.IO) { GitHubApi(token).getLogin() }
        } catch (e: Exception) {
            log("获取 GitHub 用户失败：${e.message}")
            prefs.clearAuthorization()
            refreshGithubStatus()
            return
        }
        prefs.githubToken = token
        prefs.githubLogin = login
        log("GitHub 授权成功：$login")
        refreshGithubStatus()
    }

    private fun logoutGithub() {
        pollJob?.cancel()
        prefs.clearAuthorization()
        log("已退出 GitHub 授权")
        refreshGithubStatus()
    }

    // ---------------- Trae 登录 ----------------

    private fun startTraeLogin() {
        loginLauncher.launch(Intent(this, WebLoginActivity::class.java))
    }

    // ---------------- 同步 / 触发 ----------------

    private fun syncSecrets() {
        val token = prefs.githubToken
        val login = prefs.githubLogin
        if (token.isNullOrEmpty() || login.isNullOrEmpty()) {
            log("请先完成 GitHub 授权")
            return
        }
        if (accounts.isEmpty()) {
            log("没有账号可同步，请先登录 Trae 添加账号")
            return
        }
        val missing = accounts.withIndex().filter { !it.value.hasSession }
        if (missing.isNotEmpty()) {
            log("以下账号缺少会话凭证，请重新登录：" + missing.joinToString("、") { "账号 ${it.index + 1}" })
            return
        }
        val repo = repoName()

        lifecycleScope.launch {
            binding.btnSync.isEnabled = false
            try {
                withContext(Dispatchers.IO) {
                    val api = GitHubApi(token)
                    if (!api.repoExists(login, repo)) {
                        throw GitHubException("未找到仓库 $login/$repo，请确认仓库名或在 GitHub 上创建")
                    }
                    accounts.forEachIndexed { i, account ->
                        val index = i + 1
                        val device = account.deviceId.ifBlank { randomDeviceId() }
                        api.putSecret(login, repo, SecretNaming.sessionName(index), account.session)
                        api.putSecret(login, repo, SecretNaming.deviceName(index), device)
                        account.deviceId = device
                        log("已写入账号 $index 的会话与设备号")
                    }
                    // 清理多余的更高编号，避免旧账号残留继续签到
                    for (index in (accounts.size + 1)..STALE_SCAN_LIMIT) {
                        runCatching {
                            api.deleteSecret(login, repo, SecretNaming.sessionName(index))
                            api.deleteSecret(login, repo, SecretNaming.deviceName(index))
                        }
                    }
                }
                prefs.saveAccounts(accounts)
                refreshAccounts()
                log("同步完成：共 ${accounts.size} 个账号写入 $login/$repo")
            } catch (e: Exception) {
                log("同步失败：${e.message}")
            } finally {
                binding.btnSync.isEnabled = true
            }
        }
    }

    private fun dispatchWorkflow() {
        val token = prefs.githubToken
        val login = prefs.githubLogin
        if (token.isNullOrEmpty() || login.isNullOrEmpty()) {
            log("请先完成 GitHub 授权")
            return
        }
        val repo = repoName()
        lifecycleScope.launch {
            binding.btnDispatch.isEnabled = false
            try {
                withContext(Dispatchers.IO) {
                    GitHubApi(token).dispatchWorkflow(login, repo, SecretNaming.WORKFLOW_PATH)
                }
                log("已触发云端签到，请稍后到 GitHub Actions 查看结果")
            } catch (e: Exception) {
                log("触发失败：${e.message}")
            } finally {
                binding.btnDispatch.isEnabled = true
            }
        }
    }

    // ---------------- UI 辅助 ----------------

    private fun refreshGithubStatus() {
        val login = prefs.githubLogin
        val authorized = !prefs.githubToken.isNullOrEmpty() && !login.isNullOrEmpty()
        if (authorized) {
            binding.tvGithubStatus.text = getString(R.string.github_authorized, login)
            binding.btnGithubAuth.visibility = View.GONE
            binding.btnGithubLogout.visibility = View.VISIBLE
        } else {
            binding.tvGithubStatus.setText(R.string.github_unauthorized)
            binding.btnGithubAuth.visibility = View.VISIBLE
            binding.btnGithubLogout.visibility = View.GONE
        }
        updateRepoDesc()
    }

    private fun updateRepoDesc() {
        binding.tvRepoDesc.text = getString(R.string.repo_desc, "${prefs.githubLogin ?: "?"}/${repoName()}")
    }

    private fun repoName(): String =
        binding.etRepo.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: AppPrefs.DEFAULT_REPO

    private fun saveRepoName() {
        prefs.repoName = repoName()
        updateRepoDesc()
    }

    private fun refreshAccounts() {
        adapter.submit(accounts)
        binding.tvAccountsEmpty.visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun persistAndRefresh() {
        prefs.saveAccounts(accounts)
        refreshAccounts()
    }

    private fun log(message: String) {
        val line = "[" + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) + "] " + message
        runOnUiThread {
            logBuilder.append(line).append('\n')
            binding.tvLog.text = logBuilder.toString()
        }
    }

    private fun openBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            log("无法打开浏览器，请手动访问：$url")
        }
    }

    private fun randomDeviceId(): String {
        val sb = StringBuilder(16)
        sb.append(Random.nextInt(1, 10))
        repeat(15) { sb.append(Random.nextInt(0, 10)) }
        return sb.toString()
    }

    companion object {
        private const val AUTHORIZATION_TIMEOUT_MS = 15 * 60 * 1000L
        private const val STALE_SCAN_LIMIT = 10
    }
}