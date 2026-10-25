import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import {
    ArrowRight, Banner, Button, Chip, DataTable, Delete, Download, Dropdown, EmptyData, Input, Loader, Reload, Typography, Warning, Add
} from '@jahia/moonstone';
import {
    getSfdcFields, getJcProperties, getMapping, saveMapping, getOutboundMapping, saveOutboundMapping
} from '../api';
import { exportMappingCsv, exportMappingJson } from '../exportMapping';
import { Section } from '../ui/Section';
import { cell } from '../ui/table';
import classes from '../ui/layout.module.css';

const NS = 'jcustomer-sfdc-connector';

/**
 * Per direction: the Salesforce objects it maps, its API, and which side is the source (shown first).
 * requiredToCreate: Salesforce's own rule for creating a record (outbound only: inbound never creates).
 * defaults: rows have a value sent when the jCustomer property is empty, or always without a property
 * (outbound only).
 */
const CONFIG = {
    inbound: {
        objects: ['Contact', 'Account', 'Lead'], load: getMapping, save: saveMapping,
        columns: ['sfField', 'jcProperty'], target: 'jcProperty', requiredToCreate: {}, defaults: false
    },
    outbound: {
        objects: ['Lead', 'Contact'], load: getOutboundMapping, save: saveOutboundMapping,
        columns: ['jcProperty', 'sfField'], target: 'sfField', requiredToCreate: { Lead: ['LastName', 'Company'], Contact: ['LastName'] },
        defaults: true
    }
};

const byLabel = (a, b) => a.label.localeCompare(b.label, undefined, { sensitivity: 'base' });
const snapshot = rows => JSON.stringify((rows || []).map(r => [r.sfField || '', r.jcProperty || '', r.defaultValue || '']));
/** Values of a multi-select picklist as Salesforce stores them: Value1;Value2. */
const splitValues = v => (v || '').split(';').map(x => x.trim()).filter(Boolean);
/** A row maps something: a Salesforce field fed by a property, a default value, or both. */
const isMapped = r => !!(r.sfField && (r.jcProperty || r.defaultValue));

/** Keeps a stored value visible even when it is not in the loaded options (e.g. Salesforce unreachable). */
const withValue = (options, value) => (!value || options.some(o => o.value === value)
    ? options : [{ label: value, value }, ...options]);

/**
 * Field mapping of one direction: one editable table per Salesforce object, saved per object.
 *
 * @param direction 'inbound' (Salesforce → jCustomer) or 'outbound' (jCustomer → Salesforce)
 */
export default function MappingEditor({ direction }) {
    const { t } = useTranslation(NS);
    const cfg = CONFIG[direction];
    const nextId = useRef(0);
    const withIds = rows => (Array.isArray(rows) ? rows : []).map(r => ({
        _id: `r${nextId.current++}`, sfField: r.sfField || '', jcProperty: r.jcProperty || '', defaultValue: r.defaultValue || ''
    }));

    const [object,   setObject]   = useState(cfg.objects[0]);
    const [sfFields, setSfFields] = useState({});
    const [jcProps,  setJcProps]  = useState([]);
    const [rows,     setRows]     = useState({});      // object -> rows
    const [saved,    setSaved]    = useState({});      // object -> snapshot of the stored rows
    const [loading,  setLoading]  = useState(true);
    const [saving,   setSaving]   = useState(false);
    const [banner,   setBanner]   = useState(null);

    const load = useCallback(async () => {
        setLoading(true); setBanner(null);
        const [fieldsR, propsR, mappingR] = await Promise.allSettled([getSfdcFields(), getJcProperties(), cfg.load()]);
        if (fieldsR.status === 'fulfilled') setSfFields(fieldsR.value || {});
        if (propsR.status === 'fulfilled') setJcProps(Array.isArray(propsR.value) ? propsR.value : []);
        if (mappingR.status === 'fulfilled') {
            const m = mappingR.value || {};
            const loaded = {};
            const snap = {};
            cfg.objects.forEach(o => { loaded[o] = withIds(m[o]); snap[o] = snapshot(m[o]); });
            setRows(loaded);
            setSaved(snap);
        }
        const errors = [fieldsR, propsR, mappingR].filter(r => r.status === 'rejected').map(r => r.reason?.message).filter(Boolean);
        if (errors.length) setBanner({ variant: 'danger', title: t(`${NS}:mapping.loadError`), text: errors.join(' | ') });
        setLoading(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [direction]);

    useEffect(() => { load(); }, [load]);

    if (loading) return <div className={classes.center}><Loader size="big" /></div>;

    const current = rows[object] || [];
    const dirtyOf = o => snapshot(rows[o]) !== saved[o];
    const dirty = dirtyOf(object);

    const update = (id, key, val) => setRows(r => ({ ...r, [object]: r[object].map(x => (x._id === id ? { ...x, [key]: val } : x)) }));
    const add    = () => setRows(r => ({ ...r, [object]: [...(r[object] || []), ...withIds([{}])] }));
    const remove = id => setRows(r => ({ ...r, [object]: r[object].filter(x => x._id !== id) }));

    const handleSave = async () => {
        setSaving(true); setBanner(null);
        try {
            const mappings = current.map(({ sfField, jcProperty, defaultValue }) => (cfg.defaults
                ? { sfField, jcProperty, defaultValue: (defaultValue || '').trim() }
                : { sfField, jcProperty }));
            await cfg.save({ sfObject: object, mappings });
            setSaved(s => ({ ...s, [object]: snapshot(mappings) }));
            setBanner({ variant: 'info', title: t(`${NS}:mapping.savedFor`, { object }) });
        } catch (e) {
            setBanner({ variant: 'danger', title: e.message });
        } finally { setSaving(false); }
    };

    const handleExport = fmt => {
        const n = (fmt === 'csv' ? exportMappingCsv : exportMappingJson)(direction, object, current);
        setBanner(n === 0
            ? { variant: 'warning', title: t(`${NS}:mapping.export.empty`, { obj: object }) }
            : { variant: 'info', title: t(`${NS}:mapping.export.done`, { obj: object, count: n, fmt: fmt.toUpperCase() }) });
    };

    // Options
    const jcOptions = jcProps.map(p => ({ label: `${p.name}${p.multivalued ? ' [multi]' : ''}`, value: p.id })).sort(byLabel);
    const sfOptions = (Array.isArray(sfFields[object]) ? sfFields[object] : []).map(f => ({
        label: `${f.label || f.name} (${f.name})`,
        value: object === 'Account' && !f.name.startsWith('Account.') ? `Account.${f.name}` : f.name
    })).sort(byLabel);
    // Outbound, a row may have no property: its default value is then a fixed value.
    const noProperty = { label: t(`${NS}:mapping.noProperty`), value: '' };
    const optionsFor = key => (key === 'sfField' ? sfOptions : (cfg.defaults ? [noProperty, ...jcOptions] : jcOptions));

    // Salesforce field metadata of the current object (type, picklist values), and multi-valued properties.
    const sfMeta = {};
    (Array.isArray(sfFields[object]) ? sfFields[object] : []).forEach(f => { sfMeta[f.name] = f; });
    const picklistOf = field => {
        const meta = sfMeta[field];
        return meta && Array.isArray(meta.picklistValues) && meta.picklistValues.length > 0 ? meta.picklistValues : null;
    };
    const jcMulti = new Set(jcProps.filter(p => p.multivalued).map(p => p.id));

    // Checks
    const targets = current.map(r => r[cfg.target]).filter(Boolean);
    const duplicates = [...new Set(targets.filter((v, i) => targets.indexOf(v) !== i))];
    const mapped = new Set(current.filter(isMapped).map(r => r.sfField));
    const missingRequired = (cfg.requiredToCreate[object] || []).filter(f => !mapped.has(f));
    // Salesforce refuses a value outside a restricted picklist, and several values in a single-value picklist.
    const notInPicklist = cfg.defaults ? current.flatMap(r => {
        const meta = sfMeta[r.sfField];
        const allowed = picklistOf(r.sfField);
        if (!r.defaultValue || !allowed || !meta.restrictedPicklist) return [];
        const given = meta.type === 'multipicklist' ? splitValues(r.defaultValue) : [r.defaultValue.trim()];
        const unknown = given.filter(x => !allowed.includes(x));
        return unknown.length ? [`${r.sfField} (${unknown.join(', ')})`] : [];
    }) : [];
    const multiToSingle = cfg.defaults
        ? current.filter(r => r.jcProperty && jcMulti.has(r.jcProperty) && sfMeta[r.sfField]?.type === 'picklist').map(r => r.sfField)
        : [];

    // DataTable rows are wrapping flex rows: the widths must add up to 100% exactly, or the last
    // cell wraps under the first one. The field columns (and the default value) share what the arrow
    // and remove leave.
    const fluid = `calc((100% - 88px) / ${cfg.defaults ? 3 : 2})`;
    const fieldColumn = key => ({
        key,
        width: fluid,
        label: key === 'sfField' ? t(`${NS}:mapping.col.sfFieldOf`, { object }) : t(`${NS}:mapping.col.jcProperty`),
        render: cell((v, row) => (
            <div className={classes.cellControl}>
                <Dropdown key={`${key}-${optionsFor(key).length}-${row._id}`} hasSearch size="small"
                    value={row[key] || ''}
                    placeholder={t(`${NS}:${key === 'sfField' ? 'mapping.selectField' : 'mapping.selectProperty'}`)}
                    data={withValue(optionsFor(key), row[key])}
                    onChange={(e, item) => update(row._id, key, item?.value ?? '')} />
            </div>
        ))
    });

    const columns = [
        fieldColumn(cfg.columns[0]),
        { key: 'arrow', label: '', width: '32px', render: cell(() => <ArrowRight />) },
        fieldColumn(cfg.columns[1]),
        ...(cfg.defaults ? [{
            key: 'defaultValue', width: fluid, label: t(`${NS}:mapping.col.defaultValue`),
            render: cell((v, row) => {
                const meta = sfMeta[row.sfField];
                const allowed = picklistOf(row.sfField);
                const label = t(`${NS}:mapping.col.defaultValue`);
                // A picklist offers its own values; a multi-select picklist stores them as Value1;Value2.
                if (allowed && meta.type === 'picklist') {
                    const data = [{ label: t(`${NS}:mapping.noDefault`), value: '' }, ...allowed.map(x => ({ label: x, value: x }))];
                    return (
                        <div className={classes.cellControl}>
                            <Dropdown key={`default-${row._id}-${row.sfField}`} hasSearch size="small" aria-label={label}
                                value={row.defaultValue || ''} placeholder={t(`${NS}:mapping.noDefault`)}
                                data={withValue(data, row.defaultValue)}
                                onChange={(e, item) => update(row._id, 'defaultValue', item?.value ?? '')} />
                        </div>
                    );
                }
                if (allowed && meta.type === 'multipicklist') {
                    const selected = splitValues(row.defaultValue);
                    const data = [...allowed, ...selected.filter(x => !allowed.includes(x))].map(x => ({ label: x, value: x }));
                    const toggle = value => {
                        const next = selected.includes(value) ? selected.filter(x => x !== value) : [...selected, value];
                        // Salesforce's order, then any value not in the picklist (kept so it can be removed)
                        update(row._id, 'defaultValue',
                            [...allowed.filter(x => next.includes(x)), ...next.filter(x => !allowed.includes(x))].join(';'));
                    };
                    return (
                        <div className={classes.cellControl}>
                            <Dropdown key={`default-${row._id}-${row.sfField}`} hasSearch size="small" aria-label={label}
                                values={selected} placeholder={t(`${NS}:mapping.defaultMultiPlaceholder`)} data={data}
                                onChange={(e, item) => { if (item?.value) toggle(item.value); }} />
                        </div>
                    );
                }
                const placeholder = meta?.type === 'multipicklist' ? 'mapping.defaultMultiPlaceholder'
                    : (row.jcProperty ? 'mapping.defaultPlaceholder' : 'mapping.fixedPlaceholder');
                return (
                    <div className={classes.cellControl}>
                        <Input id={`sfdc-default-${row._id}`} size="small" value={row.defaultValue || ''}
                            aria-label={label} placeholder={t(`${NS}:${placeholder}`)}
                            onChange={e => update(row._id, 'defaultValue', e.target.value)} />
                    </div>
                );
            })
        }] : []),
        {
            key: 'remove', label: '', width: '56px', align: 'right',
            render: cell((v, row) => <Button variant="ghost" color="danger" icon={<Delete />}
                aria-label={t(`${NS}:action.remove`)} onClick={() => remove(row._id)} />)
        }
    ];

    const actions = (
        <>
            {dirty && <Chip color="warning" label={t(`${NS}:common.unsaved`)} />}
            <Button variant="ghost" icon={<Download />} label={t(`${NS}:mapping.export.csv`)} onClick={() => handleExport('csv')} />
            <Button variant="ghost" icon={<Download />} label={t(`${NS}:mapping.export.json`)} onClick={() => handleExport('json')} />
            <Button variant="ghost" icon={<Reload />} label={t(`${NS}:action.reload`)} onClick={load} />
            <Button variant="default" color="accent" label={saving ? t(`${NS}:action.saving`) : t(`${NS}:action.save`)}
                isLoading={saving} isDisabled={saving || !dirty} onClick={handleSave} />
        </>
    );

    return (
        <div className={classes.page}>
            <Section title={t(`${NS}:mapping.title.${direction}`)} description={t(`${NS}:mapping.subtitle.${direction}`)} actions={actions}>
                <div className={classes.row}>
                    {cfg.objects.map(o => {
                        const count = (rows[o] || []).filter(isMapped).length;
                        return (
                            <Button key={o}
                                variant={o === object ? 'default' : 'ghost'} color={o === object ? 'accent' : 'default'}
                                label={`${o} · ${count}${dirtyOf(o) ? ' •' : ''}`}
                                onClick={() => { setObject(o); setBanner(null); }} />
                        );
                    })}
                </div>

                {banner && (
                    <Banner variant={banner.variant} title={banner.title} iconStart={banner.variant === 'info' ? undefined : <Warning />}>
                        <span>{banner.text || ''}</span>
                    </Banner>
                )}
                {missingRequired.length > 0 && (
                    <Banner variant="warning" iconStart={<Warning />}
                        title={t(`${NS}:mapping.required.title`, { object })}>
                        <span>{t(`${NS}:outbound.mapping.requiredHint`, { obj: object, fields: missingRequired.join(', ') })}</span>
                    </Banner>
                )}
                {cfg.defaults && (
                    <Typography variant="caption">{t(`${NS}:mapping.defaultsHelp`)}</Typography>
                )}
                {notInPicklist.length > 0 && (
                    <Banner variant="warning" iconStart={<Warning />}
                        title={t(`${NS}:mapping.notInPicklist.title`, { fields: notInPicklist.join(', ') })}>
                        <span>{t(`${NS}:mapping.notInPicklist.text`)}</span>
                    </Banner>
                )}
                {multiToSingle.length > 0 && (
                    <Banner variant="warning" iconStart={<Warning />}
                        title={t(`${NS}:mapping.multiToSingle.title`, { fields: multiToSingle.join(', ') })}>
                        <span>{t(`${NS}:mapping.multiToSingle.text`)}</span>
                    </Banner>
                )}
                {duplicates.length > 0 && (
                    <Banner variant="warning" iconStart={<Warning />} title={t(`${NS}:mapping.duplicates`, { fields: duplicates.join(', ') })}>
                        <span>{t(`${NS}:mapping.duplicatesHelp`)}</span>
                    </Banner>
                )}

                {current.length === 0 ? (
                    <EmptyData title={t(`${NS}:mapping.emptyTitle`, { object })} message={t(`${NS}:mapping.emptyMessage.${direction}`)} />
                ) : (
                    <div className={classes.tableScroll}>
                        <DataTable primaryKey="_id" data={current} columns={columns} enablePagination={false} />
                    </div>
                )}

                <div className={classes.actions}>
                    <Button variant="outlined" icon={<Add />} label={t(`${NS}:mapping.addRow`)} onClick={add} />
                </div>
            </Section>
        </div>
    );
}
