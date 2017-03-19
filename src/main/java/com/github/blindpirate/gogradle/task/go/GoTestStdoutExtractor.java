package com.github.blindpirate.gogradle.task.go;

import com.github.blindpirate.gogradle.util.IOUtils;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.tuple.Pair;
import org.gradle.api.internal.tasks.testing.junit.result.TestClassResult;
import org.gradle.api.internal.tasks.testing.junit.result.TestMethodResult;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.testing.TestResult;

import javax.inject.Singleton;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.blindpirate.gogradle.util.DateUtils.toMilliseconds;
import static java.lang.Double.parseDouble;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@Singleton
public class GoTestStdoutExtractor {
    private static final Logger LOGGER = Logging.getLogger(GoTestStdoutExtractor.class);
    private static final Map<String, TestResult.ResultType> RESULT_TYPE_MAP =
            ImmutableMap.of("PASS", TestResult.ResultType.SUCCESS,
                    "FAIL", TestResult.ResultType.FAILURE);

    //=== RUN   TestDiffToHTML
    //--- PASS: TestDiffToHTML (0.00s)
    private static final Pattern TEST_RESULT_PATTERN =
            Pattern.compile("=== RUN\\s+(\\w+)\\n((?:.|\\n)*?)--- (PASS|FAIL):\\s+\\w+\\s+\\(((\\d+)(\\.\\d+)?)s\\)");

    private static final String SETUP_FAILED_ERROR = "[setup failed]";
    private static final String BUILD_FAILED_ERROR = "[build failed]";
    private static final String CANNOT_LOAD_PACKAGE_ERROR = "can't load package";
    private static final AtomicLong GLOBAL_COUNTER = new AtomicLong(0);

    public List<TestClassResult> extractTestResult(PackageTestContext context) {
        if (stdoutContains(context, SETUP_FAILED_ERROR)) {
            return failResult(context, SETUP_FAILED_ERROR);
        } else if (stdoutContains(context, BUILD_FAILED_ERROR)) {
            return failResult(context, BUILD_FAILED_ERROR);
        } else if (stdoutContains(context, CANNOT_LOAD_PACKAGE_ERROR)) {
            return failResult(context, CANNOT_LOAD_PACKAGE_ERROR);
        } else {
            return successfulTestResults(context);
        }
    }

    private boolean stdoutContains(PackageTestContext context, String error) {
        return context.getStdout().stream().anyMatch(s -> s.contains(error));
    }

    private List<TestClassResult> successfulTestResults(PackageTestContext context) {
        String stdout = removeTailMessages(context.getStdout());

        Map<File, String> testFileContents = loadTestFiles(context.getTestFiles());
        List<GoTestMethodResult> results = extractTestMethodResult(stdout);

        Map<File, List<GoTestMethodResult>> testFileToResults = groupByTestFile(results, testFileContents);

        return testFileToResults.entrySet().stream()
                .map(entry -> {
                    String className = determineClassName(context.getPackagePath(), entry.getKey().getName());
                    return methodResultsToClassResult(className, entry.getValue());
                })
                .collect(toList());
    }

    private String removeTailMessages(List<String> stdout) {
        /*
            FAIL
            coverage: 66.7% of statements
            exit status 1
            FAIL github.com/my/project/a 0.006s

            FAIL
            exit status 1
            FAIL a 0.006s

            PASS
            coverage: 83.3% of statements
            ok a 0.005s

            PASS
            ok a 0.005s
         */

        for (int i = 1; i <= 4 && i <= stdout.size(); ++i) {
            String line = stdout.get(stdout.size() - i).trim();
            if ("FAIL".equals(line) || "PASS".equals(line)) {
                return String.join("\n", stdout.subList(0, stdout.size() - i));
            }
        }
        return String.join("\n", stdout);
    }

    private List<TestClassResult> failResult(PackageTestContext context, String reason) {
        String message = String.join("\n", context.getStdout());
        GoTestMethodResult result = new GoTestMethodResult(GLOBAL_COUNTER.incrementAndGet(),
                reason,
                TestResult.ResultType.FAILURE,
                0L,
                0L,
                message);

        result.addFailure(message, message, message);

        String className = determineClassName(context.getPackagePath(), reason);

        return asList(methodResultsToClassResult(className, asList(result)));
    }

    private List<GoTestMethodResult> extractTestMethodResult(String stdout) {
        Matcher matcher = TEST_RESULT_PATTERN.matcher(stdout);
        List<GoTestMethodResult> ret = new ArrayList<>();
        List<Pair<Integer, Integer>> startAndEnds = new ArrayList<>();
        while (matcher.find()) {
            long id = GLOBAL_COUNTER.incrementAndGet();
            String methodName = matcher.group(1);
            String message = matcher.group(2);
            TestResult.ResultType resultType = RESULT_TYPE_MAP.get(matcher.group(3));
            long duration = toMilliseconds(parseDouble(matcher.group(4)));

            GoTestMethodResult result = new GoTestMethodResult(id,
                    methodName,
                    resultType,
                    duration,
                    0L,
                    message);
            if (TestResult.ResultType.FAILURE == resultType) {
                result.addFailure(message, message, message);
            }
            ret.add(result);

            startAndEnds.add(Pair.of(matcher.start(), matcher.end()));
        }

        for (int i = 0; i < ret.size(); ++i) {
            int thisMatchEndIndex = startAndEnds.get(i).getRight();
            int nextMatchStartIndex = i == ret.size() - 1 ? stdout.length() : startAndEnds.get(i + 1).getLeft();

            String middle = thisMatchEndIndex >= nextMatchStartIndex
                    ? ""
                    : stdout.substring(thisMatchEndIndex + 1, nextMatchStartIndex).trim();

            GoTestMethodResult methodResult = ret.get(i);
            methodResult.setMessage(methodResult.getMessage() + middle);
        }

        return ret;
    }

    private Map<File, String> loadTestFiles(List<File> testFiles) {
        return testFiles.
                stream()
                .collect(toMap(Function.identity(), IOUtils::toString));
    }

    private TestClassResult methodResultsToClassResult(String className,
                                                       List<GoTestMethodResult> methodResults) {
        TestClassResult ret = new TestClassResult(GLOBAL_COUNTER.incrementAndGet(), className, 0L);
        methodResults.forEach(ret::add);
        return ret;
    }

    @SuppressWarnings({"checkstyle:magicnumber"})
    private String determineClassName(String packagePath, String fileName) {
        String escapedPackagePath = IOUtils.encodeInternally(packagePath);
        escapedPackagePath = escapedPackagePath.replaceAll("\\.", "_DOT_");

        return escapedPackagePath.replaceAll("%2F", ".") + "." + fileName.replaceAll("\\.", "_DOT_");
    }

    private Map<File, List<GoTestMethodResult>> groupByTestFile(List<GoTestMethodResult> results,
                                                                Map<File, String> testFiles) {
        return results.stream()
                .collect(groupingBy(
                        result -> findTestFileOfMethod(testFiles, result.getName())
                ));
    }

    private File findTestFileOfMethod(Map<File, String> testFileContents, String methodName) {
        LOGGER.debug("trying to find {} in test files.");
        return testFileContents
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue().contains(methodName))
                .findFirst()
                .get()
                .getKey();
    }

    public static class GoTestMethodResult extends TestMethodResult {
        private String message;

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public GoTestMethodResult(long id,
                                  String name,
                                  TestResult.ResultType resultType,
                                  long duration,
                                  long endTime,
                                  String message) {
            super(id, name, resultType, duration, endTime);
            this.message = message;
        }
    }
}