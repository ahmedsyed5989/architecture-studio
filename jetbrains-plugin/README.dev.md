# Architect Studio for IntelliJ IDEA / WebStorm — developer guide

Build, run, and release notes for the [Architect Studio JetBrains plugin](./README.md). User-facing docs live in [`README.md`](./README.md). Package ID and internal class names still say `apollon` throughout — that's an implementation detail, not something the rebrand touched (see the module dependency `de.tum.cit.aet.apollon` in `plugin.xml`); only user-visible text says "Architect Studio".

## Layout

A Gradle project plus one pnpm workspace, mirroring the [VS Code extension](../vscode-extension)'s split between host and canvas:

- [`src/main/kotlin/de/tum/cit/aet/apollon/`](./src/main/kotlin/de/tum/cit/aet/apollon) — the plugin host (Kotlin, IntelliJ Platform SDK):
  - `editor/` — `ApollonFileEditorProvider` + `ApollonFileEditor`, the `FileEditor` that hosts a JCEF browser loading the webview bundle, plus `ApollonWebviewRequestHandler` (serves that bundle over `http://apollon.localhost/` — JCEF has no supported way for a plugin to register a real custom scheme), `ApollonSaveListener` (flushes pending edits, syncs a PUML-backed diagram back to its `.puml` source, and drives auto-export on save), `ArchitectStudioTabTitleProvider` (a PUML-backed diagram's tab shows the `.puml` file's name, not its internal working file's), and `PumlSourceWatcher` (warns if a `.puml` file changes on disk while its canvas is open).
  - `document/` — `DiagramDocument.kt` (parse/scaffold/rewrite the `.apollon` JSON, including the legacy VS-Code-wrapped format) and `DocumentSync.kt` (debounced two-way sync between the canvas and the IntelliJ `Document`). Shared as-is by native `.apollon` diagrams and PUML-backed working files — a working file is just an `.apollon` file as far as this layer is concerned.
  - `puml/` — the PlantUML ↔ Apollon converter, deliberately IDE-free (no `com.intellij` import) so it's unit-testable without a platform test fixture: `PlantUmlImporter`/`PlantUmlExporter` (text ↔ `PumlDiagram`, see `PumlModel.kt`), `PumlResidual` (everything about the source text the canvas can't represent — comments, notes, unrecognised syntax, the exact keyword/arrow-token spelling — carried losslessly so a save never discards it), `ApollonModelMapper` (`PumlDiagram` ↔ the Apollon `UMLModel` JSON, merging a re-import onto an existing working file so canvas layout survives), `RoundTripValidator` (the gate before any `.puml` overwrite: re-parse the candidate text and check it still agrees with the model), and `PumlMemberText`/`PumlEndLabels`/`PumlArrows`/`PumlLayout` (the smaller grammar/geometry pieces those two lean on).
  - `workspace/` — `ArchitectStudioWorkspace` (the one IDE-aware class: owns `.architect-studio/`, imports a `.puml` file, and syncs canvas saves back to it) plus its pure, IDE-free helpers: `DiagramMappingRepository`/`DiagramIndex` (CRUD over `index.json`), `GitignoreEditor`, `Sha256` (external-change detection), and `AtomicFiles` (temp-file-then-rename writes for internal artefacts).
  - `protocol/` — the Kotlin side of the host↔webview message contract (`Protocol.kt`) and the diagram-type catalog (`DiagramTypes.kt`); kept in step with `webview/src/shared/`.
  - `export/` — `DiagramExporter`, the request/response bookkeeping for rendering a diagram to a sibling SVG/PNG.
  - `theme/` — `ThemeBridge.kt`, sampling the IDE's editor color scheme + Swing LaF into the `--apollon-*` CSS custom properties the canvas reads.
  - `actions/`, `toolwindow/`, `settings/` — the `Tools > Architect Studio` menu, the right-click `Architect Studio > Edit` action on a `.puml` file (`EditPumlDiagramAction`, gated by `ArchitectStudioGroup`), the diagram list tool window (native `.apollon` files only — a PUML-backed diagram's working file is filtered out), and the auto-export project setting.
- [`webview/`](./webview) — `@tumaet/jetbrains-webview`, the canvas that hosts the `@tumaet/apollon` editor (Vite). `src/shared/` mirrors the host's `protocol/` types; `jcefBridge.ts` and `theme.ts` replace the VS Code webview's `acquireVsCodeApi()`/`--vscode-*` equivalents with the JCEF `window.__apollonPostToHost`/`window.__apollonReceiveFromHost` bridge and `document.documentElement.dataset.theme`.

There is no shared TypeScript package between `vscode-extension/webview` and this one — the protocols are structurally similar but evolve independently; check both when changing the message contract.

### PlantUML round-trip architecture

```
.puml file  <──sync on save (workspace/ArchitectStudioWorkspace)──  working .apollon file
    │                                                                        │
    │  PlantUmlImporter.parse()                    PlantUmlExporter.render() │
    ▼                                                                        ▼
PumlDiagram + PumlResidual  ──ApollonModelMapper──  Apollon UMLModel JSON (the canvas)
```

`ArchitectStudioWorkspace` is the only class in this feature that touches IntelliJ Platform APIs; everything under `puml/` and the rest of `workspace/` is pure Kotlin so the converter and its merge/gitignore/index logic are tested directly, without a platform test fixture. A save never touches the `.puml` file until `RoundTripValidator` confirms the regenerated text re-parses back to the same classes and relationships the model has — and even then, only if the file's on-disk content still matches the hash recorded when Architect Studio last read it (otherwise the user is asked before anything is overwritten).

## Install dependencies

From the monorepo root:

```sh
pnpm install
```

## Build the webview bundle

The Gradle build does not shell out to pnpm itself — build the webview first, the same way CI does:

```sh
pnpm run build:jetbrains
```

This writes `webview/dist`, which `copyWebviewAssets` (a Gradle `Sync` task, wired into `processResources`) copies into `src/main/resources/webview` for `ApollonWebviewRequestHandler` to serve from the classpath at runtime.

## Run locally

```sh
cd jetbrains-plugin
./gradlew runIde
```

This launches a sandboxed IntelliJ IDEA Community instance with the plugin installed. Open or create a `.apollon` file to load the canvas. Re-run `pnpm run build:jetbrains` and restart `runIde` after a webview change; Kotlin changes only need `runIde` re-run.

## Checks

```sh
cd jetbrains-plugin
./gradlew verifyPluginProjectConfiguration   # sanity-checks the Gradle/plugin config itself
./gradlew test                               # JUnit 4 unit tests (document parsing/rewriting, puml/ and workspace/)
./gradlew buildPlugin                        # assembles build/distributions/*.zip
./gradlew verifyPlugin                       # IntelliJ Plugin Verifier against the recommended IDEs for sinceBuild..untilBuild
```

`verifyPlugin` fails only on `COMPATIBILITY_PROBLEMS`, `INVALID_PLUGIN`, `MISSING_DEPENDENCIES`, or `NOT_DYNAMIC` — see the comment above `pluginVerification` in `build.gradle.kts` for why deprecated/experimental/internal API usage is reported but not gated (it comes from implementing `ToolWindowFactory`, whose own interface methods are marked that way, independent of anything this plugin's code does).

`./gradlew buildPlugin` also runs `buildSearchableOptions`, which launches a headless IDE with the built plugin to harvest `Configurable` search terms — that needs a real (or virtual) display and working JCEF; in a display-less/JCEF-less container it fails with `Plugin 'Architect Studio' ... has module dependency 'intellij.platform.ui.jcef' which cannot be loaded`. That's an environment limitation, not a build error: run `./gradlew buildPlugin -x buildSearchableOptions` there instead, which still produces a complete, installable `build/distributions/*.zip`.

The PlantUML converter (`puml/`) and workspace layer (`workspace/`) are covered by `PlantUmlImporterTest`, `PlantUmlExporterTest`, `PumlMemberTextTest`, `EndLabelTest`, `ApollonModelMapperTest`, `RoundTripTest`, `RoundTripValidatorTest`, `GitignoreEditorTest`, `DiagramMappingRepositoryTest`, `Sha256Test`, and `AtomicFilesTest` — all pure JUnit 4, no platform test fixture needed, since none of those classes import `com.intellij.*`.

### Manually verifying the PlantUML workflow

`./gradlew test` covers the converter and workspace logic; the IDE integration itself (context menu, tool window, JCEF canvas) needs a `runIde` pass:

1. `pnpm run build:jetbrains && cd jetbrains-plugin && ./gradlew runIde`, then open a project containing a `.puml` file such as:
   ```plantuml
   @startuml
   class Customer {
     -name : String
     +placeOrder()
   }
   class Order
   Customer "1" --> "*" Order
   @enduml
   ```
2. Right-click the file in the Project tool window → **Architect Studio → Edit**. Confirm the canvas opens with `Customer` and `Order` and the `1`/`*` association between them, and that a `.architect-studio/` directory (with `index.json` and a working `.apollon` file under `diagrams/<id>/`) appeared next to it, and that the project's `.gitignore` now excludes it.
3. Edit the diagram — add an attribute, move a node, add a relationship — and save (`Ctrl+S`/`Cmd+S`). Confirm the `.puml` file on disk reflects the change and the working `.apollon` file was also updated.
4. Right-click the same `.puml` file again → **Edit**: it should reopen instantly (no re-import) since nothing changed externally.
5. Edit the `.puml` file directly in a text editor while its canvas tab is still open, save it, and confirm Architect Studio shows a balloon warning before the canvas's own next save would overwrite that change.
6. Open a native `.apollon` file and confirm New/Export/tool-window/auto-export all still behave exactly as before — this feature must not regress them.

For the webview:

```sh
pnpm --filter @tumaet/jetbrains-webview run typecheck
pnpm --filter @tumaet/jetbrains-webview run lint
pnpm --filter @tumaet/jetbrains-webview run build
```

`GRADLE_USER_HOME` matters if the default (`~/.gradle`) isn't writable or has limited space — set it before invoking `./gradlew` in that case.

## Release

The plugin shares Apollon's fixed version group (`.changeset/config.json`), so Changesets bumps `package.json` here alongside the library and the other standalone apps — `jetbrains-plugin/package.json` is a version carrier only; the actual build is Gradle/Kotlin. Record user-visible work with `pnpm changeset` like anywhere else in the repo (scope: `jetbrains`).

`Release JetBrains Plugin` (`.github/workflows/release-jetbrains-plugin.yml`) fires on a `jetbrains-plugin/package.json` version change, builds the webview + plugin ZIP, runs `verifyPlugin`, and — when `JETBRAINS_MARKETPLACE_TOKEN` and the signing secrets are configured — signs and publishes to the JetBrains Marketplace. Until then it runs in build+verify-only mode and still attaches the ZIP to a GitHub Release, matching how `Release VS Code Extension` behaves before its own Marketplace credentials existed.

Change notes shown on the Marketplace listing are generated from this package's `CHANGELOG.md` (via `scripts/extract-changelog.mjs`, the same script the other release workflows use for GitHub Release bodies) and passed to Gradle as `-PpluginChangeNotes=...`; a local `buildPlugin` without that property falls back to a link to the changelog file.
