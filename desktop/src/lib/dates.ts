export function partsInTimeZone(epochMillis: number, timeZone: string) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(new Date(epochMillis));
  const value = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? '';
  return {
    date: `${value('year')}-${value('month')}-${value('day')}`,
    time: `${value('hour')}:${value('minute')}`,
  };
}

export function zonedLocalToEpochMillis(
  date: string,
  time: string,
  timeZone: string,
): number {
  const [year, month, day] = date.split('-').map(Number);
  const [hour, minute] = time.split(':').map(Number);
  const target = Date.UTC(year, month - 1, day, hour, minute);
  let candidate = target;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const local = partsInTimeZone(candidate, timeZone);
    const [localYear, localMonth, localDay] = local.date.split('-').map(Number);
    const [localHour, localMinute] = local.time.split(':').map(Number);
    const represented = Date.UTC(
      localYear,
      localMonth - 1,
      localDay,
      localHour,
      localMinute,
    );
    candidate += target - represented;
  }
  return candidate;
}
