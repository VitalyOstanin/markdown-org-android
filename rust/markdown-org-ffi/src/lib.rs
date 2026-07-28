//! UniFFI surface over [`markdown_org_extract`].
//!
//! The application calls the extractor in-process: Android does not let an
//! app spawn the CLI, and going through a subprocess plus JSON would cost a
//! serialise/parse round trip on data that is about to be rendered by the
//! same process anyway.
//!
//! The types here are deliberately not the extractor's own. They are a
//! flattened projection: what the UI needs, in shapes UniFFI can carry
//! across the FFI boundary, so the extractor stays free to evolve its
//! internals. The conversion in this crate is the only place that has to
//! follow such a change.

use std::path::Path;

use markdown_org_extract::{
    filter_agenda, scan_directory, AgendaDates, AgendaOutput, AgendaScope, AppError, ScanOptions,
};

uniffi::setup_scaffolding!();

/// What went wrong. The extractor's error type carries an `io::Error` that
/// cannot cross the boundary, so it is rendered to a string here.
///
/// The field is `detail`, not `message`: UniFFI turns a variant field into a
/// constructor property of the generated Kotlin exception, and a property
/// named `message` collides with `Throwable.message` — the generated file
/// then declares it twice and does not compile.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum ExtractError {
    /// The path does not exist or is not a directory.
    #[error("invalid directory: {detail}")]
    InvalidDirectory {
        /// Human-readable detail.
        detail: String,
    },
    /// A date argument could not be parsed, or the window is inverted.
    #[error("invalid date: {detail}")]
    InvalidDate {
        /// Human-readable detail.
        detail: String,
    },
    /// The timezone name is not one of the IANA zones.
    #[error("invalid timezone: {detail}")]
    InvalidTimezone {
        /// Human-readable detail.
        detail: String,
    },
    /// The file glob is malformed.
    #[error("invalid glob: {detail}")]
    InvalidGlob {
        /// Human-readable detail.
        detail: String,
    },
    /// Anything else, including IO failures.
    #[error("{detail}")]
    Other {
        /// Human-readable detail.
        detail: String,
    },
}

impl From<AppError> for ExtractError {
    fn from(error: AppError) -> Self {
        let detail = error.to_string();
        match error {
            AppError::InvalidDirectory(_) => ExtractError::InvalidDirectory { detail },
            AppError::InvalidDate(_) | AppError::DateRange(_) => {
                ExtractError::InvalidDate { detail }
            }
            AppError::InvalidTimezone(_) => ExtractError::InvalidTimezone { detail },
            AppError::InvalidGlob(_) => ExtractError::InvalidGlob { detail },
            _ => ExtractError::Other { detail },
        }
    }
}

/// Org keyword a task carries. The two spellings of the cancelled keyword
/// collapse into one variant: the distinction matters when writing a file
/// back, which the extractor handles, not when displaying it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TaskType {
    /// `TODO`.
    Todo,
    /// `DONE`.
    Done,
    /// `CANCELLED` or `CANCELED`.
    Cancelled,
}

/// How wide an agenda window to build.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Scope {
    /// A single day.
    Day,
    /// Seven days from the anchor date.
    Week,
    /// The calendar month containing the anchor date.
    Month,
    /// No window: every task, as a flat list.
    Tasks,
}

impl From<Scope> for AgendaScope {
    fn from(scope: Scope) -> Self {
        match scope {
            Scope::Day => AgendaScope::Day,
            Scope::Week => AgendaScope::Week,
            Scope::Month => AgendaScope::Month,
            Scope::Tasks => AgendaScope::Tasks,
        }
    }
}

/// One task, flattened for display.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Task {
    /// Path relative to the scanned root.
    pub file: String,
    /// 1-based line of the heading, for opening the file at the right place.
    pub line: u32,
    /// Heading text without the keyword, priority cookie or tags.
    pub heading: String,
    /// Org keyword, absent for a heading that carries none.
    pub task_type: Option<TaskType>,
    /// Priority cookie as written (`A`, `B`, `12`).
    pub priority: Option<String>,
    /// `SCHEDULED`, `DEADLINE`, `CLOSED`, or absent for a bare timestamp.
    pub timestamp_type: Option<String>,
    /// Date as `YYYY-MM-DD`.
    pub timestamp_date: Option<String>,
    /// Start time as `HH:MM`; absent for an all-day task.
    pub timestamp_time: Option<String>,
    /// Repeater as written (`++7d`), absent for a one-off task.
    pub timestamp_repeater: Option<String>,
    /// Next occurrence of a repeating task as `YYYY-MM-DD`.
    pub timestamp_next: Option<String>,
    /// Days from the agenda date: negative is overdue. Only set on tasks
    /// returned by [`agenda`], never by [`scan`].
    pub days_offset: Option<i64>,
}

impl From<markdown_org_extract::Task> for Task {
    fn from(task: markdown_org_extract::Task) -> Self {
        use markdown_org_extract::TaskType as SourceType;

        Self {
            file: task.file,
            line: task.line,
            heading: task.heading,
            task_type: task.task_type.map(|kind| match kind {
                SourceType::Todo => TaskType::Todo,
                SourceType::Done => TaskType::Done,
                SourceType::Cancelled(_) => TaskType::Cancelled,
            }),
            priority: task.priority.map(|priority| priority.to_string()),
            timestamp_type: task.timestamp_type,
            timestamp_date: task.timestamp_date,
            timestamp_time: task.timestamp_time,
            timestamp_repeater: task.timestamp_repeater,
            timestamp_next: task.timestamp_next,
            days_offset: None,
        }
    }
}

/// Tasks falling on one day, already split into the buckets the agenda
/// renders as separate sections.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Day {
    /// The day, as `YYYY-MM-DD`.
    pub date: String,
    /// Tasks whose date has passed.
    pub overdue: Vec<Task>,
    /// Tasks at a specific time on this day, ordered by that time.
    pub scheduled_timed: Vec<Task>,
    /// All-day tasks on this day.
    pub scheduled_no_time: Vec<Task>,
    /// Tasks dated later but close enough to warn about.
    pub upcoming: Vec<Task>,
}

/// Result of a scan: the tasks, plus enough of the statistics to tell the
/// user that the run was not clean.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ScanResult {
    /// Every task found, in walk order.
    pub tasks: Vec<Task>,
    /// Files read to completion.
    pub files_processed: u32,
    /// Files skipped or unreadable, summed across the failure kinds.
    pub files_failed: u32,
    /// The task cap was hit, so `tasks` is truncated.
    pub truncated: bool,
}

/// Result of building an agenda. Day scopes fill `days`; `Tasks` scope fills
/// `tasks` instead. UniFFI has no untagged union, so both fields are present
/// and the empty one is empty.
#[derive(Debug, Clone, uniffi::Record)]
pub struct AgendaResult {
    /// Per-day buckets, for `Day` / `Week` / `Month`.
    pub days: Vec<Day>,
    /// Flat list, for `Tasks`.
    pub tasks: Vec<Task>,
}

/// How to walk the directory. Every field has a working default, so a caller
/// that does not care can leave them unset.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Options {
    /// File glob, `*.md` when unset.
    #[uniffi(default = None)]
    pub glob: Option<String>,
    /// Comma-separated locales for weekday names, `ru,en` when unset.
    #[uniffi(default = None)]
    pub locale: Option<String>,
    /// Cap on the number of tasks; the extractor's default when unset.
    #[uniffi(default = None)]
    pub max_tasks: Option<u32>,
}

/// Walk `dir` and return every task found.
///
/// `dir` is an absolute path inside the application's own storage. This does
/// not spawn a process and does not touch the network.
#[uniffi::export]
pub fn scan(dir: String, options: Options) -> Result<ScanResult, ExtractError> {
    let glob = options.glob.unwrap_or_else(|| "*.md".to_string());
    let locale = options.locale.unwrap_or_else(|| "ru,en".to_string());
    let defaults = ScanOptions::default();
    let scan_options = ScanOptions {
        glob: &glob,
        locale: &locale,
        max_tasks: options
            .max_tasks
            .map_or(defaults.max_tasks, |max| max as usize),
        absolute_paths: false,
    };

    let outcome = scan_directory(Path::new(&dir), &scan_options, None)?;
    let stats = outcome.stats;

    Ok(ScanResult {
        tasks: outcome.tasks.into_iter().map(Task::from).collect(),
        files_processed: stats.files_processed as u32,
        files_failed: (stats.files_skipped_size
            + stats.files_failed_read
            + stats.files_failed_search
            + stats.walk_errors) as u32,
        truncated: stats.max_tasks_reached,
    })
}

/// Walk `dir` and return the agenda for `scope`.
///
/// `current_date` is what the agenda treats as today, as `YYYY-MM-DD`. The
/// caller passes it rather than letting the library read the clock, so the
/// same input renders the same agenda — the contract the CLI follows through
/// `--current-date`. Under [`Scope::Tasks`] it is ignored: that scope has no
/// date window at all.
///
/// Scanning and filtering are one call here because nothing keeps an index
/// between calls yet; splitting them would mean walking the directory twice.
#[uniffi::export]
pub fn scan_agenda(
    dir: String,
    scope: Scope,
    current_date: String,
    timezone: String,
    include_done: bool,
    options: Options,
) -> Result<AgendaResult, ExtractError> {
    let glob = options.glob.unwrap_or_else(|| "*.md".to_string());
    let locale = options.locale.unwrap_or_else(|| "ru,en".to_string());
    let defaults = ScanOptions::default();
    let scan_options = ScanOptions {
        glob: &glob,
        locale: &locale,
        max_tasks: options
            .max_tasks
            .map_or(defaults.max_tasks, |max| max as usize),
        absolute_paths: false,
    };

    let outcome = scan_directory(Path::new(&dir), &scan_options, None)?;
    // `Tasks` is the date-less scope, and the extractor rejects any date
    // argument under it rather than quietly ignoring one. `current_date` is
    // therefore dropped here instead of being forwarded — the caller passes
    // one argument set regardless of scope, and only the scopes that have a
    // window use it.
    let dates = match scope {
        Scope::Tasks => AgendaDates::default(),
        _ => AgendaDates {
            current_date: Some(&current_date),
            ..AgendaDates::default()
        },
    };

    let output = filter_agenda(
        outcome.tasks,
        scope.into(),
        dates,
        &timezone,
        include_done,
        false,
        true,
    )?;

    Ok(match output {
        AgendaOutput::Days(days) => AgendaResult {
            days: days.into_iter().map(Day::from).collect(),
            tasks: Vec::new(),
        },
        AgendaOutput::Tasks(tasks) => AgendaResult {
            days: Vec::new(),
            tasks: tasks.into_iter().map(Task::from).collect(),
        },
    })
}

impl From<markdown_org_extract::DayAgenda> for Day {
    fn from(day: markdown_org_extract::DayAgenda) -> Self {
        Self {
            date: day.date,
            overdue: convert_offsets(day.overdue),
            scheduled_timed: convert_offsets(day.scheduled_timed),
            scheduled_no_time: convert_offsets(day.scheduled_no_time),
            upcoming: convert_offsets(day.upcoming),
        }
    }
}

fn convert_offsets(tasks: Vec<markdown_org_extract::TaskWithOffset>) -> Vec<Task> {
    tasks
        .into_iter()
        .map(|entry| Task {
            days_offset: entry.days_offset,
            ..Task::from(entry.task)
        })
        .collect()
}
