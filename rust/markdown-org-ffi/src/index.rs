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
    extract_tasks_with_counter, get_weekday_mappings, scan_directories, ScanOptions,
    Task as CoreTask, MAX_FILE_SIZE,
};

use crate::{
    build_agenda, AgendaResult, ExtractError, Options, ScanStats, Scope, DEFAULT_GLOB,
    DEFAULT_LOCALE,
};

/// The notes of one or more directories, kept between calls.
///
/// Cheap to ask for an agenda, expensive to build: the constructor walks every
/// directory, and so does [`rescan`](Self::rescan).
///
/// Several roots are one index rather than one index each because the agenda
/// over them is one agenda: the task cap is a budget for the whole of it, the
/// scan statistics are one report, and the ordering belongs to the extractor.
/// Each task carries the root it came from, so an edit reaches the collection
/// it belongs to.
#[derive(uniffi::Object)]
pub struct NotesIndex {
    /// The scanned roots, canonical and in the order they were given — the
    /// paths in the tasks are relative to one of them, and re-reading a file
    /// joins its own root back on.
    dirs: Vec<PathBuf>,
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
    /// Walk `dirs` and hold what was found.
    ///
    /// Fails the same ways a scan does — a directory that is not there, a glob
    /// that does not compile — and for the same reason: this is that scan. An
    /// empty list is refused: an index over nothing would answer every agenda
    /// with an empty one, which reads as a collection with no tasks in it.
    #[uniffi::constructor]
    pub fn open(dirs: Vec<String>, options: Options) -> Result<Self, ExtractError> {
        let glob = options.glob.unwrap_or_else(|| DEFAULT_GLOB.to_string());
        let locale = options.locale.unwrap_or_else(|| DEFAULT_LOCALE.to_string());
        let defaults = ScanOptions::default();
        let max_tasks = options
            .max_tasks
            .map_or(defaults.max_tasks, |max| max as usize);

        if dirs.is_empty() {
            return Err(ExtractError::InvalidDirectory {
                detail: "no notes directory to scan".to_string(),
            });
        }

        let roots = dirs
            .iter()
            .map(|dir| markdown_org_extract::scan::validate_dir(Path::new(dir)))
            .collect::<Result<Vec<_>, _>>()?;

        let index = Self {
            dirs: roots,
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

    /// Walk the directories again, replacing everything held.
    ///
    /// For the changes this was not told about one at a time: a fetch that
    /// fast-forwarded the checkout, a clone that filled the directory, notes
    /// edited by another application on the device.
    pub fn rescan(&self) -> Result<(), ExtractError> {
        let outcome = scan_directories(&self.dirs, &self.options(), None)?;
        let mut state = self.lock();
        state.tasks = outcome.tasks;
        state.stats = outcome.stats.into();

        Ok(())
    }

    /// Re-read one file of one root, replacing the tasks that came from it.
    ///
    /// `root` and `file` are what the task itself carries — see
    /// [`Task::root`](crate::Task::root) and [`Task::file`](crate::Task::file)
    /// — so the pair names the note whatever collection it belongs to. A root
    /// this index was not opened over is refused: joining an arbitrary path on
    /// would read a file outside the notes.
    ///
    /// A file that has gone, cannot be read, or is not UTF-8 is not a failure:
    /// its tasks are dropped and the rest of the index stands. That is what the
    /// walk would have done with it, and an edit that deleted the last task in
    /// a note has to leave the agenda without it rather than with an error.
    pub fn refresh_file(&self, root: String, file: String) -> Result<(), ExtractError> {
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

        let scanned = self
            .dirs
            .iter()
            .find(|dir| dir.as_path() == Path::new(&root))
            .ok_or_else(|| ExtractError::InvalidDirectory {
                detail: format!("{root} is not one of the scanned directories"),
            })?;

        let mappings = get_weekday_mappings(&self.locale);
        let fresh = read_file(&scanned.join(relative))
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
        // Both halves, not the path alone: the same relative path in another
        // collection is another file, and dropping its tasks here would take
        // them out of the agenda until the next full walk.
        state
            .tasks
            .retain(|task| !(task.root.as_deref() == Some(root.as_str()) && task.file == file));
        state.tasks.extend(fresh.into_iter().map(|mut task| {
            task.root = Some(root.clone());
            task
        }));
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
        date: Option<String>,
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
            date.as_deref(),
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
