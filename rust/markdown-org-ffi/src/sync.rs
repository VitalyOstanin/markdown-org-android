//! Keeping the working copy in step with a remote.
//!
//! The application owns a checkout in its own private directory and pulls it
//! forward; it never merges. Anything the remote cannot be fast-forwarded
//! onto is reported rather than resolved — merging belongs with the editing
//! that does not exist yet.
//!
//! Edits made on the device go the other way, and the same rule holds there:
//! a push the remote refuses is reported rather than forced. Nothing here
//! rewrites history on either side.

use std::cell::RefCell;
use std::os::raw::c_int;
use std::path::Path;
use std::sync::Mutex;

use foreign_types::ForeignType;
use git2::build::{CheckoutBuilder, RepoBuilder};
use git2::{
    Cred, ErrorClass, ErrorCode, FetchOptions, IndexAddOption, PushOptions, RemoteCallbacks,
    Repository, Signature, StatusOptions,
};
use libgit2_sys as raw;
use openssl::x509::X509;

use crate::document::TEMPORARY_PREFIX;

/// What to sync, and with what credentials.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SyncRequest {
    /// Absolute path of the working copy inside the application's storage.
    pub dir: String,
    /// Remote URL. `https://` in production; `file://` and plain paths work
    /// too, which is what the tests use.
    pub url: String,
    /// Access token, sent as the HTTP password. Absent for a local or public
    /// read-only remote.
    #[uniffi(default = None)]
    pub token: Option<String>,
    /// Branch to track. The remote's default when unset.
    #[uniffi(default = None)]
    pub branch: Option<String>,
}

/// Result of a sync.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SyncOutcome {
    /// The directory held no repository and one was cloned.
    pub cloned: bool,
    /// Commits the checkout moved forward by; zero when already current.
    pub commits_applied: u32,
    /// State after the sync.
    pub head: RepoStatus,
}

/// Result of a push.
#[derive(Debug, Clone, uniffi::Record)]
pub struct PushOutcome {
    /// Commits handed to the remote; zero when it already had them all.
    pub commits_pushed: u32,
    /// State after the push.
    pub head: RepoStatus,
}

/// Where the checkout stands, readable without touching the network.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RepoStatus {
    /// URL of `origin`, empty when the remote is missing.
    pub url: String,
    /// Checked-out branch, or the commit id when HEAD is detached.
    pub branch: String,
    /// Full commit id of HEAD.
    pub head_id: String,
    /// First line of the HEAD commit message.
    pub head_summary: String,
    /// Commit time of HEAD, in seconds since the epoch.
    pub head_time: i64,
    /// The working copy has changes that are not committed.
    pub dirty: bool,
    /// Commits on the checked-out branch the remote has not been given.
    ///
    /// Counted against `origin/<branch>` as the last fetch left it, so it is
    /// as fresh as the checkout's knowledge of the remote and no fresher. A
    /// branch the remote does not have at all counts as wholly unpushed.
    pub unpushed: u32,
}

/// Why a sync did not happen.
///
/// The variants are the ones the interface reacts to differently: bad
/// credentials ask for a new token, an unreachable host suggests retrying,
/// and a diverged or dirty checkout needs the user to decide.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SyncError {
    /// The remote refused the credentials, or none were supplied.
    #[error("authentication failed: {detail}")]
    Auth {
        /// Human-readable detail.
        detail: String,
    },
    /// The remote could not be reached.
    #[error("network failure: {detail}")]
    Network {
        /// Human-readable detail.
        detail: String,
    },
    /// The checkout has commits the remote does not, so no fast-forward.
    ///
    /// The branch is a field rather than only a part of `detail`: the caller
    /// puts the name into a sentence of its own language, and a name spelled
    /// into English prose cannot be taken back out of it.
    #[error("{branch} and origin/{branch} have both moved; this does not merge")]
    Diverged {
        /// The branch that moved on both sides.
        branch: String,
    },
    /// Uncommitted changes would be overwritten by the update.
    ///
    /// How many is a number for the same reason: `1 file` and `2 files` are
    /// two forms in English and four in Russian.
    #[error("{changed} uncommitted change(s) in the working copy")]
    Dirty {
        /// How many files stand in the way.
        changed: u32,
    },
    /// The remote refused the branch this checkout tried to hand it.
    ///
    /// Apart from [`SyncError::Diverged`], which is this checkout declining to
    /// merge what it fetched: here the connection was made, the credentials
    /// were accepted, and the server itself said no — most often because it
    /// has commits this checkout has not fetched. The commits are still here
    /// and nothing was lost; what is needed is a fetch and another attempt.
    ///
    /// `branch` is a field for the reason it is one on `Diverged`: the caller
    /// puts the name into a sentence of its own language.
    #[error("{branch} was refused by the remote: {detail}")]
    Rejected {
        /// The branch the remote refused.
        branch: String,
        /// What the remote said, as it said it.
        detail: String,
    },
    /// The remote address is one this application will not talk to.
    ///
    /// Apart from the failures above because nothing was attempted: the
    /// address was refused before a connection was opened, and no credentials
    /// left the device.
    #[error("the address cannot be used: {detail}")]
    Address {
        /// Human-readable detail.
        detail: String,
    },
    /// Anything else: a broken repository, a path that cannot be read.
    #[error("{detail}")]
    Repository {
        /// Human-readable detail.
        detail: String,
    },
}

impl From<git2::Error> for SyncError {
    fn from(error: git2::Error) -> Self {
        let detail = error.message().to_string();
        match (error.class(), error.code()) {
            // Only the code says the credentials were the problem. The HTTP
            // class as a whole carries a mistyped repository path (404) and a
            // server that is having a bad day (5xx) too, and telling either of
            // those to replace the token sends the user after the wrong thing.
            (_, ErrorCode::Auth) => SyncError::Auth { detail },
            (ErrorClass::Net, _) | (ErrorClass::Ssl, _) | (ErrorClass::Http, _) => {
                SyncError::Network { detail }
            }
            _ => SyncError::Repository { detail },
        }
    }
}

/// Clone the repository if the directory holds none, then fast-forward it.
///
/// Safe to call on every refresh: the first call clones, the rest fetch.
#[uniffi::export]
pub fn sync_repository(request: SyncRequest) -> Result<SyncOutcome, SyncError> {
    ensure_supported(&request.url)?;
    use_timeouts();

    let path = Path::new(&request.dir);
    match open(path) {
        Ok(repository) => {
            let applied = fast_forward(&repository, &request)?;
            Ok(SyncOutcome {
                cloned: false,
                commits_applied: applied,
                head: read_status(&repository)?,
            })
        }
        Err(error) if error.code() == ErrorCode::NotFound => {
            let repository = clone(&request)?;
            Ok(SyncOutcome {
                cloned: true,
                commits_applied: 0,
                head: read_status(&repository)?,
            })
        }
        Err(error) => Err(error.into()),
    }
}

/// Hand the branch's local commits to the remote.
///
/// Safe to call whenever: a checkout the remote is level with pushes nothing
/// and costs a comparison of two references, so the caller does not have to
/// know whether an edit happened.
///
/// What it will not do is force the remote to take them. A push the server
/// refuses comes back as [`SyncError::Rejected`] with the commits still here;
/// the answer to it is a fetch, and after that a fast-forward this application
/// can already do. Rewriting either history is not among the things it offers.
#[uniffi::export]
pub fn push_changes(request: SyncRequest) -> Result<PushOutcome, SyncError> {
    ensure_supported(&request.url)?;
    use_timeouts();

    let repository = open(Path::new(&request.dir))?;
    let branch = match request.branch.as_deref() {
        Some(branch) => branch.to_string(),
        None => head_branch(&repository)?,
    };

    let unpushed = unpushed_on(&repository, &branch)?;
    if unpushed == 0 {
        return Ok(PushOutcome {
            commits_pushed: 0,
            head: read_status(&repository)?,
        });
    }

    // Collected rather than raised from inside the callback: a server that
    // takes the connection and then declines the branch — a hook, a protected
    // branch — reports through this callback while `push` still returns
    // success, so a push that changed nothing on the remote would otherwise
    // read as one that worked.
    let refused: RefCell<Option<String>> = RefCell::new(None);
    let attempt = {
        let mut remote = repository.find_remote("origin")?;
        let mut options = push_options(&request, &refused);
        // Written out on both sides, and no leading `+`: the local branch is
        // offered to the branch of the same name, and only as a fast-forward
        // of it.
        let refspec = format!("refs/heads/{branch}:refs/heads/{branch}");
        remote.push(&[&refspec], Some(&mut options))
    };

    // The other half of the same refusal, and the one that happens against a
    // remote that has moved on: libgit2 compares what the server advertises
    // against what is here and stops before sending anything, which arrives as
    // an error rather than through the callback above.
    if let Err(error) = attempt {
        return Err(match error.code() {
            ErrorCode::NotFastForward => SyncError::Rejected {
                branch,
                detail: error.message().to_string(),
            },
            _ => error.into(),
        });
    }

    if let Some(detail) = refused.into_inner() {
        return Err(SyncError::Rejected { branch, detail });
    }

    Ok(PushOutcome {
        commits_pushed: unpushed,
        head: read_status(&repository)?,
    })
}

/// Commits on `branch` that `origin/<branch>` does not hold.
///
/// The remote-tracking reference is the last fetch's answer, not the server's
/// current one. That is the right basis anyway: it is what a push would be a
/// fast-forward of, and asking the server would mean a network round trip
/// before every screen that shows the count.
fn unpushed_on(repository: &Repository, branch: &str) -> Result<u32, SyncError> {
    let Some(local) = reference_target(repository, &format!("refs/heads/{branch}"))? else {
        return Ok(0);
    };

    match reference_target(repository, &format!("refs/remotes/origin/{branch}"))? {
        Some(upstream) => {
            let (ahead, _behind) = repository.graph_ahead_behind(local, upstream)?;
            Ok(u32::try_from(ahead).unwrap_or(u32::MAX))
        }
        // The remote has no such branch: everything on this one is news to it.
        None => {
            let mut walk = repository.revwalk()?;
            walk.push(local)?;
            Ok(u32::try_from(walk.count()).unwrap_or(u32::MAX))
        }
    }
}

/// What `name` points at, or `None` when there is no such reference.
///
/// A symbolic reference resolving to nothing answers `None` as well: there is
/// no commit to count from either way.
fn reference_target(repository: &Repository, name: &str) -> Result<Option<git2::Oid>, SyncError> {
    match repository.find_reference(name) {
        Ok(reference) => Ok(reference.target()),
        Err(error) if error.code() == ErrorCode::NotFound => Ok(None),
        Err(error) => Err(error.into()),
    }
}

/// Refuse an address this application will not fetch over.
///
/// Checked here rather than left to the interface, because [`SyncRequest`] is
/// the FFI surface: whoever calls the core gets the same guarantee the screen
/// does. `https` is the one network scheme git2 is vendored with, and the
/// reason to name an allowlist rather than ban `http` alone is the token — it
/// travels as the HTTP password, and Android's ban on cleartext traffic does
/// not reach libgit2 over a vendored OpenSSL. `git://` and `ssh://` would
/// leave in the clear or not authenticate the server at all.
///
/// A `file://` URL and an absolute path stay usable: that is a repository
/// copied onto the device, and every test here works that way.
fn ensure_supported(url: &str) -> Result<(), SyncError> {
    let refused = |what: &str| {
        Err(SyncError::Address {
            detail: format!("{what}; use https:// or a path on the device"),
        })
    };

    match () {
        () if url.trim().is_empty() => refused("no address given"),
        () if url.starts_with('/') || url.starts_with(FILE_SCHEME) => Ok(()),
        () if url.starts_with(HTTPS_SCHEME) => Ok(()),
        () => match url.split_once("://") {
            Some((scheme, _)) => refused(&format!("{scheme}:// is not encrypted")),
            // `git@host:path` — ssh under another spelling, and git2 is built
            // without ssh support, so it would fail later regardless.
            None => refused("the address names no scheme"),
        },
    }
}

const HTTPS_SCHEME: &str = "https://";
const FILE_SCHEME: &str = "file://";

/// Who a commit is attributed to.
///
/// The application has no git configuration to read: it is not a git client,
/// and the device carries no `~/.gitconfig`. The caller supplies the identity
/// from its own settings.
#[derive(Debug, Clone, uniffi::Record)]
pub struct CommitAuthor {
    /// Name recorded as both author and committer.
    pub name: String,
    /// Email recorded as both author and committer.
    pub email: String,
}

/// Commit everything the working copy holds, or report that there was nothing
/// to commit.
///
/// Called right after an edit rather than on a timer or a button. An edited
/// but uncommitted file makes the checkout dirty, and a dirty checkout is
/// refused by [`sync_repository`] — so postponing the commit would leave the
/// application unable to sync until it happened.
///
/// Returns the new commit's id, or `None` when the tree already matched HEAD.
#[uniffi::export]
pub fn commit_changes(
    dir: String,
    message: String,
    author: CommitAuthor,
) -> Result<Option<String>, SyncError> {
    let repository = open(Path::new(&dir))?;

    let mut index = repository.index()?;
    // A file an interrupted write left beside a note is not part of the
    // notes: committing it would push half a file to the remote under a name
    // of its own. Returning a non-zero value from the callback is how libgit2
    // is told to skip a path.
    let mut skip_temporaries = |path: &Path, _spec: &[u8]| -> i32 {
        let temporary = path
            .file_name()
            .and_then(|name| name.to_str())
            .is_some_and(|name| name.starts_with(TEMPORARY_PREFIX));
        i32::from(temporary)
    };
    // `add_all` picks up new and modified files; `update_all` is what notices
    // a tracked file that is gone. Both are needed to leave nothing behind
    // that would still show as a change.
    //
    // Over the whole working copy rather than the file just edited, and that
    // is the point: what makes the next sync refuse is any uncommitted change,
    // not only the one this edit made. A note captured elsewhere in the
    // directory would sit there dirtying the checkout until something else
    // happened to commit it.
    index.add_all(
        ["*"].iter(),
        IndexAddOption::DEFAULT,
        Some(&mut skip_temporaries),
    )?;
    index.update_all(["*"].iter(), None)?;
    index.write()?;

    let tree_id = index.write_tree()?;
    let parent = head_commit(&repository)?;

    if parent
        .as_ref()
        .is_some_and(|commit| commit.tree_id() == tree_id)
    {
        return Ok(None);
    }

    let tree = repository.find_tree(tree_id)?;
    let who = Signature::now(&author.name, &author.email)?;
    let parents: Vec<&git2::Commit> = parent.iter().collect();
    let id = repository.commit(Some("HEAD"), &who, &who, &message, &tree, &parents)?;

    Ok(Some(id.to_string()))
}

/// The commit HEAD is on, or `None` when the branch has no commits yet.
///
/// A repository cloned from a remote nobody has written to is the ordinary way
/// to start: HEAD names a branch that does not exist, and libgit2 reports that
/// as `UnbornBranch` rather than as an absent reference. Every other failure —
/// an id that is not in the object store, a damaged pack — is passed on.
/// Treating those as "no parent" would build a root commit over an existing
/// branch, and the history would be gone without a word.
fn head_commit(repository: &Repository) -> Result<Option<git2::Commit<'_>>, SyncError> {
    match repository.head() {
        Ok(head) => Ok(Some(head.peel_to_commit()?)),
        Err(error) if is_unborn(&error) => Ok(None),
        Err(error) => Err(error.into()),
    }
}

/// Whether the error means "this branch has no commits yet".
///
/// `NotFound` covers a repository so fresh that HEAD itself is missing.
fn is_unborn(error: &git2::Error) -> bool {
    matches!(error.code(), ErrorCode::UnbornBranch | ErrorCode::NotFound)
}

/// The branch HEAD names, with or without commits on it.
///
/// `Reference::shorthand` needs a resolved reference, which an unborn branch
/// has none of; its name is read off the symbolic HEAD instead.
fn head_branch(repository: &Repository) -> Result<String, SyncError> {
    match repository.head() {
        Ok(head) => Ok(head
            .shorthand()
            .map(str::to_string)
            .unwrap_or_else(|_| head.target().map(|id| id.to_string()).unwrap_or_default())),
        Err(error) if is_unborn(&error) => {
            let head = repository.find_reference("HEAD")?;
            Ok(head
                .symbolic_target()?
                .and_then(|target| target.strip_prefix("refs/heads/"))
                .unwrap_or_default()
                .to_string())
        }
        Err(error) => Err(error.into()),
    }
}

/// Whether the directory is a checkout at all.
///
/// Separate from [`repository_status`] because the answer is needed before
/// every commit, and the status is a walk of the whole working copy — it
/// collects the untracked files to decide `dirty`, which is a great deal of
/// work to establish that there is a `.git` here.
#[uniffi::export]
pub fn holds_repository(dir: String) -> bool {
    open(Path::new(&dir)).is_ok()
}

/// Read the checkout's state without contacting the remote.
///
/// Returns `None` when the directory holds no repository, which is how the
/// interface tells "not set up yet" from "set up and behind".
#[uniffi::export]
pub fn repository_status(dir: String) -> Result<Option<RepoStatus>, SyncError> {
    match open(Path::new(&dir)) {
        Ok(repository) => read_status(&repository).map(Some),
        Err(error) if error.code() == ErrorCode::NotFound => Ok(None),
        Err(error) => Err(error.into()),
    }
}

/// How long to wait for the connection to be made.
///
/// A phone changes networks mid-request and answers from a captive portal, so
/// the default — libgit2 leaves this to the operating system, which can mean
/// minutes — is not a value anyone chose. Long enough for a slow mobile
/// connection to complete a TLS handshake.
const CONNECT_TIMEOUT_MS: c_int = 15_000;

/// How long a request may take once the connection stands.
///
/// Cloning a notes repository moves kilobytes; a request still running after
/// this has stalled rather than started.
const SERVER_TIMEOUT_MS: c_int = 60_000;

/// Bound how long a sync can sit on the network.
///
/// Applied once per process, under a lock: the two options write into globals
/// libgit2 documents as unsynchronized, and the caller is a phone that syncs
/// from whichever coroutine the user was in.
fn use_timeouts() {
    static APPLIED: Mutex<bool> = Mutex::new(false);

    let mut applied = APPLIED
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if *applied {
        return;
    }

    // Safe: serialised by the lock above, and both calls only write a global
    // libgit2 reads when it opens a connection.
    unsafe {
        // The signature returns a Result the implementation never fills in
        // with an error; nothing here can act on one either.
        let _ = git2::opts::set_server_connect_timeout_in_milliseconds(CONNECT_TIMEOUT_MS);
        let _ = git2::opts::set_server_timeout_in_milliseconds(SERVER_TIMEOUT_MS);
    }

    *applied = true;
}

/// Load the certificate authorities into the TLS stack.
///
/// The bundle arrives as PEM text rather than as a path on purpose. OpenSSL
/// vendored for Android is configured with `no-stdio` (openssl-src does this
/// for every `*-android` target), so it has no `BIO_new_file` and
/// `GIT_OPT_SET_SSL_CERT_LOCATIONS` fails on any path with "BIO lib" —
/// however readable the file is from Kotlin or from Rust. Parsing the bundle
/// here and adding the certificates one by one is what libgit2 documents for
/// exactly this case.
///
/// Called on its own rather than as part of a sync: the store is global to
/// libgit2 and lives as long as the process, so handing the bundle over once
/// spares every later sync the copy of ~180 kB across the FFI boundary.
/// Calling it again is cheap and does nothing.
///
/// A failure is *not* remembered: a sync that went ahead with an empty store
/// would fail on every connection, so the next attempt has to try again
/// rather than inherit the miss.
#[uniffi::export]
pub fn load_ca_bundle(pem: String) -> Result<(), SyncError> {
    use_ca_bundle(&pem)
}

fn use_ca_bundle(pem: &str) -> Result<(), SyncError> {
    static LOADED: Mutex<bool> = Mutex::new(false);

    // Held across the whole load, not just the flag: `git_libgit2_opts` writes
    // into a store global to the library, and two threads filling it at once
    // is not something libgit2 promises to survive. Taking the lock first also
    // gives the second caller the finished store rather than a flag set before
    // the certificates behind it were visible.
    let mut loaded = LOADED.lock().unwrap_or_else(|poisoned| {
        // A panic while the store was half-filled leaves the flag as it was;
        // the next caller loads the bundle again, which is what a failure does
        // anyway.
        poisoned.into_inner()
    });
    if *loaded {
        return Ok(());
    }

    // The option below goes through libgit2-sys directly, which — unlike the
    // git2 wrappers — does not initialise the library. Any git2 call does;
    // this one re-states the default and changes nothing.
    git2::opts::strict_hash_verification(true);

    let certificates =
        X509::stack_from_pem(pem.as_bytes()).map_err(|error| SyncError::Repository {
            detail: format!("the certificate bundle could not be read: {error}"),
        })?;

    if certificates.is_empty() {
        return Err(SyncError::Repository {
            detail: "the certificate bundle holds no certificates".to_string(),
        });
    }

    for certificate in &certificates {
        // Safe: the pointer is valid for the call, and libgit2 takes its own
        // reference on the certificate rather than keeping this one.
        let code = unsafe {
            raw::git_libgit2_opts(
                raw::GIT_OPT_ADD_SSL_X509_CERT as c_int,
                certificate.as_ptr(),
            )
        };
        if code < 0 {
            return Err(git2::Error::last_error(code).into());
        }
    }

    *loaded = true;
    Ok(())
}

/// Open the checkout at `path`, whoever the platform says owns the directory.
///
/// Every entry point that touches a repository comes through here, so that the
/// setting below is in place before libgit2 has a chance to refuse.
fn open(path: &Path) -> Result<Repository, git2::Error> {
    open_directories_owned_by_the_platform();
    Repository::open(path)
}

/// Stop libgit2 from refusing a checkout because the directory is owned by
/// somebody else.
///
/// libgit2 compares the owner of the working directory and of `.git` against
/// the current user, and reports `repository path '…' is not owned by current
/// user` when they differ. On the shared storage of Android — anything under
/// `/storage/emulated/0`, which is where a directory chosen in the settings
/// lives (ADR-0013) — they always differ: those files are handed out through a
/// layer that reports an owner of its own rather than the uid of the
/// application reading them. The check therefore refuses every directory
/// outside the application's own storage, whoever put it there, and clone,
/// fast-forward and commit go with it. The notes are still read and written;
/// it is the repository around them that cannot be opened.
///
/// What the check defends against is a repository left by another user of a
/// shared machine, whose `.git/config` git would read and run a command out of
/// — `core.pager`, `core.sshCommand`. Neither half of that holds here. libgit2
/// runs nothing from a configuration file: it creates `hooks/` but never
/// executes anything in it, has no external clean/smudge filters, and the one
/// place in the library that starts a process is the ssh transport, which this
/// build does not compile in (`git2` is taken with `https` alone). And an
/// Android application has the device to itself as far as uids go: it reaches
/// the shared storage only through a permission granted by hand, and the
/// directory is the one the user pointed at.
///
/// What is left is a `.git` somebody else put in the notes directory, naming
/// an `origin` of their own. Nothing of the user's leaves for it — the token is
/// only ever offered to the address in the settings, see [`credentials_for`] —
/// and writing into that directory at all takes the same all-files access that
/// would let the notes themselves be rewritten.
///
/// Applied once per process, under a lock, for the reason [`use_timeouts`]
/// states: the setting is a global libgit2 documents as unsynchronized. It
/// stays off for the rest of the process, the directory inside the
/// application's own storage included — where the owner has always matched
/// anyway, and where nothing else in the process uses libgit2.
fn open_directories_owned_by_the_platform() {
    static APPLIED: Mutex<bool> = Mutex::new(false);

    let mut applied = APPLIED
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if *applied {
        return;
    }

    // Safe: serialised by the lock above, and the call only writes a global
    // libgit2 reads when it opens a repository.
    unsafe {
        // The signature returns a Result the implementation never fills in
        // with an error, the same as the timeouts above.
        let _ = git2::opts::set_verify_owner_validation(false);
    }

    *applied = true;
}

fn clone(request: &SyncRequest) -> Result<Repository, SyncError> {
    // Cloning ends in an open repository too, and the directory it lands in is
    // the one the check would refuse.
    open_directories_owned_by_the_platform();

    let mut builder = RepoBuilder::new();
    builder.fetch_options(fetch_options(request));
    if let Some(branch) = request.branch.as_deref() {
        builder.branch(branch);
    }

    Ok(builder.clone(&request.url, Path::new(&request.dir))?)
}

/// Fetch and move the checkout forward, or explain why it cannot move.
fn fast_forward(repository: &Repository, request: &SyncRequest) -> Result<u32, SyncError> {
    let checked_out = head_branch(repository)?;
    let branch = match request.branch.as_deref() {
        Some(branch) => branch.to_string(),
        None => checked_out.clone(),
    };

    let mut remote = repository.find_remote("origin")?;
    remote.fetch(&[&branch], Some(&mut fetch_options(request)), None)?;

    let target = match repository.find_reference("FETCH_HEAD") {
        Ok(fetched) => repository.reference_to_annotated_commit(&fetched)?,
        // A remote whose branch has no commits leaves FETCH_HEAD empty, and
        // libgit2 reads an empty reference file back as a corrupted one.
        // Guarded by the checkout being unborn as well, so a genuinely damaged
        // FETCH_HEAD in a checkout that does have commits is still an error.
        Err(_) if head_commit(repository)?.is_none() => return Ok(0),
        Err(error) => return Err(error.into()),
    };

    // Asked for a branch other than the one on disk: the settings changed
    // under an existing checkout. This is not a fast-forward of anything —
    // the branches may share no history at all — so the analysis below would
    // call it a divergence and the local branch would not even exist to move.
    if checked_out != branch {
        ensure_clean(repository)?;
        move_onto(repository, &branch, target.id())?;
        return Ok(0);
    }

    let (analysis, _) = repository.merge_analysis(&[&target])?;

    if analysis.is_up_to_date() {
        return Ok(0);
    }

    // `is_unborn` is the branch this checkout is on having no commits yet —
    // the state a clone of an empty remote starts in. There is nothing to
    // fast-forward from, and the first commit that arrives simply becomes the
    // branch.
    if !analysis.is_fast_forward() && !analysis.is_unborn() {
        return Err(SyncError::Diverged { branch });
    }

    // Checked before touching the tree rather than letting the checkout fail
    // half-way: a forced checkout would silently discard the changes.
    ensure_clean(repository)?;

    let before = head_commit(repository)?.map(|commit| commit.id());
    move_onto(repository, &branch, target.id())?;

    Ok(match before {
        Some(before) => count_commits(repository, before, target.id())?,
        None => 0,
    })
}

/// Point `branch` at `target`, make HEAD follow it, and write the tree out.
///
/// Creates the branch when it is not there: the checkout may have been cloned
/// on a different one, and a branch the settings name has to exist locally
/// before HEAD can be moved onto it.
fn move_onto(repository: &Repository, branch: &str, target: git2::Oid) -> Result<(), SyncError> {
    let name = format!("refs/heads/{branch}");
    match repository.find_reference(&name) {
        Ok(mut reference) => {
            reference.set_target(target, "fast-forward")?;
        }
        Err(error) if error.code() == ErrorCode::NotFound => {
            repository.reference(&name, target, true, "track the branch from the settings")?;
        }
        Err(error) => return Err(error.into()),
    }

    repository.set_head(&name)?;
    // force(): the tree is known clean, and the default checkout refuses to
    // overwrite files that differ from the index.
    repository.checkout_head(Some(CheckoutBuilder::default().force()))?;
    Ok(())
}

/// Refuse to write over anything the user has not committed.
///
/// Untracked files count. The checkout below runs with `force()`, so an
/// untracked file a new commit also carries would be replaced by it — a note
/// captured on the device and not yet committed would be gone. The one
/// exception is the temporary an interrupted write leaves beside a note:
/// nothing else will ever clean it up, and treating it as work in progress
/// would block every sync from then on.
fn ensure_clean(repository: &Repository) -> Result<(), SyncError> {
    let mut options = StatusOptions::new();
    options.include_untracked(true).include_ignored(false);

    let statuses = repository.statuses(Some(&mut options))?;
    let changed = statuses
        .iter()
        .filter(|entry| {
            !entry
                .path()
                .ok()
                .and_then(|path| Path::new(path).file_name())
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.starts_with(TEMPORARY_PREFIX))
        })
        .count();

    if changed > 0 {
        return Err(SyncError::Dirty {
            changed: u32::try_from(changed).unwrap_or(u32::MAX),
        });
    }

    Ok(())
}

/// How many commits lie between `from` (exclusive) and `to`.
fn count_commits(
    repository: &Repository,
    from: git2::Oid,
    to: git2::Oid,
) -> Result<u32, SyncError> {
    let mut walk = repository.revwalk()?;
    walk.push(to)?;
    walk.hide(from)?;
    Ok(walk.count() as u32)
}

fn fetch_options(request: &SyncRequest) -> FetchOptions<'_> {
    let mut options = FetchOptions::new();
    options.remote_callbacks(remote_callbacks(request));
    // Redirects to another host are refused outright rather than left to the
    // credential callback to notice: `Initial` still allows the redirect from
    // `/repo` to `/repo.git` that servers use, which is the only one a notes
    // checkout needs.
    options.follow_redirects(git2::RemoteRedirect::Initial);
    // Tags come with releases and are of no use to a notes checkout.
    options.download_tags(git2::AutotagOption::None);
    options
}

/// The options a push runs with, reporting into `refused` what the remote
/// declined.
///
/// The redirect rule is the fetch's, and for a stronger reason: a push offers
/// the token and the commits to whoever answers.
fn push_options<'a>(
    request: &SyncRequest,
    refused: &'a RefCell<Option<String>>,
) -> PushOptions<'a> {
    let mut callbacks = remote_callbacks(request);
    callbacks.push_update_reference(|_reference, status| {
        if let Some(message) = status {
            // First one wins: one refspec goes up per push, and a second
            // message would be about the same refusal.
            refused
                .borrow_mut()
                .get_or_insert_with(|| message.to_string());
        }
        Ok(())
    });

    let mut options = PushOptions::new();
    options.remote_callbacks(callbacks);
    options.follow_redirects(git2::RemoteRedirect::Initial);
    options
}

/// The callbacks every request to the remote runs with: the credentials, and
/// nothing else.
///
/// The closure owns its copy of the token and the address, so the callbacks
/// outlive the request and fit whatever lifetime the caller's options need.
fn remote_callbacks<'a>(request: &SyncRequest) -> RemoteCallbacks<'a> {
    let mut callbacks = RemoteCallbacks::new();
    let token = request.token.clone();
    let configured = request.url.clone();
    callbacks.credentials(move |asked, username, allowed| {
        credentials_for(&configured, asked, token.as_deref(), username, allowed)
    });

    callbacks
}

/// What to answer libgit2 when it asks for credentials for `asked`.
///
/// The token is only ever offered to the address the caller configured. libgit2
/// asks per request, and a request can be for somewhere else than the settings
/// name — a redirect, or a checkout whose `origin` was changed on disk. Git
/// itself does not carry credentials across a redirect to another host, and
/// this is the same rule stated here.
fn credentials_for(
    configured: &str,
    asked: &str,
    token: Option<&str>,
    username: Option<&str>,
    allowed: git2::CredentialType,
) -> Result<Cred, git2::Error> {
    if let Some(token) = token {
        if allowed.contains(git2::CredentialType::USER_PASS_PLAINTEXT) {
            if !same_endpoint(configured, asked) {
                return Err(git2::Error::from_str(&format!(
                    "{asked} is not the configured remote; the token was not sent"
                )));
            }
            // The username is ignored by GitHub when the password is a token,
            // but it cannot be empty; gitea and GitLab accept the same shape.
            return Cred::userpass_plaintext(username.unwrap_or("x-access-token"), token);
        }
    }
    if allowed.contains(git2::CredentialType::DEFAULT) {
        return Cred::default();
    }
    Err(git2::Error::from_str("no usable credentials"))
}

/// Whether two URLs name the same server.
///
/// Scheme, host and port, with the userinfo dropped: `https://x:t@host/repo`
/// and `https://host/repo.git` are the same endpoint asked for two ways. A
/// port written out is not the same as one left implied — assuming 443 is a
/// guess, and the safe guess here is "not the same".
fn same_endpoint(configured: &str, asked: &str) -> bool {
    match endpoint(configured) {
        Some(configured) => endpoint(asked) == Some(configured),
        // An address with no scheme is a path on the device; nothing there
        // asks for a password.
        None => false,
    }
}

fn endpoint(url: &str) -> Option<(String, String)> {
    let (scheme, rest) = url.split_once("://")?;
    let authority = rest.split('/').next().unwrap_or_default();
    let host = authority
        .rsplit_once('@')
        .map_or(authority, |(_userinfo, host)| host);
    if host.is_empty() {
        return None;
    }

    Some((scheme.to_ascii_lowercase(), host.to_ascii_lowercase()))
}

fn read_status(repository: &Repository) -> Result<RepoStatus, SyncError> {
    // A checkout of a branch with no commits has a state worth reporting: the
    // remote is configured, the branch is named, and the working copy may
    // already hold a note. The commit fields stand empty rather than the whole
    // read failing.
    let commit = head_commit(repository)?;
    let branch = head_branch(repository)?;
    let unpushed = unpushed_on(repository, &branch)?;

    let mut options = StatusOptions::new();
    options.include_untracked(true).include_ignored(false);

    Ok(RepoStatus {
        // Both of these are byte strings in git and only usually UTF-8: a
        // remote URL may hold a percent-decoded path, and a commit made
        // elsewhere may carry an `encoding` header. Rendered lossily rather
        // than dropped — an empty URL reads as "no remote configured" and an
        // empty summary as "a commit with no message", neither of which is
        // what happened.
        url: repository
            .find_remote("origin")
            .ok()
            .map(|remote| String::from_utf8_lossy(remote.url_bytes()).into_owned())
            .unwrap_or_default(),
        // A detached HEAD has no shorthand worth showing, so the commit id
        // stands in for the branch name.
        branch,
        head_id: commit
            .as_ref()
            .map(|commit| commit.id().to_string())
            .unwrap_or_default(),
        head_summary: commit
            .as_ref()
            .and_then(|commit| commit.summary_bytes())
            .map(|summary| String::from_utf8_lossy(summary).into_owned())
            .unwrap_or_default(),
        head_time: commit
            .as_ref()
            .map(|commit| commit.time().seconds())
            .unwrap_or_default(),
        dirty: !repository.statuses(Some(&mut options))?.is_empty(),
        unpushed,
    })
}

/// Unit tests for the two guards that have no reachable surface of their own:
/// the credential callback is handed to libgit2, and the address check runs
/// before anything observable happens. The behaviour they defend against —
/// a redirect to another host, a scheme that carries the token in the clear —
/// cannot be produced from a test that stays off the network.
#[cfg(test)]
mod tests {
    use super::*;

    const CONFIGURED: &str = "https://git.example.org/notes.git";

    fn credentials(asked: &str) -> Result<Cred, git2::Error> {
        credentials_for(
            CONFIGURED,
            asked,
            Some("secret"),
            Some("x-access-token"),
            git2::CredentialType::USER_PASS_PLAINTEXT,
        )
    }

    #[test]
    fn the_token_goes_to_the_configured_host() {
        assert!(credentials(CONFIGURED).is_ok());
        // The same server, asked for by the shape libgit2 uses internally.
        assert!(credentials("https://git.example.org/notes.git/info/refs").is_ok());
    }

    #[test]
    fn the_token_is_withheld_from_any_other_host() {
        for asked in [
            "https://elsewhere.example.org/notes.git",
            "https://git.example.org.attacker.test/notes.git",
            "http://git.example.org/notes.git",
            "https://git.example.org:8443/notes.git",
        ] {
            // `Cred` carries no Debug, so the outcome is unwrapped by hand
            // rather than through `expect_err`.
            let Err(error) = credentials(asked) else {
                panic!("{asked}: the token was offered");
            };
            assert!(
                error.message().contains("the token was not sent"),
                "{asked}: {error}",
            );
        }
    }

    #[test]
    fn a_url_carrying_credentials_still_names_its_host() {
        assert!(same_endpoint(
            CONFIGURED,
            "https://x:secret@git.example.org/notes.git"
        ));
        assert!(same_endpoint(
            "https://x:secret@git.example.org/notes.git",
            CONFIGURED,
        ));
        assert!(same_endpoint(
            CONFIGURED,
            "https://GIT.EXAMPLE.ORG/notes.git"
        ));
    }

    #[test]
    fn a_path_on_the_device_is_never_treated_as_a_host() {
        assert!(!same_endpoint("/data/notes", "/data/notes"));
        assert!(!same_endpoint("file:///data/notes", "file:///data/notes"));
    }

    #[test]
    fn the_addresses_the_application_talks_to() {
        for url in [
            "https://git.example.org/notes.git",
            "file:///data/notes",
            "/data/user/0/app/files/notes",
        ] {
            assert!(ensure_supported(url).is_ok(), "{url}");
        }

        for url in [
            "http://git.example.org/notes.git",
            "git://git.example.org/notes.git",
            "ssh://git@git.example.org/notes.git",
            "git@git.example.org:notes.git",
            "",
        ] {
            assert!(
                matches!(ensure_supported(url), Err(SyncError::Address { .. })),
                "{url}",
            );
        }
    }
}
