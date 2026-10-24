/**
 * API helpers - POST to Jahia Actions hosted on systemsite.
 * All connector actions use /sites/systemsite as the host JCR node.
 */

const getActionUrl = (actionName, query = '') => {
    const contextPath = window.contextJsParameters?.contextPath ?? '';
    return `${window.location.origin}${contextPath}/cms/render/default/en/sites/systemsite.${actionName}.do${query}`;
};

/**
 * Key of the site the admin panel is open on. The panel is a site-level administration route
 * (`/administration/<siteKey>/...`); the server validates the value and falls back to the configured
 * or auto-detected site when it is missing or not a site.
 */
export const currentSiteKey = () => {
    const match = window.location.pathname.match(/\/administration\/([^/]+)\//);
    return (match && decodeURIComponent(match[1])) || window.contextJsParameters?.siteKey || '';
};

const siteQuery = () => {
    const siteKey = currentSiteKey();
    return siteKey ? `?siteKey=${encodeURIComponent(siteKey)}` : '';
};

/**
 * POSTs to a connector action with XMLHttpRequest, not fetch: the actions that change something
 * (save, sync, push, purge) are under Jahia's CSRF guard, and the guard's script, loaded in every
 * back-office page, adds the session's token to XMLHttpRequest calls to *.do (never to fetch).
 * A refused call is redirected to the error page: reported as an error, never parsed as data.
 */
const post = (actionName, body = null, query = '') => new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', getActionUrl(actionName, query));
    xhr.withCredentials = true;
    xhr.setRequestHeader('Content-Type', body ? 'application/json' : 'application/x-www-form-urlencoded');
    // never serve a cached response - live data on every reload
    xhr.setRequestHeader('Cache-Control', 'no-cache, no-store');
    xhr.setRequestHeader('Pragma', 'no-cache');
    xhr.onload = () => {
        if (xhr.status < 200 || xhr.status >= 300) {
            let detail = '';
            try { detail = JSON.parse(xhr.responseText)?.error || ''; } catch { /* not JSON */ }
            reject(new Error(detail ? `${detail} (HTTP ${xhr.status})` : `HTTP ${xhr.status}`));
        } else if (/\/error\.html/.test(xhr.responseURL || '')) {
            reject(new Error('Refused by the CSRF guard: reload the page and try again.'));
        } else {
            try {
                resolve(JSON.parse(xhr.responseText));
            } catch {
                reject(new Error(`Unexpected answer from ${actionName}`));
            }
        }
    };
    xhr.onerror = () => reject(new Error(`Network error calling ${actionName}`));
    xhr.send(body ? JSON.stringify(body) : null);
});

export const getConnection        = ()             => post('getConnection');
export const getSfdcFields       = ()             => post('getSfdcFields');
export const getJcProperties     = ()             => post('getJcProperties');
export const getMapping          = ()             => post('getMapping');
export const saveMapping         = body           => post('saveMapping', body);
export const saveConnection      = body           => post('saveConnection', body);
export const testConnection      = ()             => post('testConnection');
export const triggerSync         = ()             => post('triggerSync', null, siteQuery());
export const getSyncState        = ()             => post('getSyncState');
export const getTriggers         = ()             => post('getTriggers');
export const saveTriggers        = body           => post('saveTriggers', body);

// Outbound (jCustomer → SFDC)
export const getOutboundMapping   = ()            => post('getOutboundMapping');
export const saveOutboundMapping  = body          => post('saveOutboundMapping', body);
export const triggerOutboundSync  = body          => post('triggerOutboundSync', body, siteQuery());
export const getOutboundSyncState = ()            => post('getOutboundSyncState');
export const getOutboundTriggers      = ()        => post('getOutboundTriggers');
export const saveOutboundTriggers     = body      => post('saveOutboundTriggers', body);
export const triggerOutboundReconcile = ()        => post('triggerOutboundReconcile', {}, siteQuery());

// Sync history (direction: 'inbound' | 'outbound')
const directionQuery = (direction, extra = {}) =>
    '?' + new URLSearchParams({ direction, ...extra }).toString();
export const getSyncHistory    = direction        => post('getSyncHistory', null, directionQuery(direction));
export const getSyncHistoryRun = (direction, run) => post('getSyncHistoryRun', null, directionQuery(direction, { runId: run }));
export const exportSyncHistory = direction        => post('getSyncHistory', null, directionQuery(direction, { includeRecords: 'true' }));
export const purgeSyncHistory  = direction        => post('purgeSyncHistory', null, directionQuery(direction));
export const saveHistoryRetention = (direction, enabled, days) =>
    post('saveHistoryRetention', null, directionQuery(direction, { enabled: String(enabled), days: String(days) }));
