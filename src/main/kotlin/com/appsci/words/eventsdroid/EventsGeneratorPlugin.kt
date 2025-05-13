package com.appsci.words.eventsdroid

import org.gradle.api.Plugin
import org.gradle.api.Project

open class EventsGeneratorPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.tasks.create("generateEvents", EventsGeneratorTask::class.java)
    }

}