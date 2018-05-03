/*
 * Copyright (c) 2017-2018 HERE Europe B.V.
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

package com.here.ort.scanner.scanners

import com.here.ort.model.Provenance
import com.here.ort.model.ScanResult
import com.here.ort.model.ScanSummary
import com.here.ort.model.ScannerDetails
import com.here.ort.model.jsonMapper
import com.here.ort.scanner.LocalScanner

import java.io.File
import java.time.Instant

/**
 * A simple [LocalScanner] that only counts the files in the scan path. Because it is much faster than the other
 * scanners it is useful for testing the scanner tool, for example during development or when integrating it with other
 * tools.
 */
object FileCounter : LocalScanner() {
    data class FileCountResult(val fileCount: Int)

    override val resultFileExt = "json"
    override val scannerExe = ""
    override val scannerVersion = "1.0"

    override fun getConfiguration() = ""

    override fun getVersion(dir: File) = scannerVersion

    override fun scanPath(path: File, resultsFile: File, provenance: Provenance, scannerDetails: ScannerDetails)
            : ScanResult {
        val startTime = Instant.now()
        val result = FileCountResult(path.walk().count())
        val endTime = Instant.now()

        val json = jsonMapper.writeValueAsString(result)
        resultsFile.writeText(json)

        val summary = ScanSummary(startTime, endTime, result.fileCount, sortedSetOf(), sortedSetOf())
        val rawResult = jsonMapper.readTree(json)

        return ScanResult(provenance, scannerDetails, summary, rawResult)
    }

    override fun getResult(resultsFile: File): Result {
        val result = jsonMapper.readValue(resultsFile, FileCountResult::class.java)
        return Result(result.fileCount, sortedSetOf(), sortedSetOf(), jsonMapper.readTree(resultsFile))
    }
}