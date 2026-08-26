//! Tests for carrying an entry from one file of a collection to another.
//!
//! What they hold to is the pair of files: what left the one, what arrived in
//! the other, and that everything neither of them was asked about comes back
//! byte for byte. The entry itself is compared as text rather than re-read as
//! a task — the point of a move is that the text does not change.

use std::fs;

use markdown_org_ffi::{move_entry, revert_files, EditTarget, WritePosition};

mod common;

use common::{body, target, vault};

/// The other file of the collection, as it stands after the move.
fn other(dir: &tempfile::TempDir, name: &str) -> String {
    fs::read_to_string(dir.path().join(name)).expect("read")
}

/// Put a second file in the collection.
fn write(dir: &tempfile::TempDir, name: &str, content: &str) {
    fs::write(dir.path().join(name), content).expect("write");
}

/// Aim a move at the entry `notes.md` holds on `line`.
fn from(dir: &tempfile::TempDir, line: u32, heading: &str) -> EditTarget {
    target(dir.path(), line, heading)
}

#[test]
fn the_entry_leaves_one_file_and_arrives_in_the_other() {
    let vault = vault("# Notes\n\n## TODO Write the report\n\n## TODO Ring the dentist\n");
    write(&vault, "main.md", "# Main\n\n## TODO Buy milk\n");

    let outcome = move_entry(
        from(&vault, 3, "Write the report"),
        "main.md".to_string(),
        WritePosition::End,
    )
    .expect("move");

    assert_eq!(outcome.line, "## TODO Write the report");
    assert_eq!(outcome.file, "main.md");
    assert_eq!(body(vault.path()), "# Notes\n\n## TODO Ring the dentist\n");
    assert_eq!(
        other(&vault, "main.md"),
        "# Main\n\n## TODO Buy milk\n\n## TODO Write the report\n"
    );
}

/// The entry lands where the collection writes its new tasks, which for a
/// collection that writes at the start is above the entries already there.
#[test]
fn the_entry_arrives_at_the_start_where_that_is_where_the_collection_writes() {
    let vault = vault("# Notes\n\n## TODO Write the report\n");
    write(&vault, "main.md", "# Main\n\n## TODO Buy milk\n");

    move_entry(
        from(&vault, 3, "Write the report"),
        "main.md".to_string(),
        WritePosition::Start,
    )
    .expect("move");

    assert_eq!(
        other(&vault, "main.md"),
        "## TODO Write the report\n\n# Main\n\n## TODO Buy milk\n"
    );
}

/// The whole of the entry travels: its planning line, its property block, its
/// text and the headings nested under it.
#[test]
fn everything_under_the_heading_travels_with_it() {
    let vault = vault(concat!(
        "# Notes\n",
        "\n",
        "## TODO Write the report\n",
        "`SCHEDULED: <2026-08-19 Wed>`\n",
        "```properties\n",
        "SERIES-ID: 4f2\n",
        "```\n",
        "\n",
        "The figures are in the drive.\n",
        "\n",
        "### The appendix\n",
        "Which nobody reads.\n",
        "\n",
        "## TODO Ring the dentist\n",
    ));
    write(&vault, "main.md", "# Main\n");

    move_entry(
        from(&vault, 3, "Write the report"),
        "main.md".to_string(),
        WritePosition::End,
    )
    .expect("move");

    assert_eq!(body(vault.path()), "# Notes\n\n## TODO Ring the dentist\n");
    assert_eq!(
        other(&vault, "main.md"),
        concat!(
            "# Main\n",
            "\n",
            "## TODO Write the report\n",
            "`SCHEDULED: <2026-08-19 Wed>`\n",
            "```properties\n",
            "SERIES-ID: 4f2\n",
            "```\n",
            "\n",
            "The figures are in the drive.\n",
            "\n",
            "### The appendix\n",
            "Which nobody reads.\n",
        )
    );
}

/// A heading above the entry in level ends it, and stays where it is: the
/// entry moved is the one pointed at, not the section it happens to sit in.
#[test]
fn a_heading_shallower_than_the_entry_ends_it() {
    let vault =
        vault("# Notes\n\n### TODO Write the report\nThe figures.\n\n## Later\nNothing yet.\n");
    write(&vault, "main.md", "# Main\n");

    move_entry(
        from(&vault, 3, "Write the report"),
        "main.md".to_string(),
        WritePosition::End,
    )
    .expect("move");

    assert_eq!(body(vault.path()), "# Notes\n\n## Later\nNothing yet.\n");
    assert_eq!(
        other(&vault, "main.md"),
        "# Main\n\n### TODO Write the report\nThe figures.\n"
    );
}

/// The blank line that separated the entry from the next one stays behind as
/// the separator between the entries either side of it, and does not travel.
#[test]
fn the_file_it_left_keeps_one_separator_rather_than_two() {
    let vault = vault("## TODO First\n\n## TODO Second\n\n## TODO Third\n");
    write(&vault, "main.md", "# Main\n");

    move_entry(
        from(&vault, 3, "Second"),
        "main.md".to_string(),
        WritePosition::End,
    )
    .expect("move");

    assert_eq!(body(vault.path()), "## TODO First\n\n## TODO Third\n");
    assert_eq!(other(&vault, "main.md"), "# Main\n\n## TODO Second\n");
}

/// A file whose last entry was moved away does not end in the blank line that
/// stood above it.
#[test]
fn the_last_entry_of_a_file_leaves_no_blank_line_behind_it() {
    let vault = vault("# Notes\n\n## TODO Write the report\n");
    write(&vault, "main.md", "# Main\n");

    move_entry(
        from(&vault, 3, "Write the report"),
        "main.md".to_string(),
        WritePosition::End,
    )
    .expect("move");

    assert_eq!(body(vault.path()), "# Notes\n");
}

/// The file a collection calls its main one need not exist before something is
/// moved into it.
#[test]
fn the_file_it_arrives_in_is_created_when_it_is_not_there_yet() {
    let vault = vault("# Notes\n\n## TODO Write the report\n");

    move_entry(
        from(&vault, 3, "Write the report"),
        "main.md".to_string(),
        WritePosition::End,
    )
    .expect("move");

    assert_eq!(other(&vault, "main.md"), "## TODO Write the report\n");
}

/// The receiving file keeps the endings it was written with, whatever the file
/// the entry came from used.
#[test]
fn the_entry_takes_the_endings_of_the_file_it_arrives_in() {
    let vault = vault("# Notes\r\n\r\n## TODO Write the report\r\n");
    write(&vault, "main.md", "# Main\n");

    move_entry(
        from(&vault, 3, "Write the report"),
        "main.md".to_string(),
        WritePosition::End,
    )
    .expect("move");

    assert_eq!(body(vault.path()), "# Notes\r\n");
    assert_eq!(
        other(&vault, "main.md"),
        "# Main\n\n## TODO Write the report\n"
    );
}

/// Moving an entry into the file it is already in is refused, however the file
/// is spelled: read twice and written twice, it would lose the entry.
#[test]
fn moving_an_entry_into_the_file_it_is_already_in_is_refused() {
    let vault = vault("# Notes\n\n## TODO Write the report\n");

    for name in ["notes.md", "./notes.md"] {
        let refusal = move_entry(
            from(&vault, 3, "Write the report"),
            name.to_string(),
            WritePosition::End,
        );

        let detail = refusal.expect_err(name).to_string();
        assert!(detail.contains("already in"), "{name}: {detail}");
    }
    assert_eq!(body(vault.path()), "# Notes\n\n## TODO Write the report\n");
}

/// A file named outside the notes directory is refused, the same way the file
/// a collection receives new tasks in is.
#[test]
fn a_file_outside_the_notes_directory_is_refused() {
    let vault = vault("# Notes\n\n## TODO Write the report\n");

    let refusal = move_entry(
        from(&vault, 3, "Write the report"),
        "../elsewhere.md".to_string(),
        WritePosition::End,
    );

    assert!(refusal.is_err());
    assert_eq!(body(vault.path()), "# Notes\n\n## TODO Write the report\n");
}

/// An entry the file no longer holds where the caller saw it is not moved:
/// what would travel is whatever now stands on that line.
#[test]
fn an_entry_the_file_has_moved_on_from_is_refused() {
    let vault = vault("# Notes\n\n## TODO Write the report\n");
    write(&vault, "main.md", "# Main\n");

    let refusal = move_entry(
        from(&vault, 3, "Ring the dentist"),
        "main.md".to_string(),
        WritePosition::End,
    );

    assert!(refusal.is_err());
    assert_eq!(other(&vault, "main.md"), "# Main\n");
    assert_eq!(body(vault.path()), "# Notes\n\n## TODO Write the report\n");
}

/// Undoing a move puts both files back: the entry returns to the file it left
/// and goes out of the one it reached.
#[test]
fn undoing_a_move_puts_both_files_back() {
    let before = "# Notes\n\n## TODO Write the report\n\n## TODO Ring the dentist\n";
    let vault = vault(before);
    write(&vault, "main.md", "# Main\n");

    let outcome = move_entry(
        from(&vault, 3, "Write the report"),
        "main.md".to_string(),
        WritePosition::End,
    )
    .expect("move");
    let undone = revert_files(vault.path().display().to_string(), outcome.rollback);

    assert_eq!(undone.restored, vec!["notes.md", "main.md"]);
    assert!(undone.skipped.is_empty());
    assert!(undone.failed.is_empty());
    assert_eq!(body(vault.path()), before);
    assert_eq!(other(&vault, "main.md"), "# Main\n");
}
