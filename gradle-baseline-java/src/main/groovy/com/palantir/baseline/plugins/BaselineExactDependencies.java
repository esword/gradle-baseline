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

package com.palantir.baseline.plugins;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.palantir.baseline.tasks.CheckImplicitDependenciesTask;
import com.palantir.baseline.tasks.CheckUnusedDependenciesTask;
import com.palantir.baseline.tasks.dependencies.DependencyFinderTask;
import com.palantir.baseline.tasks.dependencies.DependencyReportTask;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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
import org.apache.maven.shared.dependency.analyzer.DependencyAnalyzer;
import org.apache.maven.shared.dependency.analyzer.asm.ASMDependencyAnalyzer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;

/** Validates that java projects declare exactly the dependencies they rely on, no more and no less. */
public final class BaselineExactDependencies implements Plugin<Project> {

    private static final ClassAnalyzer JAR_ANALYZER = new DefaultClassAnalyzer();
    private static final DependencyAnalyzer CLASS_FILE_ANALYZER = new ASMDependencyAnalyzer();

    // All applications of this plugin share a single static 'Indexes' instance, because the classes
    // contained in a particular jar are immutable.
    public static final Indexes INDEXES = new Indexes();
    public static final ImmutableSet<String> VALID_ARTIFACT_EXTENSIONS = ImmutableSet.of("jar", "");
    public static final String GROUP_NAME = "Dependency Analysis";

    @Override
    public void apply(Project project) {
        project.getPluginManager().withPlugin("java", plugin -> {
            FileCollection mainSourceSetClasses = project.getConvention()
                    .getPlugin(JavaPluginConvention.class)
                    .getSourceSets()
                    .getByName(SourceSet.MAIN_SOURCE_SET_NAME)
                    .getOutput()
                    .getClassesDirs();
            Configuration compileClasspath = project.getConfigurations()
                    .getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME);
            Configuration compileOnlyClasspath = project.getConfigurations()
                    .getByName(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME);
            Configuration annotationProcessorClasspath = project.getConfigurations()
                    .getByName(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME);


            List<Configuration> classConfigs = new ArrayList<>();
            classConfigs.add(compileClasspath);
            if (annotationProcessorClasspath != null) {
                classConfigs.add(annotationProcessorClasspath);
            }

            TaskProvider<DependencyFinderTask> findMainDepsTask = project.getTasks()
                    .register("findMainDeps", DependencyFinderTask.class, t -> {
                        t.dependsOn(JavaPlugin.CLASSES_TASK_NAME);
                        t.setDescription(
                                "Produces a dot file with dependencies that are directly used by main sources "
                                        + "in this project");
                        t.setGroup(GROUP_NAME);
                        t.getSourceClasses().set(mainSourceSetClasses.getSingleFile());
                    });

            TaskProvider<DependencyFinderTask> findMainApiDepsTask = project.getTasks()
                    .register("findMainApiDeps", DependencyFinderTask.class, t -> {
                        t.dependsOn(JavaPlugin.CLASSES_TASK_NAME);
                        t.setDescription(
                                "Produces a dot file with API dependencies of the main sources in this project");
                        t.setGroup(GROUP_NAME);
                        t.getSourceClasses().set(mainSourceSetClasses.getSingleFile());
                        t.getApiOnly().set(true);
                    });

            TaskProvider<DependencyReportTask> mainAnalyzerTask = project.getTasks()
                    .register("analyzeMainDeps", DependencyReportTask.class, t -> {
                        t.setDescription(
                                "Produces a yml report for dependencies that are directly used by this project");
                        t.setGroup(GROUP_NAME);
                        t.getFullDepFiles().from(findMainDepsTask.get().getReportFile());
                        t.getApiDepFiles().from(findMainApiDepsTask.get().getReportFile());
                        t.getConfigurations().addAll(classConfigs);
                    });

            project.getTasks().create("checkUnusedDependencies", CheckUnusedDependenciesTask.class, task -> {
                task.dependsOn(JavaPlugin.CLASSES_TASK_NAME);
                task.setSourceClasses(mainSourceSetClasses);
                task.dependenciesConfiguration(compileClasspath);
                task.sourceOnlyConfiguration(compileOnlyClasspath);
                task.sourceOnlyConfiguration(annotationProcessorClasspath);

                // this is liberally applied to ease the Java8 -> 11 transition
                task.ignore("javax.annotation", "javax.annotation-api");
            });

            project.getTasks().create("checkImplicitDependencies", CheckImplicitDependenciesTask.class, task -> {
                task.dependsOn(JavaPlugin.CLASSES_TASK_NAME);
                task.setSourceClasses(mainSourceSetClasses);
                task.dependenciesConfiguration(compileClasspath);

                task.ignore("org.slf4j", "slf4j-api");
            });
        });
    }

    /** Given a {@code com/palantir/product/Foo.class} file, what other classes does it import/reference. */
    public static Stream<String> referencedClasses(File classFile) {
        try {
            return BaselineExactDependencies.CLASS_FILE_ANALYZER.analyze(classFile.toURI().toURL()).stream();
        } catch (IOException e) {
            throw new RuntimeException("Unable to analyze " + classFile, e);
        }
    }

    public static String asString(ResolvedArtifact artifact) {
        return asString(artifact.getModuleVersion().getId());
    }

    public static String asString(ModuleVersionIdentifier id) {
        return id.getGroup() + ":" + id.getName();
    }

    @ThreadSafe
    public static final class Indexes {
        private final Map<String, ResolvedArtifact> classToDependency = new ConcurrentHashMap<>();
        private final Map<ResolvedArtifact, Set<String>> classesFromArtifact = new ConcurrentHashMap<>();
        private final Map<ResolvedArtifact, ResolvedDependency> artifactsFromDependency = new ConcurrentHashMap<>();

        public void populateIndexes(Set<ResolvedDependency> declaredDependencies) {
            Set<ResolvedArtifact> allArtifacts = declaredDependencies.stream()
                    .flatMap(dependency -> dependency.getAllModuleArtifacts().stream())
                    .filter(dependency -> VALID_ARTIFACT_EXTENSIONS.contains(dependency.getExtension()))
                    .collect(Collectors.toSet());

            allArtifacts.forEach(artifact -> {
                try {
                    File jar = artifact.getFile();
                    Set<String> classesInArtifact = JAR_ANALYZER.analyze(jar.toURI().toURL());
                    classesFromArtifact.put(artifact, classesInArtifact);
                    classesInArtifact.forEach(clazz -> classToDependency.put(clazz, artifact));
                } catch (IOException e) {
                    throw new RuntimeException("Unable to analyze artifact", e);
                }
            });

            declaredDependencies.forEach(dependency -> dependency.getModuleArtifacts()
                    .forEach(artifact -> artifactsFromDependency.put(artifact, dependency)));
        }

        /** Given a class, what dependency brought it in. */
        public Optional<ResolvedArtifact> classToDependency(String clazz) {
            return Optional.ofNullable(classToDependency.get(clazz));
        }

        /** Given an artifact, what classes does it contain. */
        public Stream<String> classesFromArtifact(ResolvedArtifact resolvedArtifact) {
            return Preconditions.checkNotNull(
                    classesFromArtifact.get(resolvedArtifact),
                    "Unable to find resolved artifact").stream();
        }

        public ResolvedDependency artifactsFromDependency(ResolvedArtifact resolvedArtifact) {
            return Preconditions.checkNotNull(
                    artifactsFromDependency.get(resolvedArtifact),
                    "Unable to find resolved artifact");
        }
    }
}
