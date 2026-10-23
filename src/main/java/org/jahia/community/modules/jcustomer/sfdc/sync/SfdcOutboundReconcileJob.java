package org.jahia.community.modules.jcustomer.sfdc.sync;

import org.jahia.community.modules.jcustomer.sfdc.config.ConnectorConfigService;
import org.jahia.community.modules.jcustomer.sfdc.config.SiteKeyResolver;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.scheduler.BackgroundJob;
import org.jahia.services.scheduler.SchedulerService;
import org.jahia.settings.SettingsBean;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Quartz job that periodically reconciles jCustomer → Salesforce: pushes profiles changed since the
 * last run to Salesforce Leads. This is the "poll" half of the hybrid outbound trigger — it catches
 * profile edits that emit no Unomi event (console / REST / batch), which the event webhook cannot see.
 *
 * <p>Enabled/cron come from the outbound trigger config in JCR ({@code reconcileEnabled} /
 * {@code reconcileCron}); {@link #reschedule()} is called when that config is saved.
 *
 * <p>Quartz creates a new instance per execution, so nothing set on this instance by {@code @Activate}
 * is visible in {@link #executeJahiaJob}: the buffer travels in the JobDataMap and the site is
 * resolved at execution time through {@link SiteKeyResolver}.
 */
@Component(service = SfdcOutboundReconcileJob.class, immediate = true,
        configurationPid = "org.jahia.community.jcustomer.sfdc")
public class SfdcOutboundReconcileJob extends BackgroundJob {

    private static final Logger logger = LoggerFactory.getLogger(SfdcOutboundReconcileJob.class);

    private static final String JOB_NAME     = "jCustomer - SFDC Outbound Reconcile";
    private static final String TRIGGER_NAME = "SfdcOutboundReconcileTrigger";
    private static final int    MAX_PROFILES = 500;
    private static final String KEY_DELTA_BUFFER = "deltaBuffer";

    @interface Config {
        int    sync_delta_buffer_minutes() default 5;
    }

    private SchedulerService       schedulerService;
    private ConnectorConfigService configService;
    @Reference public void setSchedulerService(SchedulerService s)   { this.schedulerService = s; }
    @Reference public void setConfigService(ConnectorConfigService s) { this.configService = s; }

    private JobDetail jobDetail;

    @Activate
    public void activate(Config config) throws Exception {
        if (!SettingsBean.getInstance().isProcessingServer()) {
            logger.info("[SFDC-Reconcile] Not a processing server — skipping scheduler");
            return;
        }
        jobDetail = BackgroundJob.createJahiaJob(JOB_NAME, SfdcOutboundReconcileJob.class);
        jobDetail.getJobDataMap().put(KEY_DELTA_BUFFER, config.sync_delta_buffer_minutes());
        applySchedule();
    }

    /** Re-reads the JCR reconcile config and (re)schedules or unschedules accordingly. */
    public synchronized void reschedule() throws Exception {
        if (jobDetail == null || !SettingsBean.getInstance().isProcessingServer()) return;
        applySchedule();
    }

    private void applySchedule() throws Exception {
        Map<String, String> t = configService.loadOutboundTriggers();
        boolean enabled = "true".equalsIgnoreCase(t.getOrDefault("reconcileEnabled", "false"));
        String cron     = t.getOrDefault("reconcileCron", "0 0 * * * ?");

        // Remove any existing trigger AND job first — scheduleJob(jobDetail, trigger) throws
        // ObjectAlreadyExistsException if the job is already stored (e.g. from a previous enable).
        try { schedulerService.getScheduler().unscheduleJob(TRIGGER_NAME, jobDetail.getGroup()); } catch (Exception ignored) {}
        try { schedulerService.getScheduler().deleteJob(jobDetail.getName(), jobDetail.getGroup()); } catch (Exception ignored) {}

        if (!enabled) {
            logger.info("[SFDC-Reconcile] Reconcile disabled — not scheduled");
            return;
        }
        CronTrigger trigger = new CronTrigger(TRIGGER_NAME, jobDetail.getGroup(),
                jobDetail.getName(), jobDetail.getGroup(), cron);
        schedulerService.getScheduler().scheduleJob(jobDetail, trigger);
        logger.info("[SFDC-Reconcile] Scheduled with cron: {}", cron);
    }

    @Deactivate
    public void deactivate() throws Exception {
        if (jobDetail != null && SettingsBean.getInstance().isProcessingServer()) {
            try { schedulerService.getScheduler().unscheduleJob(TRIGGER_NAME, jobDetail.getGroup()); } catch (Exception ignored) {}
            try { schedulerService.getScheduler().deleteJob(jobDetail.getName(), jobDetail.getGroup()); } catch (Exception ignored) {}
        }
    }

    @Override
    public void executeJahiaJob(JobExecutionContext ctx) {
        logger.info("[SFDC-Reconcile] ===== Reconcile firing at {} =====", ctx.getFireTime());
        SfdcOutboundSyncService svc = BundleUtils.getOsgiService(SfdcOutboundSyncService.class, null);
        if (svc == null) {
            logger.error("[SFDC-Reconcile] SfdcOutboundSyncService not available — aborting");
            return;
        }
        int buffer = ctx.getJobDetail().getJobDataMap().containsKey(KEY_DELTA_BUFFER)
                ? ctx.getJobDetail().getJobDataMap().getIntValue(KEY_DELTA_BUFFER) : 5;
        SiteKeyResolver resolver = BundleUtils.getOsgiService(SiteKeyResolver.class, null);
        svc.runReconcile(resolver != null ? resolver.resolve() : null, buffer, MAX_PROFILES);
    }
}
