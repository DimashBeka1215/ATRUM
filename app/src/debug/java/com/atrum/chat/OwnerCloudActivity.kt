package com.atrum.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Прозрачный экран-обёртка вокруг сценария «копия ключа через Google Drive».
 *
 * Почему отдельная Activity, а не диалог из списка чатов: вход в Google возвращается через
 * результат Activity, а регистрировать launcher можно только до старта экрана. Отдельный
 * экран владеет и своим launcher'ом, и своими окнами — и весь Google-код остаётся в
 * `src/debug`, не протекая в общий и в публичную сборку.
 *
 * В Drive кладётся РОВНО ТОТ ЖЕ файл `.atr`, что сохраняется на диск (см. [OwnerKeyFile]):
 * один формат на оба пути, чтобы копия, взятая откуда угодно, читалась одинаково.
 */
class OwnerCloudActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var mode = MODE_RESTORE

    private val signIn = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val account = runCatching {
            GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(
                com.google.android.gms.common.api.ApiException::class.java
            )
        }.getOrNull()
        if (account == null) toastAndFinish("Вход отменён") else proceed(account)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        mode = intent.getIntExtra(EXTRA_MODE, MODE_RESTORE)
        if (mode == MODE_SAVE) askSave() else askRestore()
    }

    // ── Окна ─────────────────────────────────────────────────────────────────

    private fun askSave() {
        NeonDialog.showConfirm(
            ctx = this,
            title = "Сохранить копию ключа?",
            message = "Ключ этого устройства опознан как ваш. Положу зашифрованную копию в " +
                "скрытую папку Google Drive — после любой очистки данных она вернёт его сама.",
            positiveText = "Сохранить",
            negativeText = "Не сейчас",
            onNegative = {
                prefs.ownerCloudDeclined = true
                finish()
            }
        ) { ensureAccount() }
    }

    private fun askRestore() {
        // Свой ключ показываем прямо здесь и кладём в буфер: когда галочка пропала впервые,
        // посмотреть его было нечем, и это оказалось главным тупиком.
        val key = prefs.myIdentityPubKey
        copyToClipboard("ATRUM identity", key)
        NeonDialog.showThreeChoice(
            ctx = this,
            title = "Права владельца не подтверждены",
            message = "Ключ этого устройства не опознан — похоже, данные сбрасывались. Войдите в " +
                "Google: приложение достанет копию ключа и вернёт права.\n\n" +
                "Ключ этого устройства (скопирован в буфер):\n$key",
            primaryText = "Войти через Google",
            onPrimary = { ensureAccount() },
            neutralText = "Взять из файла",
            onNeutral = {
                // Урезанный режим: файл должен работать и тогда, когда до Google не добраться.
                OwnerActivity.start(this, fileOnly = true)
                finish()
            },
            destructiveText = "Позже",
            onDestructive = {
                OwnerCloud.postpone()
                finish()
            },
            footnote = "Google хранит только зашифрованный файл. Права по-прежнему считаются " +
                "по подписи ключа, а не по аккаунту."
        )
    }

    // ── Аккаунт ──────────────────────────────────────────────────────────────

    private fun ensureAccount() {
        val scope = Scope(OwnerDrive.SCOPE)
        val existing = GoogleSignIn.getLastSignedInAccount(this)
        if (existing != null && GoogleSignIn.hasPermissions(existing, scope)) {
            proceed(existing)
            return
        }
        val opts = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(scope)
            .build()
        signIn.launch(GoogleSignIn.getClient(this, opts).signInIntent)
    }

    private fun proceed(account: GoogleSignInAccount) {
        if (mode == MODE_SAVE) askBackupPassword(account) else doRestore(account)
    }

    // ── Сохранение ───────────────────────────────────────────────────────────

    private fun askBackupPassword(account: GoogleSignInAccount) {
        NeonDialog.showEdit(
            ctx = this,
            title = "Пароль для копии",
            initialText = "",
            positiveText = "Далее",
            negativeText = getString(R.string.btn_cancel),
            subtitle = "Файл шифруется им перед отправкой — Google видит только шифртекст. " +
                "Восстановить этот пароль потом будет нельзя.",
            isPassword = true,
            validator = { it.length >= MIN_PASSWORD }
        ) { first ->
            NeonDialog.showEdit(
                ctx = this,
                title = "Повторите пароль",
                initialText = "",
                positiveText = "Сохранить",
                negativeText = getString(R.string.btn_cancel),
                isPassword = true
            ) { second ->
                if (first != second) toastAndFinish("Пароли не совпадают")
                else doSave(account, first)
            }
        }
    }

    private fun doSave(account: GoogleSignInAccount, password: String) {
        toast("Шифрую и отправляю…")
        lifecycleScope.launch {
            val err = withContext(Dispatchers.Default) {
                runCatching {
                    val blob = buildFileBytes(password) ?: error("не удалось собрать копию")
                    OwnerDrive.save(this@OwnerCloudActivity, account, blob)
                }.exceptionOrNull()
            }
            if (err != null) {
                toastAndFinish("Не удалось сохранить: ${err.message?.take(80)}")
                return@launch
            }
            prefs.ownerCloudSaved = true
            NeonDialog.showInfo(
                ctx = this@OwnerCloudActivity,
                title = "Копия сохранена",
                message = "Она лежит в скрытой папке приложения на Google Drive. После очистки " +
                    "данных войдите в тот же аккаунт — ключ вернётся.",
                onDismiss = { finish() }
            )
        }
    }

    /** Тот же файл, что пишется на диск: ключ + заранее подписанный отзыв, зашифровано паролем. */
    private fun buildFileBytes(password: String): ByteArray? {
        val (priv, pub) = prefs.getOrCreateIdentity()
        return try {
            val revSig = CryptoHelper.signWithIdentity(priv, VerifiedBadge.revokeSigData(pub))
                ?: return null
            OwnerKeyFile.build(priv, pub, revSig, password)
        } finally {
            priv.fill(0)
        }
    }

    // ── Восстановление ───────────────────────────────────────────────────────

    private fun doRestore(account: GoogleSignInAccount) {
        toast("Ищу копию…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { OwnerDrive.load(this@OwnerCloudActivity, account) }
            }
            val err = result.exceptionOrNull()
            if (err != null) {
                toastAndFinish("Не удалось прочитать: ${err.message?.take(80)}")
                return@launch
            }
            val blob = result.getOrNull()
            if (blob == null) {
                NeonDialog.showInfo(
                    ctx = this@OwnerCloudActivity,
                    title = "Копии нет",
                    message = "В этом аккаунте копия ключа не найдена. Либо аккаунт не тот, либо " +
                        "копию так и не сохранили. Если файл .atr у вас есть — возьмите ключ из него.",
                    onDismiss = { finish() }
                )
                return@launch
            }
            askRestorePassword(blob)
        }
    }

    private fun askRestorePassword(blob: ByteArray) {
        NeonDialog.showEdit(
            ctx = this,
            title = "Пароль от копии",
            initialText = "",
            positiveText = "Восстановить",
            negativeText = getString(R.string.btn_cancel),
            isPassword = true
        ) { pwd -> applyRestore(blob, pwd) }
    }

    private fun applyRestore(blob: ByteArray, password: String) {
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.Default) {
                runCatching { OwnerKeyFile.parse(blob, password) }
                    .getOrDefault(OwnerKeyFile.Parsed.Unreadable)
            }
            when (parsed) {
                is OwnerKeyFile.Parsed.Unreadable ->
                    toastAndFinish("Не читается: не тот пароль или повреждённая копия")
                is OwnerKeyFile.Parsed.Ok -> {
                    prefs.replaceIdentity(parsed.privB64, parsed.pubB64)
                    val known = VerifiedBadge.isKeyVerified(parsed.pubB64)
                    NeonDialog.showInfo(
                        ctx = this@OwnerCloudActivity,
                        title = if (known) "Ключ восстановлен" else "Ключ заменён",
                        message = if (known)
                            "Галочка и права вернулись. Копия остаётся в скрытой папке на Google Drive."
                        else
                            "Ключ применён, но он не входит в список владельцев — прав он не даёт.",
                        onDismiss = { finish() }
                    )
                }
            }
        }
    }

    // ── Мелочи ───────────────────────────────────────────────────────────────

    private fun copyToClipboard(label: String, text: String) {
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun toastAndFinish(text: String) {
        toast(text)
        finish()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SAVE = 1
        const val MODE_RESTORE = 2

        /** Короче этого пароль не принимаем: копия уезжает наружу, в чужое хранилище. */
        private const val MIN_PASSWORD = 8

        fun start(context: Context, mode: Int) {
            context.startActivity(
                Intent(context, OwnerCloudActivity::class.java).putExtra(EXTRA_MODE, mode)
            )
        }
    }
}
