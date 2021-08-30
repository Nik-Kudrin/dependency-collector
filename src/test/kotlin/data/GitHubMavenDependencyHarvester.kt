package data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.kohsuke.github.GHContent
import org.kohsuke.github.GitHubBuilder
import java.io.File
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.*
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

const val token = ""

@JsonIgnoreProperties(ignoreUnknown = true)
private data class GithubSearchDto(
    // eg: build.gradle
    val name: String,
    val path: String,
    val sha: String,
    val url: String,

    // raw content
    @JsonProperty("download_url")
    val downloadUrl: String,

    // base64
    val content: String
) {
    fun decodeContent(): String {
        return String(Base64.getMimeDecoder().decode(content))
    }
}

@ExperimentalTime
class GitHubMavenDependencyHarvester {
    companion object {
        private val github = GitHubBuilder().withOAuthToken(token).build()
        private val jacksonMapper = jacksonObjectMapper()
    }

    private fun displayLimits() {
        val searchLimit = github.rateLimit.search
        println(
            "Remaining global limit: ${github.rateLimit.getRemaining()} from ${github.rateLimit.getLimit()}" +
                    " Remaining search limit: ${searchLimit.remaining} from ${searchLimit.limit}"
        )
    }

    private fun search(
        language: String = "",
        extension: String = "",
        fileName: String = "",
        searchTerm: String = "",
        filesLimit: Int = 100
    ): List<GithubSearchDto> {
        val httpClient = HttpClient.newHttpClient()
        val waitTime = Duration.minutes(5)

        displayLimits()
        val search = github.searchContent()

        if (searchTerm.isNotBlank())
            search.q(searchTerm)

        if (fileName.isNotBlank())
            search.filename(fileName)

        if (extension.isNotBlank())
            search.extension(extension)

        if (language.isNotBlank())
            search.language(language)

        val searchIterable = search
            .list()
            .withPageSize(50)

        val searchResults = mutableListOf<GithubSearchDto>()
        val iterator = searchIterable.iterator()
        var iterations = 0
        var hasNextItem = true

        do {
            iterations++
            displayLimits()

            var page: Iterable<GHContent>? = null

            do {
                try {
                    page = iterator.nextPage()
                } catch (e: Exception) {
                    println("Exception ${e.message} Waiting 1 min")
                    runBlocking { delay(Duration.minutes(1)) }
                }
            } while (page == null)

            page.forEach {
                try {
                    val request = HttpRequest.newBuilder(URI(it.url))
                        .GET()
                        .setHeader("Authorization", "token $token")
                        .build()

                    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
                    val searchDto = jacksonMapper.readValue<GithubSearchDto>(response.body())
                    searchResults.add(searchDto)
                } catch (e: Exception) {
                    System.err.println("Error during request to content: ${e.message}")

                    if (github.rateLimit.getRemaining() == 0) {
                        println("Limit was exhausted. Waiting $waitTime")
                        runBlocking { delay(waitTime) }
                    }
                }
            }

            try {
                hasNextItem = iterator.hasNext()
            } catch (e: Exception) {
                println("Exception ${e.message} Waiting 10 sec")
                runBlocking { delay(Duration.seconds(10)) }
            }

        } while (hasNextItem && searchResults.size < filesLimit)

        return searchResults
    }

    fun searchForBuildGradleFiles(
        fileName: String = "",
        searchTerm: String = "",
        filesLimit: Int = 100
    ): HashSet<DependencyData> {
        val dependencies = HashSet<DependencyData>(1_000)
        val files = search(language = "Gradle", fileName = fileName, searchTerm = searchTerm, filesLimit = filesLimit)
        println("Found ${files.size} Gradle files on Github")

        files.forEachIndexed { index, file ->
            if (index % 20 == 0 && (index * 100.0F / files.size).roundToInt() % 10 == 0) // each 10 percent
                println("Processed $index of ${files.size} Gradle files")
            dependencies.addAll(DependencyMavenDataExtractor.parseGradleDependencies(file.name, file.decodeContent()))
        }

        println("Parsed all Gradle files")

        DependencyMavenDataExtractor.cleanupDependencies(dependencies)
        return dependencies
    }

    fun searchForMavenPomFiles(
        fileName: String = "",
        searchTerm: String = "",
        filesLimit: Int = 100
    ): HashSet<DependencyData> {
        val dependencies = HashSet<DependencyData>(1_000)
        val files =
            search(language = "Maven POM", fileName = fileName, searchTerm = searchTerm, filesLimit = filesLimit)
        println("Found ${files.size} Maven files on Github")

        files.forEachIndexed { index, file ->
            if (index % 20 == 0 && (index * 100.0F / files.size).roundToInt() % 10 == 0) // each 10 percent
                println("Processed $index of ${files.size} Maven files")
            dependencies.addAll(DependencyMavenDataExtractor.parseMavenDependencies(file.name, file.decodeContent()))
        }

        println("Parsed all Maven files")

        DependencyMavenDataExtractor.cleanupDependencies(dependencies)
        return dependencies
    }

    @Test
    fun runSearch() {
        var dependencies = searchForMavenPomFiles(fileName = "pom.xml", filesLimit = 10_000)
        dependencies += searchForMavenPomFiles(searchTerm = "pom.xml", filesLimit = 10_000)
        dependencies += searchForBuildGradleFiles(fileName = "build.gradle", filesLimit = 10_000)
        dependencies += searchForBuildGradleFiles(searchTerm = "build.gradle", filesLimit = 10_000)

        val packages = DependencyMavenDataExtractor.getDistinctPackages(dependencies)
        val parsedFromRepos = File.createTempFile("Parsed_From_Repos_", ".txt")
        DependencyMavenDataExtractor.writeIterableToFile(packages, parsedFromRepos)
        println("Cleaned packages ${packages.size} stored to file: ${parsedFromRepos.path}")
    }

    @Test
    fun parsingAndDecodeTimeoutTest() {
        val apiUrl =
            URL("https://api.github.com/repos/computate-org/computate-org/contents/roles/redhat_project/templates/pom.xml?ref=67bdf670a6f2f46f6cf98f8531cae18cced922a0")

        val searchDto = jacksonMapper.readValue<GithubSearchDto>(apiUrl.openStream())
        val deps = DependencyMavenDataExtractor.parseMavenDependencies("pom.xml", searchDto.decodeContent())
        println(deps)
    }

    @Test
    fun checkRegexWorks() {
        val pattern = Regex("[\$ {}@><=]")

        Assertions.assertTrue(" ".contains(regex = pattern))
        Assertions.assertTrue(" $".contains(regex = pattern))
        Assertions.assertTrue(" {".contains(regex = pattern))
        Assertions.assertTrue(" }".contains(regex = pattern))
        Assertions.assertTrue(" @".contains(regex = pattern))
        Assertions.assertTrue(" >".contains(regex = pattern))
        Assertions.assertTrue(" <".contains(regex = pattern))
        Assertions.assertTrue(" =".contains(regex = pattern))
    }

    @Test
    fun deserializeMavenFileTest() {
        val apiUrl =
            URL("https://raw.githubusercontent.com/computate-org/computate-org/67bdf670a6f2f46f6cf98f8531cae18cced922a0/roles/redhat_project/templates/pom.xml")

        val deps = DependencyMavenDataExtractor.parseMavenDependencies("pom.xml", apiUrl.content.toString())
        println(deps)
    }
}