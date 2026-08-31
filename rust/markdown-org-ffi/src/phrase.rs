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
use markdown_org_extract::timestamp::parse_repeater;
use markdown_org_extract::{refine_entry, PhraseEntry, PlanningKind, Priority};

use crate::edit::EditError;
use crate::planning::PlanningKeyword;

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
    let reference = NaiveDate::parse_from_str(&today, "%Y-%m-%d").map_err(|error| {
        EditError::InvalidDate {
            detail: format!("{today:?}: {error}"),
        }
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
        entry.priority = Some(
            Priority::parse(value).ok_or_else(|| EditError::InvalidPriority {
                detail: format!("{value:?} is neither an uppercase letter nor a number in 0..=64"),
            })?,
        );
    }
    entry.planning = draft.keyword.map(|keyword| match keyword {
        PlanningKeyword::Scheduled => PlanningKind::Scheduled,
        PlanningKeyword::Deadline => PlanningKind::Deadline,
    });
    if let Some(value) = draft.date.as_deref() {
        entry.date = Some(NaiveDate::parse_from_str(value, "%Y-%m-%d").map_err(|error| {
            EditError::InvalidDate {
                detail: format!("{value:?}: {error}"),
            }
        })?);
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
    }
}
