package com.example.traceability.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OrderNoteCreateRequest(
        @NotBlank @Size(max = 500) String noteText,
        @NotBlank String statusAt,
        Integer isTerminalVisible
) {
}
