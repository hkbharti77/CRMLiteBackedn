package com.chatcrmlite.backend.models.google;

/**
 * Backend-controlled mapping of CRM Google integration features to their OAuth scopes.
 * This is the SINGLE SOURCE OF TRUTH for all scope strings.
 *
 * NEVER allow the frontend to pass raw scope strings.
 * Frontend sends: ?integration=CALENDAR
 * Backend resolves scope via: GoogleIntegrationType.valueOf("CALENDAR").getOauthScope()
 */
public enum GoogleIntegrationType {

    CALENDAR("https://www.googleapis.com/auth/calendar.events"),
    GMAIL   ("https://www.googleapis.com/auth/gmail.send"),
    CONTACTS("https://www.googleapis.com/auth/contacts.readonly"),
    TASKS   ("https://www.googleapis.com/auth/tasks"),
    DRIVE   ("https://www.googleapis.com/auth/drive.file"),
    SHEETS  ("https://www.googleapis.com/auth/spreadsheets");

    private final String oauthScope;

    GoogleIntegrationType(String oauthScope) {
        this.oauthScope = oauthScope;
    }

    public String getOauthScope() {
        return oauthScope;
    }
}
