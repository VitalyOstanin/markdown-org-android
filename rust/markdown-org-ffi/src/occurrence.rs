//! Exceptions to a repeating entry: an occurrence that is gone, and one that
//! moved.
//!
//! A repeating timestamp describes an endless series and has nowhere to say
//! that one of its occurrences is different. Both answers are written into
//! the entry itself, with the `org-properties` keys of the extractor's
//! ADR-0020 and the `MOVED` line of its ADR-0038:
//!
//! ````text
//! # TODO English
//! `SCHEDULED: <2026-08-06 Thu 15:00 +1w>`
//! `MOVED: [2026-08-20 Thu] -> <2026-08-22 Sat 18:00>` <- an occurrence that moved
//! ```org-properties
//! EXDATE: 2026-08-13                              <- an occurrence that is gone
//! ```
//! ````
//!
//! The two are not the same operation and are not written the same way. A
//! cancelled occurrence is a date added to the series' own `EXDATE`; a moved
//! one is a line naming the day it left and the timestamp it is held on
//! instead, and it needs no `EXDATE` beside it — an occurrence that moved is
//! not one that is gone, and the extractor reads it that way.
//!
//! The line stands in the entry the series is written in, which is where the
//! reader looks for it. The shape ADR-0031 wrote a move in — a second entry
//! at the end of the file carrying `SERIES_ID` and `RECURRENCE_ID` — is still
//! read, because files and other tools hold it: an occurrence standing in
//! such an entry is moved where it stands rather than answered with a `MOVED`
//! line here, so that only one of the two ever speaks for a day.
//!
//! (ADR numbers run independently in the two repositories and have already
//! collided on this subject: 0011 here is where "today" comes from, 0011 in
//! the extractor is the release commit format. Every cross-project reference
//! in this file names the project it belongs to.)

use std::ops::Range;

use chrono::{NaiveDate, NaiveTime};
use markdown_org_extract::timestamp::parse_repeater;
use markdown_org_extract::{parse_heading_line, TimestampParts};

use crate::document::Document;
use crate::edit::{parse_date, splice, EditError, EditOutcome, EditTarget};
use crate::planning::{bare_start, keyword_block_end, planning_lines, weekday_like, MOVED};

/// Property key listing the occurrences a series does not have.
const EXDATE: &str = "EXDATE";
/// Property key naming the occurrence an entry replaces.
const RECURRENCE_ID: &str = "RECURRENCE_ID";
/// Property key naming the series an entry replaces an occurrence of.
const SERIES_ID: &str = "SERIES_ID";
/// Property key holding an entry's own stable identifier.
const ID: &str = "ID";

/// The info string of the fenced block these keys are written in.
///
/// Spelled here rather than taken from the extractor because the version this
/// crate pins does not export it yet; it comes from there once the release
/// carrying the extractor's ADR-0031 is the one pinned.
const PROPERTIES: &str = "org-properties";

/// Take one occurrence out of a repeating entry.
///
/// The date joins the entry's `EXDATE`, which is written into its property
/// block — created under the planning lines when the entry has none. The
/// series itself is not touched: it goes on repeating, and the agenda leaves
/// out the one day.
///
/// Cancelling a date the series does not fall on is not refused. Whether a
/// given date is an occurrence is the repeater's answer, and the caller is
/// the agenda, which asks about a day it drew the series on; a date that is
/// not one leaves an `EXDATE` that suppresses nothing.
#[uniffi::export]
pub fn cancel_occurrence(target: EditTarget, date: String) -> Result<EditOutcome, EditError> {
    let date = parse_date(&date)?;

    let mut document = Document::open(&target)?;
    let (index, _) = document.heading(&target)?;
    repeating_line(&document, index, &target)?;

    let section = section(&document, index);
    let written = property(&document, section, EXDATE);
    let mut dates: Vec<String> = written
        .as_ref()
        .map(|(_, value)| value.split([',', ' ', '\t']).filter(|f| !f.is_empty()))
        .map(|fields| fields.map(str::to_string).collect())
        .unwrap_or_default();

    let text = date.format("%Y-%m-%d").to_string();
    if dates.contains(&text) {
        return Ok(EditOutcome {
            line: written.map_or(String::new(), |(line, _)| document.at(line).to_string()),
            changed: false,
            rollback: None,
        });
    }
    dates.push(text);

    let before = document.text();
    let line = set_property(&mut document, index, EXDATE, &dates.join(", "));
    let rollback = document.saved(before)?;

    Ok(EditOutcome {
        line,
        changed: true,
        rollback: Some(rollback),
    })
}

/// Move one occurrence of a repeating entry to another date, another time, or
/// both.
///
/// The series stays as it is: what is written is a `MOVED` line of the entry
/// (the extractor's ADR-0038), naming the occurrence before the arrow and
/// where it is held after it. The occurrence is then not drawn on the day the
/// repeater names, so nothing has to be excluded as well.
///
/// The line is written the way the planning line above it is: an inline-code
/// span, at its indentation, with the weekday spelt as that line spells it.
/// It carries no repeater and no warning cookie — one occurrence does not
/// repeat, and how far ahead a deadline warns belongs to the series.
///
/// `occurrence` and `to_date` are `YYYY-MM-DD`; `to_time` is `HH:MM` or
/// `HH:MM-HH:MM`, and `None` keeps whatever time the series carries — an
/// occurrence moved to another day is usually held at the same hour.
///
/// Moving an occurrence that has already moved rewrites what stands for it
/// rather than adding a second answer: the `MOVED` line where there is one,
/// and the entry ADR-0031 wrote where the file still holds that shape.
#[uniffi::export]
pub fn move_occurrence(
    target: EditTarget,
    occurrence: String,
    to_date: String,
    to_time: Option<String>,
) -> Result<EditOutcome, EditError> {
    // Everything the caller passed is read before the file is opened, so a
    // value that was mistyped leaves the notes as they were.
    let occurrence = parse_date(&occurrence)?;
    let to_date = parse_date(&to_date)?;
    let to_time = to_time.as_deref().map(parse_time).transpose()?;

    let mut document = Document::open(&target)?;
    let (index, _) = document.heading(&target)?;
    let (planning_index, parts) = repeating_line(&document, index, &target)?;

    let planning = document.at(planning_index).to_string();
    let held = to_time.clone().or_else(|| written_time(&planning, &parts));
    let spelling = weekday_sample(&planning, &parts, &document);
    let written = moved_line(&planning, &spelling, occurrence, to_date, held.as_deref())?;

    if let Some(line_index) = moved_line_for(&document, index, occurrence) {
        if document.at(line_index) == written {
            return Ok(EditOutcome {
                line: written,
                changed: false,
                rollback: None,
            });
        }
        let before = document.text();
        document.set(line_index, written.clone());
        let rollback = document.saved(before)?;

        return Ok(EditOutcome {
            line: written,
            changed: true,
            rollback: Some(rollback),
        });
    }

    if let Some(replacement) = replacement_of(&document, index, occurrence) {
        return rewrite_replacement(document, replacement, to_date, to_time.as_deref());
    }

    // Under the last of the entry's dated lines, so that the dates of one
    // entry stay together and a second move does not push itself between the
    // first one and the timestamp it belongs to.
    let at = last_dated_line(&document, index, planning_index) + 1;
    let before = document.text();
    document.replace_lines(at..at, vec![written.clone()]);
    let rollback = document.saved(before)?;

    Ok(EditOutcome {
        line: written,
        changed: true,
        rollback: Some(rollback),
    })
}

/// The `MOVED` line holding `occurrence` on `to`.
///
/// Spelt from the series' own planning line: its indentation, and the weekday
/// written the way that line writes it. A series naming no weekday is
/// answered without one.
///
/// Both days are timestamps, and the brackets say which is which (the core's
/// ADR-0039): the occurrence being moved is an address, so it is written
/// inactive, and the day it is held on is active.
/// A weekday spelt the way the file spells its own: the series' own planning
/// line where it names one, the first weekday written anywhere in the note
/// otherwise, and `Mon` where the note writes none at all. Both halves of a
/// `MOVED` line carry a weekday, and a first move in a note of bare dates has
/// to pick a language; the date beside it names the day either way.
fn weekday_sample(planning: &str, parts: &TimestampParts, document: &Document) -> String {
    if let Some(range) = parts.weekday.clone() {
        return planning[range].to_string();
    }
    document
        .text()
        .lines()
        .find_map(weekday_written_in)
        .unwrap_or_else(|| "Mon".to_string())
}

/// The weekday of the first timestamp on a line that names one.
fn weekday_written_in(line: &str) -> Option<String> {
    let mut rest = line;
    while let Some(at) = rest.find(['<', '[']) {
        rest = &rest[at + 1..];
        let Some(day) = rest.get(..10) else { continue };
        let iso = day
            .as_bytes()
            .iter()
            .enumerate()
            .all(|(index, byte)| match index {
                4 | 7 => *byte == b'-',
                _ => byte.is_ascii_digit(),
            });
        if !iso {
            continue;
        }
        let Some(tail) = rest.get(10..).and_then(|tail| tail.strip_prefix(' ')) else {
            continue;
        };
        let name: String = tail
            .chars()
            .take_while(|written| written.is_alphabetic())
            .collect();
        if !name.is_empty() {
            return Some(name);
        }
    }
    None
}

fn moved_line(
    planning: &str,
    spelling: &str,
    occurrence: NaiveDate,
    to: NaiveDate,
    time: Option<&str>,
) -> Result<String, EditError> {
    let moved_weekday = weekday_like(spelling, occurrence)?;
    let weekday = weekday_like(spelling, to)?;
    let held = time.map_or(String::new(), |time| format!(" {time}"));

    Ok(format!(
        "{}`{MOVED} [{} {moved_weekday}] -> <{} {weekday}{held}>`",
        indentation(planning),
        occurrence.format("%Y-%m-%d"),
        to.format("%Y-%m-%d"),
    ))
}

/// Which line of the entry already moves `occurrence`, if one does.
///
/// The first is the one that stands, which is how the extractor resolves a
/// file holding two of them.
fn moved_line_for(document: &Document, index: usize, occurrence: NaiveDate) -> Option<usize> {
    let day = occurrence.format("%Y-%m-%d").to_string();

    section(document, index).find(|line_index| {
        moved_occurrence(document.at(*line_index)).is_some_and(|written| written == day)
    })
}

/// The occurrence a `MOVED` line names, as it is written.
///
/// Read in both forms: the inactive timestamp written since ADR-0039, and the
/// bare date of ADR-0038 that files already hold.
fn moved_occurrence(line: &str) -> Option<String> {
    let rest = bare_start(line).strip_prefix(MOVED)?;
    let (day, _) = rest.split_once("->")?;

    let day = day.trim().trim_start_matches('[').trim_end_matches(']');
    let day = day.split_whitespace().next()?;
    NaiveDate::parse_from_str(day, "%Y-%m-%d")
        .ok()
        .map(|_| day.to_string())
}

/// The last line of the entry carrying a date — a planning line or a `MOVED`
/// line — which is what a new one is written under.
fn last_dated_line(document: &Document, index: usize, planning: usize) -> usize {
    let dated: Vec<usize> = planning_lines(document, index)
        .iter()
        .map(|(at, _, _)| *at)
        .chain(
            section(document, index)
                .filter(|line_index| moved_occurrence(document.at(*line_index)).is_some()),
        )
        .collect();

    dated
        .into_iter()
        .chain([planning])
        .max()
        .unwrap_or(planning)
}

/// The entry of this file that stands in for `occurrence` of the series at
/// `index`, in the shape ADR-0031 wrote, if the file holds one.
///
/// Only this file is looked at, because this is the file being written to: a
/// replacement in another note is out of reach of an operation that opens one
/// file, and the second entry it would leave is visible in the agenda rather
/// than silent.
fn replacement_of(document: &Document, index: usize, occurrence: NaiveDate) -> Option<usize> {
    let series = property(document, section(document, index), ID)
        .map(|(_, value)| value)
        .filter(|value| !value.is_empty())?;
    let day = occurrence.format("%Y-%m-%d").to_string();

    (0..document.len())
        .filter(|line_index| parse_heading_line(document.at(*line_index)).is_some())
        .find(|heading| {
            let section = section(document, *heading);
            let named = property(document, section.clone(), SERIES_ID)
                .is_some_and(|(_, value)| value == series);
            named
                && property(document, section, RECURRENCE_ID)
                    .is_some_and(|(_, value)| value.split_whitespace().next() == Some(day.as_str()))
        })
}

/// Move an entry that already stands in for one occurrence to another date or
/// time.
///
/// Only its timestamp is touched: the heading, the properties and the place in
/// the file are the ones the replacement was written with, and rewriting them
/// would move an entry the reader may since have added notes under.
fn rewrite_replacement(
    mut document: Document,
    heading: usize,
    to: NaiveDate,
    time: Option<&str>,
) -> Result<EditOutcome, EditError> {
    let (planning_index, _, parts) = planning_lines(&document, heading)
        .into_iter()
        .next()
        .ok_or_else(|| EditError::Unsupported {
            detail: format!(
                "{} carries no planning line, so there is nothing to move",
                document.at(heading).trim()
            ),
        })?;

    let line = document.at(planning_index).to_string();
    let rewritten = replacement_timestamp(&line, &parts, to, time)?;
    if rewritten == line {
        return Ok(EditOutcome {
            line: rewritten,
            changed: false,
            rollback: None,
        });
    }

    let before = document.text();
    document.set(planning_index, rewritten.clone());
    let rollback = document.saved(before)?;

    Ok(EditOutcome {
        line: rewritten,
        changed: true,
        rollback: Some(rollback),
    })
}

/// The one planning line of the entry that repeats.
///
/// An entry that does not repeat has no occurrences to make an exception to:
/// what the caller means by cancelling it is the keyword, and what it means
/// by moving it is the planning date, and both have operations of their own.
///
/// An entry repeating on two dates at once — a `SCHEDULED` and a `DEADLINE`
/// that both carry a repeater — is refused rather than guessed at: which of
/// the two the occurrence is counted by decides what the replacement carries,
/// and a wrong guess writes a wrong date into the user's notes.
fn repeating_line(
    document: &Document,
    index: usize,
    target: &EditTarget,
) -> Result<(usize, TimestampParts), EditError> {
    let mut repeating = planning_lines(document, index)
        .into_iter()
        .filter(|(_, _, parts)| parts.repeater.is_some());

    let first = repeating.next().ok_or_else(|| EditError::Unsupported {
        detail: format!(
            "{} does not repeat, and an entry that does not repeat has no occurrences",
            target.heading
        ),
    })?;
    if repeating.next().is_some() {
        return Err(EditError::Unsupported {
            detail: format!(
                "{} repeats on more than one date, and which one an occurrence is counted by is left to be decided by hand",
                target.heading
            ),
        });
    }

    Ok((first.0, first.2))
}

/// `HH:MM`, or `HH:MM-HH:MM` for an occurrence held between two times.
pub(crate) fn parse_time(value: &str) -> Result<String, EditError> {
    let refused = || EditError::InvalidDate {
        detail: format!("{value:?} is not a time written HH:MM"),
    };

    let mut halves = value.split('-');
    let from = halves.next().ok_or_else(refused)?;
    let to = halves.next();
    if halves.next().is_some() {
        return Err(refused());
    }
    for half in [Some(from), to].into_iter().flatten() {
        NaiveTime::parse_from_str(half, "%H:%M").map_err(|_| refused())?;
    }

    Ok(value.to_string())
}

/// The lines under the heading at `index`, up to the next heading.
fn section(document: &Document, index: usize) -> Range<usize> {
    let mut end = index + 1;
    while end < document.len() && parse_heading_line(document.at(end)).is_none() {
        end += 1;
    }

    index + 1..end
}

/// Where a fenced block opens or closes: its character, how many of them, and
/// what follows on the line.
fn fence(line: &str) -> Option<(char, usize, &str)> {
    let trimmed = line.trim_start();
    let mark = trimmed.chars().next()?;
    if mark != '`' && mark != '~' {
        return None;
    }

    let width = trimmed.chars().take_while(|c| *c == mark).count();
    if width < 3 {
        return None;
    }

    Some((mark, width, trimmed[width..].trim()))
}

/// Where the property block opening at `index` closes, as the line after its
/// closing fence.
///
/// For the entry editor, which keeps such a block out of the text it hands
/// over: the keys in it are written by the actions of the sheet, and an entry
/// opened and saved would otherwise come back with them retyped.
pub(crate) fn property_block_at(document: &Document, index: usize) -> Option<usize> {
    let (mark, width, info) = fence(document.at(index))?;
    if info != PROPERTIES {
        return None;
    }

    (index + 1..document.len()).find_map(|line| {
        fence(document.at(line))
            .filter(|(closing, closing_width, info)| {
                *closing == mark && *closing_width >= width && info.is_empty()
            })
            .map(|_| line + 1)
    })
}

/// Where the property blocks of `range` sit, as the ranges of the lines
/// between their fences.
///
/// A block whose fence is never closed is not one: the file it stands in is
/// already unreadable — a markdown parser runs such a fence to the end of the
/// document and swallows everything below it — and writing into it would put
/// a property where nothing reads it.
fn property_blocks(document: &Document, range: Range<usize>) -> Vec<Range<usize>> {
    let mut blocks = Vec::new();
    let mut open: Option<(char, usize, bool, usize)> = None;

    for line_index in range {
        let Some((mark, width, info)) = fence(document.at(line_index)) else {
            continue;
        };

        match open {
            Some((opened, opened_width, properties, start)) => {
                if mark == opened && width >= opened_width && info.is_empty() {
                    if properties {
                        blocks.push(start + 1..line_index);
                    }
                    open = None;
                }
            }
            None => open = Some((mark, width, info == PROPERTIES, line_index)),
        }
    }

    blocks
}

/// The key and the value a property line holds, following the extractor's
/// ADR-0020: the key is what stands before the first colon, and both halves
/// are trimmed.
fn property_line(line: &str) -> Option<(&str, &str)> {
    let (key, value) = line.split_once(':')?;
    let key = key.trim();
    if key.is_empty() {
        return None;
    }

    Some((key, value.trim()))
}

/// What the entry at `index` holds under `key`, and which line holds it.
///
/// The last one wins, which is how the extractor merges a key written twice.
fn property(document: &Document, section: Range<usize>, key: &str) -> Option<(usize, String)> {
    property_blocks(document, section)
        .into_iter()
        .flatten()
        .filter_map(|line_index| {
            property_line(document.at(line_index))
                .filter(|(written, _)| *written == key)
                .map(|(_, value)| (line_index, value.to_string()))
        })
        .next_back()
}

/// Write `key` into the property block of the entry at `index`, and answer
/// with the line as it now stands.
///
/// The line the key is already on is rewritten where there is one; otherwise
/// it joins the last property block the entry has, and an entry with no block
/// gets one under its planning lines — which is where the extractor's
/// ADR-0020 puts it, and where it stays out of the body the entry editor
/// hands over.
fn set_property(document: &mut Document, index: usize, key: &str, value: &str) -> String {
    let section = section(document, index);
    let blocks = property_blocks(document, section.clone());

    let written = property(document, section, key);
    if let Some((line_index, _)) = written {
        let line = format!("{}{key}: {value}", indentation(document.at(line_index)));
        document.set(line_index, line.clone());
        return line;
    }

    if let Some(block) = blocks.last() {
        // Written the way the block's other lines are; a block holding none
        // yet is followed by its closing fence, which carries the indentation
        // the block was written at.
        let sample = if block.start < block.end {
            block.start
        } else {
            block.end
        };
        let indent = indentation(document.at(sample)).to_string();
        let line = format!("{indent}{key}: {value}");
        document.replace_lines(block.end..block.end, vec![line.clone()]);
        return line;
    }

    let at = keyword_block_end(document, index);
    let line = format!("{key}: {value}");
    document.replace_lines(
        at..at,
        vec![format!("```{PROPERTIES}"), line.clone(), "```".to_string()],
    );
    line
}

/// The whitespace a line begins with.
fn indentation(line: &str) -> &str {
    &line[..line.len() - line.trim_start().len()]
}

/// The time the timestamp carries, as written — a range of hours included.
///
/// An occurrence held from 15:00 to 16:00 is held for an hour wherever it
/// moves to, so the whole token travels with it rather than only its start.
fn written_time(line: &str, parts: &TimestampParts) -> Option<String> {
    fields(line, parts.whole.clone())
        .into_iter()
        .find(|field| is_time(&line[field.clone()]))
        .map(|field| line[field].to_string())
}

/// The series' planning line, moved to the occurrence that replaces it.
///
/// The line is the series' own, rewritten token by token rather than composed
/// from nothing: the keyword, the indentation, the inline-code framing and
/// the language of the weekday are all the file's, and a replacement spelled
/// differently from the entry it stands in for would be a change the user did
/// not ask for.
///
/// The repeater is the one token that goes: the replacement is one occurrence
/// and does not repeat. A warning cookie stays — a deadline moved is still a
/// deadline warned about the same number of days ahead.
fn replacement_timestamp(
    line: &str,
    parts: &TimestampParts,
    date: NaiveDate,
    time: Option<&str>,
) -> Result<String, EditError> {
    let fields = fields(line, parts.whole.clone());
    let mut edits: Vec<(Range<usize>, String)> =
        vec![(parts.date.clone(), date.format("%Y-%m-%d").to_string())];

    if let Some(weekday) = parts.weekday.clone() {
        let written = weekday_like(&line[weekday.clone()], date)?;
        edits.push((weekday, written));
    }

    let repeater = fields
        .iter()
        .position(|field| is_repeater(&line[field.clone()]));
    if let Some(at) = repeater {
        // The whitespace ahead of the token goes with it: taken on its own it
        // would leave two spaces where there was one. A repeater standing
        // first is not one — the date is — so there is always a token before
        // it, and the check is for the indexing rather than for the file.
        let from = if at > 0 {
            fields[at - 1].end
        } else {
            fields[at].start
        };
        edits.push((from..fields[at].end, String::new()));
    }

    if let Some(time) = time {
        let written = fields
            .iter()
            .find(|field| is_time(&line[(*field).clone()]))
            .cloned();
        match written {
            Some(field) => edits.push((field, time.to_string())),
            // Where the timestamp had no time, it goes after the weekday, or
            // after the date where there is no weekday.
            None => {
                let after = parts
                    .weekday
                    .clone()
                    .unwrap_or_else(|| parts.date.clone())
                    .end;
                edits.push((after..after, format!(" {time}")));
            }
        }
    }

    // Applied from the end, so that a replacement of a different width cannot
    // move the range the next one was located by.
    edits.sort_by_key(|(range, _)| std::cmp::Reverse(range.start));
    let mut rewritten = line.to_string();
    for (range, with) in edits {
        rewritten = splice(&rewritten, range, &with);
    }

    Ok(rewritten)
}

/// The whitespace-separated tokens between the timestamp's brackets, as
/// ranges into the line they were read from.
pub(crate) fn fields(line: &str, whole: Range<usize>) -> Vec<Range<usize>> {
    // The brackets are one byte each, in both families.
    let (start, end) = (whole.start + 1, whole.end - 1);
    let mut fields = Vec::new();
    let mut at = start;

    while at < end {
        let Some(offset) = line[at..end].find(|c: char| !c.is_whitespace()) else {
            break;
        };
        let from = at + offset;
        let width = line[from..end]
            .find(char::is_whitespace)
            .unwrap_or(end - from);
        fields.push(from..from + width);
        at = from + width;
    }

    fields
}

/// Whether the token is a clock time, or a range of two of them.
pub(crate) fn is_time(field: &str) -> bool {
    let mut halves = field.split('-');
    let Some(first) = halves.next() else {
        return false;
    };
    let second = halves.next();
    if halves.next().is_some() {
        return false;
    }

    [Some(first), second]
        .into_iter()
        .flatten()
        .all(|half| NaiveTime::parse_from_str(half, "%H:%M").is_ok())
}

/// Whether the token is a repeater.
///
/// Asked of the extractor rather than answered here: a second reading of the
/// same syntax is a second set of answers to keep in step, and this one was
/// already out of step -- it took `+0d` for a repeater the extractor refuses,
/// missed the working-day `+1wd` it reads, and cut the unit off by byte,
/// which a unit typed in another alphabet is not a whole number of.
pub(crate) fn is_repeater(field: &str) -> bool {
    parse_repeater(field).is_some()
}
