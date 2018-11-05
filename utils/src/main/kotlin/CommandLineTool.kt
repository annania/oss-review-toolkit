/*
 * Copyright (C) 2017-2018 HERE Europe B.V.
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
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.utils

import ch.frankel.slf4k.*

import com.vdurmont.semver4j.Requirement
import com.vdurmont.semver4j.Semver

import java.io.File
import java.io.IOException

abstract class CommandLineTool(val name: String) {
    companion object {
        val ANY_VERSION = Semver("*", Semver.SemverType.NPM)
    }

    abstract val executable: String

    open val mandatoryArguments = emptyList<String>()

    /**
     * Check whether the executable for this command is available in the system PATH.
     */
    //val isInPath: Boolean
    //    get() = getPathFromEnvironment(executable) != null

    /**
     * Run this command with arguments [args] in the [workingDir] directory and the given [environment] variables. If
     * specified, use the [pathToExecutable] instead of looking it up in the PATH environment.
     */
    fun run(vararg args: String, workingDir: File? = null, environment: Map<String, String> = emptyMap(),
            pathToExecutable: File? = null): ProcessCapture {
        val pathToUse = pathToExecutable ?: run {
            val pathFromEnvironment = getPathFromEnvironment(executable)
            if (pathFromEnvironment != null &&
                    requiredVersion.isSatisfiedBy(getVersion(commandDir = pathFromEnvironment.parentFile))) {
                pathFromEnvironment.parentFile
            } else {
                bootstrappedPath ?: run {
                    log.info { "Bootstrapping $name..." }
                    bootstrap().also { bootstrappedPath = it }
                }
            }
        }

        val resolvedPath = pathToUse.resolve(executable).absolutePath
        return ProcessCapture(resolvedPath, *args, workingDir = workingDir, environment = environment)
    }

    /**
     * Run this command in the [workingDir] directory with arguments [args].
     */
    fun run(workingDir: File?, vararg args: String) = run(*args, workingDir = workingDir)

    open val versionArguments: String = "--version"

    open fun transformVersion(output: String) = output

    fun getVersion(workingDir: File? = null, commandDir: File? = null): Semver {
        val version = run(*versionArguments.split(' ').toTypedArray(), workingDir = workingDir, pathToExecutable = commandDir)

        // Some tools actually report the version to stderr, so try that as a fallback.
        val versionString = sequenceOf(version.stdout, version.stderr).map {
            transformVersion(it.trim())
        }.find {
            it.isNotBlank()
        }

        return Semver(versionString ?: "", Semver.SemverType.LOOSE)
    }

    abstract val preferredVersion: Semver

    open val requiredVersion by lazy { Requirement.build(preferredVersion)!! }

    /**
     * Run a [command] to check its version against the [required version][getVersionRequirement].
     */
    fun checkVersion(workingDir: File? = null, commandDir: File? = null, ignoreActualVersion: Boolean = false) {
        val actualVersion = getVersion(workingDir, commandDir)

        if (!requiredVersion.isSatisfiedBy(actualVersion)) {
            val message = "Unsupported $name version $actualVersion does not fulfill $requiredVersion."
            if (ignoreActualVersion) {
                log.warn { "$message Still continuing because you chose to ignore the actual version." }
            } else {
                throw IOException(message)
            }
        }
    }

    open val canBootstrap = false

    private var bootstrappedPath: File? = null

    open fun bootstrap(): File = throw NotImplementedError()
}
