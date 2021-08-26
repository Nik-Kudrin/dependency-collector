package data

import org.junit.jupiter.api.Test
import org.kohsuke.github.GitHubBuilder
import java.net.URL

const val token = ""

class GitHubMavenDependencyHarvester {
    private fun search(language: String, fileExtension: String, pagesLimit: Int = 100) {
        val github = GitHubBuilder().withOAuthToken(token).build()
        val searchLimit = github.rateLimit.search
        println("Rate limit: ${searchLimit.limit} Remaining: ${searchLimit.remaining}")

        val searchIterable = github.searchContent()
            .language(language)
            .extension(fileExtension)
//            .size()
            .list()

        searchIterable.take(pagesLimit).forEach {
            it
        }

//        return searchIterable
    }

    public fun searchForBuildGradleFiles(): List<URL> {
        search(language = "Java", fileExtension = "gradle", pagesLimit = 1)
        TODO()
    }

    public fun searchForMavenPomFiles(): List<URL> {
        TODO()
    }

    @Test
    fun runSearch() {
        searchForBuildGradleFiles()
    }
}