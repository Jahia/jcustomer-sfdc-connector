package org.jahia.community.modules.jcustomer.sfdc.sync;

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

/**
 * Quartz job for scheduled batch sync of Salesforce data into jCustomer.
 *
 * Config flows through JobDataMap (Quartz creates new instances per execution —
 * instance fields from @Activate are NOT available in executeJahiaJob). The site is resolved at
 * execution time through {@link SiteKeyResolver}.
 */
@Component(
    service = SfdcSyncJob.class,
    immediate = true,
    configurationPid = "org.jahia.community.jcustomer.sfdc"
)
public class SfdcSyncJob extends BackgroundJob {

    private static final Logger logger = LoggerFactory.getLogger(SfdcSyncJob.class);

    private static final String JOB_NAME     = "jCustomer - SFDC Connector Sync";
    private static final String TRIGGER_NAME = "SfdcSyncTrigger";

    private static final String KEY_DELTA_BUFFER = "deltaBuffer";

    @interface Config {
        String scheduler_cron()             default "0 0 5 * * ?";
        int    sync_delta_buffer_minutes()  default 5;
    }

    private SchedulerService schedulerService;
    private JobDetail        jobDetail;

    @Reference
    public void setSchedulerService(SchedulerService s) { this.schedulerService = s; }

    @Activate
    public void activate(Config config) throws Exception {
        final int    buffer     = config.sync_delta_buffer_minutes();
        final String cron       = config.scheduler_cron();

        logger.info("[SFDC-Job] Activating - cron={}, buffer={}min", cron, buffer);

        if (!SettingsBean.getInstance().isProcessingServer()) {
            logger.info("[SFDC-Job] Not a processing server — skipping scheduler");
            return;
        }

        jobDetail = BackgroundJob.createJahiaJob(JOB_NAME, SfdcSyncJob.class);
        jobDetail.getJobDataMap().put(KEY_DELTA_BUFFER, buffer);

        try { schedulerService.getScheduler().unscheduleJob(TRIGGER_NAME, jobDetail.getGroup()); }
        catch (Exception ignored) {}
        try { schedulerService.getScheduler().deleteJob(jobDetail.getName(), jobDetail.getGroup()); }
        catch (Exception ignored) {}

        CronTrigger trigger = new CronTrigger(
            TRIGGER_NAME, jobDetail.getGroup(),
            jobDetail.getName(), jobDetail.getGroup(), cron
        );
        schedulerService.getScheduler().scheduleJob(jobDetail, trigger);
        logger.info("[SFDC-Job] Scheduled with cron: {}", cron);
    }

    @Deactivate
    public void deactivate() throws Exception {
        if (jobDetail != null
                && !schedulerService.getAllJobs(jobDetail.getGroup()).isEmpty()
                && SettingsBean.getInstance().isProcessingServer()) {
            schedulerService.getScheduler().deleteJob(jobDetail.getName(), jobDetail.getGroup());
            logger.info("[SFDC-Job] Unscheduled");
        }
    }

    // ── Runtime reschedule (called from SaveTriggersAction) ───────────────────

    public void rescheduleCron(String newCron) throws Exception {
        if (jobDetail == null || !SettingsBean.getInstance().isProcessingServer()) return;
        try { schedulerService.getScheduler().unscheduleJob(TRIGGER_NAME, jobDetail.getGroup()); } catch (Exception ignored) {}
        CronTrigger trigger = new CronTrigger(TRIGGER_NAME, jobDetail.getGroup(),
            jobDetail.getName(), jobDetail.getGroup(), newCron);
        schedulerService.getScheduler().scheduleJob(jobDetail, trigger);
        logger.info("[SFDC-Job] Rescheduled with new cron: {}", newCron);
    }

    public void unschedule() throws Exception {
        if (jobDetail == null || !SettingsBean.getInstance().isProcessingServer()) return;
        try { schedulerService.getScheduler().unscheduleJob(TRIGGER_NAME, jobDetail.getGroup()); } catch (Exception ignored) {}
        try { schedulerService.getScheduler().deleteJob(jobDetail.getName(), jobDetail.getGroup()); } catch (Exception ignored) {}
        logger.info("[SFDC-Job] Job unscheduled by trigger config");
    }

    @Override
    public void executeJahiaJob(JobExecutionContext ctx) {
        logger.info("[SFDC-Job] ===== Batch sync firing at {} =====", ctx.getFireTime());

        final int    buffer  = ctx.getJobDetail().getJobDataMap().getIntValue(KEY_DELTA_BUFFER);
        SiteKeyResolver resolver = BundleUtils.getOsgiService(SiteKeyResolver.class, null);
        final String siteKey = resolver != null ? resolver.resolve() : null;

        SfdcSyncService syncService = BundleUtils.getOsgiService(SfdcSyncService.class, null);
        if (syncService == null) {
            logger.error("[SFDC-Job] SfdcSyncService not available — aborting");
            return;
        }

        try {
            syncService.runBatchSync(siteKey, buffer);
            logger.info("[SFDC-Job] ===== Batch sync complete =====");
        } catch (Exception e) {
            logger.error("[SFDC-Job] Batch sync failed", e);
        }
    }
}
