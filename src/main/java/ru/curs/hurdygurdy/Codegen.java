package ru.curs.hurdygurdy;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import lombok.val;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;


public abstract class Codegen<T> {

    private final GeneratorParams params;
    private OpenAPI openAPI;
    private final Map<ClassCategory, List<T>> typeSpecs = new EnumMap<>(ClassCategory.class);
    private final List<TypeSpecExtractor<T>> typeSpecExtractors;
    private final TypeDefiner<T> typeDefiner;


    public Codegen(GeneratorParams params, TypeProducersFactory<T> typeProducersFactory) {
        this.params = params;
        typeDefiner = typeProducersFactory.createTypeDefiner(this::addTypeSpec);
        typeSpecExtractors = typeProducersFactory.typeSpecExtractors(typeDefiner);
    }


    private Map<String, SchemaComponentDescriptor> parse(Path sourceFile) throws IOException {
        if (!Files.isReadable(sourceFile)) throw new IllegalArgumentException(
                String.format("File %s is not readable", sourceFile));
        ParseOptions parseOptions = new ParseOptions();
        SwaggerParseResult result = new OpenAPIParser()
                .readContents(Files.readString(sourceFile), null, parseOptions);
        openAPI = result.getOpenAPI();
        if (openAPI == null) {
            throw new IllegalArgumentException(String.join(String.format("%n"), result.getMessages()));
        }
        return builtComponentsTree();
    }

    private Map<String, SchemaComponentDescriptor> builtComponentsTree() {
        Map<String, SchemaComponentDescriptor> nodes = new HashMap<>();
        if (openAPI.getComponents()==null) return Collections.emptyMap();
        var schemas = openAPI.getComponents().getSchemas();
        // First pass: create nodes for each schema
        //noinspection rawtypes
        for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
            val properties = entry.getValue().getProperties();
            //noinspection unchecked
            val sourceProps = properties == null ? new HashSet<String>() : new HashSet<String>(properties.keySet());
            //noinspection unchecked,rawtypes
            val props = (entry.getValue() == null || entry.getValue().getAllOf() == null)
                    ? null
                    : ((List<Schema>) entry.getValue().getAllOf())
                    .stream()
                    .filter(schema -> schema instanceof ObjectSchema)
                    .map(schema -> ((ObjectSchema) schema).getProperties())
                    .filter(Objects::nonNull)
                    .flatMap(x -> x.keySet().stream())
                    .collect(Collectors.toSet());
            if (props != null) {
                sourceProps.addAll(props);
            }

            nodes.put(entry.getKey(), new SchemaComponentDescriptor(entry.getKey(), sourceProps));
        }

        // Second pass: add inheritance relationships
        for (var entry : schemas.entrySet()) {
            var schema = entry.getValue();
            if (schema.getAllOf() != null) {
                for (var subSchema : schema.getAllOf()) {
                    Schema<?> subSchema1 = (Schema<?>) subSchema;
                    if (subSchema1.get$ref() != null) {
                        String reference = subSchema1.get$ref();
                        // Reference is in format "#/components/schemas/BaseComponent"
                        String baseSchemaName = reference.split("/")[3];
                        nodes.get(entry.getKey()).addBaseSchema(nodes.get(baseSchemaName));
                    }
                }
            }
        }
        return nodes;
    }

    public void generate(Path sourceFile, Path resultDirectory) throws IOException {
        val componentTree = parse(sourceFile);

        if (!Files.isDirectory(resultDirectory)) throw new IllegalArgumentException(
                String.format("File %s is not a directory", resultDirectory));

        typeDefiner.init(sourceFile);
        typeSpecExtractors.forEach(e -> e.extractTypeSpecs(openAPI, this::addTypeSpec, componentTree));
        generate(resultDirectory);
    }


    void generate(Path resultDirectory) throws IOException {
        for (Map.Entry<ClassCategory, List<T>> typeSpecsEntry : typeSpecs.entrySet()) {
            for (T typeSpec : typeSpecsEntry.getValue()) {
                final String packageName = String.join(".", params.getRootPackage(),
                        typeSpecsEntry.getKey().getPackageName());
                writeFile(resultDirectory, packageName, typeSpec);
            }
        }
    }

    public void addTypeSpec(ClassCategory classCategory, T typeSpec) {
        List<T> specList = this.typeSpecs.computeIfAbsent(classCategory, n -> new ArrayList<>());
        specList.add(typeSpec);
    }

    abstract void writeFile(Path resultDirectory, String packageName, T typeSpec) throws IOException;
}
