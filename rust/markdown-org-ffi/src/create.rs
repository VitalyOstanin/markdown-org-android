//! Writing a task that was not in the notes before.
//!
//! Every other operation in this crate finds a heading and changes it. This
//! one has no heading to find: what it is given is what the user typed, and
//! where it goes is a file named in the settings of a collection — the file
//! that receives new tasks.
//!
//! Where in that file is the collection's to say as well, because the two
//! answers are worth different things. The end of the file is the one place a
//! write cannot collide with an edit made elsewhere: the notes live in a git
//! checkout merged line by line, and two devices adding a task to the same
//! file on the same day merge cleanly as long as neither of them rewrites what
//! is above. The start of it is where a task written today is read tomorrow,
//! without scrolling past everything written before it — at the cost of that
//! merge, since two devices then write into the same place.
//!
//! What is written follows the file rather than a house style, the same way
//! [`crate::planning`] writes a date: the heading is written at the level the
//! file writes its tasks at, and a planning line is spelled the way the file
//! spells the ones it already has.

use chrono::NaiveDateTime;
use markdown_org_extract::parse_heading_line;

use crate::document::Document;
use crate::edit::{checked_priority, keyword_of, parse_date, EditError, EditOutcome};
use crate::entry::body_lines;
use crate::occurrence::parse_time;
use crate::planning::{
    checked_repeater, created_line, planning_line, PlanningKeyword, StampTokens,
};
use crate::TaskType;

/// Where in the file that receives it an entry goes.
///
/// Named as a position in the file rather than as an order of entries: what
/// is at the top of a note is not the newest of anything, it is simply what a
/// reader sees first, and a file whose entries were written elsewhere has no
/// order for this to keep.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum WritePosition {
    /// Before the first heading of the file, after everything that stands
    /// above it — an introduction, a property block, a YAML front matter.
    Start,
    /// After everything the file holds.
    End,
}

/// A task to write, as the screen composed it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct NewTask {
    /// Absolute path of the notes directory of the collection.
    pub dir: String,
    /// The file that receives new tasks, relative to `dir`. It is created
    /// when it is not there yet; the directory above it is not.
    pub file: String,
    /// Where in that file the entry goes, as the collection has it set.
    pub at: WritePosition,
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
    /// The moment the entry is being written at, `YYYY-MM-DDTHH:MM`, which is
    /// marked under the heading as org-mode's expiry convention has it.
    /// `None` writes no such line.
    ///
    /// To the minute rather than to the day: entries written the same day are
    /// told apart by when they were written, which a date alone cannot say.
    ///
    /// Taken from the caller rather than read off the clock here, for the
    /// reason every other date in this crate is: the same call has to write
    /// the same file, and a test that could not name the moment would have
    /// nothing to compare against.
    pub created: Option<String>,
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

/// Write a new task into the file that receives them, where it says.
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
        checked_priority(value)?;
    }
    let planning = task
        .planning
        .as_ref()
        .map(|planning| {
            let date = parse_date(&planning.date)?;
            let time = planning.time.as_deref().map(parse_time).transpose()?;
            let repeater = planning
                .repeater
                .as_deref()
                .map(checked_repeater)
                .transpose()?;

            Ok::<_, EditError>((planning.keyword, date, time, repeater))
        })
        .transpose()?;

    let created = task
        .created
        .as_deref()
        .map(|moment| {
            NaiveDateTime::parse_from_str(moment, "%Y-%m-%dT%H:%M").map_err(|error| {
                EditError::InvalidDate {
                    detail: format!("{moment:?}: {error}"),
                }
            })
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
    let placement = placed(&document, task.at, vec![heading.clone()]);
    let index = placement.at + placement.offset;
    document.replace_lines(placement.at..placement.at, placement.lines);

    // What goes under the heading goes under the heading, wherever in the file
    // that turned out to be: the lines below are written by index rather than
    // appended, so an entry written at the start does not scatter its planning
    // line and its body across the end of the file.
    let mut under = index + 1;
    // Above the planning line, which is the order org-mode's expiry convention
    // writes them in and the order `planning` steps over them in: what the
    // entry is is stated before what it is planned for.
    if let Some(moment) = created {
        let line = created_line(&document, index, moment)?;
        document.replace_lines(under..under, vec![line]);
        under += 1;
    }
    if let Some((keyword, date, time, repeater)) = planning {
        let tokens = StampTokens {
            time: time.as_deref(),
            repeater: repeater.as_deref(),
        };
        let line = planning_line(&document, index, keyword, date, tokens)?;
        document.replace_lines(under..under, vec![line]);
        under += 1;
    }
    if !body.is_empty() {
        // A blank line between what an operation writes and what the user
        // typed: without it a paragraph runs on from the planning line above
        // it, and the entry editor's own idea of where a body begins is the
        // line after the last blank one.
        let lines = std::iter::once(String::new()).chain(body).collect();
        document.replace_lines(under..under, lines);
    }

    let rollback = document.saved(before)?;
    Ok(EditOutcome {
        line: heading,
        changed: true,
        rollback: Some(rollback),
    })
}

/// Where a block of lines goes in a file, and the block as it will be spliced.
pub(crate) struct Placement {
    /// The line the block is spliced in at.
    pub(crate) at: usize,
    /// The lines asked for, with a blank separator on whichever side of them
    /// runs into text.
    pub(crate) lines: Vec<String>,
    /// Where the first line asked for sits within [`Placement::lines`]: one
    /// where a separator went in front of it, zero otherwise. The caller
    /// writes the rest of the entry by index, and the index it needs is the
    /// heading's rather than the block's.
    pub(crate) offset: usize,
}

/// Where `lines` go in `document`, with the blank lines that have to stand
/// around them.
///
/// A separator is added on whichever side runs into text and on neither side
/// that does not: an entry written under a file already ending in a blank line
/// gets no second one, and an entry written at the top of an empty file gets
/// none at all. Without it the heading would run into the text above or the
/// heading below, and either one is a file that no longer reads as it did.
pub(crate) fn placed(document: &Document, at: WritePosition, lines: Vec<String>) -> Placement {
    let index = match at {
        WritePosition::Start => header_end(document),
        WritePosition::End => document.len(),
    };
    let above = index > 0 && !document.at(index - 1).trim().is_empty();
    let below = index < document.len() && !document.at(index).trim().is_empty();

    let mut block = Vec::with_capacity(lines.len() + 2);
    if above {
        block.push(String::new());
    }
    block.extend(lines);
    if below {
        block.push(String::new());
    }

    Placement {
        at: index,
        lines: block,
        offset: usize::from(above),
    }
}

/// Where the header of the file ends: the line its first heading stands on.
///
/// The header is whatever a file opens with before its first entry — a title
/// paragraph, a property block, a YAML front matter — and an entry written at
/// the start goes after it rather than above it: a heading put in front of the
/// title of a note would read as a note of its own, and a note whose first
/// line stops being its front matter is one no reader of it parses.
///
/// A file with no heading at all is header to the end: the entry goes after
/// everything, which is where the end of the file is too.
pub(crate) fn header_end(document: &Document) -> usize {
    (front_matter_end(document)..document.len())
        .find(|index| parse_heading_line(document.at(*index)).is_some())
        .unwrap_or_else(|| document.len())
}

/// The line after a YAML front matter, or the top of the file where there is
/// none.
///
/// Stepped over rather than searched, because a comment inside one begins
/// with `#` at the start of a line and reads as a heading: an entry written
/// above such a comment would land inside the front matter. A block that was
/// opened and never closed is not one, and the file is read from the top.
fn front_matter_end(document: &Document) -> usize {
    let opens = document.len() > 0 && document.at(0).trim_end() == FRONT_MATTER;
    if !opens {
        return 0;
    }

    (1..document.len())
        .find(|index| document.at(*index).trim_end() == FRONT_MATTER)
        .map_or(0, |index| index + 1)
}

/// The fence a YAML front matter is written between.
const FRONT_MATTER: &str = "---";

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
