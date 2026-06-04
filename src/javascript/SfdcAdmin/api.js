/**
 * API helpers — POST to Jahia Actions hosted on systemsite.
 * All connector actions use /sites/systemsite as the host JCR node.
 */

const getActionUrl = actionName => {
    const contextPath = window.contextJsParameters?.contextPath ?? '';
    return `${window.location.origin}${contextPath}/cms/render/default/en/sites/systemsite.${actionName}.do`;
};

const post = async (actionName, body = null) => {
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

    const resp = await fetch(getActionUrl(actionName), opts);
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
export const triggerSync         = (siteKey)      => post('triggerSync', null);
export const getSyncState        = ()             => post('getSyncState');
export const getTriggers         = ()             => post('getTriggers');
export const saveTriggers        = body           => post('saveTriggers', body);
