package com.history.backend.shared.domain;

import com.history.backend.integration.domain.IntegrationProvider;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class CheckpointProviderConverter implements AttributeConverter<IntegrationProvider, String> {

    @Override
    public String convertToDatabaseColumn(IntegrationProvider attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public IntegrationProvider convertToEntityAttribute(String dbData) {
        return dbData == null ? null : IntegrationProvider.fromValue(dbData);
    }
}
