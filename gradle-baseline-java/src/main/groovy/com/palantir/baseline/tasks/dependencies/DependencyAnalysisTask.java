/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.baseline.tasks.dependencies;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.paypal.digraph.parser.GraphParser;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.concurrent.ThreadSafe;
import org.apache.maven.shared.dependency.analyzer.ClassAnalyzer;
import org.apache.maven.shared.dependency.analyzer.DefaultClassAnalyzer;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;

/**
 * Produces report to optimize dependencies for a project.
 *
 * Parses dot files generated by jdeps and groups dependencies them into useful categories:
 * * required - those used by classes listed in the dot files
 * * api - those used in the API of the given classes.
 * * implicit - used but not declared in the given set of configurations.  Does not flag dependencies that are
 *      declared in a parent configuration.  e.g. If a project lists a dependency as an implementation dependency
 *      and then also uses that dependency in test classes, it does not have to declare the dependency in the
 *      testImplementation config as well.
 * * unused - declared but not used.  Unlike with the implicit dependency calculation, a dependency must be declared
 *      directly in the provided configurations in order for it to be considered unused.  This is to avoid the opposite
 *      of the situation for implicit dependencies - e.g. If a project includes a dependency as part of it's main
 *      configurations and then does not use it in test sources, it should not be reported as unused when reporting on
 *      the test sources.
 *
 */
@CacheableTask
public class DependencyAnalysisTask extends DefaultTask {
    static final ClassAnalyzer JAR_ANALYZER = new DefaultClassAnalyzer();
    static final ImmutableSet<String> VALID_ARTIFACT_EXTENSIONS = ImmutableSet.of("jar", "");

    private final SetProperty<Configuration> configurations;
    private final Property<Configuration> classpathConfiguration;
    private final DirectoryProperty dotFileDir;
    private final RegularFileProperty report;

    private final SetProperty<String> directDependencies;

    private final DependencyAnalysisTask.Indexes indexes = new Indexes();

    public DependencyAnalysisTask() {
        configurations = getProject().getObjects().setProperty(Configuration.class);
        configurations.convention(Collections.emptyList());
        classpathConfiguration = getProject().getObjects().property(Configuration.class);

        dotFileDir = getProject().getObjects().directoryProperty();

        report = getProject().getObjects().fileProperty();
        RegularFileProperty defaultReport = getProject().getObjects().fileProperty();
        defaultReport.set(
                getProject().file(String.format("%s/reports/%s-report.yaml", getProject().getBuildDir(), getName())));
        report.convention(defaultReport);

        directDependencies = getProject().getObjects().setProperty(String.class);
        directDependencies.set(configurations.map(configs -> {
            return configs.stream()
                    .map(DependencyUtils::getDirectDependencyNames)
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet());
        }));
    }

    @TaskAction
    public final void analyzeDependencies() {
        indexes.populateIndexes(classpathConfiguration.get(), getProject().getLogger());

        Set<String> allUsedDeps = findReferencedDependencies(DependencyUtils.findDetailedDotReport(dotFileDir.get()));
        Set<String> apiDeps = findReferencedDependencies(
                DependencyUtils.findDetailedDotReport(dotFileDir.get().dir("api")));
        Set<String> declaredDeps = configurations.get().stream()
                .map(c -> DependencyUtils.getDependencyNames(c, true))
                .flatMap(Collection::parallelStream)
                .collect(Collectors.toSet());

        Set<String> implicitDeps = Sets.difference(allUsedDeps, declaredDeps);
        // Only care about unused dependencies that are directly listed in the given configurations, not ones that
        // come from parent configurations.
        Set<String> unusedDeps = Sets.difference(declaredDeps, allUsedDeps).stream()
                .filter(a -> directDependencies.get().contains(a))
                .collect(Collectors.toSet());

        //clear the memory from the massive dependency map
        indexes.reset();

        ReportContent content = new ReportContent();
        content.allDependencies = sortDependencies(allUsedDeps);
        content.apiDependencies = sortDependencies(apiDeps);
        content.implicitDependencies = sortDependencies(implicitDeps);
        content.unusedDependencies = sortDependencies(unusedDeps);

        DependencyUtils.writeDepReport(getReportFile().getAsFile().get(), content);
    }

    private Set<String> findReferencedDependencies(Optional<File> dotFile) {
        return Streams.stream(dotFile)
                .flatMap(this::findReferencedClasses)
                .map(indexes::classToDependency)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toSet());
    }

    /**
     * Return all classes that are referenced (i.e. depended upon) by classes in the given dot file.
     */
    private Stream<String> findReferencedClasses(File dotFile) {
        try (InputStream input = new FileInputStream(dotFile)) {
            GraphParser parser = new GraphParser(input);
            return parser.getEdges()
                    .values()
                    .stream()
                    .map(e -> e.getNode2().getId())
                    .map(DependencyAnalysisTask::cleanNodeName);
        } catch (IOException e) {
            throw new RuntimeException("Unable to analyze " + dotFile, e);
        }
    }

    /**
     * Strips excess junk written by jdeps.
     */
    private static String cleanNodeName(String name) {
        return name.replaceAll(" \\([^)]*\\)", "").replace("\"", "");
    }

    private List<String> sortDependencies(Collection<String> dependencyIds) {
        return dependencyIds.stream()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Configurations whose dependencies will be analyzed.  Cannot list these as a full input property because the
     * caching calculations attempt to resolve the configurations at config time.  This leads to errors with configs
     * that cannot be resolved at configuration that time, notably implementation and api.  Caching and up-to-date
     * calcs are thus done via the directDependencyIds property.
     *
     */
    @Internal
    public SetProperty<Configuration> getConfigurations() {
        return configurations;
    }

    /**
     * Dependencies declared directly in the given configurations.  These normally come from the contents of
     * "dependencies" blocks in build.gradle files and are essentially what this tasks is validating.
     *
     * This is not set directly, but is derived from the passed configurations.  It is declared as an input property
     * so that up-to-date checks correctly detect when a configuration has changed in a way that requires this task
     * to be rerun.
     */
    @Input
    public Provider<Set<String>> getDirectDependencies() {
        return directDependencies;
    }

    /**
     * The configuration used to generate the compile classpath for the classes being analyzed.  This is normally
     * a superset of the configurations being analyzed.  This gives the most complete set of dependency artifacts to
     * search when looking up a class.  Using it also avoids some errors under Gradle 5.x because it can better access
     * classes in project-dependencies than the other configurations can.
     */
    @InputFiles
    @Classpath
    public Property<Configuration> getClasspathConfiguration() {
        return classpathConfiguration;
    }

    /**
     * Directory containing dot-file reports generated by the DependencyFinderTask.
     */
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    @SkipWhenEmpty
    public DirectoryProperty getDotFileDir() {
        return dotFileDir;
    }

    /**
     * The location of the generated report.
     */
    @OutputFile
    public RegularFileProperty getReportFile() {
        return report;
    }

    public static final class ReportContent {
        private List<String> allDependencies;
        private List<String> apiDependencies;
        private List<String> implicitDependencies;
        private List<String> unusedDependencies;

        /**
         * Full list of dependencies that are required by the project.
         */
        public List<String> getAllDependencies() {
            return allDependencies;
        }

        /**
         * Dependencies used in project's APIs - public or protected methods, public static methods, superclasses, etc.
         * See jdeps documentation for more info on what it considers to be in an API.
         */
        public List<String> getApiDependencies() {
            return apiDependencies;
        }

        /**
         * Dependencies that are required but are not directly declared by the project.
         */
        public List<String> getImplicitDependencies() {
            return implicitDependencies;
        }

        /**
         * Dependencies that are declared in one of the given configurations but not used in byte code.  This will only
         * report on dependencies that are directly listed by the given configurations.  It is possible that a
         * dependency is used in a source-only annotation or through some other generation tool.
         */
        public List<String> getUnusedDependencies() {
            return unusedDependencies;
        }

        public void setAllDependencies(List<String> allDependencies) {
            this.allDependencies = allDependencies;
        }

        public void setApiDependencies(List<String> apiDependencies) {
            this.apiDependencies = apiDependencies;
        }

        public void setImplicitDependencies(List<String> implicitDependencies) {
            this.implicitDependencies = implicitDependencies;
        }

        public void setUnusedDependencies(List<String> unusedDependencies) {
            this.unusedDependencies = unusedDependencies;
        }
    }

    @ThreadSafe
    private static final class Indexes {
        private final Map<String, String> classToDependency = new ConcurrentHashMap<>();

        public void populateIndexes(Configuration configuration, Logger logger) {
            Collection<ResolvedArtifact> allArtifacts = getResolvedArtifacts(configuration);

            allArtifacts.forEach(artifact -> {
                try {
                    File jar = artifact.getFile();

                    Set<String> classesInArtifact = JAR_ANALYZER.analyze(jar.toURI().toURL());
                    classesInArtifact.forEach(clazz -> classToDependency.put(clazz,
                            DependencyUtils.getDependencyName(artifact)));
                } catch (IOException e) {
                    logger.info("Could not get class from artifact: " + DependencyUtils.getDependencyName(artifact), e);
                }
            });
        }

        /** Given a class, returns what dependency brought it in if known. */
        public Optional<String> classToDependency(String clazz) {
            return Optional.ofNullable(classToDependency.get(clazz));
        }

        public void reset() {
            classToDependency.clear();
        }

        /**
         * All artifacts with valid extensions (i.e. jar) from the configuration dependencies, including
         * transitives.  Artifact identifiers are unique for hashing, so can return a set.
         *
         * TODO: Perhaps try to use Configuration.incoming.artifacts.artifacts rather than the old API
         */
        private Set<ResolvedArtifact> getResolvedArtifacts(Configuration configuration) {
            return configuration.getResolvedConfiguration().getFirstLevelModuleDependencies().stream()
                    .flatMap(dependency -> dependency.getAllModuleArtifacts().stream())
                    .filter(a -> VALID_ARTIFACT_EXTENSIONS.contains(a.getExtension()))
                    .collect(Collectors.toSet());
        }

    }
}