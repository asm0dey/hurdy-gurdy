package ru.curs.hurdygurdy;

import io.swagger.v3.oas.models.OpenAPI;

import java.util.Map;
import java.util.function.BiConsumer;

public interface TypeSpecExtractor<T> {
    void extractTypeSpecs(OpenAPI openAPI,
                          BiConsumer<ClassCategory, T> typeSpecBiConsumer,
                          Map<String, SchemaComponentDescriptor> parse);
}
