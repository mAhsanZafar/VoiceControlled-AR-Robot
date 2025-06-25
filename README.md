# VoiceControlled-AR-Robot

A mobile Android application that integrates voice control, augmented reality (AR), computer vision, and robotics. The app allows users to control an ESP-based robot remotely using voice commands, while leveraging ARCore for spatial awareness and ML Kit for face and object detection.

## Features

* **Voice Control:** Use Android `SpeechRecognizer` and `TextToSpeech` for natural language commands.
* **AR Integration:** ARCore session to track device pose and provide real-time position updates.
* **Face Recognition & Tracking:** Detect and store face embeddings for named targets using ML Kit Face Detection.
* **Object Detection & Navigation:** Identify objects (e.g., chairs, tables) and navigate towards specified objects.
* **Coordinate-based Navigation:** Set coordinate targets and navigate the robot to specific positions.
* **Follow Me Mode:** Robot follows the user's face while avoiding obstacles.
* **ESP Control:** Send HTTP commands to an ESP microcontroller for robot movement.

## Supported Voice Commands

* **Basic Movements:** `move forward`, `move backward`, `turn left`, `turn right`, `stop`
* **Timed Movements:** Append a number to specify seconds, e.g., `move forward 3`
* **Follow Me:** `follow me`
* **Buddy Commands:**

  * `he is <name>`: Save the detected face under `<name>`
  * `go to <name>`: Navigate to the saved face target
* **Coordinate Navigation:** `go to x,z` (e.g., `go to 1.5,2.0`)
* **Object Navigation:** `go to <objectName>` (e.g., `go to chair`)

## Architecture Overview

1. **MainActivity**

   * Initializes UI, voice, camera, sensors, and ARCore.
   * Registers an `ImageAnalysis.Analyzer` for ML Kit vision processing.
   * Handles navigation logic in a loop at 1 Hz.

2. **Voice Components**

   * `SpeechRecognizer` for capturing voice input continuously.
   * `TextToSpeech` for audio feedback.

3. **Vision Models (ML Kit)**

   * **Face Detector**: Accurate mode for face embeddings and tracking.
   * **Object Detector**: Stream mode with classification.

4. **ARCore**

   * Session configured for environmental HDR and latest camera image updates.
   * Uses device pose to calculate relative coordinates.

5. **Networking**

   * `OkHttpClient` to send HTTP GET requests to ESP microcontroller at `/command?text=<cmd>`.

## Installation & Setup

1. **Clone the Repository**

   ```bash
   git clone https://github.com/mAhsanZafar/VoiceControlled-AR-Robot.git
   cd VoiceControlled-AR-Robot
   ```

2. **Prerequisites**

   * Android Studio Flamingo or later
   * Android device with ARCore support
   * ESP microcontroller flashed with HTTP command firmware

3. **Configure Dependencies**
   Ensure the following dependencies are in your `build.gradle` (Module) file:

   ```groovy
   implementation "androidx.camera:camera-core:1.2.0"
   implementation "androidx.camera:camera-camera2:1.2.0"
   implementation "androidx.camera:camera-lifecycle:1.2.0"
   implementation "com.google.ar:core:1.47.0"
   implementation "com.google.mlkit:face-detection:17.1.2"
   implementation "com.google.mlkit:object-detection:17.0.0-beta3"
   implementation "com.squareup.okhttp3:okhttp:4.10.0"
   ```

4. **Permissions**
   The app requests:

   * `RECORD_AUDIO` (for voice commands)
   * `CAMERA` (for AR and vision)
   * `ACCESS_FINE_LOCATION` (optional, for enhanced ARCore geo features)

5. **Run on Device**
   Connect your ARCore-compatible Android device and click **Run** in Android Studio.

6. **Configure ESP IP**

   * Enter your ESP controller's IP address in the app UI or use the default `192.168.0.160`.
   * Press **Connect** to verify communication.

## Usage

1. Grant all requested permissions when prompted.
2. Speak commands to control the robot:

   * Example: "Move forward 3" → Robot moves forward for 3 seconds.
   * Example: "He is Alice" → Saves detected face as "Alice".
   * Example: "Go to Alice" → Robot navigates to Alice.
3. View real-time position updates in the on-screen response text.

## Project Structure

```
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/example/rosrobot/
│   │   │   │   └── MainActivity.kt
│   │   │   └── res/layout/activity_main.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
└── settings.gradle
```

## Contributing

Contributions are welcome! Feel free to open issues or submit pull requests.

1. Fork the repository.
2. Create a feature branch: `git checkout -b feature-name`
3. Commit your changes: \`git commit -m "Add feature"
4. Push to the branch: `git push origin feature-name`
5. Open a pull request.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

---

*Happy Coding!*
