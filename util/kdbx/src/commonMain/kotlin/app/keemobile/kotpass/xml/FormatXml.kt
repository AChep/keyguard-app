package app.keemobile.kotpass.xml

internal object FormatXml {
    object Tags {
        const val Document = "KeePassFile"
        const val Root = "Root"
        const val Uuid = "UUID"

        object Meta {
            const val TagName = "Meta"
            const val Generator = "Generator"
            const val HeaderHash = "HeaderHash"
            const val SettingsChanged = "SettingsChanged"
            const val DatabaseName = "DatabaseName"
            const val DatabaseNameChanged = "DatabaseNameChanged"
            const val DatabaseDescription = "DatabaseDescription"
            const val DatabaseDescriptionChanged = "DatabaseDescriptionChanged"
            const val DefaultUserName = "DefaultUserName"
            const val DefaultUserNameChanged = "DefaultUserNameChanged"
            const val MaintenanceHistoryDays = "MaintenanceHistoryDays"
            const val Color = "Color"
            const val MasterKeyChanged = "MasterKeyChanged"
            const val MasterKeyChangeRec = "MasterKeyChangeRec"
            const val MasterKeyChangeForce = "MasterKeyChangeForce"
            const val RecycleBinEnabled = "RecycleBinEnabled"
            const val RecycleBinUuid = "RecycleBinUUID"
            const val RecycleBinChanged = "RecycleBinChanged"
            const val EntryTemplatesGroup = "EntryTemplatesGroup"
            const val EntryTemplatesGroupChanged = "EntryTemplatesGroupChanged"
            const val HistoryMaxItems = "HistoryMaxItems"
            const val HistoryMaxSize = "HistoryMaxSize"
            const val LastSelectedGroup = "LastSelectedGroup"
            const val LastTopVisibleGroup = "LastTopVisibleGroup"

            object Binaries {
                const val TagName = "Binaries"
                const val Item = "Binary"
            }

            object MemoryProtection {
                const val TagName = "MemoryProtection"
                const val ProtectTitle = "ProtectTitle"
                const val ProtectUserName = "ProtectUserName"
                const val ProtectPassword = "ProtectPassword"
                const val ProtectUrl = "ProtectURL"
                const val ProtectNotes = "ProtectNotes"
            }

            object CustomIcons {
                const val TagName = "CustomIcons"
                const val Item = "Icon"
                const val ItemUuid = Uuid
                const val ItemData = "Data"
                const val ItemName = "Name"
            }
        }

        object Group {
            const val TagName = "Group"
            const val Name = "Name"
            const val Notes = "Notes"
            const val IconId = "IconID"
            const val CustomIconId = "CustomIconUUID"
            const val Tags = "Tags"
            const val IsExpanded = "IsExpanded"
            const val DefaultAutoTypeSequence = "DefaultAutoTypeSequence"
            const val EnableAutoType = "EnableAutoType"
            const val EnableSearching = "EnableSearching"
            const val LastTopVisibleEntry = "LastTopVisibleEntry"
            const val PreviousParentGroup = "PreviousParentGroup"
        }

        object Entry {
            const val TagName = "Entry"
            const val IconId = "IconID"
            const val CustomIconId = "CustomIconUUID"
            const val ForegroundColor = "ForegroundColor"
            const val BackgroundColor = "BackgroundColor"
            const val OverrideUrl = "OverrideURL"
            const val Tags = "Tags"
            const val History = "History"
            const val QualityCheck = "QualityCheck"
            const val PreviousParentGroup = "PreviousParentGroup"

            object Fields {
                const val TagName = "String"
                const val ItemKey = "Key"
                const val ItemValue = "Value"
            }

            object BinaryReferences {
                const val TagName = "Binary"
                const val ItemKey = "Key"
                const val ItemValue = "Value"
            }

            object AutoType {
                const val TagName = "AutoType"
                const val Enabled = "Enabled"
                const val Obfuscation = "DataTransferObfuscation"
                const val DefaultSequence = "DefaultSequence"
                const val Association = "Association"
                const val Window = "Window"
                const val KeystrokeSequence = "KeystrokeSequence"
            }
        }

        object CustomData {
            const val TagName = "CustomData"
            const val Item = "Item"
            const val ItemKey = "Key"
            const val ItemValue = "Value"
        }

        object TimeData {
            const val TagName = "Times"
            const val CreationTime = "CreationTime"
            const val LastModificationTime = "LastModificationTime"
            const val LastAccessTime = "LastAccessTime"
            const val ExpiryTime = "ExpiryTime"
            const val Expires = "Expires"
            const val UsageCount = "UsageCount"
            const val LocationChanged = "LocationChanged"
        }

        object DeletedObjects {
            const val TagName = "DeletedObjects"
            const val Object = "DeletedObject"
            const val Time = "DeletionTime"
        }
    }

    object Attributes {
        const val Id = "ID"
        const val Ref = "Ref"
        const val Protected = "Protected"
        const val ProtectedInMemPlainXml = "ProtectInMemory"
        const val Compressed = "Compressed"
    }

    object Values {
        const val True = "True"
        const val False = "False"
        const val Null = "Null"
    }
}
