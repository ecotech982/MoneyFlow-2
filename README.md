# MoneyFlow - Aplikasi Pengelolaan Keuangan Pribadi Android

[![Build Debug APK](https://github.com/ecotech982/MoneyFlow-2/actions/workflows/build-apk.yml/badge.svg)](https://github.com/ecotech982/MoneyFlow-2/actions/workflows/build-apk.yml)

Aplikasi manajemen keuangan pribadi modern untuk Android yang dibangun menggunakan **Kotlin**, **Jetpack Compose (Material 3)**, dan **Room Database** (penyimpanan lokal cepat dan aman). Dilengkapi dengan analitik anggaran (Aturan 50/30/20), multi-dompet/rekening bank, ekspor laporan PDF/CSV, serta pencatatan utang, piutang, dan aset.

---

## 📥 Unduh APK Langsung

Setelah repository di-push ke GitHub, GitHub Actions akan otomatis mem-build APK debug terbaru:

- **Download APK Terbaru:** [app-debug.apk](https://github.com/ecotech982/MoneyFlow-2/releases/latest/download/app-debug.apk)
- **Halaman Releases:** [GitHub Releases `ecotech982/MoneyFlow-2`](https://github.com/ecotech982/MoneyFlow-2/releases)
- **File APK Lokal di Proyek:** Folder `APK DOWNLOAD/app-debug.apk`

---

## ✨ Fitur Utama

1. **Dashboard & Analitik Interaktif:**
   - Ringkasan Total Saldo Bersih, Pemasukan, dan Pengeluaran.
   - Pembagian anggaran menurut **Aturan Keuangan 50/30/20** (Kebutuhan 50%, Keinginan 30%, Tabungan/Investasi 20%) dengan tampilan kompak dan bar progres rapi.
   - Rekap Saldo Akun: Tunai, E-Wallet, Bank BCA, Bank BRI, Bank Danamon, dll.
   - Portofolio Keuangan: Catatan Hutang Saya, Piutang Orang, dan Aset Emas.

2. **Pencatatan Transaksi:**
   - Input cepat nominal Rupiah, kategori pengeluaran/pemasukan, akun dompet/bank asal, dan catatan transaksi.
   - Manajemen riwayat transaksi dengan filter (Harian, Mingguan, Bulanan, Semua) dan fitur pencarian (*search*).

3. **Laporan & Ekspor Data:**
   - Ekspor transaksi ke format **PDF** dan **CSV (.csv)** untuk pembukuan atau pencetakan.
   - Fitur bagikan (*share*) langsung ke aplikasi lain (WhatsApp, Drive, Email).

4. **Keamanan & Personalisasi:**
   - Data tersimpan secara lokal dan privat di perangkat menggunakan SQLite / Room Database.
   - Mode Gelap (Dark Mode), Pengingat Harian, dan Fitur Profil Akun.

---

## 🛠️ Arsitektur & Teknologi

- **Bahasa:** Kotlin
- **UI Framework:** Jetpack Compose (Material Design 3)
- **Penyimpanan Data:** Room Database (SQLite) + SharedPreferences
- **Arsitektur:** MVVM (Model-View-ViewModel) + StateFlow & Coroutines
- **CI/CD:** GitHub Actions (`.github/workflows/build-apk.yml`)

---

## 🚀 Cara Push ke GitHub (`ecotech982/MoneyFlow-2`)

1. Pada Google AI Studio, buka menu **Settings** / **Export** di pojok kanan atas.
2. Pilih opsi **Push to GitHub** atau hubungkan akun GitHub Anda.
3. Arahkan ke repository: `ecotech982/MoneyFlow-2` pada branch `main`.
4. Setiap push akan otomatis memicu GitHub Actions untuk mem-build dan merilis file `app-debug.apk`.
