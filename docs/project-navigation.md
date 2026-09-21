# Folio navigation from Tandem

Folio is a native second integration for Tandem's project navigation interface.
The same Core catalog loader, parameter validation, URL compilation and phone
executor used for Formula Visuals consume `.tandem/navigation.json` here. There
are no Folio-specific branches in Tandem.

Available destinations:

| Natural-language intent | Destination | Arguments |
|---|---|---|
| Open Home / Library / Downloads / Server | `open-screen` | `screen`: `home`, `library`, `downloads`, `server` |
| Search my library for Alice | `search-library` | `query`: `Alice` |

The model discovers the catalog and resolves natural language; users do not
enter URLs. Internally both platforms accept `folio://navigate?screen=server`
or `folio://navigate?screen=library&q=Alice`. Search opens the existing local/
cached library search, not the Free books service. These routes cannot import,
download, delete, connect a server, change credentials or start playback.
Existing pairing links use their separate `folio-pair` scheme and confirmation.

The parsers reject unknown or duplicate fields and invalid screens. Search is
allowed only on Library; a query is bounded to 200 UTF-16 units and cannot carry
control characters. Form encoding preserves spaces, ampersands and literal plus
signs from Tandem's URLSearchParams output. Android handles both `onCreate` and
`onNewIntent`; iOS receives `onOpenURL`, resets navigation stacks and switches the
requested tab/search state. Invalid navigation URLs never fall through to import.

## Verification, 21 September 2026

- Android debug build, lint and 58 unit tests passed, including the new route parser.
- iOS simulator build and three new navigation unit tests passed.
- Updated apps installed on the existing iPhone 17 Pro simulator and Android
  emulator. Installation preserves application data.
- Catalog resolution checked using Tandem's actual `project-navigation.js` for
  both platforms, including `Alice & C++` encoded as `Alice+%26+C%2B%2B`.
- Live navigation through the source phone service requires the project's saved
  simulator AI-access grant. The test harness reads/rechecks that actual grant;
  it does not enable access or substitute an always-allow callback.
- Project AI access was enabled through Tandem's authenticated API with the
  user's explicit authorization.
- The real Tandem source service navigated both running apps to Home, Library,
  Downloads and Server settings. Returned UI observations verified each screen.
  Library search showed `Alice` and the explicit no-matches message on both;
  screenshots were also inspected. Both libraries were empty, so this does not
  establish populated-library loading performance.

| Operation | iPhone 17 Pro simulator | Android emulator |
|---|---:|---:|
| Open Downloads | 1.57 s | 0.51 s |
| Search library for Alice | 3.09 s | 0.64 s |
| Open Home | 1.72 s | 0.70 s |
| Open Library | 1.58 s | 0.47 s |

These are single-run, warm-app source-tool timings, including device discovery
within the navigation call, dispatch and the returned UI observation. Both
phones run concurrently. They exclude initial catalog/inventory calls, model
interpretation, inter-call delays and subsequent human/model verification.
They are not full natural-language chat benchmarks or cold-start results.

The initial Server call encountered iOS's first-use Open confirmation and an
Android UI-capture-unavailable error. It was not counted as successful arrival.
After explicitly confirming Open, a fresh observation verified Server on both
phones. The initial iOS search observation also included the first-use keyboard
tutorial; Continue was tapped separately and clean search screenshots verified.
Those setup interactions are outside the table's navigation-call timings.

At initial validation, the updated Folio apps were installed on the simulators
and Tandem's desktop package was unchanged. These measurements precede release;
they do not establish end-to-end behavior through the installed chat tool path.

Run Android checks with JDK 17 and Android SDK 36. iOS tests live in
`MediaPortalTests/FolioNavigationTests`. Internal build logs and the live test
harness are in `.agent-work/2026-09-21-navigation/`; build products remain in the
platform build directories.
