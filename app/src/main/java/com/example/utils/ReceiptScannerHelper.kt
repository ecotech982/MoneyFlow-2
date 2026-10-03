package com.example.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

data class ScannedReceiptData(
    val amount: Double,
    val type: String, // "INCOME" or "EXPENSE"
    val category: String,
    val walletAccount: String,
    val note: String,
    val rawText: String
)

object ReceiptScannerHelper {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Converts a Uri into a Bitmap safely
     */
    fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Scans receipt bitmap asynchronously using on-device ML Kit text recognition
     */
    suspend fun scanReceipt(bitmap: Bitmap): ScannedReceiptData {
        return suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val parsed = parseReceiptText(visionText.text)
                    continuation.resume(parsed)
                }
                .addOnFailureListener {
                    // Fallback default if recognition fails
                    continuation.resume(
                        ScannedReceiptData(
                            amount = 0.0,
                            type = "EXPENSE",
                            category = "Belanja",
                            walletAccount = "Tunai",
                            note = "Struk Pembayaran",
                            rawText = ""
                        )
                    )
                }
        }
    }

    /**
     * Intelligent heuristics to parse Indonesian receipts, transfer receipts, and bills
     */
    fun parseReceiptText(text: String): ScannedReceiptData {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val lowerText = text.lowercase(Locale.ROOT)

        // 1. Detect Type (Income vs Expense)
        val isIncome = lowerText.contains("transfer masuk") ||
                lowerText.contains("dana masuk") ||
                lowerText.contains("terima uang") ||
                lowerText.contains("uang masuk") ||
                lowerText.contains("pemasukan") ||
                lowerText.contains("gaji") ||
                lowerText.contains("payroll") ||
                lowerText.contains("cr ") ||
                lowerText.contains("(cr)") ||
                lowerText.contains("kredit") ||
                lowerText.contains("top up berhasil") && lowerText.contains("ke akun anda")

        val type = if (isIncome) "INCOME" else "EXPENSE"

        // 2. Detect Wallet / Account
        val wallet = when {
            lowerText.contains("bca") || lowerText.contains("klikbca") || lowerText.contains("mybca") -> "Rekening 1"
            lowerText.contains("bri") || lowerText.contains("brimo") -> "Rekening 2"
            lowerText.contains("danamon") || lowerText.contains("d-bank") -> "Rekening 3"
            lowerText.contains("gopay") || lowerText.contains("ovo") ||
                    lowerText.contains("dana") || lowerText.contains("shopeepay") ||
                    lowerText.contains("qris") || lowerText.contains("linkaja") -> "E-Wallet"
            lowerText.contains("cash") || lowerText.contains("tunai") -> "Tunai"
            else -> if (lowerText.contains("debit") || lowerText.contains("transfer")) "Rekening 1" else "Tunai"
        }

        // 3. Detect Category
        val category = when {
            // Makanan & Minuman
            lowerText.contains("resto") || lowerText.contains("cafe") || lowerText.contains("kopi") ||
                    lowerText.contains("coffee") || lowerText.contains("food") || lowerText.contains("makan") ||
                    lowerText.contains("minum") || lowerText.contains("bakso") || lowerText.contains("ayam") ||
                    lowerText.contains("burger") || lowerText.contains("pizza") || lowerText.contains("padang") ||
                    lowerText.contains("warung") || lowerText.contains("tea") || lowerText.contains("mie") ||
                    lowerText.contains("kitchen") || lowerText.contains("bakery") || lowerText.contains("roti") -> "Makanan"

            // Belanja / Shopping
            lowerText.contains("indomaret") || lowerText.contains("alfamart") || lowerText.contains("supermarket") ||
                    lowerText.contains("hypermart") || lowerText.contains("minimarket") || lowerText.contains("mart") ||
                    lowerText.contains("belanja") || lowerText.contains("toko") || lowerText.contains("shopee") ||
                    lowerText.contains("tokopedia") || lowerText.contains("fashion") || lowerText.contains("mall") -> "Belanja"

            // Transportasi
            lowerText.contains("gojek") || lowerText.contains("grab") || lowerText.contains("maxim") ||
                    lowerText.contains("goride") || lowerText.contains("gocar") || lowerText.contains("spbu") ||
                    lowerText.contains("pertamina") || lowerText.contains("shell") || lowerText.contains("bensin") ||
                    lowerText.contains("parkir") || lowerText.contains("tol") || lowerText.contains("tiket") -> "Transportasi"

            // Tagihan & Utilitas
            lowerText.contains("pln") || lowerText.contains("listrik") || lowerText.contains("pdam") ||
                    lowerText.contains("air") || lowerText.contains("wifi") || lowerText.contains("indihome") ||
                    lowerText.contains("tagihan") || lowerText.contains("bpjs") || lowerText.contains("pulsa") ||
                    lowerText.contains("kuota") || lowerText.contains("telkomsel") || lowerText.contains("indosat") -> "Tagihan Rutin"

            // Kesehatan
            lowerText.contains("apotek") || lowerText.contains("obat") || lowerText.contains("farmasi") ||
                    lowerText.contains("klinik") || lowerText.contains("dokter") || lowerText.contains("rs") ||
                    lowerText.contains("hospital") || lowerText.contains("medika") -> "Kesehatan"

            // Hiburan
            lowerText.contains("bioskop") || lowerText.contains("cinema") || lowerText.contains("xxi") ||
                    lowerText.contains("netflix") || lowerText.contains("spotify") || lowerText.contains("game") ||
                    lowerText.contains("steam") || lowerText.contains("wisata") -> "Hiburan"

            // Gaji / Income
            isIncome -> "Gaji"

            else -> if (type == "INCOME") "Lainnya" else "Belanja"
        }

        // 4. Extract Amount (Nominal)
        val extractedAmount = extractAmountFromText(lines)

        // 5. Extract Note / Merchant Name
        val merchantNote = extractMerchantNote(lines, category)

        return ScannedReceiptData(
            amount = extractedAmount,
            type = type,
            category = category,
            walletAccount = wallet,
            note = merchantNote,
            rawText = text
        )
    }

    /**
     * Extracts total amount by scanning lines with keyword priorities, or currency patterns
     */
    private fun extractAmountFromText(lines: List<String>): Double {
        val totalKeywords = listOf(
            "total bayar", "grand total", "total tagihan", "total belanja",
            "total", "jumlah transfer", "jumlah", "sebesar", "nominal", "subtotal", "net"
        )

        // Priority 1: Check lines containing explicit total keywords
        for (keyword in totalKeywords) {
            for (line in lines) {
                val lower = line.lowercase(Locale.ROOT)
                if (lower.contains(keyword)) {
                    val numbers = extractNumbersFromLine(line)
                    if (numbers.isNotEmpty()) {
                        val maxNum = numbers.maxOrNull() ?: 0.0
                        if (maxNum > 100.0) return maxNum
                    }
                }
            }
        }

        // Priority 2: Check lines with Rp or IDR
        val currencyAmounts = mutableListOf<Double>()
        for (line in lines) {
            val lower = line.lowercase(Locale.ROOT)
            if (lower.contains("rp") || lower.contains("idr")) {
                val nums = extractNumbersFromLine(line)
                currencyAmounts.addAll(nums)
            }
        }

        if (currencyAmounts.isNotEmpty()) {
            val valid = currencyAmounts.filter { it >= 500.0 }
            if (valid.isNotEmpty()) {
                return valid.maxOrNull() ?: valid.first()
            }
        }

        // Priority 3: Fallback to largest numeric value found in document
        val allNumbers = mutableListOf<Double>()
        for (line in lines) {
            allNumbers.addAll(extractNumbersFromLine(line))
        }

        val plausibleAmounts = allNumbers.filter { it in 500.0..500_000_000.0 }
        return plausibleAmounts.maxOrNull() ?: 0.0
    }

    private fun extractNumbersFromLine(line: String): List<Double> {
        val result = mutableListOf<Double>()
        // Match numbers like 50.000, 150,000, 150000.00, 25000
        val regex = Regex("""(?:Rp\.?|IDR)?\s*([0-9]{1,3}(?:[.,][0-9]{3})*(?:[.,][0-9]{2})?|[0-9]+)""")
        val matches = regex.findAll(line)
        for (match in matches) {
            val raw = match.groupValues[1]
            // Standardize format: remove thousands separators
            val clean = cleanNumberString(raw)
            clean.toDoubleOrNull()?.let {
                if (it > 0) result.add(it)
            }
        }
        return result
    }

    private fun cleanNumberString(raw: String): String {
        var str = raw.trim()
        if (str.contains(".") && str.contains(",")) {
            // Indonesian format 50.000,00 -> 50000.00
            val lastDot = str.lastIndexOf('.')
            val lastComma = str.lastIndexOf(',')
            if (lastComma > lastDot) {
                str = str.replace(".", "").replace(",", ".")
            } else {
                str = str.replace(",", "")
            }
        } else if (str.contains(".")) {
            val parts = str.split(".")
            if (parts.size > 1 && parts.last().length == 3) {
                // e.g. 50.000 or 1.500.000 -> thousands separator
                str = str.replace(".", "")
            } else if (parts.size == 2 && parts.last().length <= 2) {
                // decimal dot e.g. 50.50
            } else {
                str = str.replace(".", "")
            }
        } else if (str.contains(",")) {
            val parts = str.split(",")
            if (parts.size > 1 && parts.last().length == 3) {
                str = str.replace(",", "")
            } else if (parts.size == 2 && parts.last().length <= 2) {
                str = str.replace(",", ".")
            } else {
                str = str.replace(",", "")
            }
        }
        return str
    }

    /**
     * Extracts merchant/store name or header note from first non-trivial line
     */
    private fun extractMerchantNote(lines: List<String>, defaultCategory: String): String {
        for (line in lines.take(5)) {
            val clean = line.replace(Regex("[^a-zA-Z0-9\\s&.-]"), "").trim()
            if (clean.length in 3..40 &&
                !clean.contains("selamat datang", ignoreCase = true) &&
                !clean.contains("struk", ignoreCase = true) &&
                !clean.contains("tanggal", ignoreCase = true) &&
                !clean.contains("waktu", ignoreCase = true) &&
                !clean.contains("receipt", ignoreCase = true) &&
                !clean.contains("invoice", ignoreCase = true)
            ) {
                return clean
            }
        }
        return "Transaksi $defaultCategory"
    }
}
