//! Tests for the two exceptions a repeating entry can carry: an occurrence
//! that is gone, and one that moved.
//!
//! Both are written into the entry itself: `EXDATE` on the series lists the
//! occurrences it does not have (the extractor's ADR-0031, in the
//! `org-properties` keys of its ADR-0020), and a `MOVED` line names an
//! occurrence and where it is held instead (its ADR-0038). The occurrence it
//! names is written as an inactive timestamp, so both halves of the line are
//! timestamps (its ADR-0039); a bare date is still read. A move needs no
//! `EXDATE` beside it — that is the split RFC 5545 makes between an
//! occurrence that is gone and one that moved.
//!
//! The shape ADR-0031 wrote a move in — a second entry carrying `SERIES_ID`
//! and `RECURRENCE_ID` — is still read: an occurrence standing in one is
//! moved where it stands.

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
fn moving_an_occurrence_writes_a_line_of_the_series_and_leaves_the_series_where_it_is() {
    let vault = vault(SERIES);

    let outcome = move_occurrence(
        target(vault.path(), 1, "English"),
        "2026-08-20".to_string(),
        "2026-08-20".to_string(),
        Some("18:00".to_string()),
    )
    .expect("move");

    assert!(outcome.changed);
    assert_eq!(
        outcome.line,
        "`MOVED: [2026-08-20 Thu] -> <2026-08-20 Thu 18:00>`"
    );
    assert_eq!(
        body(vault.path()),
        "# TODO English\n\
         `SCHEDULED: <2026-08-06 Thu 15:00 +1w>`\n\
         `MOVED: [2026-08-20 Thu] -> <2026-08-20 Thu 18:00>`\n"
    );
}

#[test]
fn a_move_stands_under_the_dates_and_above_the_properties() {
    let vault = vault(
        "# TODO English\n\
         `CREATED: [2025-12-08 Mon 01:06]`\n\
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
    )
    .expect("move");

    assert_eq!(
        body(vault.path()),
        "# TODO English\n\
         `CREATED: [2025-12-08 Mon 01:06]`\n\
         `SCHEDULED: <2026-08-06 Thu 15:00 +1w>`\n\
         `MOVED: [2026-08-20 Thu] -> <2026-08-21 Fri 15:00>`\n\
         ```org-properties\n\
         ID: 4b71\n\
         ```\n"
    );
}

#[test]
fn a_move_needs_no_identifier_on_the_series() {
    // The pair that needed one -- `SERIES_ID` matching an `ID` -- existed to
    // point across entries, and a line inside the entry points at nothing.
    let vault = vault(SERIES);

    move_occurrence(
        target(vault.path(), 1, "English"),
        "2026-08-20".to_string(),
        "2026-08-21".to_string(),
        None,
    )
    .expect("move");

    assert!(
        !body(vault.path()).contains("ID:"),
        "{}",
        body(vault.path())
    );
}

#[test]
fn neither_the_repeater_nor_the_warning_cookie_goes_with_the_occurrence() {
    // One occurrence does not repeat, and how far ahead a deadline warns
    // belongs to the series; the extractor refuses a target carrying either.
    let vault = vault("# TODO Rent\n`DEADLINE: <2026-08-06 Thu ++1m -3d>`\n");

    move_occurrence(
        target(vault.path(), 1, "Rent"),
        "2026-09-06".to_string(),
        "2026-09-04".to_string(),
        None,
    )
    .expect("move");

    assert_eq!(
        body(vault.path()),
        "# TODO Rent\n\
         `DEADLINE: <2026-08-06 Thu ++1m -3d>`\n\
         `MOVED: [2026-09-06 Sun] -> <2026-09-04 Fri>`\n"
    );
}

#[test]
fn the_move_is_spelled_the_way_the_series_is() {
    let vault = vault("# TODO Английский\n`SCHEDULED: <2026-08-06 чт 15:00 +1w>`\n");

    move_occurrence(
        target(vault.path(), 1, "Английский"),
        "2026-08-20".to_string(),
        "2026-08-21".to_string(),
        Some("18:00".to_string()),
    )
    .expect("move");

    assert!(
        body(vault.path()).contains("`MOVED: [2026-08-20 чт] -> <2026-08-21 пт 18:00>`\n"),
        "{}",
        body(vault.path())
    );
}

#[test]
fn a_series_naming_no_weekday_is_still_written_with_one() {
    // Both halves carry a weekday: a day written as digits alone says nothing
    // about a step that landed on the wrong one. Where the series names none,
    // the spelling comes from a weekday the note writes elsewhere.
    let vault = vault(
        "# TODO Английский\n\
         `CREATED: [2025-12-08 Пн 01:06]`\n\
         `SCHEDULED: <2026-08-06 15:00 +1w>`\n",
    );

    move_occurrence(
        target(vault.path(), 1, "Английский"),
        "2026-08-20".to_string(),
        "2026-08-22".to_string(),
        None,
    )
    .expect("move");

    assert!(
        body(vault.path()).contains("`MOVED: [2026-08-20 Чт] -> <2026-08-22 Сб 15:00>`"),
        "{}",
        body(vault.path())
    );
}

#[test]
fn a_note_that_writes_no_weekday_at_all_is_answered_in_english() {
    let vault = vault("# TODO English\n`SCHEDULED: <2026-08-06 15:00 +1w>`\n");

    move_occurrence(
        target(vault.path(), 1, "English"),
        "2026-08-20".to_string(),
        "2026-08-22".to_string(),
        None,
    )
    .expect("move");

    assert!(
        body(vault.path()).contains("`MOVED: [2026-08-20 Thu] -> <2026-08-22 Sat 15:00>`"),
        "{}",
        body(vault.path())
    );
}

#[test]
fn an_occurrence_held_between_two_times_keeps_both_of_them() {
    let vault = vault("# TODO English\n`SCHEDULED: <2026-08-06 Thu 15:00-16:30 +1w>`\n");

    move_occurrence(
        target(vault.path(), 1, "English"),
        "2026-08-20".to_string(),
        "2026-08-22".to_string(),
        None,
    )
    .expect("move");

    assert!(
        body(vault.path()).contains("`MOVED: [2026-08-20 Thu] -> <2026-08-22 Sat 15:00-16:30>`"),
        "{}",
        body(vault.path())
    );
}

#[test]
fn the_heading_is_not_touched_and_nothing_is_appended_to_the_file() {
    let vault = vault("### TODO [#A] English\n`SCHEDULED: <2026-08-06 Thu +1w>`\n");

    move_occurrence(
        target(vault.path(), 1, "English"),
        "2026-08-20".to_string(),
        "2026-08-21".to_string(),
        None,
    )
    .expect("move");

    assert_eq!(
        body(vault.path()),
        "### TODO [#A] English\n\
         `SCHEDULED: <2026-08-06 Thu +1w>`\n\
         `MOVED: [2026-08-20 Thu] -> <2026-08-21 Fri>`\n"
    );
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
    )
    .expect_err("not a time");

    assert!(matches!(error, EditError::InvalidDate { .. }), "{error:?}");
    assert_eq!(body(vault.path()), SERIES);
}

#[test]
fn an_occurrence_moved_twice_is_moved_in_place() {
    let vault = vault(SERIES);
    let at = || target(vault.path(), 1, "English");

    move_occurrence(
        at(),
        "2026-08-20".to_string(),
        "2026-08-20".to_string(),
        Some("18:00".to_string()),
    )
    .expect("first");
    let outcome = move_occurrence(
        at(),
        "2026-08-20".to_string(),
        "2026-08-22".to_string(),
        Some("19:00".to_string()),
    )
    .expect("second");

    assert!(outcome.changed);
    assert_eq!(
        body(vault.path()),
        "# TODO English\n\
         `SCHEDULED: <2026-08-06 Thu 15:00 +1w>`\n\
         `MOVED: [2026-08-20 Thu] -> <2026-08-22 Sat 19:00>`\n"
    );
}

#[test]
fn a_move_written_as_a_bare_date_is_found_and_rewritten_in_place() {
    // The form ADR-0038 wrote before ADR-0039 named the occurrence without
    // brackets. Files still hold it, and a second move has to land on that
    // line rather than beside it.
    let vault = vault(
        "# TODO English\n\
         `SCHEDULED: <2026-08-06 Thu 15:00 +1w>`\n\
         `MOVED: 2026-08-20 -> <2026-08-21 Fri 15:00>`\n",
    );

    let outcome = move_occurrence(
        target(vault.path(), 1, "English"),
        "2026-08-20".to_string(),
        "2026-08-22".to_string(),
        Some("19:00".to_string()),
    )
    .expect("move");

    assert!(outcome.changed);
    assert_eq!(
        body(vault.path()),
        "# TODO English\n\
         `SCHEDULED: <2026-08-06 Thu 15:00 +1w>`\n\
         `MOVED: [2026-08-20 Thu] -> <2026-08-22 Sat 19:00>`\n"
    );
}

#[test]
fn moving_an_occurrence_to_where_it_already_stands_writes_nothing() {
    let vault = vault(SERIES);
    let at = || target(vault.path(), 1, "English");

    move_occurrence(
        at(),
        "2026-08-20".to_string(),
        "2026-08-22".to_string(),
        None,
    )
    .expect("first");
    let before = body(vault.path());
    let outcome = move_occurrence(
        at(),
        "2026-08-20".to_string(),
        "2026-08-22".to_string(),
        None,
    )
    .expect("second");

    assert!(!outcome.changed);
    assert!(outcome.rollback.is_none());
    assert_eq!(body(vault.path()), before);
}

#[test]
fn a_second_occurrence_gets_a_line_of_its_own() {
    let vault = vault(SERIES);
    let at = || target(vault.path(), 1, "English");

    move_occurrence(
        at(),
        "2026-08-20".to_string(),
        "2026-08-21".to_string(),
        None,
    )
    .expect("first");
    move_occurrence(
        at(),
        "2026-08-27".to_string(),
        "2026-08-29".to_string(),
        None,
    )
    .expect("second");

    assert_eq!(
        body(vault.path()),
        "# TODO English\n\
         `SCHEDULED: <2026-08-06 Thu 15:00 +1w>`\n\
         `MOVED: [2026-08-20 Thu] -> <2026-08-21 Fri 15:00>`\n\
         `MOVED: [2026-08-27 Thu] -> <2026-08-29 Sat 15:00>`\n"
    );
}

#[test]
fn an_occurrence_standing_in_an_entry_of_its_own_is_moved_where_it_stands() {
    // The shape ADR-0031 wrote, which files and other tools still hold. A
    // `MOVED` line written here as well would leave two answers for the day.
    let vault = vault(
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
         ```\n",
    );

    move_occurrence(
        target(vault.path(), 1, "English"),
        "2026-08-20".to_string(),
        "2026-08-22".to_string(),
        Some("19:00".to_string()),
    )
    .expect("move");

    let written = body(vault.path());
    assert!(
        written.contains("`SCHEDULED: <2026-08-22 Sat 19:00>`"),
        "{written}"
    );
    assert!(!written.contains("MOVED"), "{written}");
    assert_eq!(written.matches("RECURRENCE_ID").count(), 1, "{written}");
}

#[test]
fn a_moved_occurrence_can_be_put_back() {
    let vault = vault(SERIES);

    let outcome = move_occurrence(
        target(vault.path(), 1, "English"),
        "2026-08-20".to_string(),
        "2026-08-20".to_string(),
        Some("18:00".to_string()),
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
    // The extractor reads `+1wd` as a repeater, and the occurrence has to come
    // out of the series the same way any other one does: this is the unit
    // written with two letters, which a check of its own reads as none.
    let vault = vault("# TODO Standup\n`SCHEDULED: <2026-08-06 Thu 09:00 +1wd>`\n");

    move_occurrence(
        target(vault.path(), 1, "Standup"),
        "2026-08-07".to_string(),
        "2026-08-10".to_string(),
        Some("10:00".to_string()),
    )
    .expect("move");

    assert!(
        body(vault.path()).contains("`MOVED: [2026-08-07 Fri] -> <2026-08-10 Mon 10:00>`"),
        "{}",
        body(vault.path())
    );
}
