package com.chatcrmlite.backend.services.ingestion;

/**
 * Typed extraction failure codes for RAG document upload.
 */
public enum DocumentExtractionErrorCode {
    UNSUPPORTED_FORMAT,
    MALFORMED_FILE,
    EMPTY_DOCUMENT,
    ENCODING_FAILURE,
    EXTRACTION_LIMIT_EXCEEDED,
    PARSER_FAILURE,
    UPLOAD_TOO_LARGE
}
