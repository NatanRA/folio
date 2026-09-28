# Folio

Personal ebook/audiobook library: Android (Kotlin/Compose, `app/`) and native iOS (SwiftUI, `ios/`). Build, test and release steps are in `README.md`; Telegram/TDLib in `docs/telegram-library.md`; iOS in `ios/README.md`.

## Commands

- Android build: `ANDROID_HOME=/path/to/sdk ./gradlew :app:assembleDebug` (JDK 17+, SDK 36)
- Android checks: `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug` (instrumented tests need a clean emulator with no signed-in server)
- iOS: `xcodegen generate --spec ios/project.yml`, then `xcodebuild` per `ios/README.md`; IPA via `bash ios/package-ipa.sh`
- Release: `python3 scripts/publish-release.py RELEASE_TAG --apk ... --ipa ... --notes ...`

## Rules

- App ID `com.natan.mediaportal` and iOS identifiers must not change: upgrades keep users' libraries and progress.
- Never commit server credentials, personal books, signing keys or `telegram-private.json`. Build release packages without a Telegram identity installed; the publish helper rejects packages containing it.
- Release APKs are signed with `~/.android/folio-release.jks` (outside the repo). A lost or different key forces every user to reinstall; the helper checks the certificate SHA-256.
- Keep Android (`app/build.gradle.kts`) and iOS (`ios/project.yml`) versions in step; build numbers only increase. Add `docs/releases/v<version>.md` and a README release-history entry with each release.
- README download links must stay `releases/latest/download/...`, never version-specific.
- Built APKs/IPAs go in ignored `dist/`.
- BookLore and Telegram are separate systems; the app is a client and does not modify them.
