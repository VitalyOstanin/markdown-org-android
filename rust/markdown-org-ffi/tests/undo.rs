//! Tests for taking a single edit back.
//!
//! The pair of texts an edit hands back is what an undo works from, so these
//! cases go through the edits themselves rather than building a rollback by
//! hand: what is being checked is that a tap can be reversed, not that two
//! strings can be swapped.

use std::fs;

use markdown_org_ffi::{
    complete_task, revert_files, set_entry, set_planning, set_priority, set_status, FileRollback,
    PlanningKeyword, TaskType,
};

mod common;

use common::{body, target, vault};

const TODAY: &str = "2026-08-19";

#[test]
fn undoing_a_priority_puts_the_line_back() {
    let vault = vault("# TODO Write the report\n`SCHEDULED: <2026-08-19 Wed>`\n");
    let before = body(vault.path());

    let outcome = set_priority(
        target(vault.path(), 1, "Write the report"),
        Some("A".to_string()),
    )
    .expect("priority");
    assert_eq!(
        body(vault.path()),
        "# TODO [#A] Write the report\n`SCHEDULED: <2026-08-19 Wed>`\n"
    );

    let undone = revert_files(dir(&vault), vec![outcome.rollback.expect("rollback")]);

    assert_eq!(undone.restored, vec!["notes.md"]);
    assert!(undone.skipped.is_empty());
    assert_eq!(body(vault.path()), before);
}

#[test]
fn undoing_a_date_written_from_nothing_takes_the_line_out_again() {
    let vault = vault("# TODO Write the report\n\nA paragraph.\n");
    let before = body(vault.path());

    let outcome = set_planning(
        target(vault.path(), 1, "Write the report"),
        PlanningKeyword::Scheduled,
        Some(TODAY.to_string()),
    )
    .expect("set");

    revert_files(dir(&vault), vec![outcome.rollback.expect("rollback")]);

    assert_eq!(body(vault.path()), before);
}

#[test]
fn undoing_a_date_taken_off_writes_the_line_back_as_it_was() {
    let vault = vault("# TODO Write\n  `SCHEDULED: <2026-08-19 Ср 10:00>`\n");
    let before = body(vault.path());

    let outcome = set_planning(
        target(vault.path(), 1, "Write"),
        PlanningKeyword::Scheduled,
        None,
    )
    .expect("clear");
    assert_eq!(body(vault.path()), "# TODO Write\n");

    revert_files(dir(&vault), vec![outcome.rollback.expect("rollback")]);

    // Down to the indentation, the Russian weekday and the time: the undo
    // restores the bytes the file held rather than a line rebuilt from what
    // the edit understood of it.
    assert_eq!(body(vault.path()), before);
}

#[test]
fn undoing_a_completion_reopens_the_task() {
    let vault = vault("# TODO Write\n`SCHEDULED: <2026-08-19 Wed>`\n");
    let before = body(vault.path());

    let outcome =
        complete_task(target(vault.path(), 1, "Write"), TODAY.to_string()).expect("complete");
    assert!(!outcome.repeated);

    revert_files(dir(&vault), vec![outcome.rollback.expect("rollback")]);

    assert_eq!(body(vault.path()), before);
}

#[test]
fn undoing_a_repeat_that_moved_forward_puts_the_old_date_back() {
    let vault = vault("# TODO Water the plants\n`SCHEDULED: <2026-08-12 Wed +1w>`\n");
    let before = body(vault.path());

    let outcome = complete_task(
        target(vault.path(), 1, "Water the plants"),
        TODAY.to_string(),
    )
    .expect("complete");
    assert!(outcome.repeated);

    revert_files(dir(&vault), vec![outcome.rollback.expect("rollback")]);

    assert_eq!(body(vault.path()), before);
}

#[test]
fn undoing_an_edited_entry_puts_the_title_and_the_body_back() {
    let vault = vault("# TODO Write\n\nThe first note.\n\n# TODO Read\n");
    let before = body(vault.path());

    let outcome = set_entry(
        target(vault.path(), 1, "Write"),
        "Write it up".to_string(),
        "A longer note,\nover two lines.".to_string(),
    )
    .expect("entry");

    revert_files(dir(&vault), vec![outcome.rollback.expect("rollback")]);

    assert_eq!(body(vault.path()), before);
}

#[test]
fn an_edit_that_changed_nothing_offers_nothing_to_undo() {
    let vault = vault("# DONE Write\n");

    let outcome =
        set_status(target(vault.path(), 1, "Write"), Some(TaskType::Done)).expect("status");

    assert!(!outcome.changed);
    assert!(outcome.rollback.is_none());
}

#[test]
fn a_note_written_to_since_the_edit_is_left_alone() {
    let vault = vault("# TODO Write\n");

    let outcome =
        set_status(target(vault.path(), 1, "Write"), Some(TaskType::Done)).expect("status");

    // A sync landed, or the note was edited in another application: the file
    // no longer holds what the edit wrote, and putting the old text back would
    // take that work away.
    let landed = "# DONE Write\n\nA line added elsewhere.\n";
    fs::write(vault.path().join("notes.md"), landed).expect("write");

    let undone = revert_files(dir(&vault), vec![outcome.rollback.expect("rollback")]);

    assert_eq!(undone.skipped, vec!["notes.md"]);
    assert!(undone.restored.is_empty());
    assert_eq!(body(vault.path()), landed);
}

#[test]
fn a_note_that_is_gone_is_named_as_failed_rather_than_recreated() {
    let vault = vault("# TODO Write\n");

    let undone = revert_files(
        dir(&vault),
        vec![FileRollback {
            file: "gone.md".to_string(),
            before: "# TODO Write\n".to_string(),
            after: "# DONE Write\n".to_string(),
        }],
    );

    assert_eq!(undone.failed, vec!["gone.md"]);
    assert!(!vault.path().join("gone.md").exists());
}

#[test]
fn a_path_leaving_the_notes_directory_is_refused() {
    let vault = vault("# TODO Write\n");

    let undone = revert_files(
        dir(&vault),
        vec![FileRollback {
            file: "../elsewhere.md".to_string(),
            before: String::new(),
            after: String::new(),
        }],
    );

    assert_eq!(undone.failed, vec!["../elsewhere.md"]);
}

fn dir(vault: &tempfile::TempDir) -> String {
    vault.path().display().to_string()
}
