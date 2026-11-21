# APK Copier (Android)

Aplikasi Android untuk menyalin (backup) dan membagikan aplikasi yang terinstal di HP, mendukung baik APK tunggal (`.apk`) maupun Split APK (`_split.zip`).

## Cara Mendapatkan File `.apk` Otomatis via GitHub
1. Ekstrak file ZIP ini.
2. Buat repositori baru di **GitHub** (Public atau Private).
3. Unggah seluruh file dan folder hasil ekstrak (termasuk folder tersembunyi `.github/workflows/build-apk.yml`) ke repositori tersebut.
4. Buka tab **Actions** di GitHub, tunggu proses **Build Android APK** selesai (centang hijau).
5. Unduh file **`APK-Copier-Debug`** di bagian **Artifacts** di bagian bawah halaman workflow, ekstrak, lalu instal `app-debug.apk` di HP Anda.
