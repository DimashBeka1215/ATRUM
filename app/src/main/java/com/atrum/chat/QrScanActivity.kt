package com.atrum.chat

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.atrum.chat.databinding.ActivityQrScanBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Сканер QR на CameraX + ZXing.
 *  - Режим [MODE_BT]: распознаёт "ATRUMBT:<token>" → возвращает токен в [EXTRA_TOKEN].
 *  - Режим [MODE_INVITE]: распознаёт invite/deep-link (atrum://join#ATRM…) → [EXTRA_RAW].
 * В обоих режимах полный payload отдаётся в [EXTRA_RAW].
 */
class QrScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScanBinding
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val handled = AtomicBoolean(false)

    /** Что принимаем: BLE-токен (по умолчанию) или invite-приглашение. */
    private var mode = MODE_BT

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                // Сканер принимает только QR (BT-токен и invite оба кодируются в QR) —
                // ограничение формата чуть ускоряет декод и не влияет на распознавание.
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
            )
        )
    }

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
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            }.onFailure { finish() }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Диагностика (см. CLAUDE.md §14): раньше ЛЮБОЕ исключение в этой функции —
     * включая НЕ штатные (не NotFoundException) — молча проглатывалось на каждом
     * кадре без единого следа. Если декодер падает не на «QR не найден в кадре»,
     * а на чём-то структурном (например IllegalArgumentException из
     * PlanarYUVLuminanceSource при несовпадении реального размера буфера кадра с
     * ожидаемым на конкретном железе), сканер выглядел как «вообще не реагирует»,
     * и разобраться без логов было невозможно. Показываем причину ОДИН раз (не
     * спамим тостами по кадрам) — этого достаточно, чтобы прислать текст ошибки.
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

    private fun decode(proxy: ImageProxy) {
        if (handled.get()) { proxy.close(); return }
        try {
            val plane = proxy.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            val w = proxy.width
            val h = proxy.height
            val source = PlanarYUVLuminanceSource(data, plane.rowStride, h, 0, 0, w.coerceAtMost(plane.rowStride), h, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = try {
                reader.decodeWithState(bitmap)
            } catch (_: com.google.zxing.NotFoundException) {
                null // штатно — в этом конкретном кадре QR не найден, пробуем следующий
            } catch (e: Throwable) {
                reportDecodeError("decodeWithState", e)
                null
            }
            reader.reset()
            val text = result?.text
            if (text != null) {
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
        } catch (e: Throwable) {
            // Это уже НЕ decodeWithState (тот перехвачен выше) — значит упало на сборке
            // источника/битмапа (PlanarYUVLuminanceSource/BinaryBitmap/HybridBinarizer)
            // ДО декодера. Раньше такой кадр молча пропускался КАЖДЫЙ раз без следа.
            reportDecodeError("decode", e)
        } finally {
            proxy.close()
        }
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
