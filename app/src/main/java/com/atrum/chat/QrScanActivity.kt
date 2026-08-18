package com.atrum.chat

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.util.Size
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.atrum.chat.databinding.ActivityQrScanBinding
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

/**
 * Сканер QR на CameraX + ML Kit (репорт: «QR сканируется долго» — ML Kit заметно быстрее и
 * надёжнее ZXing на реальных углах/освещении, см. аудит по этому репорту). Bundled-модель
 * (`com.google.mlkit:barcode-scanning`, НЕ `play-services-mlkit-barcode-scanning`) — модель
 * зашита в APK и работает полностью офлайн, без Google Play Services и без обращений в сеть
 * ни при первом запуске, ни когда-либо ещё — тот же принцип, что и у остального приложения
 * (§1 CLAUDE.md, «без серверов»). Цена — фиксированные +~2.4МБ к размеру APK.
 *  - Режим [MODE_BT]: распознаёт "ATRUMBT:<token>" → возвращает токен в [EXTRA_TOKEN].
 *  - Режим [MODE_INVITE]: распознаёт invite/deep-link (atrum://join#ATRM…) → [EXTRA_RAW].
 * В обоих режимах полный payload отдаётся в [EXTRA_RAW].
 *
 * ⚠️ ZXing НЕ удалён из проекта — он остался в QrGen.kt для ГЕНЕРАЦИИ QR сверки (SAS),
 * это не связано со сканированием и трогать не нужно (см. build.gradle.kts).
 */
class QrScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScanBinding
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val handled = AtomicBoolean(false)

    /** Что принимаем: BLE-токен (по умолчанию) или invite-приглашение. */
    private var mode = MODE_BT

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            // Сканер принимает только QR (BT-токен и invite оба кодируются в QR) —
            // ограничение формата чуть ускоряет детекцию и не влияет на распознавание
            // (тот же принцип, что был у ZXing POSSIBLE_FORMATS).
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                Toast.makeText(this, getString(R.string.qr_scan_camera_needed), Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_BT

        binding.btnBack.setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: run {
                finish(); return@addListener
            }
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val analysisBuilder = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // БАГ (частично исправлено): без явного режима автофокуса CameraX для связки
            // Preview+ImageAnalysis (без ImageCapture) на части устройств выбирает
            // CONTROL_AF_MODE_CONTINUOUS_VIDEO — он оптимизирован под плавное видео и
            // менее агрессивно наводится на резкость на близкой дистанции (10-20см),
            // на которой обычно держат телефон при сканировании QR. Отсюда и жалоба:
            // сторонний сканер (со своим автофокусом фото-типа) читает тот же QR, а наш
            // экран — нет, потому что кадр анализа просто расфокусирован. Форсируем
            // CONTINUOUS_PICTURE — тот же режим, что использует системная камера для фото,
            // он агрессивнее подстраивается под близкие объекты.
            // graceful fallback: setCaptureRequestOption не бросает исключение, если режим
            // недоступен на конкретном железе — камера просто продолжит с дефолтным AF.
            runCatching {
                Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }
            val analysis = analysisBuilder.build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> decode(proxy) }

            runCatching {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                setupTapToFocus(camera)
            }.onFailure { finish() }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Тап по превью — ручная наводка фокуса на точку касания. Непрерывный автофокус
     * (CONTINUOUS_PICTURE) иногда «плавает» и долго не ловит резкость на близком QR; тап даёт
     * пользователю мгновенно навести фокус туда, где код, и заметно ускоряет распознавание.
     */
    private fun setupTapToFocus(camera: Camera) {
        binding.previewView.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val point = binding.previewView.meteringPointFactory.createPoint(e.x, e.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                runCatching { camera.cameraControl.startFocusAndMetering(action) }
                v.performClick()
            }
            true
        }
    }

    /**
     * Диагностика (см. CLAUDE.md §14): раньше ЛЮБОЕ исключение при декоде молча проглатывалось
     * на каждом кадре без единого следа — сканер выглядел как «вообще не реагирует», и
     * разобраться без логов было невозможно. Показываем причину ОДИН раз (не спамим тостами
     * по кадрам) — этого достаточно, чтобы прислать текст ошибки.
     */
    private val reportedError = AtomicBoolean(false)

    private fun reportDecodeError(where: String, e: Throwable) {
        if (!reportedError.compareAndSet(false, true)) return
        val msg = "$where: ${e::class.simpleName}: ${e.message}"
        android.util.Log.e("AtrumQr", msg, e)
        runOnUiThread {
            Toast.makeText(this, "QR-сканер: $msg", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * ⚠️ Раньше (ZXing) кадр обрезался до центрального квадрата ~70% перед декодом — это и
     * было фиксом «безумно долгого» скана, отбрасывало больше половины пикселей. С ML Kit
     * этот трюк НЕ переносим: `InputImage.fromMediaImage` игнорирует `Image.cropRect` — ML
     * Kit всегда анализирует кадр целиком (см. googlesamples/mlkit#491, официального обхода
     * нет). Компенсируем тем же способом, что советует сама доктрина ML Kit — разрешением
     * анализа (см. `setTargetResolution(1280,720)` в startCamera — уже ниже подсказанного
     * докой потолка ~2МП), а не ручной сборкой обрезанного NV21-буфера из отдельных
     * YUV-плоскостей: риск багов на конкретном железе (шаг строки/подвыборка хромы) — не тот
     * риск, который стоит брать в код, читающий и BT-пейринг, и инвайт.
     */
    @OptIn(ExperimentalGetImage::class)
    private fun decode(proxy: ImageProxy) {
        if (handled.get()) { proxy.close(); return }
        val mediaImage = proxy.image
        if (mediaImage == null) { proxy.close(); return }
        val image = try {
            InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        } catch (e: Throwable) {
            reportDecodeError("fromMediaImage", e)
            proxy.close()
            return
        }
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val text = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue
                    ?: return@addOnSuccessListener
                if (mode == MODE_INVITE) {
                    val invite = InviteCodec.extractInvite(text)
                    if (invite != null && handled.compareAndSet(false, true)) {
                        runOnUiThread { onScanned(invite, null) }
                    }
                } else {
                    val token = QrGen.parseBtToken(text)
                    if (token != null && handled.compareAndSet(false, true)) {
                        runOnUiThread { onScanned(text, token) }
                    }
                }
            }
            // Штатных «не найдено» здесь нет (в отличие от ZXing NotFoundException) — ML Kit
            // на пустом кадре просто зовёт addOnSuccessListener с пустым списком, а не падает.
            // addOnFailureListener — это уже структурная ошибка самого детектора.
            .addOnFailureListener { e -> reportDecodeError("scanner.process", e) }
            .addOnCompleteListener { proxy.close() }
    }

    /** Возвращает результат: EXTRA_RAW — всегда, EXTRA_TOKEN — только для BLE. */
    private fun onScanned(raw: String, btToken: String?) {
        val intent = android.content.Intent().putExtra(EXTRA_RAW, raw)
        if (btToken != null) intent.putExtra(EXTRA_TOKEN, btToken)
        setResult(RESULT_OK, intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { analysisExecutor.shutdown() }
        runCatching { scanner.close() }
    }

    companion object {
        const val EXTRA_TOKEN = "bt_token"
        /** Полный распознанный payload (BLE-токен или invite-строка). */
        const val EXTRA_RAW = "raw"
        /** Режим сканера: какой QR принимаем. */
        const val EXTRA_MODE = "mode"
        const val MODE_BT = "bt"
        const val MODE_INVITE = "invite"
    }
}
