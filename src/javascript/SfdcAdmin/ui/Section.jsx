import React from 'react';
import { Paper, Typography } from '@jahia/moonstone';
import classes from './layout.module.css';

/**
 * A titled card: Moonstone Paper with a heading, an optional description and optional actions on the
 * right of the heading. All screens of the connector are built from these sections.
 */
export const Section = ({ title, description, actions, children }) => (
    <Paper hasPadding>
        <div className={classes.sectionHead}>
            <div className={classes.sectionTitle}>
                <Typography variant="subheading" weight="bold">{title}</Typography>
                {description && <Typography variant="caption">{description}</Typography>}
            </div>
            {actions && <div className={classes.actions}>{actions}</div>}
        </div>
        {children && <div className={classes.sectionBody}>{children}</div>}
    </Paper>
);

/** One setting: label and description on the left, its control (Switch, Dropdown, Button) on the right. */
export const SettingRow = ({ label, description, control, children }) => (
    <div className={classes.settingRow}>
        <div className={classes.settingMain}>
            <div className={classes.settingText}>
                <Typography variant="body" weight="semiBold">{label}</Typography>
                {description && <Typography variant="caption">{description}</Typography>}
            </div>
            {control && <div className={classes.settingControl}>{control}</div>}
        </div>
        {children && <div className={classes.settingExtra}>{children}</div>}
    </div>
);

/** Vertical stack of sections with the standard gap. */
export const Stack = ({ children }) => <div className={classes.stack}>{children}</div>;
