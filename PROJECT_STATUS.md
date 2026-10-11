# Status Proyek Konversi Satuan

Terakhir diperbarui: 11 Oktober 2026

## Tujuan

Aplikasi Android offline-first untuk mengumpulkan pengukuran satuan lokal komoditas di pasar, memeriksa dan menyetujui faktor konversi, lalu menghitung jumlah serta harga dalam satuan baku.

Repositori: https://github.com/fajriantomanungki/konversi_satuan

## Teknologi

- Android native: Kotlin, Jetpack Compose, Room, Retrofit.
- Server: FastAPI, SQLite untuk uji coba single-instance, JWT untuk autentikasi.
- Build: GitHub Actions menghasilkan APK debug.

## Alur yang sudah tersedia

- Pengawas menyimpan draft pengukuran lokal dan dapat mengirimkannya ke API.
- Admin meninjau kiriman, menyetujui dan merata-ratakan rasio untuk kelompok komoditas, kualitas, kabupaten, serta pasangan satuan yang sama.
- Admin dapat menyinkronkan katalog lokal yang disetujui ke server.
- Petugas dapat menghitung jumlah satuan baku dan harga per satuan baku.
- Katalog dapat diekspor dan diimpor melalui file JSON agar dapat dipakai tanpa koneksi.
- Server memeriksa role dan kabupaten dari token, kemudian membatasi data sesuai wilayah akun.

## Status build

Build `assembleDebug` berhasil pada GitHub Actions, commit `82a0b87abb2240aa248e1ab47648b35239948e6e`.

APK tersedia sebagai artifact `konversi-satuan-debug-apk` di:
https://github.com/fajriantomanungki/konversi_satuan/actions/runs/38142748523

Sintaks server Python sudah diverifikasi. Uji end-to-end pada perangkat dan server terpasang belum dilakukan.

## Langkah berikutnya

1. Tambahkan layar login di Android agar pengguna tidak perlu menyalin token manual.
2. Pindahkan token dari preferensi biasa ke penyimpanan Android Keystore.
3. Tambahkan tes untuk penggabungan hasil pengukuran dan pembatasan kabupaten/role.
4. Uji alur lengkap pada beberapa akun/perangkat dan perbaiki temuan UI.
5. Deploy API dengan HTTPS, secret JWT kuat, volume database persisten, dan backup.
6. Pertimbangkan PostgreSQL jika server digunakan bersama secara luas.

## Catatan operasional

Pemilih role di aplikasi saat ini hanya mengganti tampilan lokal. Hak akses online ditegakkan oleh token server. Server belum dideploy dan memerlukan konfigurasi serta pembuatan admin awal mengikuti README.
