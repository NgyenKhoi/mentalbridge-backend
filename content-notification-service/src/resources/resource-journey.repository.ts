import { Inject, Injectable } from '@nestjs/common';

import { DATABASE_SERVICE_TOKEN } from '../application.tokens.js';
import type { DatabaseClient, DatabaseService } from '../database/database.service.js';
import {
  RESOURCE_COLUMNS,
  toResourceRow,
  type ResourceDatabaseRow,
} from './resource.repository.js';
import { toResourceSummary } from './resource.service.js';
import {
  localDateInTimeZone,
  mondayOf,
  planBingoResources,
  planDailyResources,
  practiceStreak,
  supportPlanDay,
  supportPlanStage,
} from './resource-journey.planner.js';
import type {
  AssignmentReason,
  PlannerResource,
  ResourceJourney,
  ResourceJourneyInput,
} from './resource-journey.types.js';

interface AssignmentRow {
  readonly id: string;
  readonly support_plan_id: string;
  readonly support_plan_version: string | number;
}

interface AssignmentItemRow {
  readonly ordinal: number;
  readonly resource_id: string;
  readonly selection_reason: AssignmentReason;
}

interface BingoRow {
  readonly id: string;
  readonly support_plan_id: string;
}

interface BingoItemRow {
  readonly ordinal: number;
  readonly resource_id: string;
}

interface ResourceIdRow {
  readonly resource_id: string;
}

interface DateRow {
  readonly local_date: string | Date;
}

interface ResourceCountRow extends ResourceIdRow {
  readonly scheduled_count: string | number;
}

function localDate(value: string | Date): string {
  return value instanceof Date ? value.toISOString().slice(0, 10) : value;
}

function plannerResource(row: ResourceDatabaseRow): PlannerResource {
  return {
    id: row.id,
    resourceKind: row.resource_kind,
    repeatability: row.repeatability,
    cooldownDays: row.cooldown_days,
    recommendedFrequencyPerWeek: row.recommended_frequency_per_week,
    streakEligible: row.streak_eligible,
    planTags: row.plan_tags,
  };
}

async function selectResources(client: DatabaseClient, ids: readonly string[]) {
  if (ids.length === 0) return [];
  const rows = await client.query<ResourceDatabaseRow>(
    `SELECT ${RESOURCE_COLUMNS} FROM resource WHERE id = ANY($1::uuid[])`,
    [ids],
  );
  return rows.rows.map(toResourceRow);
}

@Injectable()
export class ResourceJourneyRepository {
  constructor(
    @Inject(DATABASE_SERVICE_TOKEN)
    private readonly db: DatabaseService,
  ) {}

  async materialize(
    ownerId: string,
    requestedDate: string,
    input: ResourceJourneyInput,
  ): Promise<ResourceJourney | null> {
    const planDay = supportPlanDay(input.supportPlan.activatedAt, input.timeZone, requestedDate);
    if (planDay === null) return null;
    const planStage = supportPlanStage(planDay);
    const activationDate = localDateInTimeZone(input.supportPlan.activatedAt, input.timeZone);
    const weekStart = mondayOf(requestedDate);

    return this.db.withTransaction(async (client) => {
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [
        `${ownerId}:RESOURCE_JOURNEY_WEEK:${input.supportPlan.supportPlanId}:${weekStart}`,
      ]);
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [
        `${ownerId}:RESOURCE_JOURNEY:${requestedDate}`,
      ]);

      const candidatesResult = await client.query<ResourceDatabaseRow>(
        `SELECT ${RESOURCE_COLUMNS}
         FROM resource
         WHERE status = 'PUBLISHED'
           AND reviewed_by IS NOT NULL
           AND reviewed_at IS NOT NULL
           AND source_review_status = 'REVIEWED'
           AND (effective_at IS NULL OR effective_at <= now())
           AND (expires_at IS NULL OR expires_at > now())
           AND (
             (catalogue_visibility = 'LISTED' AND plan_tags && $1::text[])
             OR id = ANY($2::uuid[])
           )
         ORDER BY id`,
        [[...input.supportPlan.domains], [...input.supportPlan.selectedResourceIds]],
      );
      const candidateRows = candidatesResult.rows;
      if (candidateRows.length === 0) return null;

      const completedLearning = await client.query<ResourceIdRow>(
        `SELECT resource_id
         FROM resource_learning_completion
         WHERE owner_id = $1 AND local_date <= $2::date`,
        [ownerId, requestedDate],
      );
      const recentSessions = await client.query<ResourceIdRow & DateRow>(
        `SELECT DISTINCT ON (resource_id) resource_id, local_date
         FROM resource_practice_session
         WHERE owner_id = $1
           AND local_date BETWEEN $2::date - 30 AND $2::date
         ORDER BY resource_id, local_date DESC`,
        [ownerId, requestedDate],
      );
      const scheduledThisWeek = await client.query<ResourceCountRow>(
        `SELECT item.resource_id, count(*) AS scheduled_count
         FROM resource_daily_assignment assignment
         JOIN resource_daily_assignment_item item ON item.assignment_id = assignment.id
         WHERE assignment.owner_id = $1
           AND assignment.support_plan_id = $2
           AND assignment.local_date BETWEEN $3::date AND $3::date + 6
         GROUP BY item.resource_id`,
        [ownerId, input.supportPlan.supportPlanId, weekStart],
      );
      const selectedIds = new Set(input.supportPlan.selectedResourceIds);
      const completedIds = new Set(completedLearning.rows.map((row) => row.resource_id));
      const latestSessionByResource = new Map(
        recentSessions.rows.map((row) => [row.resource_id, localDate(row.local_date)]),
      );
      const scheduledThisWeekByResource = new Map(
        scheduledThisWeek.rows.map((row) => [row.resource_id, Number(row.scheduled_count)]),
      );

      let assignment = (
        await client.query<AssignmentRow>(
          `SELECT id, support_plan_id, support_plan_version
           FROM resource_daily_assignment
           WHERE owner_id = $1 AND local_date = $2::date`,
          [ownerId, requestedDate],
        )
      ).rows.at(0);
      if (!assignment) {
        const planned = planDailyResources(candidateRows.map(plannerResource), {
          localDate: requestedDate,
          planDay,
          planStage,
          selectedResourceIds: selectedIds,
          completedLearningIds: completedIds,
          latestSessionByResource,
          scheduledThisWeekByResource,
        });
        assignment = (
          await client.query<AssignmentRow>(
            `INSERT INTO resource_daily_assignment
               (owner_id, local_date, time_zone, support_plan_id, support_plan_version, plan_tags)
             VALUES ($1, $2::date, $3, $4, $5, $6::text[])
             RETURNING id, support_plan_id, support_plan_version`,
            [
              ownerId,
              requestedDate,
              input.timeZone,
              input.supportPlan.supportPlanId,
              input.supportPlan.version,
              [...input.supportPlan.domains],
            ],
          )
        ).rows.at(0);
        if (!assignment) return null;
        for (const [ordinal, plannedResource] of planned.entries()) {
          await client.query(
            `INSERT INTO resource_daily_assignment_item
               (assignment_id, ordinal, resource_id, selection_reason)
             VALUES ($1, $2, $3, $4)`,
            [assignment.id, ordinal, plannedResource.resourceId, plannedResource.reason],
          );
        }
      }

      const assignmentItems = await client.query<AssignmentItemRow>(
        `SELECT ordinal, resource_id, selection_reason
         FROM resource_daily_assignment_item
         WHERE assignment_id = $1
         ORDER BY ordinal`,
        [assignment.id],
      );
      const assignedResources = await selectResources(
        client,
        assignmentItems.rows.map((row) => row.resource_id),
      );
      const summariesById = new Map(
        assignedResources
          .map((row) => toResourceSummary(row))
          .filter((summary) => summary !== null)
          .map((summary) => [summary.id, summary]),
      );

      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [
        `${ownerId}:RESOURCE_BINGO:${weekStart}`,
      ]);
      let board = (
        await client.query<BingoRow>(
          `SELECT id, support_plan_id FROM resource_weekly_bingo
           WHERE owner_id = $1 AND week_start = $2::date`,
          [ownerId, weekStart],
        )
      ).rows.at(0);
      if (!board) {
        board = (
          await client.query<BingoRow>(
            `INSERT INTO resource_weekly_bingo
               (owner_id, week_start, time_zone, support_plan_id, support_plan_version, plan_tags)
             VALUES ($1, $2::date, $3, $4, $5, $6::text[])
             RETURNING id, support_plan_id`,
            [
              ownerId,
              weekStart,
              input.timeZone,
              input.supportPlan.supportPlanId,
              input.supportPlan.version,
              [...input.supportPlan.domains],
            ],
          )
        ).rows.at(0);
        if (!board) return null;
        const bingoIds = planBingoResources(
          candidateRows.map(plannerResource),
          selectedIds,
          weekStart,
        );
        for (const [ordinal, resourceId] of bingoIds.entries()) {
          await client.query(
            `INSERT INTO resource_weekly_bingo_item (board_id, ordinal, resource_id)
             VALUES ($1, $2, $3)`,
            [board.id, ordinal, resourceId],
          );
        }
      }

      const bingoItems = await client.query<BingoItemRow>(
        `SELECT ordinal, resource_id
         FROM resource_weekly_bingo_item
         WHERE board_id = $1
         ORDER BY ordinal`,
        [board.id],
      );
      const bingoResources = await selectResources(
        client,
        bingoItems.rows.map((row) => row.resource_id),
      );
      const bingoTitles = new Map(bingoResources.map((resource) => [resource.id, resource.title]));

      const dailyCompleted = await client.query<ResourceIdRow>(
        `SELECT resource_id
         FROM resource_daily_progress
         WHERE owner_id = $1 AND local_date = $2::date AND status = 'COMPLETED'
           AND resource_id = ANY($3::uuid[])`,
        [ownerId, requestedDate, assignmentItems.rows.map((row) => row.resource_id)],
      );
      const weekEnd = new Date(`${weekStart}T00:00:00Z`);
      weekEnd.setUTCDate(weekEnd.getUTCDate() + 6);
      const stamped = await client.query<ResourceIdRow>(
        `SELECT DISTINCT resource_id FROM (
           SELECT progress.resource_id
           FROM resource_daily_progress progress
           JOIN resource_daily_assignment assignment
             ON assignment.owner_id = progress.owner_id
            AND assignment.local_date = progress.local_date
            AND assignment.support_plan_id = $4
           JOIN resource_daily_assignment_item item
             ON item.assignment_id = assignment.id
            AND item.resource_id = progress.resource_id
           WHERE progress.owner_id = $1 AND progress.status = 'COMPLETED'
             AND progress.local_date BETWEEN $2::date AND $3::date
           UNION ALL
           SELECT session.resource_id
           FROM resource_practice_session session
           WHERE session.owner_id = $1 AND session.support_plan_id = $4
             AND session.local_date BETWEEN $2::date AND $3::date
         ) completed`,
        [ownerId, weekStart, weekEnd.toISOString().slice(0, 10), board.support_plan_id],
      );
      const streakDates = await client.query<DateRow>(
        `SELECT DISTINCT s.local_date
         FROM resource_practice_session s
         JOIN resource r ON r.id = s.resource_id
         WHERE s.owner_id = $1 AND s.support_plan_id = $3 AND r.streak_eligible
           AND s.local_date BETWEEN $4::date AND $2::date
         ORDER BY s.local_date DESC`,
        [ownerId, requestedDate, assignment.support_plan_id, activationDate],
      );

      const learningCandidates = candidateRows.filter((row) => row.resource_kind === 'LEARNING');
      const stampedIds = new Set(stamped.rows.map((row) => row.resource_id));
      return {
        assignmentId: assignment.id,
        localDate: requestedDate,
        planId: assignment.support_plan_id,
        planVersion: Number(assignment.support_plan_version),
        planDay,
        planStage,
        items: assignmentItems.rows.flatMap((row) => {
          const resource = summariesById.get(row.resource_id);
          return resource
            ? [{ position: row.ordinal + 1, resource, reason: row.selection_reason }]
            : [];
        }),
        progress: {
          dailyCompleted: dailyCompleted.rows.length,
          dailyTotal: assignmentItems.rows.length,
          learningCompleted: learningCandidates.filter((row) => completedIds.has(row.id)).length,
          learningTotal: learningCandidates.length,
          practiceStreakDays: practiceStreak(
            streakDates.rows.map((row) => localDate(row.local_date)),
            requestedDate,
          ),
        },
        weekStart,
        bingo: bingoItems.rows.map((row) => ({
          position: row.ordinal + 1,
          resourceId: row.resource_id,
          label: bingoTitles.get(row.resource_id) ?? 'Hoạt động hỗ trợ',
          stamped: stampedIds.has(row.resource_id),
        })),
      };
    });
  }
}
