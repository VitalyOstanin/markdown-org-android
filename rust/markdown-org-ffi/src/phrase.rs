//! Filling the creation screen from a sentence.
//!
//! The screen asks for nine things, and a person adding a task knows all of
//! them at once: "позвонить врачу завтра в 15:00, каждую неделю" names a
//! heading, a day, an hour and a repeater in the order they were thought of.
//! The rules that read such a sentence live in the extractor, beside the
//! grammar of the timestamps they produce, so that the phone and the editor
//! understand a phrase the same way. What is here is the boundary crossing.
//!
//! Nothing is written. The fields come back to the screen, which shows them
//! for correction the way it shows fields typed by hand — a phrase the rules
//! misread is a screen to fix, not a file to undo.
//!
//! The draft travels in both directions. A second phrase refines what the
//! first left rather than starting over, and the accumulating is the
//! extractor's to do: the client hands back the draft it holds and gets the
//! refined one, rather than merging fields itself and disagreeing with the
//! rules about which of them a phrase named.

use chrono::{NaiveDate, NaiveTime};
use markdown_org_extract::timestamp::{parse_repeater, parse_timestamp_parts, Repeater};
use markdown_org_extract::{
    parse_heading_line, refine_entry, PhraseEntry, PhraseKeyword, PlanningKind, Priority,
};

use crate::document::Document;
use crate::edit::{with_priority, with_status, EditError, EditOutcome, EditTarget};
use crate::planning::{
    holds_only, keyword_block_end, planning_line, planning_lines, rewrite_date, rewrite_repeater,
    rewrite_time, PlanningKeyword, StampTokens,
};
use crate::TaskType;

/// What the phrases said so far, in the shapes the screen holds them in.
///
/// The same record goes in and comes out: what goes in is what the screen
/// currently shows, so a field the person corrected by hand is the field the
/// next phrase refines. Every field is optional except the heading, which is
/// empty rather than absent — there is no difference between a heading nobody
/// typed and an empty one.
#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct PhraseDraft {
    /// The heading's own text, without a keyword or a priority cookie.
    pub heading: String,
    /// The bare priority (`A`, `12`), without the `[#` `]` framing.
    pub priority: Option<String>,
    /// Which planning line the date belongs on. Travels with the date: the
    /// rules set the two together and neither alone.
    pub keyword: Option<PlanningKeyword>,
    /// `YYYY-MM-DD`.
    pub date: Option<String>,
    /// `HH:MM`.
    pub time: Option<String>,
    /// An org repeater (`+1w`), written the canonical way.
    pub repeater: Option<String>,
    /// The entry's own keyword, which only an edit of one that exists has
    /// anywhere to put. Named `status` rather than `keyword` because that
    /// name is taken here by the planning keyword above.
    pub status: Option<TaskType>,
    /// The fields a phrase said to empty, which is not the same as the fields
    /// it left unnamed: both come back as `None` above.
    pub cleared: Vec<PhraseField>,
}

/// A field a phrase can say to empty.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PhraseField {
    /// The planning date, and the line it stands on.
    Date,
    /// The hour inside the planning timestamp.
    Time,
    /// The repeater inside the planning timestamp.
    Repeater,
    /// The priority cookie on the heading.
    Priority,
}

/// Refine `draft` with one more `phrase`, as of `today`.
///
/// A field the phrase names replaces what was there, a field it does not name
/// keeps its value, and text the rules do not consume is appended to the
/// heading. On an empty draft that is "what is left over becomes the heading".
///
/// `locale` is a comma-separated list of the grammars to consult (`"ru"`,
/// `"en"`, `"ru,en"`), so a phrase in a language that is switched off stays in
/// the heading. `today` is `YYYY-MM-DD`: the rules never read the clock, since
/// "tomorrow" means nothing without saying tomorrow from when, and which day
/// it is where the phone stands is the caller's to answer.
///
/// A draft carrying a field the rules cannot read back — a date that is not a
/// date, an hour that is not an hour, a repeater that spells nothing — is
/// refused with the draft untouched. The screen cannot compose one, but a
/// caller that hand-built it would otherwise have those fields quietly
/// dropped by the next phrase.
#[uniffi::export]
pub fn refine_phrase(
    draft: PhraseDraft,
    phrase: String,
    locale: String,
    today: String,
) -> Result<PhraseDraft, EditError> {
    let reference =
        NaiveDate::parse_from_str(&today, "%Y-%m-%d").map_err(|error| EditError::InvalidDate {
            detail: format!("{today:?}: {error}"),
        })?;

    let refined = refine_entry(entry_of(draft)?, &phrase, &locale, reference);
    Ok(draft_of(refined))
}

/// The draft as the extractor's own entry, or the failure naming the field
/// that could not be read.
fn entry_of(draft: PhraseDraft) -> Result<PhraseEntry, EditError> {
    let mut entry = PhraseEntry::default();
    entry.heading = draft.heading;

    if let Some(value) = draft.priority.as_deref() {
        let priority = Priority::parse(value).ok_or_else(|| EditError::InvalidPriority {
            detail: format!("{value:?} is neither an uppercase letter nor a number in 0..=64"),
        })?;
        entry.priority = Some(priority);
    }
    entry.planning = draft.keyword.map(|keyword| match keyword {
        PlanningKeyword::Scheduled => PlanningKind::Scheduled,
        PlanningKeyword::Deadline => PlanningKind::Deadline,
    });
    if let Some(value) = draft.date.as_deref() {
        let date = NaiveDate::parse_from_str(value, "%Y-%m-%d").map_err(|error| {
            EditError::InvalidDate {
                detail: format!("{value:?}: {error}"),
            }
        })?;
        entry.date = Some(date);
    }
    if let Some(value) = draft.time.as_deref() {
        entry.time = Some(NaiveTime::parse_from_str(value, "%H:%M").map_err(|_| {
            EditError::InvalidDate {
                detail: format!("{value:?} is not a time written HH:MM"),
            }
        })?);
    }
    if let Some(value) = draft.repeater.as_deref() {
        entry.repeater = Some(parse_repeater(value).ok_or_else(|| EditError::Unsupported {
            detail: format!(
                "{value:?} is not a repeater: those are written +1d, ++2w, .+1m, +1wd and the like"
            ),
        })?);
    }
    entry.keyword = draft.status.map(|status| match status {
        TaskType::Todo => PhraseKeyword::Todo,
        TaskType::Done => PhraseKeyword::Done,
        TaskType::Cancelled => PhraseKeyword::Cancelled,
    });
    for field in &draft.cleared {
        match field {
            PhraseField::Date => entry.cleared.date = true,
            PhraseField::Time => entry.cleared.time = true,
            PhraseField::Repeater => entry.cleared.repeater = true,
            PhraseField::Priority => entry.cleared.priority = true,
        }
    }

    Ok(entry)
}

/// The entry in the shapes that cross the boundary.
fn draft_of(entry: PhraseEntry) -> PhraseDraft {
    PhraseDraft {
        heading: entry.heading,
        priority: entry.priority.map(|priority| priority.to_string()),
        keyword: entry.planning.map(|planning| match planning {
            PlanningKind::Scheduled => PlanningKeyword::Scheduled,
            PlanningKind::Deadline => PlanningKeyword::Deadline,
        }),
        date: entry.date.map(|date| date.format("%Y-%m-%d").to_string()),
        time: entry.time.map(|time| time.format("%H:%M").to_string()),
        repeater: entry.repeater.map(|repeater| repeater.canonical()),
        status: entry.keyword.map(|keyword| match keyword {
            PhraseKeyword::Todo => TaskType::Todo,
            PhraseKeyword::Done => TaskType::Done,
            PhraseKeyword::Cancelled => TaskType::Cancelled,
        }),
        cleared: [
            (entry.cleared.date, PhraseField::Date),
            (entry.cleared.time, PhraseField::Time),
            (entry.cleared.repeater, PhraseField::Repeater),
            (entry.cleared.priority, PhraseField::Priority),
        ]
        .into_iter()
        .filter_map(|(cleared, field)| cleared.then_some(field))
        .collect(),
    }
}

/// Apply the fields a phrase named to an entry that already exists.
///
/// One write and one rollback for the whole phrase: "перенеси на пятницу в
/// 16:00 и сделай срочной" moves the date, the hour and the priority, and
/// taking it back is one undo rather than three. The operations underneath are
/// the ones the buttons use — the same rewriting of the heading and of the
/// planning line — so a phrase cannot write a line no button could.
///
/// `draft` is what [`refine_phrase`] answered. Its heading has to be empty:
/// text the rules did not consume has nowhere to go here, and applying the
/// half that was understood would change a field the person did not name. The
/// caller is expected to have said so in its own words; this refuses as well,
/// because a draft that reached here with a leftover is a caller that did not
/// look.
#[uniffi::export]
pub fn apply_phrase(target: EditTarget, draft: PhraseDraft) -> Result<EditOutcome, EditError> {
    if !draft.heading.trim().is_empty() {
        return Err(EditError::Unsupported {
            detail: format!(
                "{:?} was not understood, and an edit has no heading to put it in",
                draft.heading
            ),
        });
    }

    // Everything is parsed before the file is opened: a value the caller
    // mistyped must leave the notes as they were.
    let entry = entry_of(draft)?;
    let cleared = entry.cleared;
    if entry.keyword.is_none()
        && entry.priority.is_none()
        && entry.date.is_none()
        && entry.time.is_none()
        && entry.repeater.is_none()
        && cleared.is_empty()
    {
        return Err(EditError::Unsupported {
            detail: "the phrase named no field to change".to_string(),
        });
    }

    let mut document = Document::open(&target)?;
    let (index, _) = document.heading(&target)?;
    let before = document.text();

    let heading_changed = apply_to_heading(&mut document, index, &entry)?;
    let planning_changed = apply_to_planning(&mut document, index, &entry)?;

    let line = document.at(index).to_string();
    if !heading_changed && !planning_changed {
        // Every field the phrase named already said what the entry says.
        // Reported as an edit that changed nothing rather than as a failure,
        // which is how every other operation answers the same case.
        return Ok(EditOutcome {
            line,
            changed: false,
            rollback: None,
        });
    }

    let rollback = document.saved(before)?;
    Ok(EditOutcome {
        line,
        changed: true,
        rollback: Some(rollback),
    })
}

/// The keyword and the priority cookie, as the phrase named them.
///
/// The line is re-parsed between the two: both are located by byte ranges into
/// the line they were read from, and the first rewrite moves the ranges the
/// second was found at.
fn apply_to_heading(
    document: &mut Document,
    index: usize,
    entry: &PhraseEntry,
) -> Result<bool, EditError> {
    let original = document.at(index).to_string();
    let mut line = original.clone();

    if let Some(keyword) = entry.keyword {
        let heading = parse_heading_line(&line).ok_or_else(|| EditError::Stale {
            detail: format!("{line:?} is not a heading"),
        })?;
        let status = match keyword {
            PhraseKeyword::Todo => TaskType::Todo,
            PhraseKeyword::Done => TaskType::Done,
            PhraseKeyword::Cancelled => TaskType::Cancelled,
        };
        line = with_status(&line, &heading, Some(status));
    }

    let priority = match (&entry.priority, entry.cleared.priority) {
        (Some(priority), _) => Some(priority.to_string()),
        (None, true) => None,
        // Neither named nor emptied: the cookie stays as written.
        (None, false) => {
            return finish_heading(document, index, &original, line);
        }
    };
    let heading = parse_heading_line(&line).ok_or_else(|| EditError::Stale {
        detail: format!("{line:?} is not a heading"),
    })?;
    line = with_priority(&line, &heading, priority.as_deref());

    finish_heading(document, index, &original, line)
}

fn finish_heading(
    document: &mut Document,
    index: usize,
    original: &str,
    line: String,
) -> Result<bool, EditError> {
    if line == original {
        return Ok(false);
    }
    document.set(index, line);
    Ok(true)
}

/// The planning line: emptied, rewritten, or written where there was none.
fn apply_to_planning(
    document: &mut Document,
    index: usize,
    entry: &PhraseEntry,
) -> Result<bool, EditError> {
    let touches = entry.date.is_some()
        || entry.time.is_some()
        || entry.repeater.is_some()
        || entry.planning.is_some()
        || entry.cleared.date
        || entry.cleared.time
        || entry.cleared.repeater;
    if !touches {
        return Ok(false);
    }

    let existing = planning_lines(document, index);
    let keyword = match entry.planning {
        Some(PlanningKind::Scheduled) => PlanningKeyword::Scheduled,
        Some(PlanningKind::Deadline) => PlanningKeyword::Deadline,
        // The kind the entry already uses, so "убрать время" does not have to
        // say which of the two lines it means.
        None => existing
            .first()
            .map_or(PlanningKeyword::Scheduled, |(_, kind, _)| *kind),
    };
    let found = existing.into_iter().find(|(_, kind, _)| *kind == keyword);

    if entry.cleared.date {
        let Some((line_index, _, parts)) = found else {
            // Nothing of that kind to take off: the entry is already where the
            // phrase asked it to be.
            return Ok(false);
        };
        let line = document.at(line_index);
        if !holds_only(line, keyword, &parts) {
            return Err(EditError::Unsupported {
                detail: format!(
                    "{line:?} carries more than the {} timestamp, and is left to be edited by hand",
                    keyword.token()
                ),
            });
        }
        document.remove(line_index);
        return Ok(true);
    }

    match found {
        Some((line_index, _, parts)) => rewrite_planning(document, line_index, parts, entry),
        None => insert_written_planning(document, index, keyword, entry),
    }
}

/// The line the entry has, with the tokens the phrase named replaced.
///
/// Each token is written on its own, and the line is re-parsed between them
/// for the same reason the heading is: the ranges move as the line is rewritten.
fn rewrite_planning(
    document: &mut Document,
    line_index: usize,
    parts: markdown_org_extract::TimestampParts,
    entry: &PhraseEntry,
) -> Result<bool, EditError> {
    let original = document.at(line_index).to_string();
    let mut line = original.clone();
    let mut parts = parts;

    if let Some(date) = entry.date {
        line = rewrite_date(&line, &parts, date)?;
        parts = reparse(&line)?;
    }
    if entry.time.is_some() || entry.cleared.time {
        let time = entry.time.map(|time| time.format("%H:%M").to_string());
        line = rewrite_time(&line, &parts, time.as_deref());
        parts = reparse(&line)?;
    }
    if entry.repeater.is_some() || entry.cleared.repeater {
        let repeater = entry.repeater.as_ref().map(Repeater::canonical);
        line = rewrite_repeater(&line, &parts, repeater.as_deref());
    }

    if line == original {
        return Ok(false);
    }
    document.set(line_index, line);
    Ok(true)
}

/// The pieces of the timestamp the line now carries.
fn reparse(line: &str) -> Result<markdown_org_extract::TimestampParts, EditError> {
    parse_timestamp_parts(line).ok_or_else(|| EditError::Unsupported {
        detail: format!("{line:?} stopped being a timestamp while it was being written"),
    })
}

/// A planning line for an entry that had none.
///
/// The day has to come from the phrase: an hour or a repeater on its own has
/// no date to stand in, and putting it on today would be a day nobody named.
fn insert_written_planning(
    document: &mut Document,
    index: usize,
    keyword: PlanningKeyword,
    entry: &PhraseEntry,
) -> Result<bool, EditError> {
    let Some(date) = entry.date else {
        return Err(EditError::Unsupported {
            detail: "an hour or a repeater needs a day, and neither the phrase nor the entry \
                     names one"
                .to_string(),
        });
    };

    let time = entry.time.map(|time| time.format("%H:%M").to_string());
    let repeater = entry.repeater.as_ref().map(Repeater::canonical);
    let line = planning_line(
        document,
        index,
        keyword,
        date,
        StampTokens {
            time: time.as_deref(),
            repeater: repeater.as_deref(),
        },
    )?;
    let at = keyword_block_end(document, index);
    document.replace_lines(at..at, vec![line]);
    Ok(true)
}
