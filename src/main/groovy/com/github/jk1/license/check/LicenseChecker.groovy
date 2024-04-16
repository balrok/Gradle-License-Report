/*
 * Copyright 2018 Evgeny Naumenko <jk.vc@mail.ru>
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
package com.github.jk1.license.check

import groovy.json.JsonOutput
import org.gradle.api.GradleException

/**
 * This class compares the found licences with the allowed licenses and creates a report for any missing license
 */
class LicenseChecker {
    static void checkAllDependencyLicensesAreAllowed(
            Object allowedLicensesFile,
            File projectLicensesDataFile,
            boolean requireAllLicensesAllowed,
            File notPassedDependenciesOutputFile) {
        List<Dependency> allDependencies = LicenseCheckerFileReader.importDependencies(projectLicensesDataFile)
        removeNullLicenses(allDependencies)
        List<AllowedLicense> allowedLicenses = LicenseCheckerFileReader.importAllowedLicenses(allowedLicensesFile)
        List<Tuple2<Dependency, List<ModuleLicense>>> notPassedDependencies = getNotAllowedLicenses(allDependencies, allowedLicenses)
        if (!requireAllLicensesAllowed) {
            // when we do not check for all Licenses allowed, we can filter out all dependencies here which had a partial match:
            // this means, when the size of notPassedLicenses differs, at least one license matched with our allowed-list
            notPassedDependencies = notPassedDependencies.findAll { it.get(0).moduleLicenses == null || it.get(1).size() == it.get(0).moduleLicenses.size() }
        }
        generateNotPassedDependenciesFile(notPassedDependencies, notPassedDependenciesOutputFile)

        if (!notPassedDependencies.isEmpty()) {
            throw new GradleException("Some library licenses are not allowed:\n" +
                    "$notPassedDependenciesOutputFile.text\n\n" +
                    "Read [$notPassedDependenciesOutputFile.path] for more information.")
        }
    }

    /**
     * removes 'null'-licenses from dependencies which have at least one more license
     */
    private static void removeNullLicenses(List<Dependency> dependencies) {
        for (Dependency dependency : dependencies) {
            if (dependency.moduleLicenses.any { it.moduleLicense == null } && !dependency.moduleLicenses.every { it.moduleLicense == null }) {
                dependency.moduleLicenses = dependency.moduleLicenses.findAll { it.moduleLicense != null }
            }
        }
    }

    private static List<Tuple2<Dependency, List<ModuleLicense>>> getNotAllowedLicenses(List<Dependency> dependencies, List<AllowedLicense> allowedLicenses) {
        List<Tuple2<Dependency, List<ModuleLicense>>> result = new ArrayList<>()
        for (Dependency dependency : dependencies) {
            List<AllowedLicense> perDependencyAllowedLicenses = allowedLicenses.findAll { isDependencyNameMatchesAllowedLicense(dependency, it) && isDependencyVersionMatchesAllowedLicense(dependency, it) }
            // allowedLicense matches anything, so we don't need to further check
            if (perDependencyAllowedLicenses.any { it.moduleLicense == null || it.moduleLicense == ".*" }) {
                continue
            }
            def notAllowedLicenses = dependency.moduleLicenses.findAll { !isDependencyLicenseMatchesAllowedLicense(it, perDependencyAllowedLicenses) }
            if (!notAllowedLicenses.isEmpty()) {
                result.add(Tuple2.of(dependency, notAllowedLicenses))
            }
        }
        return result
    }

    private static void generateNotPassedDependenciesFile(
            List<Tuple2<Dependency, List<ModuleLicense>>> notPassedDependencies, File notPassedDependenciesOutputFile) {
        notPassedDependenciesOutputFile.text =
                JsonOutput.prettyPrint(JsonOutput.toJson(
                        ["dependenciesWithoutAllowedLicenses": notPassedDependencies.collect { toAllowedLicenseList(it.get(0), it.get(1)) }.flatten()]))
    }

    private static boolean isDependencyNameMatchesAllowedLicense(Dependency dependency, AllowedLicense allowedLicense) {
        return dependency.moduleName ==~ allowedLicense.moduleName || allowedLicense.moduleName == null ||
                dependency.moduleName == allowedLicense.moduleName
    }

    private static boolean isDependencyVersionMatchesAllowedLicense(Dependency dependency, AllowedLicense allowedLicense) {
        return dependency.moduleVersion ==~ allowedLicense.moduleVersion || allowedLicense.moduleVersion == null ||
                dependency.moduleVersion == allowedLicense.moduleVersion
    }

    private static boolean isDependencyLicenseMatchesAllowedLicense(ModuleLicense moduleLicense, List<AllowedLicense> allowedLicenses) {
        for (AllowedLicense allowedLicense : allowedLicenses) {
            if (allowedLicense.moduleLicense == null || allowedLicense.moduleLicense == ".*") return true

            if (moduleLicense.moduleLicense ==~ allowedLicense.moduleLicense ||
                    moduleLicense.moduleLicense == allowedLicense.moduleLicense) return true
        }
        return false
    }

    private static List<AllowedLicense> toAllowedLicenseList(Dependency dependency, List<ModuleLicense> moduleLicenses) {
        if (moduleLicenses.isEmpty()) {
            return [new AllowedLicense(dependency.moduleName, dependency.moduleVersion, null)]
        } else {
            return moduleLicenses.findAll { it }.collect { new AllowedLicense(dependency.moduleName, dependency.moduleVersion, it.moduleLicense) }
        }
    }
}
