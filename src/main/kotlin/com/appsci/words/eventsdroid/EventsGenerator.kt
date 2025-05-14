package com.appsci.words.eventsdroid

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import java.io.File

data class EventGroup(
    val name: String,
    val events: List<Event>,
)

data class Event(
    val name: String,
    val customClassName: String?,
    val description: String?,
    val parameters: List<Parameter>,
)

data class Parameter(
    val name: String,
    val nullable: Boolean,
    val description: String?,
)

class EventsGenerator(
    private val destFilePath: File,
    private val packageName: String
) {

    companion object {
        private const val BASE_EVENT_CLASS_NAME = "BaseEvent"
    }

    private fun parseCsvFile(file: File): List<EventGroup> {
        return file
            .readLines()
            .asSequence()
            .drop(1) // drop first line with column names
            .map { it.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)".toRegex()) } // split by a cleaver regexp
            .filter {
                // category and name are mandatory
                !it.getOrNull(0).isNullOrBlank() && !it.getOrNull(2).isNullOrBlank()
            }
            .groupBy { it.first() }
            .map { eventGroup ->
                val eventGroupName = eventGroup.key
                val events = eventGroup.value.map { events ->
                    val customName = events.getOrNull(1)?.takeIf { it.isNotBlank() }
                    val eventName = events[2]
                    val description = events.getOrNull(3)?.takeIf { it.isNotBlank() }

                    val eventParams = events
                        .drop(4)
                        .chunked(2) // take pairs of parameters
                        .mapNotNull { pair ->
                            val name = pair[0]
                            val description = pair.getOrNull(1)

                            val paramName = name.trim()
                            if (paramName.isNotBlank()) {
                                val isNullable = paramName.last() == '?'
                                val paramDescription = description?.trim()?.takeIf { it.isNotBlank() }

                                Parameter(
                                    name = paramName.trimEnd('?'),
                                    nullable = isNullable,
                                    description = paramDescription,
                                )
                            } else {
                                null
                            }
                        }

                    Event(
                        name = eventName,
                        customClassName = customName,
                        description = description,
                        parameters = eventParams,
                    )
                }

                EventGroup(
                    name = eventGroupName,
                    events = events,
                )
            }
    }

    fun generateEventsClasses(schemaFile: File) = runCatching {
        val eventGroups = parseCsvFile(schemaFile)

        eventGroups.forEach { eventGroup ->
            var hasNullableParams = false
            val categoryName = eventGroup.name
            val formattedClassName = getFormattedEventSetClassName(categoryName)

            val className = ClassName("", formattedClassName)
            val rootObjectBuilder = TypeSpec.objectBuilder(formattedClassName)

            val eventNames = mutableListOf<String>()
            eventGroup.events.forEach { event ->

                val eventName = event.name
                var eventClassName = getFormattedEventClassName(event.customClassName ?: event.name)

                val duplicateCount = eventNames.count { it == eventClassName }
                if (duplicateCount > 0) {
                    eventClassName += "V${duplicateCount + 1}"
                }
                eventNames.add(eventClassName)

                val parameters = event.parameters
                hasNullableParams = hasNullableParams || parameters.any { it.nullable }

                if (parameters.isEmpty()) {
                    // Otherwise, plain Event object with empty custom parameters map will be
                    // generated for this event.
                    val eventObjectBuilder = createEventObjectBuilder(
                        eventName = eventName,
                        eventClassName = eventClassName,
                        description = event.description,
                        categoryName = categoryName,
                    )

                    rootObjectBuilder.addType(eventObjectBuilder.build())
                } else {
                    // If there are many parameters defined, data class with custom fields will be generated.
                    val eventDataClassBuilder = createEventDataClassBuilder(
                        eventName = eventName,
                        eventClassName = eventClassName,
                        description = event.description,
                        categoryName = categoryName,
                        parameters = parameters,
                    )

                    rootObjectBuilder.addType(eventDataClassBuilder.build())
                }
            }

            val generalPackageName = packageName
            val eventsFile = FileSpec
                .builder(
                    packageName = "$packageName.${categoryName.lowercase()}",
                    fileName = "$className"
                )
                .apply {
                    if (hasNullableParams) addImport(generalPackageName, "orNone")
                }
                .addType(rootObjectBuilder.build())
                .build()
            eventsFile.writeTo(destFilePath)
        }
    }.onFailure { e ->
        println("Error generating event classes: ${e.message}")
        e.printStackTrace()
    }

    fun generateBaseEventFile() {
        val baseEventClassBuilder = TypeSpec.classBuilder("BaseEvent").apply {
            primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("categoryName", String::class)
                    .addParameter("eventName", String::class)
                    .addParameter(
                        "params",
                        Map::class.parameterizedBy(String::class, String::class)
                    )
                    .build()
            )
            addModifiers(KModifier.OPEN)
            addProperty(
                PropertySpec.builder("categoryName", String::class)
                    .initializer("categoryName")
                    .build()
            )
            addProperty(
                PropertySpec.builder("eventName", String::class)
                    .initializer("eventName")
                    .build()
            )
            addProperty(
                PropertySpec
                    .builder(
                        "params",
                        Map::class.parameterizedBy(String::class, String::class)
                    )
                    .initializer("params")
                    .build()
            )
        }

        val defaultValueFunction = FunSpec.builder("orNone")
            .receiver(String::class.asTypeName().copy(nullable = true))
            .addStatement("return this ?: \"none\"")
            .returns(String::class)

        val file = FileSpec.builder(packageName, BASE_EVENT_CLASS_NAME)
            .addType(baseEventClassBuilder.build())
            .addFunction(defaultValueFunction.build())
            .build()
        file.writeTo(destFilePath)
    }

    private fun createEventObjectBuilder(
        eventClassName: String,
        eventName: String,
        description: String?,
        categoryName: String,
    ): TypeSpec.Builder {

        val eventObjectBuilder = TypeSpec.objectBuilder(eventClassName)

        return eventObjectBuilder
            .apply {
                if (description != null) addKdoc("%L\n", description)
            }
            .superclass(ClassName(packageName, BASE_EVENT_CLASS_NAME))
            .addSuperclassConstructorParameter("%S", categoryName)
            .addSuperclassConstructorParameter("%S", eventName)
            .addSuperclassConstructorParameter("emptyMap()")
    }

    private fun createEventDataClassBuilder(
        parameters: List<Parameter>,
        eventClassName: String,
        description: String?,
        categoryName: String,
        eventName: String
    ): TypeSpec.Builder {

        val eventDataClassBuilder = TypeSpec.classBuilder(eventClassName)

        // Initialize the builder of event data class constructor which accepts custom parameters as arguments.
        val customParamsConstructorBuilder = FunSpec.constructorBuilder()

        // Generate map of custom parameters
        val customEventParamsBuilder = CodeBlock.builder()
        customEventParamsBuilder.add("mapOf(")

        val eventParamsSet = parameters.toSet()

        eventParamsSet.forEachIndexed { index, field ->
            val formattedParamName = getFormattedParameterName(field.name)

            // Add custom parameter to event constructor's signature.
            val type = String::class.asTypeName().copy(nullable = field.nullable)
            customParamsConstructorBuilder.addParameter(formattedParamName, type)

            // Provide specification for this custom parameter.
            val eventParamSpec = PropertySpec.builder(formattedParamName, type)
                .apply {
                    if (field.description != null) addKdoc("%L\n", field.description)
                }
                .initializer(formattedParamName)
                .build()
            eventDataClassBuilder.addProperty(eventParamSpec)

            // Add custom parameters to map.
            if (field.nullable) {
                customEventParamsBuilder.add("%S to %N.orNone()", field.name, eventParamSpec)
            } else {
                customEventParamsBuilder.add("%S to %N", field.name, eventParamSpec)
            }
            if (index < eventParamsSet.size - 1) {
                // Separate all pairs except for the last one with comma.
                customEventParamsBuilder.add(", ")
            }
        }
        customEventParamsBuilder.add(")")

        return eventDataClassBuilder
            .apply {
                if (description != null) addKdoc("%L\n", description)
            }
            .addModifiers(KModifier.DATA)
            .primaryConstructor(customParamsConstructorBuilder.build())
            .superclass(ClassName(packageName, BASE_EVENT_CLASS_NAME))
            .addSuperclassConstructorParameter("%S", categoryName)
            .addSuperclassConstructorParameter("%S", eventName)
            .addSuperclassConstructorParameter(customEventParamsBuilder.build())
    }

    private fun getFormattedEventSetClassName(categoryName: String): String {
        return categoryName
            .split("_")
            .asSequence()
            .map { it.capitalize() }
            .joinToString(postfix = "Events", separator = "")
    }

    private fun getFormattedEventClassName(eventName: String): String {
        return eventName
            .split("_")
            .asSequence()
            .map { it.capitalize() }
            .joinToString(separator = "", postfix = "Event")
    }

    private fun getFormattedParameterName(paramName: String): String {
        return paramName
            .split("_")
            .asSequence()
            .mapIndexed { index: Int, param: String ->
                if (index > 0) param.capitalize() else param
            }
            .joinToString(separator = "")
    }
}
