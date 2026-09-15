# Source-level regression checks

Run `python3 tools/host-tests/run.py` from a checkout with Python 3.9+, a JDK, and Kotlin/JVM 1.9+ on PATH. No downloads occur. The script builds in a temporary directory and removes it after execution.

To compare with the original commit, run this same script with `--repo /path/to/original/checkout`. The baseline must contain the original production files, not copies of the repaired algorithms. Both runs execute the same assertions.

The suite compiles ScanSessionService, LensRotationScheduler, YoloDetector, PlateFormats, and AlprResults directly, plus ModelAssetCache when present. `run.py` contains all replacement Android, ONNX Runtime, settings, and org.json interfaces. These doubles simulate permissions, a deterministic Handler clock, native ownership, and failure paths. Real filesystem operations, model-byte hashing, Kotlin parsing rules, and thread synchronization run on the JVM.

This is NOT an APK build, Android instrumentation, actual NNAPI/XNNPACK inference, real image decoding, or JSON grammar validation. The JSON double only supports object access; AlprJson.parsePlate is invoked reflectively with synthetic objects. No camera, private backup, native SDK, real license plate image, GPS feed, or Telegram recipient is used.

Expected baseline: 14 pass / 36 fail. Expected repaired source: 50 pass / 0 fail. These are assertion scenarios, not 36 unique bugs. The separate Gradle JUnit suite and Android Lint must still be run.
