package com.example.utils

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object OpenRouterAiHelper {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(40, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Resizes bitmap and encodes to Base64 JPEG string
     */
    fun bitmapToBase64(bitmap: Bitmap, maxDimension: Int = 1024): String {
        val width = bitmap.width
        val height = bitmap.height

        val scaledBitmap = if (width > maxDimension || height > maxDimension) {
            val ratio = width.toFloat() / height.toFloat()
            val newWidth = if (width > height) maxDimension else (maxDimension * ratio).toInt()
            val newHeight = if (height > width) maxDimension else (maxDimension / ratio).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth.coerceAtLeast(1), newHeight.coerceAtLeast(1), true)
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    /**
     * Analyzes receipt image with OpenRouter Vision AI
     */
    suspend fun analyzeReceiptWithAi(
        bitmap: Bitmap,
        apiKey: String,
        modelName: String = "google/gemini-2.0-flash-001"
    ): ScannedReceiptData = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("API Key OpenRouter belum diisi. Silakan masukkan API Key terlebih dahulu.")
        }

        val base64Image = bitmapToBase64(bitmap)
        val imageUrl = "data:image/jpeg;base64,$base64Image"

        val prompt = """
            Kamu adalah asisten keuangan pintar dan OCR presisi. Analisis foto struk, bukti bayar, nota, tiket, atau bukti transfer berikut.
            Ekstrak data transaksi dengan teliti dan presisi tinggi.
            
            Aturan:
            1. Jika struk memiliki daftar barang (seperti di Indomaret, minimarket, cafe, resto), cantumkan setiap item di array 'items' dengan nama presisi dan harga aslinya.
            2. 'type' harus 'EXPENSE' (untuk pengeluaran/pembelian/pembayaran) atau 'INCOME' (untuk transfer masuk, gaji, terima uang).
            3. 'wallet' pilih yang paling sesuai: 'Tunai', 'E-Wallet', 'Rekening 1', 'Rekening 2', atau 'Rekening 3'.
            4. Kategori item harus salah satu dari: 'Makanan', 'Minuman', 'Belanja(Shopping)', 'Transportasi', 'Tagihan Rutin', 'Kesehatan', 'Perawatan Diri', 'Kebutuhan Sekolah', 'Hiburan', 'Gaji', 'Bonus', 'Lainnya'.
            5. Kembalikan HANYA format JSON valid murni tanpa format markdown seperti ```json:
            {
              "merchant": "Nama Toko/Merchant/Penerima",
              "type": "EXPENSE atau INCOME",
              "wallet": "Tunai / E-Wallet / Rekening 1",
              "total": 50000,
              "items": [
                {
                  "name": "Nama barang/jasa",
                  "price": 15000,
                  "category": "Makanan"
                }
              ]
            }
        """.trimIndent()

        // Construct OpenRouter Chat Completions Request Payload
        val contentArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", imageUrl)
                })
            })
        }

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            })
        }

        val requestJson = JSONObject().apply {
            put("model", modelName.ifBlank { "google/gemini-2.0-flash-001" })
            put("messages", messagesArray)
            put("temperature", 0.1)
        }

        val requestBody = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .addHeader("HTTP-Referer", "https://moneyflow.aistudio.app")
            .addHeader("X-Title", "MoneyFlow App")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            val errorMsg = try {
                val errObj = JSONObject(responseBody)
                errObj.optJSONObject("error")?.optString("message") ?: "Kode status: ${response.code}"
            } catch (e: Exception) {
                "HTTP ${response.code}: $responseBody"
            }
            throw RuntimeException("Gagal menghubungi OpenRouter: $errorMsg")
        }

        // Parse JSON response
        parseOpenRouterAiResponse(responseBody)
    }

    private fun parseOpenRouterAiResponse(responseBody: String): ScannedReceiptData {
        val root = JSONObject(responseBody)
        val choices = root.getJSONArray("choices")
        if (choices.length() == 0) {
            throw RuntimeException("OpenRouter mengembalikan respon kosong")
        }

        val message = choices.getJSONObject(0).getJSONObject("message")
        var content = message.getString("content").trim()

        // Clean any markdown backticks if AI returns ```json ... ```
        if (content.startsWith("```")) {
            val firstLineBreak = content.indexOf('\n')
            if (firstLineBreak != -1) {
                content = content.substring(firstLineBreak + 1)
            }
            if (content.endsWith("```")) {
                content = content.substring(0, content.length - 3)
            }
            content = content.trim()
        }

        val parsedJson = JSONObject(content)
        val merchant = parsedJson.optString("merchant", "Struk Transaksi AI").ifBlank { "Struk Transaksi" }
        val type = parsedJson.optString("type", "EXPENSE").uppercase()
        val finalType = if (type == "INCOME") "INCOME" else "EXPENSE"
        val wallet = parsedJson.optString("wallet", "Tunai").ifBlank { "Tunai" }
        val grandTotal = parsedJson.optDouble("total", 0.0)

        val itemsList = mutableListOf<ScannedItem>()
        val itemsArray = parsedJson.optJSONArray("items")

        if (itemsArray != null && itemsArray.length() > 0) {
            for (i in 0 until itemsArray.length()) {
                val itemObj = itemsArray.getJSONObject(i)
                val name = itemObj.optString("name", "Item ${i + 1}").ifBlank { "Item ${i + 1}" }
                val price = itemObj.optDouble("price", 0.0)
                val cat = itemObj.optString("category", if (finalType == "INCOME") "Gaji" else "Belanja(Shopping)")

                itemsList.add(
                    ScannedItem(
                        name = name,
                        price = price,
                        category = cat,
                        type = finalType,
                        isSelected = true
                    )
                )
            }
        }

        // Fallback if no items inside items array
        if (itemsList.isEmpty()) {
            itemsList.add(
                ScannedItem(
                    name = merchant,
                    price = grandTotal,
                    category = if (finalType == "INCOME") "Gaji" else "Belanja(Shopping)",
                    type = finalType,
                    isSelected = true
                )
            )
        }

        val finalTotal = if (grandTotal > 0.0) grandTotal else itemsList.sumOf { it.price }

        return ScannedReceiptData(
            merchantName = merchant,
            items = itemsList,
            totalAmount = finalTotal,
            type = finalType,
            defaultCategory = itemsList.firstOrNull()?.category ?: "Belanja(Shopping)",
            walletAccount = wallet,
            rawText = content
        )
    }
}
