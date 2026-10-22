# DroidSCP

Klien SFTP/SCP untuk Android yang terinspirasi alur kerja WinSCP (implementasi sendiri, bukan salinan kode).
Lihat [docs/ANALISIS.md](docs/ANALISIS.md) untuk analisis fitur, arsitektur, dan batasan.

## Build

Cara termudah: buka folder ini di Android Studio (Koala atau lebih baru), tunggu sinkronisasi Gradle
(wrapper dibuat otomatis), lalu jalankan konfigurasi `app` di perangkat/emulator (Android 8.0+).

Lewat terminal (JDK 17, Android SDK 34, Gradle 8.9+):

```bash
gradle assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
```

## Fitur v0.2

SFTP, SCP, FTP, FTPS · Site Manager terenkripsi · password / kunci privat · verifikasi fingerprint host (SSH) dan pin sertifikat TOFU (FTPS) · kunci privat terenkripsi di disk ·
jelajah, urut, cari, buat folder, ganti nama, hapus, chmod, potong-tempel · unggah/unduh berkas dan folder
dengan antrean, notifikasi, **resume**, dan dialog konflik (timpa / lewati / ganti nama / lanjutkan) ·
**sinkronisasi folder** dua arah dengan pratinjau · editor teks remote.

Tahap 3 (UX): **sambung ulang otomatis** saat koneksi putus (3 percobaan, tidak diulang bila kunci host berubah atau
login ditolak) · **properti** berkas · **bookmark folder** per situs · **buka dengan aplikasi lain** (diunduh ke cache,
hanya baca) · **manajer host tepercaya** (lihat dan lupakan fingerprint).

Tampilan lebih ramah: daftar situs berbentuk kartu dengan ringkasan keamanan, ikon berwarna per jenis berkas,
tanggal mudah dibaca ("Hari ini 14:30"), tombol **Tambah** (unggah berkas/folder, folder baru) menggantikan deretan ikon,
jalur folder dengan tombol Beranda, transfer dengan persentase, kondisi kosong yang menjelaskan langkah berikutnya,
pesan galat dalam bahasa awam beserta saran, dan form situs dengan petunjuk protokol serta alasan tombol Simpan nonaktif.

Tahap 4 (cakupan): **SCP tanpa SFTP** (penelusuran lewat perintah shell bila server tidak punya SFTP) · **FTPS implisit**
(sakelar pada situs, port 990) · **banyak sesi** sebagai tab, dengan pembatalan transfer per sesi.

## Catatan

Kode belum pernah dikompilasi di lingkungan pembuat (SDK Android tidak tersedia). Jalankan build pertama
dan laporkan galat kompilasi bila ada. Batasan dan rencana ada di [docs/ANALISIS.md](docs/ANALISIS.md).

## Tes

```bash
gradle :app:testDebugUnitTest      # tes JVM murni: path, salin/skip stream, perencana sinkronisasi, pin TLS, sambung ulang, parser/perintah shell SCP-only, judul tab
```

CI (`.github/workflows/android.yml`) menjalankan tes lalu membangun APK debug pada setiap push dan pull request.
