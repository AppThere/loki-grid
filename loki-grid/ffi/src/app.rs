//! What is GridCraft's own: the session and how methods reach it. A spreadsheet has no page to
//! render: the front end draws the grid itself from `sheet.read` (`loki.surface` says so).

use gridcraft_mcp::{Backend, Headless};
use serde_json::{Value, json};

use crate::Rendered;

pub(crate) struct Inner {
    h: Headless,
}

/// A session with a blank workbook open.
pub(crate) fn new() -> Inner {
    Inner { h: Headless::new() }
}

/// Every control-protocol method the headless backend answers (`engine.execute`, `engine.commands`,
/// and the read-only commands by id: `sheet.read`, `cell.get`, `document.inspect`, …).
pub(crate) fn call(inner: &mut Inner, method: &str, params: Value) -> Result<Value, String> {
    inner.h.call(method, params)
}

/// The headless backend runs commands with `execute_headless`, which answers a request for a
/// dialog with an error and drops the rest, so nothing reaches the front end yet (phase 6).
pub(crate) fn ui_requests(_inner: &mut Inner) -> Value {
    json!([])
}

/// `cells`: the front end draws the active sheet from `sheet.read`; `pages` is the sheet count.
pub(crate) fn surface(inner: &mut Inner) -> Value {
    match inner.h.session.doc() {
        Ok(d) => json!({"kind": "cells", "pages": d.wb.sheets.len(), "current": d.wb.active_sheet()}),
        Err(_) => json!({"kind": "cells", "pages": 0, "current": 0}),
    }
}

pub(crate) fn render(_inner: &mut Inner, _page: u32, _scale: f32, _max_pixels: u64) -> Option<Rendered> {
    None
}
