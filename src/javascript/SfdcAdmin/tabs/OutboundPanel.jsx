import React, { useState, Suspense, lazy } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader, Tab, TabItem } from '@jahia/moonstone';

const OutboundMappingTab = lazy(() => import('./OutboundMappingTab'));
const OutboundSyncTab    = lazy(() => import('./OutboundSyncTab'));

const NS = 'jcustomer-sfdc-connector';
const SUB = ['mapping', 'sync'];

/** jCustomer → SFDC (outbound) — Lead field mapping + push/synchronisation. */
export default function OutboundPanel() {
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
                    {sub === 'mapping' && <OutboundMappingTab />}
                    {sub === 'sync'    && <OutboundSyncTab />}
                </Suspense>
            </div>
        </div>
    );
}
