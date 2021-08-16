package data

import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.FileWriter
import java.util.stream.IntStream

class ImportGenerator {
    @Test
    fun printProjectImportList() {
        IntStream.range(0, 15_000).forEach {
            println("include(\"project$it\")")
        }
    }

    @Test
    fun printDependencyImports() {
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
}