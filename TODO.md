# TODO

Work that is understood but deliberately not done yet.

## Table of contents

- [SSH remotes](#ssh-remotes)

## SSH remotes

Only `https://` remotes (and a local path) are accepted today; `remoteUrlProblem`
in `app/src/main/kotlin/io/github/vitalyostanin/markdownorg/core/RemoteUrl.kt`
refuses `ssh://` and `git@host:path` before anything is stored, because saving a
remote empties the working copy and the failure would otherwise surface only
after that.

Two things are missing, not one:

| № | Missing                                                                | What it takes                                                             |
|---|------------------------------------------------------------------------|---------------------------------------------------------------------------|
| 1 | The transport. `git2` is built with `default-features = false` and the `https` feature only, so libgit2 carries no libssh2 and does not register the protocol. | Add the `ssh` and `ssh_key_from_memory` features, vendor libssh2, and check what it adds to the library — the vendored stack is already most of the 10 MB per ABI. |
| 2 | Somewhere to keep a key. The settings hold a URL, a branch and a token; there is no private key, no passphrase and no host key. | An import path in the settings screen, storage in the application's own directory, and a `certificate_check` callback — without one the connection accepts any server that answers. |

Tests would need an ssh server to clone from, which the instrumented suite does
not have today.

Worth doing when a repository that offers no token authentication has to be
used. GitLab and GitHub both take a personal access token over https, which is
what the core already sends.
