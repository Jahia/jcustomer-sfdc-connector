import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import {
    ArrowRight, Banner, Button, Chip, DataTable, Delete, Download, Dropdown, EmptyData, Loader, Reload, Warning, Add
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
 */
const CONFIG = {
    inbound: {
        objects: ['Contact', 'Account', 'Lead'], load: getMapping, save: saveMapping,
        columns: ['sfField', 'jcProperty'], target: 'jcProperty', requiredToCreate: {}
    },
    outbound: {
        objects: ['Lead', 'Contact'], load: getOutboundMapping, save: saveOutboundMapping,
        columns: ['jcProperty', 'sfField'], target: 'sfField', requiredToCreate: { Lead: ['LastName', 'Company'], Contact: ['LastName'] }
    }
};

const byLabel = (a, b) => a.label.localeCompare(b.label, undefined, { sensitivity: 'base' });
const snapshot = rows => JSON.stringify((rows || []).map(r => [r.sfField || '', r.jcProperty || '']));

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
        _id: `r${nextId.current++}`, sfField: r.sfField || '', jcProperty: r.jcProperty || ''
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
            const mappings = current.map(({ sfField, jcProperty }) => ({ sfField, jcProperty }));
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
    const optionsFor = key => (key === 'sfField' ? sfOptions : jcOptions);

    // Checks
    const targets = current.map(r => r[cfg.target]).filter(Boolean);
    const duplicates = [...new Set(targets.filter((v, i) => targets.indexOf(v) !== i))];
    const mapped = new Set(current.map(r => r.sfField));
    const missingRequired = (cfg.requiredToCreate[object] || []).filter(f => !mapped.has(f));

    // DataTable rows are wrapping flex rows: the widths must add up to 100% exactly, or the last
    // cell wraps under the first one. The two field columns share what the arrow and remove leave.
    const fieldColumn = key => ({
        key,
        width: 'calc((100% - 88px) / 2)',
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
                        const count = (rows[o] || []).filter(r => r.sfField && r.jcProperty).length;
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
