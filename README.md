# EWS Lereng Android — Versi GitHub Sederhana

Versi ini kembali ke arsitektur aplikasi Android pada buku awal:

- TANPA Firebase
- TANPA google-services.json
- TANPA service account
- TANPA Android Studio untuk build
- Build APK otomatis dengan GitHub Actions
- Aplikasi polling backend Google Apps Script setiap 1 menit
- Foreground service tetap memantau ketika aplikasi ditutup
- Wake lock + permintaan pengecualian battery optimization
- Alarm dicatat per sensor + per severity sehingga level yang sama tidak berbunyi berulang
- Status terbaru: AMAN / WASPADA / SIAGA / AWAS / BAHAYA

## Backend

URL Apps Script sudah diisi:

https://script.google.com/macros/s/AKfycbxENcSAzoCpD2N4ENn_c_Mo36Oxlk51-5pJXKp3aypRBF7F9O6S9XJlifPU62eWGJU0/exec

Aplikasi mencoba `?api=data` terlebih dahulu seperti metode buku awal.
Jika endpoint tersebut tidak tersedia, aplikasi otomatis fallback ke:
- `?api=status`
- `?api=power`

Jadi tidak perlu mengubah backend untuk build pertama.

## Upload ke GitHub

1. Buat repository baru, misalnya `ews-lereng-app`.
2. Jangan buat README dari GitHub.
3. Upload ISI folder proyek ini sehingga `app`, `.github`, `build.gradle`, dan `settings.gradle` berada langsung di root repository.
4. Commit.
5. Buka tab Actions.
6. Tunggu `Build APK` selesai.
7. Pada halaman run, download Artifact `app-debug-apk`.
8. Extract ZIP artifact -> `app-debug.apk`.
9. Install APK ke HP.

## Setelah install

Saat pertama dibuka:
- Izinkan notifikasi.
- Setujui pengecualian battery optimization jika diperlukan.
- Biarkan notifikasi foreground `EWS Lereng` aktif.

## Alarm

Default:
- AMAN = tidak alarm
- WASPADA = alarm/notifikasi
- SIAGA = alarm/notifikasi
- AWAS = alarm/notifikasi
- BAHAYA = alarm/notifikasi

Jika ingin alarm suara hanya mulai SIAGA:
ubah di `Config.kt`:

`const val ALARM_MIN_SEVERITY = 2`

## Catatan

Ini aplikasi monitoring tambahan. Jalur keselamatan utama tetap:
S1-S6 -> ESP-NOW -> Pos Utama -> Sirine.
