package lk.sunshine.smartcare.shared.passwordreset;

public record ResetCodeGeneratedEvent(String email, String rawCode) {}
