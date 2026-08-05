package com.history.backend.integration.dto;

import com.history.backend.integration.service.SelectionOption;

public record SelectionOptionResponse(String value, String label) {

    public static SelectionOptionResponse from(SelectionOption option) {
        return new SelectionOptionResponse(option.value(), option.label());
    }
}
