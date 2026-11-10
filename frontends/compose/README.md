# GridCraft — Compose Multiplatform frontend

A second, swappable UI for GridCraft, written in Kotlin with Compose Multiplatform. Like
`crates/ui-egui` it is thin: it holds no spreadsheet logic and shares no code with the Rust
workspace. It reads engine state and acts only through the control protocol
([`docs/control-protocol.md`](../../docs/control-protocol.md)): `document.inspect`, `sheet.read`,
`cell.get`, `selection.set`, `cell.set`, `sheet.activate`, `edit.undo`/`edit.redo`.

## Run

```sh
cargo run --release -p gridcraft -- --sample sales --control 7979   # the engine
cd frontends/compose && gradle :composeApp:run --args="--port 7979"   # this frontend
```

The token is read from `GRIDCRAFT_CONTROL_TOKEN` or the app's token file for that port.
Test: `gradle :composeApp:desktopTest`.

## Layout

- `commonMain`: `EngineClient` (transport interface), models, `GridViewModel`, the `App` composables.
- `desktopMain`: `TcpEngineClient` (authenticated JSON lines over TCP) and `main`.

## Status / next steps

First slice: toolbar (undo/redo), formula bar, 100×26 grid window, sheet tabs, engine errors shown.
Not yet: scrolling beyond the window and virtual columns, in-cell editing, keyboard navigation,
formatting (fonts, fills, number formats from `cell.get` style), ribbon from `engine.commands`,
and web (wasm), Android and iOS targets (these need a transport other than a raw TCP socket).
Building needs Maven Central, the Gradle plugin portal and Google Maven (`dl.google.com`).
