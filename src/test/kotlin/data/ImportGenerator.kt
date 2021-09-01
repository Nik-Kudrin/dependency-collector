package data

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import model.DependencyData
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.stream.IntStream
import kotlin.time.ExperimentalTime


class ImportGenerator {
    @OptIn(ExperimentalTime::class)
    @Test
    fun parseFlattenDependency() {
        val stream = ImportGenerator::class.java.classLoader
            .getResourceAsStream("flatten_dependency_tree_from_build_scan_gradle.json")

        val dependenciesList =
            jacksonObjectMapper().readValue(stream, object : TypeReference<List<DependencyData>>() {})
        println("Flat dependencies count ${dependenciesList.size}")

        val distinctDependencies = DependencyMavenDataExtractor.getDistinctPackages(dependenciesList.toSet())

        val file = File.createTempFile("Flat_Dependencies_Distinct", ".txt")
        DependencyMavenDataExtractor.writeIterableToFile(distinctDependencies, file)
        println("Distinct stored to file: ${file.path}")
    }


    @Test
    fun printProjectImportList() {
        IntStream.range(0, 15_000).forEach {
            println("include(\"project$it\")")
        }
    }

    @Test
    fun printDependencyImports() {
        var stream = ImportGenerator::class.java.classLoader.getResourceAsStream("TOTAL_PACKAGES_EXIST.txt")
        val packages = BufferedReader(stream.reader()).readLines()

        BufferedWriter(FileWriter("/home/neon/Downloads/dependencies.txt")).use { writer ->
            writer.write("dependencies {" + System.lineSeparator())

            packages.forEach {
                writer.write("  implementation \"$it\"" + System.lineSeparator())
            }

            writer.write("}" + System.lineSeparator())
        }
    }
}