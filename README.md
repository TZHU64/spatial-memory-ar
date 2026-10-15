# Spatial Memory for AR Glasses

An experimental Android app for RayNeo X3 Pro glasses. It selects camera views using local image quality and appearance-change gates, saves interpreted observations on the glasses, and answers spoken questions using recorded evidence. The main app stores memory in SQLite and calls Xiaomi MiMo over HTTPS for scene descriptions, speech recognition, and answers.

Answers describe recorded history; they do not establish an object's current location. This prototype does not implement SLAM, persistent 3D anchors, a wake word, or speech output.

## Source layout

| Directory | Contents |
| --- | --- |
| `prototype/android/app` | Main binocular memory app, camera gates, local memory, MiMo client, and device tests |
| `prototype/android/recorder` | Separate VGA/RGB recording app and export tools |
| `prototype/android/tests` | Standalone Java camera-gate tests |
| `prototype/backend` | Earlier optional Python memory service and browser interface |
| `prototype/vision` | Optional local-model replay, filtering, and recording review tools |
| `prototype/shared/protocol` | HTTP schemas, validators, and synthetic test fixtures |

This repository contains source and build configuration. SDK binaries, model weights, Gradle wrapper JAR, API keys, local settings, camera images, audio/video, databases, experiment outputs, and prebuilt apps are excluded. Synthetic protocol fixtures and tests generate their own data; no recorded test media is supplied.

## Build the glasses app

### 1. Prepare the tools and hardware

Use RayNeo X3 Pro glasses with developer mode and USB debugging enabled, Android SDK platform 32, Android SDK Build Tools, and Android Platform Tools (`adb`). The app requires Android 12 / API 31 or newer and RayNeo's Mercury runtime; an ordinary phone or emulator is not a substitute for the glasses UI and camera testing.

The build configuration uses Android Gradle Plugin 7.2.1, Kotlin 1.7.0, and Gradle 7.6. Use JDK 11 for this toolchain. Set `JAVA_HOME` and ensure `gradle` 7.6 and `adb` are available on your path. Install Gradle 7.6 separately for the first build because this source-only distribution excludes the wrapper JAR.

### 2. Obtain the Mercury SDK

Obtain Mercury Android SDK **v0.2.6** from RayNeo's developer resources under the applicable vendor terms. Place the AAR at:

```text
prototype/android/app/libs/mercury-sdk-0.2.6.aar
```

Create `libs/` if necessary. The app and recorder both use this file. The expected SDK SHA-256 is:

```text
5d408e2c5d80e8ae746c42abbda50012b50617005adfeb397661bec9c9be2676
```

The vendor binary and sample archive are not redistributed here.

### 3. Download the image embedder

From the repository root, run the included downloader. This command uses only Python's standard library and checks the downloaded model's SHA-256:

```sh
python3 prototype/vision/fetch_models.py
mkdir -p prototype/android/app/src/main/assets
cp prototype/vision/models/mobilenet_v3_small_075_224_embedder.tflite \
  prototype/android/app/src/main/assets/
```

It fetches Google's public [MobileNet image embedder](https://storage.googleapis.com/mediapipe-tasks/image_embedder/mobilenet_v3_small_075_224_embedder.tflite). The Android app also verifies the expected fingerprint before loading it.

### 4. Configure your own account

```sh
cp prototype/android/local.properties.example prototype/android/local.properties
```

Edit `sdk.dir` in that file to point to your Android SDK. Supply your own MiMo API key through the `MIMO_API_KEY` environment variable, or set `spatialMemory.mimoApiKey` in the ignored `local.properties`. The environment variable takes priority. The default scene/answer model is `mimo-v2.6-flash`; `spatialMemory.mimoModel` can override it. Speech recognition uses `mimo-v2.5-asr` in the client.

Debug builds embed the configured key in the APK. Keep configured APKs private. Release builds intentionally have empty credentials and cannot use the online features without a separate credential provisioning design.

### 5. Build and install

From the repository root:

```sh
cd prototype/android
gradle :app:assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You may then open `prototype/android` in Android Studio. To enable the included wrapper scripts locally, generate the omitted wrapper JAR with Gradle 7.6:

```sh
gradle wrapper --gradle-version 7.6 --distribution-type all
./gradlew :app:assembleDebug
```

Generated binaries and local configuration are ignored by Git. No signing keystore or signing password is provided.

## Use the app

1. Launch **Spatial Memory** on the glasses with internet access.
2. Select **Start / Resume**. Grant camera permission, then select it again if prompted. Automatic capture uses local gates; **Remember view** captures explicitly.
3. Wait for successful scene interpretation. Memories and their source JPEGs persist on the glasses.
4. Select **Ask by voice**, grant microphone permission if needed, and select it again. Speak a question and tap to finish; recording stops automatically after 15 seconds.
5. Review the transcript. Swipe through pages, tap to submit, or double tap to cancel. Read the answer on the binocular display.
6. Select **Start / Resume** to resume automatic capture after asking.

Camera monitoring pauses while asking. Questions are independent; conversational follow-up context is not implemented. Stop or backgrounding cancels recording, though a remote request already sent may finish at the provider.

Captured photos, selected historical evidence, and explicitly recorded questions are sent to MiMo. Scene records and source photos remain in the app's private storage across restarts. Automatic storage eviction is not implemented. Android app storage controls can clear these records.

## Optional tools

### Walk Recorder

This is a separate app for collecting your own recordings:

```sh
cd prototype/android
gradle :recorder:assembleDebug
adb install -r recorder/build/outputs/apk/debug/recorder-debug.apk
```

Its recording export and inspection tools are in `prototype/android/recorder/tools/`. Run each with `--help` for options. Keep exported recordings outside the public repository.

### Offline vision replay

Use Python 3.12. From the repository root:

```sh
python3.12 -m venv prototype/vision/.venv
prototype/vision/.venv/bin/python -m pip install -r prototype/vision/requirements-rgb.txt
prototype/vision/.venv/bin/python prototype/vision/fetch_models.py --rgb
HF_HUB_OFFLINE=1 prototype/vision/.venv/bin/python prototype/vision/replay.py \
  /path/to/your/recording --output prototype/vision/outputs/my-run --rgb-model mobileclip2
```

Use a recording exported by Walk Recorder. To run only the VGA gate, install `requirements.txt` instead and omit `--rgb` and `--rgb-model mobileclip2`. Review a generated run with `prototype/vision/review_server.py`; use `--help` for arguments. Models, reports, and recordings are created locally and are ignored by Git.

### Earlier computer backend

The current standalone app does not require this service. The service and browser interface remain available for development of the earlier computer-backed flow. It does not replace the standalone app's MiMo client.

Set up the full RGB vision environment above first; scene-mode startup requires its cached MobileCLIP weights and `prototype/vision/.venv`. Then, from the repository root:

```sh
python3.12 -m venv prototype/backend/.venv
prototype/backend/.venv/bin/python -m pip install -r prototype/backend/requirements.txt
cp prototype/backend/config.example.json prototype/backend/config.local.json
prototype/backend/.venv/bin/python prototype/backend/server.py
```

Supply `MIMO_API_KEY` in the backend process environment. Scene mode needs no reference photographs. The service generates a private pairing token in the local config and binds to `127.0.0.1:8765` by default. The question interface is available at `http://127.0.0.1:8766`. Runtime images and SQLite state are created in the ignored `prototype/backend/runtime/` directory.

## Checks

Run the backend's synthetic tests without contacting MiMo:

```sh
prototype/backend/.venv/bin/python -m unittest discover -s prototype/backend -v
```

Validate the protocol and run the offline pipeline's synthetic tests:

```sh
prototype/backend/.venv/bin/python -m pip install -r prototype/shared/protocol/requirements.txt
prototype/backend/.venv/bin/python prototype/shared/protocol/validate_minimal.py
prototype/backend/.venv/bin/python prototype/shared/protocol/validate_contract.py
prototype/vision/.venv/bin/python -m unittest discover -s prototype/vision/tests -v
```

With the SDK, model, and glasses configured:

```sh
cd prototype/android
gradle :app:lintDebug :recorder:lintDebug :app:assembleDebugAndroidTest
gradle :app:connectedDebugAndroidTest
```

The live MiMo smoke test is opt-in and skips by default; no test audio is included. Compilation and synthetic tests do not validate wearer comfort, camera quality, physical controls, or live model accuracy.
