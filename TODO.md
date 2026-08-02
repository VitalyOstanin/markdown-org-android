# TODO

Work that is understood but deliberately not done yet.

## Table of contents

- [One set of notes on more than one remote](#one-set-of-notes-on-more-than-one-remote)
- [Weekday names beyond Russian and English](#weekday-names-beyond-russian-and-english)
- [Notes carrying a byte-order mark](#notes-carrying-a-byte-order-mark)
- [Unicode normalisation when a heading can be typed](#unicode-normalisation-when-a-heading-can-be-typed)

## One set of notes on more than one remote

Not the same as the collections the application already has. Those are several
sets of notes, each in its own repository and its own directory; this one is a
single set of notes kept on several servers at once — the same commits on
GitHub and on GitLab, so that one host being unreachable, throttled or gone
does not take the notes with it.

Git already models this: one working copy can carry several remotes, and the
commits are the same objects wherever they are pushed. What the application
does not have is anything to drive it — the settings hold one URL, and the
core's sync fetches from and fast-forwards against a single remote.

| № | Part          | What changes                                                                                                                             |
|---|---------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | Settings      | A list of remotes for the one checkout, with one of them named as the one to pull from; a token per remote, since they are different hosts. |
| 2 | The core      | `sync_repository` takes the remotes rather than one URL: fetch and fast-forward from the primary, push to each of the others.               |
| 3 | Partial failure | A push that failed to one host while the others went through is not a failed sync — it is a state, and it has to be reported per remote and retried on the next run rather than silently dropped. |
| 4 | Divergence    | Two remotes can hold different histories once a push has failed to one of them for a while. Only fast-forward is on offer today, so the answer is to say which remote is behind rather than to merge. |
| 5 | The screens   | The banner names which remotes are current and which are behind, and the settings add and remove remotes.                                   |

Worth doing together with the section above, or after it: both replace the
single stored triple of URL, branch and token with a list, and writing that
migration twice is the part not worth repeating.

## Weekday names beyond Russian and English

Moving a date rewrites the weekday token to match, in the language the file
already uses. The two languages the ecosystem knows are Russian and English
(`SUPPORTED_LOCALES` in the extractor), and `weekday_like` in
`rust/markdown-org-ffi/src/planning.rs` refuses anything else with
`Unsupported` rather than replacing, say, Ukrainian `Нд` with Russian `Вс`.

For a file written in a third language that means its dates cannot be moved
from the phone at all. Adding one is a table in the extractor's `locale`
module plus the matching entry in `SUPPORTED_LOCALES`, so that reading and
writing agree; doing it here alone would let the application write names the
extractor cannot read back.

## Notes carrying a byte-order mark

A file that starts with U+FEFF keeps it: it is read as part of the first line
and written back with it. Its first heading is not editable, because the
extractor anchors the heading grammar at the start of the line and a line
beginning with the mark never becomes a task — so nothing reaches an edit.

Stripping the mark on read and restoring it on write would only help once the
extractor skips it as well. Until then the two would disagree about which
line the file starts with.

## Unicode normalisation when a heading can be typed

`Document::heading` compares the heading in the file with the one the caller
believes is there byte for byte. Today both come from the same scan and pass
through Kotlin without loss, so a NFC/NFD difference cannot arise.

It can as soon as a heading is typed — a system keyboard hands over decomposed
diacritics — and a visually identical heading would then be refused as
`Stale`. The answer is to compare after NFC normalisation
(`unicode-normalization`), and it is worth adding together with whatever first
lets a heading be entered.
