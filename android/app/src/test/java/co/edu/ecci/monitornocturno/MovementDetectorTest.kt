package co.edu.ecci.monitornocturno

import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MovementDetectorTest {
    @Test fun quietSignalDoesNotTrigger() {
        val detector = MovementDetector(sampleRate = 50, persistenceSeconds = 2)
        var state = DetectionState(0.0, 0.0, false)
        repeat(1500) { state = detector.add(0.001 * sin(2.0 * PI * it / 37.0)) }
        assertFalse(state.candidate)
    }
}
