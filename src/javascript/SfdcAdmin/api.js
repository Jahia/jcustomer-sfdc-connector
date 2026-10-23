/**
 * API helpers — POST to Jahia Actions hosted on systemsite.
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

const post = async (actionName, body = null, query = '') => {
    const opts = {
        method: 'POST',
        credentials: 'include',
        cache: 'no-store',   // never serve a cached response — critical for live data reload
        headers: {
            'Content-Type': body ? 'application/json' : 'application/x-www-form-urlencoded',
            'Cache-Control': 'no-cache, no-store',
            'Pragma': 'no-cache'
        }
    };
    if (body) opts.body = JSON.stringify(body);

    const resp = await fetch(getActionUrl(actionName, query), opts);
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    return resp.json();
};

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
