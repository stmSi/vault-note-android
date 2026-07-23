import {
  isPermissionGranted,
  requestPermission,
  sendNotification,
  Visibility,
} from '@tauri-apps/plugin-notification';
import type { ScheduledAlert } from './models';

export interface ReminderReconcileResult {
  permissionGranted: boolean;
  scheduledCount: number;
}

const maximumTimerDelay = 2_000_000_000;
const activeTimers = new Map<number, ReturnType<typeof setTimeout>>();

export function prepareScheduledAlerts(
  alerts: ScheduledAlert[],
  now = Date.now(),
): ScheduledAlert[] {
  const identifiers = new Set<number>();
  return alerts
    .filter(
      (alert) =>
        Number.isSafeInteger(alert.triggerAtEpochMillis) &&
        alert.triggerAtEpochMillis > now + 500 &&
        Number.isInteger(alert.notificationId),
    )
    .sort((left, right) => left.triggerAtEpochMillis - right.triggerAtEpochMillis)
    .filter((alert) => {
      if (identifiers.has(alert.notificationId)) {
        return false;
      }
      identifiers.add(alert.notificationId);
      return true;
    });
}

export async function reconcileReminderNotifications(
  alerts: ScheduledAlert[],
  requestAccess: boolean,
): Promise<ReminderReconcileResult> {
  for (const timer of activeTimers.values()) {
    clearTimeout(timer);
  }
  activeTimers.clear();

  let permissionGranted = await isPermissionGranted();
  if (!permissionGranted && requestAccess && alerts.length > 0) {
    permissionGranted = (await requestPermission()) === 'granted';
  }
  if (!permissionGranted) {
    return { permissionGranted: false, scheduledCount: 0 };
  }

  const schedulable = prepareScheduledAlerts(alerts);
  for (const alert of schedulable) {
    armTimer(alert);
  }
  return {
    permissionGranted: true,
    scheduledCount: schedulable.length,
  };
}

function armTimer(alert: ScheduledAlert): void {
  const remaining = alert.triggerAtEpochMillis - Date.now();
  if (remaining <= 0) {
    showPrivateNotification(alert);
    return;
  }
  const timer = setTimeout(
    () => {
      activeTimers.delete(alert.notificationId);
      armTimer(alert);
    },
    Math.min(remaining, maximumTimerDelay),
  );
  activeTimers.set(alert.notificationId, timer);
}

function showPrivateNotification(alert: ScheduledAlert): void {
  sendNotification({
    id: alert.notificationId,
    title: 'VaultNote reminder',
    body: 'Open VaultNote to view this private reminder.',
    visibility: Visibility.Private,
    autoCancel: true,
    extra: {
      source: 'vaultnote-reminder',
      itemId: alert.itemId,
      entryId: alert.entryId,
    },
  });
}
