package com.hydrosense.edge.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.hydrosense.edge.data.model.HydrationReading
import com.hydrosense.edge.data.model.HydrationStatus

@Entity(tableName = "hydration_records")
data class HydrationRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val readingNumber: Int,
    val hydrationScore: Float?,
    val hiInstant: Float?,
    val hydrationStatus: String,
    val perfusionIndex: Float?,
    val heartRate: Float?,
    val rmssd: Float?,
    val signalQuality: Float?,
    val motionStatus: String,
    val dcIr: Float?,
    val dcRed: Float?,
    val irShift: Float?,
    val refPI: Float?,
    val refIR: Float?,
    val refCV: Float?,
    val contactFraction: Float?,
    val accepted: Boolean,
    val pulseWidth: Float?,
    val riseTime: Float?,
    val pulseArea: Float?,
    val ampCV: Float?,
    val sdA: Float?,
    val sdG: Float?,
    val nValid: Int
) {
    fun toReading(): HydrationReading {
        return HydrationReading(
            id = id,
            readingNumber = readingNumber,
            timestamp = timestamp,
            hi = hiInstant,
            hiSmooth = hydrationScore,
            status = HydrationStatus.fromString(hydrationStatus),
            rawStatusText = hydrationStatus,
            piComb = perfusionIndex,
            hr = heartRate,
            rmssd = rmssd,
            sqi = signalQuality,
            motion = motionStatus,
            dcIr = dcIr,
            dcRed = dcRed,
            irShift = irShift,
            refPI = refPI,
            refIR = refIR,
            refCV = refCV,
            contactFrac = contactFraction,
            accepted = accepted,
            width = pulseWidth,
            rise = riseTime,
            area = pulseArea,
            ampCV = ampCV,
            sdA = sdA,
            sdG = sdG,
            nValid = nValid
        )
    }

    companion object {
        fun fromReading(reading: HydrationReading): HydrationRecordEntity {
            return HydrationRecordEntity(
                timestamp = reading.timestamp,
                readingNumber = reading.readingNumber,
                hydrationScore = reading.hiSmooth,
                hiInstant = reading.hi,
                hydrationStatus = reading.status.displayName,
                perfusionIndex = reading.piComb,
                heartRate = reading.hr,
                rmssd = reading.rmssd,
                signalQuality = reading.sqi,
                motionStatus = reading.motion,
                dcIr = reading.dcIr,
                dcRed = reading.dcRed,
                irShift = reading.irShift,
                refPI = reading.refPI,
                refIR = reading.refIR,
                refCV = reading.refCV,
                contactFraction = reading.contactFrac,
                accepted = reading.accepted,
                pulseWidth = reading.width,
                riseTime = reading.rise,
                pulseArea = reading.area,
                ampCV = reading.ampCV,
                sdA = reading.sdA,
                sdG = reading.sdG,
                nValid = reading.nValid
            )
        }
    }
}
