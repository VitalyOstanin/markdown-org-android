//! Editing the text of one entry: its title, and the lines under it.
//!
//! Everything else in this crate rewrites a single line and says so — see the
//! note at the head of [`crate::edit`]. This is the one operation that touches
//! several, and it is bounded on purpose: an entry, not a file. The notes live
//! in a git checkout merged line by line, so an edit that reaches past the
//! entry the user opened turns a merge that would have been automatic into a
//! conflict, and there is no editor here worth that.
//!
//! What the entry is made of, in the order a file holds it:
//!
//! ```text
//! # TODO [#A] Write the report      <- the heading; the title is editable
//! `SCHEDULED: <2026-07-28 Tue>`     <- planning lines; moved by their own operations
//!                                   <- the separator, left where it is
//! The figures are in the drive.     <- the body, editable
//! ```
//!
//! The heading grammar is not repeated here: where the title starts is
//! [`markdown_org_extract::parse_heading_line`], and the same call is used
//! afterwards to check that what the user typed did not turn into a keyword.

use markdown_org_extract::parse_heading_line;

use crate::document::Document;
use crate::edit::{splice, EditError, EditOutcome, EditTarget};
use crate::planning::{bare_start, planning_keyword, CLOSED};

/// The text of an entry as the file holds it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct EntryText {
    /// The heading without its `#` run, its keyword and its priority cookie,
    /// exactly as written — the markup included, because that is what is
    /// being edited.
    pub title: String,
    /// The lines under the heading that are neither planning lines nor the
    /// blank separators around them, joined by newlines. Empty when the entry
    /// has nothing under it.
    pub body: String,
}

/// Read the title and the body of the entry `target` points at.
///
/// The same staleness check every edit makes runs here too: an entry read out
/// of a file that has moved on would be edited back over whatever now stands
/// in its place.
#[uniffi::export]
pub fn read_entry(target: EditTarget) -> Result<EntryText, EditError> {
    let document = Document::open(&target)?;
    let (index, heading) = document.heading(&target)?;
    let body = body_range(&document, index);

    Ok(EntryText {
        title: document.at(index)[heading.title_start..].to_string(),
        body: body
            .map(|line| document.at(line))
            .collect::<Vec<_>>()
            .join("\n"),
    })
}

/// Write the title and the body of the entry back.
///
/// Both at once, in one write and one commit, because they are one edit as
/// far as the user is concerned. Writing them in two calls would also make the
/// second one fail: the first changes the heading, and the second would arrive
/// naming a heading the file no longer holds.
///
/// Nothing is written when neither has changed, so opening an entry and
/// closing it leaves no commit behind.
#[uniffi::export]
pub fn set_entry(
    target: EditTarget,
    title: String,
    body: String,
) -> Result<EditOutcome, EditError> {
    let mut document = Document::open(&target)?;
    let (index, heading) = document.heading(&target)?;

    let line = document.at(index).to_string();
    let rewritten = retitled(&line, heading.title_start, &title)?;
    let lines = body_lines(&body)?;

    let before = document.text();
    document.set(index, rewritten.clone());
    let mut body = body_range(&document, index);
    if lines.is_empty() {
        body = with_the_separator(&document, body);
    }
    document.replace_lines(body, lines);

    if document.text() == before {
        return Ok(EditOutcome {
            line: rewritten,
            changed: false,
            rollback: None,
        });
    }

    let rollback = document.saved(before)?;
    Ok(EditOutcome {
        line: rewritten,
        changed: true,
        rollback: Some(rollback),
    })
}

/// The heading line with `title` in place of the title it carried.
///
/// The result is read back with the grammar that produced `title_start`, and
/// refused unless the title is still the title: a line typed as `TODO ring the
/// dentist` would otherwise come back as a keyword this application sets from
/// its own actions, and the user would have set a status by typing it.
fn retitled(line: &str, title_start: usize, title: &str) -> Result<String, EditError> {
    let title = title.trim();
    if title.is_empty() {
        return Err(EditError::Unsupported {
            detail: "a heading with no title is not written here".to_string(),
        });
    }
    if title.contains(['\n', '\r']) {
        return Err(EditError::Unsupported {
            detail: "a title is one line".to_string(),
        });
    }

    let rewritten = splice(line, title_start..line.len(), title);
    let reread =
        parse_heading_line(&rewritten).filter(|heading| heading.title_start == title_start);
    if reread.is_none() {
        return Err(EditError::Unsupported {
            detail: format!("{title:?} reads as a keyword or a priority, which are set by the actions of the sheet rather than typed"),
        });
    }

    Ok(rewritten)
}

/// The body as the lines it is written into the file as.
///
/// Blank lines at either end are dropped rather than written: the separator
/// between the planning lines and the body, and the one before the next
/// heading, belong to the file and are what [`body_range`] leaves out. Keeping
/// them here would add one on every save.
///
/// Shared with [`crate::create`]: a task written from nothing carries a body
/// typed into the same field, and it is refused on the same terms.
pub(crate) fn body_lines(body: &str) -> Result<Vec<String>, EditError> {
    let mut lines: Vec<String> = body
        .split('\n')
        .map(|line| line.trim_end_matches('\r').to_string())
        .collect();

    while lines.first().is_some_and(|line| line.trim().is_empty()) {
        lines.remove(0);
    }
    while lines.last().is_some_and(|line| line.trim().is_empty()) {
        lines.pop();
    }

    for line in &lines {
        if parse_heading_line(line).is_some() {
            return Err(EditError::Unsupported {
                detail: format!("{line:?} would start another entry"),
            });
        }
        if structural(line) {
            return Err(EditError::Unsupported {
                detail: format!(
                    "{line:?} is a planning line, which is written by the date actions"
                ),
            });
        }
    }

    Ok(lines)
}

/// Which lines of the entry at `index` are its body.
///
/// The entry runs to the next heading of any level, as the extractor reads it.
/// Within that, the body starts after the last planning line: a file where one
/// stands below a paragraph — after a manual edit, or a `CREATED:` line
/// between them — is left with that paragraph untouched rather than having it
/// swallowed by an edit aimed at the text below.
///
/// Blank lines at either end are left out, so the separator under the planning
/// lines and the one before the next heading survive an edit that empties the
/// body.
fn body_range(document: &Document, index: usize) -> std::ops::Range<usize> {
    let mut end = index + 1;
    while end < document.len() && parse_heading_line(document.at(end)).is_none() {
        end += 1;
    }

    let mut start = index + 1;
    for line in index + 1..end {
        if structural(document.at(line)) {
            start = line + 1;
        }
    }

    // Trailing first: an entry of nothing but a planning line and a blank
    // separator has to leave the separator alone, and trimming from the front
    // first would put the empty range after it, against the next heading.
    while end > start && document.at(end - 1).trim().is_empty() {
        end -= 1;
    }
    while start < end && document.at(start).trim().is_empty() {
        start += 1;
    }

    start..end
}

/// The range with the blank line above it, when there is one to take.
///
/// For an entry whose body is being taken out: the separator under the
/// planning lines and the one before the next heading are the same line once
/// nothing stands between them, and leaving both would open a gap in the file
/// on every emptied entry. An entry that had no body to begin with is left
/// alone — nothing is being removed, so nothing has closed up.
fn with_the_separator(
    document: &Document,
    range: std::ops::Range<usize>,
) -> std::ops::Range<usize> {
    let removing = range.start < range.end;
    let above_is_blank = range.start > 0 && document.at(range.start - 1).trim().is_empty();

    if removing && above_is_blank {
        range.start - 1..range.end
    } else {
        range
    }
}

/// Whether the line is one an operation writes rather than one the user types:
/// a planning line, or the closing date.
fn structural(line: &str) -> bool {
    planning_keyword(line).is_some() || bare_start(line).starts_with(CLOSED)
}
