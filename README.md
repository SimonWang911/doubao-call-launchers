# Doubao Call Launchers

Two small Android launcher APKs for opening Doubao voice and video calls directly:

- `豆包语音通话`
- `豆包视频通话`

The apps are intended for elderly or visually impaired users who can ask the phone voice assistant to open one of the launcher apps. Each app immediately routes into the corresponding Doubao call deep link.

## Build

This project uses the local Android SDK command-line tools directly, without Gradle.

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\verify_project.ps1
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Output APKs are written to `dist/`.
