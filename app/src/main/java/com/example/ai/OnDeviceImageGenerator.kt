package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.tasks.core.ErrorListener
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGeneratorResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "OnDeviceImageGenerator"

class AiGenerationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

object OnDeviceImageGenerator {

    private val seedRandom = SecureRandom()

    /**
     * Executes on-device Stable Diffusion inference via MediaPipe [ImageGenerator] on
     * [Dispatchers.Default]. Runs completely offline using the model files under [modelDir].
     */
    suspend fun generate(
        context: Context,
        modelDir: File,
        prompt: String,
        aspectRatio: AiAspectRatio,
        steps: Int,
        seed: Int = nextRandomSeed(),
        onStepProgress: (completedStep: Int, totalSteps: Int) -> Unit = { _, _ -> }
    ): Bitmap = withContext(Dispatchers.Default) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) {
            throw AiGenerationException("Enter a prompt to generate an image.")
        }
        if (!modelDir.exists() || !modelDir.isDirectory) {
            throw AiGenerationException("Offline AI model is not installed in sd_model.")
        }

        val clampedSteps = SdModelManager.clampInferenceSteps(steps)
        val safeSeed = seed.coerceAtLeast(1)
        val appContext = context.applicationContext
        val runtimeError = AtomicReference<String?>(null)
        var generator: ImageGenerator? = null

        try {
            currentCoroutineContext().ensureActive()

            val options = ImageGenerator.ImageGeneratorOptions.builder()
                .setImageGeneratorModelDirectory(modelDir.absolutePath)
                .setErrorListener(
                    ErrorListener { error ->
                        Log.e(TAG, "MediaPipe ImageGenerator runtime error", error)
                        runtimeError.compareAndSet(null, error.localizedMessage ?: error.message)
                    }
                )
                .build()

            generator = ImageGenerator.createFromOptions(appContext, options)
            currentCoroutineContext().ensureActive()

            generator.setInputs(cleanPrompt, clampedSteps, safeSeed)

            var finalResult: ImageGeneratorResult? = null
            for (step in 0 until clampedSteps) {
                currentCoroutineContext().ensureActive()
                val showResult = (step == clampedSteps - 1)
                val stepResult = generator.execute(showResult)
                if (showResult) {
                    finalResult = stepResult
                }
                onStepProgress(step + 1, clampedSteps)
            }

            currentCoroutineContext().ensureActive()

            val mpImage = finalResult?.generatedImage()
                ?: throw AiGenerationException(
                    runtimeError.get() ?: "Image generation completed without output."
                )

            val rawBitmap = mpImage.use { image ->
                val extracted = BitmapExtractor.extract(image)
                // Copy into an independent ARGB_8888 bitmap owned by ZamLab before closing MPImage/generator
                extracted.copy(Bitmap.Config.ARGB_8888, false)
            } ?: throw AiGenerationException("Failed to decode generated bitmap.")

            applyAspectCrop(rawBitmap, aspectRatio)
        } catch (e: CancellationException) {
            throw e
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError during on-device image generation", oom)
            Runtime.getRuntime().gc()
            throw AiGenerationException(
                "Device ran low on memory during AI generation. Try closing background apps or lowering steps to 12–15.",
                oom
            )
        } catch (link: UnsatisfiedLinkError) {
            Log.e(TAG, "Native MediaPipe library load failure", link)
            throw AiGenerationException(
                "On-device image generator native runtime is not supported on this device architecture.",
                link
            )
        } catch (e: AiGenerationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "On-device image generation failed", e)
            val detail = runtimeError.get() ?: e.localizedMessage ?: "Unexpected generation failure."
            throw AiGenerationException("AI generation failed: $detail", e)
        } finally {
            runCatching { generator?.close() }
        }
    }

    private fun applyAspectCrop(source: Bitmap, aspectRatio: AiAspectRatio): Bitmap {
        val rect = SdModelManager.computeCenterCropRect(source.width, source.height, aspectRatio)
        if (rect.left == 0 && rect.top == 0 && rect.width == source.width && rect.height == source.height) {
            return source
        }
        val cropped = Bitmap.createBitmap(source, rect.left, rect.top, rect.width, rect.height)
        if (cropped !== source && !source.isRecycled) {
            source.recycle()
        }
        return cropped
    }

    private fun nextRandomSeed(): Int {
        return seedRandom.nextInt(Int.MAX_VALUE - 1) + 1
    }
}
