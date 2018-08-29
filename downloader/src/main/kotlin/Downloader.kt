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

package com.here.ort.downloader

import ch.frankel.slf4k.*

import com.here.ort.downloader.vcs.GitRepo
import com.here.ort.model.HashAlgorithm
import com.here.ort.model.Package
import com.here.ort.model.Project
import com.here.ort.model.RemoteArtifact
import com.here.ort.model.VcsInfo
import com.here.ort.utils.OkHttpClientHelper
import com.here.ort.utils.collectMessages
import com.here.ort.utils.log
import com.here.ort.utils.safeDeleteRecursively
import com.here.ort.utils.safeMkdirs
import com.here.ort.utils.unpack

import okhttp3.Request

import okio.Okio

import org.apache.commons.codec.digest.DigestUtils

import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.SortedSet

const val TOOL_NAME = "downloader"
const val HTTP_CACHE_PATH = "$TOOL_NAME/cache/http"

/**
 * The class to download source code. The signatures of public functions in this class define the library API.
 */
class Downloader {
    /**
     * The choice of data entities to download.
     */
    enum class DataEntity {
        PACKAGES,
        PROJECT;
    }

    /**
     * This class describes what was downloaded by [download] to the [downloadDirectory] or if any exception occured.
     * Either [sourceArtifact] or [vcsInfo] is set to a non-null value. The download was started at [dateTime].
     */
    data class DownloadResult(
            val dateTime: Instant,
            val downloadDirectory: File,
            val sourceArtifact: RemoteArtifact? = null,
            val vcsInfo: VcsInfo? = null,
            val originalVcsInfo: VcsInfo? = null
    ) {
        init {
            require((sourceArtifact == null) != (vcsInfo == null)) {
                "Either sourceArtifact or vcsInfo must be set, but not both."
            }
        }
    }

    /**
     * Consolidate projects based on their VcsInfo without taking the path into account. As we store VcsInfo per project
     * but many project definition files actually reside in different sub-directories of the same VCS working tree, it
     * does not make sense to download (and scan) all of them individually, not even if doing sparse checkouts.
     *
     * @param projects A set of projects to consolidate into packages.
     * @return A map that associates packages for projects with distinct VCS working trees to all other projects from
     *         the same VCS working tree.
     */
    fun consolidateProjectPackagesByVcs(projects: SortedSet<Project>): Map<Package, List<Package>> {
        // TODO: In case of GitRepo, we still download the whole GitRepo working tree *and* any individual
        // Git repositories that contain project definition files, which in many cases is doing duplicate
        // work.
        val projectPackages = projects.map { it.toPackage() }
        val projectPackagesByVcs = projectPackages.groupBy {
            if (it.vcsProcessed.type == GitRepo().toString()) {
                it.vcsProcessed
            } else {
                it.vcsProcessed.copy(path = "")
            }
        }

        return projectPackagesByVcs.entries.associate { (sameVcs, projectsWithSameVcs) ->
            // Find the original project which has the empty path, if any, or simply take the first project
            // and clear the path unless it is a GitRepo project (where the path refers to the manifest).
            val referencePackage = projectsWithSameVcs.find { it.vcsProcessed.path.isEmpty() }
                    ?: projectsWithSameVcs.first()

            val otherPackages = (projectsWithSameVcs - referencePackage).map { it.copy(vcsProcessed = sameVcs) }

            Pair(referencePackage.copy(vcsProcessed = sameVcs), otherPackages)
        }
    }

    /**
     * Download the source code of the [target] package to a folder inside [outputDirectory]. The folder name is created
     * from the [name][Identifier.name] and [version][Identifier.version] of the [target] package [id][Package.id].
     *
     * @param target The description of the package to download.
     * @param outputDirectory The parent directory to download the source code to.
     * @param allowMovingRevisions Indicate whether VCS downloads may use symbolic names to moving revisions.
     *
     * @return The [DownloadResult].
     *
     * @throws DownloadException In case the download failed.
     */
    fun download(target: Package, outputDirectory: File, allowMovingRevisions: Boolean = false): DownloadResult {
        log.info { "Trying to download source code for '${target.id}'." }

        val targetDir = File(outputDirectory, target.id.toPath()).apply { safeMkdirs() }

        try {
            if (target.vcsProcessed.url.isBlank()) {
                throw DownloadException("No VCS URL provided for '${target.id}'.")
            } else {
                return downloadFromVcs(target, targetDir, allowMovingRevisions)
            }
        } catch (vcsDownloadException: DownloadException) {
            log.debug { "VCS download failed for '${target.id}': ${vcsDownloadException.message}" }

            // Clean up any files left from the failed VCS download (i.e. a ".git" directory).
            targetDir.safeDeleteRecursively()
            targetDir.safeMkdirs()

            try {
                return downloadSourceArtifact(target, targetDir)
            } catch (sourceDownloadException: DownloadException) {
                if (sourceDownloadException.cause != null) {
                    throw sourceDownloadException
                } else {
                    throw sourceDownloadException.initCause(vcsDownloadException)
                }
            }
        }
    }

    private fun downloadFromVcs(target: Package, outputDirectory: File, allowMovingRevisions: Boolean): DownloadResult {
        log.info {
            "Trying to download '${target.id}' sources to '${outputDirectory.absolutePath}' from VCS..."
        }

        if (target.vcsProcessed != target.vcs) {
            log.info { "Using processed ${target.vcsProcessed}. Original was ${target.vcs}." }
        } else {
            log.info { "Using ${target.vcsProcessed}." }
        }

        var applicableVcs: VersionControlSystem? = null

        if (target.vcsProcessed.type.isNotBlank()) {
            applicableVcs = VersionControlSystem.forType(target.vcsProcessed.type)
            log.info {
                if (applicableVcs != null) {
                    "Detected VCS type '$applicableVcs' from type name '${target.vcsProcessed.type}'."
                } else {
                    "Could not detect VCS type from type name '${target.vcsProcessed.type}'."
                }
            }
        }

        if (applicableVcs == null) {
            applicableVcs = VersionControlSystem.forUrl(target.vcsProcessed.url)
            log.info {
                if (applicableVcs != null) {
                    "Detected VCS type '$applicableVcs' from URL '${target.vcsProcessed.url}'."
                } else {
                    "Could not detect VCS type from URL '${target.vcsProcessed.url}'."
                }
            }
        }

        if (applicableVcs == null) {
            throw DownloadException("Unsupported VCS type '${target.vcsProcessed.type}'.")
        }

        val startTime = Instant.now()
        val workingTree = applicableVcs.download(target, outputDirectory, allowMovingRevisions)
        val revision = workingTree.getRevision()

        log.info { "Finished downloading source code revision '$revision' to '${outputDirectory.absolutePath}'." }

        val vcsInfo = VcsInfo(
                type = applicableVcs.toString(),
                url = target.vcsProcessed.url,
                revision = target.vcsProcessed.revision.takeIf { it.isNotBlank() } ?: revision,
                resolvedRevision = revision,
                path = target.vcsProcessed.path // TODO: Needs to check if the VCS used the sparse checkout.
        )
        return DownloadResult(startTime, outputDirectory, vcsInfo = vcsInfo,
                originalVcsInfo = target.vcsProcessed.takeIf { it != vcsInfo })
    }

    private fun downloadSourceArtifact(target: Package, outputDirectory: File): DownloadResult {
        if (target.sourceArtifact.url.isBlank()) {
            throw DownloadException("No source artifact URL provided for '${target.id}'.")
        }

        log.info {
            "Trying to download source artifact for '${target.id}' from '${target.sourceArtifact.url}'..."
        }

        val request = Request.Builder()
                // Disable transparent gzip, otherwise we might end up writing a tar file to disk and expecting to find
                // a tar.gz file, thus failing to unpack the archive.
                // See https://github.com/square/okhttp/blob/parent-3.10.0/okhttp/src/main/java/okhttp3/internal/ \
                // http/BridgeInterceptor.java#L79
                .addHeader("Accept-Encoding", "identity")
                .get()
                .url(target.sourceArtifact.url)
                .build()

        val startTime = Instant.now()
        val response = try {
            OkHttpClientHelper.execute(HTTP_CACHE_PATH, request)
        } catch (e: IOException) {
            throw DownloadException("Failed to download source artifact: ${e.collectMessages()}", e)
        }

        val body = response.body()
        if (!response.isSuccessful || body == null) {
            throw DownloadException("Failed to download source artifact: $response")
        }

        val sourceArchive = createTempFile(suffix = target.sourceArtifact.url.substringAfterLast("/"))
        Okio.buffer(Okio.sink(sourceArchive)).use { it.writeAll(body.source()) }

        verifyChecksum(sourceArchive, target.sourceArtifact.hash, target.sourceArtifact.hashAlgorithm)

        try {
            if (sourceArchive.extension == "gem") {
                // Unpack the nested data archive for Ruby Gems.
                val gemDirectory = createTempDir()
                val dataFile = File(gemDirectory, "data.tar.gz")

                try {
                    sourceArchive.unpack(gemDirectory)
                    dataFile.unpack(outputDirectory)
                } finally {
                    if (!gemDirectory.deleteRecursively()) {
                        log.warn { "Unable to delete temporary directory '$gemDirectory'." }
                    }
                }
            } else {
                sourceArchive.unpack(outputDirectory)
            }
        } catch (e: IOException) {
            log.error { "Could not unpack source artifact '${sourceArchive.absolutePath}': ${e.message}" }
            throw DownloadException(e)
        } finally {
            if (!sourceArchive.delete()) {
                log.warn { "Unable to delete temporary file '$sourceArchive'." }
            }
        }

        log.info {
            "Successfully downloaded source artifact for '${target.id}' to '${outputDirectory.absolutePath}'..."
        }

        return DownloadResult(startTime, outputDirectory, sourceArtifact = target.sourceArtifact)
    }

    private fun verifyChecksum(file: File, hash: String, hashAlgorithm: HashAlgorithm) {
        val digest = file.inputStream().use {
            when (hashAlgorithm) {
                HashAlgorithm.MD2 -> DigestUtils.md2Hex(it)
                HashAlgorithm.MD5 -> DigestUtils.md5Hex(it)
                HashAlgorithm.SHA1 -> DigestUtils.sha1Hex(it)
                HashAlgorithm.SHA256 -> DigestUtils.sha256Hex(it)
                HashAlgorithm.SHA384 -> DigestUtils.sha384Hex(it)
                HashAlgorithm.SHA512 -> DigestUtils.sha512Hex(it)
                HashAlgorithm.UNKNOWN -> {
                    log.warn { "Unknown hash algorithm." }
                    ""
                }
            }
        }

        if (digest != hash) {
            throw DownloadException("Calculated $hashAlgorithm hash '$digest' differs from expected hash '$hash'.")
        }
    }
}