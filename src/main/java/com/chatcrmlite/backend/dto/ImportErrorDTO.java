package com.chatcrmlite.backend.dto;

public class ImportErrorDTO {
    private String file;
    private Integer row;
    private String field;
    private String code;
    private String message;

    public ImportErrorDTO() {}

    public ImportErrorDTO(String file, Integer row, String field, String code, String message) {
        this.file = file;
        this.row = row;
        this.field = field;
        this.code = code;
        this.message = message;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public Integer getRow() {
        return row;
    }

    public void setRow(Integer row) {
        this.row = row;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
