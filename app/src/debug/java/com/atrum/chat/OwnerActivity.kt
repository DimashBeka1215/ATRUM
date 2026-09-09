package com.atrum.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.databinding.ActivityOwnerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран «Владелец» — только личная сборка, только при `owner.tools=true`.
 *
 * Здесь живут три вещи, которым больше негде: показ своего ключа, копия ключа файлом и отзыв.
 * Пароля у раздела нет намеренно — прошлый вариант с паролем ничего не защищал (см. историю в
 * PersonalFeatures.ownerTools), а настоящую защиту даёт то, что чужой ключ не входит в
 * [VerifiedBadge.VERIFIED].
 *
 * Экран лежит в `src/debug`, поэтому в публичный APK не попадает вовсе — как и Google-код
 * рядом. Общий код открывает его через [OwnerCloud.openOwner], у которого в `src/release`
 * пустая заглушка.
 */
class OwnerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOwnerBinding
    private lateinit var prefs: Prefs

    /** Содержимое .atr, ждущее, пока пользователь выберет, куда его сохранить. */
    private var pendingFile: ByteArray? = null

    /**
     * Урезанный режим: доступно ТОЛЬКО восстановление из файла.
     *
     * Нужен, чтобы файл `.atr` остался независимым путём. Полный раздел требует входа в Google
     * под аккаунтом владельца, но если ключ потерян, а до гугла не добраться, человек всё равно
     * обязан суметь подложить файл — иначе вся затея с файлом теряет смысл.
     */
    private var fileOnly = false

    private val createFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> writePending(uri) }

    private val openFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) askFilePassword(uri) else toast("Файл не выбран") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOwnerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        // Открыт по тапу на .atr или из окна «ключ не опознан» — это режим «только файл».
        fileOnly = intent?.action == Intent.ACTION_VIEW || intent?.getBooleanExtra(EXTRA_FILE_ONLY, false) == true

        // Полный раздел существует только для владельца, вошедшего в свой Google-аккаунт.
        // Без этого экран просто не открывается — ни строкой в настройках, ни напрямую.
        if (!fileOnly && !OwnerCloud.isOwnerAccount(this)) {
            finish()
            return
        }
        if (fileOnly) {
            binding.itemShowKey.visibility = View.GONE
            binding.itemBackup.visibility = View.GONE
            binding.itemRevoke.visibility = View.GONE
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.itemShowKey.setOnClickListener { showKey() }
        binding.itemBackup.setOnClickListener { askBackupPassword() }
        binding.itemRestore.setOnClickListener { openFile.launch(arrayOf("*/*")) }
        binding.itemRevoke.setOnClickListener { confirmRevoke() }

        // Открытие .atr тапом из файлового менеджера: экран поднимается сразу с файлом.
        // ⚠️ Работает не везде: Android опознаёт такие файлы по расширению в пути, а многие
        // менеджеры отдают content-URI без него. Поэтому «Восстановить из файла» остаётся —
        // это надёжный путь, а intent-filter лишь удобство.
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { askFilePassword(it) }
        }

        render()
    }

    private fun render() {
        val key = prefs.myIdentityPubKey
        binding.tvKeyPreview.text = key
        binding.cardWarning.visibility =
            if (VerifiedBadge.isKeyVerified(key)) View.GONE else View.VISIBLE
    }

    // ── Показ ключа ──────────────────────────────────────────────────────────

    private fun showKey() {
        val key = prefs.myIdentityPubKey
        copyToClipboard("ATRUM identity", key)
        NeonDialog.showInfo(
            ctx = this,
            title = "Мой ключ",
            message = "$key\n\nСкопирован в буфер. Это ПУБЛИЧНЫЙ ключ — подписи им не подделать."
        )
    }

    // ── Сохранение копии ─────────────────────────────────────────────────────

    private fun askBackupPassword() {
        NeonDialog.showEdit(
            ctx = this,
            title = "Пароль для копии",
            initialText = "",
            positiveText = "Далее",
            negativeText = getString(R.string.btn_cancel),
            subtitle = "Им шифруется файл. Восстановить этот пароль потом будет нельзя.",
            isPassword = true,
            validator = { it.length >= MIN_PASSWORD }
        ) { first ->
            NeonDialog.showEdit(
                ctx = this,
                title = "Повторите пароль",
                initialText = "",
                positiveText = "Создать",
                negativeText = getString(R.string.btn_cancel),
                isPassword = true
            ) { second ->
                if (first != second) toast("Пароли не совпадают") else buildFile(first)
            }
        }
    }

    private fun buildFile(password: String) {
        toast("Шифрую…")
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.Default) {
                runCatching {
                    val (priv, pub) = prefs.getOrCreateIdentity()
                    try {
                        // Отзыв подписывается ЗАРАНЕЕ и кладётся в файл: подписать его может
                        // только владелец ключа, а если ключ потерян — подписывать уже нечем.
                        val revSig = CryptoHelper.signWithIdentity(priv, VerifiedBadge.revokeSigData(pub))
                            ?: return@runCatching null
                        OwnerKeyFile.build(priv, pub, revSig, password)
                    } finally {
                        priv.fill(0)
                    }
                }.getOrNull()
            }
            if (bytes == null) {
                toast("Не удалось собрать копию")
                return@launch
            }
            pendingFile = bytes
            createFile.launch(OwnerKeyFile.DEFAULT_NAME)
        }
    }

    private fun writePending(uri: Uri?) {
        val bytes = pendingFile
        pendingFile = null
        if (uri == null || bytes == null) {
            toast("Сохранение отменено")
            return
        }
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching false
            true
        }.getOrDefault(false)
        if (!ok) {
            toast("Не удалось записать файл")
            return
        }
        NeonDialog.showConfirm(
            ctx = this,
            title = "Копия сохранена",
            message = "Файл записан. Положить такую же копию в скрытую папку Google Drive? " +
                "Тогда после очистки данных ключ вернётся сам, без файла под рукой.",
            positiveText = "В Google Drive",
            negativeText = "Не надо",
            onNegative = { }
        ) {
            OwnerCloudActivity.start(this, OwnerCloudActivity.MODE_SAVE)
        }
    }

    // ── Восстановление из файла ──────────────────────────────────────────────

    private fun askFilePassword(uri: Uri) {
        NeonDialog.showEdit(
            ctx = this,
            title = "Пароль от копии",
            initialText = "",
            positiveText = "Восстановить",
            negativeText = getString(R.string.btn_cancel),
            isPassword = true
        ) { pwd -> applyFile(uri, pwd) }
    }

    private fun applyFile(uri: Uri, password: String) {
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@runCatching OwnerKeyFile.Parsed.Unreadable
                    OwnerKeyFile.parse(bytes, password)
                }.getOrDefault(OwnerKeyFile.Parsed.Unreadable)
            }
            when (parsed) {
                is OwnerKeyFile.Parsed.Unreadable ->
                    // Неверный пароль, чужой файл и порченые байты намеренно неотличимы:
                    // решает GCM-тег, и подсказывать, что именно не сошлось, незачем.
                    toast("Файл не читается: не тот пароль или не тот файл")
                is OwnerKeyFile.Parsed.Ok -> confirmRestore(parsed)
            }
        }
    }

    private fun confirmRestore(ok: OwnerKeyFile.Parsed.Ok) {
        NeonDialog.showConfirm(
            ctx = this,
            title = "Заменить ключ устройства?",
            message = "Новый ключ: ${ok.pubB64}\n\nТекущий будет заменён. Собеседники увидят " +
                "смену ключа — после сброса данных это ожидаемо.",
            positiveText = "Заменить",
            positiveIsDestructive = true,
            negativeText = getString(R.string.btn_cancel)
        ) {
            prefs.replaceIdentity(ok.privB64, ok.pubB64)
            render()
            toast(
                if (VerifiedBadge.isKeyVerified(ok.pubB64)) "Ключ восстановлен"
                else "Ключ заменён, но он не в списке владельцев"
            )
        }
    }

    // ── Отзыв ────────────────────────────────────────────────────────────────

    private fun confirmRevoke() {
        val pub = prefs.myIdentityPubKey
        if (RevokedKeys.isRevoked(pub)) {
            toast("Этот ключ уже отозван")
            return
        }
        NeonDialog.showConfirm(
            ctx = this,
            title = "Отозвать ключ?",
            message = "Отменить это нельзя: отозванный ключ не примут обратно ни при каком " +
                "восстановлении. Отзыв уедет с профилем во все беседы, и у собеседников " +
                "погаснут галочка и права. Делайте это, если ключ утёк.",
            positiveText = "Отозвать",
            positiveIsDestructive = true,
            negativeText = getString(R.string.btn_cancel)
        ) {
            doRevoke(pub)
        }
    }

    private fun doRevoke(pub: String) {
        lifecycleScope.launch {
            val done = withContext(Dispatchers.Default) {
                runCatching {
                    val (priv, _) = prefs.getOrCreateIdentity()
                    val sig = try {
                        CryptoHelper.signWithIdentity(priv, VerifiedBadge.revokeSigData(pub))
                    } finally {
                        priv.fill(0)
                    } ?: return@runCatching false
                    RevokedKeys.revokeMine(pub, sig)
                }.getOrDefault(false)
            }
            if (!done) {
                toast("Не удалось подписать отзыв")
                return@launch
            }
            // Разгоняем отзыв по всем чатам: он приложится к профилю на ближайшей публикации.
            withContext(Dispatchers.IO) {
                runCatching {
                    AppDatabase.get(applicationContext).chatDao().getAll()
                        .filter { !it.isFavorites }
                        .forEach { PublishScheduler.markMyProfileDirty(applicationContext, it.chatId) }
                }
            }
            render()
            NeonDialog.showInfo(
                ctx = this@OwnerActivity,
                title = "Ключ отозван",
                message = "Отзыв разослан во все беседы. У тех, кто его прочитает, ключ погаснет " +
                    "навсегда. Это не мгновенно: без сервера отзыв доезжает по мере чтения профилей."
            )
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

    companion object {
        /** Короче этого пароль не принимаем: файл уезжает наружу, за пределы устройства. */
        private const val MIN_PASSWORD = 8

        /** Флаг урезанного режима: только восстановление из файла, без входа в Google. */
        const val EXTRA_FILE_ONLY = "file_only"

        fun start(context: Context, fileOnly: Boolean = false) {
            context.startActivity(
                Intent(context, OwnerActivity::class.java).putExtra(EXTRA_FILE_ONLY, fileOnly)
            )
        }
    }
}
