package data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

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
    private val countOfExceptionThreshold = 3
    private var countOfHttpException: Int = 0

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

        writeIterableToFile(uniquePackages, cleanBaseDependenciesFile)
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
        writeIterableToFile(packages, librariesFromMavenCentral)

        println("Packages has been written to file: ${librariesFromMavenCentral.path}")
    }

    private fun readFileToSet(filePath: File): Set<String> = readFileToSet(filePath.path)

    private fun readFileToSet(filePath: String): Set<String> {
        BufferedReader(FileReader(filePath)).use {
            return it.readLines().toSet()
        }
    }

    private fun writeIterableToFile(iterable: Iterable<String>, file: File) {
        if (file.exists()) {
            file.delete()
            file.createNewFile()
        }

        BufferedWriter(FileWriter(file)).use { writer ->
            iterable.forEach { writer.write(it + System.lineSeparator()) }
        }
    }

    @ExperimentalTime
    @Test
    fun mergePackagesCached() {
        //        extractAndCleanupPackageDataFromCommonListOfPackages()
//        getMavenCentralPackagesList()

        cleanBaseDependenciesFile = Path.of("/tmp/Clean_Base_Dependencies_List8591417797809616084.txt").toFile()
        librariesFromMavenCentral = Path.of("/tmp/Libraries_List_From_Maven_Central13161733866809064272.txt").toFile()

        val baseDependencies = readFileToSet(cleanBaseDependenciesFile)//.take(3000).toSet()
        val parsedDependencies = readFileToSet(librariesFromMavenCentral)//.take(3000).toSet()

        // store partially processed packages

        val notFoundPackages = mutableSetOf<String>()
        val existedPackages = mutableSetOf<String>()

        var inputSet = baseDependencies.plus(parsedDependencies)

        val totalNotFoundFile = File.createTempFile("TOTAL_PACKAGES_NOT_FOUND", ".txt")
        val totalExistFile = File.createTempFile("TOTAL_PACKAGES_EXIST", ".txt")

        do {
            countOfHttpException = 0
            val (packagesNotFoundFilePath, existedPackagesFilePath) = mergePackages(emptySet(), inputSet)

            notFoundPackages.addAll(readFileToSet(packagesNotFoundFilePath))
            existedPackages.addAll(readFileToSet(existedPackagesFilePath))

            writeIterableToFile(notFoundPackages, totalNotFoundFile)
            println("SUBTOTAL ${notFoundPackages.size} NOT FOUND PACKAGES FILE: ${totalNotFoundFile.path}")

            writeIterableToFile(existedPackages, totalExistFile)
            println("SUBTOTAL ${existedPackages.size} EXIST PACKAGES FILE: ${totalExistFile.path}")

            runBlocking { delay(Duration.seconds(60)) }

            inputSet = inputSet.minus(notFoundPackages).minus(existedPackages)
            println("Left to check packages: ${inputSet.size}")

        } while (inputSet.isNotEmpty())

        println("====== THE END ============")
    }

    @ExperimentalTime
//    @Test
    fun mergePackages(firstSet: Set<String>, secondSet: Set<String>): Pair<String, String> {

        val uniquePackages = getDistinctPackages(firstSet.toMutableSet().also { it.addAll(secondSet) })
            .toMutableSet()

        println("Raw packages count: ${uniquePackages.size}")

        val packagesNotFound = mutableSetOf<String>()
        val existedPackages = mutableSetOf<String>()

        var index = 0
        for (item in uniquePackages) {
            if (index++ % 100 == 0) // every N request
                println("Checking package existence: ${index * 100.0 / uniquePackages.size} %")

            if (countOfHttpException >= countOfExceptionThreshold) {
                System.err.println("Http exception threshold in ${countOfExceptionThreshold} requests was reached")
                break
            }

            if (!checkIsDependencyAvailable(item)) {
//                println("Package $item doesn't exist")
                packagesNotFound.add(item)
            } else {
                existedPackages.add(item)
            }
        }

        println("Count of packages, that weren't found ${packagesNotFound.size}. Packages, that exist: ${existedPackages.size}")

        val notExistPackagesFile = File.createTempFile("Not_Existed_Packages", ".txt")
        writeIterableToFile(packagesNotFound, notExistPackagesFile)
        println("Packages, that weren't found on Maven Central has been written to file: ${notExistPackagesFile.path}")


        val finalList = File.createTempFile("Libraries_Final_List", ".txt")
        writeIterableToFile(existedPackages.shuffled(), finalList)

        println("List with final packages (that do exist) has been written to file: ${finalList.path}")

        return Pair(notExistPackagesFile.path, finalList.path)
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
                404 -> false
                else -> {
                    System.err.println("Unexpected request status code: $responseCode")
                    false
                }
            }
        } catch (e: Exception) {
            countOfHttpException++
//            e.printStackTrace()
            System.err.println("Http failure encountered")
            false
        }
    }
}
