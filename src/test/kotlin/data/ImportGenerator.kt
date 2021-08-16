package data

import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.FileWriter
import java.util.stream.IntStream

class ImportGenerator {
    @Test
    fun generateImport() {
        IntStream.range(0, 15_000).forEach {
            println("include(\"project$it\")")
        }
    }

    @Test
    fun dependencyImport() {
        var stream = ImportGenerator::class.java.classLoader.getResourceAsStream("Libraries_Final_List.txt")
        val packages = BufferedReader(stream.reader()).readLines()

        BufferedWriter(FileWriter("/home/neon/Downloads/dependencies.txt")).use { writer ->
            writer.write("dependencies {" + System.lineSeparator())

            packages.forEach {
                writer.write("  implementation \"$it\"" + System.lineSeparator())
            }

            writer.write("}" + System.lineSeparator())
        }
    }

    // fr.mastah.maven.plugin.m2e.jsdoc3:jsdoc3-m2e-site:1.0.1

    // Could not find jsdoc3-m2e-site-1.0.1.jar (fr.mastah.maven.plugin.m2e.jsdoc3:jsdoc3-m2e-site:1.0.1).
    //Searched in the following locations:
    //    https://repo.maven.apache.org/maven2/fr/mastah/maven/plugin/m2e/jsdoc3/jsdoc3-m2e-site/1.0.1/jsdoc3-m2e-site-1.0.1.jar

    @Test
    fun libChecker() {
        val builder = StringBuilder("https://repo.maven.apache.org/maven2/")

        val dependency = "fr.mastah.maven.plugin.m2e.jsdoc3:jsdoc3-m2e-site:1.0.1"
//        val parts = dependency.split(delimiters = ":")

    }
}