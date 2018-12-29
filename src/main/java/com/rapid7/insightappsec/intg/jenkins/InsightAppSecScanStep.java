package com.rapid7.insightappsec.intg.jenkins;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.rapid7.insightappsec.intg.jenkins.api.InsightAppSecLogger;
import com.rapid7.insightappsec.intg.jenkins.api.scan.ScanApi;
import com.rapid7.insightappsec.intg.jenkins.api.search.SearchApi;
import com.rapid7.insightappsec.intg.jenkins.credentials.InsightCredentialsHelper;
import com.rapid7.insightappsec.intg.jenkins.exception.UnrecognizedBuildAdvanceIndicatorException;
import com.rapid7.insightappsec.intg.jenkins.exception.UnrecognizedRegionException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toCollection;

public class InsightAppSecScanStep extends Builder implements SimpleBuildStep {

    private final String scanConfigId;
    private final BuildAdvanceIndicator buildAdvanceIndicator;
    private final String vulnerabilityQuery;
    private final Region region;
    private final String credentialsId;
    private final boolean storeScanResults;
    private final String maxScanStartWaitTime;
    private final String maxScanRuntime;

    @DataBoundConstructor
    public InsightAppSecScanStep(String scanConfigId,
                                 String buildAdvanceIndicator,
                                 String vulnerabilityQuery,
                                 String region,
                                 String credentialsId,
                                 boolean storeScanResults,
                                 String maxScanStartWaitTime,
                                 String maxScanRuntime) {
        this.scanConfigId = Util.fixEmptyAndTrim(scanConfigId);
        this.buildAdvanceIndicator = BuildAdvanceIndicator.fromString(buildAdvanceIndicator);
        this.vulnerabilityQuery = Util.fixEmptyAndTrim(vulnerabilityQuery);
        this.region = Region.fromString(region);
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
        this.storeScanResults = storeScanResults;
        this.maxScanStartWaitTime = Util.fixEmptyAndTrim(maxScanStartWaitTime);
        this.maxScanRuntime = Util.fixEmptyAndTrim(maxScanRuntime);
    }

    public String getScanConfigId() {
        return scanConfigId;
    }

    public BuildAdvanceIndicator getBuildAdvanceIndicator() {
        return buildAdvanceIndicator;
    }

    public String getVulnerabilityQuery() {
        return vulnerabilityQuery;
    }

    public Region getRegion() {
        return region;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public boolean isStoreScanResults() {
        return storeScanResults;
    }

    public String getMaxScanStartWaitTime() {
        return maxScanStartWaitTime;
    }

    public String getMaxScanRuntime() {
        return maxScanRuntime;
    }

    @Override
    public void perform(Run<?, ?> run,
                        FilePath workspace,
                        Launcher launcher,
                        TaskListener listener) throws InterruptedException {
        InsightAppSecLogger logger = new InsightAppSecLogger(listener.getLogger());

        Optional<ScanResults> scanResults = newRunner(logger).run(scanConfigId,
                                                                  buildAdvanceIndicator,
                                                                  vulnerabilityQuery);
        if (storeScanResults && scanResults.isPresent()) {
            new ScanResultHandler().handleScanResult(run, logger, buildAdvanceIndicator, scanResults.get());
        }
    }

    // HELPERS

    private InsightAppSecScanStepRunner newRunner(InsightAppSecLogger logger) {
        String apiKey = InsightCredentialsHelper.lookupInsightCredentialsById(credentialsId).getApiKey().getPlainText();

        ScanApi scanApi = new ScanApi(region.getAPIHost(), apiKey);
        SearchApi searchApi = new SearchApi(region.getAPIHost(), apiKey);

        return new InsightAppSecScanStepRunner(scanApi,
                                               searchApi,
                                               ThreadHelper.INSTANCE,
                                               logger,
                                               newWaitTimeHandler(scanApi, logger));
    }

    private WaitTimeHandler newWaitTimeHandler(ScanApi scanApi, InsightAppSecLogger logger) {
        long maxScanRuntimeDuration = WaitTimeParser.parseWaitTimeString(maxScanRuntime);
        long maxScanStartWaitTimeDuration = WaitTimeParser.parseWaitTimeString(maxScanStartWaitTime);

        return new WaitTimeHandler(buildAdvanceIndicator,
                                   maxScanStartWaitTimeDuration,
                                   maxScanRuntimeDuration,
                                   scanApi,
                                   logger);
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public ListBoxModel doFillBuildAdvanceIndicatorItems() {
            return Stream.of(BuildAdvanceIndicator.values())
                         .map(bi -> new ListBoxModel.Option(bi.getDisplayName(), bi.name()))
                         .collect(toCollection(ListBoxModel::new));
        }

        public FormValidation doCheckVulnerabilityQuery(@QueryParameter String vulnerabilityQuery) {
            return FormValidation.okWithMarkup(String.format(Messages.validation_markup_vulnerabilityQuery(),
                                                             Messages.selectors_vulnerabilityQuery()));
        }

        public FormValidation doCheckMaxScanStartWaitTime(@QueryParameter String maxScanStartWaitTime) {
            return doCheckWaitTime(maxScanStartWaitTime,
                                   String.format(Messages.validation_markup_maxScanStartWaitTime(),
                                                 Messages.selectors_scanSubmitted()));
        }

        public FormValidation doCheckMaxScanRuntime(@QueryParameter String maxScanRuntime) {
            return doCheckWaitTime(maxScanRuntime,
                                   String.format(Messages.validation_markup_maxScanRuntime(),
                                                 Messages.selectors_scanSubmitted(),
                                                 Messages.selectors_scanStarted()));
        }

        private FormValidation doCheckWaitTime(String waitTime,
                                               String defaultMarkup) {
            if (waitTime.isEmpty()) {
                return FormValidation.okWithMarkup(defaultMarkup);
            }

            try {
                WaitTimeParser.parseWaitTimeString(waitTime);
                return FormValidation.okWithMarkup(defaultMarkup);
            } catch (Exception e) {
                return FormValidation.error(Messages.validation_errors_invalidWaitTime());
            }
        }

        public FormValidation doCheckScanConfigId(@QueryParameter String scanConfigId) {
            return doCheckId(scanConfigId);
        }

        public ListBoxModel doFillRegionItems() {
            return Stream.of(Region.values())
                         .map(bi -> new ListBoxModel.Option(bi.getDisplayName(), bi.name()))
                         .collect(toCollection(ListBoxModel::new));
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Jenkins context) {
            if (context == null || !context.hasPermission(Item.CONFIGURE)) {
                return new StandardListBoxModel();
            }

            return new StandardListBoxModel().withAll(InsightCredentialsHelper.lookupAllInsightCredentials(context));
        }

        private FormValidation doCheckId(String id) {
            id = Util.fixEmptyAndTrim(id);

            if (id == null) {
                return FormValidation.error(Messages.validation_errors_requiredId());
            }

            try {
                UUID.fromString(id);
            } catch (Exception e) {
                return FormValidation.error(Messages.validation_errors_invalidId());
            }

            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.displayName();
        }

    }

    public enum Region {

        US(Messages.selectors_us(), resolveAPIHost("us")),
        CA(Messages.selectors_ca(), resolveAPIHost("ca")),
        EU(Messages.selectors_eu(), resolveAPIHost("eu")),
        AU(Messages.selectors_au(), resolveAPIHost("au"));

        private String displayName;
        private String apiHost;

        Region(String displayName, String apiHost) {
            this.displayName = displayName;
            this.apiHost = apiHost;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getAPIHost() {
            return apiHost;
        }

        private static String resolveAPIHost(String prefix) {
            return String.format("%s.api.insight.rapid7.com", prefix);
        }

        static Region fromString(String value) {
            return Arrays.stream(Region.values())
                    .filter(e -> e.name().equalsIgnoreCase(value))
                    .findAny()
                    .orElseThrow(() -> new UnrecognizedRegionException(String.format("The region provided [%s] is not recognized", value)));
        }
    }

    public enum BuildAdvanceIndicator {

        SCAN_SUBMITTED(Messages.selectors_scanSubmitted()),
        SCAN_STARTED(Messages.selectors_scanStarted()),
        SCAN_COMPLETED(Messages.selectors_scanCompleted()),
        VULNERABILITY_RESULTS(Messages.selectors_vulnerabilityQuery());

        String displayName;

        BuildAdvanceIndicator(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        static BuildAdvanceIndicator fromString(String value) {
            return Arrays.stream(BuildAdvanceIndicator.values())
                         .filter(e -> e.name().equalsIgnoreCase(value))
                         .findAny()
                         .orElseThrow(() -> new UnrecognizedBuildAdvanceIndicatorException("The build advance indicator provided is not recognized"));
        }

    }

}
