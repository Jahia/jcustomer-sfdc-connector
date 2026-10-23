import React, { useState, Suspense, lazy } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader, Tab, TabItem } from '@jahia/moonstone';

const MappingTab = lazy(() => import('./MappingTab'));
const SyncTab    = lazy(() => import('./SyncTab'));
const HistoryTab = lazy(() => import('./HistoryTab'));

const NS = 'jcustomer-sfdc-connector';
const SUB = ['mapping', 'sync', 'history'];

/** SFDC → jCustomer (inbound) — groups the existing field mapping + synchronisation tabs. */
export default function InboundPanel() {
    const { t } = useTranslation(NS);
    const [sub, setSub] = useState('mapping');

    return (
        <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
            <div style={{ padding: '8px 24px 0' }}>
                <Tab>
                    {SUB.map(k => (
                        <TabItem key={k} label={t(`${NS}:admin.tab.${k}`)}
                            isSelected={sub === k} onClick={() => setSub(k)} />
                    ))}
                </Tab>
            </div>
            <div style={{ flex: 1, overflowY: 'auto' }}>
                <Suspense fallback={<div style={{ display: 'flex', justifyContent: 'center', padding: 48 }}><Loader size="big" /></div>}>
                    {sub === 'mapping' && <MappingTab />}
                    {sub === 'sync'    && <SyncTab />}
                    {sub === 'history' && <HistoryTab direction="inbound" />}
                </Suspense>
            </div>
        </div>
    );
}
