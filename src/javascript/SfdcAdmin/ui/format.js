/**
 * Human formatting for the admin UI: dates in the UI language (relative and absolute) and Quartz cron
 * expressions described in words. Nothing here is a value of the connector: everything is derived
 * from what the server returns.
 */
import { DAY_KEYS, parseCron } from '../tabs/CronBuilder';

const NS = 'jcustomer-sfdc-connector';

const uiLang = () => window.contextJsParameters?.uilang || undefined;

const UNITS = [
    ['year', 365 * 24 * 3600], ['month', 30 * 24 * 3600], ['week', 7 * 24 * 3600],
    ['day', 24 * 3600], ['hour', 3600], ['minute', 60], ['second', 1]
];

/** "5 minutes ago", "in 2 hours", in the UI language; empty for a missing or invalid date. */
export const relativeTime = iso => {
    if (!iso) return '';
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) return iso;
    const seconds = Math.round((date.getTime() - Date.now()) / 1000);
    const rtf = new Intl.RelativeTimeFormat(uiLang(), { numeric: 'auto' });
    for (const [unit, size] of UNITS) {
        if (Math.abs(seconds) >= size || unit === 'second') {
            return rtf.format(Math.round(seconds / size), unit);
        }
    }
    return '';
};

/** Full date and time in the UI language; the raw value when it cannot be parsed. */
export const absoluteTime = iso => {
    if (!iso) return '';
    const date = new Date(iso);
    return Number.isNaN(date.getTime()) ? iso : date.toLocaleString(uiLang());
};

const pad = v => String(v ?? 0).padStart(2, '0');

/** A Quartz cron expression in words ("Every 5 minutes", "Every day at 05:00"), or the raw expression. */
export const describeCron = (cron, t) => {
    if (!cron) return '';
    const s = parseCron(cron);
    const time = `${pad(s.hour)}:${pad(s.minute)}`;
    switch (s.freq) {
        case 'minutes':
            return t(`${NS}:cron.describe.minutes`, { count: Number(s.interval) });
        case 'hourly':
            return t(`${NS}:cron.describe.hourly`, { minute: pad(s.minute) });
        case 'weekly': {
            const day = DAY_KEYS.find(d => d.value === s.day);
            return t(`${NS}:cron.describe.weekly`, { day: day ? t(`${NS}:cron.day.${day.key}`) : s.day, time });
        }
        case 'monthly':
            return t(`${NS}:cron.describe.monthly`, { day: s.dayOfMonth, time });
        case 'daily':
        default:
            return t(`${NS}:cron.describe.daily`, { time });
    }
};
