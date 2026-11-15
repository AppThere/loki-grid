//! GridCraft's own: what a cell gets is what `sheet.read` gives back.

use super::*;

#[test]
fn a_value_typed_into_a_cell_is_read_back() {
    let s = loki_session_new();
    let r = call(s, "engine.execute", Some(r#"{"command":"cell.set","params":{"cell":"B2","input":"=6*7"}}"#));
    assert_eq!(r["ok"], true, "{r}");
    let r = call(s, "sheet.read", Some(r#"{"range":"A1:C3"}"#));
    assert_eq!(r["ok"], true, "{r}");
    assert!(r["result"].to_string().contains("42"), "{r}");
    assert_eq!(call(s, "loki.surface", None)["result"]["kind"], "cells");
    unsafe { loki_session_free(s) };
}
