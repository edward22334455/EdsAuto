# Ed's Auto

Waze and your music app side by side, plus a looping front and back dash cam.

## Build the APK on GitHub
1. Create a new GitHub repository and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab, pick **Build Ed's Auto APK**, and run it (it also runs on every push).
3. When it finishes, download the **EdsAuto-apk** artifact, unzip it, and install `app-debug.apk` on your phone.

## First-time setup on the phone
- Open Ed's Auto, tap **Turn on split-screen helper**, find "Ed's Auto" in Accessibility and switch it on.
  It is used only to trigger split screen. It does not read your screen.
- Pick Spotify, YouTube Music, Smart AudioBook Player or Audible, then tap **Start Waze + music**.
- For the dash cam, set the storage limit and tap **Start recording**. Allow camera (and microphone) when asked.

## Dash cam notes
- Clips are 10 minutes each (about 300 MB per camera at the built-in 720p/4 Mbit setting).
- Before each new clip the oldest ones are deleted so the folder stays under your limit.
- Clips are saved to `Android/data/com.eds.auto/files/EdsAuto/` (name ends `_back.mp4` / `_front.mp4`).
- Recording keeps going while Waze and music are on screen.
- Not every phone can run both cameras at once. If yours can't, it records the back camera only.
- There is a very short gap (about a second) between clips.

## Honest limits
- Waze, Spotify etc. are separate apps, so Ed's Auto puts them in Android's normal split screen
  rather than embedding them. Some phones or Waze versions may not accept the automatic split;
  if so, open Waze, use your phone's split-screen gesture, and pick the music app for the other half.
- Audible and Smart AudioBook Player are launched, not controlled.
