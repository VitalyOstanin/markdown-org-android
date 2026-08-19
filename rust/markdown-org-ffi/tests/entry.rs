//! Tests for editing the text of an entry.
//!
//! As with the other editing tests, every case asserts on the whole file: what
//! matters about an edit bounded to one entry is precisely that the lines
//! around it come back unchanged.

use markdown_org_ffi::{read_entry, set_entry, EditError};

mod common;

use common::{body, target, vault};

const ENTRY: &str = "\
# TODO Write the report
`SCHEDULED: <2026-07-28 Tue>`

The figures are in the drive.
Ask Anna for the rest.

# TODO Water the plants
";

#[test]
fn an_entry_reads_as_its_title_and_the_lines_under_it() {
    let vault = vault(ENTRY);

    let entry = read_entry(target(vault.path(), 1, "Write the report")).expect("read");

    assert_eq!(entry.title, "Write the report");
    assert_eq!(
        entry.body,
        "The figures are in the drive.\nAsk Anna for the rest."
    );
}

#[test]
fn the_title_is_read_with_the_markup_it_is_written_with() {
    let vault = vault("# TODO Read **the** `manual`\n");

    let entry = read_entry(target(vault.path(), 1, "Read the manual")).expect("read");

    // What comes back is what an editor puts in front of the user, so it is
    // the line as written rather than the display text the agenda shows.
    assert_eq!(entry.title, "Read **the** `manual`");
}

#[test]
fn a_new_title_leaves_the_keyword_and_the_cookie_alone() {
    let vault = vault("## TODO [#A] Write the report\nA note.\n");

    let outcome = set_entry(
        target(vault.path(), 1, "Write the report"),
        "File the report".to_string(),
        "A note.".to_string(),
    )
    .expect("edit");

    assert_eq!(outcome.line, "## TODO [#A] File the report");
    assert!(outcome.changed);
    assert_eq!(
        body(vault.path()),
        "## TODO [#A] File the report\nA note.\n"
    );
}

#[test]
fn a_title_that_reads_as_a_keyword_is_refused() {
    let vault = vault("# Write the report\n");

    let refusal = set_entry(
        target(vault.path(), 1, "Write the report"),
        "TODO write the report".to_string(),
        String::new(),
    )
    .expect_err("refused");

    // Typing a keyword into the title would set a status, which is what the
    // actions of the sheet are for — and the file has to be left alone.
    assert!(matches!(refusal, EditError::Unsupported { .. }));
    assert_eq!(body(vault.path()), "# Write the report\n");
}

#[test]
fn a_title_with_nothing_in_it_is_refused() {
    let vault = vault("# Write the report\n");

    let refusal = set_entry(
        target(vault.path(), 1, "Write the report"),
        "   ".to_string(),
        String::new(),
    )
    .expect_err("refused");

    assert!(matches!(refusal, EditError::Unsupported { .. }));
}

#[test]
fn a_body_is_written_where_the_entry_had_none() {
    let vault = vault("# TODO Write the report\n`SCHEDULED: <2026-07-28 Tue>`\n\n# TODO Water\n");

    set_entry(
        target(vault.path(), 1, "Write the report"),
        "Write the report".to_string(),
        "Due to the board.".to_string(),
    )
    .expect("edit");

    // The blank line before the next heading is the file's, not the entry's,
    // and it stays where it is.
    assert_eq!(
        body(vault.path()),
        "\
# TODO Write the report
`SCHEDULED: <2026-07-28 Tue>`
Due to the board.

# TODO Water
"
    );
}

#[test]
fn a_rewritten_body_leaves_the_planning_line_and_the_entries_around_it() {
    let vault = vault(ENTRY);

    set_entry(
        target(vault.path(), 1, "Write the report"),
        "Write the report".to_string(),
        "Only the figures are missing.".to_string(),
    )
    .expect("edit");

    assert_eq!(
        body(vault.path()),
        "\
# TODO Write the report
`SCHEDULED: <2026-07-28 Tue>`

Only the figures are missing.

# TODO Water the plants
"
    );
}

#[test]
fn an_emptied_body_leaves_the_entry_standing() {
    let vault = vault(ENTRY);

    set_entry(
        target(vault.path(), 1, "Write the report"),
        "Write the report".to_string(),
        String::new(),
    )
    .expect("edit");

    assert_eq!(
        body(vault.path()),
        "\
# TODO Write the report
`SCHEDULED: <2026-07-28 Tue>`

# TODO Water the plants
"
    );
}

#[test]
fn a_body_line_that_would_start_another_entry_is_refused() {
    let vault = vault(ENTRY);

    let refusal = set_entry(
        target(vault.path(), 1, "Write the report"),
        "Write the report".to_string(),
        "The figures.\n# TODO Something else".to_string(),
    )
    .expect_err("refused");

    assert!(matches!(refusal, EditError::Unsupported { .. }));
    assert_eq!(body(vault.path()), ENTRY);
}

#[test]
fn a_body_line_that_would_be_a_planning_line_is_refused() {
    let vault = vault(ENTRY);

    let refusal = set_entry(
        target(vault.path(), 1, "Write the report"),
        "Write the report".to_string(),
        "`DEADLINE: <2026-08-01 Sat>`".to_string(),
    )
    .expect_err("refused");

    // The dates are moved by the operations that know how to rewrite a
    // timestamp; one typed into the body would be read as the entry's own.
    assert!(matches!(refusal, EditError::Unsupported { .. }));
    assert_eq!(body(vault.path()), ENTRY);
}

#[test]
fn writing_back_what_was_read_changes_nothing() {
    let vault = vault(ENTRY);
    let entry = read_entry(target(vault.path(), 1, "Write the report")).expect("read");

    let outcome = set_entry(
        target(vault.path(), 1, "Write the report"),
        entry.title,
        entry.body,
    )
    .expect("edit");

    assert!(!outcome.changed);
    assert_eq!(body(vault.path()), ENTRY);
}

#[test]
fn a_file_written_with_crlf_keeps_its_endings() {
    let vault = vault("# TODO Write\r\n`SCHEDULED: <2026-07-28 Tue>`\r\n\r\nOne line.\r\n");

    set_entry(
        target(vault.path(), 1, "Write"),
        "Write".to_string(),
        "One line.\nAnd another.".to_string(),
    )
    .expect("edit");

    assert_eq!(
        body(vault.path()),
        "# TODO Write\r\n`SCHEDULED: <2026-07-28 Tue>`\r\n\r\nOne line.\r\nAnd another.\r\n"
    );
}

#[test]
fn a_file_that_ends_without_a_newline_does_not_grow_one() {
    let vault = vault("# TODO Write\nOne line.");

    set_entry(
        target(vault.path(), 1, "Write"),
        "Write".to_string(),
        "One line.\nAnd another.".to_string(),
    )
    .expect("edit");

    assert_eq!(body(vault.path()), "# TODO Write\nOne line.\nAnd another.");
}

#[test]
fn a_planning_line_below_a_paragraph_keeps_the_paragraph_out_of_the_body() {
    // A file written this way — by hand, or by an editor that puts the date
    // under a note — must not have the paragraph above swallowed by an edit
    // aimed at the text below.
    let vault = vault("# TODO Write\nA note above.\n`DEADLINE: <2026-08-01 Sat>`\nBelow.\n");

    let entry = read_entry(target(vault.path(), 1, "Write")).expect("read");
    assert_eq!(entry.body, "Below.");

    set_entry(
        target(vault.path(), 1, "Write"),
        "Write".to_string(),
        "Below, rewritten.".to_string(),
    )
    .expect("edit");

    assert_eq!(
        body(vault.path()),
        "# TODO Write\nA note above.\n`DEADLINE: <2026-08-01 Sat>`\nBelow, rewritten.\n"
    );
}

#[test]
fn an_entry_that_has_moved_on_is_not_written_to() {
    let vault = vault(ENTRY);

    let refusal = set_entry(
        target(vault.path(), 1, "Water the plants"),
        "Anything".to_string(),
        String::new(),
    )
    .expect_err("refused");

    assert!(matches!(refusal, EditError::Stale { .. }));
    assert_eq!(body(vault.path()), ENTRY);
}
