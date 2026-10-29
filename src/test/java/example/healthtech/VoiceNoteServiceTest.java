package example.healthtech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;

class VoiceNoteServiceTest {
    @Test void patientSafeReminderDecision() {
        String approved = VoiceNoteService.notification(true);
        assertEquals("Your appointment reminder is ready. Please use your secure patient portal for details.", approved);
        assertFalse(approved.contains("transcript"));
        assertEquals("No patient notification scheduled.", VoiceNoteService.notification(false));
    }
}
