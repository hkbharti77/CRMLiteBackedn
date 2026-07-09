package com.chatcrmlite.backend.services.lead;

import com.chatcrmlite.backend.dto.BulkLeadRowDTO;
import com.chatcrmlite.backend.dto.RowErrorDTO;
import com.chatcrmlite.backend.repositories.ContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BulkLeadValidator}.
 *
 * <p>No Spring context is loaded — {@link ContactRepository} is mocked via Mockito.
 *
 * <p>Validates: Requirements 5, 6
 */
@ExtendWith(MockitoExtension.class)
class BulkLeadValidatorTest {

    @Mock
    private ContactRepository contactRepository;

    private BulkLeadValidator validator;

    private static final UUID TENANT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        validator = new BulkLeadValidator(contactRepository);
    }

    // ── 1. Missing name ───────────────────────────────────────────────────

    @Test
    @DisplayName("Row with blank name produces error 'name is required'")
    void missingNameProducesError() {
        BulkLeadRowDTO row = BulkLeadRowDTO.builder()
                .rowNumber(1)
                .name("")
                .email("alice@example.com")
                .phone("9876543210")
                .build();

        BulkLeadValidator.ValidationResult result =
                validator.validate(List.of(row), null, TENANT_ID);

        assertThat(result.validRows()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        RowErrorDTO error = result.errors().get(0);
        assertThat(error.getRowNumber()).isEqualTo(1);
        assertThat(error.getReason()).isEqualTo("name is required");
    }

    @Test
    @DisplayName("Row with null name also produces error 'name is required'")
    void nullNameProducesError() {
        BulkLeadRowDTO row = BulkLeadRowDTO.builder()
                .rowNumber(2)
                .name(null)
                .email("bob@example.com")
                .build();

        BulkLeadValidator.ValidationResult result =
                validator.validate(List.of(row), null, TENANT_ID);

        assertThat(result.validRows()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).getReason()).isEqualTo("name is required");
    }

    // ── 2. Both email and phone blank ─────────────────────────────────────

    @Test
    @DisplayName("Row with both email and phone blank produces error 'email or phone is required'")
    void bothEmailAndPhoneBlankProducesError() {
        BulkLeadRowDTO row = BulkLeadRowDTO.builder()
                .rowNumber(1)
                .name("Alice")
                .email("")
                .phone("")
                .build();

        BulkLeadValidator.ValidationResult result =
                validator.validate(List.of(row), null, TENANT_ID);

        assertThat(result.validRows()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        RowErrorDTO error = result.errors().get(0);
        assertThat(error.getRowNumber()).isEqualTo(1);
        assertThat(error.getReason()).isEqualTo("email or phone is required");
    }

    @Test
    @DisplayName("Row with null email and null phone produces error 'email or phone is required'")
    void nullEmailAndNullPhoneProducesError() {
        BulkLeadRowDTO row = BulkLeadRowDTO.builder()
                .rowNumber(3)
                .name("Charlie")
                .email(null)
                .phone(null)
                .build();

        BulkLeadValidator.ValidationResult result =
                validator.validate(List.of(row), null, TENANT_ID);

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).getReason()).isEqualTo("email or phone is required");
    }

    // ── 3. Missing custom required field ─────────────────────────────────

    @Test
    @DisplayName("Row missing extra required field 'source' produces error 'source is required'")
    void missingCustomRequiredFieldProducesError() {
        BulkLeadRowDTO row = BulkLeadRowDTO.builder()
                .rowNumber(1)
                .name("Alice")
                .email("alice@example.com")
                .source(null)   // extra required field is absent
                .build();

        BulkLeadValidator.ValidationResult result =
                validator.validate(List.of(row), List.of("source"), TENANT_ID);

        assertThat(result.validRows()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        RowErrorDTO error = result.errors().get(0);
        assertThat(error.getRowNumber()).isEqualTo(1);
        assertThat(error.getReason()).isEqualTo("source is required");
    }

    @Test
    @DisplayName("Row with blank extra required field 'status' produces error 'status is required'")
    void blankCustomRequiredFieldProducesError() {
        BulkLeadRowDTO row = BulkLeadRowDTO.builder()
                .rowNumber(2)
                .name("Bob")
                .phone("9876543210")
                .status("")     // blank value for required field
                .build();

        BulkLeadValidator.ValidationResult result =
                validator.validate(List.of(row), List.of("status"), TENANT_ID);

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).getReason()).isEqualTo("status is required");
    }

    // ── 4. Duplicate email ────────────────────────────────────────────────

    @Test
    @DisplayName("Row whose email already exists in the tenant produces error 'duplicate email'")
    void duplicateEmailProducesError() {
        String existingEmail = "existing@example.com";
        when(contactRepository.existsByEmailAndTenant_Id(eq(existingEmail), eq(TENANT_ID)))
                .thenReturn(true);

        BulkLeadRowDTO row = BulkLeadRowDTO.builder()
                .rowNumber(1)
                .name("Alice")
                .email(existingEmail)
                .build();

        BulkLeadValidator.ValidationResult result =
                validator.validate(List.of(row), null, TENANT_ID);

        assertThat(result.validRows()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        RowErrorDTO error = result.errors().get(0);
        assertThat(error.getRowNumber()).isEqualTo(1);
        assertThat(error.getReason()).isEqualTo("duplicate email");
    }

    // ── 5. Valid row ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Row with name and non-duplicate email ends up in validRows")
    void validRowWithEmailEndsInValidRows() {
        when(contactRepository.existsByEmailAndTenant_Id(any(), any())).thenReturn(false);

        BulkLeadRowDTO row = BulkLeadRowDTO.builder()
                .rowNumber(1)
                .name("Alice")
                .email("alice@example.com")
                .build();

        BulkLeadValidator.ValidationResult result =
                validator.validate(List.of(row), null, TENANT_ID);

        assertThat(result.errors()).isEmpty();
        assertThat(result.validRows()).hasSize(1);
        assertThat(result.validRows().get(0)).isEqualTo(row);
    }

    @Test
    @DisplayName("Row with name and phone (no email) ends up in validRows without duplicate check")
    void validRowWithPhoneOnlyEndsInValidRows() {
        // contactRepository should NOT be called when email is absent
        BulkLeadRowDTO row = BulkLeadRowDTO.builder()
                .rowNumber(1)
                .name("Bob")
                .phone("9876543210")
                .build();

        BulkLeadValidator.ValidationResult result =
                validator.validate(List.of(row), null, TENANT_ID);

        assertThat(result.errors()).isEmpty();
        assertThat(result.validRows()).hasSize(1);
    }

    // ── 6. Mix of valid and invalid rows ─────────────────────────────────

    @Test
    @DisplayName("Mixed batch is split correctly into validRows and errors")
    void mixedBatchIsSplitCorrectly() {
        when(contactRepository.existsByEmailAndTenant_Id(any(), any())).thenReturn(false);

        BulkLeadRowDTO valid1 = BulkLeadRowDTO.builder()
                .rowNumber(1).name("Alice").email("alice@example.com").build();

        BulkLeadRowDTO noName = BulkLeadRowDTO.builder()
                .rowNumber(2).name(null).email("bob@example.com").build();

        BulkLeadRowDTO valid2 = BulkLeadRowDTO.builder()
                .rowNumber(3).name("Charlie").phone("9876543210").build();

        BulkLeadRowDTO noContact = BulkLeadRowDTO.builder()
                .rowNumber(4).name("Dave").email(null).phone(null).build();

        BulkLeadValidator.ValidationResult result =
                validator.validate(List.of(valid1, noName, valid2, noContact), null, TENANT_ID);

        // Two valid rows
        assertThat(result.validRows()).hasSize(2);
        assertThat(result.validRows()).containsExactly(valid1, valid2);

        // Two errors
        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors()).extracting(RowErrorDTO::getRowNumber).containsExactly(2, 4);
        assertThat(result.errors()).extracting(RowErrorDTO::getReason)
                .containsExactly("name is required", "email or phone is required");
    }

    // ── 7. No custom rules (null extraRequiredFields) ─────────────────────

    @Test
    @DisplayName("Null extraRequiredFields skips custom-rule check; valid row passes")
    void nullExtraRequiredFieldsSkipsCustomRules() {
        when(contactRepository.existsByEmailAndTenant_Id(any(), any())).thenReturn(false);

        // source is absent — but no extra rules are configured, so this must still pass
        BulkLeadRowDTO row = BulkLeadRowDTO.builder()
                .rowNumber(1)
                .name("Alice")
                .email("alice@example.com")
                .source(null)
                .build();

        BulkLeadValidator.ValidationResult result =
                validator.validate(List.of(row), null, TENANT_ID);

        assertThat(result.errors()).isEmpty();
        assertThat(result.validRows()).hasSize(1);
    }

    @Test
    @DisplayName("Empty extraRequiredFields list also skips custom-rule check")
    void emptyExtraRequiredFieldsSkipsCustomRules() {
        when(contactRepository.existsByEmailAndTenant_Id(any(), any())).thenReturn(false);

        BulkLeadRowDTO row = BulkLeadRowDTO.builder()
                .rowNumber(1)
                .name("Alice")
                .email("alice@example.com")
                .build();

        BulkLeadValidator.ValidationResult result =
                validator.validate(List.of(row), List.of(), TENANT_ID);

        assertThat(result.errors()).isEmpty();
        assertThat(result.validRows()).hasSize(1);
    }
}
