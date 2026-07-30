//! Point edits to a heading line.
//!
//! The application has no text editor: it changes a task's keyword, its
//! priority or its planning date, and every such change rewrites exactly one
//! line. That is not only an interface decision. The notes live in a git
//! checkout that is merged line by line, so an edit touching neighbouring
//! lines turns a merge that would have been automatic into a conflict.
//!
//! Reading a heading is [`markdown_org_extract::parse_heading_line`], which
//! reports where each token sits; writing one back is here. The split is
//! deliberate: the grammar belongs to the crate that also reads the files, and
//! a second copy of it would drift.

use markdown_org_extract::{HeadingLine, Priority};

use crate::document::Document;
use crate::TaskType;

/// Which heading to edit, and what the caller believes is written there.
///
/// `line` and `heading` come from a task the agenda produced. Between building
/// that agenda and running the edit the file may have been rewritten by a
/// sync, so both are checked before anything is written — see
/// [`EditError::Stale`].
#[derive(Debug, Clone, uniffi::Record)]
pub struct EditTarget {
    /// Absolute path of the notes directory.
    pub dir: String,
    /// Path of the file relative to `dir`, as a scan reported it.
    pub file: String,
    /// 1-based line the heading was found on.
    pub line: u32,
    /// Heading text without the keyword and the priority cookie.
    pub heading: String,
}

/// What the edit did.
#[derive(Debug, Clone, uniffi::Record)]
pub struct EditOutcome {
    /// The heading line as it now stands in the file.
    pub line: String,
    /// `false` when the file already held exactly this line and was not
    /// written to.
    pub changed: bool,
}

/// Why an edit did not happen.
///
/// The field is `detail`, not `message`, for the reason given on
/// [`crate::ExtractError`]: UniFFI would generate a Kotlin exception that
/// declares the property twice.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum EditError {
    /// The file is not there, or the path points outside the notes directory.
    #[error("file not found: {detail}")]
    NotFound {
        /// Human-readable detail.
        detail: String,
    },
    /// The line does not hold the heading the caller expected. The file has
    /// moved on since the agenda was built and has to be re-read.
    #[error("the file has changed since it was read: {detail}")]
    Stale {
        /// Human-readable detail.
        detail: String,
    },
    /// The priority is not a value org-mode accepts.
    #[error("invalid priority: {detail}")]
    InvalidPriority {
        /// Human-readable detail.
        detail: String,
    },
    /// The task carries no planning line of the requested kind.
    #[error("no planning line to move: {detail}")]
    NoPlanningLine {
        /// Human-readable detail.
        detail: String,
    },
    /// The date the caller passed as today is not `YYYY-MM-DD`.
    #[error("invalid date: {detail}")]
    InvalidDate {
        /// Human-readable detail.
        detail: String,
    },
    /// The edit is one this application does not make, and guessing at it
    /// would put a wrong date in the user's file.
    #[error("unsupported: {detail}")]
    Unsupported {
        /// Human-readable detail.
        detail: String,
    },
    /// Reading or writing the file failed.
    #[error("{detail}")]
    Io {
        /// Human-readable detail.
        detail: String,
    },
    /// The file is not valid UTF-8 — typically a note written in a legacy
    /// single-byte encoding and committed to the same repository. Separate
    /// from [`EditError::Io`] because the two need different answers: this
    /// one is fixed by converting the file, not by retrying.
    #[error("the file is not valid UTF-8: {detail}")]
    NotUtf8 {
        /// Human-readable detail.
        detail: String,
    },
}

/// Set, replace or clear the `TODO` / `DONE` / `CANCELLED` keyword.
///
/// Passing `None` clears the keyword. Passing the status the heading already
/// carries is a no-op that reports `changed: false` — including across the two
/// cancelled spellings, which are one status: a heading written `CANCELED`
/// stays `CANCELED` rather than being respelled under the user's hands.
#[uniffi::export]
pub fn set_status(target: EditTarget, status: Option<TaskType>) -> Result<EditOutcome, EditError> {
    edit_heading(target, |line, heading| with_status(line, heading, status))
}

/// The heading line with its keyword set to `status`, or cleared by `None`.
///
/// Shared with the completion flow, which sets the keyword as one part of a
/// larger edit rather than through [`set_status`].
pub(crate) fn with_status(line: &str, heading: &HeadingLine, status: Option<TaskType>) -> String {
    let current = heading
        .status
        .as_ref()
        .map(|token| &line[token.range.clone()]);

    let keyword = match status {
        None => None,
        Some(wanted) => {
            // Already this status: leave the line alone, which is also what
            // keeps a `CANCELED` spelling from being rewritten to `CANCELLED`.
            if current.is_some_and(|written| same_status(written, wanted)) {
                return line.to_string();
            }
            Some(keyword_of(wanted))
        }
    };

    match (&heading.status, keyword) {
        // Replace the keyword in place, leaving the gap after it alone.
        (Some(token), Some(keyword)) => splice(line, token.range.clone(), keyword),
        // Drop the keyword and the whitespace that separated it.
        (Some(token), None) => {
            let gap_end = skip_spaces(line, token.range.end);
            splice(line, token.range.start..gap_end, "")
        }
        // A keyword goes ahead of everything else on the line.
        (None, Some(keyword)) => {
            let at = body_start(line, heading.level);
            splice(line, at..at, &format!("{keyword} "))
        }
        (None, None) => line.to_string(),
    }
}

/// Set, replace or clear the `[#A]` priority cookie.
///
/// `priority` is the bare value (`A`, `12`), without the `[#` `]` framing.
/// Passing `None` clears the cookie.
#[uniffi::export]
pub fn set_priority(
    target: EditTarget,
    priority: Option<String>,
) -> Result<EditOutcome, EditError> {
    // Validated before the file is touched: an invalid value must leave the
    // file exactly as it was.
    if let Some(value) = priority.as_deref() {
        if Priority::parse(value).is_none() {
            return Err(EditError::InvalidPriority {
                detail: format!("{value:?} is neither an uppercase letter nor a number in 0..=64"),
            });
        }
    }

    edit_heading(target, |line, heading| {
        match (&heading.priority, priority.as_deref()) {
            (Some(token), Some(value)) => splice(line, token.range.clone(), &format!("[#{value}]")),
            (Some(token), None) => {
                let gap_end = skip_spaces(line, token.range.end);
                splice(line, token.range.start..gap_end, "")
            }
            // The cookie follows the keyword and precedes the title.
            (None, Some(value)) => {
                let at = heading.status.as_ref().map_or_else(
                    || body_start(line, heading.level),
                    |token| skip_spaces(line, token.range.end),
                );
                splice(line, at..at, &format!("[#{value}] "))
            }
            (None, None) => line.to_string(),
        }
    })
}

/// Read the file, hand the heading line to `rewrite`, write the result back.
fn edit_heading(
    target: EditTarget,
    rewrite: impl FnOnce(&str, &HeadingLine) -> String,
) -> Result<EditOutcome, EditError> {
    let mut document = Document::open(&target)?;
    let (index, heading) = document.heading(&target)?;
    let line = document.at(index).to_string();

    let rewritten = rewrite(&line, &heading);
    write_line(&mut document, index, rewritten)
}

/// Put `rewritten` on line `index`, writing the file only if that changes it.
///
/// Shared by every edit: rewriting a file with its own content would bump its
/// timestamp, show up as a write to whatever is watching the directory, and
/// report `changed: true` for an edit that changed nothing.
pub(crate) fn write_line(
    document: &mut Document,
    index: usize,
    rewritten: String,
) -> Result<EditOutcome, EditError> {
    if document.at(index) == rewritten {
        return Ok(EditOutcome {
            line: rewritten,
            changed: false,
        });
    }

    document.set(index, rewritten.clone());
    document.save()?;

    Ok(EditOutcome {
        line: rewritten,
        changed: true,
    })
}

/// Replace the bytes of `range` in `line` with `with`.
pub(crate) fn splice(line: &str, range: std::ops::Range<usize>, with: &str) -> String {
    let mut result = line.to_string();
    result.replace_range(range, with);
    result
}

/// First byte at or after `from` that is not a space or a tab.
fn skip_spaces(line: &str, from: usize) -> usize {
    from + line[from..]
        .find(|c: char| c != ' ' && c != '\t')
        .unwrap_or(line.len() - from)
}

/// Where the heading's content starts: past the `#` run and the gap after it.
///
/// Only whitespace is skipped here, so this is not a second copy of the
/// heading grammar — the tokens themselves are located by the extractor.
fn body_start(line: &str, level: usize) -> usize {
    skip_spaces(line, level)
}

/// The keyword written for a status. The cancelled variant is written in the
/// double-L spelling org-mode itself uses; a file already spelling it with one
/// L keeps its spelling, because that case never reaches here.
fn keyword_of(status: TaskType) -> &'static str {
    match status {
        TaskType::Todo => "TODO",
        TaskType::Done => "DONE",
        TaskType::Cancelled => "CANCELLED",
    }
}

/// Whether the keyword written in the file is the status `wanted`, treating
/// the two cancelled spellings as one status.
fn same_status(written: &str, wanted: TaskType) -> bool {
    match wanted {
        TaskType::Todo => written == "TODO",
        TaskType::Done => written == "DONE",
        TaskType::Cancelled => written == "CANCELLED" || written == "CANCELED",
    }
}
