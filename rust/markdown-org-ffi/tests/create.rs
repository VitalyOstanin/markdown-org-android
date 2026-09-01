//! Tests for writing a task that was not in the notes before.
//!
//! What they hold to is the file: where the entry lands, what level it is
//! written at, and that everything the file already held comes back byte for
//! byte. The receiving file is named the way a collection names it — a
//! relative path typed into the settings — so the cases that refuse one are
//! about paths a user can type, not about paths a scan produced.

use std::fs;

use markdown_org_ffi::{
    create_task, revert_files, NewPlanning, NewTask, PlanningKeyword, TaskType, WritePosition,
};

mod common;

use common::{body, vault};

/// A task with nothing set on it but its title, aimed at the end of
/// `notes.md`.
///
/// No creation mark: the cases that are about where an entry lands read
/// easier without a line none of them is asking about, and the ones that are
/// about the mark name the moment themselves.
fn task(dir: &tempfile::TempDir, title: &str) -> NewTask {
    NewTask {
        dir: dir.path().display().to_string(),
        file: "notes.md".to_string(),
        at: WritePosition::End,
        title: title.to_string(),
        body: String::new(),
        status: Some(TaskType::Todo),
        priority: None,
        planning: None,
        created: None,
    }
}

/// A planning date on its own: no hour, and happening once.
fn planning(keyword: PlanningKeyword, date: &str) -> NewPlanning {
    NewPlanning {
        keyword,
        date: date.to_string(),
        time: None,
        repeater: None,
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

/// A task written at the start goes above the entries, under the title.
#[test]
fn the_task_goes_before_the_first_heading_when_the_file_says_start() {
    let vault = vault("# Notes\n\n## TODO Write the report\n");

    let outcome = create_task(NewTask {
        at: WritePosition::Start,
        ..task(&vault, "Ring the dentist")
    })
    .expect("create");

    assert_eq!(outcome.line, "## TODO Ring the dentist");
    assert_eq!(
        body(vault.path()),
        "## TODO Ring the dentist\n\n# Notes\n\n## TODO Write the report\n"
    );
}

/// Everything above the first heading is the header, and the entry goes after
/// it: a paragraph introducing the note keeps introducing it.
#[test]
fn the_header_is_whatever_stands_above_the_first_heading() {
    let vault = vault("These are my notes.\n\n# TODO Write the report\n");

    create_task(NewTask {
        at: WritePosition::Start,
        ..task(&vault, "Ring the dentist")
    })
    .expect("create");

    assert_eq!(
        body(vault.path()),
        "These are my notes.\n\n# TODO Ring the dentist\n\n# TODO Write the report\n"
    );
}

/// A YAML front matter is stepped over whole, comment lines included: a `#` at
/// the start of one reads as a heading, and an entry written above it would
/// land inside the front matter and break it.
#[test]
fn a_yaml_front_matter_is_stepped_over_rather_than_written_into() {
    let vault = vault(
        "---\n# the day this note was started\ntitle: Notes\n---\n\n# TODO Write the report\n",
    );

    create_task(NewTask {
        at: WritePosition::Start,
        ..task(&vault, "Ring the dentist")
    })
    .expect("create");

    assert_eq!(
        body(vault.path()),
        "---\n# the day this note was started\ntitle: Notes\n---\n\n# TODO Ring the dentist\n\n# TODO Write the report\n"
    );
}

/// A file with no heading at all is header to the end, so the start and the
/// end of it are the same place.
#[test]
fn a_file_with_no_heading_takes_the_task_after_everything_it_holds() {
    let vault = vault("Shopping.\n");

    create_task(NewTask {
        at: WritePosition::Start,
        ..task(&vault, "Ring the dentist")
    })
    .expect("create");

    assert_eq!(body(vault.path()), "Shopping.\n\n# TODO Ring the dentist\n");
}

/// The date and the text of a task written at the start stay under its
/// heading, rather than being written at the end of the file the heading is no
/// longer at.
#[test]
fn what_goes_under_the_heading_follows_it_to_the_start_of_the_file() {
    let vault = vault("# Notes\n\n## TODO Write the report\n`SCHEDULED: <2026-08-19 Wed>`\n");

    create_task(NewTask {
        at: WritePosition::Start,
        body: "Their number is in the drawer.".to_string(),
        planning: Some(planning(PlanningKeyword::Scheduled, "2026-08-20")),
        ..task(&vault, "Ring the dentist")
    })
    .expect("create");

    assert_eq!(
        body(vault.path()),
        "## TODO Ring the dentist\n`SCHEDULED: <2026-08-20 Thu>`\n\nTheir number is in the drawer.\n\n# Notes\n\n## TODO Write the report\n`SCHEDULED: <2026-08-19 Wed>`\n"
    );
}

/// A file made by the first task written into it has nothing to be separated
/// from, wherever the entry is meant to go.
#[test]
fn a_file_that_did_not_exist_gets_the_entry_and_no_blank_lines() {
    let vault = vault("# Notes\n");

    create_task(NewTask {
        file: "made.md".to_string(),
        at: WritePosition::Start,
        ..task(&vault, "Ring the dentist")
    })
    .expect("create");

    assert_eq!(
        fs::read_to_string(vault.path().join("made.md")).expect("read"),
        "# TODO Ring the dentist\n"
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
    asked.planning = Some(planning(PlanningKeyword::Deadline, "2026-08-21"));

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
    asked.planning = Some(planning(PlanningKeyword::Scheduled, "2026-08-21"));

    create_task(asked).expect("create");

    assert_eq!(
        body(vault.path()),
        "# Notes\n\n## TODO Ring the dentist\n`SCHEDULED: <2026-08-21 Fri>`\n"
    );
}

#[test]
fn an_hour_and_a_repeater_are_written_into_the_timestamp() {
    let vault = vault("# Notes\n");
    let mut asked = task(&vault, "Water the plants");
    asked.planning = Some(NewPlanning {
        keyword: PlanningKeyword::Scheduled,
        date: "2026-08-21".to_string(),
        time: Some("09:00".to_string()),
        repeater: Some("++1w".to_string()),
    });

    create_task(asked).expect("create");

    assert!(
        body(vault.path()).ends_with("`SCHEDULED: <2026-08-21 Fri 09:00 ++1w>`\n"),
        "{}",
        body(vault.path())
    );
}

#[test]
fn the_repeater_is_written_the_canonical_way_whatever_it_was_spelled_as() {
    let vault = vault("# Notes\n");
    let mut asked = task(&vault, "Water the plants");
    asked.planning = Some(NewPlanning {
        keyword: PlanningKeyword::Scheduled,
        date: "2026-08-21".to_string(),
        time: None,
        repeater: Some("  ++007w  ".to_string()),
    });

    create_task(asked).expect("create");

    assert!(
        body(vault.path()).ends_with("`SCHEDULED: <2026-08-21 Fri ++7w>`\n"),
        "{}",
        body(vault.path())
    );
}

#[test]
fn an_hour_is_written_even_where_the_file_spells_its_dates_without_one() {
    let vault = vault("# TODO Write\n  `SCHEDULED: <2026-08-19 Ср>`\n");
    let mut asked = task(&vault, "Ring the dentist");
    asked.planning = Some(NewPlanning {
        keyword: PlanningKeyword::Deadline,
        date: "2026-08-21".to_string(),
        time: Some("18:30".to_string()),
        repeater: None,
    });

    create_task(asked).expect("create");

    assert!(
        body(vault.path()).ends_with("  `DEADLINE: <2026-08-21 Пт 18:30>`\n"),
        "{}",
        body(vault.path())
    );
}

#[test]
fn an_hour_that_is_not_one_is_refused_before_the_file_is_opened() {
    let vault = vault("# Notes\n");
    let before = body(vault.path());
    let mut asked = task(&vault, "Water the plants");
    asked.planning = Some(NewPlanning {
        keyword: PlanningKeyword::Scheduled,
        date: "2026-08-21".to_string(),
        time: Some("half nine".to_string()),
        repeater: None,
    });

    let refused = create_task(asked).expect_err("refused");

    assert!(format!("{refused}").contains("HH:MM"), "{refused}");
    assert_eq!(body(vault.path()), before);
}

#[test]
fn a_repeater_that_spells_nothing_is_refused_before_the_file_is_opened() {
    let vault = vault("# Notes\n");
    let before = body(vault.path());
    let mut asked = task(&vault, "Water the plants");
    asked.planning = Some(NewPlanning {
        keyword: PlanningKeyword::Scheduled,
        date: "2026-08-21".to_string(),
        time: None,
        repeater: Some("weekly".to_string()),
    });

    let refused = create_task(asked).expect_err("refused");

    assert!(format!("{refused}").contains("not a repeater"), "{refused}");
    assert_eq!(body(vault.path()), before);
}

#[test]
fn the_body_goes_under_the_date_with_a_line_between_them() {
    let vault = vault("# Notes\n");
    let mut asked = task(&vault, "Ring the dentist");
    asked.body = "The number is on the fridge.\nAsk for the evening slot.".to_string();
    asked.planning = Some(planning(PlanningKeyword::Scheduled, "2026-08-21"));

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
    asked.planning = Some(planning(PlanningKeyword::Scheduled, "the 21st"));

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

/// The day the entry was written on is marked under the heading, above the
/// date it is planned for.
#[test]
fn the_day_it_was_written_on_stands_above_the_day_it_is_planned_for() {
    let vault = vault("# Notes\n\n## TODO Write the report\n`SCHEDULED: <2026-08-19 Wed>`\n");

    create_task(NewTask {
        planning: Some(planning(PlanningKeyword::Scheduled, "2026-09-02")),
        created: Some("2026-09-01T14:01".to_string()),
        ..task(&vault, "Ring the dentist")
    })
    .expect("create");

    assert_eq!(
        body(vault.path()),
        "# Notes\n\n## TODO Write the report\n`SCHEDULED: <2026-08-19 Wed>`\n\n\
         ## TODO Ring the dentist\n`CREATED: [2026-09-01 Tue 14:01]`\n\
         `SCHEDULED: <2026-09-02 Wed>`\n"
    );
}

/// A file that writes its dates bare gets a bare line, and one that writes
/// them without a weekday gets no weekday: the mark follows the file the same
/// way a planning line does.
#[test]
fn the_created_line_is_spelled_the_way_the_file_spells_its_dates() {
    let vault = vault("# Notes\n\n## TODO Write the report\nSCHEDULED: <2026-08-19>\n");

    create_task(NewTask {
        created: Some("2026-09-01T14:01".to_string()),
        ..task(&vault, "Ring the dentist")
    })
    .expect("create");

    assert_eq!(
        body(vault.path()),
        "# Notes\n\n## TODO Write the report\nSCHEDULED: <2026-08-19>\n\n\
         ## TODO Ring the dentist\nCREATED: [2026-09-01 14:01]\n"
    );
}

/// Russian weekdays in the file mean a Russian weekday in the mark, for the
/// reason a planning line takes one: a note is written in one language.
#[test]
fn a_file_written_in_russian_gets_a_russian_weekday() {
    let vault = vault("# Заметки\n\n## TODO Написать отчёт\n`SCHEDULED: <2026-08-19 Ср>`\n");

    create_task(NewTask {
        created: Some("2026-09-01T14:01".to_string()),
        ..task(&vault, "Позвонить врачу")
    })
    .expect("create");

    assert_eq!(
        body(vault.path()),
        "# Заметки\n\n## TODO Написать отчёт\n`SCHEDULED: <2026-08-19 Ср>`\n\n\
         ## TODO Позвонить врачу\n`CREATED: [2026-09-01 Вт 14:01]`\n"
    );
}

/// A moment that is not one leaves the file exactly as it was, the same as
/// every other value read before the file is opened. A date without an hour is
/// among them: the mark carries the minute it was written at.
#[test]
fn a_creation_moment_that_is_not_one_writes_nothing() {
    let vault = vault("# Notes\n\n## TODO Write the report\n");

    let outcome = create_task(NewTask {
        created: Some("the first of September".to_string()),
        ..task(&vault, "Ring the dentist")
    });
    let dateless = create_task(NewTask {
        created: Some("2026-09-01".to_string()),
        ..task(&vault, "Ring the dentist")
    });

    assert!(outcome.is_err());
    assert!(dateless.is_err());
    assert_eq!(body(vault.path()), "# Notes\n\n## TODO Write the report\n");
}
