package com.here.ort.model

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.reflect.KClass

val GNUPLOT_SCRIPT = """
set boxwidth 0.75
set style fill solid
plot "data.dat"  using 2:xtic(1) with boxes
""".trimIndent()

private interface Serializer {
    fun <T: Any> serialize(obj: T): ByteArray
    fun <T: Any> deserialize(data: ByteArray, kClass: KClass<T>): T?
    fun getName(): String
}

private class JacksonSerializer(private val mapper: ObjectMapper, private val name: String): Serializer {
    override fun <T: Any> serialize(obj: T) = mapper.writeValueAsBytes(obj)
    override fun <T: Any> deserialize(data: ByteArray, kClass: KClass<T>): T = mapper.readValue(data, kClass.java)
    override fun getName(): String = name
}

data class ResultItem(val dataFormat: String, val compression: String, val size: Int)

private interface Compressor {
    fun compress(data: ByteArray): ByteArray
    fun decompress(data: ByteArray): ByteArray
    val name: String
}

private class NoneCompressor: Compressor {
    override fun compress(data: ByteArray): ByteArray = data
    override fun decompress(data: ByteArray): ByteArray = data
    override val name = "NONE"
}

private class CommonsCompressor(override val name: String): Compressor {
    private val factory = CompressorStreamFactory.getSingleton()

    override fun compress(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val compressorOutput = factory.createCompressorOutputStream(name, output)
        compressorOutput.write(data)
        compressorOutput.close()
        return output.toByteArray()
    }

    override fun decompress(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val input = ByteArrayInputStream(data)
        val compressorInput = factory.createCompressorInputStream(name, input)
        compressorInput.copyTo(output)
        input.close()
        return output.toByteArray()
    }
}

fun getInputData(): OrtResult {
    return File("/home/viernau/analyzer-result.json").readValue()
}

private fun getSerializers() = listOf<Serializer>(
            JacksonSerializer(jsonMapper, "JSON"),
            JacksonSerializer(yamlMapper, "YML"),
            JacksonSerializer(bsonMapper, "BSON"),
            JacksonSerializer(cborMapper, "CBOR"),
            JacksonSerializer(messagePackMapper, "MSGPCK")
    ).sortedBy { it.getName() }

private fun getCompressors(): List<Compressor> = listOf<Compressor> (
        NoneCompressor(),
        CommonsCompressor(CompressorStreamFactory.BZIP2),
        CommonsCompressor(CompressorStreamFactory.GZIP),
        CommonsCompressor(CompressorStreamFactory.LZMA),
        CommonsCompressor(CompressorStreamFactory.LZ4_BLOCK),
        CommonsCompressor(CompressorStreamFactory.XZ)
).sortedBy { it.name }


fun main(args: Array<String>) {
    val sampleData = getInputData()
    val serializers = getSerializers()
    val compressors = getCompressors()

    val result = mutableListOf<ResultItem>()
    serializers.forEach { s ->
        compressors.forEach { c ->
            result.add(evaluateSerializer(s, c, sampleData))
        }
    }

    println("----------data.dat-------------")
    println("# format size")
    result.sortedBy { it.size }.forEach {
        println(it.dataFormat.toLowerCase() + "-" + it.compression + " " + it.size)
    }
    println("------------------------------")
    println(GNUPLOT_SCRIPT)
}

private inline fun <reified T : Any> evaluateSerializer(serializer: Serializer, compressor: Compressor, sampleData: T): ResultItem {
    val serialized = serializer.serialize(sampleData)
    val compressed = compressor.compress(serialized)
    //val decompressed = compressor.decompress(compressed)
    //val deserialized = serializer.deserialize(decompressed, T::class)
    return ResultItem(serializer.getName(), compressor.name, compressed.size)
}
