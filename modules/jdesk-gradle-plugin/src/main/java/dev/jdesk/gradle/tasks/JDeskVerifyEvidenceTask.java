package dev.jdesk.gradle.tasks;

import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.process.ExecOperations;

/**
 * {@code jdeskVerifyEvidence}: runs {@code dev.jdesk.testkit.evidence.VerifyMain}
 * against the configured evidence directory (default {@code build/evidence}),
 * recomputing checksums and validating manifests per spec section 18. The verifier
 * classpath comes from the {@code jdeskTestkit} configuration.
 */
@UntrackedTask(because = "verification of external evidence; must run every time")
public abstract class JDeskVerifyEvidenceTask extends DefaultTask {

    @Classpath
    public abstract ConfigurableFileCollection getVerifierClasspath();

    @Internal
    public abstract DirectoryProperty getEvidenceDirectory();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void verifyEvidence() {
        String evidenceDir = getEvidenceDirectory().get().getAsFile().getAbsolutePath();
        getLogger().lifecycle("jdeskVerifyEvidence: verifying {}", evidenceDir);
        try {
            getExecOperations().javaexec(spec -> {
                spec.classpath(getVerifierClasspath());
                spec.getMainClass().set("dev.jdesk.testkit.evidence.VerifyMain");
                spec.args(evidenceDir);
            });
        } catch (Exception e) {
            throw new GradleException("jdeskVerifyEvidence: evidence verification failed"
                    + " for " + evidenceDir + ". See the VerifyMain output above;"
                    + " evidence must be produced by real runs (spec section 18).", e);
        }
    }
}
