/**
 * DataTable column render adapter. A UI extension runs on the HOST's Moonstone (Module Federation
 * singleton shipped by jContent), not on the version in its own node_modules, and DataTable changed its
 * column contract: up to 2.19 it calls render(value, row), from 2.20 render({ id, value, data, meta }).
 * Wrap every column render with cell() so it works whichever version jContent ships.
 */
export const cell = fn => (a, b) => (b === undefined && a && typeof a === 'object' && 'meta' in a && 'data' in a
    ? fn(a.value, a.data)
    : fn(a, b));
