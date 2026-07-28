//! Keeping the working copy in step with a remote.
//!
//! The application owns a checkout in its own private directory and pulls it
//! forward; it never merges. Anything the remote cannot be fast-forwarded
//! onto is reported rather than resolved — merging belongs with the editing
//! that does not exist yet.

use std::os::raw::c_int;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};

use git2::build::{CheckoutBuilder, RepoBuilder};
use git2::{
    Cred, ErrorClass, ErrorCode, FetchOptions, RemoteCallbacks, Repository, StatusOptions,
};
use foreign_types::ForeignType;
use libgit2_sys as raw;
use openssl::x509::X509;

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
    /// Certificate authorities as PEM text. Android ships no `/etc/ssl/certs`,
    /// which is where the vendored OpenSSL looks by default, so the caller
    /// passes the bundle in. Contents rather than a path — see
    /// [`use_ca_bundle`].
    #[uniffi(default = None)]
    pub ca_bundle_pem: Option<String>,
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
    #[error("the checkout has diverged from the remote: {detail}")]
    Diverged {
        /// Human-readable detail.
        detail: String,
    },
    /// Uncommitted changes would be overwritten by the update.
    #[error("the working copy has uncommitted changes: {detail}")]
    Dirty {
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
            (ErrorClass::Http, _) | (_, ErrorCode::Auth) => SyncError::Auth { detail },
            (ErrorClass::Net, _) | (ErrorClass::Ssl, _) => SyncError::Network { detail },
            _ => SyncError::Repository { detail },
        }
    }
}

/// Clone the repository if the directory holds none, then fast-forward it.
///
/// Safe to call on every refresh: the first call clones, the rest fetch.
#[uniffi::export]
pub fn sync_repository(request: SyncRequest) -> Result<SyncOutcome, SyncError> {
    if let Some(bundle) = request.ca_bundle_pem.as_deref() {
        use_ca_bundle(bundle)?;
    }

    let path = Path::new(&request.dir);
    match Repository::open(path) {
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

/// Read the checkout's state without contacting the remote.
///
/// Returns `None` when the directory holds no repository, which is how the
/// interface tells "not set up yet" from "set up and behind".
#[uniffi::export]
pub fn repository_status(dir: String) -> Result<Option<RepoStatus>, SyncError> {
    match Repository::open(Path::new(&dir)) {
        Ok(repository) => read_status(&repository).map(Some),
        Err(error) if error.code() == ErrorCode::NotFound => Ok(None),
        Err(error) => Err(error.into()),
    }
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
/// Done once per process on success — the store is global to libgit2 and
/// repeating it would be pointless work. A failure is *not* remembered: a
/// sync that went ahead with an empty store would fail on every connection,
/// so the next attempt has to try again rather than inherit the miss.
fn use_ca_bundle(pem: &str) -> Result<(), SyncError> {
    static LOADED: AtomicBool = AtomicBool::new(false);

    if LOADED.load(Ordering::Relaxed) {
        return Ok(());
    }

    // The option below goes through libgit2-sys directly, which — unlike the
    // git2 wrappers — does not initialise the library. Any git2 call does;
    // this one re-states the default and changes nothing.
    git2::opts::strict_hash_verification(true);

    let certificates = X509::stack_from_pem(pem.as_bytes()).map_err(|error| {
        SyncError::Repository {
            detail: format!("the certificate bundle could not be read: {error}"),
        }
    })?;

    if certificates.is_empty() {
        return Err(SyncError::Repository {
            detail: "the certificate bundle holds no certificates".to_string(),
        });
    }

    for certificate in &certificates {
        // Safe: the pointer is valid for the call, and libgit2 takes its own
        // reference on the certificate rather than keeping this one.
        let code = unsafe { raw::git_libgit2_opts(ADD_SSL_X509_CERT, certificate.as_ptr()) };
        if code < 0 {
            return Err(git2::Error::last_error(code).into());
        }
    }

    LOADED.store(true, Ordering::Relaxed);
    Ok(())
}

/// `GIT_OPT_ADD_SSL_X509_CERT`.
///
/// libgit2-sys 0.18.7 stops its copy of the enum one entry short of this
/// option, while the two lists agree entry for entry up to there — so the
/// value is derived from the last one both of them have rather than written
/// out as a number.
const ADD_SSL_X509_CERT: c_int = raw::GIT_OPT_GET_USER_AGENT_PRODUCT as c_int + 1;

fn clone(request: &SyncRequest) -> Result<Repository, SyncError> {
    let mut builder = RepoBuilder::new();
    builder.fetch_options(fetch_options(request));
    if let Some(branch) = request.branch.as_deref() {
        builder.branch(branch);
    }

    Ok(builder.clone(&request.url, Path::new(&request.dir))?)
}

/// Fetch and move the checkout forward, or explain why it cannot move.
fn fast_forward(repository: &Repository, request: &SyncRequest) -> Result<u32, SyncError> {
    let branch = match request.branch.as_deref() {
        Some(branch) => branch.to_string(),
        None => current_branch(repository)?,
    };

    let mut remote = repository.find_remote("origin")?;
    remote.fetch(&[&branch], Some(&mut fetch_options(request)), None)?;

    let fetched = repository.find_reference("FETCH_HEAD")?;
    let target = repository.reference_to_annotated_commit(&fetched)?;
    let (analysis, _) = repository.merge_analysis(&[&target])?;

    if analysis.is_up_to_date() {
        return Ok(0);
    }

    if !analysis.is_fast_forward() {
        return Err(SyncError::Diverged {
            detail: format!(
                "{branch} and origin/{branch} have both moved; the application \
                 does not merge"
            ),
        });
    }

    // Checked before touching the tree rather than letting the checkout fail
    // half-way: a forced checkout would silently discard the changes.
    let mut options = StatusOptions::new();
    options.include_untracked(false).include_ignored(false);
    let changed = repository.statuses(Some(&mut options))?.len();
    if changed > 0 {
        return Err(SyncError::Dirty {
            detail: format!("{changed} file(s) changed since the last commit"),
        });
    }

    let before = repository.head()?.target();
    let mut reference = repository.find_reference(&format!("refs/heads/{branch}"))?;
    reference.set_target(target.id(), "fast-forward")?;
    repository.set_head(&format!("refs/heads/{branch}"))?;
    // force(): the tree is known clean, and the default checkout refuses to
    // overwrite files that differ from the index.
    repository.checkout_head(Some(CheckoutBuilder::default().force()))?;

    Ok(match before {
        Some(before) => count_commits(repository, before, target.id())?,
        None => 0,
    })
}

/// How many commits lie between `from` (exclusive) and `to`.
fn count_commits(repository: &Repository, from: git2::Oid, to: git2::Oid) -> Result<u32, SyncError> {
    let mut walk = repository.revwalk()?;
    walk.push(to)?;
    walk.hide(from)?;
    Ok(walk.count() as u32)
}

fn current_branch(repository: &Repository) -> Result<String, SyncError> {
    let head = repository.head()?;
    if !head.is_branch() {
        return Err(SyncError::Repository {
            detail: "HEAD does not point at a branch".to_string(),
        });
    }
    // Result, not Option: git2 hands back an error for a name that is not
    // UTF-8, which is a repository this application cannot work with anyway.
    Ok(head.shorthand()?.to_string())
}

fn fetch_options(request: &SyncRequest) -> FetchOptions<'_> {
    let mut callbacks = RemoteCallbacks::new();
    let token = request.token.clone();
    callbacks.credentials(move |_url, username, allowed| {
        if allowed.contains(git2::CredentialType::USER_PASS_PLAINTEXT) {
            if let Some(token) = token.as_deref() {
                // The username is ignored by GitHub when the password is a
                // token, but it cannot be empty; gitea and GitLab accept the
                // same shape.
                return Cred::userpass_plaintext(username.unwrap_or("x-access-token"), token);
            }
        }
        if allowed.contains(git2::CredentialType::DEFAULT) {
            return Cred::default();
        }
        Err(git2::Error::from_str("no usable credentials"))
    });

    let mut options = FetchOptions::new();
    options.remote_callbacks(callbacks);
    // Tags come with releases and are of no use to a notes checkout.
    options.download_tags(git2::AutotagOption::None);
    options
}

fn read_status(repository: &Repository) -> Result<RepoStatus, SyncError> {
    let head = repository.head()?;
    let commit = head.peel_to_commit()?;

    let mut options = StatusOptions::new();
    options.include_untracked(true).include_ignored(false);

    Ok(RepoStatus {
        url: repository
            .find_remote("origin")
            .ok()
            .and_then(|remote| remote.url().ok().map(str::to_string))
            .unwrap_or_default(),
        // A detached HEAD has no shorthand worth showing, so the commit id
        // stands in for the branch name.
        branch: head
            .shorthand()
            .map(str::to_string)
            .unwrap_or_else(|_| commit.id().to_string()),
        head_id: commit.id().to_string(),
        head_summary: commit.summary().ok().flatten().unwrap_or_default().to_string(),
        head_time: commit.time().seconds(),
        dirty: !repository.statuses(Some(&mut options))?.is_empty(),
    })
}
