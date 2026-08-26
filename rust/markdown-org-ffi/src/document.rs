//! The file an edit works on, held as its own lines.
//!
//! A markdown file is rebuilt from the lines it was read as, each keeping the
//! ending it was written with, so a CRLF file stays CRLF and a file without a
//! trailing newline does not grow one. Lines nobody touched come back
//! byte-for-byte, which is what keeps an edit to one task out of the way of a
//! git merge with an edit to another.
//!
//! A byte-order mark is held apart from the lines and written back at the
//! front, so a file that carries one keeps it and its first heading is still
//! a heading. Keeping the mark on the line instead left the first task of
//! such a file uneditable: the heading grammar is anchored at the start of
//! the line, and a line beginning with U+FEFF matches nothing. The scan
//! agrees -- the markdown parser it goes through drops the mark before the
//! grammar sees the line, so both sides read the file as starting with a
//! heading.

use std::fs;
use std::io::{ErrorKind, Write};
use std::path::{Component, Path, PathBuf};

use markdown_org_extract::{display_text, parse_heading_line, HeadingLine};

use crate::edit::{EditError, EditTarget};
use crate::undo::FileRollback;

/// Name prefix of the file [`Document::save`] writes before renaming it over
/// the note. Nothing else in the notes directory is expected to carry it, so
/// a leftover from a process killed mid-write is recognisable — the commit
/// step skips anything named this way rather than committing it.
pub(crate) const TEMPORARY_PREFIX: &str = ".markdown-org-";

/// The mark editors such as Notepad and VS Code put at the front of a file
/// saved as "UTF-8 with BOM".
const BYTE_ORDER_MARK: char = '\u{FEFF}';

/// A file as its lines, each with the ending it was written with.
fn split_lines(content: &str) -> Vec<(String, String)> {
    content
        .split_inclusive('\n')
        .map(|raw| {
            let body = raw.trim_end_matches(['\n', '\r']);
            (body.to_string(), raw[body.len()..].to_string())
        })
        .collect()
}

/// A file's content without its byte-order mark, and whether it had one.
fn split_byte_order_mark(content: &str) -> (bool, &str) {
    match content.strip_prefix(BYTE_ORDER_MARK) {
        Some(rest) => (true, rest),
        None => (false, content),
    }
}

pub(crate) struct Document {
    path: PathBuf,
    /// Path of the file relative to the notes directory, as the caller named
    /// it. Kept because that is how a rollback names a file: an undo is given
    /// the notes directory and finds the file under it, and the absolute path
    /// this document was opened by would tie the pair to one device.
    file: String,
    /// Whether the file began with a byte-order mark. Held apart from the
    /// lines so the first line reads as what it is, and put back by
    /// [`Document::text`] so the file keeps the mark it was written with.
    byte_order_mark: bool,
    /// Each line as its content and the ending that followed it. The last
    /// line of a file without a trailing newline has an empty ending.
    lines: Vec<(String, String)>,
}

impl Document {
    /// Read the file `target` names, refusing a path that leaves the notes
    /// directory.
    pub(crate) fn open(target: &EditTarget) -> Result<Self, EditError> {
        Self::read(&target.dir, &target.file)
    }

    /// Where `file` sits under `dir`, refusing a path that leaves it.
    ///
    /// The path comes back from a scan of that very directory, so a `..` in it
    /// means something went wrong upstream rather than that the user asked for
    /// a file elsewhere. The one path that does not come from a scan is the
    /// file a collection receives new tasks in, which the user types into the
    /// settings — and that is the case this refusal is for.
    ///
    /// The path is also put together component by component rather than
    /// joined whole, so that `notes.md` and `./notes.md` resolve to one path:
    /// two documents of the same file are two copies that overwrite each
    /// other, and an operation reading both would lose whichever it saved
    /// first.
    fn resolve(dir: &str, file: &str) -> Result<PathBuf, EditError> {
        let mut relative = PathBuf::new();
        for part in Path::new(file).components() {
            match part {
                Component::Normal(name) => relative.push(name),
                Component::CurDir => {}
                Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                    return Err(EditError::NotFound {
                        detail: format!("{file} is outside the notes directory"),
                    })
                }
            }
        }

        Ok(Path::new(dir).join(relative))
    }

    /// Where this document is on disk, for an operation working on two of them
    /// at once: two documents of the same file are two copies, and the one
    /// saved last is the only one that survives.
    pub(crate) fn path(&self) -> &Path {
        &self.path
    }

    /// Read `file` from under `dir`, or start an empty document where there is
    /// nothing there yet.
    ///
    /// For the file a collection receives new tasks in: it is named in the
    /// settings rather than found by a scan, so the first task written to it
    /// is also what creates it. Only a file that is not there at all is
    /// started from nothing — anything else standing at that path, a directory
    /// or a symbolic link, is refused by [`Document::read`] rather than
    /// written over.
    ///
    /// The directory above it is not created: a receiving file named under a
    /// directory nobody made is a typo in the settings, and making the
    /// directory would leave the typo in place and the notes with an empty
    /// tree in them.
    pub(crate) fn read_or_empty(dir: &str, file: &str) -> Result<Self, EditError> {
        let path = Self::resolve(dir, file)?;
        if fs::symlink_metadata(&path).is_ok() {
            return Self::read(dir, file);
        }

        let directory = path.parent().unwrap_or_else(|| Path::new("."));
        if !directory.is_dir() {
            return Err(EditError::NotFound {
                detail: format!("{} is not a directory", directory.display()),
            });
        }

        Ok(Self {
            path,
            file: file.to_string(),
            byte_order_mark: false,
            lines: Vec::new(),
        })
    }

    /// Read `file` from under `dir`, refusing a path that leaves it.
    pub(crate) fn read(dir: &str, file: &str) -> Result<Self, EditError> {
        let path = Self::resolve(dir, file)?;
        // `symlink_metadata` rather than `is_file`, which follows the link:
        // `..` is not the only way out of the directory, and a link inside it
        // points anywhere. The scan the path comes from does not follow links
        // either, so a link is never a note the caller could have meant.
        let found = fs::symlink_metadata(&path)
            .map(|metadata| metadata.is_file())
            .unwrap_or(false);
        if !found {
            return Err(EditError::NotFound {
                detail: path.display().to_string(),
            });
        }

        let content = fs::read_to_string(&path).map_err(|error| {
            // `InvalidData` from `read_to_string` has exactly one cause: the
            // bytes are not UTF-8. Reported apart from the other IO failures
            // because the answer differs — the file has to be converted, not
            // the operation retried.
            if error.kind() == ErrorKind::InvalidData {
                EditError::NotUtf8 {
                    detail: path.display().to_string(),
                }
            } else {
                EditError::Io {
                    detail: format!("{}: {error}", path.display()),
                }
            }
        })?;

        let (byte_order_mark, body) = split_byte_order_mark(&content);

        Ok(Self {
            path,
            file: file.to_string(),
            byte_order_mark,
            lines: split_lines(body),
        })
    }

    /// The line at `index`, which the caller has already established is there.
    ///
    /// Indexed rather than handed back as an `Option`. Every caller arrives
    /// with a bound of its own — [`Document::heading`] for the heading of a
    /// task, a range over [`Document::len`] for the lines under it — so a miss
    /// is an indexing mistake in this crate. Substituting an empty line for
    /// one, as this used to, turned that mistake into an edit that wrote
    /// nothing and reported success.
    pub(crate) fn at(&self, index: usize) -> &str {
        &self.lines[index].0
    }

    /// Put `content` on line `index`, which must be a line of this document.
    ///
    /// Out of range panics for the same reason [`Document::at`] does: an edit
    /// aimed past the end of the file used to do nothing at all, while
    /// `edit_heading` went on to report `changed: true` and save the file.
    pub(crate) fn set(&mut self, index: usize, content: String) {
        self.lines[index].0 = content;
    }

    pub(crate) fn len(&self) -> usize {
        self.lines.len()
    }

    /// Drop the line at `index` from the document.
    ///
    /// The line that preceded it keeps the ending it was written with, except
    /// where the dropped line was the last one and ended the file without a
    /// newline: that property belongs to the file rather than to the line, so
    /// it moves to whatever line is last now. A file that did not end in a
    /// newline must not grow one because a planning line was taken out of it.
    pub(crate) fn remove(&mut self, index: usize) {
        let (_, ending) = self.lines.remove(index);

        if ending.is_empty() {
            if let Some(last) = self.lines.last_mut() {
                last.1 = String::new();
            }
        }
    }

    /// Put `lines` where the lines of `range` were.
    ///
    /// Each replacement takes the ending of the line it stands in place of,
    /// so a body that came back unedited is written out byte for byte —
    /// including a file of mixed endings, where a prevailing ending imposed
    /// on every line would rewrite lines nobody touched. Lines beyond the
    /// range replaced take the ending the file opens with.
    ///
    /// Whether the file ends in a newline belongs to the file rather than to
    /// its last line, so it is read off before the splice and put back after
    /// it: an entry edited at the end of a file that ended without one must
    /// not grow one, and a line that stops being last must not run into the
    /// one that follows it.
    pub(crate) fn replace_lines(&mut self, range: std::ops::Range<usize>, lines: Vec<String>) {
        let ending = self.ending();
        let open = self.lines.last().is_some_and(|(_, end)| end.is_empty());

        let replaced: Vec<_> = lines
            .into_iter()
            .enumerate()
            .map(|(offset, body)| {
                let inherited = self
                    .lines
                    .get(range.start + offset)
                    .filter(|_| range.start + offset < range.end)
                    .map(|(_, end)| end.clone())
                    .filter(|end| !end.is_empty());
                (body, inherited.unwrap_or_else(|| ending.clone()))
            })
            .collect();
        self.lines.splice(range, replaced);

        let last = self.lines.len().saturating_sub(1);
        for (index, (_, end)) in self.lines.iter_mut().enumerate() {
            if index < last && end.is_empty() {
                *end = ending.clone();
            }
        }
        if let Some((_, end)) = self.lines.last_mut() {
            if open {
                end.clear();
            } else if end.is_empty() {
                *end = ending;
            }
        }
    }

    /// The line ending the file is written with, taken from the first line
    /// that has one. A file of a single line without a newline has none to
    /// take, and a line added to it gets the ending of the platform the notes
    /// are read on everywhere else.
    fn ending(&self) -> String {
        self.lines
            .iter()
            .map(|(_, end)| end)
            .find(|end| !end.is_empty())
            .cloned()
            .unwrap_or_else(|| "\n".to_string())
    }

    /// The file as it now stands, byte for byte as [`Document::save`] writes
    /// it.
    pub(crate) fn text(&self) -> String {
        // Sized before it is filled: the file is known in full here, and a
        // string grown from nothing reallocates and copies its way up to the
        // same length once per doubling.
        let mark = if self.byte_order_mark {
            BYTE_ORDER_MARK.len_utf8()
        } else {
            0
        };
        let size = mark
            + self
                .lines
                .iter()
                .map(|(body, ending)| body.len() + ending.len())
                .sum::<usize>();
        let mut content = String::with_capacity(size);
        if self.byte_order_mark {
            content.push(BYTE_ORDER_MARK);
        }
        for (body, ending) in &self.lines {
            content.push_str(body);
            content.push_str(ending);
        }
        content
    }

    /// Replace the whole document with `content`, read the way a file is.
    ///
    /// What an undo writes back: the text it restores was produced by
    /// [`Document::text`], so a file goes back to the bytes it held rather
    /// than to a re-rendering of its lines.
    pub(crate) fn set_text(&mut self, content: &str) {
        let (byte_order_mark, body) = split_byte_order_mark(content);

        self.byte_order_mark = byte_order_mark;
        self.lines = split_lines(body);
    }

    /// Write the file out, handing back what it held before and what it
    /// holds now.
    ///
    /// The pair is what an undo works from -- see [`crate::undo`] -- and it is
    /// taken here rather than left to each edit, so that every write produces
    /// one: an edit that forgot to would be an edit that cannot be taken back.
    ///
    /// `before` is the caller's, because only the caller knows when it stopped
    /// being true: it has to be read off the document before the first line is
    /// changed, and by the time this is called the change has been made.
    pub(crate) fn saved(&self, before: String) -> Result<FileRollback, EditError> {
        self.save()?;

        Ok(FileRollback {
            file: self.file.clone(),
            before,
            after: self.text(),
        })
    }

    /// Write the file out as a whole.
    ///
    /// The content goes to a temporary file beside the target and is renamed
    /// over it, because `rename` within one directory is atomic: a write that
    /// runs out of space or is killed partway leaves the notes exactly as
    /// they were, and `EditError::Io` therefore means "the file was not
    /// changed". Writing over the file in place truncates it first, and the
    /// next successful edit would commit that truncation to git.
    pub(crate) fn save(&self) -> Result<(), EditError> {
        let content = self.text();

        // The temporary has to share the directory with the target: `rename`
        // is only atomic within one filesystem.
        let directory = self.path.parent().unwrap_or_else(|| Path::new("."));
        let mut temporary = tempfile::Builder::new()
            .prefix(TEMPORARY_PREFIX)
            .suffix(".tmp")
            .tempfile_in(directory)
            .map_err(|error| self.failed(&error))?;

        temporary
            .write_all(content.as_bytes())
            .map_err(|error| self.failed(&error))?;
        // The rename below is atomic with respect to other readers, not with
        // respect to power loss: without this the directory entry can point
        // at a file whose content has not reached the device yet.
        temporary
            .as_file()
            .sync_all()
            .map_err(|error| self.failed(&error))?;

        // A fresh temporary file is created 0600. The note keeps the mode it
        // was written with instead — it is the user's file, and a checkout
        // that syncs it back would otherwise show a mode change.
        if let Ok(metadata) = fs::metadata(&self.path) {
            fs::set_permissions(temporary.path(), metadata.permissions())
                .map_err(|error| self.failed(&error))?;
        }

        temporary
            .persist(&self.path)
            .map_err(|error| self.failed(&error.error))?;
        Ok(())
    }

    fn failed(&self, error: &std::io::Error) -> EditError {
        EditError::Io {
            detail: format!("{}: {error}", self.path.display()),
        }
    }

    /// Locate the heading `target` points at, checking that it is still the
    /// one the caller saw.
    ///
    /// A sync between building the agenda and running the edit can have moved
    /// the file on. Writing to a line that now holds something else is the one
    /// failure mode that damages notes, so the text is compared before
    /// anything is written.
    pub(crate) fn heading(&self, target: &EditTarget) -> Result<(usize, HeadingLine), EditError> {
        let index = (target.line as usize)
            .checked_sub(1)
            .filter(|index| *index < self.lines.len())
            .ok_or_else(|| EditError::Stale {
                detail: format!("line {} is past the end of {}", target.line, target.file),
            })?;

        let line = self.at(index);
        let heading = parse_heading_line(line).ok_or_else(|| EditError::Stale {
            detail: format!("{}:{} is not a heading", target.file, target.line),
        })?;

        // Compared after the inline markup is taken off, because that is what
        // the caller was handed: `Task::heading` comes out of a scan with the
        // asterisks, backticks and link syntax already gone. Comparing the
        // raw slice would refuse every edit of a heading carrying **bold**, a
        // link or `code` as stale, while the file has not moved at all.
        let title = display_text(&line[heading.title_start..]);
        if title != display_text(&target.heading) {
            return Err(EditError::Stale {
                detail: format!(
                    "{}:{} holds {:?}, not {:?}",
                    target.file, target.line, title, target.heading
                ),
            });
        }

        Ok((index, heading))
    }
}
