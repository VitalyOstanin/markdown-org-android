//! Tests for writing a task that was not in the notes before.
//!
//! What they hold to is the file: where the entry lands, what level it is
//! written at, and that everything the file already held comes back byte for
//! byte. The receiving file is named the way a collection names it — a
//! relative path typed into the settings — so the cases that refuse one are
//! about paths a user can type, not about paths a scan produced.

use std::fs;

use markdown_org_ffi::{
    create_task, revert_files, NewPlanning, NewTask, PlanningKeyword, TaskType,
};

mod common;

use common::{body, vault};

/// A task with nothing set on it but its title, aimed at `notes.md`.
fn task(dir: &tempfile::TempDir, title: &str) -> NewTask {
    NewTask {
        dir: dir.path().display().to_string(),
        file: "notes.md".to_string(),
        title: title.to_string(),
        body: String::new(),
        status: Some(TaskType::Todo),
        priority: None,
        planning: None,
    }
}

#[test]
fn the_task_goes_at_the_end_of_the_file() {
    let vault = vault("# Notes\n\n## TODO Write the report\n`SCHEDULED: <2026-08-19 Wed>`\n");

    let outcome = create_task(task(&vault, "Ring the dentist")).expect("create");

    assert!(outcome.changed);
    assert_eq!(outcome.line, "## TODO Ring the dentist");
    assert_eq!(
        body(vault.path()),
        "# Notes\n\n## TODO Write the report\n`SCHEDULED: <2026-08-19 Wed>`\n\n## TODO Ring the dentist\n"
    );
}

#[test]
fn the_level_is_the_one_the_file_writes_its_tasks_at() {
    let vault = vault("# Notes\n\n### TODO Write the report\n");

    create_task(task(&vault, "Ring the dentist")).expect("create");

    assert!(body(vault.path()).ends_with("### TODO Ring the dentist\n"));
}

#[test]
fn a_file_of_nothing_but_a_title_takes_the_task_under_it() {
    let vault = vault("# Notes\n");

    create_task(task(&vault, "Ring the dentist")).expect("create");

    assert_eq!(body(vault.path()), "# Notes\n\n## TODO Ring the dentist\n");
}

#[test]
fn a_file_with_no_headings_at_all_starts_at_the_top() {
    let vault = vault("A paragraph nobody made a heading of.\n");

    create_task(task(&vault, "Ring the dentist")).expect("create");

    assert!(body(vault.path()).ends_with("\n# TODO Ring the dentist\n"));
}

#[test]
fn the_file_is_created_when_it_is_not_there_yet() {
    let vault = vault("# Notes\n");
    let mut asked = task(&vault, "Ring the dentist");
    asked.file = "inbox.md".to_string();

    create_task(asked).expect("create");

    let written = fs::read_to_string(vault.path().join("inbox.md")).expect("read");
    assert_eq!(written, "# TODO Ring the dentist\n");
}

#[test]
fn a_file_under_a_directory_that_is_not_there_is_refused() {
    let vault = vault("# Notes\n");
    let mut asked = task(&vault, "Ring the dentist");
    asked.file = "work/inbox.md".to_string();

    let refused = create_task(asked).expect_err("refused");

    assert!(
        format!("{refused}").contains("not a directory"),
        "{refused}"
    );
    assert!(!vault.path().join("work").exists());
}

#[test]
fn a_file_outside_the_notes_directory_is_refused() {
    let vault = vault("# Notes\n");
    let mut asked = task(&vault, "Ring the dentist");
    asked.file = "../elsewhere.md".to_string();

    let refused = create_task(asked).expect_err("refused");

    assert!(
        format!("{refused}").contains("outside the notes directory"),
        "{refused}"
    );
}

#[test]
fn the_keyword_and_the_priority_are_written_as_the_fields_asked() {
    let vault = vault("## TODO Write the report\n");
    let mut asked = task(&vault, "Ring the dentist");
    asked.status = Some(TaskType::Done);
    asked.priority = Some("B".to_string());

    let outcome = create_task(asked).expect("create");

    assert_eq!(outcome.line, "## DONE [#B] Ring the dentist");
}

#[test]
fn a_task_can_be_written_without_a_keyword() {
    let vault = vault("## TODO Write the report\n");
    let mut asked = task(&vault, "Ring the dentist");
    asked.status = None;

    let outcome = create_task(asked).expect("create");

    assert_eq!(outcome.line, "## Ring the dentist");
}

#[test]
fn the_date_is_spelled_the_way_the_file_spells_its_own() {
    let vault = vault("# TODO Write\n  `SCHEDULED: <2026-08-19 Ср 10:00>`\n");
    let mut asked = task(&vault, "Ring the dentist");
    asked.planning = Some(NewPlanning {
        keyword: PlanningKeyword::Deadline,
        date: "2026-08-21".to_string(),
    });

    create_task(asked).expect("create");

    assert!(
        body(vault.path()).ends_with("  `DEADLINE: <2026-08-21 Пт>`\n"),
        "{}",
        body(vault.path())
    );
}

#[test]
fn a_file_with_no_date_in_it_gets_the_canonical_spelling() {
    let vault = vault("# Notes\n");
    let mut asked = task(&vault, "Ring the dentist");
    asked.planning = Some(NewPlanning {
        keyword: PlanningKeyword::Scheduled,
        date: "2026-08-21".to_string(),
    });

    create_task(asked).expect("create");

    assert_eq!(
        body(vault.path()),
        "# Notes\n\n## TODO Ring the dentist\n`SCHEDULED: <2026-08-21 Fri>`\n"
    );
}

#[test]
fn the_body_goes_under_the_date_with_a_line_between_them() {
    let vault = vault("# Notes\n");
    let mut asked = task(&vault, "Ring the dentist");
    asked.body = "The number is on the fridge.\nAsk for the evening slot.".to_string();
    asked.planning = Some(NewPlanning {
        keyword: PlanningKeyword::Scheduled,
        date: "2026-08-21".to_string(),
    });

    create_task(asked).expect("create");

    assert_eq!(
        body(vault.path()),
        concat!(
            "# Notes\n\n",
            "## TODO Ring the dentist\n",
            "`SCHEDULED: <2026-08-21 Fri>`\n\n",
            "The number is on the fridge.\n",
            "Ask for the evening slot.\n",
        )
    );
}

#[test]
fn a_title_that_reads_as_a_keyword_is_refused_and_nothing_is_written() {
    let vault = vault("# Notes\n");
    let before = body(vault.path());

    let refused = create_task(task(&vault, "TODO ring the dentist")).expect_err("refused");

    assert!(
        format!("{refused}").contains("reads as a keyword"),
        "{refused}"
    );
    assert_eq!(body(vault.path()), before);
}

#[test]
fn a_title_with_nothing_in_it_is_refused() {
    let vault = vault("# Notes\n");

    let refused = create_task(task(&vault, "   ")).expect_err("refused");

    assert!(format!("{refused}").contains("no title"), "{refused}");
}

#[test]
fn a_body_line_that_would_start_another_entry_is_refused() {
    let vault = vault("# Notes\n");
    let before = body(vault.path());
    let mut asked = task(&vault, "Ring the dentist");
    asked.body = "## TODO And another".to_string();

    let refused = create_task(asked).expect_err("refused");

    assert!(
        format!("{refused}").contains("would start another entry"),
        "{refused}"
    );
    assert_eq!(body(vault.path()), before);
}

#[test]
fn a_priority_the_format_does_not_have_is_refused() {
    let vault = vault("# Notes\n");
    let before = body(vault.path());
    let mut asked = task(&vault, "Ring the dentist");
    asked.priority = Some("a".to_string());

    create_task(asked).expect_err("refused");

    assert_eq!(body(vault.path()), before);
}

#[test]
fn a_date_that_is_not_a_date_is_refused_before_the_file_is_opened() {
    let vault = vault("# Notes\n");
    let before = body(vault.path());
    let mut asked = task(&vault, "Ring the dentist");
    asked.planning = Some(NewPlanning {
        keyword: PlanningKeyword::Scheduled,
        date: "the 21st".to_string(),
    });

    create_task(asked).expect_err("refused");

    assert_eq!(body(vault.path()), before);
}

#[test]
fn a_file_written_with_crlf_keeps_its_endings() {
    let vault = vault("# Notes\r\n\r\n## TODO Write\r\n");

    create_task(task(&vault, "Ring the dentist")).expect("create");

    assert_eq!(
        body(vault.path()),
        "# Notes\r\n\r\n## TODO Write\r\n\r\n## TODO Ring the dentist\r\n"
    );
}

#[test]
fn a_file_that_ended_without_a_newline_does_not_grow_one() {
    let vault = vault("# Notes\n\n## TODO Write");

    create_task(task(&vault, "Ring the dentist")).expect("create");

    assert_eq!(
        body(vault.path()),
        "# Notes\n\n## TODO Write\n\n## TODO Ring the dentist"
    );
}

#[test]
fn undoing_a_creation_takes_the_entry_out_again() {
    let vault = vault("# Notes\n\n## TODO Write the report\n");
    let before = body(vault.path());

    let outcome = create_task(task(&vault, "Ring the dentist")).expect("create");
    let undone = revert_files(
        vault.path().display().to_string(),
        vec![outcome.rollback.expect("rollback")],
    );

    assert_eq!(undone.restored, vec!["notes.md"]);
    assert_eq!(body(vault.path()), before);
}

#[test]
fn undoing_the_first_task_of_a_file_leaves_it_empty_rather_than_gone() {
    let vault = vault("# Notes\n");
    let mut asked = task(&vault, "Ring the dentist");
    asked.file = "inbox.md".to_string();

    let outcome = create_task(asked).expect("create");
    revert_files(
        vault.path().display().to_string(),
        vec![outcome.rollback.expect("rollback")],
    );

    let written = fs::read_to_string(vault.path().join("inbox.md")).expect("read");
    assert_eq!(written, "");
}
