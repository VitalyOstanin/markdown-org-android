//! The notes held between calls, so an edit does not cost a walk of them.
//!
//! [`scan_agenda`](crate::scan_agenda) walks the directory and builds an
//! agenda in one call, which is the right shape when the answer is wanted
//! once. It is the wrong shape after an edit: a tap that moves one task by a
//! day re-reads and re-parses every note to find out what the other thousands
//! of them still say. On a device that is seconds, and all of it is the walk —
//! sorting and bucketing the tasks afterwards costs almost nothing.
//!
//! [`NotesIndex`] keeps the tasks from the last walk. An edit tells it which
//! file changed, it re-parses that one file, and the agenda is rebuilt from
//! what it holds. Everything the agenda means — the day windows, the repeats,
//! the order — stays in the extractor: this holds tasks, it does not interpret
//! them.
//!
//! What it does not do is notice changes it was not told about. A fetch that
//! fast-forwards the checkout rewrites files behind its back, and so does a
//! change of directory; both call [`NotesIndex::rescan`]. That is the whole of
//! the contract, and it is why the index is handed out per directory rather
//! than kept in a global.

use std::path::{Component, Path, PathBuf};
use std::sync::Mutex;

use markdown_org_extract::{
    extract_tasks_with_counter, get_weekday_mappings, scan_directory, ScanOptions,
    Task as CoreTask, MAX_FILE_SIZE,
};

use crate::{
    build_agenda, AgendaResult, ExtractError, Options, ScanStats, Scope, DEFAULT_GLOB,
    DEFAULT_LOCALE,
};

/// The notes of one directory, kept between calls.
///
/// Cheap to ask for an agenda, expensive to build: the constructor walks the
/// directory, and so does [`rescan`](Self::rescan).
#[derive(uniffi::Object)]
pub struct NotesIndex {
    /// The scanned root, canonical — the paths in the tasks are relative to
    /// it, and re-reading one of them joins it back on.
    dir: PathBuf,
    glob: String,
    locale: String,
    max_tasks: usize,
    state: Mutex<IndexState>,
}

/// What the last walk found, and what edits have done to it since.
struct IndexState {
    tasks: Vec<CoreTask>,
    /// From the last full walk. A single file re-read does not update these:
    /// they describe a pass over the whole directory, and pretending otherwise
    /// would report "no files failed" after a walk in which some did.
    stats: ScanStats,
}

#[uniffi::export]
impl NotesIndex {
    /// Walk `dir` and hold what was found.
    ///
    /// Fails the same ways a scan does — a directory that is not there, a glob
    /// that does not compile — and for the same reason: this is that scan.
    #[uniffi::constructor]
    pub fn open(dir: String, options: Options) -> Result<Self, ExtractError> {
        let glob = options.glob.unwrap_or_else(|| DEFAULT_GLOB.to_string());
        let locale = options.locale.unwrap_or_else(|| DEFAULT_LOCALE.to_string());
        let defaults = ScanOptions::default();
        let max_tasks = options
            .max_tasks
            .map_or(defaults.max_tasks, |max| max as usize);

        let index = Self {
            dir: markdown_org_extract::scan::validate_dir(Path::new(&dir))?,
            glob,
            locale,
            max_tasks,
            state: Mutex::new(IndexState {
                tasks: Vec::new(),
                stats: markdown_org_extract::ProcessingStats::default().into(),
            }),
        };
        index.rescan()?;

        Ok(index)
    }

    /// Walk the directory again, replacing everything held.
    ///
    /// For the changes this was not told about one at a time: a fetch that
    /// fast-forwarded the checkout, a clone that filled the directory, notes
    /// edited by another application on the device.
    pub fn rescan(&self) -> Result<(), ExtractError> {
        let outcome = scan_directory(&self.dir, &self.options(), None)?;
        let mut state = self.lock();
        state.tasks = outcome.tasks;
        state.stats = outcome.stats.into();

        Ok(())
    }

    /// Re-read one file, replacing the tasks that came from it.
    ///
    /// `file` is a path relative to the scanned directory, exactly as it comes
    /// back in [`Task::file`](crate::Task::file).
    ///
    /// A file that has gone, cannot be read, or is not UTF-8 is not a failure:
    /// its tasks are dropped and the rest of the index stands. That is what the
    /// walk would have done with it, and an edit that deleted the last task in
    /// a note has to leave the agenda without it rather than with an error.
    pub fn refresh_file(&self, file: String) -> Result<(), ExtractError> {
        let relative = Path::new(&file);
        // The path comes back from a scan of this directory, so `..` in it is a
        // mistake upstream rather than a request — refused rather than joined,
        // which would read a file outside the notes.
        let climbs = relative
            .components()
            .any(|part| matches!(part, Component::ParentDir | Component::RootDir));
        if climbs {
            return Err(ExtractError::InvalidDirectory {
                detail: format!("{file} is outside the notes directory"),
            });
        }

        let mappings = get_weekday_mappings(&self.locale);
        let fresh = read_file(&self.dir.join(relative))
            .map(|content| {
                let mut timestamps = 0_usize;
                let mut properties = 0_usize;
                extract_tasks_with_counter(
                    relative,
                    &content,
                    &mappings,
                    self.max_tasks,
                    &mut timestamps,
                    &mut properties,
                )
            })
            .unwrap_or_default();

        let mut state = self.lock();
        state.tasks.retain(|task| Path::new(&task.file) != relative);
        state.tasks.extend(fresh);
        // The cap belongs to the collection, not to one file: without this a
        // note whose tasks grew would push the total past a limit the walk
        // itself enforces.
        state.tasks.truncate(self.max_tasks);

        Ok(())
    }

    /// Build the agenda from what is held, walking nothing.
    ///
    /// The arguments are those of [`scan_agenda`](crate::scan_agenda), and so
    /// is the answer: the same tasks through the same filter produce the same
    /// agenda, whether they were just read or read a hundred edits ago.
    pub fn agenda(
        &self,
        scope: Scope,
        current_date: String,
        timezone: String,
        include_done: bool,
    ) -> Result<AgendaResult, ExtractError> {
        let state = self.lock();
        // Cloned because the filter consumes what it is given and this index
        // outlives the answer. It is the cost of holding the tasks rather than
        // re-reading them, and it is the whole of what an agenda now costs.
        build_agenda(
            state.tasks.clone(),
            state.stats.clone(),
            scope,
            &current_date,
            &timezone,
            include_done,
        )
    }
}

impl NotesIndex {
    fn options(&self) -> ScanOptions<'_> {
        ScanOptions {
            glob: &self.glob,
            locale: &self.locale,
            max_tasks: self.max_tasks,
            absolute_paths: false,
        }
    }

    /// The held state, recovering a lock a panicking caller left poisoned.
    ///
    /// A poisoned lock here means a panic inside one of the methods above, and
    /// the state it guards is a cache: the worst it can be is stale, and the
    /// answer to that is a rescan rather than a dead index the application
    /// cannot use again.
    fn lock(&self) -> std::sync::MutexGuard<'_, IndexState> {
        self.state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

/// Read a file the way the walk does: capped, and UTF-8 or nothing.
fn read_file(path: &Path) -> Option<String> {
    let size = std::fs::symlink_metadata(path)
        .ok()
        .filter(|metadata| metadata.is_file())
        .map(|metadata| metadata.len())?;
    if size > MAX_FILE_SIZE {
        return None;
    }

    std::fs::read_to_string(path).ok()
}
