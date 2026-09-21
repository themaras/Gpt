# Poker Capture Android

Split-screen Android client for capturing a selected half of the screen and sending that cropped frame directly to the OpenAI Responses API.

## Flow

1. Open PokerStars and Poker Capture in Android split screen.
2. In Poker Capture choose which half contains the poker table: Left / Right / Top / Bottom.
3. Paste the OpenAI API key once and tap **Save API Key**. The key is encrypted at rest with Android Keystore and is never committed to GitHub.
4. Tap **Start Screen Capture** and choose **Entire screen** in Android's capture dialog.
5. During play, tap the large **CAP** button.
6. Poker Capture crops only the selected half, compresses the image in memory, calls the OpenAI Responses API, and displays the short result plus detected hand/position/stack/confidence and latency.

The screenshot is not saved to the photo gallery. A request is sent only when CAP is pressed.

Current model: `gpt-5.6-luna`.

GitHub Actions builds a debug APK automatically after pushes to `main`. Open **Actions → Build Android APK → latest run → Artifacts** and download `PokerCapture-debug-apk`.
