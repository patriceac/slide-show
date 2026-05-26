# Slide Show

Slide Show is a local Windows slideshow controller with a companion Android viewer. The Windows app serves the slideshow experience from local web assets, and the Android app can discover and display the slideshow on the local network.

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
