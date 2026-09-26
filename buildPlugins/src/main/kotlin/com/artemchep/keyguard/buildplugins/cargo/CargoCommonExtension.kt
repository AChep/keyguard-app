package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class CargoCommonExtension @Inject constructor(
    private val project: Project,
    objects: ObjectFactory,
) {
    abstract val sourceDir: DirectoryProperty
    abstract val rustTarget: Property<String>
    abstract val cargoBinaryName: Property<String>
    abstract val packagedBinaryName: Property<String>
    abstract val composeResourceDir: Property<String>
    abstract val certIdentity: Property<String>
    abstract val platformMacOs: Property<Boolean>
    abstract val platformWindows: Property<Boolean>
    abstract val markExecutable: Property<Boolean>
    abstract val cargoPackage: Property<String>
    abstract val cargoArguments: ListProperty<String>
    abstract val environmentVariables: MapProperty<String, String>

    val extraSourceInputs: ConfigurableFileCollection = objects.fileCollection()

    private var registered = false

    /**
     * Registers this project's binary with stable task names. File locations and build options
     * remain connected to the extension, so they can still be configured after registration.
     */
    fun register(
        compileTaskName: String,
        cargoTaskName: String = "cargoBuild",
    ): CargoTasks {
        check(!registered) { "The Cargo binary for ${project.path} is already registered" }
        registered = true
        return project.registerCargoTasks(this, compileTaskName, cargoTaskName)
    }

    init {
        markExecutable.convention(true)
        cargoArguments.convention(emptyList())
        environmentVariables.convention(emptyMap())
    }
}
