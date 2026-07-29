//! The file an edit works on, held as its own lines.
//!
//! A markdown file is rebuilt from the lines it was read as, each keeping the
//! ending it was written with, so a CRLF file stays CRLF and a file without a
//! trailing newline does not grow one. Lines nobody touched come back
//! byte-for-byte, which is what keeps an edit to one task out of the way of a
//! git merge with an edit to another.
//!
//! A byte-order mark is read as part of the first line and written back with
//! it, so a file that carries one keeps it. Its heading is not editable
//! either way: the extractor anchors the heading grammar at the start of the
//! line, so a first line beginning with U+FEFF never becomes a task and never
//! reaches an edit.

use std::fs;
use std::io::{ErrorKind, Write};
use std::path::{Component, Path, PathBuf};

use markdown_org_extract::{display_text, parse_heading_line, HeadingLine};

use crate::edit::{EditError, EditTarget};

/// Name prefix of the file [`Document::save`] writes before renaming it over
/// the note. Nothing else in the notes directory is expected to carry it, so
/// a leftover from a process killed mid-write is recognisable — the commit
/// step skips anything named this way rather than committing it.
pub(crate) const TEMPORARY_PREFIX: &str = ".markdown-org-";

pub(crate) struct Document {
    path: PathBuf,
    /// Each line as its content and the ending that followed it. The last
    /// line of a file without a trailing newline has an empty ending.
    lines: Vec<(String, String)>,
}

impl Document {
    /// Read the file `target` names, refusing a path that leaves the notes
    /// directory.
    ///
    /// The path comes back from a scan of that very directory, so a `..` in it
    /// means something went wrong upstream rather than that the user asked for
    /// a file elsewhere.
    pub(crate) fn open(target: &EditTarget) -> Result<Self, EditError> {
        let relative = Path::new(&target.file);
        let climbs = relative
            .components()
            .any(|part| matches!(part, Component::ParentDir | Component::RootDir));
        if climbs {
            return Err(EditError::NotFound {
                detail: format!("{} is outside the notes directory", target.file),
            });
        }

        let path = Path::new(&target.dir).join(relative);
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

        let lines = content
            .split_inclusive('\n')
            .map(|raw| {
                let body = raw.trim_end_matches(['\n', '\r']);
                (body.to_string(), raw[body.len()..].to_string())
            })
            .collect();

        Ok(Self { path, lines })
    }

    pub(crate) fn line(&self, index: usize) -> Option<&str> {
        self.lines.get(index).map(|(body, _)| body.as_str())
    }

    pub(crate) fn set(&mut self, index: usize, content: String) {
        if let Some(line) = self.lines.get_mut(index) {
            line.0 = content;
        }
    }

    pub(crate) fn len(&self) -> usize {
        self.lines.len()
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
        let mut content = String::new();
        for (body, ending) in &self.lines {
            content.push_str(body);
            content.push_str(ending);
        }

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

        let line = self.line(index).unwrap_or_default();
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
