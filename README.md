# Slide Show

Slide Show manages photo collections on Windows and plays them on Windows, Android, or a browser. Several collections can be shared from one PC on the local network.

## Watching photos

1. On Windows, **Add collection** chooses a photo folder. Select a collection to play it, rename it under **Folder details**, or change its playback options.
2. Turn on **Available to other devices** to share that collection. **Copy collection link** opens that specific collection on another device. Keep the PC and tray app running during connected playback.
3. Android lists available collections with the PC shown as their source. **Play** streams the selected collection; **Save offline** saves it on the phone. **Play offline** opens the saved copy. Refreshing a saved copy uses its original collection, independently of the desktop selection.

Playback supports shuffle, file name, and modification date order, and remembers the current photo per collection on each device. Choosing a collection never switches another viewer. **Show entire photo** preserves the edges; **Fill screen** crops them. Connected viewers of the same collection share playback options; different collections and saved copies have independent settings.

**Theme** offers System (the default), Light, and Dark from the collections screen's ⋮ menu and playback settings. The choice is saved on each browser or Android device, follows system changes automatically, and leaves the photo background unchanged.

Existing recent folders migrate to named collections. The previously selected folder keeps its sharing enabled; older folders remain available only on this PC until shared. Existing saved copies and PINs are preserved.

Offline downloads show progress and can be canceled. A failed or canceled replacement preserves the previous copy. Android keeps up to four saved libraries. Browser saving requires HTTPS or localhost and available browser storage. Browser copies belong to the browser and address where they were saved.

**Save offline** offers an optional PIN before downloading. Leave both PIN fields blank to save without a playback lock; photos are encrypted on the device either way. Updating a protected copy keeps its PIN; replacing it with another collection offers a new PIN choice.

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

Focused regression checks: `dotnet test tests/SlideShow.Tests.csproj -c Release`, `node --test tests/*.test.mjs`, and `gradle :app:testDebugUnitTest` from `android/`.

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
