package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.data.model.Transaction
import com.example.ui.theme.SoftGreenSuccess
import com.example.ui.theme.SoftRedDanger
import com.example.utils.FormatUtils
import com.example.utils.OpenRouterAiHelper
import com.example.utils.ReceiptScannerHelper
import com.example.utils.ScannedItem
import com.example.utils.ScannedReceiptData
import com.example.viewmodel.FinanceViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptScanDialog(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    financeViewModel: FinanceViewModel,
    onTransactionSaved: ((Transaction) -> Unit)? = null
) {
    if (!isOpen) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val openRouterApiKey by financeViewModel.openRouterApiKey.collectAsState()
    val openRouterModel by financeViewModel.openRouterModel.collectAsState()

    var showApiKeyDialog by remember { mutableStateOf(false) }

    var scannedBitmaps by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var isProcessing by remember { mutableStateOf(false) }
    var processingStatusText by remember { mutableStateOf("Menganalisis data...") }
    var wasAiUsed by remember { mutableStateOf(false) }

    // Multi-Item list extracted from receipt(s)
    val scannedItemList = remember { mutableStateListOf<ScannedItem>() }
    var merchantTitle by remember { mutableStateOf("Struk Belanja") }
    var globalType by remember { mutableStateOf("EXPENSE") } // "EXPENSE" or "INCOME"
    var selectedWallet by remember { mutableStateOf("Tunai") }

    // Mode: true = Rekap Per Item (Multi-Transaksi), false = Rekap 1 Transaksi (Total)
    var isSeparateItemsMode by remember { mutableStateOf(true) }

    val incomeCategories = listOf("Gaji", "Bonus", "Investasi", "Penjualan", "Lainnya")
    val expenseCategories = listOf(
        "Makanan", "Minuman", "Belanja(Shopping)", "Transportasi", "Tagihan Rutin",
        "Kesehatan", "Perawatan Diri", "Kebutuhan Sekolah", "Hiburan", "Lainnya"
    )
    val walletOptions = listOf("Tunai", "E-Wallet", "Rekening 1", "Rekening 2", "Rekening 3", "Lainnya")

    // Function to parse single or multiple images using AI or OCR fallback
    fun processBitmaps(bitmaps: List<Bitmap>) {
        scannedBitmaps = bitmaps
        isProcessing = true
        coroutineScope.launch {
            try {
                scannedItemList.clear()
                var detectedMerchant = "Struk Belanja"
                var detectedType = "EXPENSE"
                var detectedWallet = "Tunai"
                var usedAiSuccess = false

                for (bitmap in bitmaps) {
                    val result: ScannedReceiptData = if (openRouterApiKey.isNotBlank()) {
                        processingStatusText = "AI OpenRouter ($openRouterModel) sedang menganalisis struk presisi..."
                        try {
                            val aiResult = OpenRouterAiHelper.analyzeReceiptWithAi(
                                bitmap = bitmap,
                                apiKey = openRouterApiKey,
                                modelName = openRouterModel
                            )
                            usedAiSuccess = true
                            aiResult
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(
                                context,
                                "OpenRouter AI gagal (${e.localizedMessage ?: "error"}), beralih ke OCR lokal.",
                                Toast.LENGTH_LONG
                            ).show()
                            processingStatusText = "Membaca teks struk dengan OCR lokal..."
                            ReceiptScannerHelper.scanReceipt(bitmap)
                        }
                    } else {
                        processingStatusText = "Membaca teks struk dengan OCR lokal..."
                        ReceiptScannerHelper.scanReceipt(bitmap)
                    }

                    if (result.merchantName.isNotBlank() && result.merchantName != "Struk Pembelian") {
                        detectedMerchant = result.merchantName
                    }
                    detectedType = result.type
                    detectedWallet = result.walletAccount

                    result.items.forEach { item ->
                        scannedItemList.add(item)
                    }
                }

                wasAiUsed = usedAiSuccess
                merchantTitle = detectedMerchant
                globalType = detectedType
                selectedWallet = detectedWallet

                // Ensure at least one item
                if (scannedItemList.isEmpty()) {
                    scannedItemList.add(
                        ScannedItem(
                            name = "Transaksi Baru",
                            price = 0.0,
                            category = "Belanja(Shopping)",
                            type = globalType
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Gagal memindai gambar: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isProcessing = false
            }
        }
    }

    // Camera Launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            processBitmaps(listOf(bitmap))
        }
    }

    // Camera Permission Launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            cameraLauncher.launch(null)
        } else {
            Toast.makeText(context, "Izin kamera diperlukan untuk memotret struk", Toast.LENGTH_SHORT).show()
        }
    }

    // Multi-Image Gallery Launcher
    val multiGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val bitmaps = uris.mapNotNull { ReceiptScannerHelper.loadBitmapFromUri(context, it) }
            if (bitmaps.isNotEmpty()) {
                processBitmaps(bitmaps)
            } else {
                Toast.makeText(context, "Gagal memuat gambar dari galeri", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Dialog Input API Key OpenRouter
    if (showApiKeyDialog) {
        OpenRouterApiKeyModal(
            currentKey = openRouterApiKey,
            currentModel = openRouterModel,
            onDismiss = { showApiKeyDialog = false },
            onSave = { newKey, newModel ->
                financeViewModel.setOpenRouterApiKey(newKey)
                financeViewModel.setOpenRouterModel(newModel)
                showApiKeyDialog = false
                Toast.makeText(context, "API Key OpenRouter berhasil disimpan!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Pindai Struk dengan AI",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Analisis presisi bertenaga OpenRouter",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // OpenRouter Key Status Button
                        IconButton(
                            onClick = { showApiKeyDialog = true },
                            modifier = Modifier.testTag("btn_configure_openrouter_dialog")
                        ) {
                            Icon(
                                imageVector = if (openRouterApiKey.isNotBlank()) Icons.Default.VpnKey else Icons.Default.KeyOff,
                                contentDescription = "Konfigurasi OpenRouter",
                                tint = if (openRouterApiKey.isNotBlank()) SoftGreenSuccess else MaterialTheme.colorScheme.error
                            )
                        }

                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Tutup")
                        }
                    }
                }

                // AI Status Pill Banner
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (openRouterApiKey.isNotBlank()) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable { showApiKeyDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (openRouterApiKey.isNotBlank()) Icons.Default.AutoAwesome else Icons.Default.Key,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (openRouterApiKey.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (openRouterApiKey.isNotBlank()) "AI OpenRouter: Aktif (${openRouterModel.take(24)})" else "API Key OpenRouter Belum Diatur (Klik untuk atur)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (openRouterApiKey.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = if (openRouterApiKey.isNotBlank()) "Ubah" else "Pasang",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )

                if (scannedBitmaps.isEmpty()) {
                    // Initial State: Choose Camera vs Gallery
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                            contentDescription = null,
                            modifier = Modifier.size(68.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Ambil Struk atau Bukti Transaksi",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "AI akan membaca harga, tipe transaksi (pemasukan/pengeluaran), kategori, dan rincian catatan setiap item secara presisi dan akurat.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Camera Button
                        Button(
                            onClick = {
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPermission) {
                                    cameraLauncher.launch(null)
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("btn_scan_camera"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Buka Kamera (Foto Struk)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Multi-Image Gallery Button
                        OutlinedButton(
                            onClick = {
                                multiGalleryLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("btn_scan_gallery"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pilih Gambar dari Galeri (Bisa Lebih dari Satu)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                } else if (isProcessing) {
                    // Processing / AI analysis state
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(44.dp)
                            )
                            Text(
                                text = processingStatusText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                    }
                } else {
                    // Review & Edit Scanned Multi-Items
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Section 1: Thumbnail(s) & Merchant Header
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Image previews
                                            Row(
                                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                scannedBitmaps.forEach { b ->
                                                    Image(
                                                        bitmap = b.asImageBitmap(),
                                                        contentDescription = "Preview Struk",
                                                        modifier = Modifier
                                                            .size(48.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                                                        contentScale = ContentScale.Crop
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (wasAiUsed) {
                                                        Icon(
                                                            Icons.Default.AutoAwesome,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(14.dp),
                                                            tint = SoftGreenSuccess
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                    }
                                                    Text(
                                                        text = if (wasAiUsed) "Analisis AI OpenRouter" else "Ekstraksi OCR",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = SoftGreenSuccess
                                                    )
                                                }
                                                Text(
                                                    text = "${scannedItemList.size} Item Terdeteksi Presisi",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        // Reset/Retake button
                                        IconButton(onClick = {
                                            scannedBitmaps = emptyList()
                                            scannedItemList.clear()
                                        }) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Pindai Ulang", tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    // Merchant / Note Input
                                    OutlinedTextField(
                                        value = merchantTitle,
                                        onValueChange = { merchantTitle = it },
                                        label = { Text("Nama Toko / Tempat / Keterangan Struk") },
                                        leadingIcon = { Icon(Icons.Default.Storefront, contentDescription = null) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                }
                            }
                        }

                        // Section 2: Global Configuration (Tipe Transaksi)
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                    .padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Button(
                                    onClick = {
                                        globalType = "EXPENSE"
                                        scannedItemList.forEach { it.type = "EXPENSE" }
                                    },
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (globalType == "EXPENSE") SoftRedDanger else Color.Transparent,
                                        contentColor = if (globalType == "EXPENSE") Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Pengeluaran", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        globalType = "INCOME"
                                        scannedItemList.forEach { it.type = "INCOME" }
                                    },
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (globalType == "INCOME") SoftGreenSuccess else Color.Transparent,
                                        contentColor = if (globalType == "INCOME") Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Pemasukan", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Section 3: Mode Rekap Selector
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (isSeparateItemsMode) "Mode: Rekap Tiap Item (Rinci)" else "Mode: Rekap Total Struk",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = if (isSeparateItemsMode) "Tiap barang masuk ke riwayat secara terpisah" else "Semua barang digabung jadi 1 transaksi total",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = isSeparateItemsMode,
                                        onCheckedChange = { isSeparateItemsMode = it }
                                    )
                                }
                            }
                        }

                        // Section 4: Dompet / Sumber Dana
                        item {
                            Column {
                                Text(
                                    text = "Sumber Dana / Akun:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    walletOptions.forEach { w ->
                                        val isSel = selectedWallet == w
                                        FilterChip(
                                            selected = isSel,
                                            onClick = { selectedWallet = w },
                                            label = { Text(w, fontSize = 11.sp) },
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Section 5: List of Items (Header)
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Rincian Item (${scannedItemList.count { it.isSelected }} terpilih):",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                TextButton(
                                    onClick = {
                                        scannedItemList.add(
                                            ScannedItem(
                                                name = "Item Baru",
                                                price = 0.0,
                                                category = if (globalType == "INCOME") "Gaji" else "Makanan",
                                                type = globalType
                                            )
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("+ Tambah Item", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Items list with editable names, prices, categories, and checkboxes
                        itemsIndexed(scannedItemList, key = { _, item -> item.id }) { index, item ->
                            ScannedItemRow(
                                item = item,
                                globalType = globalType,
                                expenseCategories = expenseCategories,
                                incomeCategories = incomeCategories,
                                onUpdate = { updated ->
                                    scannedItemList[index] = updated
                                },
                                onDelete = {
                                    if (scannedItemList.size > 1) {
                                        scannedItemList.removeAt(index)
                                    } else {
                                        Toast.makeText(context, "Minimal harus ada 1 item", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }

                    // Bottom Summary & Rekap Action Bar
                    val selectedItems = scannedItemList.filter { it.isSelected }
                    val totalSelectedAmount = selectedItems.sumOf { it.price }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Total (${selectedItems.size} Item Terpilih):",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = FormatUtils.formatRupiah(totalSelectedAmount),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (globalType == "INCOME") SoftGreenSuccess else MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Button(
                                onClick = {
                                    if (selectedItems.isEmpty()) {
                                        Toast.makeText(context, "Pilih minimal 1 item untuk direkap", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }

                                    if (isSeparateItemsMode) {
                                        // Mode 1: Rekap setiap item sebagai transaksi terpisah
                                        val transactions = selectedItems.map { itm ->
                                            Transaction(
                                                type = itm.type,
                                                category = itm.category,
                                                amount = itm.price,
                                                date = System.currentTimeMillis(),
                                                note = "${itm.name} ($merchantTitle)",
                                                walletAccount = selectedWallet
                                            )
                                        }
                                        financeViewModel.addMultipleTransactions(transactions)
                                        transactions.forEach { onTransactionSaved?.invoke(it) }
                                        Toast.makeText(
                                            context,
                                            "Berhasil merekap ${transactions.size} item ke Riwayat!",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        // Mode 2: Rekap sebagai 1 total transaksi
                                        val itemsSummary = selectedItems.joinToString(", ") { "${it.name} (${FormatUtils.formatRupiah(it.price)})" }
                                        val singleTransaction = Transaction(
                                            type = globalType,
                                            category = selectedItems.firstOrNull()?.category ?: "Belanja(Shopping)",
                                            amount = totalSelectedAmount,
                                            date = System.currentTimeMillis(),
                                            note = "$merchantTitle: $itemsSummary",
                                            walletAccount = selectedWallet
                                        )
                                        financeViewModel.addTransaction(singleTransaction)
                                        onTransactionSaved?.invoke(singleTransaction)
                                        Toast.makeText(
                                            context,
                                            "Berhasil merekap total ${FormatUtils.formatRupiah(totalSelectedAmount)} ke Riwayat!",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("btn_save_scanned_transaction"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isSeparateItemsMode) "Rekap ${selectedItems.size} Transaksi ke Riwayat" else "Rekap Total ke Riwayat",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Modal to input or update OpenRouter API Key and Model manually
 */
@Composable
fun OpenRouterApiKeyModal(
    currentKey: String,
    currentModel: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var keyInput by remember { mutableStateOf(currentKey) }
    var modelInput by remember { mutableStateOf(currentModel.ifBlank { "google/gemini-2.0-flash-001" }) }
    var isKeyVisible by remember { mutableStateOf(false) }

    val presetModels = listOf(
        "google/gemini-2.0-flash-001",
        "openai/gpt-4o-mini",
        "qwen/qwen-2.5-vl-72b-instruct:free",
        "meta-llama/llama-3.2-11b-vision-instruct:free"
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "API Key OpenRouter",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup")
                    }
                }

                Text(
                    text = "Masukkan API key OpenRouter Anda secara manual. AI akan menganalisis struk belanja dan foto transaksi secara presisi dan akurat.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // API Key TextField
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("OpenRouter API Key (sk-or-...)") },
                    singleLine = true,
                    visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                            Icon(
                                imageVector = if (isKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isKeyVisible) "Sembunyikan" else "Tampilkan"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                // Model Selection
                Column {
                    Text(
                        text = "Pilih / Ketik Model Vision AI:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presetModels.forEach { m ->
                            FilterChip(
                                selected = modelInput == m,
                                onClick = { modelInput = m },
                                label = { Text(m.substringAfter("/"), fontSize = 10.sp) },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Batal")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(keyInput, modelInput) },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Simpan API Key")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannedItemRow(
    item: ScannedItem,
    globalType: String,
    expenseCategories: List<String>,
    incomeCategories: List<String>,
    onUpdate: (ScannedItem) -> Unit,
    onDelete: () -> Unit
) {
    var isExpandedCategory by remember { mutableStateOf(false) }
    var priceStr by remember(item.price) { mutableStateOf(if (item.price > 0) item.price.toLong().toString() else "") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (item.isSelected) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f) else Color.Transparent
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Checkbox to include/exclude
                Checkbox(
                    checked = item.isSelected,
                    onCheckedChange = { isChecked ->
                        onUpdate(item.copy(isSelected = isChecked))
                    },
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Item Name (Precise)
                OutlinedTextField(
                    value = item.name,
                    onValueChange = { newName ->
                        onUpdate(item.copy(name = newName))
                    },
                    label = { Text("Nama Barang / Catatan", fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Delete button
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Hapus Item",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Price Input (Precise)
                OutlinedTextField(
                    value = priceStr,
                    onValueChange = { input ->
                        if (input.all { it.isDigit() }) {
                            priceStr = input
                            val num = input.toDoubleOrNull() ?: 0.0
                            onUpdate(item.copy(price = num))
                        }
                    },
                    label = { Text("Harga (Rp)", fontSize = 10.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.55f),
                    shape = RoundedCornerShape(8.dp)
                )

                // Category Selector Button
                Box(modifier = Modifier.weight(0.45f)) {
                    OutlinedButton(
                        onClick = { isExpandedCategory = true },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = item.category,
                            fontSize = 11.sp,
                            maxLines = 1,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    DropdownMenu(
                        expanded = isExpandedCategory,
                        onDismissRequest = { isExpandedCategory = false }
                    ) {
                        val categories = if (globalType == "INCOME") incomeCategories else expenseCategories
                        categories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat, fontSize = 12.sp) },
                                onClick = {
                                    onUpdate(item.copy(category = cat))
                                    isExpandedCategory = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
