package com.github.blindpirate.gogradle.task.go;

import com.github.blindpirate.gogradle.GolangPluginSetting;
import com.github.blindpirate.gogradle.build.TestPatternFilter;
import com.github.blindpirate.gogradle.util.CollectionUtils;
import com.github.blindpirate.gogradle.util.IOUtils;
import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.internal.tasks.options.Option;
import org.gradle.api.internal.tasks.testing.junit.report.DefaultTestReport;
import org.gradle.api.internal.tasks.testing.junit.result.TestClassResult;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.operations.BuildOperationProcessor;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.github.blindpirate.gogradle.common.GoSourceCodeFilter.TEST_GO_FILTER;
import static com.github.blindpirate.gogradle.task.GolangTaskContainer.INSTALL_BUILD_DEPENDENCIES_TASK_NAME;
import static com.github.blindpirate.gogradle.task.GolangTaskContainer.INSTALL_TEST_DEPENDENCIES_TASK_NAME;
import static com.github.blindpirate.gogradle.util.CollectionUtils.isEmpty;
import static com.github.blindpirate.gogradle.util.IOUtils.filterFilesRecursively;
import static com.github.blindpirate.gogradle.util.IOUtils.safeListFiles;
import static com.github.blindpirate.gogradle.util.StringUtils.fileNameEndsWithAny;
import static com.github.blindpirate.gogradle.util.StringUtils.fileNameStartsWithAny;
import static com.github.blindpirate.gogradle.util.StringUtils.toUnixString;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

public class GoTestTask extends Go {
    private static final Logger LOGGER = Logging.getLogger(GoTestTask.class);

    @Inject
    private Project project;

    @Inject
    private GolangPluginSetting setting;

    @Inject
    private BuildOperationProcessor buildOperationProcessor;

    @Inject
    private GoTestStdoutExtractor extractor;

    private List<String> testNamePattern;

    public GoTestTask() {
        dependsOn(INSTALL_BUILD_DEPENDENCIES_TASK_NAME,
                INSTALL_TEST_DEPENDENCIES_TASK_NAME);
    }

    @Option(option = "tests", description = "Sets test class or method name to be included, '*' is supported.")
    @Incubating
    public GoTestTask setTestNamePattern(List<String> testNamePattern) {
        this.testNamePattern = testNamePattern;
        return this;
    }

    @Override
    protected void doAddDefaultAction() {
        if (isEmpty(testNamePattern)) {
            addTestAllAction();
        } else {
            addTestActions();
        }
    }

    private void addTestActions() {
        Collection<File> filesMatchingPatterns = filterMatchedTests();

        if (filesMatchingPatterns.isEmpty()) {
            LOGGER.quiet("No tests matching " + testNamePattern.stream().collect(joining("/")) + ", skip.");
        } else {
            LOGGER.quiet("Found " + filesMatchingPatterns.size() + " tests to run.");

            Map<File, List<File>> parentDirToFiles = groupByParentDir(filesMatchingPatterns);

            parentDirToFiles.forEach((parentDir, tests) -> {
                tests.addAll(getAllNonTestGoFiles(parentDir));
            });

            doLast(new TestPackagesAction(parentDirToFiles, true));
        }
    }

    private Collection<File> filterMatchedTests() {
        TestPatternFilter filter = TestPatternFilter.withPattern(testNamePattern);
        return filterFilesRecursively(project.getRootDir(), filter);
    }

    private void addTestAllAction() {
        // https://golang.org/cmd/go/#hdr-Description_of_package_lists
        Collection<File> allTestFiles = IOUtils.filterFilesRecursively(project.getRootDir(), TEST_GO_FILTER);
        doLast(new TestPackagesAction(groupByParentDir(allTestFiles), false));
    }

    private Map<File, List<File>> groupByParentDir(Collection<File> files) {
        return files.stream()
                .collect(groupingBy(File::getParentFile));
    }

    private List<File> getAllNonTestGoFiles(File dir) {
        return safeListFiles(dir).stream()
                .filter(file -> file.getName().endsWith(".go"))
                .filter(file -> !fileNameStartsWithAny(file, "_", "."))
                .filter(file -> !fileNameEndsWithAny(file, "_test.go"))
                .filter(File::isFile)
                .collect(toList());
    }

    private String dirToImportPath(File dir) {
        Path relativeToProjectRoot = project.getRootDir().toPath().relativize(dir.toPath());
        Path importPath = Paths.get(setting.getPackagePath()).resolve(relativeToProjectRoot);
        return toUnixString(importPath);
    }

    private class TestPackagesAction implements Action<Task> {
        private Map<File, List<File>> parentDirToTestFiles;

        private boolean isCommandLineArguments;

        private TestPackagesAction(Map<File, List<File>> parentDirToTestFiles, boolean isCommandLineArguments) {
            this.parentDirToTestFiles = parentDirToTestFiles;
            this.isCommandLineArguments = isCommandLineArguments;
        }

        @Override
        public void execute(Task task) {
            List<TestClassResult> testResults = new ArrayList<>();
            RetcodeCollector retcodeCollector = new RetcodeCollector();

            parentDirToTestFiles.forEach((parentDir, testFiles) -> {
                String packageImportPath = dirToImportPath(parentDir);
                StdoutStderrCollector stdoutCollector = doSingleTest(parentDir, testFiles, retcodeCollector);
                PackageTestContext context = PackageTestContext.builder()
                        .withPackagePath(packageImportPath)
                        .withStdout(stdoutCollector.getStdoutStderr())
                        .withTestFiles(testFiles)
                        .build();

                List<TestClassResult> resultOfSinglePackage = extractor.extractTestResult(context);
                logResult(packageImportPath, resultOfSinglePackage);
                testResults.addAll(resultOfSinglePackage);
            });

            GoTestResultsProvider provider = new GoTestResultsProvider(testResults);

            File reportDir = new File(project.getRootDir(), "reports/test");
            DefaultTestReport report = new DefaultTestReport(buildOperationProcessor);
            report.generateReport(provider, reportDir);

            if (retcodeCollector.containsNonZero()) {
                throw new IllegalStateException("There are failed tests. Please see "
                        + reportDir.getAbsolutePath()
                        + " for more details");
            }
        }

        private StdoutStderrCollector doSingleTest(File parentDir, List<File> testFiles, RetcodeCollector collector) {
            StdoutStderrCollector lineConsumer = new StdoutStderrCollector();
            if (isCommandLineArguments) {
                List<String> args = CollectionUtils.asStringList(
                        "test",
                        "-v",
                        testFiles.stream().map(File::getAbsolutePath).collect(toList()));
                buildManager.go(args, null, lineConsumer, lineConsumer, collector);
            } else {
                String importPath = dirToImportPath(parentDir);
                buildManager.go(asList("test", "-v", importPath), null, lineConsumer, lineConsumer, collector);
            }

            return lineConsumer;
        }

        private void logResult(String packagePath, List<TestClassResult> resultOfSinglePackage) {
            LOGGER.quiet("Test for {} finished, {} succeed, {} failed",
                    packagePath,
                    successCount(resultOfSinglePackage),
                    failureCount(resultOfSinglePackage)
            );
        }

        private int successCount(List<TestClassResult> results) {
            return results.stream()
                    .mapToInt(result ->
                            result.getResults().size() - result.getFailuresCount())
                    .sum();
        }

        private int failureCount(List<TestClassResult> results) {
            return results.stream().mapToInt(TestClassResult::getFailuresCount).sum();
        }
    }

    private static class StdoutStderrCollector implements Consumer<String> {
        private StringBuffer sb = new StringBuffer();

        @Override
        public void accept(String s) {
            sb.append(s).append("\n");
        }

        public String getStdoutStderr() {
            return sb.toString();
        }
    }

    private static class RetcodeCollector implements Consumer<Integer> {
        private List<Integer> retcodes = new ArrayList<>();

        private boolean containsNonZero() {
            return retcodes.stream().anyMatch(code -> code != 0);
        }

        @Override
        public synchronized void accept(Integer code) {
            retcodes.add(code);
        }
    }

}
