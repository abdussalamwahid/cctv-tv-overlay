# cctv-tv-overlay (com.salam.tvwidget)

App overlay untuk Android TV (Xiaomi TV Stick) yang menampilkan **live CCTV 2x2 langsung di home launcher** tanpa membuka aplikasi.

- `src3/com/salam/tvwidget/FocusService.java` — AccessibilityService yang memantau fokus launcher; saat home aktif, overlay `WindowManager` (GridLayout 2x2, rounded seperti kartu app, ukuran disamakan dengan kartu Netflix/Prime/YouTube dan ikut scroll bersama) menampilkan frame dari `http://<server>:8090/frame/camN.jpg` yang di-refresh berkala. Ada efek suara tick ala PS5 (`res/raw/tick.wav`) saat fokus berpindah.
- `AndroidManifest.xml`, `res/` — manifest, konfigurasi accessibility service, strings, suara.

## Build
Project Android sederhana (tanpa Gradle penuh) — build dengan aapt/javac atau impor sebagai modul di Android Studio, hasil APK: `cctv-home-overlay.apk`.

## Pasang di TV stick
```bash
adb connect 192.168.1.6:5555
adb install cctv-home-overlay.apk
# aktifkan Accessibility service: com.salam.tvwidget/.FocusService
```
