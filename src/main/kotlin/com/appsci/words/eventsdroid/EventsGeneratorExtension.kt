package com.appsci.words.eventsdroid

import org.gradle.api.Project
import org.gradle.api.provider.Property
import java.io.File

class EventsGeneratorExtension {

    val eventsSchemaFileName: Property<File>
    val destPath: Property<File>
    val packageName: Property<String>

    constructor(project: Project) {
        eventsSchemaFileName = project.objects.property(File::class.java)
        destPath = project.objects.property(File::class.java)
        packageName = project.objects.property(String::class.java)
    }
}
