# YT Video Download App

A simple Android app that downloads YouTube videos or audio from a link. You choose the quality, the file name and the folder where the file is saved.

Built with Kotlin and Jetpack Compose. The downloading itself is done by [yt-dlp](https://github.com/yt-dlp/yt-dlp), running on the phone through the [youtubedl-android](https://github.com/yausername/youtubedl-android) library.

> **For personal use only.** See the [Disclaimer](#disclaimer) at the bottom.

## Features

- Paste a YouTube link and fetch the video's title and thumbnail
- Choose from the qualities the video really offers (for example 1080p, 720p, 480p), or audio only (m4a or mp3)
- Choose the save folder, and the app remembers it
- Edit the file name before downloading; if the name already exists, the app asks whether to replace it or change the name
- Green progress button with live speed and time remaining
- Downloads keep running in the background, with a progress notification and a Cancel action
- Tap the finished notification to open the folder
- History of past downloads, tap an entry to open the file
- Engine updates itself once a day, plus an **Update engine** button for when YouTube changes something

## Download the app

**[Download the APK from Google Drive](https://drive.google.com/file/d/1oHlrb_e6e9xd6d0-E-fb5lH8A_Bltm0r/view?usp=drive_link)**

### How to install

1. Download the APK file to your phone.
2. Tap the file to install it.
3. If Android asks, allow **Install unknown apps** for the app you opened the file from (your browser or file manager).
4. If Google Play Protect shows a warning, choose **Install anyway**. The warning appears because the app is not distributed through the Play Store.

Requires **Android 7.0 (API 24) or newer** and an internet connection.

### First use

1. Open the app and tap **Choose** next to *Save to* to pick a folder. Android does not allow saving directly to the root of internal storage or to the Downloads folder itself, so create a subfolder such as `YT Downloads` and pick that.
2. Allow notifications when asked (Android 13 and newer), so you can see download progress.
3. Paste a link, tap **Fetch**, pick a quality, and tap **Download**.

If downloads start failing, tap **Update engine**. YouTube changes often, and updating yt-dlp usually fixes it.

## Build it yourself

1. Install [Android Studio](https://developer.android.com/studio).
2. Clone this repository:
   ```
   git clone https://github.com/Cosay09/YT-Video-Download-App.git
   ```
3. Open the cloned folder in Android Studio and wait for the Gradle sync to finish.
4. Connect an Android phone with USB debugging or Wireless debugging turned on, then press **Run**.

To make an APK, use **Build > Generate App Bundles or APKs > Generate APKs**. The file is created at `app/build/outputs/apk/debug/app-debug.apk`.

## How it works

1. **Fetch:** yt-dlp reads the link and returns the title, thumbnail and list of available formats. The app turns the available heights into the quality options.
2. **Download:** a foreground service runs yt-dlp with the chosen format, using FFmpeg to merge video and audio or convert to mp3. The file is first written to the app's private cache.
3. **Save:** when the download finishes, the file is copied into the folder you picked, using Android's Storage Access Framework, then removed from the cache.
4. **Progress:** the service publishes progress to a shared state that both the screen and the notification read, so they stay in sync.

## Project structure

| File | What it does |
| --- | --- |
| `MainActivity.kt` | App entry point, shows the main screen |
| `App.kt` | Starts the yt-dlp and FFmpeg engines when the app launches |
| `FetchScreen.kt` | The whole user interface: link field, quality dropdown, file name, folder picker, download button, history sheet |
| `DownloadService.kt` | Foreground service that runs the download, updates the notification and saves the file |
| `DownloadState.kt` | Shared download progress, speed and status |
| `HistoryStore.kt` | Saves and loads the download history |

## Built with

- [Kotlin](https://kotlinlang.org/) and [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [youtubedl-android](https://github.com/yausername/youtubedl-android) (library and FFmpeg modules), a wrapper around [yt-dlp](https://github.com/yt-dlp/yt-dlp)
- [Coil](https://coil-kt.github.io/coil/) for loading thumbnails
- AndroidX DocumentFile for saving to the chosen folder

## Disclaimer

This project was made for personal use and learning. It is not affiliated with or endorsed by YouTube or Google. YouTube's Terms of Service do not allow downloading content unless a download option is provided, and copyright laws still apply. Only download content that you own, that is licensed for reuse (such as Creative Commons), or that you have permission to save. You are responsible for how you use this app.
