# TODO

Work that is understood but deliberately not done yet.

## Table of contents

- [One set of notes on more than one remote](#one-set-of-notes-on-more-than-one-remote)
- [Weekday names beyond Russian and English](#weekday-names-beyond-russian-and-english)
- [Tooltips beyond the task row](#tooltips-beyond-the-task-row)
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

## Tooltips beyond the task row

`TaskTooltip` wraps the rows of both layouts, and nothing else on the screen
carries a tooltip. The icon buttons have a `contentDescription`, which the
screen reader announces but a long press does not show, so a glyph a sighted
user does not recognise stays unexplained.

What goes without one, from a walk over the screens on 2026-08-09:

| № | Element                                                            | What it offers today                                          |
|---|--------------------------------------------------------------------|---------------------------------------------------------------|
| 1 | The icon buttons of the bar                                        | a `contentDescription`, and nothing on a long press            |
| 2 | The collection chips and the collection filter                     | a label, with no word on the directory behind it               |
| 3 | The sync banner, the unpushed count, the time of the last sync     | a line of text, with no word on what is being counted          |
| 4 | The scan notices                                                   | the notice alone, with no word on what produced it             |
| 5 | The group action menu and its items                                | a `contentDescription` on the anchor                           |
| 6 | The collection dot at the head of a row                            | a colour, and nothing that names the collection                |

`TooltipBox` already does the delay and the positioning, so what is missing is
a string per element in both languages, worded as the extension words it — the
same task should read the same on the phone and in the editor.

## Unicode normalisation when a heading can be typed

`Document::heading` compares the heading in the file with the one the caller
believes is there byte for byte. Today both come from the same scan and pass
through Kotlin without loss, so a NFC/NFD difference cannot arise.

It can as soon as a heading is typed — a system keyboard hands over decomposed
diacritics — and a visually identical heading would then be refused as
`Stale`. The answer is to compare after NFC normalisation
(`unicode-normalization`), and it is worth adding together with whatever first
lets a heading be entered.
