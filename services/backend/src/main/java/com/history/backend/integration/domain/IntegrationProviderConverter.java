package com.history.backend.integration.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class IntegrationProviderConverter implements AttributeConverter<IntegrationProvider, String> {

    @Override
    public String convertToDatabaseColumn(IntegrationProvider attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public IntegrationProvider convertToEntityAttribute(String dbData) {
        return dbData == null ? null : IntegrationProvider.fromValue(dbData);
    }
}
