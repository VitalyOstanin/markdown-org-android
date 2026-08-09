//! Tests for acting on a group of tasks at once.
//!
//! Every case asserts on the whole file for the reason the single-edit tests
//! do — a group edit that disturbs a neighbouring line is what turns a merge
//! into a conflict — and the cases with more than one file assert on both:
//! the point of the group is that each file is written once, and a file
//! nobody's task was in must not be written at all.

use std::fs;
use std::path::Path;

use markdown_org_ffi::{
    apply_to_group, revert_bulk, BulkAction, BulkTarget, PlanningKeyword, RefusalReason,
};

/// Three overdue tasks, two of them dated and one repeating.
const NOTES: &str = "\
# TODO Pay the tax
`SCHEDULED: <2026-02-15 Sun>`

# TODO Book the service
`SCHEDULED: <2026-03-02 Mon>`

# TODO English on Friday
`SCHEDULED: <2026-07-31 Fri +1w>`
";

const TODAY: &str = "2026-08-02";

#[test]
fn a_group_of_dates_moves_to_today_in_one_pass() {
    let vault = vault(&[("notes.md", NOTES)]);

    let outcome = apply_to_group(
        dir(&vault),
        vec![
            scheduled("notes.md", 1, "Pay the tax"),
            scheduled("notes.md", 4, "Book the service"),
        ],
        BulkAction::MoveToToday,
        TODAY.to_string(),
    )
    .expect("group");

    assert_eq!(outcome.changed, 2);
    assert!(outcome.refused.is_empty());
    // One file, so one entry to undo: the two tasks were one rewrite.
    assert_eq!(outcome.rollback.len(), 1);
    assert_eq!(
        read(&vault, "notes.md"),
        "\
# TODO Pay the tax
`SCHEDULED: <2026-08-02 Sun>`

# TODO Book the service
`SCHEDULED: <2026-08-02 Sun>`

# TODO English on Friday
`SCHEDULED: <2026-07-31 Fri +1w>`
"
    );
}

#[test]
fn a_missed_repeat_is_caught_up_rather_than_dragged_to_today() {
    let vault = vault(&[("notes.md", NOTES)]);

    let outcome = apply_to_group(
        dir(&vault),
        vec![scheduled("notes.md", 7, "English on Friday")],
        BulkAction::MoveToToday,
        TODAY.to_string(),
    )
    .expect("group");

    assert_eq!(outcome.changed, 1);
    // A week on from the date in the file, and the repeater still in it —
    // the same answer the single-task completion gives.
    assert!(
        read(&vault, "notes.md").contains("`SCHEDULED: <2026-08-07 Fri +1w>`"),
        "{}",
        read(&vault, "notes.md"),
    );
}

#[test]
fn tasks_in_different_files_are_undone_file_by_file() {
    let vault = vault(&[
        (
            "work.md",
            "# TODO Pay the tax\n`SCHEDULED: <2026-02-15 Sun>`\n",
        ),
        (
            "home.md",
            "# TODO Water the plants\n`SCHEDULED: <2026-03-02 Mon>`\n",
        ),
        ("other.md", "# TODO Nothing to do with it\n"),
    ]);

    let outcome = apply_to_group(
        dir(&vault),
        vec![
            scheduled("work.md", 1, "Pay the tax"),
            scheduled("home.md", 1, "Water the plants"),
        ],
        BulkAction::MoveToToday,
        TODAY.to_string(),
    )
    .expect("group");

    assert_eq!(outcome.changed, 2);
    assert_eq!(
        outcome
            .rollback
            .iter()
            .map(|entry| entry.file.clone())
            .collect::<Vec<_>>(),
        vec!["work.md", "home.md"],
    );
    // The file nobody's task was in is not in the rollback, and so was never
    // written.
    assert_eq!(read(&vault, "other.md"), "# TODO Nothing to do with it\n");
}

#[test]
fn a_heading_that_moved_is_refused_on_its_own() {
    let vault = vault(&[("notes.md", NOTES)]);

    let outcome = apply_to_group(
        dir(&vault),
        vec![
            scheduled("notes.md", 1, "Pay the tax"),
            // The agenda said this was on line 4; the file says otherwise.
            scheduled("notes.md", 4, "Something else entirely"),
        ],
        BulkAction::MoveToToday,
        TODAY.to_string(),
    )
    .expect("group");

    assert_eq!(outcome.changed, 1);
    assert_eq!(outcome.refused.len(), 1);
    assert_eq!(outcome.refused[0].reason, RefusalReason::Moved);
    assert_eq!(outcome.refused[0].heading, "Something else entirely");
    // The one that was still where it said it was went through.
    assert!(read(&vault, "notes.md").contains("`SCHEDULED: <2026-08-02 Sun>`"));
}

#[test]
fn a_task_placed_by_no_planning_line_is_named_rather_than_guessed_at() {
    let vault = vault(&[("notes.md", "# TODO Buy milk\n")]);

    let outcome = apply_to_group(
        dir(&vault),
        vec![BulkTarget {
            file: "notes.md".to_string(),
            line: 1,
            heading: "Buy milk".to_string(),
            keyword: None,
        }],
        BulkAction::MoveToToday,
        TODAY.to_string(),
    )
    .expect("group");

    assert_eq!(outcome.changed, 0);
    assert_eq!(outcome.refused[0].reason, RefusalReason::NoPlanningLine);
    assert_eq!(read(&vault, "notes.md"), "# TODO Buy milk\n");
    // Nothing was written, so there is nothing to undo.
    assert!(outcome.rollback.is_empty());
}

#[test]
fn dropping_the_date_takes_the_planning_line_and_nothing_else() {
    let vault = vault(&[("notes.md", NOTES)]);

    let outcome = apply_to_group(
        dir(&vault),
        vec![
            scheduled("notes.md", 1, "Pay the tax"),
            scheduled("notes.md", 4, "Book the service"),
        ],
        BulkAction::DropPlanning,
        TODAY.to_string(),
    )
    .expect("group");

    assert_eq!(outcome.changed, 2);
    assert_eq!(
        read(&vault, "notes.md"),
        "\
# TODO Pay the tax

# TODO Book the service

# TODO English on Friday
`SCHEDULED: <2026-07-31 Fri +1w>`
"
    );
}

#[test]
fn a_file_that_ends_without_a_newline_does_not_grow_one() {
    let vault = vault(&[(
        "notes.md",
        "# TODO Pay the tax\n`SCHEDULED: <2026-02-15 Sun>`",
    )]);

    apply_to_group(
        dir(&vault),
        vec![scheduled("notes.md", 1, "Pay the tax")],
        BulkAction::DropPlanning,
        TODAY.to_string(),
    )
    .expect("group");

    assert_eq!(read(&vault, "notes.md"), "# TODO Pay the tax");
}

#[test]
fn cancelling_a_group_leaves_its_dates_where_they_are() {
    let vault = vault(&[("notes.md", NOTES)]);

    let outcome = apply_to_group(
        dir(&vault),
        vec![scheduled("notes.md", 1, "Pay the tax")],
        BulkAction::Cancel,
        TODAY.to_string(),
    )
    .expect("group");

    assert_eq!(outcome.changed, 1);
    assert!(read(&vault, "notes.md").starts_with(
        "\
# CANCELLED Pay the tax
`SCHEDULED: <2026-02-15 Sun>`
"
    ));
}

#[test]
fn a_task_already_standing_that_way_is_neither_changed_nor_refused() {
    let vault = vault(&[(
        "notes.md",
        "# CANCELLED Pay the tax\n`SCHEDULED: <2026-02-15 Sun>`\n",
    )]);

    let outcome = apply_to_group(
        dir(&vault),
        vec![scheduled("notes.md", 1, "Pay the tax")],
        BulkAction::Cancel,
        TODAY.to_string(),
    )
    .expect("group");

    assert_eq!(outcome.changed, 0);
    assert!(outcome.refused.is_empty());
    assert!(outcome.rollback.is_empty());
}

#[test]
fn undoing_a_group_puts_the_notes_back() {
    let vault = vault(&[("notes.md", NOTES)]);

    let outcome = apply_to_group(
        dir(&vault),
        vec![
            scheduled("notes.md", 1, "Pay the tax"),
            scheduled("notes.md", 4, "Book the service"),
        ],
        BulkAction::MoveToToday,
        TODAY.to_string(),
    )
    .expect("group");

    let undone = revert_bulk(dir(&vault), outcome.rollback);

    assert_eq!(undone.restored, vec!["notes.md"]);
    assert!(undone.skipped.is_empty());
    assert!(undone.failed.is_empty());
    assert_eq!(read(&vault, "notes.md"), NOTES);
}

#[test]
fn a_file_opening_with_a_byte_order_mark_keeps_it_through_the_undo() {
    // The snapshot an undo restores is the file as the document reads it, so
    // a mark held apart from the lines has to come back with them.
    let marked = format!("\u{FEFF}{NOTES}");
    let vault = vault(&[("notes.md", marked.as_str())]);

    let outcome = apply_to_group(
        dir(&vault),
        vec![scheduled("notes.md", 1, "Pay the tax")],
        BulkAction::MoveToToday,
        TODAY.to_string(),
    )
    .expect("group");

    assert_eq!(outcome.changed, 1);
    assert!(
        read(&vault, "notes.md").starts_with('\u{FEFF}'),
        "the mark must survive the edit: {:?}",
        read(&vault, "notes.md")
    );

    let undone = revert_bulk(dir(&vault), outcome.rollback);

    assert_eq!(undone.restored, vec!["notes.md"]);
    assert_eq!(read(&vault, "notes.md"), marked);
}

#[test]
fn a_file_written_to_since_the_group_is_left_alone_by_the_undo() {
    let vault = vault(&[
        (
            "work.md",
            "# TODO Pay the tax\n`SCHEDULED: <2026-02-15 Sun>`\n",
        ),
        (
            "home.md",
            "# TODO Water the plants\n`SCHEDULED: <2026-03-02 Mon>`\n",
        ),
    ]);

    let outcome = apply_to_group(
        dir(&vault),
        vec![
            scheduled("work.md", 1, "Pay the tax"),
            scheduled("home.md", 1, "Water the plants"),
        ],
        BulkAction::MoveToToday,
        TODAY.to_string(),
    )
    .expect("group");

    // Whatever the user did next: an edit of their own, or a sync landing.
    let moved_on = "# DONE Water the plants\n`SCHEDULED: <2026-08-02 Sun>`\n";
    fs::write(vault.path().join("home.md"), moved_on).expect("write");

    let undone = revert_bulk(dir(&vault), outcome.rollback);

    assert_eq!(undone.restored, vec!["work.md"]);
    assert_eq!(undone.skipped, vec!["home.md"]);
    assert_eq!(
        read(&vault, "work.md"),
        "# TODO Pay the tax\n`SCHEDULED: <2026-02-15 Sun>`\n",
    );
    // The one that moved on keeps what was written to it.
    assert_eq!(read(&vault, "home.md"), moved_on);
}

#[test]
fn a_date_the_caller_did_not_state_properly_is_refused_before_anything_is_read() {
    let vault = vault(&[("notes.md", NOTES)]);

    let refused = apply_to_group(
        dir(&vault),
        vec![scheduled("notes.md", 1, "Pay the tax")],
        BulkAction::MoveToToday,
        "02.08.2026".to_string(),
    );

    assert!(refused.is_err());
    assert_eq!(read(&vault, "notes.md"), NOTES);
}

/// A directory holding the named files, removed when the handle drops.
fn vault(files: &[(&str, &str)]) -> tempfile::TempDir {
    let dir = tempfile::tempdir().expect("tempdir");
    for (name, body) in files {
        fs::write(dir.path().join(name), body).expect("write");
    }
    dir
}

fn dir(vault: &tempfile::TempDir) -> String {
    vault.path().display().to_string()
}

fn read(vault: &tempfile::TempDir, file: &str) -> String {
    fs::read_to_string(Path::new(vault.path()).join(file)).expect("read")
}

/// A task the agenda placed by its `SCHEDULED` line.
fn scheduled(file: &str, line: u32, heading: &str) -> BulkTarget {
    BulkTarget {
        file: file.to_string(),
        line,
        heading: heading.to_string(),
        keyword: Some(PlanningKeyword::Scheduled),
    }
}
