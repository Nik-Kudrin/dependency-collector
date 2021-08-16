package data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
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
class DependencyMavenDataExtractor {
    private lateinit var cleanBaseDependenciesFile: File
    private lateinit var librariesFromMavenCentral: File

    private fun getDistinctPackages(packages: Iterable<String>): Set<String> {
        val groupedPackages = packages
            .groupBy(keySelector = { it.split(":").take(2).joinToString(separator = ":") },
                valueTransform = { it.split(":").last() }
            )

        val uniquePackages = groupedPackages.map { "${it.key}:${it.value.maxOrNull()}" }.toSet()
        return uniquePackages
    }

    private fun getRandomSymbol(): Char = LETTERS[Random.nextInt(LETTERS.indices)]

    private fun getResourcesBasePath(): Path {
        val resourceUrl = DependencyMavenDataExtractor::class.java.classLoader.getResource("Base_Dependencies_List.txt")
        return Path.of(resourceUrl.toString()).parent
    }

    @Test
    fun extractAndCleanupPackageDataFromCommonListOfPackages() {
        val stream =
            DependencyMavenDataExtractor::class.java.classLoader.getResourceAsStream("Base_Dependencies_List.txt")

        val rawPackages = stream.bufferedReader().readLines()
            .filter { it.contains("Maven: ") || it.contains("Gradle: ") }
            .map {
                it.removePrefix("implementation Maven:")
                    .removePrefix("Maven:")
                    .removePrefix("implementation Gradle:")
                    .removePrefix("Gradle:")
                    .trim()
            }

        val uniquePackages = getDistinctPackages(rawPackages)

//        println(uniquePackages.toString())
        cleanBaseDependenciesFile = File.createTempFile("Clean_Base_Dependencies_List", ".txt")

        BufferedWriter(FileWriter(cleanBaseDependenciesFile)).use { writer ->
            uniquePackages.forEach { writer.write(it + System.lineSeparator()) }
        }
    }

    @Test
    fun getMavenCentralPackagesList() {
        val client = HttpClient.newHttpClient()
        val packages = mutableSetOf<String>()

        val twoLetterCombination =
            LETTERS.flatMap { firstLetter -> LETTERS.map { secondLetter -> firstLetter.toString() + secondLetter } }

        val queries = LETTERS.map { it.toString() }.toMutableList().also { it.addAll(twoLetterCombination) }

        queries
            .forEachIndexed { index, query ->
                if (index % 10 == 0) // every N request
                    println("Parsing maven central search index: ${index * 100.0 / queries.size} %")

                val request =
                    HttpRequest.newBuilder(URI("https://search.maven.org/solrsearch/select?q=$query&rows=10000&wt=json"))
                        .GET()
                        .build()

//                    println("Request: ${request.uri().toString()}")

                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                val mavenSearchModel = jacksonObjectMapper().readValue<MavenSearchModel>(response.body())

                packages.addAll(mavenSearchModel.response.docs
                    .filter { it.repositoryId == "central" } // only from maven central
                    .map { "${it.id}:${it.latestVersion}" }.toSet()
                )
            }

//                println(packages.toString())
        librariesFromMavenCentral = File.createTempFile("Libraries_List_From_Maven_Central", ".txt")

        BufferedWriter(FileWriter(librariesFromMavenCentral)).use { writer ->
            packages.forEach { writer.write(it + System.lineSeparator()) }
        }

        println("Packages has been written to file: ${librariesFromMavenCentral.path}")
    }

    @Test
    fun mergePackages() {
        extractAndCleanupPackageDataFromCommonListOfPackages()
        getMavenCentralPackagesList()

        val baseDependencies = mutableSetOf<String>()
        val parsedDependencies = mutableSetOf<String>()

        BufferedReader(FileReader(cleanBaseDependenciesFile)).use {
            baseDependencies.addAll(it.readLines())
        }

        BufferedReader(FileReader(librariesFromMavenCentral)).use {
            parsedDependencies.addAll(it.readLines())
        }

        val uniquePackages = getDistinctPackages(baseDependencies.toMutableSet().also { it.addAll(parsedDependencies) })
            .toMutableSet()

        println("Raw packages count: ${uniquePackages.size}")

        val packagesDoesntExist = mutableSetOf<String>()
        uniquePackages.forEachIndexed { index, item ->
            if (index % 100 == 0) // every N request
                println("Checking package existence: ${index * 100.0 / uniquePackages.size} %")

            if (!checkIsDependencyAvailable(item)) {
//                println("Package $item doesn't exist")
                packagesDoesntExist.add(item)
            }
        }

        uniquePackages.removeAll(packagesDoesntExist)

        println("Count of packages, that weren't found ${packagesDoesntExist.size}. Packages, that exist: ${uniquePackages.size}")

        val notExistPackagesFile = File.createTempFile("Not_Existed_Packages", ".txt")
        BufferedWriter(FileWriter(notExistPackagesFile)).use { writer ->
            packagesDoesntExist.forEach { writer.write(it + System.lineSeparator()) }
        }
        println("Packages, that weren't found on Maven Central has been written to file: ${notExistPackagesFile.path}")


        val outputFile = File.createTempFile("Libraries_Final_List", ".txt")

        BufferedWriter(FileWriter(outputFile)).use { writer ->
            uniquePackages.shuffled().forEach { writer.write(it + System.lineSeparator()) }
        }

        println("List with final packages (that do exist) has been written to file: ${outputFile.path}")
    }

    @Test
    fun isDependencyAvailableTest() {
        val dependency = "fr.mastah.maven.plugin.m2e.jsdoc3:jsdoc3-m2e-site:1.0.1"
        Assertions.assertFalse(checkIsDependencyAvailable(dependency))
    }

    fun checkIsDependencyAvailable(dependencyFullName: String): Boolean {
        val parts = dependencyFullName.split(':')

        // fr.mastah.maven.plugin.m2e.jsdoc3
        val packagePrefix = parts.first()
        // jsdoc3-m2e-site
        val packageName = parts[1]
        // 1.0.1
        val version = parts.last()

        val urlStringBuilder = StringBuilder("https://repo.maven.apache.org/maven2")

        packagePrefix.split('.').forEach { urlStringBuilder.append("/$it") }
        urlStringBuilder
            .append("/$packageName")
            .append("/$version")
            .append("/$packageName-$version.jar")

        val artifactUrl = URL(urlStringBuilder.toString())
        return try {
            val responseCode = (artifactUrl.openConnection() as HttpURLConnection).responseCode
            when (responseCode) {
                200 -> true
                else -> false
            }

        } catch (e: Exception) {
            false
        }
    }
}
