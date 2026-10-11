# Konversi Satuan

Aplikasi Android native Kotlin untuk mendata satuan lokal di pasar dan menghitung harga per satuan baku. Arsitekturnya offline-first: database Room menjadi sumber data lokal, sedangkan API dipakai untuk pertukaran data antar kabupaten/perangkat.

## Fitur

- **Pengawas:** mencatat komoditas, kualitas, nilai dan nama satuan lokal, nilai dan nama satuan baku, harga, dan kabupaten. Draft tersimpan di Room saat offline; saat terhubung, draft dikirim ke server.
- **Admin:** meninjau kiriman, menggabungkan catatan dengan komoditas, kualitas, dan kabupaten yang sama, merata-ratakan rasio konversi dan harga, lalu menyetujui. Server hanya mengizinkan admin untuk wilayah akun tersebut.
- **Petugas:** menghitung jumlah satuan baku dan harga per satuan baku dari katalog yang telah disetujui.
- **Transfer manual:** admin dapat mengekspor katalog yang disetujui sebagai JSON dan petugas mengimpornya melalui pemilih file Android. Setelah impor, kalkulator tidak memerlukan jaringan.
- **Sinkronisasi server:** pengawas mengunggah kiriman; admin mengunduh antrean dan mengesahkan; admin/petugas mengunduh katalog approved sesuai kabupaten akun.
- **Logo aplikasi:** monogram “KS” dengan warna navy/teal, dibuat langsung dari komponen UI.

## Rumus kalkulator

Misalnya hasil rata-rata menunjukkan `1 liter = 0,8 kg`, harga lokal `Rp15.000/liter`, dan petugas memasukkan `1 liter`:

- jumlah baku = jumlah lokal × nilai baku ÷ nilai lokal = `0,8 kg`
- harga per satuan baku = harga lokal ÷ (nilai baku ÷ nilai lokal) = `Rp18.750/kg`

Aplikasi menampilkan kedua hasil dan rasio yang dipakai.

## Menjalankan Android

1. Buka repositori ini di Android Studio (Jellyfish atau yang lebih baru) dengan JDK 17.
2. Tunggu Gradle Sync, lalu jalankan konfigurasi `app` pada emulator/perangkat Android 8.0+.
3. Atur peran perangkat dan kabupaten di tab **Sinkron**. Peran lokal mengatur tampilan alur; otorisasi server tetap memakai akun/token dengan role dan kabupaten dari server.
4. Pilih komoditas dari katalog yang disarankan saat pengawas mengetik nama. Untuk komoditas baru, isi nama dan satuan baku secara manual.

Build APK dari command line: `gradle assembleDebug`. APK debug akan berada di `app/build/outputs/apk/debug/app-debug.apk`.

## Menjalankan API

Python 3.11+ disarankan. Contoh lokal untuk pengujian:

```bash
cd server
python -m venv .venv
# aktifkan virtualenv, lalu:
pip install -r requirements.txt
export JWT_SECRET="gunakan-random-secret-minimal-32-karakter"
uvicorn main:app --host 0.0.0.0 --port 8000
```

Di terminal kedua, buat admin pertama:

```bash
cd server
export JWT_SECRET="gunakan-random-secret-minimal-32-karakter"
python bootstrap_admin.py
```

Admin membuat akun pengawas/petugas lewat `POST /v1/users` (Bearer admin) atau halaman dokumentasi interaktif `https://alamat-api/docs`. Login di `POST /v1/login` menghasilkan `accessToken`; salin token ke tab Sinkron di aplikasi. API hanya boleh dipakai lewat HTTPS saat deployment nyata. `JWT_SECRET` wajib diganti dan basis data `server/konversi.db` harus disimpan pada volume persisten. Dockerfile tersedia untuk membangun image server.

Endpoint utama:

| Endpoint | Akses | Kegunaan |
| --- | --- | --- |
| `POST /v1/login` | Publik | Memperoleh token |
| `POST /v1/users` | Admin | Membuat akun pada kabupaten admin |
| `POST /v1/submissions` | Pengawas | Mengirim draft dengan kabupaten akun |
| `GET /v1/pending` | Admin | Mengambil catatan yang menunggu review |
| `POST /v1/approve/{commodity}?quality=...&localUnit=...&standardUnit=...` | Admin | Rata-rata per komoditas, kualitas, dan pasangan satuan; menyetujui |
| `GET /v1/approved` | Semua role | Mengambil katalog approved sesuai kabupaten akun |
| `POST /v1/catalog/import` | Admin | Mengimpor file JSON katalog approved |

## Format transfer katalog

Aplikasi mengimpor/mengekspor berkas JSON dengan struktur berikut (field `schemaVersion` saat ini `1`):

```json
{
  "schemaVersion": 1,
  "exportedAt": 1760000000000,
  "conversions": [
    {
      "id": "uuid",
      "commodity": "Beras",
      "quality": "Medium",
      "localUnit": "liter",
      "localValue": 1,
      "standardUnit": "kg",
      "standardValue": 0.8,
      "localPrice": 15000,
      "kabupaten": "Palu",
      "approved": true,
      "contributors": 4,
      "updatedAt": 1760000000000
    }
  ]
}
```

Nilai `standardValue / localValue` adalah faktor konversi. Rata-rata dihitung atas faktor tiap catatan yang memakai komoditas, kualitas, dan pasangan satuan yang sama, bukan rata-rata nilai dengan penyebut berbeda atau satuan lokal berbeda.

## Catatan implementasi dan produksi

- Versi pertama menyediakan alur operasional dan API tanpa layanan cloud vendor tertentu. SQLite backend sesuai untuk uji coba single-instance; gunakan PostgreSQL dan backup rutin bila dipakai banyak kabupaten/perangkat.
- Kata sandi server di-hash PBKDF2; token JWT berumur 14 hari. Gunakan HTTPS, secret kuat dari environment, rotasi token, serta penyimpanan kata sandi/secret manager produksi sebelum peluncuran.
- Pemilih peran di aplikasi hanya untuk memilih tampilan. Otorisasi lintas pengguna dan pembatasan kabupaten yang aman dilakukan server berdasarkan token.
- Cache token saat ini disimpan di preferensi aplikasi. Untuk distribusi produksi, ganti dengan Android Keystore-backed secure storage dan tambahkan layar login/token yang dikelola.
- Master komoditas dapat dikirim sebagai katalog JSON melalui admin/API; pengawas bisa memilih saran katalog atau mencatat komoditas baru.
- Untuk build terverifikasi, jalankan Android Gradle build di Android Studio atau GitHub Actions yang memasang Android SDK. Lingkungan pembuatan saat ini tidak memiliki Gradle/Android SDK.
