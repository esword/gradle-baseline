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

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

@CacheableTask
public class DependencyReportTask extends DefaultTask {
    private final ListProperty<Configuration> configurations;
    private final ListProperty<Configuration> sourceOnlyConfigurations;
    private final DirectoryProperty dotFileDir;
    private final RegularFileProperty report;

    public DependencyReportTask() {
        configurations = getProject().getObjects().listProperty(Configuration.class);
        configurations.convention(Collections.emptyList());
        sourceOnlyConfigurations = getProject().getObjects().listProperty(Configuration.class);
        sourceOnlyConfigurations.convention(Collections.emptyList());

        dotFileDir = getProject().getObjects().directoryProperty();

        report = getProject().getObjects().fileProperty();
        RegularFileProperty defaultReport = getProject().getObjects().fileProperty();
        defaultReport.set(
                getProject().file(String.format("%s/reports/%s-report.yaml", getProject().getBuildDir(), getName())));
        report.convention(defaultReport);
    }

    @TaskAction
    public final void analyzeDependencies() {
        DependencyAnalyzer analyzer = new DependencyAnalyzer(getProject(),
                configurations.get(),
                sourceOnlyConfigurations.get(),
                dotFileDir.get());

        ReportContent content = new ReportContent();
        content.allDependencies = artifactNames(analyzer.getAllRequiredArtifacts());
        content.apiDependencies = artifactNames(analyzer.getApiArtifacts());
        content.implicitDependencies = artifactNames(analyzer.getImplicitDependencies());
        content.unusedDependencies = artifactNames(analyzer.getUnusedDependencies());

        DependencyUtils.writeDepReport(getReportFile().getAsFile().get(), content);

    }

    private List<String> artifactNames(Collection<ResolvedArtifact> artifacts) {
        return artifacts.stream()
                .map(DependencyUtils::getDependencyName)
                .sorted()
                .collect(Collectors.toList());
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public final ListProperty<Configuration> getConfigurations() {
        return configurations;
    }

    /**
     * Directory containing dot-file reports.
     */
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public final DirectoryProperty getDotFileDir() {
        return dotFileDir;
    }

    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public final ListProperty<Configuration> getSourceOnlyConfigurations() {
        return sourceOnlyConfigurations;
    }

    @OutputFile
    public final RegularFileProperty getReportFile() {
        return report;
    }

    public static final class ReportContent {
        private List<String> allDependencies;
        private List<String> apiDependencies;
        private List<String> implicitDependencies;
        private List<String> unusedDependencies;

        public List<String> getAllDependencies() {
            return allDependencies;
        }

        public List<String> getApiDependencies() {
            return apiDependencies;
        }

        public List<String> getImplicitDependencies() {
            return implicitDependencies;
        }

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
}
