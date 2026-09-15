package com.chatcrmlite.backend.services.ingestion;

/**
 * Typed failure from document text extraction. Never write chunks on this exception.
 */
public class DocumentExtractionException extends Exception {
    private final DocumentExtractionErrorCode code;

    public DocumentExtractionException(DocumentExtractionErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public DocumentExtractionException(DocumentExtractionErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public DocumentExtractionErrorCode getCode() {
        return code;
    }
}
