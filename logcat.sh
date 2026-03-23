#!/usr/bin/env bash
set -euo pipefail

PKG=com.example.media_player
APK=app/build/outputs/apk/debug/app-debug.apk
LOG=logcat_$(date +%Y%m%d_%H%M%S).txt

adb install -r "$APK"
adb shell am start -n "$PKG/.MainActivity"

PID=$(adb shell pidof "$PKG" 2>/dev/null || true)

echo ">> PID: ${PID:-unknown}"
echo ">> Logging to $LOG  (Ctrl+C to stop)"

adb logcat -c

adb logcat \
  MatrixPlayer:V \
  MainActivity:V \
  MusicService:V \
  AudioEngine:V \
  AudioTrackOutput:V \
  UsbAudioOutput:V \
  UsbAudioManager:V \
  ArtworkCache:V \
  ArtworkActivity:V \
  ArtworkLayout:V \
  SyncedLyricsView:V \
  LyricsLoader:V \
  AppSettings:V \
  TidalFragment:V \
  TidalApi:V \
  TidalAuth:V \
  DashFlacDataSource:V \
  HttpMediaDataSource:V \
  BluetoothCodecManager:V \
  AndroidRuntime:E \
  ActivityManager:W \
  System.err:W \
  *:S \
  | tee "$LOG"
