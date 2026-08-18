//! Tests for the index that holds the notes between calls.
//!
//! Two properties matter and everything here is one of them. First: an agenda
//! built from the index is the agenda a fresh walk would have produced —
//! otherwise the saving is bought with a screen that disagrees with the files.
//! Second: a file re-read replaces exactly the tasks of that file, including
//! when it has lost them, gone, or stopped being readable.

use std::fs;

use markdown_org_ffi::{scan_agenda, AgendaQuery, ExtractError, NotesIndex, Options, Scope};

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

fn index(dir: &tempfile::TempDir) -> NotesIndex {
    NotesIndex::open(vec![root(dir)], options()).expect("open the index")
}

/// The directory as the index reports it: canonical, which is what a task
/// carries and what a re-read is addressed by.
fn root(dir: &tempfile::TempDir) -> String {
    fs::canonicalize(dir.path())
        .expect("canonicalize")
        .display()
        .to_string()
}

/// The headings of a day agenda, in the order they come back.
fn headings(result: &markdown_org_ffi::AgendaResult) -> Vec<String> {
    result
        .days
        .iter()
        .flat_map(|day| {
            day.scheduled_timed
                .iter()
                .chain(&day.scheduled_no_time)
                .chain(&day.upcoming)
        })
        .map(|task| task.heading.clone())
        .collect()
}

fn day_of(index: &NotesIndex) -> markdown_org_ffi::AgendaResult {
    index
        .agenda(AgendaQuery {
            scope: Scope::Day,
            current_date: "2026-03-02".to_string(),
            date: None,
            timezone: "Europe/Moscow".to_string(),
            include_done: false,
            week_start: None,
        })
        .expect("agenda")
}

const TODAY: &str = "# TODO Write the report\n`SCHEDULED: <2026-03-02 Mon 10:00>`\n";
const LATER: &str = "# TODO File the return\n`DEADLINE: <2026-03-05 Thu>`\n";
const MOVED: &str = "# TODO Write the report\n`SCHEDULED: <2026-03-09 Mon 10:00>`\n";
const LETTER: &str = "# TODO Write the letter\n`SCHEDULED: <2026-03-02 Mon 09:00>`\n";

#[test]
fn the_index_answers_what_a_fresh_walk_would_have() {
    let vault = write_vault(&[("a.md", TODAY), ("folder/b.md", LATER)]);

    let held = day_of(&index(&vault));
    let walked = scan_agenda(
        vault.path().display().to_string(),
        AgendaQuery {
            scope: Scope::Day,
            current_date: "2026-03-02".to_string(),
            date: None,
            timezone: "Europe/Moscow".to_string(),
            include_done: false,
            week_start: None,
        },
        options(),
    )
    .expect("scan");

    assert_eq!(headings(&held), headings(&walked));
    assert_eq!(held.days.len(), walked.days.len());
}

#[test]
fn a_re_read_file_brings_its_edit_and_leaves_the_rest() {
    let vault = write_vault(&[("a.md", TODAY), ("b.md", LATER)]);
    let index = index(&vault);
    assert_eq!(headings(&day_of(&index)).len(), 2);

    // The edit an application would have made through set_status or
    // shift_planning: one file rewritten, everything else untouched.
    fs::write(vault.path().join("a.md"), MOVED).expect("rewrite");
    index
        .refresh_file(root(&vault), "a.md".to_string())
        .expect("re-read");

    // The moved task is a week out and off this day; the untouched one stays.
    let after = headings(&day_of(&index));
    assert_eq!(after, vec!["File the return".to_string()]);
}

#[test]
fn only_the_named_file_is_re_read() {
    let vault = write_vault(&[("a.md", TODAY), ("b.md", LATER)]);
    let index = index(&vault);

    // Both files change on disk, but the index is told about one. The other
    // has to stay as it was: this is a cache with a stated contract, not a
    // watcher, and a caller reading otherwise would be surprised later.
    fs::write(vault.path().join("a.md"), MOVED).expect("rewrite a");
    fs::write(vault.path().join("b.md"), TODAY).expect("rewrite b");
    index
        .refresh_file(root(&vault), "a.md".to_string())
        .expect("re-read");

    assert_eq!(
        headings(&day_of(&index)),
        vec!["File the return".to_string()]
    );
}

#[test]
fn a_rescan_picks_up_everything_at_once() {
    let vault = write_vault(&[("a.md", TODAY)]);
    let index = index(&vault);

    // What a fetch does: files appear and change without anyone naming them.
    fs::write(vault.path().join("b.md"), LETTER).expect("write b");
    index.rescan().expect("rescan");

    let mut after = headings(&day_of(&index));
    after.sort();
    assert_eq!(
        after,
        vec![
            "Write the letter".to_string(),
            "Write the report".to_string(),
        ]
    );
}

#[test]
fn a_file_that_lost_its_tasks_loses_them_in_the_index() {
    let vault = write_vault(&[("a.md", TODAY), ("b.md", LETTER)]);
    let index = index(&vault);

    fs::write(vault.path().join("a.md"), "# Just a heading\n").expect("rewrite");
    index
        .refresh_file(root(&vault), "a.md".to_string())
        .expect("re-read");

    assert_eq!(
        headings(&day_of(&index)),
        vec!["Write the letter".to_string()]
    );
}

#[test]
fn a_deleted_file_is_dropped_rather_than_reported() {
    let vault = write_vault(&[("a.md", TODAY), ("b.md", LATER)]);
    let index = index(&vault);

    // A note deleted outside the application, or by an edit that removed the
    // last task in it. There is nothing to tell the user here: the walk would
    // simply not have found it either.
    fs::remove_file(vault.path().join("a.md")).expect("remove");

    index
        .refresh_file(root(&vault), "a.md".to_string())
        .expect("re-read");

    assert_eq!(
        headings(&day_of(&index)),
        vec!["File the return".to_string()]
    );
}

#[test]
fn a_file_that_is_not_utf8_is_dropped_rather_than_reported() {
    let vault = write_vault(&[("a.md", TODAY), ("b.md", LATER)]);
    let index = index(&vault);

    // A note saved in a single-byte encoding. The walk counts it and moves on,
    // and so does this: its tasks go, the rest of the index stands.
    fs::write(vault.path().join("a.md"), [0xff, 0xfe, 0x00]).expect("rewrite");
    index
        .refresh_file(root(&vault), "a.md".to_string())
        .expect("re-read");

    assert_eq!(
        headings(&day_of(&index)),
        vec!["File the return".to_string()]
    );
}

#[test]
fn a_path_that_climbs_out_of_the_directory_is_refused() {
    let vault = write_vault(&[("a.md", TODAY)]);
    let index = index(&vault);

    let refused = index.refresh_file(root(&vault), "../elsewhere.md".to_string());

    assert!(matches!(
        refused,
        Err(ExtractError::InvalidDirectory { .. })
    ));
}

#[test]
fn the_task_cap_survives_a_re_read() {
    let vault = write_vault(&[("a.md", TODAY), ("b.md", LATER)]);
    let index = NotesIndex::open(
        vec![root(&vault)],
        Options {
            glob: None,
            locale: None,
            max_tasks: Some(2),
        },
    )
    .expect("open the index");

    // Three tasks in a file whose one task was counted: without the cap the
    // index would hold more than a walk of the same directory ever would.
    let grown = format!("{TODAY}\n# TODO Second\n`SCHEDULED: <2026-03-02 Mon 11:00>`\n\n# TODO Third\n`SCHEDULED: <2026-03-02 Mon 12:00>`\n");
    fs::write(vault.path().join("a.md"), grown).expect("rewrite");
    index
        .refresh_file(root(&vault), "a.md".to_string())
        .expect("re-read");

    assert_eq!(headings(&day_of(&index)).len(), 2);
}

// Several collections at once. Notes live in more than one place — a work
// repository and a private one — and the agenda over them is one agenda. What
// has to hold is that a task still names the collection it came from, because
// an edit is addressed by that pair and the same relative path occurs in both.

#[test]
fn the_index_merges_the_collections_it_was_opened_over() {
    let work = write_vault(&[("a.md", TODAY)]);
    let home = write_vault(&[("a.md", LETTER)]);

    let index = NotesIndex::open(vec![root(&work), root(&home)], options()).expect("open");

    let mut after = headings(&day_of(&index));
    after.sort();
    assert_eq!(
        after,
        vec![
            "Write the letter".to_string(),
            "Write the report".to_string(),
        ]
    );
}

#[test]
fn every_task_names_the_collection_it_came_from() {
    let work = write_vault(&[("a.md", TODAY)]);
    let home = write_vault(&[("a.md", LETTER)]);

    let index = NotesIndex::open(vec![root(&work), root(&home)], options()).expect("open");

    let agenda = day_of(&index);
    let mut named: Vec<(String, String)> = agenda
        .days
        .iter()
        .flat_map(|day| day.scheduled_timed.iter())
        .map(|task| (task.heading.clone(), task.root.clone().unwrap_or_default()))
        .collect();
    named.sort();
    assert_eq!(
        named,
        vec![
            ("Write the letter".to_string(), root(&home)),
            ("Write the report".to_string(), root(&work)),
        ],
        "the same relative path in two collections is two different files"
    );
}

#[test]
fn a_re_read_touches_only_the_collection_it_names() {
    let work = write_vault(&[("a.md", TODAY)]);
    let home = write_vault(&[("a.md", LETTER)]);
    let index = NotesIndex::open(vec![root(&work), root(&home)], options()).expect("open");

    // Both collections hold `a.md`. Re-reading the one in `work` must not
    // drop the tasks of the one in `home`, which is what dropping by path
    // alone would do.
    fs::write(work.path().join("a.md"), MOVED).expect("rewrite");
    index
        .refresh_file(root(&work), "a.md".to_string())
        .expect("re-read");

    assert_eq!(
        headings(&day_of(&index)),
        vec!["Write the letter".to_string()]
    );
}

#[test]
fn a_root_the_index_was_not_opened_over_is_refused() {
    let vault = write_vault(&[("a.md", TODAY)]);
    let elsewhere = write_vault(&[("a.md", LETTER)]);
    let index = index(&vault);

    let refused = index.refresh_file(root(&elsewhere), "a.md".to_string());

    assert!(matches!(
        refused,
        Err(ExtractError::InvalidDirectory { .. })
    ));
}

#[test]
fn an_index_over_no_directory_at_all_is_refused() {
    // An empty list would answer every agenda with an empty one, which reads
    // as a collection with nothing in it rather than as a missing setting.
    let refused = NotesIndex::open(Vec::new(), options());

    assert!(matches!(
        refused,
        Err(ExtractError::InvalidDirectory { .. })
    ));
}
