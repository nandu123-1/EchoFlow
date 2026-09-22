# EchoFlow

Voice-first ambient AI assistant for Android.

## Features

- **Ambient Voice Capture & Assistant**: Quick activation via accessibility service, tile, or floating gesture triggers.
- **On-Device & Fallback AI Reasoning**: Local GGUF/Qwen LLM engine with rule-based fallback support.
- **Action Execution & Graph**: Automated calendar management, voice messaging, reminders, calls, and notes.
- **Modern Jetpack Compose UI**: Dynamic timeline, history, and configurable settings.
- **Room Database Persistence**: Robust offline-first storage and audit logging.

## Tech Stack

- **Platform**: Android (Kotlin, Jetpack Compose, Material 3)
- **Architecture**: MVVM, Kotlin Coroutines & Flow, Room Database
- **Local AI**: GGUF inference via Llama Android
- **Build System**: Gradle Version Catalogs (libs.versions.toml)
