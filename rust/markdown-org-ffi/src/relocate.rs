//! Moving an entry from one file of a collection to another.
//!
//! The other operations here rewrite lines of one file. This one carries lines
//! from one file to another, and that is a difference worth stating: the entry
//! travels byte for byte, so what git records is a removal and an addition of
//! the same text rather than a rewrite of either file. Nothing is renumbered,
//! no heading is deepened to fit under whatever it lands beside, and a note
//! that was read one way before the move reads the same way after it.
//!
//! What travels is the entry as a reader means it: the heading, the planning
//! lines under it, its property block, its text, and every heading nested
//! below it. The next heading of the same level or shallower is where it
//! stops — that heading is the next entry, and taking it would be taking a
//! note the user did not point at.
//!
//! Both files are of the same collection, which is what makes this one edit.
//! Between two collections it would be two git checkouts, two commits and two
//! undos, and a second step that failed would leave the entry in both places
//! or in neither.

use std::ops::Range;

use markdown_org_extract::parse_heading_line;

use crate::create::{placed, WritePosition};
use crate::document::Document;
use crate::edit::{EditError, EditTarget};
use crate::undo::FileRollback;

/// What moving an entry did.
#[derive(Debug, Clone, uniffi::Record)]
pub struct MoveOutcome {
    /// The heading line, as it stood in the file it left and stands in the
    /// file it reached: a move rewrites nothing.
    pub line: String,
    /// The file the entry now sits in, relative to the notes directory.
    pub file: String,
    /// What each file held before the move and holds after it, the file the
    /// entry left first.
    ///
    /// Two of them, which is why this is a list where a single edit hands back
    /// one: undoing the move means putting both files back, and
    /// [`crate::revert_files`] takes them together.
    pub rollback: Vec<FileRollback>,
}

/// Carry the entry `target` points at into `file`, and save both.
///
/// `file` is relative to the same notes directory as `target`, and is created
/// when it is not there yet — the file a collection calls its main one need
/// not exist before something is moved into it. `at` says where in it the
/// entry lands, the same setting new tasks are written by.
///
/// The receiving file is written first. Should the second write fail, the
/// entry is momentarily in both files rather than in neither, and the write to
/// the receiving file is taken back before the failure is reported — losing
/// the entry outright is the one outcome worth ordering the writes around.
#[uniffi::export]
pub fn move_entry(
    target: EditTarget,
    file: String,
    at: WritePosition,
) -> Result<MoveOutcome, EditError> {
    let mut source = Document::open(&target)?;
    let (index, heading) = source.heading(&target)?;
    let mut destination = Document::read_or_empty(&target.dir, &file)?;
    // Compared as paths rather than as the strings the caller passed: the same
    // file can be named `notes.md` and `./notes.md`, and moving a file into
    // itself would read the entry once, write it twice and remove it from a
    // copy of the file that is then overwritten by the other.
    if source.path() == destination.path() {
        return Err(EditError::Unsupported {
            detail: format!("{file} is the file this entry is already in"),
        });
    }

    let bounds = entry_bounds(&source, index, heading.level);
    let line = source.at(index).to_string();
    let mut carried: Vec<String> = bounds.clone().map(|at| source.at(at).to_string()).collect();
    // The blank line that separated this entry from the next belongs to the
    // file rather than to the entry: carried along it would open a gap in the
    // receiving file, and left behind it is the separator that now stands
    // between the entries either side of the one taken out.
    while carried.last().is_some_and(|line| line.trim().is_empty()) {
        carried.pop();
    }

    let removal = removal_bounds(&source, bounds);
    let placement = placed(&destination, at, carried);

    let received_before = destination.text();
    destination.replace_lines(placement.at..placement.at, placement.lines);
    let left_before = source.text();
    source.replace_lines(removal, Vec::new());

    let received = destination.saved(received_before.clone())?;
    let left = match source.saved(left_before) {
        Ok(rollback) => rollback,
        Err(error) => {
            // Put the receiving file back rather than leave the entry standing
            // in two places. It was written a moment ago and by this process,
            // so what is being undone is this move and nothing else; a restore
            // that fails too is not reported over the failure that matters.
            destination.set_text(&received_before);
            let _ = destination.save();
            return Err(error);
        }
    };

    Ok(MoveOutcome {
        line,
        file,
        rollback: vec![left, received],
    })
}

/// Which lines the entry at `index` is made of.
///
/// From the heading to the next heading of its own level or shallower, the
/// blank line before that heading included: a deeper heading is part of this
/// entry, and one at the same level is the next entry. A file that holds
/// nothing after it ends the entry at its end.
fn entry_bounds(document: &Document, index: usize, level: usize) -> Range<usize> {
    let mut end = index + 1;
    while end < document.len() {
        let next = parse_heading_line(document.at(end)).is_some_and(|next| next.level <= level);
        if next {
            break;
        }
        end += 1;
    }

    index..end
}

/// The lines to take out of the file the entry is leaving.
///
/// The entry itself, and — where it was the last thing in the file — the blank
/// line that stood above it. That line separated this entry from the one
/// before it, and a file whose last entry was moved away would otherwise end
/// in a blank line it did not have before.
fn removal_bounds(document: &Document, entry: Range<usize>) -> Range<usize> {
    let last = entry.end == document.len();
    let separated = entry.start > 0 && document.at(entry.start - 1).trim().is_empty();

    if last && separated {
        entry.start - 1..entry.end
    } else {
        entry
    }
}
