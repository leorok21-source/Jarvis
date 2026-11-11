# J.A.R.V.I.S. Android 1.0 (privacy-first test build)

Android app that takes a voice command only after tapping the microphone button. Features: flashlight, camera launch, battery percent, time, music volume and launching visible applications. Uses the phone's speech recognition service and TextToSpeech; no paid API keys.

Security: no autostart, background microphone service, notification listener, accessibility service, overlay, or internet permission. Microphone and camera are requested on demand. The app is an independent prototype, not affiliated with Marvel.

Limitations: **does not listen with the screen locked**; system speech recognition may use online services depending on phone configuration. The debug APK is automatically signed with an ephemeral debug key, not a Play Store release identity. Successful build is not proof that Play Protect will allow sideloading or that every device feature works. A trusted release requires stable signing, security testing, and distribution through a verified channel.
