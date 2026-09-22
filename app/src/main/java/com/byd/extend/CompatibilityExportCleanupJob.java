package com.byd.extend;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class CompatibilityExportCleanupJob extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        CompatibilityExportArtifacts.checkAsync(this, () -> jobFinished(params, false));
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }
}
