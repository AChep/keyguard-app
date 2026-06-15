package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class CargoCommonExtension @Inject constructor(
    objects: ObjectFactory,
) {
    abstract val sourceDir: DirectoryProperty
    abstract val rustTarget: Property<String>
    abstract val cargoBinaryName: Property<String>
    abstract val packagedBinaryName: Property<String>
    abstract val composeResourceDir: Property<String>
    abstract val compileTaskName: Property<String>
    abstract val cargoTaskName: Property<String>
    abstract val certIdentity: Property<String>
    abstract val platformMacOs: Property<Boolean>
    abstract val platformWindows: Property<Boolean>
    abstract val markExecutable: Property<Boolean>

    val extraSourceInputs: ConfigurableFileCollection = objects.fileCollection()

    init {
        cargoTaskName.convention("cargoBuild")
        markExecutable.convention(true)
    }
}
