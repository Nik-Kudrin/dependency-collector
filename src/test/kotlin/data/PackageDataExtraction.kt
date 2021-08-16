package com.intellij.startupTime.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import data.ImportGenerator
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.FileReader
import java.io.FileWriter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.random.Random
import kotlin.random.nextInt

private val LETTERS = "abcdefghijklmnopqrstuvwxyz"

@JsonIgnoreProperties(ignoreUnknown = true)
private data class MavenSearchResponseDoc(
    val id: String,
    val latestVersion: String,
    val repositoryId: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class MavenSearchResponse(
    val docs: List<MavenSearchResponseDoc>
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class MavenSearchModel(
    val response: MavenSearchResponse
)

/**
 * DO NOT RUN THESE TESTS ON CI. ONLY FOR LOCAL DATA PREPARATION
 */
class PackageDataExtraction {

    private fun distinctPackages(packages: Iterable<String>): Set<String> {
        val groupedPackages = packages
            .groupBy(keySelector = { it.split(":").take(2).joinToString(separator = ":") },
                valueTransform = { it.split(":").last() }
            )

        val uniquePackages = groupedPackages.map { "${it.key}:${it.value.maxOrNull()}" }.toSet()
        return uniquePackages
    }

    private fun getRandomSymbol(): Char = LETTERS[Random.nextInt(LETTERS.indices)]

    @Test
    fun extractAndClenupPackageDataFromCommonListOfPackages() {
        var stream = ImportGenerator::class.java.classLoader.getResourceAsStream("Base_Dependencies_List.txt")

        val rawPackages = stream.bufferedReader().readLines()
            .filter { it.contains("Maven: ") || it.contains("Gradle: ") }
            .map {
                it.removePrefix("implementation Maven:")
                    .removePrefix("Maven:")
                    .removePrefix("implementation Gradle:")
                    .removePrefix("Gradle:")
                    .trim()
            }

        val uniquePackages = distinctPackages(rawPackages)

        println(uniquePackages.toString())
        BufferedWriter(FileWriter("/home/neon/Downloads/Libraries_List.txt")).use { writer ->
            uniquePackages.forEach { writer.write(it + System.lineSeparator()) }
        }
    }

    @Test
    fun getMavenCentralPackagesList() {
        val client = HttpClient.newHttpClient()
        val packages = mutableSetOf<String>()

        val twoLetterCombination =
            LETTERS.flatMap { firstLetter -> LETTERS.map { secondLetter -> firstLetter.toString() + secondLetter } }

        LETTERS.map { it.toString() }.toMutableList().apply {
            addAll(twoLetterCombination)

            forEach { query ->
                val request =
                    HttpRequest.newBuilder(URI("https://search.maven.org/solrsearch/select?q=$query&rows=10000&wt=json"))
                        .GET()
                        .build()

                println("Request: ${request.uri().toString()}")

                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                val mavenSearchModel = jacksonObjectMapper().readValue<MavenSearchModel>(response.body())

                packages.addAll(mavenSearchModel.response.docs
                    .filter { it.repositoryId == "central" } // only from maven central
                    .map { "${it.id}:${it.latestVersion}" }.toSet()
                )
            }

            println(packages.toString())

            BufferedWriter(FileWriter("/home/neon/Downloads/Libraries_List_From_Maven_Central.txt")).use { writer ->
                packages.forEach { writer.write(it + System.lineSeparator()) }
            }
        }
    }

    @Test
    fun mergePackages() {
        val packages = mutableSetOf<String>()

        BufferedReader(FileReader("/home/neon/Downloads/Libraries_List_From_Maven_Central.txt")).use {
            packages.addAll(it.readLines())
        }

        BufferedReader(FileReader("/home/neon/Downloads/Libraries_List.txt")).use {
            packages.addAll(it.readLines())
        }

        val uniquePackages = distinctPackages(packages)

        BufferedWriter(FileWriter("/home/neon/Downloads/Libraries_Final_List.txt")).use { writer ->
            uniquePackages.forEach { writer.write(it + System.lineSeparator()) }
        }
    }
}
