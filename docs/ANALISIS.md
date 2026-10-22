# Analisis WinSCP dan pemetaannya ke Android (DroidSCP)

DroidSCP adalah reimplementasi *clean-room* dari konsep WinSCP untuk Android. Tidak ada kode WinSCP
(GPL) yang disalin; hanya fungsi dan alur kerjanya yang dijadikan acuan. Nama "WinSCP" adalah merek
dagang pihak lain, sehingga aplikasi ini memakai nama sendiri.

## 1. Fitur inti WinSCP

| Area | Di WinSCP | Status di DroidSCP v0.1 |
|---|---|---|
| Protokol | SFTP, SCP, FTP/FTPS, WebDAV, S3 | **SFTP, SCP (juga tanpa SFTP), FTP, FTPS (TLS eksplisit dan implisit).** WebDAV dan S3 ditunda (lihat §4) |
| Site Manager | Profil koneksi tersimpan | Ada, disimpan terenkripsi (Android Keystore, AES-256-GCM) |
| Autentikasi | Password, kunci publik, keyboard-interactive, agent | Password, keyboard-interactive, kunci privat (+passphrase). Agent tidak berlaku di Android |
| Verifikasi host | Fingerprint, known_hosts | TOFU: tanya saat pertama, **blok & peringatan** jika fingerprint berubah |
| Penjelajah berkas | Panel kiri (lokal) dan kanan (remote) | Satu panel remote; sisi lokal memakai pemilih berkas/folder Android (SAF) |
| Operasi berkas | Buat folder, ganti nama, hapus, chmod, pindah | Semuanya ada; hapus folder rekursif; pindah lewat potong-tempel |
| Daftar berkas | Urut, filter, tampil berkas tersembunyi | Urut (nama/ukuran/tanggal), cari, tampil/sembunyikan berkas tersembunyi |
| Transfer | Antrean, latar belakang, resume, konfirmasi timpa | Antrean (2 paralel), foreground service, batalkan, ulangi, **resume** (SFTP/FTP/FTPS), dialog konflik per berkas (timpa/lewati/ganti nama/lanjutkan, "terapkan ke semua"), unggah dan unduh folder rekursif |
| Editor | Editor internal/eksternal | Editor teks internal (UTF-8, maks 512 KB) |
| Sinkronisasi folder | Ada | Ada: analisis → ringkasan → jalankan, dua arah pilihan, opsi hapus yang tidak ada di sumber |
| Skrip/otomasi (CLI, .NET) | Ada | Tidak dipetakan; bukan kebutuhan utama di perangkat seluler |
| Tab sesi ganda, tema, bahasa | Ada | Banyak sesi sekaligus (tab per koneksi); tema dinamis Material You |

## 2. Perbedaan platform yang memengaruhi desain

1. **Layar kecil, satu tangan.** Panel ganda tidak praktis. Diganti satu panel remote ditambah aksi
   "Unggah" (pemilih berkas) dan "Unduh" (ke folder pilihan).
2. **Scoped Storage.** Aplikasi tidak boleh membaca/menulis sembarang path. Seluruh sisi lokal memakai
   Storage Access Framework (`ContentResolver`, `DocumentFile`). Izin folder unduhan dipertahankan
   lewat `takePersistableUriPermission`.
3. **Proses dibunuh di latar belakang.** Transfer berjalan di *foreground service* bertipe `dataSync`
   dengan notifikasi, agar tidak dimatikan sistem saat layar mati.
4. **Penyimpanan rahasia.** Password dan passphrase tidak boleh jadi teks polos: dipakai
   `EncryptedSharedPreferences` (kunci di Android Keystore). Berkas kunci privat disimpan terenkripsi
   (`EncryptedFile`, AES-256-GCM-HKDF, berkas `.enc`) oleh `KeyVault`, dan dibaca langsung ke memori
   saat login, tanpa berkas teks polos di disk. Berkas kunci versi lama dimigrasikan otomatis.
5. **Jaringan berubah-ubah.** Keep-alive SSH 30 detik; saat koneksi putus pengguna dikembalikan ke
   daftar situs dengan pesan jelas.

## 3. Arsitektur

```
app.droidscp
├── core/    Murni Kotlin/JVM, tanpa dependensi Android (mudah diuji)
│   ├── Models.kt         Site, RemoteEntry, util path & ukuran
│   ├── HostKeys.kt       Verifikasi fingerprint TOFU (SSH)
│   ├── RemoteFs.kt       Antarmuka sesi bersama + util salin/skip
│   ├── RemoteSession.kt  SFTP (sshj) + SCP lewat exec channel
│   ├── SftpOps.kt / ShellFs.kt  operasi berkas lewat SFTP, atau lewat perintah shell bila server tanpa SFTP
│   └── FtpSession.kt     FTP/FTPS (commons-net), satu koneksi per transfer
├── data/
│   ├── Stores.kt         SiteRepository (terenkripsi), known hosts
│   ├── TransferManager.kt  Antrean, konflik, resume, unggah/unduh folder, eksekusi sinkronisasi
│   ├── Sync.kt           Perencana sinkronisasi (pindai dua sisi → rencana)
│   └── TransferService.kt  Foreground service + notifikasi
└── ui/       Jetpack Compose (Material 3)
    ├── AppViewModel.kt   Satu sumber state (UiState) dan semua aksi
    ├── Screens.kt        Daftar situs, form situs, penjelajah, antrean, editor
    └── Theme.kt
```

Pilihan pustaka: **sshj** (SFTP+SCP, aktif dipelihara) dengan **BouncyCastle** untuk algoritma modern
(ed25519, dll.). Alternatif JSch ditolak karena versi asli sudah lama tidak diperbarui dan tidak
mendukung banyak algoritma baru.

## 4. Batasan dan rencana

- **FTPS**: TLS eksplisit (AUTH TLS) atau implisit (sakelar pada situs, port bawaan 990). Sertifikat yang valid menurut
  CA sistem *dan* cocok dengan nama host diterima langsung. Selain itu (self-signed, CA tidak dikenal,
  nama tidak cocok) dipakai pin TOFU seperti SSH: pertama kali pengguna diminta mengonfirmasi
  fingerprint SHA-256, lalu bila sertifikat berubah koneksi ditolak dengan peringatan.
  Sebagian server mewajibkan *TLS session reuse* pada kanal data; bila unduhan/unggahan FTPS gagal di
  server seperti itu, gunakan SFTP.
- **FTP biasa** tidak terenkripsi. Form situs memberi peringatan; Android tidak memblokir soket mentah.
- **SCP**: transfer memakai `scp`. Bila server menyediakan SFTP, penelusuran lewat SFTP; bila tidak, dipakai
  perintah shell Unix (`ls -lA`, `mkdir`, `mv`, `rm`, `chmod`, `cat`, `cd`/`pwd`). Mode shell butuh shell dan
  `scp` standar di server, tidak mendukung nama berkas yang memuat baris baru, dan membaca waktu ubah dari
  `ls` (epoch bila GNU, selain itu perkiraan dalam UTC). SCP tidak bisa resume.
- **Resume** hanya melanjutkan dari ukuran berkas parsial; isi parsial tidak diverifikasi checksum. Ukuran
  akhir diperiksa, dan bila tidak cocok transfer dinyatakan gagal. Berkas parsial sengaja dibiarkan bila
  transfer gagal/dibatalkan supaya bisa dilanjutkan; unggahan parsial juga tertinggal di server.
- **Sinkronisasi** membandingkan ukuran dan waktu ubah (toleransi 2 detik), bukan checksum. Android tidak
  mengizinkan mengatur waktu ubah berkas SAF, jadi berkas hasil unduhan bertanda waktu "sekarang". Jam
  perangkat dan server yang jauh berbeda bisa membuat berkas diunggah ulang. Opsi hapus bersifat permanen.
- **Banyak sesi**: tiap koneksi jadi tab; antrean transfer dipakai bersama (maks 2 paralel untuk semua sesi).
  Memutus satu sesi hanya membatalkan transfer milik sesi itu. Potong-tempel dan sinkronisasi terikat pada sesi
  aktif dan dikosongkan saat berpindah tab. Sambung ulang berjalan satu per satu bila beberapa sesi putus bersamaan.
- **Sambung ulang**: bila sesi mati, 3 percobaan (0 s, 2 s, 5 s). Transfer yang sedang berjalan tetap gagal dan
  diulangi manual dari layar Transfer (memakai sesi baru; resume berlaku). Kunci host berubah dan login ditolak
  tidak dicoba ulang.
- **Buka dengan aplikasi lain**: berkas diunduh utuh ke cache (maks 64 MB, lihat `MAX_EXTERNAL_BYTES`) dan dibuka
  lewat `FileProvider` hanya-baca; perubahan oleh aplikasi lain tidak dikirim balik. Cache dibersihkan saat start.
- Bookmark tersimpan di profil situs (ikut terenkripsi); host tepercaya hanya bisa dilihat dan dilupakan.
- Kunci privat kini terenkripsi di disk, tetapi tetap berada di memori selama proses login.
- Pin FTPS hanya diperiksa pada kanal kontrol/data saat handshake; tidak ada layar kelola host tepercaya.
- Asumsi yang belum diverifikasi di build asli: `SSHClient.loadKeys(String, String?, PasswordFinder?)`
  (sshj, varian berbasis isi kunci) dan integrasi `EncryptedFile` dengan Keystore perangkat nyata.
- Belum ada: WebDAV/S3, pemantauan perubahan folder.

## 5. Keamanan

- `allowBackup=false` (rahasia tidak ikut cadangan awan); password dan passphrase hanya di
  `EncryptedSharedPreferences`.
- SSH: fingerprint berubah **tidak** diterima diam-diam; pengguna harus memilih eksplisit setelah peringatan.
- Nama berkas dari server tidak dipercaya: nama berisi `/`, `.` atau `..` ditolak, dan berkas lokal dibuat
  lewat SAF, sehingga *path traversal* tidak mungkin. Symlink folder tidak diikuti saat unduh/sinkron.
- Sinkronisasi dengan "hapus" dan operasi hapus rekursif selalu lewat konfirmasi/ringkasan lebih dulu.
