import { describe, expect, it } from 'vitest';
import type { ScheduledAlert } from './models';
import { prepareScheduledAlerts } from './reminders';

function alert(id: number, trigger: number): ScheduledAlert {
  return {
    notificationId: id,
    alertId: `alert-${id}`,
    entryId: `entry-${id}`,
    itemId: `item-${id}`,
    triggerAtEpochMillis: trigger,
  };
}

describe('prepareScheduledAlerts', () => {
  it('drops past alerts, sorts future alerts, and protects notification identifiers', () => {
    expect(
      prepareScheduledAlerts(
        [alert(1, 3_000), alert(2, 900), alert(1, 2_000), alert(3, 2_500)],
        1_000,
      ),
    ).toEqual([alert(1, 2_000), alert(3, 2_500)]);
  });
});
