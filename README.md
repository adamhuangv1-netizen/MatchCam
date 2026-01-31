# Match Cam

Match Cam is a recording software catered towards tennis players, giving them the ability to slice files into selected sizes, a flash timer to indicate recording, and a stealth feature to save battery during long recordings. It offers a wide selection of zoom settings as well as recording quality for the user to manage.

[https://github.com/adamhuangv1-netizen/MatchCam.git](https://github.com/adamhuangv1-netizen/MatchCam.git)

### How It's Made

*   **Tech Stack:** Kotlin, Android XML, CameraX, Material Design Components
*   **Core Functionality:** Built around the Android CameraX library for low-level control over the device's camera hardware.
*   **Adaptive UI:** The UI is designed with Android XML layouts that adapt to both portrait and landscape orientations for a consistent user experience.
*   **Data Persistence:** User settings are saved across app sessions using SharedPreferences, providing a customized experience every time.

### Optimizations

*   **Seamless Segmentation:** To prevent data loss during long recordings, the app automatically splits the video into manageable file segments (e.g., 5GB). It handles the hardware-level race condition between closing one file and starting the next by using a fail-safe, delayed restart (100ms), ensuring no action is missed.
*   **OLED-Designed Stealth Mode:** The "Stealth Mode" uses a pure black overlay, which turns off the pixels on OLED screens. This saves significant battery life during long recordings while remaining instantly responsive to touch. For LCD screens, it dims the screen to its lowest brightness.
*   **Dynamic UI:**
    *   Toggle switches use an XML `ColorStateList` to dynamically change their color from gray (off) to green (on), providing clear visual feedback.
    *   The app manages screen real estate by allowing only one menu to be open at a time, preventing clutter and layout overlap.

### Lessons Learned

*   Implementing features and fixing bugs based on feedback from paid clients.
*   Bridging the gap between hardware and software by leveraging CameraX features to control camera input and output.
*   Utilizing the Android Studio device manager to emulate and test the user experience before deploying to a physical device.

### Developed by Adam Huang