package model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.*

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class GithubSearchDto(
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