//! Tests for the FFI projection.
//!
//! The extractor has its own tests; what needs covering here is the layer
//! this crate adds — the conversion from the extractor's types to the ones
//! that cross the boundary, and the mapping of failures onto the error enum
//! a Kotlin caller catches.

use std::fs;

use markdown_org_ffi::{scan, scan_agenda, ExtractError, Options, Scope, TaskType};

fn options() -> Options {
    Options {
        glob: None,
        locale: None,
        max_tasks: None,
    }
}

fn write_vault(files: &[(&str, &str)]) -> tempfile::TempDir {
    let dir = tempfile::tempdir().expect("tempdir");
    for (name, body) in files {
        let path = dir.path().join(name);
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).expect("create parent");
        }
        fs::write(&path, body).expect("write file");
    }
    dir
}

const TIMED: &str = "# TODO Write the report\n`SCHEDULED: <2026-03-02 Mon 10:00>`\n";
const REPEATING: &str = "# TODO Weekly review\n`SCHEDULED: <2026-03-02 Mon ++7d>`\n";
const CANCELLED: &str = "# CANCELED Old plan\n`SCHEDULED: <2026-03-02 Mon>`\n";

#[test]
fn scan_projects_a_task_onto_the_ffi_record() {
    let vault = write_vault(&[("notes.md", TIMED)]);

    let result = scan(vault.path().display().to_string(), options()).expect("scan");

    assert_eq!(result.tasks.len(), 1);
    let task = &result.tasks[0];
    assert_eq!(task.heading, "Write the report");
    assert_eq!(task.task_type, Some(TaskType::Todo));
    assert_eq!(task.timestamp_type.as_deref(), Some("SCHEDULED"));
    assert_eq!(task.timestamp_date.as_deref(), Some("2026-03-02"));
    assert_eq!(task.timestamp_time.as_deref(), Some("10:00"));
    assert_eq!(result.files_processed, 1);
    assert_eq!(result.files_failed, 0);
    assert!(!result.truncated);
}

#[test]
fn both_cancelled_spellings_collapse_into_one_variant() {
    // The extractor keeps CANCELLED and CANCELED apart so it can write the
    // file back unchanged. A reader does not care, and carrying the
    // distinction across the boundary would put it in the UI's way.
    let vault = write_vault(&[("a.md", CANCELLED)]);

    let result = scan(vault.path().display().to_string(), options()).expect("scan");

    assert_eq!(result.tasks[0].task_type, Some(TaskType::Cancelled));
}

#[test]
fn a_repeater_survives_the_conversion() {
    let vault = write_vault(&[("notes.md", REPEATING)]);

    let result = scan(vault.path().display().to_string(), options()).expect("scan");

    assert_eq!(result.tasks[0].timestamp_repeater.as_deref(), Some("++7d"));
}

#[test]
fn the_task_cap_is_reported_rather_than_silently_applied() {
    let vault = write_vault(&[("a.md", TIMED), ("b.md", REPEATING)]);
    let capped = Options {
        max_tasks: Some(1),
        ..options()
    };

    let result = scan(vault.path().display().to_string(), capped).expect("scan");

    assert_eq!(result.tasks.len(), 1);
    assert!(
        result.truncated,
        "a truncated result must say so, or the UI shows a partial list as complete"
    );
}

#[test]
fn the_glob_reaches_the_walker() {
    let vault = write_vault(&[("keep.md", TIMED), ("skip.md", REPEATING)]);
    let filtered = Options {
        glob: Some("keep.md".to_string()),
        ..options()
    };

    let result = scan(vault.path().display().to_string(), filtered).expect("scan");

    assert_eq!(result.tasks.len(), 1);
    assert_eq!(result.tasks[0].heading, "Write the report");
}

#[test]
fn a_day_agenda_splits_tasks_into_buckets() {
    let vault = write_vault(&[("notes.md", TIMED), ("more.md", REPEATING)]);

    let result = scan_agenda(
        vault.path().display().to_string(),
        Scope::Day,
        "2026-03-02".to_string(),
        "Europe/Moscow".to_string(),
        false,
        options(),
    )
    .expect("agenda");

    assert_eq!(result.days.len(), 1);
    assert!(
        result.tasks.is_empty(),
        "a day scope fills days, not the flat list"
    );
    let day = &result.days[0];
    assert_eq!(day.date, "2026-03-02");
    assert_eq!(day.scheduled_timed.len(), 1);
    assert_eq!(
        day.scheduled_timed[0].timestamp_time.as_deref(),
        Some("10:00")
    );
    assert_eq!(day.scheduled_no_time.len(), 1);
}

#[test]
fn the_tasks_scope_fills_the_flat_list_instead_of_days() {
    let vault = write_vault(&[("notes.md", TIMED)]);

    let result = scan_agenda(
        vault.path().display().to_string(),
        Scope::Tasks,
        "2026-03-02".to_string(),
        "Europe/Moscow".to_string(),
        false,
        options(),
    )
    .expect("agenda");

    assert!(result.days.is_empty());
    assert_eq!(result.tasks.len(), 1);
}

#[test]
fn the_agenda_is_anchored_on_the_supplied_date_not_the_clock() {
    // The whole point of passing current_date: the same vault must render
    // the same agenda whenever it is asked for.
    let vault = write_vault(&[("notes.md", TIMED)]);

    let on_the_day = scan_agenda(
        vault.path().display().to_string(),
        Scope::Day,
        "2026-03-02".to_string(),
        "Europe/Moscow".to_string(),
        false,
        options(),
    )
    .expect("agenda");
    let a_day_later = scan_agenda(
        vault.path().display().to_string(),
        Scope::Day,
        "2026-03-03".to_string(),
        "Europe/Moscow".to_string(),
        false,
        options(),
    )
    .expect("agenda");

    assert_eq!(on_the_day.days[0].scheduled_timed.len(), 1);
    assert!(a_day_later.days[0].scheduled_timed.is_empty());
    assert_eq!(
        a_day_later.days[0].overdue.len(),
        1,
        "yesterday's task is overdue, and the offset says by how much"
    );
    assert_eq!(a_day_later.days[0].overdue[0].days_offset, Some(-1));
}

#[test]
fn a_missing_directory_arrives_as_its_own_variant() {
    let vault = write_vault(&[]);
    let missing = vault.path().join("nowhere").display().to_string();

    let error = scan(missing, options()).expect_err("must fail");

    assert!(
        matches!(error, ExtractError::InvalidDirectory { .. }),
        "got {error:?}"
    );
}

#[test]
fn a_bad_timezone_arrives_as_its_own_variant() {
    let vault = write_vault(&[("notes.md", TIMED)]);

    let error = scan_agenda(
        vault.path().display().to_string(),
        Scope::Day,
        "2026-03-02".to_string(),
        "Nowhere/Nothing".to_string(),
        false,
        options(),
    )
    .expect_err("must fail");

    assert!(
        matches!(error, ExtractError::InvalidTimezone { .. }),
        "got {error:?}"
    );
}

#[test]
fn a_bad_date_arrives_as_its_own_variant() {
    let vault = write_vault(&[("notes.md", TIMED)]);

    let error = scan_agenda(
        vault.path().display().to_string(),
        Scope::Day,
        "not-a-date".to_string(),
        "Europe/Moscow".to_string(),
        false,
        options(),
    )
    .expect_err("must fail");

    assert!(
        matches!(error, ExtractError::InvalidDate { .. }),
        "got {error:?}"
    );
}

#[test]
fn a_malformed_glob_arrives_as_its_own_variant() {
    let vault = write_vault(&[("notes.md", TIMED)]);
    let broken = Options {
        glob: Some("[".to_string()),
        ..options()
    };

    let error = scan(vault.path().display().to_string(), broken).expect_err("must fail");

    assert!(
        matches!(error, ExtractError::InvalidGlob { .. }),
        "got {error:?}"
    );
}
