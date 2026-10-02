package com.chatcrmlite.backend.models.google;

/**
 * All possible states for a Google Integration feature in CRMLite.
 * Used in GoogleIntegration.status to power frontend status badges.
 */
public enum GoogleIntegrationStatus {

    /** OAuth flow started — awaiting user consent callback */
    PENDING,

    /** Fully authorized — all requested scopes granted and verified */
    CONNECTED,

    /** OAuth completed but user denied some requested scopes (granular permissions) */
    PARTIAL,

    /** Access token expired — background refresh pending or failed */
    TOKEN_EXPIRED,

    /** Refresh token revoked — user must re-authorize from Google consent screen */
    REAUTH_REQUIRED,

    /** Access revoked from Google account settings by the user */
    REVOKED,

    /** Google API returned an unrecoverable error */
    ERROR,

    /** User explicitly disconnected this integration from CRMLite */
    DISCONNECTED
}
