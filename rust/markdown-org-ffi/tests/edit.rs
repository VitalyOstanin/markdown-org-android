//! Tests for the editing surface.
//!
//! Every case asserts on the whole file, not on the edited line alone: an
//! edit that changes a neighbouring line is exactly the failure that makes
//! git merges conflict, and it would go unnoticed by an assertion scoped to
//! the line under test.

use std::fs;

use markdown_org_ffi::{set_priority, set_status, EditError, TaskType};

mod common;

use common::{body, target, vault};

const TWO_TASKS: &str = "\
# TODO Write the report
`SCHEDULED: <2026-07-28 Tue>`

# TODO Water the plants
";

#[test]
fn setting_a_status_rewrites_only_the_keyword() {
    let vault = vault(TWO_TASKS);

    let outcome = set_status(
        target(vault.path(), 1, "Write the report"),
        Some(TaskType::Done),
    )
    .expect("edit");

    assert_eq!(outcome.line, "# DONE Write the report");
    assert!(outcome.changed);
    assert_eq!(
        body(vault.path()),
        "\
# DONE Write the report
`SCHEDULED: <2026-07-28 Tue>`

# TODO Water the plants
"
    );
}

#[test]
fn clearing_a_status_leaves_the_title_in_place() {
    let vault = vault("## TODO [#A] Write the report\n");

    let outcome = set_status(target(vault.path(), 1, "Write the report"), None).expect("edit");

    assert_eq!(outcome.line, "## [#A] Write the report");
}

#[test]
fn a_status_is_inserted_ahead_of_the_priority_cookie() {
    let vault = vault("# [#A] Write the report\n");

    let outcome = set_status(
        target(vault.path(), 1, "Write the report"),
        Some(TaskType::Todo),
    )
    .expect("edit");

    assert_eq!(outcome.line, "# TODO [#A] Write the report");
}

#[test]
fn the_cancelled_spelling_already_in_the_file_is_kept() {
    let vault = vault("# CANCELED Old plan\n");

    let outcome = set_status(
        target(vault.path(), 1, "Old plan"),
        Some(TaskType::Cancelled),
    )
    .expect("edit");

    // Both spellings are the same status, so the file is left alone rather
    // than respelled: the user's file, the user's spelling.
    assert_eq!(outcome.line, "# CANCELED Old plan");
    assert!(!outcome.changed);
}

#[test]
fn text_between_the_keyword_and_the_cookie_survives() {
    let vault = vault("# TODO leftover [#B] Title\n");

    let outcome = set_status(target(vault.path(), 1, "Title"), Some(TaskType::Done)).expect("edit");

    assert_eq!(outcome.line, "# DONE leftover [#B] Title");
}

#[test]
fn setting_a_priority_replaces_the_cookie() {
    let vault = vault("# TODO [#A] Write the report\n");

    let outcome = set_priority(
        target(vault.path(), 1, "Write the report"),
        Some("B".to_string()),
    )
    .expect("edit");

    assert_eq!(outcome.line, "# TODO [#B] Write the report");
}

#[test]
fn a_priority_is_inserted_after_the_keyword() {
    let vault = vault("# TODO Write the report\n");

    let outcome = set_priority(
        target(vault.path(), 1, "Write the report"),
        Some("A".to_string()),
    )
    .expect("edit");

    assert_eq!(outcome.line, "# TODO [#A] Write the report");
}

#[test]
fn clearing_a_priority_removes_the_cookie_and_its_gap() {
    let vault = vault("# TODO [#A] Write the report\n");

    let outcome = set_priority(target(vault.path(), 1, "Write the report"), None).expect("edit");

    assert_eq!(outcome.line, "# TODO Write the report");
}

#[test]
fn a_priority_outside_the_accepted_range_is_refused() {
    let vault = vault("# TODO Write the report\n");

    let error = set_priority(
        target(vault.path(), 1, "Write the report"),
        Some("65".to_string()),
    )
    .expect_err("must refuse");

    assert!(
        matches!(error, EditError::InvalidPriority { .. }),
        "{error:?}"
    );
    assert_eq!(body(vault.path()), "# TODO Write the report\n");
}

#[test]
fn a_heading_that_moved_is_refused_rather_than_overwritten() {
    let vault = vault(TWO_TASKS);

    // The agenda said the heading was on line 4; by the time the edit runs
    // the file holds a different heading there.
    let error = set_status(
        target(vault.path(), 4, "Write the report"),
        Some(TaskType::Done),
    )
    .expect_err("must refuse");

    assert!(matches!(error, EditError::Stale { .. }), "{error:?}");
    assert_eq!(body(vault.path()), TWO_TASKS);
}

#[test]
fn a_line_that_is_not_a_heading_is_refused() {
    let vault = vault(TWO_TASKS);

    let error = set_status(
        target(vault.path(), 2, "Write the report"),
        Some(TaskType::Done),
    )
    .expect_err("must refuse");

    assert!(matches!(error, EditError::Stale { .. }), "{error:?}");
}

#[test]
fn a_line_past_the_end_of_the_file_is_refused() {
    let vault = vault(TWO_TASKS);

    let error = set_status(
        target(vault.path(), 99, "Write the report"),
        Some(TaskType::Done),
    )
    .expect_err("must refuse");

    assert!(matches!(error, EditError::Stale { .. }), "{error:?}");
}

#[test]
fn a_missing_file_is_refused() {
    let vault = vault(TWO_TASKS);
    let mut where_to = target(vault.path(), 1, "Write the report");
    where_to.file = "gone.md".to_string();

    let error = set_status(where_to, Some(TaskType::Done)).expect_err("must refuse");

    assert!(matches!(error, EditError::NotFound { .. }), "{error:?}");
}

#[test]
fn a_path_pointing_outside_the_vault_is_refused() {
    let vault = vault(TWO_TASKS);
    let mut where_to = target(vault.path(), 1, "Write the report");
    where_to.file = "../outside.md".to_string();

    let error = set_status(where_to, Some(TaskType::Done)).expect_err("must refuse");

    assert!(matches!(error, EditError::NotFound { .. }), "{error:?}");
}

/// `..` is not the only way out of the notes directory: a symlink inside it
/// points anywhere, and an edit that follows one writes there. The path comes
/// from a scan that does not follow links, but `EditTarget.file` is a plain
/// string on the FFI surface and the guard is what the type promises.
#[test]
#[cfg(unix)]
fn a_symlink_leading_out_of_the_vault_is_refused() {
    let vault = vault(TWO_TASKS);
    let elsewhere = tempfile::tempdir().expect("tempdir");
    let outside = elsewhere.path().join("outside.md");
    // The same content the vault holds, so the edit would go through and the
    // test fails on the write rather than on a heading that does not match.
    fs::write(&outside, TWO_TASKS).expect("write");
    std::os::unix::fs::symlink(&outside, vault.path().join("link.md")).expect("symlink");

    let mut where_to = target(vault.path(), 1, "Write the report");
    where_to.file = "link.md".to_string();

    let error = set_status(where_to, Some(TaskType::Done)).expect_err("must refuse");

    assert!(matches!(error, EditError::NotFound { .. }), "{error:?}");
    assert_eq!(
        fs::read_to_string(&outside).expect("read"),
        TWO_TASKS,
        "the file behind the link must be untouched",
    );
}

#[test]
fn windows_line_endings_are_preserved() {
    let vault = vault("# TODO Write the report\r\n# TODO Water the plants\r\n");

    set_status(
        target(vault.path(), 1, "Write the report"),
        Some(TaskType::Done),
    )
    .expect("edit");

    assert_eq!(
        body(vault.path()),
        "# DONE Write the report\r\n# TODO Water the plants\r\n"
    );
}

#[test]
fn a_file_without_a_trailing_newline_does_not_grow_one() {
    let vault = vault("# TODO Write the report");

    set_status(
        target(vault.path(), 1, "Write the report"),
        Some(TaskType::Done),
    )
    .expect("edit");

    assert_eq!(body(vault.path()), "# DONE Write the report");
}

#[test]
fn a_write_replaces_the_file_rather_than_truncating_it_in_place() {
    // The notes live in a git checkout, so a write interrupted halfway
    // through would leave a truncated file that the next successful edit
    // commits. Writing beside the target and renaming over it is what makes
    // an interrupted write leave the original alone; the inode changing is
    // the observable trace of that rename.
    use std::os::unix::fs::MetadataExt;

    let vault = vault(TWO_TASKS);
    let path = vault.path().join("notes.md");
    let before = fs::metadata(&path).expect("metadata");

    set_status(
        target(vault.path(), 1, "Write the report"),
        Some(TaskType::Done),
    )
    .expect("edit");

    let after = fs::metadata(&path).expect("metadata");
    assert_ne!(before.ino(), after.ino(), "the file was written in place");

    let left_behind: Vec<_> = fs::read_dir(vault.path())
        .expect("read_dir")
        .filter_map(|entry| entry.ok())
        .map(|entry| entry.file_name().to_string_lossy().into_owned())
        .filter(|name| name != "notes.md")
        .collect();
    assert!(left_behind.is_empty(), "left behind: {left_behind:?}");
}

#[test]
fn a_write_keeps_the_permissions_the_file_had() {
    use std::os::unix::fs::PermissionsExt;

    let vault = vault(TWO_TASKS);
    let path = vault.path().join("notes.md");
    fs::set_permissions(&path, fs::Permissions::from_mode(0o640)).expect("chmod");

    set_status(
        target(vault.path(), 1, "Write the report"),
        Some(TaskType::Done),
    )
    .expect("edit");

    let mode = fs::metadata(&path).expect("metadata").permissions().mode();
    assert_eq!(mode & 0o777, 0o640, "mode is now {:o}", mode & 0o777);
}

#[test]
fn a_file_that_is_not_utf8_is_named_as_such_rather_than_as_an_io_failure() {
    // A note written in a Windows editor and committed to the same
    // repository arrives as CP1251. "stream did not contain valid UTF-8" is
    // indistinguishable from a filesystem failure for the person reading it.
    let vault = vault("");
    // `# TODO Отчёт` with the title in CP1251.
    let mut cp1251 = b"# TODO ".to_vec();
    cp1251.extend_from_slice(&[0xCE, 0xF2, 0xF7, 0xB8, 0xF2, b'\n']);
    fs::write(vault.path().join("notes.md"), cp1251).expect("write");

    let error = set_status(target(vault.path(), 1, "Отчёт"), Some(TaskType::Done))
        .expect_err("must refuse");

    assert!(matches!(error, EditError::NotUtf8 { .. }), "{error:?}");
}

#[test]
fn a_multibyte_heading_is_edited_on_a_character_boundary() {
    let vault = vault("# TODO [#A] Отчёт за неделю\n");

    let outcome = set_status(
        target(vault.path(), 1, "Отчёт за неделю"),
        Some(TaskType::Done),
    )
    .expect("edit");

    assert_eq!(outcome.line, "# DONE [#A] Отчёт за неделю");
}

/// The heading an edit is aimed at, taken from a scan of the notes rather
/// than from the file text — which is how the application builds it, and the
/// only way inline markup in a heading is exercised at all.
fn scanned_target(dir: &std::path::Path, title: &str) -> markdown_org_ffi::EditTarget {
    let result = markdown_org_ffi::scan(
        dir.display().to_string(),
        markdown_org_ffi::Options {
            glob: None,
            locale: None,
            max_tasks: None,
        },
    )
    .expect("scan");

    let task = result
        .tasks
        .iter()
        .find(|task| task.heading.contains(title))
        .unwrap_or_else(|| panic!("no task holding {title:?} in {:?}", result.tasks));

    markdown_org_ffi::EditTarget {
        dir: dir.display().to_string(),
        file: task.file.clone(),
        line: task.line,
        heading: task.heading.clone(),
    }
}

#[test]
fn a_heading_carrying_bold_text_can_still_be_edited() {
    // The scan hands back the heading with the markup taken off, so comparing
    // it against the raw line would refuse every edit of a formatted heading
    // as stale — while the file has not moved at all.
    let vault = vault("# TODO **Отчёт** за июль\n");

    let outcome = set_status(
        scanned_target(vault.path(), "за июль"),
        Some(TaskType::Done),
    )
    .expect("set status");

    assert_eq!(outcome.line, "# DONE **Отчёт** за июль");
    assert_eq!(body(vault.path()), "# DONE **Отчёт** за июль\n");
}

#[test]
fn a_heading_carrying_inline_code_can_still_be_edited() {
    let vault = vault("# TODO `build` is broken\n");

    let outcome = set_status(
        scanned_target(vault.path(), "is broken"),
        Some(TaskType::Done),
    )
    .expect("set status");

    assert_eq!(outcome.line, "# DONE `build` is broken");
}

#[test]
fn a_heading_carrying_a_link_can_still_be_edited() {
    let vault = vault("# TODO Read [the spec](https://example.invalid/spec)\n");

    let outcome = set_status(
        scanned_target(vault.path(), "the spec"),
        Some(TaskType::Done),
    )
    .expect("set status");

    assert_eq!(
        outcome.line,
        "# DONE Read [the spec](https://example.invalid/spec)"
    );
}

#[test]
fn a_heading_behind_a_byte_order_mark_can_still_be_edited() {
    // Editors on Windows save "UTF-8 with BOM": the file opens with U+FEFF.
    // The scan reads past it and hands back a task on line 1, but while the
    // document kept the mark on that line the heading grammar saw
    // "\u{FEFF}# TODO ..." -- which is not a heading -- and refused the edit
    // as stale. The first task of such a file could not be acted on at all.
    let vault = vault("\u{FEFF}# TODO Write the report\n");

    let outcome = set_status(
        scanned_target(vault.path(), "Write the report"),
        Some(TaskType::Done),
    )
    .expect("set status");

    assert_eq!(outcome.line, "# DONE Write the report");
    // The mark is the file's, not the line's: it goes back where it was.
    assert_eq!(body(vault.path()), "\u{FEFF}# DONE Write the report\n");
}

#[test]
fn a_file_without_a_byte_order_mark_is_not_given_one() {
    let vault = vault("# TODO Write the report\n");

    set_status(
        target(vault.path(), 1, "Write the report"),
        Some(TaskType::Done),
    )
    .expect("set status");

    assert_eq!(body(vault.path()), "# DONE Write the report\n");
}

#[test]
fn a_heading_that_really_did_change_is_still_refused() {
    // The comparison is loosened, not dropped: a line that now holds another
    // task must not be written to.
    let vault = vault("# TODO Water the plants\n");

    let error = set_status(
        target(vault.path(), 1, "Write the report"),
        Some(TaskType::Done),
    )
    .expect_err("must refuse");

    assert!(matches!(error, EditError::Stale { .. }), "{error:?}");
}
