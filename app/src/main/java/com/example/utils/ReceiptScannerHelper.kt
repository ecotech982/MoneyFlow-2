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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

data class ScannedItem(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var price: Double,
    var category: String,
    var isSelected: Boolean = true,
    var type: String = "EXPENSE" // "EXPENSE" or "INCOME"
)

data class ScannedReceiptData(
    val merchantName: String,
    val items: List<ScannedItem>,
    val totalAmount: Double,
    val type: String, // "INCOME" or "EXPENSE"
    val defaultCategory: String,
    val walletAccount: String,
    val rawText: String,
    val date: Long = System.currentTimeMillis()
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
     * Scans multiple receipt images asynchronously
     */
    suspend fun scanMultipleReceipts(context: Context, uris: List<Uri>): List<ScannedReceiptData> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ScannedReceiptData>()
        for (uri in uris) {
            val bitmap = loadBitmapFromUri(context, uri)
            if (bitmap != null) {
                val data = scanReceipt(bitmap)
                results.add(data)
            }
        }
        results
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
                            merchantName = "Struk Pembayaran",
                            items = listOf(
                                ScannedItem(
                                    name = "Transaksi Struk",
                                    price = 0.0,
                                    category = "Belanja",
                                    type = "EXPENSE"
                                )
                            ),
                            totalAmount = 0.0,
                            type = "EXPENSE",
                            defaultCategory = "Belanja",
                            walletAccount = "Tunai",
                            rawText = ""
                        )
                    )
                }
        }
    }

    /**
     * Intelligent heuristics to parse Indonesian receipts, transfer receipts, and bills
     * with high-precision line-item detection
     */
    fun parseReceiptText(text: String): ScannedReceiptData {
        val rawLines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
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

        val globalType = if (isIncome) "INCOME" else "EXPENSE"

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

        // 3. Extract Merchant / Store Name
        val merchantName = extractMerchantName(rawLines)

        // 4. Extract Items Line-by-Line with Exact Price & Name
        val detectedItems = extractDetailedItems(rawLines, globalType, merchantName)

        // 5. Calculate Total Amount
        val calculatedItemsSum = detectedItems.sumOf { it.price }
        val grandTotalFromText = extractGrandTotal(rawLines)
        val finalTotal = if (grandTotalFromText > 0.0) grandTotalFromText else calculatedItemsSum

        // 6. Default category
        val defaultCategory = if (detectedItems.isNotEmpty()) detectedItems.first().category else if (isIncome) "Gaji" else "Belanja"

        return ScannedReceiptData(
            merchantName = merchantName,
            items = detectedItems,
            totalAmount = finalTotal,
            type = globalType,
            defaultCategory = defaultCategory,
            walletAccount = wallet,
            rawText = text
        )
    }

    /**
     * Extracts individual line items (Nama Barang & Harga) precisely from receipt text
     */
    private fun extractDetailedItems(lines: List<String>, globalType: String, merchant: String): List<ScannedItem> {
        val items = mutableListOf<ScannedItem>()

        val summaryKeywords = listOf(
            "total", "subtotal", "grand total", "total bayar", "total tagihan", "total belanja",
            "tunai", "cash", "kembali", "kembalian", "change", "diskon", "discount", "ppn", "pajak", "tax",
            "pembulatan", "card", "debit", "kredit", "qris", "terbayar", "sisa saldo"
        )

        // Identify where the item list starts and ends
        var inItemList = false
        var passedSummary = false

        for (i in lines.indices) {
            val line = lines[i]
            val lower = line.lowercase(Locale.ROOT)

            // Stop when reaching total / payment summary lines
            if (summaryKeywords.any { lower.startsWith(it) || lower == it || lower.contains("total bayar") || lower.contains("grand total") }) {
                // If it's an admin fee line (Biaya Admin: 2.500), keep it!
                if (lower.contains("biaya admin") || lower.contains("admin")) {
                    val feeAmount = extractSinglePrice(line)
                    if (feeAmount > 0) {
                        items.add(
                            ScannedItem(
                                name = "Biaya Admin Transaksi",
                                price = feeAmount,
                                category = "Tagihan Rutin",
                                type = globalType
                            )
                        )
                    }
                }
                passedSummary = true
                continue
            }

            // Skip store header noise
            if (!inItemList) {
                if (isHeaderNoise(line)) {
                    continue
                } else {
                    inItemList = true
                }
            }

            if (passedSummary) {
                // Lines after summary (e.g. "Terima Kasih", barcode, etc.) are skipped
                continue
            }

            // Pattern 1: Same line contains item name AND price
            // e.g., "INDOMIE GORENG 3.500" or "KOPI SUSU GULA AREN Rp 18.000"
            val itemWithPrice = parseItemAndPriceFromLine(line, globalType)
            if (itemWithPrice != null) {
                items.add(itemWithPrice)
                continue
            }

            // Pattern 2: Line is item name, and next line has price
            // e.g., Line 1: "SARI ROTI COKLAT"
            //       Line 2: "1 x 12.000" or "12.000"
            if (i + 1 < lines.size) {
                val nextLine = lines[i + 1]
                val nextPrice = extractSinglePrice(nextLine)
                if (nextPrice > 100.0 && isValidItemName(line)) {
                    val cleanName = cleanItemName(line)
                    val cat = detectItemCategory(cleanName, globalType)
                    items.add(
                        ScannedItem(
                            name = cleanName,
                            price = nextPrice,
                            category = cat,
                            type = globalType
                        )
                    )
                }
            }
        }

        // If no multi-items were extracted (e.g. single transfer or bill)
        if (items.isEmpty()) {
            val total = extractGrandTotal(lines)
            val cleanNote = if (merchant.isNotBlank()) merchant else "Transaksi Pembayaran"
            val cat = detectItemCategory(cleanNote, globalType)
            items.add(
                ScannedItem(
                    name = cleanNote,
                    price = total,
                    category = cat,
                    type = globalType
                )
            )
        }

        return items
    }

    /**
     * Checks if line has both item name and a price at the end
     */
    private fun parseItemAndPriceFromLine(line: String, globalType: String): ScannedItem? {
        // Regex for price at end of line: e.g. " 15.000", " Rp15.000", " 15000"
        val regex = Regex("""^(.*?)\s+(?:Rp\.?|IDR)?\s*([0-9]{1,3}(?:[.,][0-9]{3})+(?:[.,][0-9]{2})?|[0-9]{4,9})$""", RegexOption.IGNORE_CASE)
        val match = regex.find(line.trim())
        if (match != null) {
            val rawName = match.groupValues[1].trim()
            val rawPrice = match.groupValues[2].trim()

            if (isValidItemName(rawName)) {
                val price = cleanNumberString(rawPrice).toDoubleOrNull() ?: 0.0
                if (price in 500.0..500_000_000.0) {
                    val cleanName = cleanItemName(rawName)
                    val category = detectItemCategory(cleanName, globalType)
                    return ScannedItem(
                        name = cleanName,
                        price = price,
                        category = category,
                        type = globalType
                    )
                }
            }
        }
        return null
    }

    private fun extractSinglePrice(line: String): Double {
        val numbers = extractNumbersFromLine(line)
        return numbers.filter { it in 500.0..500_000_000.0 }.maxOrNull() ?: 0.0
    }

    private fun isValidItemName(name: String): Boolean {
        val clean = name.replace(Regex("[^a-zA-Z0-9]"), "").trim()
        if (clean.length < 3) return false
        val lower = name.lowercase(Locale.ROOT)
        val blacklist = listOf(
            "tanggal", "waktu", "jam", "date", "time", "kasir", "cashier",
            "pos", "struk", "receipt", "telp", "no.", "jl.", "jalan",
            "selamat datang", "terima kasih", "customer", "member",
            "subtotal", "total", "tunai", "kembali", "kembalian", "tax", "ppn"
        )
        return blacklist.none { lower.contains(it) }
    }

    private fun isHeaderNoise(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        return lower.contains("selamat datang") ||
                lower.contains("jl.") ||
                lower.contains("jalan") ||
                lower.contains("telp") ||
                lower.contains("npwp") ||
                lower.contains("kasir") ||
                lower.contains("pos ") ||
                lower.contains("receipt no") ||
                lower.contains("transaksi no") ||
                lower.contains("order id")
    }

    private fun cleanItemName(raw: String): String {
        var clean = raw.replace(Regex("""^\d+[\s\.\-]+"""), "") // Remove leading numbers like "1. ", "01 "
        clean = clean.replace(Regex("""\s+\d+\s*[xX]\s*.*$"""), "") // Remove trailing qty like " 2 x 10.000"
        clean = clean.replace(Regex("""\s+"""), " ").trim()
        return if (clean.length in 3..50) clean else raw.take(50)
    }

    /**
     * Auto-categorizes each specific item by its product name
     */
    fun detectItemCategory(name: String, type: String): String {
        val lower = name.lowercase(Locale.ROOT)

        if (type == "INCOME") {
            return when {
                lower.contains("gaji") || lower.contains("salary") || lower.contains("payroll") -> "Gaji"
                lower.contains("bonus") || lower.contains("insentif") -> "Bonus"
                lower.contains("dividen") || lower.contains("reksa") || lower.contains("saham") || lower.contains("emas") -> "Investasi"
                lower.contains("jual") || lower.contains("penjualan") || lower.contains("omzet") -> "Penjualan"
                else -> "Lainnya"
            }
        }

        return when {
            // Makanan
            lower.contains("nasi") || lower.contains("mie") || lower.contains("ayam") ||
                    lower.contains("bakso") || lower.contains("burger") || lower.contains("pizza") ||
                    lower.contains("roti") || lower.contains("soto") || lower.contains("padang") ||
                    lower.contains("food") || lower.contains("resto") || lower.contains("cafe") ||
                    lower.contains("warung") || lower.contains("snack") || lower.contains("snk") ||
                    lower.contains("biskuit") || lower.contains("indomie") || lower.contains("sedap") ||
                    lower.contains("chiki") || lower.contains("cokelat") || lower.contains("candy") -> "Makanan"

            // Minuman
            lower.contains("kopi") || lower.contains("coffee") || lower.contains("tea") ||
                    lower.contains("teh") || lower.contains("susu") || lower.contains("milk") ||
                    lower.contains("air") || lower.contains("mineral") || lower.contains("aqua") ||
                    lower.contains("le minerale") || lower.contains("jus") || lower.contains("juice") ||
                    lower.contains("boba") || lower.contains("drink") || lower.contains("latte") -> "Minuman"

            // Belanja / Kebutuhan Pokok
            lower.contains("beras") || lower.contains("minyak") || lower.contains("telur") ||
                    lower.contains("gula") || lower.contains("garam") || lower.contains("bumbu") ||
                    lower.contains("kecap") || lower.contains("sabun") || lower.contains("shampoo") ||
                    lower.contains("rinso") || lower.contains("detergen") || lower.contains("odol") ||
                    lower.contains("pasta gigi") || lower.contains("tissue") || lower.contains("indomaret") ||
                    lower.contains("alfamart") || lower.contains("supermarket") || lower.contains("shopping") ||
                    lower.contains("baju") || lower.contains("kaos") || lower.contains("celana") -> "Belanja(Shopping)"

            // Perawatan Diri
            lower.contains("skincare") || lower.contains("facial") || lower.contains("parfum") ||
                    lower.contains("cream") || lower.contains("serum") || lower.contains("lipstik") ||
                    lower.contains("salon") || lower.contains("barbershop") -> "Perawatan Diri"

            // Transportasi
            lower.contains("gojek") || lower.contains("grab") || lower.contains("maxim") ||
                    lower.contains("goride") || lower.contains("gocar") || lower.contains("spbu") ||
                    lower.contains("pertamina") || lower.contains("shell") || lower.contains("bensin") ||
                    lower.contains("pertalite") || lower.contains("pertamax") || lower.contains("solar") ||
                    lower.contains("parkir") || lower.contains("tol") || lower.contains("tiket") ||
                    lower.contains("kereta") || lower.contains("krl") || lower.contains("bus") -> "Transportasi"

            // Tagihan Rutin
            lower.contains("pln") || lower.contains("listrik") || lower.contains("token") ||
                    lower.contains("pdam") || lower.contains("air") || lower.contains("wifi") ||
                    lower.contains("indihome") || lower.contains("first media") || lower.contains("bpjs") ||
                    lower.contains("pbb") || lower.contains("iuran") || lower.contains("admin") -> "Tagihan Rutin"

            // Pulsa/Data
            lower.contains("pulsa") || lower.contains("kuota") || lower.contains("data") ||
                    lower.contains("telkomsel") || lower.contains("indosat") || lower.contains("xl") ||
                    lower.contains("tri") || lower.contains("smartfren") -> "Pulsa/Data"

            // Kesehatan
            lower.contains("apotek") || lower.contains("obat") || lower.contains("farmasi") ||
                    lower.contains("klinik") || lower.contains("dokter") || lower.contains("rs") ||
                    lower.contains("panadol") || lower.contains("bodrex") || lower.contains("vitamin") ||
                    lower.contains("paracetamol") || lower.contains("kimia farma") || lower.contains("k24") -> "Kesehatan"

            // Hiburan
            lower.contains("bioskop") || lower.contains("cinema") || lower.contains("xxi") ||
                    lower.contains("netflix") || lower.contains("spotify") || lower.contains("game") ||
                    lower.contains("steam") || lower.contains("wisata") || lower.contains("rekreasi") -> "Hiburan"

            // Kebutuhan Sekolah
            lower.contains("buku") || lower.contains("pulpen") || lower.contains("pensil") ||
                    lower.contains("spp") || lower.contains("sekolah") || lower.contains("kursus") ||
                    lower.contains("bimbel") || lower.contains("seragam") -> "Kebutuhan Sekolah"

            else -> "Lainnya"
        }
    }

    /**
     * Extracts total amount by scanning lines with keyword priorities, or currency patterns
     */
    fun extractGrandTotal(lines: List<String>): Double {
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
        val regex = Regex("""(?:Rp\.?|IDR)?\s*([0-9]{1,3}(?:[.,][0-9]{3})*(?:[.,][0-9]{2})?|[0-9]+)""")
        val matches = regex.findAll(line)
        for (match in matches) {
            val raw = match.groupValues[1]
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
    private fun extractMerchantName(lines: List<String>): String {
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
        return "Struk Pembelian"
    }
}
