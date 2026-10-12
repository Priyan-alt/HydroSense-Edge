package com.hydrosense.edge.data.ble

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hydrosense.edge.data.model.CalibrationInfo
import com.hydrosense.edge.data.model.HydrationReading
import com.hydrosense.edge.data.model.HydrationStatus
import java.util.Locale

/**
 * Result of parsing a BLE packet.
 */
sealed class ParseResult {
    data class Reading(val reading: HydrationReading) : ParseResult()
    data class Calibration(val info: CalibrationInfo) : ParseResult()
    data class CalibrationComplete(val info: CalibrationInfo) : ParseResult()
    data class DeviceStatus(val deviceName: String, val message: String) : ParseResult()
    data class MeasureStart(val duration: Int, val requestId: Long) : ParseResult()
    data class MeasureProgress(
        val percent: Int,
        val secondsRemaining: Int,
        val status: String,
        val angle: Float? = null,
        val requestId: Long = 0L
    ) : ParseResult()
    data class MeasureError(val status: String, val reason: String) : ParseResult()
    data class RawSample(val timestampMs: Long, val red: Long, val ir: Long, val aMag: Float, val gMag: Float) : ParseResult()
    data class RawData(val line: String) : ParseResult()
    data class Error(val rawText: String, val errorMessage: String) : ParseResult()
}

/**
 * Modular parser interface.
 */
interface BlePacketParser {
    fun parse(rawText: String): List<ParseResult>
}

/**
 * Resilient, modular parser implementation supporting:
 *  - Fragmented BLE chunks buffering (reassembles packets if MTU < payload size)
 *  - JSON reading, calibration, calibration_complete, status
 *  - CSV W,... (15-second window summaries) and R,... (raw sample streams)
 *  - Debug messages starting with '#'
 *  - Diagnostic error reporting
 */
class ModularBlePacketParser : BlePacketParser {

    private val chunkBuffer = StringBuilder()
    private var lastChunkTime = 0L

    @Synchronized
    override fun parse(rawText: String): List<ParseResult> {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        // Reset buffer if stale (> 3 seconds)
        if (chunkBuffer.isNotEmpty() && (now - lastChunkTime > 3000L)) {
            chunkBuffer.clear()
        }
        lastChunkTime = now

        val results = mutableListOf<ParseResult>()

        // Check if incoming text is a complete CSV or status line
        if (trimmed.startsWith("W,") || trimmed.startsWith("R,") || trimmed.startsWith("#")) {
            val lines = trimmed.split("\r\n", "\n")
            for (line in lines) {
                val lineTrimmed = line.trim()
                if (lineTrimmed.isNotEmpty()) {
                    results.add(parseSingleLine(lineTrimmed))
                }
            }
            return results
        }

        // Process potential JSON (or chunks of JSON)
        if (chunkBuffer.isEmpty()) {
            if (trimmed.startsWith("{")) {
                chunkBuffer.append(trimmed)
            } else {
                // Not starting with '{', treat as single line
                results.add(parseSingleLine(trimmed))
                return results
            }
        } else {
            chunkBuffer.append(trimmed)
        }

        // Attempt to extract all complete JSON objects from chunkBuffer
        val bufferContent = chunkBuffer.toString()
        var braceCount = 0
        var startIndex = -1

        var currentIndex = 0
        while (currentIndex < bufferContent.length) {
            val c = bufferContent[currentIndex]
            if (c == '{') {
                if (braceCount == 0) {
                    startIndex = currentIndex
                }
                braceCount++
            } else if (c == '}') {
                braceCount--
                if (braceCount == 0 && startIndex != -1) {
                    val jsonCandidate = bufferContent.substring(startIndex, currentIndex + 1)
                    results.add(parseJsonPacket(jsonCandidate))
                    startIndex = -1
                }
            }
            currentIndex++
        }

        if (startIndex == -1 && braceCount == 0) {
            // All JSON objects extracted successfully
            chunkBuffer.clear()
        } else if (startIndex != -1) {
            // Incomplete JSON remains in buffer, preserve from startIndex
            val remaining = bufferContent.substring(startIndex)
            chunkBuffer.clear()
            chunkBuffer.append(remaining)
        } else {
            // Buffer in inconsistent state, clear
            chunkBuffer.clear()
        }

        return results
    }

    private fun parseSingleLine(line: String): ParseResult {
        return when {
            line.startsWith("{") && line.endsWith("}") -> parseJsonPacket(line)
            line.startsWith("W,") -> parseCsvWindow(line)
            line.startsWith("R,") -> parseCsvRawSample(line)
            line.startsWith("#") -> ParseResult.DeviceStatus("HydroSense Edge", line)
            else -> ParseResult.RawData(line)
        }
    }

    private fun parseJsonPacket(jsonString: String): ParseResult {
        return try {
            val jsonElement = JsonParser.parseString(jsonString)
            if (!jsonElement.isJsonObject) {
                return ParseResult.Error(jsonString, "Not a valid JSON object")
            }
            val obj = jsonElement.asJsonObject
            val type = obj.get("type")?.asString?.lowercase(Locale.ROOT) ?: "reading"

            when (type) {
                "reading" -> parseReadingJson(obj, jsonString)
                "calibration" -> parseCalibrationJson(obj)
                "calibration_complete" -> parseCalibrationCompleteJson(obj)
                "status" -> parseStatusJson(obj)
                "measure_start" -> parseMeasureStartJson(obj)
                "measure_progress" -> parseMeasureProgressJson(obj)
                "measure_error" -> parseMeasureErrorJson(obj)
                else -> {
                    if (obj.has("hi") || obj.has("pi") || obj.has("status")) {
                        parseReadingJson(obj, jsonString)
                    } else {
                        ParseResult.DeviceStatus(
                            deviceName = obj.get("device")?.asString ?: "HydroSense Edge",
                            message = jsonString
                        )
                    }
                }
            }
        } catch (e: Exception) {
            ParseResult.Error(jsonString, "JSON parse error: ${e.message}")
        }
    }

    private fun parseMeasureStartJson(obj: JsonObject): ParseResult {
        val duration = getIntOrNull(obj, "duration") ?: 15
        val requestId = getLongOrNull(obj, "requestId") ?: 0L
        return ParseResult.MeasureStart(duration = duration, requestId = requestId)
    }

    private fun parseMeasureProgressJson(obj: JsonObject): ParseResult {
        val percent = getIntOrNull(obj, "percent") ?: 0
        val seconds = getIntOrNull(obj, "secondsRemaining") ?: getIntOrNull(obj, "seconds") ?: 0
        val status = getStringOrNull(obj, "status") ?: "MEASURING"
        val angle = getFloatOrNull(obj, "angle")
        val requestId = getLongOrNull(obj, "requestId") ?: 0L
        return ParseResult.MeasureProgress(
            percent = percent,
            secondsRemaining = seconds,
            status = status,
            angle = angle,
            requestId = requestId
        )
    }

    private fun parseMeasureErrorJson(obj: JsonObject): ParseResult {
        val status = getStringOrNull(obj, "status") ?: "UNRELIABLE"
        val reason = getStringOrNull(obj, "reason") ?: "Measurement rejected"
        return ParseResult.MeasureError(status = status, reason = reason)
    }

    private fun parseReadingJson(obj: JsonObject, rawString: String): ParseResult {
        return try {
            val hi = getFloatOrNull(obj, "hi")
            val hiSmooth = getFloatOrNull(obj, "hiSmooth")
            val statusStr = getStringOrNull(obj, "status")
            val status = HydrationStatus.fromString(statusStr)
            val piComb = getFloatOrNull(obj, "pi")
            val hr = getFloatOrNull(obj, "hr")
            val rmssd = getFloatOrNull(obj, "rmssd")
            val sqi = getFloatOrNull(obj, "sqi")
            val motion = getStringOrNull(obj, "motion") ?: "STABLE"
            val dcIr = getFloatOrNull(obj, "ir")
            val dcRed = getFloatOrNull(obj, "red") ?: getFloatOrNull(obj, "dcRed")
            val irShift = getFloatOrNull(obj, "irShift")
            val refPI = getFloatOrNull(obj, "refPI")
            val refHR = getFloatOrNull(obj, "refHR")
            val refIR = getFloatOrNull(obj, "refIR")
            val refCV = getFloatOrNull(obj, "refCV") ?: getFloatOrNull(obj, "scatter")
            val contact = getFloatOrNull(obj, "contact")
            val accepted = getBooleanOrNull(obj, "accepted") ?: true
            val width = getFloatOrNull(obj, "width")
            val rise = getFloatOrNull(obj, "rise")
            val area = getFloatOrNull(obj, "area")
            val ampCV = getFloatOrNull(obj, "ampCV")
            val sdA = getFloatOrNull(obj, "sdA")
            val sdG = getFloatOrNull(obj, "sdG")
            val nValid = getIntOrNull(obj, "nValid") ?: 0
            val readingNum = getIntOrNull(obj, "reading") ?: 0
            val requestId = getLongOrNull(obj, "requestId")
            val reading = HydrationReading(
                readingNumber = readingNum,
                hi = hi,
                hiSmooth = hiSmooth,
                status = status,
                rawStatusText = statusStr ?: "",
                isPreliminary = statusStr?.contains("?") == true,
                piComb = piComb,
                hr = hr,
                rmssd = rmssd,
                sqi = sqi,
                motion = motion,
                dcIr = dcIr,
                dcRed = dcRed,
                irShift = irShift,
                refPI = refPI,
                refHR = refHR,
                refIR = refIR,
                refCV = refCV,
                contactFrac = contact,
                accepted = accepted,
                width = width,
                rise = rise,
                area = area,
                ampCV = ampCV,
                sdA = sdA,
                sdG = sdG,
                nValid = nValid,
                requestId = requestId
            )
            ParseResult.Reading(reading)
        } catch (e: Exception) {
            ParseResult.Error(rawString, "Failed to parse reading: ${e.message}")
        }
    }

    private fun parseCalibrationJson(obj: JsonObject): ParseResult {
        val progress = getIntOrNull(obj, "progress") ?: 0
        val total = getIntOrNull(obj, "total") ?: 8
        val pi = getFloatOrNull(obj, "pi")
        val sqi = getFloatOrNull(obj, "sqi")
        return ParseResult.Calibration(
            CalibrationInfo(
                isCalibrating = true,
                progress = progress,
                total = total,
                lastPI = pi,
                lastSQI = sqi,
                isValid = false
            )
        )
    }

    private fun parseCalibrationCompleteJson(obj: JsonObject): ParseResult {
        val refPI = getFloatOrNull(obj, "refPI")
        val refHR = getFloatOrNull(obj, "refHR")
        val refIR = getFloatOrNull(obj, "refIR")
        val scatter = getFloatOrNull(obj, "scatter")
        return ParseResult.CalibrationComplete(
            CalibrationInfo(
                isCalibrating = false,
                progress = 8,
                total = 8,
                refPI = refPI,
                refHR = refHR,
                refIR = refIR,
                scatter = scatter,
                isValid = true
            )
        )
    }

    private fun parseStatusJson(obj: JsonObject): ParseResult {
        val device = obj.get("device")?.asString ?: "HydroSense Edge"
        val msg = obj.get("status")?.asString ?: "Ready"
        return ParseResult.DeviceStatus(deviceName = device, message = msg)
    }

    private fun parseCsvWindow(csv: String): ParseResult {
        return try {
            val tokens = csv.split(",")
            if (tokens.size < 28) {
                return ParseResult.Error(csv, "CSV has insufficient columns (${tokens.size})")
            }
            val readingNum = tokens.getOrNull(1)?.toIntOrNull() ?: 0
            val statusRaw = tokens.getOrNull(4) ?: ""
            val contactFrac = tokens.getOrNull(5)?.toFloatOrNull()
            val dcRed = tokens.getOrNull(8)?.toFloatOrNull()
            val dcIr = tokens.getOrNull(9)?.toFloatOrNull()
            val piComb = tokens.getOrNull(12)?.toFloatOrNull()
            val hr = tokens.getOrNull(14)?.toFloatOrNull()
            val rmssd = tokens.getOrNull(15)?.toFloatOrNull()
            val width = tokens.getOrNull(16)?.toFloatOrNull()
            val rise = tokens.getOrNull(17)?.toFloatOrNull()
            val area = tokens.getOrNull(18)?.toFloatOrNull()
            val ampCV = tokens.getOrNull(19)?.toFloatOrNull()
            val sdA = tokens.getOrNull(20)?.toFloatOrNull()
            val sdG = tokens.getOrNull(21)?.toFloatOrNull()
            val sqi = tokens.getOrNull(22)?.toFloatOrNull()
            val nValid = tokens.getOrNull(23)?.toIntOrNull() ?: 0
            val accepted = tokens.getOrNull(24)?.toIntOrNull() == 1
            val hi = tokens.getOrNull(25)?.toFloatOrNull()
            val hiSmooth = tokens.getOrNull(26)?.toFloatOrNull()
            val hydStatusStr = tokens.getOrNull(27) ?: ""
            val irShift = tokens.getOrNull(29)?.toFloatOrNull()

            val motion = if ((sdA ?: 0f) > 0.05f || (sdG ?: 0f) > 10.0f) "MOVING" else "STABLE"

            val reading = HydrationReading(
                readingNumber = readingNum,
                hi = hi,
                hiSmooth = hiSmooth,
                status = HydrationStatus.fromString(hydStatusStr),
                rawStatusText = if (hydStatusStr.isNotBlank() && hydStatusStr != "--") hydStatusStr else statusRaw,
                piComb = piComb,
                hr = hr,
                rmssd = rmssd,
                sqi = sqi,
                motion = motion,
                dcIr = dcIr,
                dcRed = dcRed,
                irShift = irShift,
                contactFrac = contactFrac,
                accepted = accepted,
                width = width,
                rise = rise,
                area = area,
                ampCV = ampCV,
                sdA = sdA,
                sdG = sdG,
                nValid = nValid
            )
            ParseResult.Reading(reading)
        } catch (e: Exception) {
            ParseResult.Error(csv, "CSV parse failed: ${e.message}")
        }
    }

    private fun parseCsvRawSample(csv: String): ParseResult {
        // Format: R,millis,red,ir,aNow,gNow
        return try {
            val tokens = csv.split(",")
            val tMs = tokens.getOrNull(1)?.toLongOrNull() ?: System.currentTimeMillis()
            val red = tokens.getOrNull(2)?.toLongOrNull() ?: 0L
            val ir = tokens.getOrNull(3)?.toLongOrNull() ?: 0L
            val aMag = tokens.getOrNull(4)?.toFloatOrNull() ?: 1.0f
            val gMag = tokens.getOrNull(5)?.toFloatOrNull() ?: 0.0f
            ParseResult.RawSample(tMs, red, ir, aMag, gMag)
        } catch (e: Exception) {
            ParseResult.Error(csv, "Failed to parse raw sample: ${e.message}")
        }
    }

    private fun getFloatOrNull(obj: JsonObject, key: String): Float? {
        val el = obj.get(key) ?: return null
        if (el.isJsonNull) return null
        val str = el.asString.trim()
        if (str.equals("null", ignoreCase = true) || str.equals("nan", ignoreCase = true) || str.isEmpty()) {
            return null
        }
        return str.toFloatOrNull()
    }

    private fun getIntOrNull(obj: JsonObject, key: String): Int? {
        val el = obj.get(key) ?: return null
        if (el.isJsonNull) return null
        val str = el.asString.trim()
        if (str.equals("null", ignoreCase = true) || str.isEmpty()) return null
        return str.toIntOrNull()
    }

    private fun getBooleanOrNull(obj: JsonObject, key: String): Boolean? {
        val el = obj.get(key) ?: return null
        if (el.isJsonNull) return null
        val str = el.asString.trim()
        if (str.equals("null", ignoreCase = true)) return null
        return str.toBooleanStrictOrNull()
    }

    private fun getStringOrNull(obj: JsonObject, key: String): String? {
        val el = obj.get(key) ?: return null
        if (el.isJsonNull) return null
        val str = el.asString.trim()
        if (str.equals("null", ignoreCase = true) || str.isEmpty()) return null
        return str
    }

    private fun getLongOrNull(obj: JsonObject, key: String): Long? {
        val el = obj.get(key) ?: return null
        if (el.isJsonNull) return null
        val str = el.asString.trim()
        if (str.equals("null", ignoreCase = true) || str.isEmpty()) return null
        return str.toLongOrNull()
    }
}
