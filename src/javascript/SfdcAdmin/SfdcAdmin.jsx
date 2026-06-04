import React, { useState, Suspense, lazy } from 'react';
import { useTranslation } from 'react-i18next';
import { Header, Loader, Tab, TabItem } from '@jahia/moonstone';

const ConnectionTab = lazy(() => import('./tabs/ConnectionTab'));
const MappingTab    = lazy(() => import('./tabs/MappingTab'));
const SyncTab       = lazy(() => import('./tabs/SyncTab'));

const NS = 'jcustomer-sfdc-connector';
const TABS = ['connection', 'mapping', 'sync'];

export default function SfdcAdmin() {
    const { t } = useTranslation(NS);
    const [tab, setTab] = useState('connection');

    return (
        <div style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
            <Header
                title={t(`${NS}:admin.panel.title`)}
                toolbarLeft={[
                    <Tab key="tabs">
                        {TABS.map(key => (
                            <TabItem
                                key={key}
                                label={t(`${NS}:admin.tab.${key}`)}
                                isSelected={tab === key}
                                onClick={() => setTab(key)}
                            />
                        ))}
                    </Tab>
                ]}
            />

            <div style={{ flex: 1, overflowY: 'auto' }}>
                <Suspense fallback={<div style={{ display: 'flex', justifyContent: 'center', padding: 48 }}><Loader size="big" /></div>}>
                    {tab === 'connection' && <ConnectionTab />}
                    {tab === 'mapping'    && <MappingTab />}
                    {tab === 'sync'       && <SyncTab />}
                </Suspense>
            </div>
        </div>
    );
}
