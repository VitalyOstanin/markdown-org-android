//! Tests for the two exceptions a repeating entry can carry: an occurrence
//! that is gone, and one that moved.
//!
//! The shape written into the notes is the extractor's ADR-0031, which is
//! iCalendar's answer in the `org-properties` keys of ADR-0020: `EXDATE` on
//! the series lists occurrences it does not have, and a separate entry
//! carrying `SERIES_ID` and `RECURRENCE_ID` stands in for the one occurrence
//! it names. A replacement needs no `EXDATE` beside it — that is the split
//! RFC 5545 makes between an occurrence that is gone and one that moved.

use markdown_org_ffi::{cancel_occurrence, move_occurrence, revert_files, EditError};

mod common;

use common::{body, target, vault};

/// A weekly class, which is the case these operations exist for.
const SERIES: &str = "# TODO English\n`SCHEDULED: <2026-08-06 Thu 15:00 +1w>`\n";

#[test]
fn cancelling_an_occurrence_writes_a_property_block_where_there_was_none() {
    let vault = vault(SERIES);

    let outcome = cancel_occurrence(target(vault.path(), 1, "English"), "2026-08-20".to_string())
        .expect("cancel");

    assert!(outcome.changed);
    assert_eq!(outcome.line, "EXDATE: 2026-08-20");
    assert_eq!(
        body(vault.path()),
        "# TODO English\n\
         `SCHEDULED: <2026-08-06 Thu 15:00 +1w>`\n\
         ```org-properties\n\
         EXDATE: 2026-08-20\n\
         ```\n"
    );
}

#[test]
fn a_second_cancelled_occurrence_joins_the_list() {
    let vault = vault(SERIES);
    let at = || target(vault.path(), 1, "English");

    cancel_occurrence(at(), "2026-08-20".to_string()).expect("first");
    let outcome = cancel_occurrence(at(), "2026-08-27".to_string()).expect("second");

    assert!(outcome.changed);
    assert_eq!(
        body(vault.path()),
        "# TODO English\n\
         `SCHEDULED: <2026-08-06 Thu 15:00 +1w>`\n\
         ```org-properties\n\
         EXDATE: 2026-08-20, 2026-08-27\n\
         ```\n"
    );
}

#[test]
fn cancelling_the_same_occurrence_twice_writes_nothing_the_second_time() {
    let vault = vault(SERIES);
    let at = || target(vault.path(), 1, "English");

    cancel_occurrence(at(), "2026-08-20".to_string()).expect("first");
    let before = body(vault.path());
    let outcome = cancel_occurrence(at(), "2026-08-20".to_string()).expect("second");

    assert!(!outcome.changed);
    assert!(outcome.rollback.is_none());
    assert_eq!(body(vault.path()), before);
}

#[test]
fn a_property_block_the_entry_already_has_takes_the_key() {
    let vault = vault(
        "# TODO English\n\
         `SCHEDULED: <2026-08-06 Thu 15:00 +1w>`\n\
         ```org-properties\n\
         ID: 9f2c\n\
         ```\n\
         \n\
         Textbook, unit four.\n",
    );

    cancel_occurrence(target(vault.path(), 1, "English"), "2026-08-20".to_string())
        .expect("cancel");

    assert_eq!(
        body(vault.path()),
        "# TODO English\n\
         `SCHEDULED: <2026-08-06 Thu 15:00 +1w>`\n\
         ```org-properties\n\
         ID: 9f2c\n\
         EXDATE: 2026-08-20\n\
         ```\n\
         \n\
         Textbook, unit four.\n"
    );
}

#[test]
fn the_block_goes_under_the_planning_lines_and_above_the_body() {
    let vault = vault(
        "## TODO English\n\
         `CREATED: [2026-07-01 Wed]`\n\
         `SCHEDULED: <2026-08-06 Thu 15:00 +1w>`\n\
         \n\
         Textbook, unit four.\n",
    );

    cancel_occurrence(target(vault.path(), 1, "English"), "2026-08-20".to_string())
        .expect("cancel");

    assert_eq!(
        body(vault.path()),
        "## TODO English\n\
         `CREATED: [2026-07-01 Wed]`\n\
         `SCHEDULED: <2026-08-06 Thu 15:00 +1w>`\n\
         ```org-properties\n\
         EXDATE: 2026-08-20\n\
         ```\n\
         \n\
         Textbook, unit four.\n"
    );
}

#[test]
fn an_entry_that_does_not_repeat_has_no_occurrence_to_cancel() {
    let vault = vault("# TODO Write the report\n`SCHEDULED: <2026-08-06 Thu 15:00>`\n");
    let before = body(vault.path());

    let error = cancel_occurrence(
        target(vault.path(), 1, "Write the report"),
        "2026-08-06".to_string(),
    )
    .expect_err("no series");

    assert!(matches!(error, EditError::Unsupported { .. }), "{error:?}");
    assert_eq!(body(vault.path()), before);
}

#[test]
fn a_date_that_is_not_a_date_leaves_the_file_alone() {
    let vault = vault(SERIES);

    let error = cancel_occurrence(
        target(vault.path(), 1, "English"),
        "next thursday".to_string(),
    )
    .expect_err("not a date");

    assert!(matches!(error, EditError::InvalidDate { .. }), "{error:?}");
    assert_eq!(body(vault.path()), SERIES);
}

#[test]
fn a_cancelled_occurrence_can_be_put_back() {
    let vault = vault(SERIES);

    let outcome = cancel_occurrence(target(vault.path(), 1, "English"), "2026-08-20".to_string())
        .expect("cancel");

    let reverted = revert_files(
        vault.path().display().to_string(),
        vec![outcome.rollback.expect("rollback")],
    );

    assert_eq!(reverted.restored, ["notes.md"]);
    assert_eq!(body(vault.path()), SERIES);
}

#[test]
fn moving_an_occurrence_leaves_the_series_where_it_is_and_writes_what_replaces_it() {
    let vault = vault(SERIES);

    let outcome = move_occurrence(
        target(vault.path(), 1, "English"),
        "2026-08-20".to_string(),
        "2026-08-20".to_string(),
        Some("18:00".to_string()),
        "9f2c".to_string(),
    )
    .expect("move");

    assert!(outcome.changed);
    assert_eq!(outcome.line, "# TODO English");
    assert_eq!(
        body(vault.path()),
        "# TODO English\n\
         `SCHEDULED: <2026-08-06 Thu 15:00 +1w>`\n\
         ```org-properties\n\
         ID: 9f2c\n\
         ```\n\
         \n\
         # TODO English\n\
         `SCHEDULED: <2026-08-20 Thu 18:00>`\n\
         ```org-properties\n\
         SERIES_ID: 9f2c\n\
         RECURRENCE_ID: 2026-08-20 15:00\n\
         ```\n"
    );
}

#[test]
fn a_series_that_already_has_an_identifier_keeps_it() {
    let vault = vault(
        "# TODO English\n\
         `SCHEDULED: <2026-08-06 Thu 15:00 +1w>`\n\
         ```org-properties\n\
         ID: 4b71\n\
         ```\n",
    );

    move_occurrence(
        target(vault.path(), 1, "English"),
        "2026-08-20".to_string(),
        "2026-08-21".to_string(),
        None,
        "9f2c".to_string(),
    )
    .expect("move");

    assert_eq!(
        body(vault.path()),
        "# TODO English\n\
         `SCHEDULED: <2026-08-06 Thu 15:00 +1w>`\n\
         ```org-properties\n\
         ID: 4b71\n\
         ```\n\
         \n\
         # TODO English\n\
         `SCHEDULED: <2026-08-21 Fri 15:00>`\n\
         ```org-properties\n\
         SERIES_ID: 4b71\n\
         RECURRENCE_ID: 2026-08-20 15:00\n\
         ```\n"
    );
}

#[test]
fn the_warning_cookie_survives_the_move_and_the_repeater_does_not() {
    let vault = vault("# TODO Rent\n`DEADLINE: <2026-08-06 Thu ++1m -3d>`\n");

    move_occurrence(
        target(vault.path(), 1, "Rent"),
        "2026-09-06".to_string(),
        "2026-09-04".to_string(),
        None,
        "9f2c".to_string(),
    )
    .expect("move");

    assert!(
        body(vault.path()).contains("`DEADLINE: <2026-09-04 Fri -3d>`"),
        "{}",
        body(vault.path())
    );
    assert!(body(vault.path()).contains("RECURRENCE_ID: 2026-09-06\n"));
}

#[test]
fn the_replacement_is_spelled_the_way_the_series_is() {
    let vault = vault("# TODO Английский\nSCHEDULED: <2026-08-06 чт 15:00 +1w>\n");

    move_occurrence(
        target(vault.path(), 1, "Английский"),
        "2026-08-20".to_string(),
        "2026-08-21".to_string(),
        Some("18:00".to_string()),
        "9f2c".to_string(),
    )
    .expect("move");

    assert!(
        body(vault.path()).contains("SCHEDULED: <2026-08-21 пт 18:00>\n"),
        "{}",
        body(vault.path())
    );
}

#[test]
fn a_priority_and_a_heading_level_are_copied_as_they_stand() {
    let vault = vault("### TODO [#A] English\n`SCHEDULED: <2026-08-06 Thu +1w>`\n");

    let outcome = move_occurrence(
        target(vault.path(), 1, "English"),
        "2026-08-20".to_string(),
        "2026-08-21".to_string(),
        None,
        "9f2c".to_string(),
    )
    .expect("move");

    assert_eq!(outcome.line, "### TODO [#A] English");
    assert!(body(vault.path()).contains("### TODO [#A] English\n`SCHEDULED: <2026-08-21 Fri>`\n"));
}

#[test]
fn an_entry_that_does_not_repeat_has_no_occurrence_to_move() {
    let vault = vault("# TODO Write the report\n`SCHEDULED: <2026-08-06 Thu 15:00>`\n");
    let before = body(vault.path());

    let error = move_occurrence(
        target(vault.path(), 1, "Write the report"),
        "2026-08-06".to_string(),
        "2026-08-07".to_string(),
        None,
        "9f2c".to_string(),
    )
    .expect_err("no series");

    assert!(matches!(error, EditError::Unsupported { .. }), "{error:?}");
    assert_eq!(body(vault.path()), before);
}

#[test]
fn a_time_that_is_not_a_time_leaves_the_file_alone() {
    let vault = vault(SERIES);

    let error = move_occurrence(
        target(vault.path(), 1, "English"),
        "2026-08-20".to_string(),
        "2026-08-20".to_string(),
        Some("six in the evening".to_string()),
        "9f2c".to_string(),
    )
    .expect_err("not a time");

    assert!(matches!(error, EditError::InvalidDate { .. }), "{error:?}");
    assert_eq!(body(vault.path()), SERIES);
}

#[test]
fn an_identifier_that_would_not_read_back_is_refused() {
    let vault = vault(SERIES);

    let error = move_occurrence(
        target(vault.path(), 1, "English"),
        "2026-08-20".to_string(),
        "2026-08-20".to_string(),
        None,
        "the: one".to_string(),
    )
    .expect_err("not an identifier");

    assert!(matches!(error, EditError::Unsupported { .. }), "{error:?}");
    assert_eq!(body(vault.path()), SERIES);
}

#[test]
fn an_occurrence_already_replaced_is_not_replaced_a_second_time() {
    let vault = vault(SERIES);
    let at = || target(vault.path(), 1, "English");

    move_occurrence(
        at(),
        "2026-08-20".to_string(),
        "2026-08-20".to_string(),
        Some("18:00".to_string()),
        "9f2c".to_string(),
    )
    .expect("first");
    let before = body(vault.path());

    let error = move_occurrence(
        at(),
        "2026-08-20".to_string(),
        "2026-08-20".to_string(),
        Some("19:00".to_string()),
        "9f2c".to_string(),
    )
    .expect_err("already replaced");

    assert!(matches!(error, EditError::Unsupported { .. }), "{error:?}");
    assert_eq!(body(vault.path()), before);
}

#[test]
fn a_moved_occurrence_can_be_put_back() {
    let vault = vault(SERIES);

    let outcome = move_occurrence(
        target(vault.path(), 1, "English"),
        "2026-08-20".to_string(),
        "2026-08-20".to_string(),
        Some("18:00".to_string()),
        "9f2c".to_string(),
    )
    .expect("move");

    let reverted = revert_files(
        vault.path().display().to_string(),
        vec![outcome.rollback.expect("rollback")],
    );

    assert_eq!(reverted.restored, ["notes.md"]);
    assert_eq!(body(vault.path()), SERIES);
}

#[test]
fn a_working_day_series_leaves_its_repeater_behind_too() {
    // The extractor reads `+1wd` as a repeater, and the replacement must come
    // out of the series the same way any other one does: this is the unit
    // written with two letters, which a check of its own reads as none.
    let vault = vault("# TODO Standup\n`SCHEDULED: <2026-08-06 Thu 09:00 +1wd>`\n");

    move_occurrence(
        target(vault.path(), 1, "Standup"),
        "2026-08-07".to_string(),
        "2026-08-10".to_string(),
        Some("10:00".to_string()),
        "9f2c".to_string(),
    )
    .expect("move");

    assert!(
        body(vault.path()).contains("`SCHEDULED: <2026-08-10 Mon 10:00>`"),
        "{}",
        body(vault.path())
    );
}
