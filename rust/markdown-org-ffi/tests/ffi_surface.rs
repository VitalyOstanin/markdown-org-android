//! Tests for the FFI projection.
//!
//! The extractor has its own tests; what needs covering here is the layer
//! this crate adds — the conversion from the extractor's types to the ones
//! that cross the boundary, and the mapping of failures onto the error enum
//! a Kotlin caller catches.

use std::fs;

use markdown_org_ffi::{scan, scan_agenda, ExtractError, Options, Scope, TaskType, TimestampType};

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
const DUE: &str = "# TODO File the return\n`DEADLINE: <2026-03-02 Mon>`\n";
const BARE: &str = "# TODO Ring the dentist\n`<2026-03-02 Mon>`\n";
const UNDATED: &str = "# TODO Think about it\n";

#[test]
fn scan_projects_a_task_onto_the_ffi_record() {
    let vault = write_vault(&[("notes.md", TIMED)]);

    let result = scan(vault.path().display().to_string(), options()).expect("scan");

    assert_eq!(result.tasks.len(), 1);
    let task = &result.tasks[0];
    assert_eq!(task.heading, "Write the report");
    assert_eq!(task.task_type, Some(TaskType::Todo));
    assert_eq!(task.timestamp_type, Some(TimestampType::Scheduled));
    assert_eq!(task.timestamp_date.as_deref(), Some("2026-03-02"));
    assert_eq!(task.timestamp_time.as_deref(), Some("10:00"));
    assert_eq!(result.stats.files_processed, 1);
    assert_eq!(result.stats.files_failed, 0);
    assert!(!result.stats.truncated);
    assert!(!result.stats.has_warnings);
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
fn every_planning_keyword_arrives_as_a_variant_of_its_own() {
    // The keyword decides what a row looks like and which shift buttons it
    // offers. As a string it was compared against literals spelled out in two
    // Kotlin files, where a change of spelling here would have gone unnoticed
    // by the compiler and shown up as a task that stopped being a deadline.
    let vault = write_vault(&[("due.md", DUE), ("timed.md", TIMED)]);

    let result = scan(vault.path().display().to_string(), options()).expect("scan");

    let kinds: Vec<_> = result
        .tasks
        .iter()
        .map(|task| task.timestamp_type)
        .collect();
    assert!(
        kinds.contains(&Some(TimestampType::Deadline)),
        "got {kinds:?}"
    );
    assert!(
        kinds.contains(&Some(TimestampType::Scheduled)),
        "got {kinds:?}"
    );
}

#[test]
fn a_timestamp_with_no_keyword_is_told_apart_from_no_timestamp_at_all() {
    // Two different things the record used to blur: the extractor calls a
    // bare timestamp `PLAIN`, and only a task carrying no timestamp leaves
    // the field empty.
    let vault = write_vault(&[("bare.md", BARE), ("undated.md", UNDATED)]);

    let result = scan(vault.path().display().to_string(), options()).expect("scan");

    let bare = task_named(&result.tasks, "Ring the dentist");
    let undated = task_named(&result.tasks, "Think about it");
    assert_eq!(bare.timestamp_type, Some(TimestampType::Plain));
    assert_eq!(undated.timestamp_type, None);
}

fn task_named<'a>(
    tasks: &'a [markdown_org_ffi::Task],
    heading: &str,
) -> &'a markdown_org_ffi::Task {
    tasks
        .iter()
        .find(|task| task.heading == heading)
        .unwrap_or_else(|| panic!("no task named {heading} among {tasks:?}"))
}

#[test]
fn both_walks_read_the_same_defaults() {
    // The two entry points prepare the walk the same way, and the defaults
    // they fill in are stated once. Written down twice they drifted: a note
    // that one walk could see and the other could not is the shape that
    // failure takes.
    let vault = write_vault(&[("notes.md", TIMED), ("notes.txt", REPEATING)]);

    let scanned = scan(vault.path().display().to_string(), options()).expect("scan");
    let agenda = scan_agenda(
        vault.path().display().to_string(),
        Scope::Tasks,
        "2026-03-02".to_string(),
        None,
        "Europe/Moscow".to_string(),
        false,
        options(),
    )
    .expect("agenda");

    assert_eq!(scanned.stats.files_processed, 1, "the default glob is *.md");
    assert_eq!(
        scanned.stats.files_processed, agenda.stats.files_processed,
        "one walk saw a different set of files than the other"
    );
    assert_eq!(scanned.tasks.len(), agenda.tasks.len());
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
        result.stats.truncated,
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
        None,
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
        None,
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
        None,
        "Europe/Moscow".to_string(),
        false,
        options(),
    )
    .expect("agenda");
    let a_day_later = scan_agenda(
        vault.path().display().to_string(),
        Scope::Day,
        "2026-03-03".to_string(),
        None,
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
fn the_window_moves_without_taking_today_with_it() {
    // Stepping a month forward moves what is drawn, not what "now" means.
    // Passed as one date the two are the same thing, and the buckets that
    // exist only relative to today — overdue, upcoming — follow the reader
    // around the calendar: a task a week late is reported as a day late on
    // the day after it slipped, and a month late once the reader has paged
    // there.
    let vault = write_vault(&[("notes.md", TIMED)]);

    let paged = scan_agenda(
        vault.path().display().to_string(),
        Scope::Month,
        "2026-03-03".to_string(),
        Some("2026-04-15".to_string()),
        "Europe/Moscow".to_string(),
        false,
        options(),
    )
    .expect("agenda");

    assert_eq!(paged.days[0].date, "2026-04-01", "the window is April");
    assert_eq!(paged.days.last().expect("a last day").date, "2026-04-30");
    assert!(
        paged.days.iter().all(|day| day.overdue.is_empty()),
        "today is March, so April carries nobody's arrears"
    );
}

/// `# TODO Отчёт` plus a timestamp, with the title in CP1251 — a note saved
/// by a Windows editor and committed to the same repository.
fn cp1251_note() -> Vec<u8> {
    let mut bytes = b"# TODO ".to_vec();
    bytes.extend_from_slice(&[0xCE, 0xF2, 0xF7, 0xB8, 0xF2, b'\n']);
    bytes.extend_from_slice(b"`SCHEDULED: <2026-03-02 Mon>`\n");
    bytes
}

#[test]
fn an_agenda_says_a_file_was_skipped_for_its_encoding_rather_than_hiding_it() {
    // Without this the agenda of a directory holding one readable note and
    // one CP1251 note is indistinguishable from the agenda of a directory
    // holding one note: no tasks, no reason, no sign.
    let vault = write_vault(&[("ok.md", TIMED)]);
    fs::write(vault.path().join("cp1251.md"), cp1251_note()).expect("write file");

    let result = scan_agenda(
        vault.path().display().to_string(),
        Scope::Day,
        "2026-03-02".to_string(),
        None,
        "Europe/Moscow".to_string(),
        false,
        options(),
    )
    .expect("agenda");

    assert_eq!(result.stats.files_processed, 1);
    assert_eq!(result.stats.files_not_utf8, 1);
    assert_eq!(result.stats.files_failed, 0);
    assert!(result.stats.has_warnings);
}

#[test]
fn a_scan_reports_the_same_statistics_as_an_agenda() {
    let vault = write_vault(&[("ok.md", TIMED)]);
    fs::write(vault.path().join("cp1251.md"), cp1251_note()).expect("write file");

    let result = scan(vault.path().display().to_string(), options()).expect("scan");

    assert_eq!(result.stats.files_not_utf8, 1);
    assert_eq!(result.stats.files_processed, 1);
    assert!(result.stats.has_warnings);
}

#[test]
fn a_task_whose_path_is_not_utf8_is_counted_so_it_can_be_marked_uneditable() {
    // A filename on Linux is an arbitrary byte sequence. Such a path reaches
    // the caller with U+FFFD in place of the invalid bytes, so an edit aimed
    // at it looks for a file that does not exist; the count is what lets the
    // interface refuse before the user taps.
    use std::os::unix::ffi::OsStrExt;

    let vault = write_vault(&[("ok.md", TIMED)]);
    let name = std::ffi::OsStr::from_bytes(b"bad\xffname.md");
    fs::write(vault.path().join(name), TIMED).expect("write file");

    let result = scan(vault.path().display().to_string(), options()).expect("scan");

    assert_eq!(result.stats.files_processed, 2);
    assert_eq!(result.stats.nonutf8_paths, 1);
    assert!(result.stats.has_warnings);
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
        None,
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
        None,
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
