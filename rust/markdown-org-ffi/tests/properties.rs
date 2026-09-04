//! What every operation on a note must leave true, over files nobody wrote by
//! hand.
//!
//! The examples in the other test files each cover one shape of file at a
//! time; these cover the combinations of them, and they cover every operation
//! at once rather than one per file. Undo is the case that made it worth
//! doing: it is a universal invariant -- an edit taken back leaves the bytes
//! that were there -- checked by naming it in five test files and forgetting
//! it in the sixth.

use markdown_org_ffi::{
    apply_phrase, complete_task, revert_files, set_planning, set_priority, EditError, EditTarget,
    FileRollback, PhraseDraft, PlanningKeyword,
};
use proptest::prelude::*;

mod common;

use common::body;

/// The heading of the one task the generated file holds.
const HEADING: &str = "Write the report";

/// A line as a note holds one, none of which is a task: the task is put in at
/// a known place, and a second one would make the line numbers a guess.
fn line_body() -> impl Strategy<Value = String> {
    prop_oneof![
        Just("## Раздел".to_string()),
        Just(String::new()),
        Just("Отчёт за неделю".to_string()),
        Just("сдать 📄 отчёт".to_string()),
        Just("zero\u{200B}width".to_string()),
    ]
}

fn line_ending() -> impl Strategy<Value = String> {
    prop_oneof![Just("\n".to_string()), Just("\r\n".to_string())]
}

/// A file with one task in it: what is above the task, what is below, whether
/// the file carries a byte-order mark, and whether it ends with a newline.
#[derive(Debug, Clone)]
struct Note {
    content: String,
    /// Which line the task is on, counted the way an edit names it.
    line: u32,
}

fn note() -> impl Strategy<Value = Note> {
    (
        any::<bool>(),
        proptest::collection::vec((line_body(), line_ending()), 0..4),
        proptest::collection::vec((line_body(), line_ending()), 0..4),
        line_ending(),
        any::<bool>(),
    )
        .prop_map(|(mark, above, below, task_ending, open)| {
            let mut content = String::new();
            if mark {
                content.push('\u{FEFF}');
            }
            for (body, ending) in &above {
                content.push_str(body);
                content.push_str(ending);
            }
            content.push_str(&format!("# TODO {HEADING}"));
            content.push_str(&task_ending);

            let last = below.len().saturating_sub(1);
            for (index, (body, ending)) in below.iter().enumerate() {
                content.push_str(body);
                if index < last || !open {
                    content.push_str(ending);
                }
            }
            // A file whose last line is the task itself ends with what that
            // line ends with; an open file is one whose last line is below.
            if below.is_empty() && open {
                let kept = content.trim_end_matches(['\n', '\r']).to_string();
                content = kept;
            }

            Note {
                content,
                line: above.len() as u32 + 1,
            }
        })
}

/// The file written into a directory of its own, and where the task in it is.
fn written(note: &Note) -> (tempfile::TempDir, EditTarget) {
    let dir = tempfile::tempdir().expect("tempdir");
    std::fs::write(dir.path().join("notes.md"), &note.content).expect("write");

    (
        dir,
        EditTarget {
            dir: String::new(),
            file: "notes.md".to_string(),
            line: note.line,
            heading: HEADING.to_string(),
        },
    )
}

/// A draft that names one field, which is what a phrase amounts to here.
fn phrase_draft() -> PhraseDraft {
    PhraseDraft {
        heading: String::new(),
        priority: Some("A".to_string()),
        keyword: None,
        date: None,
        time: None,
        repeater: None,
        status: None,
        cleared: Vec::new(),
    }
}

/// Every operation that writes a note, named once so a property covers them
/// all rather than one of them.
///
/// Only the rollback is taken from each: the outcomes differ in what else
/// they say, and what a property is about is the file rather than the report.
fn operation(index: usize, target: EditTarget) -> Result<Option<FileRollback>, EditError> {
    match index {
        0 => set_planning(
            target,
            PlanningKeyword::Scheduled,
            Some("2026-07-29".to_string()),
        )
        .map(|outcome| outcome.rollback),
        1 => set_priority(target, Some("B".to_string())).map(|outcome| outcome.rollback),
        2 => complete_task(target, "2026-07-28".to_string()).map(|outcome| outcome.rollback),
        _ => apply_phrase(target, phrase_draft()).map(|outcome| outcome.rollback),
    }
}

proptest! {
    // 64 rather than the 256 proptest defaults to: every case writes a file,
    // edits it and reads it back.
    #![proptest_config(ProptestConfig { cases: 64, ..ProptestConfig::default() })]

    /// An edit taken back leaves the file holding the bytes it held before,
    /// whatever the file was written as and whichever operation made the
    /// edit.
    #[test]
    fn an_edit_taken_back_leaves_the_bytes_that_were_there(
        note in note(),
        which in 0usize..4,
    ) {
        let (dir, mut target) = written(&note);
        target.dir = dir.path().display().to_string();

        let rollback = operation(which, target)
            .expect("edit")
            .expect("an edit that wrote has a rollback");
        let reverted = revert_files(dir.path().display().to_string(), vec![rollback]);

        prop_assert_eq!(reverted.restored, vec!["notes.md".to_string()]);
        prop_assert_eq!(body(dir.path()), note.content);
    }

    /// An edit rewrites the task it names and leaves everything else where it
    /// was: the lines above it are the bytes they were, and so is the file's
    /// mark and its last newline or lack of one.
    #[test]
    fn an_edit_leaves_the_rest_of_the_file_as_it_found_it(
        note in note(),
        which in 0usize..4,
    ) {
        let (dir, mut target) = written(&note);
        target.dir = dir.path().display().to_string();

        operation(which, target).expect("edit");
        let after = body(dir.path());

        let source: Vec<&str> = note.content.split_inclusive('\n').collect();
        let result: Vec<&str> = after.split_inclusive('\n').collect();
        let above = note.line as usize - 1;

        prop_assert_eq!(&result[..above], &source[..above]);
        // The task is still on the line it was named on: an operation
        // rewrites that line rather than moving the entry or taking it out.
        prop_assert!(
            result[above].contains(HEADING),
            "the task left the line it was addressed on: {result:?}"
        );
        // What an operation writes goes where the task is: the heading is
        // rewritten and a planning line appears under it. Everything below
        // that is the same lines in the same order, which is what keeps an
        // edit to one task out of the way of a merge with an edit to another.
        prop_assert!(
            result.ends_with(&source[note.line as usize..]),
            "the lines below the task did not come through: {result:?}"
        );
        prop_assert_eq!(after.starts_with('\u{FEFF}'), note.content.starts_with('\u{FEFF}'));
        prop_assert_eq!(after.ends_with('\n'), note.content.ends_with('\n'));
    }
}
