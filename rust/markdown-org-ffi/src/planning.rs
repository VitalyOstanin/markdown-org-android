//! Moving a planning date, and completing a task that repeats.
//!
//! The repeater rules follow upstream Emacs Org-mode `org-auto-repeat-maybe`
//! (lisp/org.el), read rather than recalled:
//!
//! * a bare `+N` takes exactly one step from the date in the file, even when
//!   the result is still in the past;
//! * `++N` keeps stepping until it passes today, taking at least one step;
//! * `.+N` restarts from today.
//!
//! Marking a repeating task done therefore moves its dates forward and leaves
//! the keyword open, which is why completing a task is one operation here
//! rather than a status change the interface composes itself.
//!
//! Two documented divergences from upstream, both in the direction of leaving
//! the user's file alone:
//!
//! * upstream deletes a `SCHEDULED` line that carries no repeater when the
//!   task repeats elsewhere; this application keeps it;
//! * upstream also shifts plain timestamps in the task's body; this
//!   application only moves the planning lines under the heading.

use chrono::{Datelike, Duration, NaiveDate};
use markdown_org_extract::locale::RU_WEEKDAY_MAPPINGS;
use markdown_org_extract::timestamp::parse_repeater;
use markdown_org_extract::{
    add_months, parse_heading_line, parse_timestamp_parts, HolidayCalendar, Repeater, RepeaterType,
    RepeaterUnit, TimestampParts,
};

use crate::document::Document;
use crate::edit::{splice, with_status, write_line, EditError, EditOutcome, EditTarget};
use crate::occurrence::{fields, is_time, parse_time};
use crate::undo::FileRollback;
use crate::TaskType;

/// Which planning line an operation applies to.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PlanningKeyword {
    /// `SCHEDULED:` — when work on the task starts.
    Scheduled,
    /// `DEADLINE:` — when the task is due.
    Deadline,
}

impl PlanningKeyword {
    fn token(self) -> &'static str {
        match self {
            PlanningKeyword::Scheduled => "SCHEDULED:",
            PlanningKeyword::Deadline => "DEADLINE:",
        }
    }
}

/// The repeater `value` spells, written the canonical way, or `None` where it
/// spells none.
///
/// Offered to the interface so a repeater typed by hand can be answered
/// before it is written: the screen that takes one has a field the user fills
/// in, and a field that only reports its mistake once the task has been
/// created reports it too late. What comes back is what would go into the
/// file, so `+007d` shown back as `+7d` is the answer as well as the check.
#[uniffi::export]
pub fn canonical_repeater(value: String) -> Option<String> {
    parse_repeater(&value).map(|repeater| repeater.canonical())
}

/// The same, as the writers take it: canonical or refused.
pub(crate) fn checked_repeater(value: &str) -> Result<String, EditError> {
    canonical_repeater(value.to_string()).ok_or_else(|| EditError::Unsupported {
        detail: format!(
            "{value:?} is not a repeater: those are written +1d, ++2w, .+1m, +1wd and the like"
        ),
    })
}

/// What completing a task did.
#[derive(Debug, Clone, uniffi::Record)]
pub struct CompleteOutcome {
    /// The heading line as it now stands.
    pub heading: String,
    /// The task repeats, so it moved forward and stayed open instead of
    /// being closed.
    pub repeated: bool,
    /// The planning lines that moved, as they now stand.
    pub planning: Vec<String>,
    /// What the file held before and holds after, to hand
    /// [`crate::revert_files`] if the completion is taken back.
    pub rollback: Option<FileRollback>,
}

/// Move a planning date by whole days.
///
/// Everything else in the timestamp — the time, the repeater, the warning
/// cookie — stays as written; the weekday is rewritten to match the new date
/// in the language and length the file already uses.
#[uniffi::export]
pub fn shift_planning(
    target: EditTarget,
    keyword: PlanningKeyword,
    days: i32,
) -> Result<EditOutcome, EditError> {
    let mut document = Document::open(&target)?;
    let (index, _) = document.heading(&target)?;

    let found = planning_lines(&document, index)
        .into_iter()
        .find(|(_, kind, _)| *kind == keyword);
    let (line_index, _, parts) = found.ok_or_else(|| EditError::NoPlanningLine {
        detail: format!("{} has no {}", target.heading, keyword.token()),
    })?;

    let line = document.at(line_index).to_string();
    let moved = parts
        .value
        .checked_add_signed(Duration::days(days as i64))
        .ok_or_else(|| EditError::InvalidDate {
            // `day(s)` reads as a placeholder for the form nobody picked;
            // this detail is diagnostics and stays in English, so English is
            // what it is written in properly.
            detail: format!(
                "{} shifted by {days} {} is out of range",
                parts.value,
                if days.abs() == 1 { "day" } else { "days" },
            ),
        })?;

    let rewritten = rewrite_date(&line, &parts, moved)?;
    write_line(&mut document, line_index, rewritten)
}

/// Put a planning date on a task, or take one off.
///
/// `date` is `YYYY-MM-DD`, and `None` removes the line. The two are one
/// operation because they are one question on screen — which day this is
/// planned for, if any — and because a date removed and put back has to land
/// where the first one was.
///
/// Where an existing line is rewritten, only its date changes: the time, the
/// repeater and the warning cookie stay as written, exactly as a shift leaves
/// them. A line that has to be written from nothing follows the file rather
/// than a house style — see [`sample_planning`].
#[uniffi::export]
pub fn set_planning(
    target: EditTarget,
    keyword: PlanningKeyword,
    date: Option<String>,
) -> Result<EditOutcome, EditError> {
    // Parsed before the file is opened: a date the caller mistyped must leave
    // the notes as they were.
    let date = date
        .map(|date| {
            NaiveDate::parse_from_str(&date, "%Y-%m-%d").map_err(|error| EditError::InvalidDate {
                detail: format!("{date:?}: {error}"),
            })
        })
        .transpose()?;

    let mut document = Document::open(&target)?;
    let (index, _) = document.heading(&target)?;

    let found = planning_lines(&document, index)
        .into_iter()
        .find(|(_, kind, _)| *kind == keyword);

    match (date, found) {
        (Some(date), Some((line_index, _, parts))) => {
            let line = document.at(line_index).to_string();
            let rewritten = rewrite_date(&line, &parts, date)?;
            write_line(&mut document, line_index, rewritten)
        }
        (Some(date), None) => insert_planning(&mut document, index, keyword, date),
        (None, Some((line_index, _, parts))) => {
            remove_planning(&mut document, line_index, keyword, &parts)
        }
        // Nothing to take off. Reported as an edit that changed nothing rather
        // than as a failure: the task already has no date of that kind, which
        // is what the caller asked for.
        (None, None) => Ok(EditOutcome {
            line: String::new(),
            changed: false,
            rollback: None,
        }),
    }
}

/// Put an hour on a planning date, or take one off.
///
/// `time` is `HH:MM`, or `HH:MM-HH:MM` where the entry is held between two
/// times, and `None` takes the hour off and leaves the day. Separate from
/// [`set_planning`] because the two answer different questions — which day,
/// and at what hour of it — and because an hour can only be put on a date
/// that already exists: an hour is a token inside a timestamp, and a task
/// with no planning line has no timestamp to put it in.
///
/// Everything else the line carries is left as written: the date, the
/// weekday in whatever language it was spelled, the repeater, the warning
/// cookie, the keyword and the framing around them. Where the timestamp had
/// no hour, the new one goes after the weekday, or after the date where there
/// is no weekday, which is where org-mode reads it from.
#[uniffi::export]
pub fn set_planning_time(
    target: EditTarget,
    keyword: PlanningKeyword,
    time: Option<String>,
) -> Result<EditOutcome, EditError> {
    // Parsed before the file is opened: a time the caller mistyped must leave
    // the notes as they were.
    let time = time.as_deref().map(parse_time).transpose()?;

    let mut document = Document::open(&target)?;
    let (index, _) = document.heading(&target)?;

    let (line_index, _, parts) = planning_lines(&document, index)
        .into_iter()
        .find(|(_, kind, _)| *kind == keyword)
        .ok_or_else(|| EditError::Unsupported {
            detail: format!(
                "{} carries no {} date, and an hour is written into a date",
                target.heading,
                keyword.token().trim_end_matches(':'),
            ),
        })?;

    let line = document.at(line_index).to_string();
    let rewritten = rewrite_time(&line, &parts, time.as_deref());
    write_line(&mut document, line_index, rewritten)
}

/// The line with its hour replaced, added or taken off.
///
/// The tokens are located inside the brackets and edited from the end, so a
/// replacement of a different width cannot move the range the next one was
/// found at. Removing takes the whitespace ahead of the token with it, which
/// is what keeps a line from coming back with two spaces where it had one.
fn rewrite_time(line: &str, parts: &TimestampParts, time: Option<&str>) -> String {
    let fields = fields(line, parts.whole.clone());
    let written = fields
        .iter()
        .position(|field| is_time(&line[field.clone()]));

    let edit = match (time, written) {
        (Some(time), Some(at)) => (fields[at].clone(), time.to_string()),
        (Some(time), None) => {
            let after = parts
                .weekday
                .clone()
                .unwrap_or_else(|| parts.date.clone())
                .end;
            (after..after, format!(" {time}"))
        }
        (None, Some(at)) => {
            // A time never stands first — the date does — so there is always a
            // token before it, and the check is for the indexing rather than
            // for the file.
            let from = if at > 0 {
                fields[at - 1].end
            } else {
                fields[at].start
            };
            (from..fields[at].end, String::new())
        }
        (None, None) => return line.to_string(),
    };

    splice(line, edit.0, &edit.1)
}

/// Mark a task done, or move it to its next occurrence when it repeats.
///
/// `today` is `YYYY-MM-DD` and comes from the caller rather than the clock,
/// the same contract the extractor follows for the agenda: the same input has
/// to produce the same file.
#[uniffi::export]
pub fn complete_task(target: EditTarget, today: String) -> Result<CompleteOutcome, EditError> {
    let today =
        NaiveDate::parse_from_str(&today, "%Y-%m-%d").map_err(|error| EditError::InvalidDate {
            detail: format!("{today:?}: {error}"),
        })?;

    let mut document = Document::open(&target)?;
    let (index, heading) = document.heading(&target)?;
    let heading_line = document.at(index).to_string();

    let repeating: Vec<_> = planning_lines(&document, index)
        .into_iter()
        .filter(|(_, _, parts)| parts.repeater.is_some())
        .collect();

    if repeating.is_empty() {
        let closed = with_status(&heading_line, &heading, Some(TaskType::Done));
        let before = document.text();
        document.set(index, closed.clone());
        let rollback = document.saved(before)?;
        return Ok(CompleteOutcome {
            heading: closed,
            repeated: false,
            planning: Vec::new(),
            rollback: Some(rollback),
        });
    }

    // Every date is computed before anything is written: a repeater this
    // application cannot advance must leave the file untouched rather than
    // half-moved.
    let mut moved = Vec::with_capacity(repeating.len());
    for (line_index, _, parts) in &repeating {
        let repeater = parts.repeater.as_ref().expect("filtered on Some");
        let next = next_occurrence(parts.value, today, repeater)?;
        let line = document.at(*line_index);
        moved.push((*line_index, rewrite_date(line, parts, next)?));
    }

    let before = document.text();

    // A loop rather than a `map` that writes as it goes: the write is the
    // point, and a lazy adapter leaves it to whoever consumes the chain.
    let mut planning = Vec::with_capacity(moved.len());
    for (line_index, line) in moved {
        document.set(line_index, line.clone());
        planning.push(line);
    }

    // Upstream sets the keyword back to what it was before the task was
    // marked done; with the three keywords this application knows, that is
    // TODO. A heading that carries no keyword keeps carrying none — upstream
    // adds no keyword to such a line either, and the user asked for the date
    // to move, not for the heading to become a task.
    let reopened = match heading.status {
        Some(_) => with_status(&heading_line, &heading, Some(TaskType::Todo)),
        None => heading_line.clone(),
    };
    document.set(index, reopened.clone());
    let rollback = document.saved(before)?;

    Ok(CompleteOutcome {
        heading: reopened,
        repeated: true,
        planning,
        rollback: Some(rollback),
    })
}

/// The planning lines belonging to the heading at `index`.
///
/// The whole section is searched — up to the next heading, or to the end of
/// the file — rather than only the lines immediately below. The extractor
/// takes a timestamp from any paragraph of the section, so a blank line or a
/// `CREATED:` line between the heading and the planning line leaves the date
/// on screen; stopping at the first line that is not a planning line would
/// leave the edit unable to find what the agenda is showing.
///
/// Both keywords may be present, in either order, and a file may hold more
/// than one of a kind after a manual edit.
pub(crate) fn planning_lines(
    document: &Document,
    index: usize,
) -> Vec<(usize, PlanningKeyword, TimestampParts)> {
    let mut found = Vec::new();

    for line_index in index + 1..document.len() {
        let line = document.at(line_index);
        if parse_heading_line(line).is_some() {
            break;
        }

        let Some(keyword) = planning_keyword(line) else {
            continue;
        };
        if let Some(parts) = parse_timestamp_parts(line) {
            found.push((line_index, keyword, parts));
        }
    }

    found
}

/// The line with the indentation and the inline-code framing taken off.
///
/// What is left is where a keyword is looked for. Shared with the entry
/// editor, which recognises the same lines to keep them out of the body it
/// hands over for editing.
pub(crate) fn bare_start(line: &str) -> &str {
    line.trim_start().trim_start_matches('`').trim_start()
}

/// The planning keyword `line` begins with, if it is a planning line.
///
/// Anchored at the start of the line, as the extractor anchors it: a body
/// that merely mentions the other keyword — `` `DEADLINE: <...>` — agreed,
/// see SCHEDULED: in the ticket `` — would otherwise be read as a
/// `SCHEDULED` line while its timestamp comes from the deadline, and the
/// wrong date would move.
///
/// Leading backticks are skipped because that is how these lines are written
/// in the notes: the extractor reads the timestamp out of an inline-code
/// span, and it sees the literal without the framing this does.
pub(crate) fn planning_keyword(line: &str) -> Option<PlanningKeyword> {
    let start = bare_start(line);

    if start.starts_with(PlanningKeyword::Scheduled.token()) {
        Some(PlanningKeyword::Scheduled)
    } else if start.starts_with(PlanningKeyword::Deadline.token()) {
        Some(PlanningKeyword::Deadline)
    } else {
        None
    }
}

/// The closing date org-mode writes when a task is finished.
///
/// One of the keyword lines that sit under a heading, and structural like the
/// planning lines: written by an operation rather than typed. Shared with the
/// entry editor, which keeps such lines out of the body it hands over.
pub(crate) const CLOSED: &str = "CLOSED:";

/// The date org-mode's expiry convention writes when an entry is created.
/// Never edited here, only stepped over: it stands in the same block the
/// planning lines do.
const CREATED: &str = "CREATED:";

/// Where the block of keyword lines under the heading at `index` ends.
///
/// Where a line written by an operation rather than typed belongs: a
/// `SCHEDULED` added to a task carrying `CREATED` and `DEADLINE` joins that
/// block instead of splitting it, and so does the property block an exception
/// is written into.
pub(crate) fn keyword_block_end(document: &Document, index: usize) -> usize {
    let mut at = index + 1;
    while at < document.len() && keyword_line(document.at(at)) {
        at += 1;
    }

    at
}

/// Whether the line is one of the keyword lines written under a heading,
/// rather than the text of the entry.
fn keyword_line(line: &str) -> bool {
    let start = bare_start(line);
    planning_keyword(line).is_some() || start.starts_with(CLOSED) || start.starts_with(CREATED)
}

/// Write a planning line the entry did not have, and save the file.
///
/// It goes below the keyword lines already under the heading rather than
/// directly under it, so a `SCHEDULED` added to a task that carries `CREATED`
/// and `DEADLINE` joins that block instead of splitting it.
fn insert_planning(
    document: &mut Document,
    index: usize,
    keyword: PlanningKeyword,
    date: NaiveDate,
) -> Result<EditOutcome, EditError> {
    let line = planning_line(document, index, keyword, date, StampTokens::default())?;
    let at = keyword_block_end(document, index);

    let before = document.text();
    document.replace_lines(at..at, vec![line.clone()]);
    let rollback = document.saved(before)?;

    Ok(EditOutcome {
        line,
        changed: true,
        rollback: Some(rollback),
    })
}

/// What a new timestamp carries besides its date.
///
/// Both empty for a date put on a task that already exists: that operation is
/// asked for a day and leaves the rest of the line as written. A task being
/// created has no line to leave alone, so the screen that composes it can say
/// what hour the entry is held at and whether it repeats.
#[derive(Debug, Clone, Copy, Default)]
pub(crate) struct StampTokens<'a> {
    /// `HH:MM`, or `HH:MM-HH:MM` for an entry held between two times.
    pub time: Option<&'a str>,
    /// An org repeater as it is written, `++1w`.
    pub repeater: Option<&'a str>,
}

/// The planning line to write, spelled the way this file spells the ones it
/// already has.
///
/// Shared with [`crate::create`], which writes one under a heading it has just
/// added: a date on a new task follows the file exactly as a date put on an
/// old one does.
pub(crate) fn planning_line(
    document: &Document,
    index: usize,
    keyword: PlanningKeyword,
    date: NaiveDate,
    tokens: StampTokens<'_>,
) -> Result<String, EditError> {
    // The same bound `rewrite_date` keeps: a year outside four digits is
    // written by chrono in a form no reader of these files accepts.
    if !(1000..=9999).contains(&date.year()) {
        return Err(EditError::InvalidDate {
            detail: format!("{date} is outside the four-digit years timestamps are written in"),
        });
    }

    let sample = sample_planning(document, index);
    let indent = sample.as_ref().map_or("", |(line, _)| {
        &line[..line.len() - line.trim_start().len()]
    });
    // Framed in inline code, which is how these lines are written in markdown
    // notes: the timestamp is not a link and the backticks keep a renderer
    // from making one of it. A file that writes them bare keeps doing so.
    let fenced = sample
        .as_ref()
        .is_none_or(|(line, _)| line.trim_start().starts_with('`'));

    let weekday = match &sample {
        // A file that writes its dates without a weekday goes on without one.
        Some((line, parts)) => parts.weekday.clone().map(|range| {
            // A weekday this application cannot rewrite -- a language neither
            // of the two -- is no reason to refuse a date it was not asked to
            // touch, so the canonical name is written instead.
            weekday_like(&line[range], date).unwrap_or_else(|_| date.weekday().to_string())
        }),
        None => Some(date.weekday().to_string()),
    };

    // The order org writes them in: the date, then the weekday that names it,
    // then the hour it is held at, and the repeater last.
    let written = [
        Some(date.format("%Y-%m-%d").to_string()),
        weekday,
        tokens.time.map(str::to_string),
        tokens.repeater.map(str::to_string),
    ];
    let stamp = format!(
        "<{}>",
        written.into_iter().flatten().collect::<Vec<_>>().join(" ")
    );
    let body = format!("{} {stamp}", keyword.token());

    Ok(if fenced {
        format!("{indent}`{body}`")
    } else {
        format!("{indent}{body}")
    })
}

/// A planning line to copy the spelling of: the entry's own first, then the
/// first one anywhere in the file.
///
/// The entry first because that is what the new line will stand beside; the
/// file after it because a note written in Russian, or without weekdays, or
/// without the inline-code framing, is written that way throughout. A file
/// with no planning line at all leaves nothing to follow, and the caller
/// writes the canonical form.
fn sample_planning(document: &Document, index: usize) -> Option<(String, TimestampParts)> {
    let own = planning_lines(document, index)
        .into_iter()
        .next()
        .map(|(line_index, _, parts)| (document.at(line_index).to_string(), parts));
    if own.is_some() {
        return own;
    }

    (0..document.len()).find_map(|line_index| {
        let line = document.at(line_index);
        planning_keyword(line)?;
        parse_timestamp_parts(line).map(|parts| (line.to_string(), parts))
    })
}

/// Take a planning line out of the file, and save it.
///
/// Only a line that carries this timestamp and nothing else is removed. A
/// line holding both keywords at once -- which a manual edit can leave behind
/// -- is refused rather than half-read: the operations here locate a keyword
/// at the start of a line, so cutting one out of such a line would take the
/// other's date with it.
fn remove_planning(
    document: &mut Document,
    line_index: usize,
    keyword: PlanningKeyword,
    parts: &TimestampParts,
) -> Result<EditOutcome, EditError> {
    let line = document.at(line_index);
    if !holds_only(line, keyword, parts) {
        return Err(EditError::Unsupported {
            detail: format!(
                "{line:?} carries more than the {} timestamp, and is left to be edited by hand",
                keyword.token()
            ),
        });
    }

    let before = document.text();
    document.remove(line_index);
    let rollback = document.saved(before)?;

    Ok(EditOutcome {
        line: String::new(),
        changed: true,
        rollback: Some(rollback),
    })
}

/// Whether the line holds the keyword, its timestamp and nothing else worth
/// keeping -- indentation and the inline-code framing aside.
fn holds_only(line: &str, keyword: PlanningKeyword, parts: &TimestampParts) -> bool {
    let bare = bare_start(line);
    let after_keyword = line.len() - bare.len() + keyword.token().len();

    let between = line.get(after_keyword..parts.whole.start);
    let tail = line.get(parts.whole.end..);

    between.is_some_and(|gap| gap.chars().all(char::is_whitespace))
        && tail.is_some_and(|tail| tail.trim().trim_end_matches('`').trim().is_empty())
}

/// Put `date` into the timestamp `parts` describes, keeping the weekday token
/// in step.
///
/// The two tokens are written right to left — the weekday first, then the
/// date — so a replacement of a different width cannot move the range the
/// other one was located by.
pub(crate) fn rewrite_date(
    line: &str,
    parts: &TimestampParts,
    date: NaiveDate,
) -> Result<String, EditError> {
    // Anything outside four digits comes back from chrono signed and of
    // another width (`+10021-04-01`), which no reader of these files accepts
    // — and the caller would be writing it into the user's notes.
    if !(1000..=9999).contains(&date.year()) {
        return Err(EditError::InvalidDate {
            detail: format!("{date} is outside the four-digit years timestamps are written in"),
        });
    }

    let with_weekday = match parts.weekday.clone() {
        Some(weekday) => {
            let written = &line[weekday.clone()];
            splice(line, weekday, &weekday_like(written, date)?)
        }
        None => line.to_string(),
    };

    Ok(splice(
        &with_weekday,
        parts.date.clone(),
        &date.format("%Y-%m-%d").to_string(),
    ))
}

/// The name of `date`'s weekday, written the way `written` is.
///
/// A file written in Russian keeps its Russian weekdays, an abbreviation
/// stays an abbreviation and a lowercase token stays lowercase: the extractor
/// reads any of these, but a date that comes back spelled differently from
/// its neighbours is a visible change the user did not ask for.
///
/// A token in neither of the two languages the ecosystem knows — Ukrainian
/// `Нд`, Greek `Δευ` — is refused rather than replaced with a Russian or
/// English name, for the same reason.
pub(crate) fn weekday_like(written: &str, date: NaiveDate) -> Result<String, EditError> {
    let (language, full) = language_of(written).ok_or_else(|| EditError::Unsupported {
        detail: format!("{written:?} is not a weekday name this application can rewrite"),
    })?;

    let index = date.weekday().num_days_from_monday() as usize;
    // `Weekday::to_string` is the three-letter English form; the full names
    // are spelled out here because chrono has no unlocalised long form.
    let english = if full {
        FULL_WEEKDAYS[index].to_string()
    } else {
        date.weekday().to_string()
    };

    let name = match language {
        Language::English => english,
        Language::Russian => RU_WEEKDAY_MAPPINGS
            .iter()
            .find(|(_, en)| *en == english)
            .map_or(english, |(ru, _)| (*ru).to_string()),
    };

    // A file that spells its weekdays in lowercase keeps doing so. Anything
    // else — capitalised, upper case — is written the canonical way, which is
    // what it already was.
    Ok(if written.chars().all(|c| !c.is_uppercase()) {
        name.to_lowercase()
    } else {
        name
    })
}

/// Which of the two languages a weekday token is written in, and whether it
/// is the full name rather than an abbreviation.
///
/// Matched case-insensitively against the same tables the extractor reads
/// with, so every token that reaches the agenda can also be rewritten.
fn language_of(written: &str) -> Option<(Language, bool)> {
    let token = written.to_lowercase();

    if let Some((russian, _)) = RU_WEEKDAY_MAPPINGS
        .iter()
        .find(|(ru, _)| ru.to_lowercase() == token)
    {
        // The table pairs full names with full names and abbreviations with
        // abbreviations, so the length of the entry says which this is.
        return Some((Language::Russian, russian.chars().count() > 3));
    }

    if FULL_WEEKDAYS
        .iter()
        .any(|name| name.to_lowercase() == token)
    {
        return Some((Language::English, true));
    }

    let abbreviated = FULL_WEEKDAYS
        .iter()
        .any(|name| name[..3].to_lowercase() == token);
    abbreviated.then_some((Language::English, false))
}

/// The languages weekday names are read and written in, matching the
/// extractor's `SUPPORTED_LOCALES`.
#[derive(Debug, Clone, Copy)]
enum Language {
    Russian,
    English,
}

/// Monday-first, matching `Weekday::num_days_from_monday`.
const FULL_WEEKDAYS: [&str; 7] = [
    "Monday",
    "Tuesday",
    "Wednesday",
    "Thursday",
    "Friday",
    "Saturday",
    "Sunday",
];

/// The date a repeating task moves to when it is completed.
pub(crate) fn next_occurrence(
    base: NaiveDate,
    today: NaiveDate,
    repeater: &Repeater,
) -> Result<NaiveDate, EditError> {
    if repeater.unit == RepeaterUnit::Hour {
        // Upstream shifts the clock time by N hours, which needs a time in
        // the timestamp and time arithmetic this application does not do yet.
        return Err(EditError::Unsupported {
            detail: "an hourly repeater cannot be advanced from this application yet".to_string(),
        });
    }

    match repeater.repeater_type {
        RepeaterType::Cumulative => step(base, repeater),
        RepeaterType::Restart => step(today, repeater),
        RepeaterType::CatchUp => {
            // At least one step, then as many as it takes to pass today —
            // upstream's loop, which always runs its body once.
            let mut date = step(base, repeater)?;
            let mut guard = 0;
            while date <= today {
                date = step(date, repeater)?;
                guard += 1;
                if guard > CATCH_UP_LIMIT {
                    return Err(EditError::Unsupported {
                        detail: format!(
                            "{} repeats of {} do not reach {today}",
                            CATCH_UP_LIMIT,
                            repeater.canonical()
                        ),
                    });
                }
            }
            Ok(date)
        }
    }
}

/// How many repeats a catch-up may take before the timestamp is treated as
/// broken. A daily repeater covers a century well inside this.
const CATCH_UP_LIMIT: u32 = 100_000;

/// One repeater interval after `date`.
fn step(date: NaiveDate, repeater: &Repeater) -> Result<NaiveDate, EditError> {
    let value = repeater.value;
    let moved = match repeater.unit {
        RepeaterUnit::Day => date.checked_add_signed(Duration::days(value as i64)),
        RepeaterUnit::Week => date.checked_add_signed(Duration::days(value as i64 * 7)),
        RepeaterUnit::Month => add_months(date, value as i32),
        RepeaterUnit::Year => add_months(date, value as i32 * 12),
        RepeaterUnit::Workday => {
            Some(HolidayCalendar::global().nth_workday_after(date, value as u64))
        }
        // Handled by the caller, which refuses before reaching here.
        RepeaterUnit::Hour => None,
    };

    moved.ok_or_else(|| EditError::InvalidDate {
        detail: format!("{date} plus {} is out of range", repeater.canonical()),
    })
}
