//! The file an edit works on, held as its own lines.
//!
//! A markdown file is rebuilt from the lines it was read as, each keeping the
//! ending it was written with, so a CRLF file stays CRLF and a file without a
//! trailing newline does not grow one. Lines nobody touched come back
//! byte-for-byte, which is what keeps an edit to one task out of the way of a
//! git merge with an edit to another.

use std::fs;
use std::path::{Component, Path, PathBuf};

use markdown_org_extract::{parse_heading_line, HeadingLine};

use crate::edit::{EditError, EditTarget};

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
        if !path.is_file() {
            return Err(EditError::NotFound {
                detail: path.display().to_string(),
            });
        }

        let content = fs::read_to_string(&path).map_err(|error| EditError::Io {
            detail: format!("{}: {error}", path.display()),
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

    pub(crate) fn save(&self) -> Result<(), EditError> {
        let mut content = String::new();
        for (body, ending) in &self.lines {
            content.push_str(body);
            content.push_str(ending);
        }
        fs::write(&self.path, content).map_err(|error| EditError::Io {
            detail: format!("{}: {error}", self.path.display()),
        })
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

        if line[heading.title_start..].trim() != target.heading.trim() {
            return Err(EditError::Stale {
                detail: format!(
                    "{}:{} holds {:?}, not {:?}",
                    target.file,
                    target.line,
                    &line[heading.title_start..],
                    target.heading
                ),
            });
        }

        Ok((index, heading))
    }
}
