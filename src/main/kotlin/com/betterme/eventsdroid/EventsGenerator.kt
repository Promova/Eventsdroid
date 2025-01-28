package com.betterme.eventsdroid

import com.betterme.eventsdroid.entities.AnalyticsCategoryEntity
import com.betterme.eventsdroid.entities.EventParameterEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import java.io.File

class EventsGenerator(
        private val gson: Gson,
        private val destFilePath: File,
        private val packageName: String
) {

    companion object {
        private const val BASE_EVENT_CLASS_NAME = "BaseEvent"
    }

    fun generateEventsClasses(schemaFile: File) {
        val jsonString = readFile(schemaFile)

        val analyticsCategories = gson.fromJson<List<AnalyticsCategoryEntity>>(jsonString)
        analyticsCategories.forEach { eventEntity ->
            val categoryName = eventEntity.categoryName
            val formattedClassName = getFormattedEventSetClassName(categoryName)
            val className = ClassName("", formattedClassName)
            val rootObjectBuilder = TypeSpec.objectBuilder(formattedClassName)

            val predefinedValuesSet = mutableSetOf<String>()

            eventEntity.events.forEach { event ->
                var eventNames = mutableListOf<String>()

                val eventName = event.eventName
                var eventClassName = getFormattedEventClassName(eventName)
                eventNames.add(eventClassName)

                eventNames
                    .count { it == eventClassName }
                    .takeIf { it <= 1 }
                    ?.let {
                        eventClassName += "V$it"
                    }

                val parameters = event.parameters
                val parametersNames = parameters.map { it.param }


                if (parametersNames.isEmpty()) {
                    // Otherwise, plain Event object with empty custom parameters map will be
                    // generated for this event.
                    val eventObjectBuilder = createEventObjectBuilder(eventClassName, categoryName,
                            eventName)

                    rootObjectBuilder.addType(eventObjectBuilder.build())
                } else {
                    // If there are many parameters defined, data class with custom fields will be generated.
                    val eventDataClassBuilder = createEventDataClassBuilder(parameters,
                            eventClassName, categoryName, eventName)

                    rootObjectBuilder.addType(eventDataClassBuilder.build())
                }

                parameters.filter { it.value.isNotEmpty() && it.value != "null" }.forEach {
                    predefinedValuesSet.add(it.value)
                }
            }

            if (predefinedValuesSet.isNotEmpty()) {
                val predefinedValuesObjectBuilder = createPredefinedValuesObjectBuilder(predefinedValuesSet)
                rootObjectBuilder.addType(predefinedValuesObjectBuilder.build())
            }

            val eventsFile = FileSpec.builder("$packageName.${categoryName.toLowerCase().replace("_", "")}", "$className")
                    .addType(rootObjectBuilder.build())
                    .build()
            eventsFile.writeTo(destFilePath)
        }
    }

    fun generateBaseEventClass() {
        val baseEventClassBuilder = TypeSpec.classBuilder("BaseEvent").apply {
            primaryConstructor(FunSpec.constructorBuilder()
                    .addParameter("categoryName", String::class)
                    .addParameter("eventName", String::class)
                    .addParameter("params", Map::class.parameterizedBy(String::class, String::class))
                    .build())
            addModifiers(KModifier.OPEN)
            addProperty(PropertySpec.builder("categoryName", String::class)
                    .initializer("categoryName")
                    .build())
            addProperty(PropertySpec.builder("eventName", String::class)
                    .initializer("eventName")
                    .build())
            addProperty(PropertySpec.builder("params", Map::class.parameterizedBy(String::class, String::class))
                    .initializer("params")
                    .build()
            )
        }
        val file = FileSpec.builder(packageName, BASE_EVENT_CLASS_NAME)
                .addType(baseEventClassBuilder.build())
                .build()
        file.writeTo(destFilePath)
    }

    private fun createEventObjectBuilder(
            eventClassName: String,
            categoryName: String,
            eventName: String
    ): TypeSpec.Builder {

        val eventObjectBuilder = TypeSpec.objectBuilder(eventClassName)

        return eventObjectBuilder
                .superclass(ClassName(packageName, BASE_EVENT_CLASS_NAME))
                .addSuperclassConstructorParameter("%S", categoryName)
                .addSuperclassConstructorParameter("%S", eventName)
                .addSuperclassConstructorParameter("emptyMap()")
    }

    private fun createEventDataClassBuilder(
            parameters: List<EventParameterEntity>,
            eventClassName: String,
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
            val formattedParamName = getFormattedParameterName(field.param)

            // Add custom parameter to event constructor's signature.
            customParamsConstructorBuilder.addParameter(formattedParamName, String::class)

            // Provide specification for this custom parameter.
            val eventParamSpec = PropertySpec.builder(formattedParamName, String::class)
                    .initializer(formattedParamName)
                    .build()
            eventDataClassBuilder.addProperty(eventParamSpec)

            // Add custom parameters to map.
            customEventParamsBuilder.add("%S to %N", field.param, eventParamSpec)
            if (index < eventParamsSet.size - 1) {
                // Separate all pairs except for the last one with comma.
                customEventParamsBuilder.add(", ")
            }
        }
        customEventParamsBuilder.add(")")

        return eventDataClassBuilder
                .addModifiers(KModifier.DATA)
                .primaryConstructor(customParamsConstructorBuilder.build())
                .superclass(ClassName(packageName, BASE_EVENT_CLASS_NAME))
                .addSuperclassConstructorParameter("%S", categoryName)
                .addSuperclassConstructorParameter("%S", eventName)
                .addSuperclassConstructorParameter(customEventParamsBuilder.build())
    }

    private fun createPredefinedValuesObjectBuilder(predefinedValues: Set<String>): TypeSpec.Builder {
        val valuesObjectBuilder = TypeSpec.objectBuilder("Values")
        predefinedValues
                .forEach { predefinedValue ->
                    valuesObjectBuilder
                            .addProperty(PropertySpec.builder(getPredefinedValueVariableName(predefinedValue), String::class)
                                    .addModifiers(KModifier.CONST)
                                    .initializer("%S", predefinedValue)
                                    .build())
                }
        return valuesObjectBuilder
    }

    private fun readFile(file: File): String = file.readText(Charsets.UTF_8)

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

    private fun getPredefinedValueVariableName(paramValue: String) =
            paramValue.toUpperCase().replace(" ", "_")

    inline fun <reified T> Gson.fromJson(json: String) =
            this.fromJson<T>(json, object : TypeToken<T>() {}.type)

}
