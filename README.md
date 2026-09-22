# Slide Show

Slide Show is a local Windows slideshow controller with a companion Android viewer. The Windows app serves the slideshow experience from local web assets, and the Android app can discover and display the slideshow on the local network.

## Watching photos

1. Open Slide Show on Windows and choose a photo folder. The Library shows recent PC folders and copies saved in this browser.
2. Use **Play here**, or **Watch on another device** to copy the address for a phone on the same Wi-Fi. Keep the PC and tray app running during connected playback.
3. On Android, choose a PC to start streaming. Use **Save offline** in playback settings when you want a copy on the phone. **Play offline** opens a saved copy immediately; **Update saved copy** refreshes it when its source folder is selected on the PC.

Playback supports shuffle, file name, and modification date order, and remembers the current photo per library on each device. **Show entire photo** preserves the edges; **Fill screen** crops them. Playback changes on connected viewers apply to the PC's other connected viewers; saved copies have independent settings.

Offline downloads show progress and can be canceled. A failed or canceled replacement preserves the previous copy. Android keeps up to four saved libraries. Browser saving requires HTTPS or localhost and available browser storage. Browser copies belong to the browser and address where they were saved.

Closing the control tab keeps the Windows server running in the system tray. Start at login is optional. JPG, PNG, WebP, GIF, BMP, AVIF and SVG are served by the PC; native Android format support depends on its OS. Convert HEIC/HEIF first.

## Build

Build the Windows app:

```powershell
dotnet build .\SlideShow.csproj --configuration Release
```

Build the Windows installer:

```powershell
powershell -ExecutionPolicy Bypass -File .\installer\build-installer.ps1
```

Build the Android app:

```powershell
cd android
gradle assembleRelease
```

## Regression checks

Focused regression checks: `dotnet test tests/SlideShow.Tests.csproj -c Release`, `node --test tests/playback.test.mjs`, and `gradle :app:testDebugUnitTest` from `android/`.

## Git Safety

This repo includes a local pre-push hook that blocks deleting or rewriting `main`.
Enable it in a checkout with:

```powershell
git config core.hooksPath .githooks
```

## Project Layout

- `wwwroot/` contains the slideshow and control web UI.
- `Assets/` contains app icon assets.
- `android/` contains the Android viewer project.
- `installer/` contains the Windows installer project and build script.
