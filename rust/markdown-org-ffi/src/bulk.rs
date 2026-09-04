//! Acting on a group of tasks in one go.
//!
//! A group of overdue entries is answered in one move rather than read task by
//! task, and doing that as N single edits would rewrite one file N times and
//! leave N commits behind it. Here the group is grouped by file: each file is
//! read once, every task in it is rewritten, and the file is written once.
//!
//! Two properties the single-task edits do not need:
//!
//! * a task that cannot be edited — its heading moved under a sync, it carries
//!   no planning line — is refused on its own and named, while the rest of the
//!   group goes through. Failing the whole group over one entry would leave
//!   the user to find which one it was;
//! * what was overwritten is handed back as [`FileRollback`], so the move can
//!   be undone. A move over twenty notes is not something to be sure about in
//!   advance, and the notes may not be in git at all — see
//!   [`crate::revert_files`].

use chrono::NaiveDate;
use markdown_org_extract::TimestampParts;

use crate::document::Document;
use crate::edit::{parse_date, with_status, EditError, EditTarget};
use crate::planning::{next_occurrence, planning_lines, rewrite_date, PlanningKeyword};
use crate::undo::FileRollback;
use crate::TaskType;

/// One task of the group, and which of its planning lines the agenda placed
/// it by.
#[derive(Debug, Clone, uniffi::Record)]
pub struct BulkTarget {
    /// Path of the file relative to the notes directory.
    pub file: String,
    /// 1-based line the heading was found on.
    pub line: u32,
    /// Heading text without the keyword and the priority cookie.
    pub heading: String,
    /// The planning line the row was placed by. `None` where the row carries
    /// neither kind, which only [`BulkAction::Cancel`] can act on.
    pub keyword: Option<PlanningKeyword>,
}

/// What to do to every task of the group.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum BulkAction {
    /// Give the planning date today's date. A repeating task catches up to
    /// its next occurrence instead, keeping its repeater — the same rule the
    /// single-task completion follows, because a missed repeat is not
    /// rescheduled but caught up.
    MoveToToday,
    /// Take the planning line out of the note, leaving the task without a
    /// date. The heading and everything else under it stay as they are.
    DropPlanning,
    /// Set the keyword to `CANCELLED`, leaving the dates where they are.
    Cancel,
}

/// Why one task of the group was left alone.
///
/// The reason is an enum rather than a sentence because the screen words it,
/// and `detail` carries what the core would have said for a log.
#[derive(Debug, Clone, uniffi::Record)]
pub struct BulkRefusal {
    /// Path of the file relative to the notes directory.
    pub file: String,
    /// 1-based line the caller aimed at.
    pub line: u32,
    /// Heading the caller named, so the screen can say which task it was.
    pub heading: String,
    /// What kind of refusal this is.
    pub reason: RefusalReason,
    /// Human-readable detail, in English, for the log.
    pub detail: String,
}

/// The kinds of refusal a task in a group can meet.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RefusalReason {
    /// The line no longer holds the heading the agenda was built from: a sync
    /// has rewritten the file since. Re-reading the notes is the answer.
    Moved,
    /// The task carries no planning line of the kind the action needs.
    NoPlanningLine,
    /// The edit is one this application does not make — an hourly repeater, a
    /// date outside the four-digit years, a weekday name in a third language.
    Unsupported,
    /// The file could not be read or written: gone, unreadable, not UTF-8.
    Unreadable,
}

/// What acting on the group did.
#[derive(Debug, Clone, uniffi::Record)]
pub struct BulkOutcome {
    /// How many tasks were changed.
    pub changed: u32,
    /// The tasks that were left alone, and why.
    pub refused: Vec<BulkRefusal>,
    /// What to hand [`crate::revert_files`] to put the notes back.
    pub rollback: Vec<FileRollback>,
}

/// Apply `action` to every task of `targets`.
///
/// `today` is `YYYY-MM-DD` and comes from the caller rather than the clock,
/// the same contract the rest of the crate follows.
///
/// The result is a report rather than a failure: a task that could not be
/// changed appears in [`BulkOutcome::refused`] while the others go through.
/// The one `Err` is a `today` that is not a date, which is the caller's
/// mistake and not the notes'.
#[uniffi::export]
pub fn apply_to_group(
    dir: String,
    targets: Vec<BulkTarget>,
    action: BulkAction,
    today: String,
) -> Result<BulkOutcome, EditError> {
    let today = parse_date(&today)?;

    let mut outcome = BulkOutcome {
        changed: 0,
        refused: Vec::new(),
        rollback: Vec::new(),
    };

    for file in files_of(&targets) {
        let group: Vec<&BulkTarget> = targets.iter().filter(|it| it.file == file).collect();
        apply_to_file(&dir, &file, &group, action, today, &mut outcome);
    }

    Ok(outcome)
}

/// The files the targets name, each once, in the order they first appear.
///
/// The order is the caller's rather than sorted: the group came off a screen,
/// and a report that lists its files in another order reads as being about
/// something else.
fn files_of(targets: &[BulkTarget]) -> Vec<String> {
    let mut files: Vec<String> = Vec::new();
    for target in targets {
        if !files.contains(&target.file) {
            files.push(target.file.clone());
        }
    }
    files
}

/// One line of a file, changed or taken out.
enum Change {
    Replace(usize, String),
    Remove(usize),
}

impl Change {
    fn at(&self) -> usize {
        match self {
            Change::Replace(index, _) | Change::Remove(index) => *index,
        }
    }
}

/// Apply the group's share of `file`, adding what happened to `outcome`.
fn apply_to_file(
    dir: &str,
    file: &str,
    targets: &[&BulkTarget],
    action: BulkAction,
    today: NaiveDate,
    outcome: &mut BulkOutcome,
) {
    let mut document = match Document::read(dir, file) {
        Ok(document) => document,
        Err(error) => {
            // The file is gone or unreadable, so every task in it is refused
            // for that one reason rather than tried and refused one by one.
            outcome
                .refused
                .extend(targets.iter().map(|target| target.refused(&error)));
            return;
        }
    };

    let before = document.text();

    // Every change is computed before any is applied: a line index taken
    // against the document is only valid while nothing has moved, and a group
    // that failed halfway would leave the file in a state nobody asked for.
    let mut changes = Vec::with_capacity(targets.len());
    for target in targets {
        match change_for(&document, dir, target, action, today) {
            Ok(Some(change)) => changes.push(change),
            // The task is already as the action would leave it. Not a
            // refusal: nothing is wrong with it, and there is nothing to do.
            Ok(None) => {}
            Err(error) => outcome.refused.push(target.refused(&error)),
        }
    }

    if changes.is_empty() {
        return;
    }

    // Applied from the end of the file backwards, so that taking a line out
    // cannot move the line another change is aimed at.
    changes.sort_by_key(|change| std::cmp::Reverse(change.at()));
    for change in &changes {
        match change {
            Change::Replace(index, line) => document.set(*index, line.clone()),
            Change::Remove(index) => document.remove(*index),
        }
    }

    let rollback = match document.saved(before) {
        Ok(rollback) => rollback,
        Err(error) => {
            // Nothing was written: `save` renames a finished temporary over
            // the note, so a failure leaves the file exactly as it was.
            outcome
                .refused
                .extend(targets.iter().map(|target| target.refused(&error)));
            return;
        }
    };

    outcome.changed += changes.len() as u32;
    outcome.rollback.push(rollback);
}

/// What `action` does to one task, or `None` when the task already stands
/// that way.
fn change_for(
    document: &Document,
    dir: &str,
    target: &BulkTarget,
    action: BulkAction,
    today: NaiveDate,
) -> Result<Option<Change>, EditError> {
    let aimed = EditTarget {
        dir: dir.to_string(),
        file: target.file.clone(),
        line: target.line,
        heading: target.heading.clone(),
    };
    let (index, heading) = document.heading(&aimed)?;

    match action {
        BulkAction::Cancel => {
            let line = document.at(index);
            let cancelled = with_status(line, &heading, Some(TaskType::Cancelled));
            Ok((cancelled != line).then_some(Change::Replace(index, cancelled)))
        }

        BulkAction::DropPlanning => {
            let (line_index, _) = planning_line(document, index, target)?;
            Ok(Some(Change::Remove(line_index)))
        }

        BulkAction::MoveToToday => {
            let (line_index, parts) = planning_line(document, index, target)?;
            let moved = match parts.repeater.as_ref() {
                // A missed repeat is caught up rather than dragged to today:
                // its own interval says where it lands, and the repeater
                // stays in the file.
                Some(repeater) => next_occurrence(parts.value, today, repeater)?,
                None => today,
            };

            let line = document.at(line_index);
            let rewritten = rewrite_date(line, &parts, moved)?;
            Ok((rewritten != line).then_some(Change::Replace(line_index, rewritten)))
        }
    }
}

/// The planning line the target was placed by.
fn planning_line(
    document: &Document,
    index: usize,
    target: &BulkTarget,
) -> Result<(usize, TimestampParts), EditError> {
    let wanted = target.keyword.ok_or_else(|| EditError::NoPlanningLine {
        detail: format!("{} is not placed by a planning line", target.heading),
    })?;

    planning_lines(document, index)
        .into_iter()
        .find(|(_, kind, _)| *kind == wanted)
        .map(|(line_index, _, parts)| (line_index, parts))
        .ok_or_else(|| EditError::NoPlanningLine {
            detail: format!("{} has no {:?} line", target.heading, wanted),
        })
}

impl BulkTarget {
    /// This task, refused for what `error` says.
    fn refused(&self, error: &EditError) -> BulkRefusal {
        BulkRefusal {
            file: self.file.clone(),
            line: self.line,
            heading: self.heading.clone(),
            reason: RefusalReason::of(error),
            detail: error.to_string(),
        }
    }
}

impl RefusalReason {
    fn of(error: &EditError) -> Self {
        match error {
            EditError::Stale { .. } => RefusalReason::Moved,
            EditError::NoPlanningLine { .. } => RefusalReason::NoPlanningLine,
            EditError::Unsupported { .. }
            | EditError::InvalidDate { .. }
            | EditError::InvalidPriority { .. } => RefusalReason::Unsupported,
            EditError::NotFound { .. } | EditError::Io { .. } | EditError::NotUtf8 { .. } => {
                RefusalReason::Unreadable
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_file_is_named_once_however_many_of_its_tasks_are_in_the_group() {
        let targets = vec![
            target("work.md", 1),
            target("home.md", 3),
            target("work.md", 9),
        ];

        assert_eq!(files_of(&targets), vec!["work.md", "home.md"]);
    }

    #[test]
    fn changes_are_applied_from_the_end_of_the_file_backwards() {
        let mut changes = [
            Change::Remove(2),
            Change::Replace(7, "seventh".to_string()),
            Change::Remove(4),
        ];

        changes.sort_by_key(|change| std::cmp::Reverse(change.at()));

        assert_eq!(
            changes.iter().map(Change::at).collect::<Vec<_>>(),
            vec![7, 4, 2],
        );
    }

    #[test]
    fn a_heading_that_moved_is_told_apart_from_a_file_that_cannot_be_read() {
        let moved = EditError::Stale {
            detail: "notes.md:4 is not a heading".to_string(),
        };
        let unreadable = EditError::NotUtf8 {
            detail: "notes.md".to_string(),
        };

        assert_eq!(RefusalReason::of(&moved), RefusalReason::Moved);
        assert_eq!(RefusalReason::of(&unreadable), RefusalReason::Unreadable);
    }

    #[test]
    fn a_task_the_agenda_placed_by_no_planning_line_is_refused_by_name() {
        let target = BulkTarget {
            file: "notes.md".to_string(),
            line: 1,
            heading: "Buy milk".to_string(),
            keyword: None,
        };
        let error = EditError::NoPlanningLine {
            detail: "Buy milk is not placed by a planning line".to_string(),
        };

        let refusal = target.refused(&error);

        assert_eq!(refusal.reason, RefusalReason::NoPlanningLine);
        assert_eq!(refusal.heading, "Buy milk");
        assert_eq!(refusal.line, 1);
    }

    fn target(file: &str, line: u32) -> BulkTarget {
        BulkTarget {
            file: file.to_string(),
            line,
            heading: format!("{file}:{line}"),
            keyword: Some(PlanningKeyword::Scheduled),
        }
    }
}
