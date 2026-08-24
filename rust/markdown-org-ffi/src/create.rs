//! Writing a task that was not in the notes before.
//!
//! Every other operation in this crate finds a heading and changes it. This
//! one has no heading to find: what it is given is what the user typed, and
//! where it goes is a file named in the settings of a collection — the file
//! that receives new tasks. The entry is appended to the end of it, which is
//! the one place a write cannot collide with an edit made elsewhere: the notes
//! live in a git checkout merged line by line, and two devices adding a task
//! to the same file on the same day merge cleanly as long as neither of them
//! rewrites what is above.
//!
//! What is written follows the file rather than a house style, the same way
//! [`crate::planning`] writes a date: the heading is written at the level the
//! file writes its tasks at, and a planning line is spelled the way the file
//! spells the ones it already has.

use chrono::NaiveDate;
use markdown_org_extract::{parse_heading_line, Priority};

use crate::document::Document;
use crate::edit::{keyword_of, EditError, EditOutcome};
use crate::entry::body_lines;
use crate::occurrence::parse_time;
use crate::planning::{checked_repeater, planning_line, PlanningKeyword, StampTokens};
use crate::TaskType;

/// A task to write, as the screen composed it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct NewTask {
    /// Absolute path of the notes directory of the collection.
    pub dir: String,
    /// The file that receives new tasks, relative to `dir`. It is created
    /// when it is not there yet; the directory above it is not.
    pub file: String,
    /// The heading's own text, without a keyword or a priority cookie —
    /// those are the two fields below, so that typing one cannot set the
    /// other.
    pub title: String,
    /// The lines under the heading, empty for a task that is only a heading.
    pub body: String,
    /// The keyword to write, or `None` for a heading that carries none.
    pub status: Option<TaskType>,
    /// The bare priority (`A`, `12`), without the `[#` `]` framing.
    pub priority: Option<String>,
    /// The date the task is planned for, if any.
    pub planning: Option<NewPlanning>,
}

/// A date on a new task, and everything the timestamp around it carries.
///
/// One record rather than loose optional fields, because a date without a
/// keyword is not a planning line and a keyword without a date is not a date:
/// they travel together or not at all. The hour and the repeater belong to
/// that same date — there is no hour to be held at and nothing to repeat
/// where no day was chosen.
#[derive(Debug, Clone, uniffi::Record)]
pub struct NewPlanning {
    pub keyword: PlanningKeyword,
    /// `YYYY-MM-DD`.
    pub date: String,
    /// `HH:MM`, or `None` for an entry that takes the whole day.
    pub time: Option<String>,
    /// An org repeater (`++1w`), or `None` for a task that happens once.
    /// Written the canonical way whatever the caller spelled it as.
    pub repeater: Option<String>,
}

/// Write a new task at the end of the file that receives them.
///
/// Answers with the heading line as it was written and the pair an undo works
/// from, the same as every other write here — see [`crate::undo`]. `changed`
/// is always true: a task that was asked for is a task that was written.
///
/// Nothing reaches the file until all of it is in hand: a title that reads as
/// a keyword, a body line that would start another entry, a date outside the
/// years timestamps are written in, an hour that is not one and a repeater
/// that spells nothing are all refused with the file exactly as it was.
#[uniffi::export]
pub fn create_task(task: NewTask) -> Result<EditOutcome, EditError> {
    // Everything the caller typed is read before the file is opened, for the
    // reason the other operations read theirs there: a value the caller
    // mistyped must leave the notes as they were.
    if let Some(value) = task.priority.as_deref() {
        if Priority::parse(value).is_none() {
            return Err(EditError::InvalidPriority {
                detail: format!("{value:?} is neither an uppercase letter nor a number in 0..=64"),
            });
        }
    }
    let planning = task
        .planning
        .as_ref()
        .map(|planning| {
            let date = NaiveDate::parse_from_str(&planning.date, "%Y-%m-%d").map_err(|error| {
                EditError::InvalidDate {
                    detail: format!("{:?}: {error}", planning.date),
                }
            })?;
            let time = planning.time.as_deref().map(parse_time).transpose()?;
            let repeater = planning
                .repeater
                .as_deref()
                .map(checked_repeater)
                .transpose()?;

            Ok::<_, EditError>((planning.keyword, date, time, repeater))
        })
        .transpose()?;

    let mut document = Document::read_or_empty(&task.dir, &task.file)?;
    let heading = heading_line(
        entry_level(&document),
        task.status,
        task.priority.as_deref(),
        &task.title,
    )?;
    let body = body_lines(&task.body)?;

    let before = document.text();
    let entry = opening(&document, heading.clone());
    append(&mut document, entry);
    // The heading is the last line of the file, whether or not a blank
    // separator went in ahead of it.
    let index = document.len() - 1;

    if let Some((keyword, date, time, repeater)) = planning {
        let tokens = StampTokens {
            time: time.as_deref(),
            repeater: repeater.as_deref(),
        };
        let line = planning_line(&document, index, keyword, date, tokens)?;
        append(&mut document, vec![line]);
    }
    if !body.is_empty() {
        // A blank line between what an operation writes and what the user
        // typed: without it a paragraph runs on from the planning line above
        // it, and the entry editor's own idea of where a body begins is the
        // line after the last blank one.
        append(
            &mut document,
            std::iter::once(String::new()).chain(body).collect(),
        );
    }

    let rollback = document.saved(before)?;
    Ok(EditOutcome {
        line: heading,
        changed: true,
        rollback: Some(rollback),
    })
}

/// The heading with the blank line that has to precede it, if any.
///
/// A file whose last line already stands empty gets no second one: the
/// separator belongs between two entries, and one added on every write would
/// open a gap that grows by a line per task.
pub(crate) fn opening(document: &Document, heading: String) -> Vec<String> {
    let last = document.len().checked_sub(1);
    let closed = last.is_none_or(|index| document.at(index).trim().is_empty());

    if closed {
        vec![heading]
    } else {
        vec![String::new(), heading]
    }
}

/// Put `lines` at the end of the document.
pub(crate) fn append(document: &mut Document, lines: Vec<String>) {
    let at = document.len();
    document.replace_lines(at..at, lines);
}

/// The level a new entry is written at: the level this file writes its tasks
/// at.
///
/// Taken from the file rather than fixed at one, because a note is usually a
/// title and the tasks under it — the sample this application writes is —
/// and a task appended at the top level of such a file would stand beside the
/// title instead of under it.
///
/// The shallowest level carrying a keyword is what the file writes its tasks
/// at; a file with headings but no tasks yet gets one level below its
/// shallowest heading, which is where a task under a title goes; a file with
/// no headings at all — one just created — starts at the top.
fn entry_level(document: &Document) -> usize {
    let headings: Vec<_> = (0..document.len())
        .filter_map(|index| parse_heading_line(document.at(index)))
        .collect();

    let tasks = headings
        .iter()
        .filter(|heading| heading.status.is_some())
        .map(|heading| heading.level)
        .min();
    if let Some(level) = tasks {
        return level;
    }

    headings
        .iter()
        .map(|heading| heading.level)
        .min()
        // Six is as deep as a heading goes, so a file written entirely at that
        // depth takes a sibling rather than a child that is not a heading.
        .map_or(1, |level| (level + 1).min(MAX_LEVEL))
}

/// As many `#` as a heading can carry, which is what markdown allows.
const MAX_LEVEL: usize = 6;

/// The heading line to write, with the title checked for what it must not be.
///
/// The title is read back with the grammar that will read it out of the file,
/// on its own line without the tokens: a title typed as `TODO ring the
/// dentist` would otherwise set a status by being typed, and this screen has a
/// field for the status. The whole line is then read back too, so that what
/// was asked for is what the file will be understood to hold.
fn heading_line(
    level: usize,
    status: Option<TaskType>,
    priority: Option<&str>,
    title: &str,
) -> Result<String, EditError> {
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

    let hashes = "#".repeat(level);
    let bare = format!("{hashes} {title}");
    let plain = parse_heading_line(&bare).is_some_and(|heading| heading.title_start == level + 1);
    if !plain {
        return Err(EditError::Unsupported {
            detail: format!("{title:?} reads as a keyword or a priority, which are set by the fields beside the title rather than typed into it"),
        });
    }

    let keyword = status
        .map(keyword_of)
        .map_or(String::new(), |keyword| format!("{keyword} "));
    let cookie = priority.map_or(String::new(), |value| format!("[#{value}] "));
    let line = format!("{hashes} {keyword}{cookie}{title}");

    let written = parse_heading_line(&line)
        .is_some_and(|heading| heading.level == level && line[heading.title_start..] == *title);
    if !written {
        return Err(EditError::Unsupported {
            detail: format!("{line:?} does not read back as the task it was asked to be"),
        });
    }

    Ok(line)
}
