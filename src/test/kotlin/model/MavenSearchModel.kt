package model

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty


@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MavenSearchResponseDoc(
    val id: String,
    val latestVersion: String,
    val repositoryId: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MavenSearchResponse(
    val docs: List<MavenSearchResponseDoc>
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MavenSearchModel(
    val response: MavenSearchResponse
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DependencyData(
    val type: String,
    val group: String,

    @JsonProperty("name")
    @JsonAlias("module")
    val name: String,

    val version: String
)