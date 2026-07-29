//! Tests for moving a planning date and for completing a task.
//!
//! The repeater cases follow upstream Emacs Org-mode `org-auto-repeat-maybe`
//! (lisp/org.el): a `+` repeater takes exactly one step from the date in the
//! file, `++` keeps stepping until it passes today, and `.+` restarts from
//! today. Marking a repeating task done therefore moves it forward and leaves
//! it open rather than closing it.

use markdown_org_ffi::{complete_task, shift_planning, EditError, PlanningKeyword};

mod common;

use common::{body, target, vault};

const TODAY: &str = "2026-07-28";

#[test]
fn shifting_a_scheduled_date_keeps_everything_around_it() {
    let vault = vault("# TODO Write the report\n`SCHEDULED: <2026-07-28 Tue 10:00 -2d>`\n");

    let outcome = shift_planning(
        target(vault.path(), 1, "Write the report"),
        PlanningKeyword::Scheduled,
        1,
    )
    .expect("shift");

    assert_eq!(outcome.line, "`SCHEDULED: <2026-07-29 Wed 10:00 -2d>`");
    assert_eq!(
        body(vault.path()),
        "# TODO Write the report\n`SCHEDULED: <2026-07-29 Wed 10:00 -2d>`\n"
    );
}

#[test]
fn shifting_backwards_works_the_same_way() {
    let vault = vault("# TODO Write the report\n`DEADLINE: <2026-07-28 Tue>`\n");

    let outcome = shift_planning(
        target(vault.path(), 1, "Write the report"),
        PlanningKeyword::Deadline,
        -7,
    )
    .expect("shift");

    assert_eq!(outcome.line, "`DEADLINE: <2026-07-21 Tue>`");
}

#[test]
fn a_localised_weekday_is_rewritten_in_the_same_language() {
    let vault = vault("# TODO Отчёт\n`SCHEDULED: <2026-07-28 Вт>`\n");

    let outcome = shift_planning(
        target(vault.path(), 1, "Отчёт"),
        PlanningKeyword::Scheduled,
        1,
    )
    .expect("shift");

    assert_eq!(outcome.line, "`SCHEDULED: <2026-07-29 Ср>`");
}

#[test]
fn a_full_weekday_name_stays_full() {
    let vault = vault("# TODO Write the report\n`SCHEDULED: <2026-07-28 Tuesday>`\n");

    let outcome = shift_planning(
        target(vault.path(), 1, "Write the report"),
        PlanningKeyword::Scheduled,
        1,
    )
    .expect("shift");

    assert_eq!(outcome.line, "`SCHEDULED: <2026-07-29 Wednesday>`");
}

#[test]
fn the_other_planning_line_is_left_alone() {
    let vault = vault(
        "# TODO Write the report\n`SCHEDULED: <2026-07-28 Tue>`\n`DEADLINE: <2026-07-30 Thu>`\n",
    );

    shift_planning(
        target(vault.path(), 1, "Write the report"),
        PlanningKeyword::Scheduled,
        1,
    )
    .expect("shift");

    assert_eq!(
        body(vault.path()),
        "# TODO Write the report\n`SCHEDULED: <2026-07-29 Wed>`\n`DEADLINE: <2026-07-30 Thu>`\n"
    );
}

#[test]
fn shifting_a_planning_line_the_task_does_not_have_is_refused() {
    let vault = vault("# TODO Write the report\n`SCHEDULED: <2026-07-28 Tue>`\n");

    let error = shift_planning(
        target(vault.path(), 1, "Write the report"),
        PlanningKeyword::Deadline,
        1,
    )
    .expect_err("must refuse");

    assert!(
        matches!(error, EditError::NoPlanningLine { .. }),
        "{error:?}"
    );
}

#[test]
fn completing_a_plain_task_marks_it_done() {
    let vault = vault("# TODO Write the report\n`SCHEDULED: <2026-07-28 Tue>`\n");

    let outcome = complete_task(
        target(vault.path(), 1, "Write the report"),
        TODAY.to_string(),
    )
    .expect("complete");

    assert!(!outcome.repeated);
    assert_eq!(outcome.heading, "# DONE Write the report");
    assert_eq!(
        body(vault.path()),
        "# DONE Write the report\n`SCHEDULED: <2026-07-28 Tue>`\n"
    );
}

#[test]
fn completing_a_cumulative_repeater_takes_one_step() {
    // `+1w` from a date in the past lands one week later, still in the past:
    // upstream takes exactly one step and does not catch up.
    let vault = vault("# TODO Water the plants\n`SCHEDULED: <2026-07-01 Wed +1w>`\n");

    let outcome = complete_task(
        target(vault.path(), 1, "Water the plants"),
        TODAY.to_string(),
    )
    .expect("complete");

    assert!(outcome.repeated);
    assert_eq!(
        body(vault.path()),
        "# TODO Water the plants\n`SCHEDULED: <2026-07-08 Wed +1w>`\n"
    );
}

#[test]
fn completing_a_catch_up_repeater_passes_today() {
    let vault = vault("# TODO Water the plants\n`SCHEDULED: <2026-07-01 Wed ++1w>`\n");

    complete_task(
        target(vault.path(), 1, "Water the plants"),
        TODAY.to_string(),
    )
    .expect("complete");

    // 1, 8, 15, 22, 29 July: the first Wednesday strictly after 28 July.
    assert_eq!(
        body(vault.path()),
        "# TODO Water the plants\n`SCHEDULED: <2026-07-29 Wed ++1w>`\n"
    );
}

#[test]
fn completing_a_restart_repeater_counts_from_today() {
    let vault = vault("# TODO Water the plants\n`SCHEDULED: <2026-07-01 Wed .+3d>`\n");

    complete_task(
        target(vault.path(), 1, "Water the plants"),
        TODAY.to_string(),
    )
    .expect("complete");

    assert_eq!(
        body(vault.path()),
        "# TODO Water the plants\n`SCHEDULED: <2026-07-31 Fri .+3d>`\n"
    );
}

#[test]
fn completing_a_repeating_task_moves_every_repeating_planning_line() {
    let vault = vault(
        "# TODO Report\n`SCHEDULED: <2026-07-01 Wed +1m>`\n`DEADLINE: <2026-07-05 Sun +1m>`\n",
    );

    complete_task(target(vault.path(), 1, "Report"), TODAY.to_string()).expect("complete");

    assert_eq!(
        body(vault.path()),
        "# TODO Report\n`SCHEDULED: <2026-08-01 Sat +1m>`\n`DEADLINE: <2026-08-05 Wed +1m>`\n"
    );
}

#[test]
fn a_planning_line_without_a_repeater_stays_put_when_another_one_repeats() {
    let vault =
        vault("# TODO Report\n`SCHEDULED: <2026-07-01 Wed +1m>`\n`DEADLINE: <2026-07-05 Sun>`\n");

    complete_task(target(vault.path(), 1, "Report"), TODAY.to_string()).expect("complete");

    // Upstream removes a repeater-less SCHEDULED here; this application keeps
    // it, because removing a line the user wrote is not something a tap on a
    // phone should do. Documented divergence.
    assert_eq!(
        body(vault.path()),
        "# TODO Report\n`SCHEDULED: <2026-08-01 Sat +1m>`\n`DEADLINE: <2026-07-05 Sun>`\n"
    );
}

#[test]
fn completing_a_repeating_task_leaves_it_open() {
    let vault = vault("# TODO Water the plants\n`SCHEDULED: <2026-07-28 Tue +1d>`\n");

    let outcome = complete_task(
        target(vault.path(), 1, "Water the plants"),
        TODAY.to_string(),
    )
    .expect("complete");

    assert_eq!(outcome.heading, "# TODO Water the plants");
    assert!(outcome.repeated);
}

#[test]
fn an_hourly_repeater_is_refused_rather_than_guessed_at() {
    let vault = vault("# TODO Take the pill\n`SCHEDULED: <2026-07-28 Tue 10:00 +6h>`\n");

    let error = complete_task(target(vault.path(), 1, "Take the pill"), TODAY.to_string())
        .expect_err("must refuse");

    assert!(matches!(error, EditError::Unsupported { .. }), "{error:?}");
    assert_eq!(
        body(vault.path()),
        "# TODO Take the pill\n`SCHEDULED: <2026-07-28 Tue 10:00 +6h>`\n"
    );
}

#[test]
fn a_workday_repeater_skips_the_weekend() {
    // 31 July 2026 is a Friday, so one working day later is Monday.
    let vault = vault("# TODO Standup\n`SCHEDULED: <2026-07-31 Fri +1wd>`\n");

    complete_task(target(vault.path(), 1, "Standup"), TODAY.to_string()).expect("complete");

    assert_eq!(
        body(vault.path()),
        "# TODO Standup\n`SCHEDULED: <2026-08-03 Mon +1wd>`\n"
    );
}

#[test]
fn completing_a_task_with_a_malformed_today_is_refused() {
    let vault = vault("# TODO Write the report\n`SCHEDULED: <2026-07-28 Tue +1d>`\n");

    let error = complete_task(
        target(vault.path(), 1, "Write the report"),
        "yesterday".to_string(),
    )
    .expect_err("must refuse");

    assert!(matches!(error, EditError::InvalidDate { .. }), "{error:?}");
}
