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
use markdown_org_extract::{
    add_months, parse_timestamp_parts, HolidayCalendar, Repeater, RepeaterType, RepeaterUnit,
    TimestampParts,
};

use crate::document::Document;
use crate::edit::{splice, with_status, EditError, EditOutcome, EditTarget};
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

    let line = document.line(line_index).unwrap_or_default().to_string();
    let moved = parts
        .value
        .checked_add_signed(Duration::days(days as i64))
        .ok_or_else(|| EditError::InvalidDate {
            detail: format!("{} shifted by {days} day(s) is out of range", parts.value),
        })?;

    let rewritten = rewrite_date(&line, &parts, moved);
    document.set(line_index, rewritten.clone());
    document.save()?;

    Ok(EditOutcome {
        line: rewritten,
        changed: true,
    })
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
    let heading_line = document.line(index).unwrap_or_default().to_string();

    let repeating: Vec<_> = planning_lines(&document, index)
        .into_iter()
        .filter(|(_, _, parts)| parts.repeater.is_some())
        .collect();

    if repeating.is_empty() {
        let closed = with_status(&heading_line, &heading, Some(TaskType::Done));
        document.set(index, closed.clone());
        document.save()?;
        return Ok(CompleteOutcome {
            heading: closed,
            repeated: false,
            planning: Vec::new(),
        });
    }

    // Every date is computed before anything is written: a repeater this
    // application cannot advance must leave the file untouched rather than
    // half-moved.
    let mut moved = Vec::with_capacity(repeating.len());
    for (line_index, _, parts) in &repeating {
        let repeater = parts.repeater.as_ref().expect("filtered on Some");
        let next = next_occurrence(parts.value, today, repeater)?;
        let line = document.line(*line_index).unwrap_or_default();
        moved.push((*line_index, rewrite_date(line, parts, next)));
    }

    let planning = moved
        .into_iter()
        .map(|(line_index, line)| {
            document.set(line_index, line.clone());
            line
        })
        .collect();

    // Upstream sets the keyword back to what it was before the task was
    // marked done; with the three keywords this application knows, that is
    // TODO.
    let reopened = with_status(&heading_line, &heading, Some(TaskType::Todo));
    document.set(index, reopened.clone());
    document.save()?;

    Ok(CompleteOutcome {
        heading: reopened,
        repeated: true,
        planning,
    })
}

/// The planning lines directly under the heading at `index`.
///
/// The block ends at the first line that is not one — a blank line, the body,
/// the next heading. Both keywords may be present, in either order, and a
/// file may hold more than one of a kind after a manual edit.
fn planning_lines(
    document: &Document,
    index: usize,
) -> Vec<(usize, PlanningKeyword, TimestampParts)> {
    let mut found = Vec::new();

    for line_index in index + 1..document.len() {
        let line = document.line(line_index).unwrap_or_default();
        let keyword = if line.contains(PlanningKeyword::Scheduled.token()) {
            PlanningKeyword::Scheduled
        } else if line.contains(PlanningKeyword::Deadline.token()) {
            PlanningKeyword::Deadline
        } else {
            break;
        };

        match parse_timestamp_parts(line) {
            Some(parts) => found.push((line_index, keyword, parts)),
            None => break,
        }
    }

    found
}

/// Put `date` into the timestamp `parts` describes, keeping the weekday token
/// in step.
fn rewrite_date(line: &str, parts: &TimestampParts, date: NaiveDate) -> String {
    let with_date = splice(
        line,
        parts.date.clone(),
        &date.format("%Y-%m-%d").to_string(),
    );

    let Some(weekday) = parts.weekday.clone() else {
        return with_date;
    };

    // The date is always ten bytes wide, so the weekday range is unaffected
    // by the splice above and can be reused as it is.
    let written = &line[weekday.clone()];
    splice(&with_date, weekday, &weekday_like(written, date))
}

/// The name of `date`'s weekday, in the language and length of `written`.
///
/// A file written in Russian keeps its Russian weekdays, and an abbreviation
/// stays an abbreviation: the extractor reads either, but a date that comes
/// back in a different language than its neighbours is a visible change the
/// user did not ask for.
fn weekday_like(written: &str, date: NaiveDate) -> String {
    let cyrillic = !written.is_ascii();
    // Abbreviations are two characters in Russian and three in English; every
    // full name is longer.
    let full = written.chars().count() > 3;
    let index = date.weekday().num_days_from_monday() as usize;

    // `Weekday::to_string` is the three-letter English form; the full names
    // are spelled out here because chrono has no unlocalised long form.
    let english = if full {
        FULL_WEEKDAYS[index].to_string()
    } else {
        date.weekday().to_string()
    };

    if !cyrillic {
        return english;
    }

    RU_WEEKDAY_MAPPINGS
        .iter()
        .find(|(_, en)| *en == english)
        .map_or(english, |(ru, _)| (*ru).to_string())
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
fn next_occurrence(
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
