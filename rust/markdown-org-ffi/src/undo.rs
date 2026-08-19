//! Putting a note back the way an edit found it.
//!
//! Every edit hands back what the file held before it and what it holds
//! after, and that pair is all an undo needs: the note goes back to bytes it
//! actually had rather than to a re-rendering of what the edit thinks it did.
//!
//! Both halves are kept because an undo has to know it is undoing its own
//! edit. A file the user has changed in the meantime -- a sync landed, another
//! edit was made, the note was opened in another application -- is left alone
//! rather than reverted over: taking away work done after the edit is worse
//! than the edit being undone.
//!
//! The same pair serves one tap and a group of twenty. A group is several
//! files at once and a single edit is one, which is a difference in how many
//! rather than in kind, so [`revert_files`] takes a list either way.

use crate::document::Document;

/// A file as it was before an edit and as it stands after.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FileRollback {
    /// Path of the file relative to the notes directory.
    pub file: String,
    /// The file as it stood before the edit was applied.
    pub before: String,
    /// The file as the edit left it.
    pub after: String,
}

/// What undoing did.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RevertOutcome {
    /// Files put back the way they were.
    pub restored: Vec<String>,
    /// Files that no longer held what the edit wrote and were left alone.
    pub skipped: Vec<String>,
    /// Files that could not be read or written back.
    pub failed: Vec<String>,
}

/// Put the files back the way the edit that produced `rollback` found them.
///
/// A file is restored only when it still holds exactly what was written into
/// it. Anything else is skipped and named, so the caller can say that part of
/// it went back and part of it did not; nothing is refused outright, because
/// a group spanning several files can have one of them moved on and the rest
/// untouched.
#[uniffi::export]
pub fn revert_files(dir: String, rollback: Vec<FileRollback>) -> RevertOutcome {
    let mut outcome = RevertOutcome {
        restored: Vec::new(),
        skipped: Vec::new(),
        failed: Vec::new(),
    };

    for entry in rollback {
        let Ok(mut document) = Document::read(&dir, &entry.file) else {
            outcome.failed.push(entry.file);
            continue;
        };

        if document.text() != entry.after {
            outcome.skipped.push(entry.file);
            continue;
        }

        document.set_text(&entry.before);
        match document.save() {
            Ok(()) => outcome.restored.push(entry.file),
            Err(_) => outcome.failed.push(entry.file),
        }
    }

    outcome
}
