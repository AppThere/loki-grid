//! Read-only commands for agents: inspect the workbook, read ranges, cell details.

use gridcraft_core::{CellRef, RangeRef, Value};
use serde_json::{Value as Json, json};

use super::{CommandSpec, EngineError, Result, Session, always, bad, bool_param, cell_param_on, cmd, has_doc, str_param, target_range, target_sheet};

pub fn specs() -> Vec<CommandSpec> {
    vec![
        cmd!(query "document.inspect", "Inspect Workbook", [], None, "{} → sheets, selection, names, tables, charts, dirty, trust (local|fromFile), history", has_doc, inspect),
        cmd!(query "sheet.read", "Read Range", [], None, "{range?: \"A1:D10\" (default: used range), sheet?, formulas?: bool, formatted?: bool} → rows of values", has_doc, read),
        cmd!(query "sheet.layout", "Sheet Layout", [], None, "{range?: \"A1:Z40\" (default: the first 40 rows and 26 columns; at most 50,000 cells), sheet?} → {range, cols: [width px], rows: [height px], defaultWidth, defaultHeight, freeze: {rows, cols} | null, merges: [\"B2:C3\"], cells: [{r, c, bold?, italic?, underline?, strike?, color?: \"#RRGGBB\", fill?: \"#RRGGBB\", align?: left|center|right, wrap?, size?: pt}]} the sizes and looks of a window of cells for a front end that draws them (a hidden column or row is 0; only cells whose style is not the default are listed; r and c are zero-based)", has_doc, layout),
        cmd!(query "cell.get", "Get Cell", [], None, "{cell?, sheet?} → value, formula, display text, style, comment, link, python {code, output, typeName, preview, stale}", has_doc, cell_get),
        cmd!(query "selection.stats", "Selection Statistics", [], None, "{} → count, sum, average, min, max", has_doc, sel_stats),
        cmd!(query "history.list", "Undo History", [], None, "{} → undo/redo labels, grouping (an undo group is open)", has_doc, history),
        cmd!(query "app.commands", "List Commands", [], None, "{search?} → ids, labels, params", always, list_commands),
    ]
}

pub fn value_json(v: &Value) -> Json {
    match v {
        Value::Empty => Json::Null,
        Value::Number(n) => json!(n),
        Value::Text(t) => json!(t.as_ref()),
        Value::Bool(b) => json!(b),
        Value::Error(e) => json!({"error": e.as_str()}),
        Value::Array(a) => {
            let rows: Vec<Json> =
                (0..a.rows).map(|r| Json::Array((0..a.cols).map(|c| value_json(&a.get(r, c).cloned().unwrap_or_default())).collect())).collect();
            Json::Array(rows)
        }
    }
}

fn inspect(s: &mut Session, _: &Json) -> Result<Json> {
    let d = s.doc()?;
    let wb = &d.wb;
    let sheets: Vec<Json> = wb
        .sheets
        .iter()
        .map(|sh| {
            json!({
                "name": sh.name,
                "visibility": format!("{:?}", sh.visibility),
                "usedRange": sh.used_range().map(|r| r.a1()),
                "cells": sh.cells.len(),
                "freeze": sh.freeze,
                "merges": sh.merges.iter().map(|m| m.a1()).collect::<Vec<_>>(),
                "tables": sh.tables.iter().map(|t| json!({"name": t.name, "range": t.range.a1(), "style": t.style, "totals": t.totals_row, "columns": t.columns.iter().map(|c| c.name.clone()).collect::<Vec<_>>()})).collect::<Vec<_>>(),
                "charts": sh.charts.iter().map(|c| json!({"id": c.id, "kind": format!("{:?}", c.kind), "title": c.title, "at": c.anchor.cell.a1(), "series": c.series.len(), "dataTable": c.data_table.as_ref().map(|t| json!({"showKeys": t.show_keys, "horizontalBorder": t.horizontal_border, "verticalBorder": t.vertical_border, "outline": t.outline})), "trendlines": c.series.iter().flat_map(|s| s.trendlines.iter().map(|t| format!("{:?}", t.kind))).collect::<Vec<_>>()})).collect::<Vec<_>>(),
                "images": sh.images.len(),
                "shapes": sh.shapes.len(),
                "conditionalFormats": sh.cond_formats.len(),
                "validations": sh.validations.len(),
                "comments": sh.comments.len(),
                "autofilter": sh.autofilter.as_ref().map(|a| a.range.a1()),
                "protected": sh.protection.is_some(),
                "tabColor": sh.tab_color.and_then(|c| c.hex(&wb.theme)),
                "zoom": sh.zoom,
            })
        })
        .collect();
    Ok(json!({
        "title": d.display_title(),
        "path": d.path,
        "dirty": d.is_dirty(),
        "trust": d.trust,
        "activeSheet": wb.active_sheet(),
        "sheets": sheets,
        "selection": d.selection.a1(),
        "activeCell": d.selection.active.a1(),
        "names": wb.names.iter().map(|n| json!({"name": n.name, "refersTo": format!("={}", n.formula), "scope": n.scope})).collect::<Vec<_>>(),
        "calcMode": format!("{:?}", wb.calc.mode),
        "undo": d.undo.iter().rev().take(20).map(|e| e.label.clone()).collect::<Vec<_>>(),
        "redo": d.redo.iter().rev().take(20).map(|e| e.label.clone()).collect::<Vec<_>>(),
        "theme": wb.theme.name,
        "mode": format!("{:?}", s.mode),
    }))
}

fn read(s: &mut Session, p: &Json) -> Result<Json> {
    let sheet = target_sheet(s, p)?;
    let d = s.doc()?;
    let sh = d.wb.sheet(sheet).ok_or(EngineError::NoDocument)?;
    let r = match str_param(p, "range") {
        Some(_) => target_range(s, p)?,
        None => match sh.used_range() {
            Some(r) => r,
            None => return Ok(json!({"range": null, "values": []})),
        },
    };
    let r = match sh.used_range() {
        Some(u) if r.is_full_cols() || r.is_full_rows() => r.intersection(&u).unwrap_or(RangeRef::cell(r.start)),
        _ => r,
    };
    if r.count() > 1_000_000 {
        return Err(bad("sheet.read", "range larger than 1,000,000 cells; read it in parts"));
    }
    let formulas = bool_param(p, "formulas").unwrap_or(false);
    let formatted = bool_param(p, "formatted").unwrap_or(false);
    let mut rows = Vec::with_capacity(r.height() as usize);
    for row in r.start.row..=r.end.row {
        let mut cols = Vec::with_capacity(r.width() as usize);
        for col in r.start.col..=r.end.col {
            let c = CellRef::new(row, col);
            let v = if formulas && let Some(f) = sh.cell(c).and_then(|x| x.formula.as_ref()) {
                json!(format!("={}", f.text))
            } else if formatted {
                json!(crate::display::cell_text(&d.wb, sh, c))
            } else {
                value_json(&sh.value(c))
            };
            cols.push(v);
        }
        rows.push(Json::Array(cols));
    }
    Ok(json!({"range": r.a1(), "sheet": sh.name, "values": rows}))
}

/// The most cells `sheet.layout` looks at in one call.
const LAYOUT_CELLS: u64 = 50_000;

fn layout(s: &mut Session, p: &Json) -> Result<Json> {
    use gridcraft_model::{HAlign, PatternType, StyleId, Underline};
    let sheet = target_sheet(s, p)?;
    let d = s.doc()?;
    let sh = d.wb.sheet(sheet).ok_or(EngineError::NoDocument)?;
    let r = match str_param(p, "range") {
        Some(_) => target_range(s, p)?,
        None => RangeRef::new(CellRef::new(0, 0), CellRef::new(39, 25)),
    };
    if u64::from(r.width()) * u64::from(r.height()) > LAYOUT_CELLS {
        return Err(bad("sheet.layout", "range larger than 50,000 cells; ask for a window"));
    }
    let theme = &d.wb.theme;
    let cols: Vec<Json> = (r.start.col..=r.end.col).map(|c| json!(sh.col_width(c))).collect();
    let rows: Vec<Json> = (r.start.row..=r.end.row).map(|row| json!(sh.row_height(row))).collect();
    let merges: Vec<String> = sh.merges.iter().filter(|m| m.intersection(&r).is_some()).map(RangeRef::a1).collect();
    let mut cells = Vec::new();
    for row in r.start.row..=r.end.row {
        for col in r.start.col..=r.end.col {
            let c = CellRef::new(row, col);
            let id = sh.style_id(c);
            if id == StyleId::DEFAULT {
                continue;
            }
            let st = d.wb.styles.get(id);
            let mut o = serde_json::Map::new();
            o.insert("r".into(), json!(row));
            o.insert("c".into(), json!(col));
            let f = &st.font;
            if f.bold {
                o.insert("bold".into(), json!(true));
            }
            if f.italic {
                o.insert("italic".into(), json!(true));
            }
            if f.underline != Underline::None {
                o.insert("underline".into(), json!(true));
            }
            if f.strike {
                o.insert("strike".into(), json!(true));
            }
            if let Some(hex) = f.color.hex(theme) {
                o.insert("color".into(), json!(hex));
            }
            if f.size.is_finite() && f.size > 0.0 {
                o.insert("size".into(), json!(f.size));
            }
            if st.fill.pattern == PatternType::Solid
                && let Some(hex) = st.fill.fg.hex(theme)
            {
                o.insert("fill".into(), json!(hex));
            }
            match st.align.h {
                HAlign::Left => o.insert("align".into(), json!("left")),
                HAlign::Center | HAlign::CenterAcross => o.insert("align".into(), json!("center")),
                HAlign::Right => o.insert("align".into(), json!("right")),
                _ => None,
            };
            if st.align.wrap {
                o.insert("wrap".into(), json!(true));
            }
            if o.len() > 2 {
                cells.push(Json::Object(o));
            }
        }
    }
    Ok(json!({
        "range": r.a1(),
        "cols": cols,
        "rows": rows,
        "defaultWidth": sh.default_col_width,
        "defaultHeight": sh.default_row_height,
        "freeze": sh.freeze.map(|(a, b)| json!({"rows": a, "cols": b})),
        "merges": merges,
        "cells": cells,
    }))
}

fn cell_get(s: &mut Session, p: &Json) -> Result<Json> {
    let sheet = target_sheet(s, p)?;
    let d = s.doc()?;
    let sh = d.wb.sheet(sheet).ok_or(EngineError::NoDocument)?;
    let c = cell_param_on(s, p, "cell", sheet)?.unwrap_or(d.selection.active);
    let cell = sh.cell(c);
    let style = d.wb.styles.get(sh.style_id(c));
    Ok(json!({
        "cell": c.a1(),
        "value": value_json(&sh.value(c)),
        "type": if sh.cell_pictures.contains_key(&c) { "picture" } else { match sh.value(c) { Value::Empty => "empty", Value::Number(_) => "number", Value::Text(_) => "text", Value::Bool(_) => "boolean", Value::Error(_) => "error", Value::Array(_) => "array" } },
        "formula": cell.and_then(|x| x.formula.as_ref()).map(|f| format!("={}", f.text)),
        "text": crate::display::cell_text(&d.wb, sh, c),
        "input": sh.input_text(c),
        "picture": sh.cell_pictures.get(&c).map(|picture| json!({"mime": picture.mime, "alt": picture.alt, "bytes": picture.data.len()})),
        "spilledFrom": sh.spill_anchor(c).map(|a| a.a1()),
        "spill": sh.spill_ranges.get(&c).map(|r| r.a1()),
        "style": style,
        "merge": sh.merge_at(c).map(|m| m.a1()),
        "comment": sh.comments.get(&c),
        "hyperlink": sh.hyperlinks.get(&c),
        "python": cell.and_then(|x| x.py.as_deref()).map(|py| json!({
            "code": py.code,
            "output": if py.object { "object" } else { "value" },
            "typeName": py.output.as_ref().map(|o| o.type_name.as_str()),
            "preview": py.output.as_ref().map(|o| o.preview.as_str()),
            "stale": py.stale,
        })),
    }))
}

fn sel_stats(s: &mut Session, _: &Json) -> Result<Json> {
    let d = s.doc()?;
    let sh = d.wb.active().ok_or(EngineError::NoDocument)?;
    let st = crate::display::stats(sh, &d.selection.ranges);
    serde_json::to_value(st).map_err(|e| EngineError::Other(e.to_string()))
}

fn history(s: &mut Session, _: &Json) -> Result<Json> {
    let d = s.doc()?;
    Ok(json!({
        "undo": d.undo.iter().rev().map(|e| e.label.clone()).collect::<Vec<_>>(),
        "redo": d.redo.iter().rev().map(|e| e.label.clone()).collect::<Vec<_>>(),
        "grouping": s.in_group(d.uid),
    }))
}

fn list_commands(s: &mut Session, p: &Json) -> Result<Json> {
    let q = str_param(p, "search").map(str::to_ascii_lowercase);
    let v: Vec<Json> = s
        .commands()
        .into_iter()
        .filter(|c| q.as_ref().is_none_or(|q| c.id.to_ascii_lowercase().contains(q.as_str()) || c.label.to_ascii_lowercase().contains(q.as_str())))
        .map(|c| serde_json::to_value(c).unwrap_or_default())
        .collect();
    Ok(Json::Array(v))
}

#[cfg(test)]
mod layout_tests {
    use serde_json::json;

    use crate::Session;

    fn session() -> Session {
        let mut s = Session::new();
        s.new_workbook();
        s.execute("cell.set", json!({"cell": "B2", "input": "x"})).unwrap();
        s
    }

    #[test]
    fn sizes_looks_merges_and_freeze_of_a_window() {
        let mut s = session();
        s.execute("home.bold", json!({"range": "B2"})).unwrap();
        s.execute("home.fillColor", json!({"range": "B2:C2", "color": "#FFFF00"})).unwrap();
        s.execute("home.alignRight", json!({"range": "C2"})).unwrap();
        s.execute("home.columnWidth", json!({"cols": "B:B", "width": 120})).unwrap();
        s.execute("home.rowHeight", json!({"rows": "3:3", "height": 40})).unwrap();
        s.execute("home.mergeCells", json!({"range": "E2:F3"})).unwrap();
        s.execute("view.freezeTopRow", json!({})).unwrap();
        let r = s.execute("sheet.layout", json!({"range": "A1:F4"})).unwrap();
        assert_eq!(r["range"], "A1:F4");
        assert_eq!(r["cols"].as_array().unwrap().len(), 6);
        assert_eq!(r["rows"].as_array().unwrap().len(), 4);
        assert_eq!(r["cols"][1], 120.0, "column B is the width set");
        assert_eq!(r["cols"][0], r["defaultWidth"], "column A is the default");
        assert_eq!(r["rows"][2], 40.0);
        assert_eq!(r["merges"], json!(["E2:F3"]));
        assert_eq!(r["freeze"], json!({"rows": 1, "cols": 0}));
        let cells = r["cells"].as_array().unwrap();
        let b2 = cells.iter().find(|c| c["r"] == 1 && c["c"] == 1).expect("B2 is styled");
        assert_eq!((b2["bold"].as_bool(), b2["fill"].as_str()), (Some(true), Some("#FFFF00")));
        let c2 = cells.iter().find(|c| c["r"] == 1 && c["c"] == 2).expect("C2 is styled");
        assert_eq!((c2["align"].as_str(), c2.get("bold")), (Some("right"), None));
        assert!(cells.iter().all(|c| !(c["r"] == 0 && c["c"] == 0)), "a default cell is not listed");
    }

    #[test]
    fn a_hidden_column_is_zero_wide_and_a_huge_window_is_refused() {
        let mut s = session();
        s.execute("home.hideColumns", json!({"cols": "C:C"})).unwrap();
        s.execute("home.hideRows", json!({"rows": "2:2"})).unwrap();
        let r = s.execute("sheet.layout", json!({"range": "A1:D3"})).unwrap();
        assert_eq!(r["cols"][2], 0.0, "column C is hidden");
        assert_eq!(r["rows"][1], 0.0, "row 2 is hidden");
        assert_ne!(r["cols"][1], 0.0);
        assert!(s.execute("sheet.layout", json!({"range": "A1:ZZ9999"})).is_err(), "more than 50,000 cells");
        assert!(s.execute("sheet.layout", json!({"range": "nonsense"})).is_err());
    }

    #[test]
    fn it_reads_the_other_sheet_when_asked() {
        let mut s = session();
        s.execute("sheet.rename", json!({"name": "One"})).unwrap();
        let r = s.execute("sheet.layout", json!({"sheet": "One", "range": "A1:B2"})).unwrap();
        assert_eq!(r["cols"].as_array().unwrap().len(), 2);
    }
}
