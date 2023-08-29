package ru.curs.hurdygurdy

import com.squareup.kotlinpoet.*
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import org.springframework.web.bind.annotation.*
import java.util.*
import javax.servlet.http.HttpServletResponse
import kotlin.reflect.KClass
import kotlin.streams.asSequence

class KotlinAPIExtractor(
    typeDefiner: TypeDefiner<TypeSpec>,
    params: GeneratorParams
) :
    APIExtractor<TypeSpec, TypeSpec.Builder>(
        typeDefiner,
        params,
        TypeSpec::interfaceBuilder,
        TypeSpec.Builder::build
    ) {

    public override fun buildMethod(
        openAPI: OpenAPI,
        classBuilder: TypeSpec.Builder,
        stringPathItemEntry: Map.Entry<String, PathItem>,
        operationEntry: Map.Entry<PathItem.HttpMethod, Operation>,
        operationId: String,
        generateResponseParameter: Boolean,
        componentTree: Map<String, SchemaComponentDescriptor>
    ) {
        val methodBuilder = FunSpec
            .builder(operationId)
            .addModifiers(KModifier.PUBLIC, KModifier.ABSTRACT)
        getControllerMethodAnnotationSpec(operationEntry, stringPathItemEntry.key)?.let(methodBuilder::addAnnotation)
        //we are deriving the returning type from the schema of the successful result
        methodBuilder.returns(determineReturnKotlinType(operationEntry.value, openAPI, classBuilder, componentTree))
        Optional.ofNullable(operationEntry.value.requestBody)
            .map { obj: RequestBody -> obj.content }
            .stream().asSequence()
            .flatMap { getContentType(it, openAPI, classBuilder, componentTree) }
            .forEach { paramSpec: RequestPartParams ->
                methodBuilder.addParameter(
                    ParameterSpec.builder(
                        paramSpec.name,
                        paramSpec.typeName
                    ).addAnnotation(paramSpec.annotation).build()
                )
            }

        getParameterStream(stringPathItemEntry.value, operationEntry.value)
            .filter { parameter: Parameter ->
                "path".equals(
                    parameter.getIn(),
                    ignoreCase = true
                )
            }
            .forEach { parameter: Parameter ->
                methodBuilder.addParameter(
                    ParameterSpec.builder(
                        CaseUtils.snakeToCamel(parameter.name),
                        typeDefiner.defineKotlinType(parameter.schema, openAPI, classBuilder, null, componentTree),
                    )
                        .addAnnotation(
                            AnnotationSpec.builder(PathVariable::class)
                                .addMember("name = %S", parameter.name).build()
                        )
                        .build()
                )
            }
        getParameterStream(stringPathItemEntry.value, operationEntry.value)
            .filter { parameter: Parameter ->
                "query".equals(
                    parameter.getIn(),
                    ignoreCase = true
                )
            }
            .forEach { parameter: Parameter ->
                val builder = AnnotationSpec.builder(RequestParam::class)
                    .addMember("required = %L", parameter.required)
                    .addMember("name = %S", parameter.name)
                parameter.schema?.default?.let { builder.addMember("defaultValue = %S", it.toString()) }
                val annotationSpec = builder.build()
                val typeName =
                    typeDefiner.defineKotlinType(
                        parameter.schema,
                        openAPI,
                        classBuilder,
                        null,
                        componentTree
                    )
                methodBuilder.addParameter(
                    ParameterSpec.builder(CaseUtils.snakeToCamel(parameter.name), typeName)
                        .apply {
                            if (parameter.required == false) defaultValue("%L", null)
                            parameter.schema?.default?.let {
                                defaultValue(
                                    if (typeName.copy(nullable = false) == String::class.asTypeName()) "%S" else "%L",
                                    it.toString()
                                )
                            }
                        }
                        .addAnnotation(
                            annotationSpec
                        ).build()
                )
            }
        JavaAPIExtractor.getParameterStream(stringPathItemEntry.value, operationEntry.value)
            .filter { parameter: Parameter ->
                "header".equals(
                    parameter.getIn(),
                    ignoreCase = true
                )
            }
            .forEach { parameter: Parameter ->
                methodBuilder.addParameter(
                    ParameterSpec.builder(
                        CaseUtils.kebabToCamel(parameter.name),
                        typeDefiner.defineKotlinType(parameter.schema, openAPI, classBuilder, null, componentTree),
                    )
                        .addAnnotation(
                            AnnotationSpec.builder(
                                RequestHeader::class
                            ).addMember("required = %L", parameter.required)
                                .addMember("name = %S", parameter.name).build()
                        ).build()
                )
            }
        if (generateResponseParameter) {
            methodBuilder.addParameter(
                ParameterSpec.builder(
                    "response",
                    HttpServletResponse::class,
                ).build()
            )
        }
        classBuilder.addFunction(methodBuilder.build())
    }

    private fun getControllerMethodAnnotationSpec(
        operationEntry: Map.Entry<PathItem.HttpMethod, Operation>,
        path: String
    ): AnnotationSpec? {
        val annotationClass: KClass<out Annotation>? = when (operationEntry.key) {
            PathItem.HttpMethod.GET -> GetMapping::class
            PathItem.HttpMethod.POST -> PostMapping::class
            PathItem.HttpMethod.PUT -> PutMapping::class
            PathItem.HttpMethod.PATCH -> PatchMapping::class
            PathItem.HttpMethod.DELETE -> DeleteMapping::class
            else -> null
        }
        return if (annotationClass != null) {
            val builder = AnnotationSpec.builder(annotationClass).addMember("value = [%S]", path)
            getSuccessfulReply(operationEntry.value)
                .flatMap(::getMediaType)
                .map { it.key }
                .ifPresent { builder.addMember("produces = [%S]", it) }

            Optional.ofNullable(operationEntry.value.requestBody)
                .map { it.content }
                .flatMap(::getMediaType)
                .map { it.key }
                .filter { it.isNotBlank() && it != "application/json" }
                .ifPresent { builder.addMember("consumes = [%S]", it) }
            builder.build()
        } else null
    }

    private fun determineReturnKotlinType(
        operation: Operation,
        openAPI: OpenAPI,
        parent: TypeSpec.Builder,
        componentTree: Map<String, SchemaComponentDescriptor>
    ): TypeName =
        getSuccessfulReply(operation)
            .stream().asSequence()
            .flatMap { c: Content ->
                getContentType(c, openAPI, parent, componentTree)
            }
            .map { it.typeName }
            .firstOrNull() ?: UNIT

    private data class RequestPartParams(
        val typeName: TypeName,
        val name: String,
        val annotation: AnnotationSpec
    )

    private fun getContentType(
        content: Content,
        openAPI: OpenAPI,
        parent: TypeSpec.Builder,
        componentTree: Map<String, SchemaComponentDescriptor>
    ): Sequence<RequestPartParams> {
        val mediaTypeEntry = Optional.ofNullable(content)
            .flatMap { getMediaType(it) }
        if (mediaTypeEntry.isEmpty) {
            return sequenceOf()
        } else {
            val entry = mediaTypeEntry.get()
            if ("multipart/form-data".equals(entry.key, ignoreCase = true)) {
                //Multipart
                return entry.value.schema?.properties?.asSequence().orEmpty()
                    .map { (name, schema) ->
                        RequestPartParams(
                            name = name,
                            typeName = typeDefiner.defineKotlinType(schema, openAPI, parent, null, componentTree),
                            annotation = AnnotationSpec.builder(RequestPart::class)
                                .addMember("name = %S", name).build()
                        )
                    }

            } else {
                //Single-part
                return Optional.ofNullable(entry.value.schema).stream().asSequence()
                    .map { typeDefiner.defineKotlinType(it, openAPI, parent, null, componentTree) }
                    .map {
                        RequestPartParams(
                            name = "request",
                            typeName = it,
                            annotation = AnnotationSpec
                                .builder(org.springframework.web.bind.annotation.RequestBody::class)
                                .build()
                        )
                    }
            }
        }
    }
}