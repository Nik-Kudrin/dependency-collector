package data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement

@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "dependency")
data class MavenDependency(
    @JacksonXmlProperty(localName = "groupId")
    val groupId: String,
    @JacksonXmlProperty(localName = "artifactId")
    val artifactId: String,
    @JacksonXmlProperty(localName = "version")
    val version: String?,

    // compile, provided, runtime, test ...
    @JacksonXmlProperty(localName = "scope")
    val scope: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MavenDependencyManagement(
    @JacksonXmlProperty(localName = "dependencies")
    val dependencies: List<MavenDependency>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "project")
data class MavenProject(
    @JacksonXmlProperty(localName = "dependencyManagement")
    val dependencyManagement: MavenDependencyManagement?,

    @JacksonXmlProperty(localName = "dependencies")
    val dependencies: List<MavenDependency>?
)

//project -> dependencyManagement -> dependencies ->
