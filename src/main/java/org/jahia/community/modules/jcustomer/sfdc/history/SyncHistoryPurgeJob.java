package org.jahia.community.modules.jcustomer.sfdc.history;

import org.jahia.osgi.BundleUtils;
import org.jahia.services.scheduler.BackgroundJob;
import org.jahia.services.scheduler.SchedulerService;
import org.jahia.settings.SettingsBean;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quartz job that applies the automatic purge of the sync history: deletes the runs older than the
 * retention of their direction ({@link SyncHistoryService#purgeExpired()}).
 *
 * <p>Schedule: {@code history.purge.cron} of the {@code org.jahia.community.jcustomer.sfdc}
 * configuration (default every hour). Runs on the processing server only.
 *
 * <p>Quartz creates a new instance per execution: the service is looked up at execution time.
 */
@Component(service = SyncHistoryPurgeJob.class, immediate = true,
        configurationPid = "org.jahia.community.jcustomer.sfdc")
public class SyncHistoryPurgeJob extends BackgroundJob {

    private static final Logger logger = LoggerFactory.getLogger(SyncHistoryPurgeJob.class);

    private static final String JOB_NAME     = "jCustomer - SFDC History Purge";
    private static final String TRIGGER_NAME = "SfdcHistoryPurgeTrigger";

    @interface Config {
        String history_purge_cron() default "0 7 * * * ?";
    }

    private SchedulerService schedulerService;
    @Reference public void setSchedulerService(SchedulerService s) { this.schedulerService = s; }

    private JobDetail jobDetail;

    @Activate
    @Modified
    public synchronized void activate(Config config) throws Exception {
        if (!SettingsBean.getInstance().isProcessingServer()) {
            logger.info("[SFDC-History] Not a processing server - automatic purge not scheduled here");
            return;
        }
        unschedule();                                   // previous schedule, on a configuration change
        jobDetail = BackgroundJob.createJahiaJob(JOB_NAME, SyncHistoryPurgeJob.class);
        unschedule();                                   // a trigger left by an earlier start of the bundle
        CronTrigger trigger = new CronTrigger(TRIGGER_NAME, jobDetail.getGroup(),
                jobDetail.getName(), jobDetail.getGroup(), config.history_purge_cron());
        schedulerService.getScheduler().scheduleJob(jobDetail, trigger);
        logger.info("[SFDC-History] Automatic purge scheduled with cron {}", config.history_purge_cron());
    }

    @Deactivate
    public synchronized void deactivate() {
        if (SettingsBean.getInstance().isProcessingServer()) unschedule();
    }

    private void unschedule() {
        if (jobDetail == null) return;
        try { schedulerService.getScheduler().unscheduleJob(TRIGGER_NAME, jobDetail.getGroup()); } catch (Exception ignored) {}
        try { schedulerService.getScheduler().deleteJob(jobDetail.getName(), jobDetail.getGroup()); } catch (Exception ignored) {}
    }

    @Override
    public void executeJahiaJob(JobExecutionContext ctx) {
        SyncHistoryService history = BundleUtils.getOsgiService(SyncHistoryService.class, null);
        if (history == null) {
            logger.error("[SFDC-History] SyncHistoryService not available - automatic purge skipped");
            return;
        }
        history.purgeExpired();
    }
}
