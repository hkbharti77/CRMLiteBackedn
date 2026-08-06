package com.chatcrmlite.backend.dto;

import java.util.ArrayList;
import java.util.List;

public class ImportResultDTO {
    private int total = 0;
    private int created = 0;
    private int updated = 0;
    private int skipped = 0;
    private int failed = 0;
    private List<ImportErrorDTO> errors = new ArrayList<>();

    public ImportResultDTO() {}

    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }

    public int getCreated() { return created; }
    public void setCreated(int created) { this.created = created; }

    public int getUpdated() { return updated; }
    public void setUpdated(int updated) { this.updated = updated; }

    public int getSkipped() { return skipped; }
    public void setSkipped(int skipped) { this.skipped = skipped; }

    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }

    public List<ImportErrorDTO> getErrors() { return errors; }
    public void setErrors(List<ImportErrorDTO> errors) { this.errors = errors; }
    
    public void incrementCreated() { this.created++; this.total++; }
    public void incrementUpdated() { this.updated++; this.total++; }
    public void incrementSkipped() { this.skipped++; this.total++; }
    public void incrementFailed() { this.failed++; this.total++; }
    
    public void addError(ImportErrorDTO error) {
        this.errors.add(error);
    }
}
